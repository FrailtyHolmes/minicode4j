package minicode.context.compact;

import minicode.context.boundary.ContextBoundaryGuard;
import minicode.context.stats.ContextStats;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.ChatMessage;

import java.util.List;
import java.util.Objects;

/**
 * 自动压缩控制器：当上下文使用率逼近上限时，调用 LLM 自己写摘要替换历史消息。
 *
 * <p>这是 minicode4j 三级压缩机制（大输出落盘 → microcompact → autoCompact）中的最后一道防线。
 * 它仅在<b>满足全部触发条件</b>时才真的去压缩，避免"压缩本身又把 token 吃掉"的恶性循环：
 * <ol>
 *   <li>{@code enabled} 开关打开</li>
 *   <li>当前 effective input 已超过 {@link AutoCompactPolicy#minEffectiveInput()}（绝对量门槛，如 20k）</li>
 *   <li>{@code utilization} 达到 {@link AutoCompactPolicy#utilizationThreshold()}（占比门槛，如 0.85）</li>
 *   <li>{@link ContextBoundaryGuard#isCompactSafeBoundary(List)} 判定当前不是"工具调用了还没收到结果"的中间态</li>
 *   <li>{@code cooldownRemaining == 0}，没在失败冷却期内</li>
 * </ol>
 *
 * <p>设计取舍：使用<b>指数退避</b>的失败冷却而非简单重试——压缩调用持续失败时（如 API 故障），
 * 每次循环都试一遍会反复浪费 token，因此每次失败把冷却 preflight 数翻倍，
 * 让"持续故障"很快进入近乎放弃的状态，而"临时故障"等几次循环就会恢复。
 *
 * <p>{@code consecutiveFailures} 与 {@code cooldownRemaining} 是仅在主循环单线程内访问的状态，
 * 故未做并发同步。
 */
public final class AutoCompactController {
    private final CompactService compactService;
    private final AutoCompactPolicy policy;
    private final boolean enabled;
    /** 连续失败次数，与冷却时长成正比；成功一次即清零。 */
    private int consecutiveFailures;
    /** 还需跳过的 preflight 次数；大于 0 时即使条件满足也不压缩。 */
    private int cooldownRemaining;

    /**
     * 构造一个<b>启用</b>的自动压缩控制器。
     *
     * @param compactService 真正执行"调 LLM 写摘要"的服务
     * @param policy         触发阈值与冷却参数的配置策略
     */
    public AutoCompactController(CompactService compactService, AutoCompactPolicy policy) {
        this(compactService, policy, true);
    }

    private AutoCompactController(CompactService compactService, AutoCompactPolicy policy, boolean enabled) {
        this.compactService = Objects.requireNonNull(compactService, "compactService");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.enabled = enabled;
    }

    /**
     * 创建一个永远不会触发压缩的"空操作"控制器。
     *
     * <p>用于测试或用户显式关闭自动压缩的场景；
     * 调用 {@link #preflight(List, ContextStats, ModelAdapter)} 会直接返回 SKIPPED。
     *
     * @return 永久禁用的控制器实例
     */
    public static AutoCompactController disabled() {
        return new AutoCompactController(new CompactService(), AutoCompactPolicy.defaults(), false);
    }

    /**
     * 该控制器是否启用。
     *
     * @return {@code true} 表示会按策略触发压缩；{@code false} 表示永远跳过
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 在不实际执行压缩的前提下，判断当前条件是否会触发一次自动压缩。
     *
     * <p>主要供主循环 / UI 用作"事先告知用户即将压缩"的展示信号；
     * 与 {@link #preflight(List, ContextStats, ModelAdapter)} 的判断条件保持一致，
     * 但<b>不会</b>修改冷却计数等内部状态。
     *
     * @param messages 当前完整对话消息列表
     * @param stats    当前上下文画像
     * @return 若所有触发条件都满足则返回 {@code true}
     */
    public boolean willAttempt(List<ChatMessage> messages, ContextStats stats) {
        List<ChatMessage> actualMessages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(stats, "stats");
        return enabled
                && stats.effectiveInput() >= policy.minEffectiveInput()
                && stats.utilization() >= policy.utilizationThreshold()
                && ContextBoundaryGuard.isCompactSafeBoundary(actualMessages)
                && cooldownRemaining == 0;
    }

