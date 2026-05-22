package minicode.tools.api;

import minicode.core.turn.CancellationToken;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次工具调用的运行时上下文——把「这个工具是谁、在哪、能不能被取消」一次性显式传进来。
 *
 * <p>每次 AgentLoop 调一个工具，都会 new 一个新的 {@code ToolContext} 传给
 * {@link minicode.tools.registry.ToolRegistry#execute}。这样工具实现要拿 cwd、要监听取消信号，
 * 都通过参数拿，<b>避免 ThreadLocal、避免全局变量</b>，也方便测试时手工构造。
 *
 * <p>把 {@code turnId} 和 {@code toolUseId} 设计成 {@link Optional} 是因为：
 * 测试场景下你完全可以裸跑一个工具，不需要真实 Turn / ToolUse 编号；
 * 但 {@code cwd} 和 {@code sessionId} 任何时候都必须存在，因此在 compact 构造器里强校验。
 *
 * <p>所有字段都是不可变的（record + final 引用），可以安全地在多线程间共享。
 *
 * @param cwd               当前工作目录，工具解析相对路径都从这里开始
 * @param sessionId         所属会话 ID，不可为空字符串
 * @param turnId            所属 Turn ID，测试或非 Turn 调用时可缺省
 * @param toolUseId         本次工具调用 ID，由 LLM 在 tool_use 块里给出，主要用于审计 / 关联事件
 * @param cancellationToken 取消令牌，工具应在耗时操作前后调用其 throwIfCancellationRequested 检查
 */
public record ToolContext(Path cwd, String sessionId, Optional<String> turnId, Optional<String> toolUseId,
                          CancellationToken cancellationToken) {
    /**
     * 便捷构造器：不传取消令牌时使用 {@link CancellationToken#none()}（永不取消）。
     *
     * <p>主要给单元测试用——生产代码里 AgentLoop 会显式传一个真实的取消令牌。
     */
    public ToolContext(Path cwd, String sessionId, Optional<String> turnId, Optional<String> toolUseId) {
        this(cwd, sessionId, turnId, toolUseId, CancellationToken.none());
    }

    /**
     * Compact 构造器：在对象构造前对所有字段做空值与合法性校验。
     *
     * <p>{@code cwd}/{@code turnId}/{@code toolUseId}/{@code cancellationToken} 不允许为 {@code null}；
     * {@code sessionId} 不允许为空字符串（包括纯空白）。
     */
    public ToolContext {
        cwd = Objects.requireNonNull(cwd, "cwd");
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        turnId = Objects.requireNonNull(turnId, "turnId");
        toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
        cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
    }
}
