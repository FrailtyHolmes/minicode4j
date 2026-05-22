package minicode.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;
import minicode.permissions.model.PathIntent;
import minicode.tools.validation.ToolInputValidation;
import minicode.workspace.WorkspacePathException;
import minicode.workspace.WorkspacePathRequest;
import minicode.workspace.WorkspacePathResolver;
import minicode.workspace.WorkspacePathResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.Objects;
import java.util.Set;

/**
 * 读 UTF-8 文本文件的内置工具——是「Tool 接口怎么实现」的标准样板。
 *
 * <p>支持两种读法（互斥，不能混用）：
 * <p>- <b>字符模式</b>：传 {@code offset} / {@code limit}，按字符位置切片；
 * <p>- <b>行模式</b>：传 {@code lineStart} / {@code lineCount}，按 1-based 行号读。
 *
 * <p>典型职责拆分（实现 {@link Tool} 接口的四个方法）：
 * <p>1. {@link #metadata()} 返回 {@code static final} 单例，类加载期就构造好；
 * <p>2. {@link #inputSchema()} 同样是单例，描述入参 JSON 结构发给 LLM；
 * <p>3. {@link #validateInput} 用链式 DSL 声明字段约束，附带跨字段互斥校验；
 * <p>4. {@link #run} 内部分四步走：路径合法化（{@link WorkspacePathResolver}）→ 权限审批
 *    （{@link ReadFilePathAccess}）→ 实际 IO → 切片并拼 header 返回。
 *
 * <p>设计亮点——返回内容里嵌入「下一步操作提示」：当文件被截断时，
 * header 会写明 {@code TRUNCATED: yes - call read_file again with offset N}，
 * 这样 LLM 一眼就知道下次怎么续读，比改 system prompt 教它续读更直接。
 *
 * <p>所有异常都在 {@link #run} 内部 catch 后转 {@link ToolResult#error(String)}，
 * 严格遵循 {@link Tool#run} 的「不抛异常给 ToolRegistry」契约。
 */
