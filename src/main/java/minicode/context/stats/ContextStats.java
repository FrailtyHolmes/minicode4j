package minicode.context.stats;

import minicode.context.accounting.TokenAccountingResult;

import java.util.Objects;

/**
 * 当前上下文窗口的"画像"快照，记录已用 token、上限以及使用率。
 *
 * <p>每次进入 AgentLoop 一步循环前都会重新计算一份 {@code ContextStats}，
 * 它是 microcompact / autoCompact 等压缩机制的<b>决策依据</b>：
 * 例如 {@code utilization >= 0.50} 触发 microcompact，{@code utilization >= 0.85} 触发 autoCompact。
 *
 * <p>Token 数本身在 {@link TokenAccountingResult} 中按字符数粗略估算，
 * 误差约 ±20-30%；阈值留了缓冲，工程上够用。
 *
 * <p>设计取舍：使用 record 让该对象天然不可变，在多线程读取 / 跨层传递时无需防御性拷贝。
 *
 * @param accounting    底层 token 计量结果（按消息分项累加得到）
 * @param contextWindow 模型上下文上限（如 Sonnet 的 200_000 token）
 * @param outputReserve 给模型 output 预留的预算（如 8_000 token），不能用于输入
 * @param effectiveInput 真正可用于输入的 token 上限，必须等于 {@code contextWindow - outputReserve}
 * @param utilization   使用率 = 已用 input token / effectiveInput；超过 1.0 表示已超限
 * @param warningLevel  对应的告警级别（NONE / WARN / CRITICAL 等），由调用方根据 utilization 推导
 */
public record ContextStats(TokenAccountingResult accounting, long contextWindow, long outputReserve,
                           long effectiveInput, double utilization,
                           ContextWarningLevel warningLevel) {
    public ContextStats {
        accounting = Objects.requireNonNull(accounting, "accounting");
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("contextWindow must be positive");
        }
        if (outputReserve < 0 || outputReserve >= contextWindow) {
            throw new IllegalArgumentException("outputReserve must be non-negative and smaller than contextWindow");
        }
        if (effectiveInput <= 0 || effectiveInput != contextWindow - outputReserve) {
            throw new IllegalArgumentException("effectiveInput must equal contextWindow - outputReserve");
        }
        if (utilization < 0.0d) {
            throw new IllegalArgumentException("utilization must be non-negative");
        }
        warningLevel = Objects.requireNonNull(warningLevel, "warningLevel");
    }

    /**
     * 返回真正可用于输入的 token 上限，等价于 {@link #effectiveInput()}。
     *
     * <p>提供该别名是为了让调用方在表达"这一次请求最多能塞多少 token"时
     * 用更直观的命名；二者数值始终相同。
     *
     * @return 可用输入 token 上限
     */
    public long maxTokens() {
        return effectiveInput;
    }
}
