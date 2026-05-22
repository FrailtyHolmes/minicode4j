package minicode.tools.registry;

import com.fasterxml.jackson.databind.JsonNode;
import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationRequestedException;
import minicode.tools.result.ToolResult;
import minicode.tools.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * 工具注册表 + 工具执行器二合一——所有内置工具都在这里登记，AgentLoop 也通过它执行工具。
 *
 * <p>为什么是「二合一」？因为同一个对象既要支持装配期的 {@link #register(Tool)}，
 * 又要在运行期被 {@code AgentLoop} 调 {@link #execute}。让它实现 {@link ToolExecutor}，
 * 对外只暴露执行器视图，就能让 AgentLoop 摆脱对具体注册表的依赖（参考 ToolExecutor 的注释）。
 *
 * <p>关键设计取舍：
 * <p>1. 用 {@link LinkedHashMap} 而非 HashMap：保证「注册顺序 = list 顺序」，
 *    进而决定 system prompt 里工具排列顺序，影响 LLM 的优先选择。
 * <p>2. 重复注册直接抛异常：在启动期就把「工具名冲突」暴露出来，避免运行时的诡异覆盖。
 * <p>3. {@link #execute} 内部多层 try-catch：校验异常、执行异常分别处理，<b>所有异常都被转成
 *    {@link ToolResult#error(String)}</b>，<b>除了取消信号必须透传</b>。
 *    任何一个工具崩了，都不能拖死整个 AgentLoop。
 * <p>4. 取消检查放在最前 + 关键阶段前后多次重复：避免在耗时阶段才发现取消。
 *
 * <p>本类<b>非线程安全</b>：注册一般在启动期单线程完成；运行期只读使用。
 */
public final class ToolRegistry implements ToolExecutor {
    /**
     * 工具名 → Tool 实例的映射。用 {@link LinkedHashMap} 保证插入顺序可观测，
     * 进而决定 {@link #list()} 的输出顺序与 system prompt 中工具排列顺序。
     */
    private final Map<String, Tool> toolsByName = new LinkedHashMap<>();

    /**
     * 注册一个工具到本注册表。
     *
     * <p>工具名取自 {@code tool.metadata().name()}。同一个名字只允许注册一次，
     * 重复注册会抛异常——这种「失败趁早」的设计能在启动期立刻暴露配置错误，
     * 避免线上跑了半天才发现工具被某个 plugin 静默覆盖。
     *
     * @param tool 要注册的工具实例，不能为 {@code null}
     * @throws IllegalArgumentException 同名工具已注册时抛出
     */
    public void register(Tool tool) {
        Tool actualTool = Objects.requireNonNull(tool, "tool");
        String name = actualTool.metadata().name();
        if (toolsByName.containsKey(name)) {
            throw new IllegalArgumentException("Tool already registered: " + name);
        }
        toolsByName.put(name, actualTool);
    }

    /**
     * 按工具名查找已注册的工具实例。
     *
     * @param name 工具名，不能为 {@code null}
     * @return 包装在 {@link Optional} 里的工具实例；未注册则为 {@link Optional#empty()}
     */
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(toolsByName.get(Objects.requireNonNull(name, "name")));
    }

    /**
     * 返回所有已注册工具的快照副本，<b>顺序与注册顺序一致</b>。
     *
     * <p>主要给 {@code SystemPromptBuilder} 拼系统提示用，也给 TUI 列工具列表用。
     * 返回的是不可变 copy，对返回值的修改不会影响注册表内部状态。
     *
     * @return 工具实例列表（不可变）
     */
    public List<Tool> list() {
        return List.copyOf(toolsByName.values());
    }

    /**
     * 执行一次工具调用——本类作为 {@link ToolExecutor} 的核心实现。
     *
     * <p>处理顺序（任何一步失败都会被转成 {@link ToolResult#error(String)} 返回，
     * 除取消信号外不会向上抛异常）：
     * <p>1. 取消检查（最前置，避免无谓开销）；
     * <p>2. 路由：按工具名找实现，找不到 → "Unknown tool"；
     * <p>3. 校验：调 {@link Tool#validateInput}，校验中再次检查取消；
     *    校验器返回 null 或 {@code valid=true} 但 normalizedInput 缺失等异常情况都有兜底；
     * <p>4. 执行：调 {@link Tool#run}，前后再次检查取消；执行抛 RuntimeException → 转 error；
     *    执行返回 null → 转 "Tool returned null" 错误。
     *
     * <p>{@code CancellationRequestedException} 是<b>唯一会向上抛出的异常</b>，
     * 因为 AgentLoop 必须感知到取消信号来中断整个 Turn。
     *
     * @param call        待执行的工具调用（含工具名 + 入参 JSON）
     * @param toolContext 调用上下文（cwd、取消令牌等）
     * @return 工具执行结果，永不为 {@code null}
     */
    @Override
    public ToolResult execute(ToolCall call, ToolContext toolContext) {
        ToolCall actualCall = Objects.requireNonNull(call, "call");
        ToolContext actualToolContext = Objects.requireNonNull(toolContext, "toolContext");
        actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
        Tool tool = toolsByName.get(actualCall.toolName());
        if (tool == null) {
            return ToolResult.error("Unknown tool: " + actualCall.toolName());
        }

        ValidationResult validation;
        try {
            actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            validation = tool.validateInput(actualCall.input());
            actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return ToolResult.error(messageOrDefault(exception, "Tool input validation failed"));
        }

        if (validation == null) {
            return ToolResult.error("Tool input validation failed: validator returned null");
        }

        if (!validation.valid()) {
            return ToolResult.error(formatValidationErrors(actualCall.toolName(), validation));
        }

        if (validation.normalizedInput().isEmpty()) {
            return ToolResult.error("Tool input validation failed: valid result requires normalized input");
        }

        JsonNode normalizedInput = validation.normalizedInput().get();

        try {
            actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            ToolResult result = tool.run(normalizedInput, actualToolContext);
            actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            return result == null ? ToolResult.error("Tool returned null ToolResult") : result;
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return ToolResult.error(messageOrDefault(exception, "Tool execution failed"));
        }
    }

    private static String formatValidationErrors(String toolName, ValidationResult validation) {
        StringJoiner joiner = new StringJoiner("; ");
        validation.errors().forEach(joiner::add);
        return "Tool input validation failed for " + toolName + ": " + joiner;
    }

    private static String messageOrDefault(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
