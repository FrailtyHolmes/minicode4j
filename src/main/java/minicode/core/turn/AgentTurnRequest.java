package minicode.core.turn;

import minicode.core.message.ChatMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


/**
 * 一次 Agent turn 的输入参数：把"这次要让 agent 做什么"所需的所有上下文打包成一个不可变对象。
 *
 * <p>每按一次回车（或工具的 awaitUser 恢复后）就会构造一个新的 {@code AgentTurnRequest}，
 * 由 {@code AgentLoop.runTurn} 消费。设计成 record 是为了天然不可变、值语义、易于打日志。
 *
 * <p>构造时会做防御性拷贝（{@code List.copyOf(messages)}）和参数校验，
 * 保证循环内部拿到的状态干净、可信。
 *
 * @param turnId            本次 turn 的唯一标识（通常是 UUID），贯穿所有事件与持久化记录用于追踪
 * @param cwd               工作目录，工具执行（如读文件、运行命令）的根路径
 * @param sessionId         所属会话 ID，多个 turn 串成一个会话
 * @param messages          截至本 turn 开始的完整历史消息（含 system prompt + user 输入），构造时会复制
 * @param maxSteps          本 turn 允许的最大循环步数；正数，超过即强制以 MAX_STEPS 收尾，防止无限烧 token
 * @param modelName         可选的模型名覆盖；不传则用全局默认模型
 * @param cancellationToken 协作式取消令牌，循环各阶段会主动 check 它来响应 Ctrl+C 或 UI 取消
 */
public record AgentTurnRequest(
        String turnId,
        Path cwd,
        String sessionId,
        List<ChatMessage> messages,
        int maxSteps,
        Optional<String> modelName,
        CancellationToken cancellationToken
) {
    /**
     * 不需要取消语义的便捷构造器，等价于传入 {@link CancellationToken#none()}。
     * 一般用于测试或一次性脚本场景。
     */
    public AgentTurnRequest(String turnId, Path cwd, String sessionId, List<ChatMessage> messages, int maxSteps,
                            Optional<String> modelName) {
        this(turnId, cwd, sessionId, messages, maxSteps, modelName, CancellationToken.none());
    }

    /**
     * 紧凑构造器：参数非空校验 + 防御性拷贝 + maxSteps 必须为正。
     * 让非法状态根本无法构造出来，是 record 的标准用法。
     */
    public AgentTurnRequest {
        if (Objects.requireNonNull(turnId, "turnId").isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        cwd = Objects.requireNonNull(cwd, "cwd");
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        modelName = Objects.requireNonNull(modelName, "modelName");
        cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
    }
}