public final class ReadFileTool implements Tool {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final int DEFAULT_READ_LIMIT = 12_000;
    private static final int MAX_READ_LIMIT = 20_000;
    private static final int DEFAULT_LINE_COUNT = 200;
    private static final int MAX_LINE_COUNT = 2_000;
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "read_file",
            "Read a UTF-8 text file relative to the current workspace. Use lineStart/lineCount for 1-based line ranges, or offset/limit for character chunks.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.READ),
            ToolStatus.AVAILABLE
    );

    private final ReadFilePathAccess pathAccess;
    private final WorkspacePathResolver workspacePathResolver;

    /**
     * 默认构造器：用「不可用」的 {@link ReadFilePathAccess} 占位 + 默认的路径解析器。
     *
     * <p>主要给非交互场景或者测试用——一旦遇到需要权限审批的越界路径就会拒绝。
     */
    public ReadFileTool() {
        this(ReadFilePathAccess.unavailable(), new WorkspacePathResolver());
    }

    /**
     * 注入自定义 {@link ReadFilePathAccess} 的构造器，路径解析器使用默认实现。
     *
     * @param pathAccess 路径权限审批器，越界路径走它的逻辑（弹窗 / 拒绝）
     */
    public ReadFileTool(ReadFilePathAccess pathAccess) {
        this(pathAccess, new WorkspacePathResolver());
    }

    /**
     * 完全自定义版本——同时注入权限审批器与路径解析器，方便测试时用 fake 替身。
     *
     * @param pathAccess            路径权限审批器
     * @param workspacePathResolver 把相对路径解析成绝对路径并做越界校验的解析器
     */
    public ReadFileTool(ReadFilePathAccess pathAccess, WorkspacePathResolver workspacePathResolver) {
        this.pathAccess = Objects.requireNonNull(pathAccess, "pathAccess");
        this.workspacePathResolver = Objects.requireNonNull(workspacePathResolver, "workspacePathResolver");
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public JsonNode inputSchema() {
        return INPUT_SCHEMA;
    }

    /**
     * 用 {@code ToolInputValidation} 链式 DSL 声明入参约束。
     *
     * <p>字段层约束：
     * <p>- {@code path}：必需，必须是合法路径字符串；
     * <p>- {@code offset}/{@code limit}：可选整数，分别在 [0, MAX_INT] 与 [1, MAX_READ_LIMIT] 范围内；
     * <p>- {@code lineStart}/{@code lineCount}：可选整数，{@code lineCount} 上限是 {@code MAX_LINE_COUNT}。
     *
     * <p>跨字段约束（用 {@code custom(...)} 自定义校验回调）：
     * <p>1. 字符模式（offset/limit）和行模式（lineStart/lineCount）<b>不能混用</b>；
     * <p>2. 传 {@code lineCount} 时必须同时传 {@code lineStart}（否则不知道从哪开始数）。
     */
    @Override
    public ValidationResult validateInput(JsonNode input) {
        return ToolInputValidation.object(input)
                .pathField("path", true)
                .optionalInteger("offset", 0, Integer.MAX_VALUE)
                .optionalInteger("limit", 1, MAX_READ_LIMIT)
                .optionalInteger("lineStart", 1, Integer.MAX_VALUE)
                .optionalInteger("lineCount", 1, MAX_LINE_COUNT)
                .custom((rawInput, builder) -> {
                    boolean hasOffset = builder.normalized().has("offset");
                    boolean hasLimit = builder.normalized().has("limit");
                    boolean hasLineStart = builder.normalized().has("lineStart");
                    boolean hasLineCount = builder.normalized().has("lineCount");
                    boolean charMode = hasOffset || hasLimit;
                    boolean lineMode = hasLineStart || hasLineCount;
                    if (charMode && lineMode) {
                        builder.addError("read_file character mode offset/limit cannot be combined with line mode lineStart/lineCount");
                    }
                    if (hasLineCount && !hasLineStart) {
                        builder.addError("read_file line mode requires lineStart");
                    }
                })
                .build();
    }

    /**
     * 实际读取文件内容并按所选模式切片返回。
     *
     * <p>四步流程：
     * <p>1. 用 {@link WorkspacePathResolver} 把相对路径解析成绝对路径，并校验是否越出 cwd；
     * <p>2. 调 {@link ReadFilePathAccess#ensureReadAllowed} 走权限审批（越界路径会触发审批弹窗）；
     * <p>3. 用 {@link Files#readString} 一次性读完整文件（UTF-8）；
     * <p>4. 按行模式或字符模式切片，拼上带 TRUNCATED 提示的 header 一并返回。
     *
     * <p>所有可预期异常都在此处 catch 转 {@link ToolResult#error(String)}：
     * 文件不存在 → "File not found: ..."；越界 → 解析器自带消息；
     * IO 异常 → "Failed to read file ..."；其它 RuntimeException 兜底成「access denied」。
     *
     * @param normalizedInput 已通过 {@link #validateInput} 校验和规范化的入参
     * @param toolContext     调用上下文，{@link ToolContext#cwd()} 用于解析相对路径
     * @return 成功时 ok 包裹「header + 内容」，失败时 error 包裹友好错误信息
     */
    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
        String inputPath = normalizedInput.get("path").asText();
        boolean lineMode = normalizedInput.has("lineStart") || normalizedInput.has("lineCount");

        try {
            WorkspacePathResult resolvedPath = workspacePathResolver.resolve(new WorkspacePathRequest(
                    toolContext.cwd(),
                    inputPath,
                    PathIntent.READ,
                    true,
                    false
            ));
            pathAccess.ensureReadAllowed(toolContext, resolvedPath.resolvedPath());
            String content = Files.readString(resolvedPath.resolvedPath().normalizedPath(), StandardCharsets.UTF_8);
            if (lineMode) {
                int lineStart = normalizedInput.get("lineStart").asInt();
                int lineCount = normalizedInput.has("lineCount")
                        ? normalizedInput.get("lineCount").asInt()
                        : DEFAULT_LINE_COUNT;
                return ToolResult.ok(lineChunk(inputPath, content, lineStart, lineCount));
            }
            int offset = normalizedInput.has("offset") ? normalizedInput.get("offset").asInt() : 0;
            int limit = normalizedInput.has("limit") ? normalizedInput.get("limit").asInt() : DEFAULT_READ_LIMIT;
            int start = Math.min(offset, content.length());
            int end = Math.min(content.length(), start + limit);
            String chunk = content.substring(start, end);
            boolean truncated = end < content.length();
            return ToolResult.ok(charHeader(inputPath, start, end, content.length(), truncated) + chunk);
        } catch (NoSuchFileException exception) {
            return ToolResult.error("File not found: " + inputPath);
        } catch (WorkspacePathException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("Path does not exist:")) {
                return ToolResult.error("File not found: " + inputPath);
            }
            return ToolResult.error(exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.error("Failed to read file " + inputPath + ": " + exception.getMessage());
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            return ToolResult.error(message == null || message.isBlank() ? "Read file access denied" : message);
        }
    }

    private static String lineChunk(String inputPath, String content, int lineStart, int lineCount) {
        String[] lines = content.split("\\R", -1);
        int totalLines = logicalLineCount(lines, content);
        int startIndex = lineStart - 1;
        if (startIndex >= totalLines) {
            return lineHeader(inputPath, lineStart, totalLines, totalLines, false, totalLines + 1);
        }
        int endExclusive = (int) Math.min((long) totalLines, (long) startIndex + lineCount);
        int lineEnd = endExclusive > startIndex ? endExclusive : lineStart - 1;
        boolean truncated = endExclusive < totalLines;
        StringBuilder chunk = new StringBuilder();
        for (int index = startIndex; index < endExclusive; index++) {
            chunk.append(lines[index]).append('\n');
        }
        return lineHeader(inputPath, lineStart, lineEnd, totalLines, truncated, endExclusive + 1) + chunk;
    }

    private static int logicalLineCount(String[] splitLines, String content) {
        if (content.isEmpty()) {
            return 0;
        }
        if (content.endsWith("\n") || content.endsWith("\r")) {
            return Math.max(0, splitLines.length - 1);
        }
        return splitLines.length;
    }

    private static String charHeader(String inputPath, int offset, int end, int totalChars, boolean truncated) {
        return String.join("\n",
                "FILE: " + inputPath,
                "MODE: chars",
                "OFFSET: " + offset,
                "END: " + end,
                "TOTAL_CHARS: " + totalChars,
                truncated ? "TRUNCATED: yes - call read_file again with offset " + end : "TRUNCATED: no",
                ""
        ) + "\n";
    }

    private static String lineHeader(String inputPath, int lineStart, int lineEnd, int totalLines,
                                     boolean truncated, int nextLineStart) {
        return String.join("\n",
                "FILE: " + inputPath,
                "MODE: lines",
                "LINE_START: " + lineStart,
                "LINE_END: " + lineEnd,
                "TOTAL_LINES: " + totalLines,
                truncated ? "TRUNCATED: yes - call read_file again with lineStart " + nextLineStart : "TRUNCATED: no",
                ""
        ) + "\n";
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "Path to the UTF-8 text file. Relative paths are resolved from cwd.");

        ObjectNode offset = properties.putObject("offset");
        offset.put("type", "integer");
        offset.put("minimum", 0);
        offset.put("description", "Character offset to start reading from. Use only in character mode with limit; do not combine with lineStart or lineCount.");

        ObjectNode limit = properties.putObject("limit");
        limit.put("type", "integer");
        limit.put("minimum", 1);
        limit.put("maximum", MAX_READ_LIMIT);
        limit.put("description", "Maximum number of characters to read in character mode. Omit this field to use the default chunk size; use small values only for targeted excerpts, not general file understanding. Do not combine with lineStart or lineCount.");

        ObjectNode lineStart = properties.putObject("lineStart");
        lineStart.put("type", "integer");
        lineStart.put("minimum", 1);
        lineStart.put("description", "1-based line number to start reading from. Use with lineCount when you have line numbers from grep_files.");

        ObjectNode lineCount = properties.putObject("lineCount");
        lineCount.put("type", "integer");
        lineCount.put("minimum", 1);
        lineCount.put("maximum", MAX_LINE_COUNT);
        lineCount.put("description", "Maximum number of lines to read in line mode. Omit for the default line window. Maximum is 2000. Do not combine with offset or limit.");

        ArrayNode required = schema.putArray("required");
        required.add("path");

        return schema;
    }
}
