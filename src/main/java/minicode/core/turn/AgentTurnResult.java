package minicode.core.turn;

import minicode.core.message.ChatMessage;
import minicode.session.plan.TurnPersistencePlan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;




/**
 * 一次 Agent turn 的终止结果：把"循环为什么停下来 + 停下来时的最终消息"打包给调用方。
 *
 * <p>这里用"显式结果对象"而非"抛异常"建模终止原因，因为大多数停止其实是正常流程
 * （FINAL 完成、AWAIT_USER 等用户回答、MAX_STEPS 兜底），用异常会扭曲控制流。
 *
 * <p>不变式：{@link AgentTurnStopReason} 与 {@link #stopDetails} 必须匹配，
 * 由静态工厂方法 + {@link #validate} 共同保证。比如 {@code MODEL_ERROR} 必带
 * {@link ModelErrorDetails}，{@code FINAL} 不允许带任何 details。
 * 非法组合在构造期就会抛 {@link IllegalArgumentException}，调用方拿到的对象一定合法。
 *
 * @param messages        turn 结束时的完整消息列表（构造时复制为不可变）
 * @param persistencePlan 本 turn 期间累积的持久化动作清单，由外层落盘到 session
 * @param stopReason      6 种停止原因之一（FINAL / AWAIT_USER / MAX_STEPS / MODEL_ERROR / CANCELLED / EMPTY_RESPONSE_FALLBACK）
 * @param stopDetails     与 stopReason 配套的详情；正常完成类停止为空，错误/取消类停止必填
 */
public record AgentTurnResult(List<ChatMessage> messages, TurnPersistencePlan persistencePlan,
                              AgentTurnStopReason stopReason, Optional<AgentTurnStopDetails> stopDetails) {
    /**
     * 紧凑构造器：参数非空校验 + 消息列表防御性拷贝 + stopReason/stopDetails 一致性校验。
     */
    public AgentTurnResult {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        persistencePlan = Objects.requireNonNull(persistencePlan, "persistencePlan");
        stopReason = Objects.requireNonNull(stopReason, "stopReason");
        stopDetails = Objects.requireNonNull(stopDetails, "stopDetails");
        validate(stopReason, stopDetails);
    }

    /**
     * 通用工厂方法。一般推荐用下方 {@code finalResult / awaitUser / ...} 等具名工厂；
     * 此方法只在需要透传外部计算出的 stopReason 时使用。
     */
    public static AgentTurnResult create(List<ChatMessage> messages, TurnPersistencePlan persistencePlan,
                                         AgentTurnStopReason stopReason,
                                         Optional<AgentTurnStopDetails> stopDetails) {
        return new AgentTurnResult(messages, persistencePlan, stopReason, stopDetails);
    }

    /** 模型给出 {@code <final>} 标记，turn 正常完成。 */
    public static AgentTurnResult finalResult(List<ChatMessage> messages, TurnPersistencePlan persistencePlan) {
        return create(messages, persistencePlan, AgentTurnStopReason.FINAL, Optional.empty());
    }

    /** 工具返回 {@code awaitUser=true}（如 ask_user），需要等用户回答才能继续。 */
    public static AgentTurnResult awaitUser(List<ChatMessage> messages, TurnPersistencePlan persistencePlan) {
        return create(messages, persistencePlan, AgentTurnStopReason.AWAIT_USER, Optional.empty());
    }

    /** 跑满 {@code maxSteps} 仍未结束，强制收尾以防失控。 */
    public static AgentTurnResult maxSteps(List<ChatMessage> messages, TurnPersistencePlan persistencePlan) {
        return create(messages, persistencePlan, AgentTurnStopReason.MAX_STEPS, Optional.empty());
    }

    /** 模型调用 3 次重试后仍失败，附带错误详情供 UI 展示与上层决策是否重试。 */
    public static AgentTurnResult modelError(List<ChatMessage> messages, TurnPersistencePlan persistencePlan,
                                             ModelErrorDetails details) {
        return create(messages, persistencePlan, AgentTurnStopReason.MODEL_ERROR, Optional.of(details));
    }

    /** 用户主动取消（Ctrl+C 或 UI 按钮），附带取消发生的阶段与原因。 */
    public static AgentTurnResult cancelled(List<ChatMessage> messages, TurnPersistencePlan persistencePlan,
                                            CancellationDetails details) {
        return create(messages, persistencePlan, AgentTurnStopReason.CANCELLED, Optional.of(details));
    }

    /** 模型连续返回空响应、催促重试也无效后的兜底分支，给用户一段友好的失败提示。 */
    public static AgentTurnResult emptyFallback(List<ChatMessage> messages, TurnPersistencePlan persistencePlan,
                                                Optional<EmptyFallbackDetails> details) {
        return create(messages, persistencePlan, AgentTurnStopReason.EMPTY_RESPONSE_FALLBACK, details.map(AgentTurnStopDetails.class::cast));
    }

    /**
     * 校验 stopReason 与 details 的配对关系，违反任一规则均抛 {@link IllegalArgumentException}。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code FINAL / AWAIT_USER / MAX_STEPS}：禁止携带 details。</li>
     *   <li>{@code MODEL_ERROR}：必带 {@link ModelErrorDetails}。</li>
     *   <li>{@code CANCELLED}：必带 {@link CancellationDetails}。</li>
     *   <li>{@code EMPTY_RESPONSE_FALLBACK}：若提供 details，必须为 {@link EmptyFallbackDetails}（允许 empty）。</li>
     * </ul>
     */
    private static void validate(AgentTurnStopReason reason, Optional<AgentTurnStopDetails> details) {
        switch (reason) {
            case FINAL, AWAIT_USER, MAX_STEPS -> {
                if (details.isPresent()) {
                    throw new IllegalArgumentException(reason + " cannot carry stop details");
                }
            }
            case MODEL_ERROR -> requireDetails(details, ModelErrorDetails.class, reason);
            case CANCELLED -> requireDetails(details, CancellationDetails.class, reason);
            case EMPTY_RESPONSE_FALLBACK -> {
                if (details.isPresent() && !(details.get() instanceof EmptyFallbackDetails)) {
                    throw new IllegalArgumentException(reason + " requires EmptyFallbackDetails");
                }
            }
        }
    }

    private static void requireDetails(Optional<AgentTurnStopDetails> details,
                                       Class<? extends AgentTurnStopDetails> type,
                                       AgentTurnStopReason reason) {
        if (details.isEmpty() || !type.isInstance(details.get())) {
            throw new IllegalArgumentException(reason + " requires " + type.getSimpleName());
        }
    }
}
