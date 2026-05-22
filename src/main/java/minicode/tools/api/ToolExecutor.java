package minicode.tools.api;

import minicode.tools.result.ToolResult;

import java.util.Objects;

/**
 * 工具执行器抽象——AgentLoop 唯一依赖的「能跑工具的东西」。
 *
 * <p>这是个 {@link FunctionalInterface}，在面向对象代码里很优雅地解决了一个现实问题：
 * {@link minicode.tools.registry.ToolRegistry} 同时承担「注册表」和「执行器」两份职责，
 * 但 AgentLoop 在运行时只关心「执行」这一面。
 * 让 ToolRegistry 实现 ToolExecutor，AgentLoop 持有 ToolExecutor 类型字段，
 * 就达成了<b>依赖倒置（DIP）</b>：模块依赖抽象，不依赖具体实现。
 *
 * <p>这种拆分带来三个好处：
 * <p>1. 测试 AgentLoop 时可以直接传 lambda（{@code (call, ctx) -> ToolResult.ok("fake")}），
 *    完全不用启动 ToolRegistry 或注册任何 Tool。
 * <p>2. 隐藏了 register / find / list 这些装配期方法，运行时不会被误用。
 * <p>3. 未来若想做「远程工具执行」（工具跑在另一台机器上），只需要换实现，AgentLoop 零改动。
 */
@FunctionalInterface
public interface ToolExecutor {
    /**
     * 执行一次工具调用。
     *
     * <p>实现方需要确保：任何来自工具内部的可预期异常都已被转成 {@link ToolResult#error(String)}，
     * 不要把堆栈直接抛给 AgentLoop。唯一例外是
     * {@code CancellationRequestedException}——这个必须透传，让上层感知取消。
     *
     * @param call        本次调用的工具名 + 入参 JSON
     * @param toolContext 调用上下文（cwd、取消令牌等）
     * @return 工具执行结果，永不为 {@code null}
     */
    ToolResult execute(ToolCall call, ToolContext toolContext);

    /**
     * 返回一个「永远抛 unknown tool 异常」的占位执行器。
     *
     * <p>用法场景：当某个组件需要 ToolExecutor 字段但又确实不会真的去执行工具时
     * （比如某些 dry-run 模式 / 占位测试），用它来满足类型系统又能在「真被调到」时及时炸出来。
     *
     * @return 一个调用即抛 {@link IllegalArgumentException} 的 ToolExecutor
     */
    static ToolExecutor unsupported() {
        return (call, toolContext) -> {
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(toolContext, "toolContext");
            throw new IllegalArgumentException("Unknown tool: " + call.toolName());
        };
    }
}
