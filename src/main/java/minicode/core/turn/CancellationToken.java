package minicode.core.turn;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 协作式取消令牌：由调用方在循环各阶段主动 check，决定是否中断当前 turn。
 *
 * <p>这是 .NET {@code CancellationToken} 的 Java 移植版。相比标准库的
 * {@link Thread#interrupt()}，它有三个关键优势：
 * <ul>
 *   <li>不依赖线程中断响应——HTTP 客户端、用户代码不一定支持中断。</li>
 *   <li>每次 check 都带 {@link CancellationPhase}，能精确告知"在哪个阶段取消"。</li>
 *   <li>语义是"业务级"取消，可以携带 {@link CancellationSource}（用户/超时/外部）和原因字符串。</li>
 * </ul>
 *
 * <p>线程安全：取消请求用 {@link AtomicReference} + CAS 写入，保证多线程下只生效一次。
 * 多次调用 {@link #requestCancellation} 不会覆盖第一次的请求。
 */
public final class CancellationToken {
    /** 共享的"永不可取消"实例，{@link #none()} 返回。用作不需要取消语义场景的占位。 */
    private static final CancellationToken NONE = new CancellationToken(false);

    /** 是否允许被取消；{@code false} 时所有 requestCancellation 都是 no-op。 */
    private final boolean cancellable;

    /** 取消请求的原子槽位：null 表示尚未取消；非 null 后不再变化。 */
    private final AtomicReference<CancellationRequest> request = new AtomicReference<>();

    private CancellationToken(boolean cancellable) {
        this.cancellable = cancellable;
    }

    /**
     * 返回单例的"永不可取消"令牌，用于不关心取消语义的场景（如测试、一次性脚本）。
     */
    public static CancellationToken none() {
        return NONE;
    }

    /**
     * 创建一个全新的可取消令牌，初始未取消。一般由 TUI/调度层持有引用，按需调用
     * {@link #requestCancellation} 触发取消。
     */
    public static CancellationToken create() {
        return new CancellationToken(true);
    }

    /**
     * 创建一个已经处于取消状态的令牌，主要用于测试构造"已取消"分支。
     *
     * @param source 取消来源
     * @param reason 取消原因（不可为空白，否则 {@link CancellationRequest} 会抛 {@link IllegalArgumentException}）
     */
    public static CancellationToken cancelled(CancellationSource source, String reason) {
        CancellationToken token = create();
        token.requestCancellation(source, reason);
        return token;
    }

    /** 是否已收到取消请求；轻量级查询，循环里高频调用也不会有性能负担。 */
    public boolean isCancellationRequested() {
        return request.get() != null;
    }

    /**
     * 请求取消。{@link #cancellable} 为 false 时是 no-op；
     * 多次调用只有第一次生效（CAS），保证取消原因稳定。
     *
     * @param source 取消来源（用户主动 / 超时 / 外部信号 等）
     * @param reason 人类可读的原因字符串，会出现在 UI 与日志里
     */
    public void requestCancellation(CancellationSource source, String reason) {
        if (!cancellable) {
            return;
        }
        CancellationRequest cancellationRequest = new CancellationRequest(source, reason);
        request.compareAndSet(null, cancellationRequest);
    }

    /**
     * 拿当前取消请求并打上发生阶段的标签。未取消则返回空。
     *
     * @param phase 调用方此刻所处的阶段，用于在最终的 {@link TurnCancellation} 里精确归因
     */
    public Optional<TurnCancellation> cancellation(CancellationPhase phase) {
        CancellationRequest cancellationRequest = request.get();
        if (cancellationRequest == null) {
            return Optional.empty();
        }
        return Optional.of(cancellationRequest.toCancellation(phase));
    }

    /**
     * 若已收到取消请求，立刻抛 {@link CancellationRequestedException} 跳出当前调用栈。
     *
     * <p>这是循环内最常用的取消 check 方式：在每个关键阶段插一行
     * {@code throwIfCancellationRequested(phase)}，让取消信号能在最近的安全点生效。
     *
     * @param phase 当前所在阶段，会被一并写入异常里携带的 {@link TurnCancellation}
     * @throws CancellationRequestedException 当且仅当已收到取消请求时抛出
     */
    public void throwIfCancellationRequested(CancellationPhase phase) {
        cancellation(phase).ifPresent(cancellation -> {
            throw new CancellationRequestedException(cancellation);
        });
    }

    /**
     * 一次取消请求的内部表示：来源 + 原因。和外暴露的 {@link TurnCancellation} 区别在于
     * 此处不包含阶段——阶段由 check 调用方提供。
     */
    private record CancellationRequest(CancellationSource source, String reason) {
        private CancellationRequest {
            source = Objects.requireNonNull(source, "source");
            if (Objects.requireNonNull(reason, "reason").isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }

        private TurnCancellation toCancellation(CancellationPhase phase) {
            return new TurnCancellation(source, phase, reason);
        }
    }
}
