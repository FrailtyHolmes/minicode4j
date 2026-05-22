package minicode.core.step;

/**
 * 模型一次调用产出的"一步"结果，是 Agent 主循环里最核心的抽象。
 *
 * <p>用 sealed 限定只有两种实现：
 * <ul>
 *   <li>{@link AssistantStep}：模型给出文本（可能是最终答复 FINAL，也可能是中途汇报 PROGRESS）。</li>
 *   <li>{@link ToolCallsStep}：模型决定调用一个或多个工具，等待外部执行后再继续。</li>
 * </ul>
 *
 * <p>之所以做成 sealed，是为了让 {@code AgentLoop.runTurn} 里用 pattern switch
 * 穷举两种分支时编译器能帮忙静态检查；新增分支必须显式更新循环逻辑，避免漏处理。
 */
public sealed interface AgentStep permits AssistantStep, ToolCallsStep {
}