    /**
     * 执行一次"飞行前检查 + 视情况压缩"的核心入口。
     *
     * <p>按以下顺序短路返回：
     * <ol>
     *   <li>未启用 → SKIPPED</li>
     *   <li>token 总量未到最低门槛 → SKIPPED + 重置失败计数（说明对话还小，无需关心冷却）</li>
     *   <li>使用率未到阈值 → SKIPPED + 重置失败计数</li>
     *   <li>当前不在安全压缩边界（有未完成的工具调用）→ SKIPPED（不重置：稍后还会重试）</li>
     *   <li>仍在冷却期 → SKIPPED 并将 cooldownRemaining 减 1</li>
     *   <li>条件全部满足 → 调用 {@link CompactService#compact} 让 LLM 写摘要</li>
     * </ol>
     *
     * <p>压缩成功会清零失败计数；压缩失败会调用 {@link #recordFailure()} 进入指数退避冷却。
     *
     * @param messages     当前对话消息列表
     * @param stats        当前上下文画像
     * @param modelAdapter 用于实际调用 LLM 写摘要的模型适配器
     * @return 包含 SKIPPED / COMPACTED / FAILED 三种状态以及处理后消息列表的结果对象
     */
    public AutoCompactResult preflight(List<ChatMessage> messages, ContextStats stats, ModelAdapter modelAdapter) {
        List<ChatMessage> actualMessages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(modelAdapter, "modelAdapter");
        if (!enabled) {
            return AutoCompactResult.skipped(actualMessages, "auto compact disabled");
        }
        if (stats.effectiveInput() < policy.minEffectiveInput()) {
            resetFailures();
            return AutoCompactResult.skipped(actualMessages, "effective input window is below auto compact minimum");
        }
        if (stats.utilization() < policy.utilizationThreshold()) {
            resetFailures();
            return AutoCompactResult.skipped(actualMessages, "context utilization is below auto compact threshold");
        }
        if (!ContextBoundaryGuard.isCompactSafeBoundary(actualMessages)) {
            return AutoCompactResult.skipped(actualMessages, "unsafe compact boundary: incomplete tool round");
        }
        if (cooldownRemaining > 0) {
            cooldownRemaining--;
            return AutoCompactResult.skipped(actualMessages, "auto compact cooldown after previous failure");
        }

        ManualCompactResult result = compactService.compact(new CompactRequest(actualMessages, modelAdapter,
                CompactTrigger.AUTO));
        if (result.status() == CompactStatus.COMPACTED) {
            consecutiveFailures = 0;
            cooldownRemaining = 0;
            return AutoCompactResult.compacted(result.messages(), result.boundary().orElseThrow());
        }
        if (result.status() == CompactStatus.FAILED) {
            recordFailure();
            return AutoCompactResult.failed(actualMessages, result.reason().orElse("auto compact failed"));
        }
        return AutoCompactResult.skipped(actualMessages, result.reason().orElse("auto compact skipped"));
    }

    /**
     * 记录一次压缩失败并按"线性 × 连续失败次数"延长冷却时长。
     *
     * <p>例如 {@code failureCooldownPreflights = 2} 时：
     * 第 1 次失败等 2 次 preflight；第 2 次等 4 次；第 3 次等 6 次。
     * 连续失败上限被 {@link AutoCompactPolicy#maxFailures()} 钳住，避免冷却时长无限增长。
     */
    private void recordFailure() {
        consecutiveFailures = Math.min(policy.maxFailures(), consecutiveFailures + 1);
        cooldownRemaining = policy.failureCooldownPreflights() * consecutiveFailures;
    }

    private void resetFailures() {
        consecutiveFailures = 0;
        cooldownRemaining = 0;
    }
}
