package minicode.prompt;

import minicode.mcp.McpServerStatus;
import minicode.mcp.McpServerSummary;
import minicode.skills.SkillSummary;
import minicode.tools.api.Tool;
import minicode.tools.registry.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 把"身份、规则、工具、Skill、MCP、AGENTS.md"等动态信息拼装成发给 LLM 的 system prompt。
 *
 * <p>对 Coding Agent 而言 system prompt 是 LLM 的唯一编程接口：要做什么、不做什么、
 * 用什么工具、走什么协议，全靠这段 ~3000 字文本。本类把 prompt 切成 10 段（身份、cwd、
 * 通用规则、AGENTS.md 入口、工具清单、Skill 清单、MCP 清单、权限边界、progress/final 协议、
 * 各工具使用细则），用 {@link StringJoiner} 以双换行分隔，便于 LLM 按 Markdown 段落理解。</p>
 *
 * <p>调用关系：每次 Turn 开始前由 ApplicationServices.withFreshSystemPrompt 重新调用
 * {@link #build(Input)} 构建，结果作为 SystemMessage 放入对话首位发给 Anthropic API。
 * 之所以"每次都重建而不缓存"，是因为 cwd、工具列表、Skill、MCP 状态、AGENTS.md 内容
 * 都可能在 Turn 之间变化；构建本身 &lt;5ms，相比一次 HTTP 调用可忽略。</p>
 *
 * <p>关键设计取舍：</p>
 * <ul>
 *   <li>用 Java 21 Text Block 写多行规则段，比字符串拼接可读得多。</li>
 *   <li>动态段（tools/skills/mcp）和静态段同等对待，全部走同一个 joiner，便于扩展。</li>
 *   <li>AGENTS.md 通过 {@link #maybeRead} 在末尾追加，让用户无需改代码即可影响 LLM 行为。</li>
 * </ul>
 */
public final class SystemPromptBuilder {
    /**
     * 构建一次 system prompt 所需的全部动态上下文。
     *
     * <p>紧凑构造器会把 home / cwd 规范化为绝对路径，并把 skills、mcpServers
     * 拷贝成不可变列表，避免外部后续修改影响已构建的 Input 实例。</p>
     *
     * @param home 用户主目录（用于读取全局 AGENTS.md），构造时会被规范化为绝对路径
     * @param cwd 当前工作目录（用于身份段以及读取项目级 AGENTS.md），构造时会被规范化为绝对路径
     * @param tools 当前可用的工具注册表，用于生成"Available tools"段
     * @param skills 已发现的 Skill 摘要列表，用于生成"Available skills"段
     * @param mcpServers 已配置的 MCP 服务器摘要列表，列表非空时才会出现 MCP 段
     */
    public record Input(Path home, Path cwd, ToolRegistry tools, List<SkillSummary> skills,
                        List<McpServerSummary> mcpServers) {
        /**
         * 便捷构造器：仅指定 home/cwd/tools，skills 和 mcpServers 默认为空列表。
         */
        public Input(Path home, Path cwd, ToolRegistry tools) {
            this(home, cwd, tools, List.of());
        }

        /**
         * 便捷构造器：在 {@link #Input(Path, Path, ToolRegistry)} 基础上额外指定 skills，mcpServers 默认为空。
         */
        public Input(Path home, Path cwd, ToolRegistry tools, List<SkillSummary> skills) {
            this(home, cwd, tools, skills, List.of());
        }

        public Input {
            home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
            cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
            tools = Objects.requireNonNull(tools, "tools");
            skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
            mcpServers = List.copyOf(Objects.requireNonNull(mcpServers, "mcpServers"));
        }
    }

    /**
     * 根据给定上下文构建一段完整的 system prompt 文本。
     *
     * <p>输出按"身份 + cwd + 通用规则 + AGENTS.md 入口 + 工具清单 + Skill 清单 +
     * （可选）MCP 清单 + 权限边界 + progress/final 协议 + 工具使用细则 +
     * 全局 AGENTS.md + 项目 AGENTS.md"的顺序拼接，段落之间用空行分隔。</p>
     *
     * <p>本方法不缓存：每次调用都会重新读取 AGENTS.md 文件并重新生成所有段落，
     * 以保证 cwd 变化、工具/Skill 增减、AGENTS.md 编辑都能即时反映到下一次 LLM 请求。</p>
     *
     * @param input 构建所需的动态上下文，不能为 null
     * @return 拼装好的 system prompt 文本
     */
    public String build(Input input) {
        Objects.requireNonNull(input, "input");
        StringJoiner prompt = new StringJoiner("\n\n");
        prompt.add("""
                You are mini-code, a terminal coding assistant.
                Default behavior: inspect the repository, use tools, make code changes when appropriate, and explain results clearly.
                Prefer reading files, searching code, editing files, and running verification commands over giving purely theoretical advice.
                """.strip());
        prompt.add("Current cwd: " + input.cwd());
        prompt.add("""
                You can inspect or modify paths outside the current cwd when the user asks, but tool permissions may pause for approval first.
                When making code changes, keep them minimal, practical, and working-oriented.
                If the user clearly asked you to build, modify, optimize, or generate something, do the work instead of stopping at a plan.
                When several independent files, searches, or inspections are needed, request them in the same tool-call step instead of one at a time. Keep dependent actions sequential when later calls need earlier results.
                If you need user clarification, call the ask_user tool with one concise question and wait for the user reply. Do not ask clarifying questions as plain assistant text.
                Do not choose subjective preferences such as colors, visual style, copy tone, or naming unless the user explicitly told you to decide yourself.
                When using read_file, pay attention to the header fields. If it says TRUNCATED: yes, continue reading with a larger offset before concluding that the file itself is cut off.
                If the user names a skill or clearly asks for a workflow that matches a listed skill, call load_skill before following it.
                """.strip());
        prompt.add("""
                Project and global instruction entry points:
                - Read and follow project AGENTS.md when present.
                - Read and follow global AGENTS.md when present.
                - Java MiniCode currently uses AGENTS.md for project/global instructions; this is equivalent to the TS reference prompt's CLAUDE.md instruction entry point.
                - Local project instructions override broad global preferences when they conflict.
                """.strip());
        prompt.add(toolSection(input.tools()));
        prompt.add(skillSection(input.skills()));
        mcpSection(input.mcpServers()).ifPresent(prompt::add);
        prompt.add("""
                Permission and edit review boundaries:
                - Sensitive path access, command execution, and file edits may pause for Permission review.
                - Do not claim a denied action succeeded. Adapt to denial feedback and continue when possible.
                - If permission is denied with user feedback or a question, address that feedback before requesting more tools.
                - File writing tools use edit review semantics; preserve exact requested content and avoid unrelated rewrites.
                """.strip());
        prompt.add("""
                Structured response protocol:
                - Use <progress> for brief, concrete status updates during multi-step work, especially before or between tool batches, searches, edits, long commands, and verification.
                - Use <progress> only when you are still working and will continue with more tool calls or reasoning.
                - Keep <progress> concise; report what you are doing or what you found.
                - Use <final> only when the task is actually complete and control should return to the user.
                - After <progress>, continue immediately in the next step. Do not stop at a progress note.
                - Plain assistant text may be treated as a completed assistant message.
                """.strip());
        prompt.add("""
                ask_user rules:
                - Call ask_user only when a concrete user decision is required to continue.
                - Ask one concise question and wait for the tool result.
                - Do not ask required clarification as plain assistant text.
                """.strip());
        prompt.add("""
                read_file rules:
                - Use lineStart and lineCount for 1-based line ranges, especially when following line numbers from grep_files.
                - Use offset and limit only for character chunks, character-based continuation after TRUNCATED, or targeted character ranges.
                - Do not combine lineStart/lineCount with offset/limit.
                - For normal source files and documents, prefer the default chunk or a sufficiently large lineCount instead of tiny character limits.
                - Read the header. If TRUNCATED: yes appears, continue with the indicated next offset or lineStart before concluding content is missing.
                """.strip());
        prompt.add("""
                run_command rules:
                - Prefer explicit argv-style arguments when the tool supports them.
                - single-string commands are supported for compatibility.
                - Shell snippets are limited and permission-controlled; use them only when a real shell expression is needed.
                - On Windows, prefer simple `cmd /c ...` wrappers for simple shell commands.
                - For complex PowerShell, use `powershell -NoProfile -Command ...` and quote the whole command carefully; avoid mixing unescaped PowerShell env var assignments such as `$env:PATH=...` in a nested one-line shell command.
                - When setting JDK/Maven for verification, prefer full executable paths over one-line PowerShell environment mutation.
                - Command results and denials must be fed back into the next model step.
                """.strip());
        prompt.add("""
                Exact-text edit and patch rules:
                - For exact-text replacement, the old text must match the file exactly.
                - If exact text is not found, reread the relevant file range and retry with the exact current text.
                - Keep patch_file/edit_file/modify_file changes scoped to the requested task.
                """.strip());
        prompt.add("""
                Large output rules:
                - Tool results may be replaced with <persisted-output ...> references when large.
                - Treat replacement text as a pointer to stored output, not as the full original output.
                - Continue using available summaries and reread or rerun narrower commands when needed.
                """.strip());
        maybeRead(input.home().resolve("AGENTS.md"), "Global instructions", prompt);
        maybeRead(input.cwd().resolve("AGENTS.md"), "Project instructions", prompt);
        return prompt.toString();
    }

    private String toolSection(ToolRegistry registry) {
        StringBuilder builder = new StringBuilder("Available tools:");
        if (registry.list().isEmpty()) {
            builder.append("\n- none");
            return builder.toString();
        }
        for (Tool tool : registry.list()) {
            builder.append("\n- ")
                    .append(tool.metadata().name())
                    .append(": ")
                    .append(tool.metadata().description())
                    .append("\n  schema: ")
                    .append(tool.inputSchema().toString());
        }
        return builder.toString();
    }

    private String skillSection(List<SkillSummary> skills) {
        StringBuilder builder = new StringBuilder("Available skills:");
        if (skills.isEmpty()) {
            builder.append("\n- none discovered");
            return builder.toString();
        }
        for (SkillSummary skill : skills) {
            builder.append("\n- ")
                    .append(skill.name())
                    .append(": ")
                    .append(truncate(skill.description(), 300));
        }
        return builder.toString();
    }

    private java.util.Optional<String> mcpSection(List<McpServerSummary> mcpServers) {
        if (mcpServers.isEmpty()) {
            return java.util.Optional.empty();
        }
        StringBuilder builder = new StringBuilder("Configured MCP servers:");
        boolean hasConnected = false;
        for (McpServerSummary server : mcpServers) {
            builder.append("\n- ")
                    .append(server.name())
                    .append(": ")
                    .append(server.status().displayName())
                    .append(", tools=")
                    .append(server.toolCount());
            server.error().ifPresent(error -> builder.append(" (").append(truncate(error, 200)).append(")"));
            hasConnected = hasConnected || server.status() == McpServerStatus.CONNECTED;
        }
        if (hasConnected) {
            builder.append("\nConnected MCP tools are exposed in the tool list with names prefixed like mcp__server__tool.");
        }
        return java.util.Optional.of(builder.toString());
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 3) {
            return value.substring(0, maxChars);
        }
        return value.substring(0, maxChars - 3) + "...";
    }

    /**
     * 如果指定路径存在 AGENTS.md（或类似指令文件），将其内容追加到 prompt 末尾。
     *
     * <p>用于把"全局指令"和"项目指令"以纯文本方式注入 system prompt：
     * 文件不存在则静默跳过；读取失败时也不会抛异常，而是把错误信息作为占位段加入，
     * 避免 IO 错误打断整次 prompt 构建。</p>
     *
     * @param path 待读取的 AGENTS.md 路径
     * @param label 段落标签，例如 "Global instructions" 或 "Project instructions"
     * @param prompt 累积 prompt 段落的 joiner，本方法只追加不修改既有段
     */
    private void maybeRead(Path path, String label, StringJoiner prompt) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            prompt.add(label + " from " + path + ":\n" + Files.readString(path));
        } catch (IOException exception) {
            prompt.add(label + " from " + path + " could not be read: " + exception.getMessage());
        }
    }
}
