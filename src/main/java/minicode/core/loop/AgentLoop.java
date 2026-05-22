package minicode.core.loop;

import minicode.context.accounting.TokenAccountingService;
import minicode.context.compact.AutoCompactController;
import minicode.context.compact.AutoCompactEventType;
import minicode.context.compact.AutoCompactResult;
import minicode.context.compact.CompactStatus;
import minicode.context.manager.ContextManager;
import minicode.context.stats.ContextStats;
import minicode.context.stats.ContextStatsCalculator;
import minicode.context.stats.ModelContextWindow;
import minicode.core.event.AgentEvent;
import minicode.core.event.AgentEventSink;
import minicode.core.event.ToolResultsBudgetedEvent;
import minicode.core.message.*;
import minicode.core.step.AgentStep;
import minicode.core.step.AssistantStep;
import minicode.core.step.ToolCallsStep;
import minicode.core.turn.*;
import minicode.model.UsageStaleness;
import minicode.model.ModelRequestException;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ToolExecutor;
import minicode.tools.result.ToolResult;
import minicode.tools.result.ToolResultBudgetResult;
import minicode.tools.result.ToolResultReplacementResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent 主循环：在一个 turn 内反复执行 model → tool → model，直到模型给出 final 答复、
 * 等用户输入、超步数、被取消或连续空响应兜底。整门课最核心的类。
 *
 * <p>这个类的核心职责是把"调模型 - 跑工具 - 处理结果 - 再调模型"这条朴素链路
 * 包装成一个稳健的有界状态机：
 * <ul>
 *   <li>用三个独立计数器（stepIndex / emptyResponseCount / recoverableThinkingRetryCount）
 *       防止三种性质不同的死循环。</li>
 *   <li>用 {@link CancellationToken} 在每个关键阶段做协作式取消 check。</li>
 *   <li>把模型错误、工具错误、空响应、取消等所有终止原因都显式建模成
 *       {@link AgentTurnResult} 的不同分支，对外永远返回结果对象而不抛异常。</li>
 *   <li>把工具结果、上下文压缩、token 统计等副作用都写入 {@link PersistenceAction} 列表，
 *       由调用方决定何时落盘，loop 自己不直接碰持久化。</li>
 * </ul>
 *
 * <p>线程模型：本类不自带异步，{@code runTurn} 是同步阻塞调用。取消通过
 * {@link CancellationToken} 实现（不依赖 {@link Thread#interrupt()}），
 * UI 渲染在另一线程，因此即便循环阻塞也不会卡住界面。
 */
public final class AgentLoop {
    private static final String EMPTY_RESPONSE_MESSAGE =
            "The model returned an empty response after retries. Please try again.";
    private static final String PROGRESS_CONTINUATION_PROMPT =
            "Continue immediately from your <progress> update with concrete tool calls, code changes, or an explicit <final> answer only if the task is complete.";
    private static final String EMPTY_RESPONSE_CONTINUATION_PROMPT =
            "Your last response was empty. Continue immediately with concrete tool calls, code changes, or an explicit <final> answer only if the task is complete.";
    private static final String EMPTY_RESPONSE_AFTER_TOOL_RESULT_CONTINUATION_PROMPT =
            "Your last response was empty after recent tool results. Continue immediately by trying the next concrete step, adapting to any tool errors, or giving an explicit <final> answer only if the task is complete.";
    private static final String EMPTY_RESPONSE_AFTER_TOOL_ERROR_CONTINUATION_PROMPT =
            "Your last response was empty after recent tool results that included errors. Adapt to the tool error, try the next concrete step, or give an explicit <final> answer only if the task is complete.";
    private static final String MAX_TOKENS_THINKING_CONTINUATION_PROMPT =
            "Your previous response hit max_tokens during thinking before producing the next actionable step. Resume immediately and continue with the next concrete tool call, code change, or an explicit <final> answer only if the task is complete. Do not repeat the earlier plan.";
    private static final String PAUSE_TURN_THINKING_CONTINUATION_PROMPT =
            "Resume from the previous pause_turn and continue the task immediately. Produce the next concrete tool call, code change, or an explicit <final> answer only if the task is complete.";
    /** 单次模型调用的最大重试次数，含首次。3 次覆盖大多数瞬时错误（429、502 等）。 */
    private static final int MODEL_REQUEST_ATTEMPTS = 3;

    private final ModelAdapter modelAdapter;
    private final AgentEventSink eventSink;
    private final ToolExecutor toolExecutor;
    private final ContextManager contextManager;
    private final ContextStatsCalculator contextStatsCalculator;
    private final AutoCompactController autoCompactController;
    /** 同一 turn 内允许连续空响应被催促重试的最大次数；超过则进入 emptyFallback 兜底。 */
    private final int maxEmptyResponseRetries;

    /**
     * 最简构造：不接工具执行器、空响应重试上限默认 2。一般用于纯对话场景或单元测试。
     */
    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink) {
        this(modelAdapter, eventSink, ToolExecutor.unsupported(), 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, int maxEmptyResponseRetries) {
        this(modelAdapter, eventSink, ToolExecutor.unsupported(), maxEmptyResponseRetries);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor) {
        this(modelAdapter, eventSink, toolExecutor, 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     int maxEmptyResponseRetries) {
        this(modelAdapter, eventSink, toolExecutor, ContextManager.noOp(), maxEmptyResponseRetries);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, int maxEmptyResponseRetries) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, defaultContextStatsCalculator(),
                AutoCompactController.disabled(), maxEmptyResponseRetries);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, ContextStatsCalculator contextStatsCalculator) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, contextStatsCalculator, 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, ContextStatsCalculator contextStatsCalculator,
                     int maxEmptyResponseRetries) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, contextStatsCalculator,
                AutoCompactController.disabled(), maxEmptyResponseRetries);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, ContextStatsCalculator contextStatsCalculator,
                     AutoCompactController autoCompactController) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, contextStatsCalculator,
                autoCompactController, 2);
    }

    /**
     * 全参主构造：装配所有依赖并校验非空、参数合法。其它重载都最终委托到这里。
     *
     * @param modelAdapter            模型适配层（如 {@code AnthropicModelAdapter}），把消息转成模型协议并解析返回
     * @param eventSink               事件汇，循环里的关键状态转换都会通过它推给 UI/日志/持久化
     * @param toolExecutor            工具执行器；不需要工具时传 {@link ToolExecutor#unsupported()}
     * @param contextManager          上下文管理器，负责微压缩、超大工具结果替换、token 预算控制
     * @param contextStatsCalculator  token 统计器，每步循环前算一次，作为 autoCompact 决策依据
     * @param autoCompactController   自动压缩控制器，token 接近上下文窗口上限时触发摘要压缩
     * @param maxEmptyResponseRetries 空响应连续催促重试上限；非负整数
     * @throws NullPointerException     任一依赖为 null 时抛出
     * @throws IllegalArgumentException maxEmptyResponseRetries 为负时抛出
     */
    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, ContextStatsCalculator contextStatsCalculator,
                     AutoCompactController autoCompactController,
                     int maxEmptyResponseRetries) {
        this.modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager");
        this.contextStatsCalculator = Objects.requireNonNull(contextStatsCalculator, "contextStatsCalculator");
        this.autoCompactController = Objects.requireNonNull(autoCompactController, "autoCompactController");
        if (maxEmptyResponseRetries < 0) {
            throw new IllegalArgumentException("maxEmptyResponseRetries must be non-negative");
        }
        this.maxEmptyResponseRetries = maxEmptyResponseRetries;
    }

    /**
     * 执行一次完整的 agent turn：反复 model → tool → model 直到能给出终态。
     *
     * <p>主循环骨架（详见 docs/tutorial/ch02-agent-loop.md）：
     * <ol>
     *   <li>每步先做上下文预处理（微压缩 + 自动压缩 + 推 ContextStatsEvent）。</li>
     *   <li>调模型（带 3 次重试）；模型抛错最终失败 → 返回 modelError。</li>
     *   <li>分发到 {@link ToolCallsStep}（执行工具，工具异常吃掉转 ToolResult.error）
     *       或 {@link AssistantStep}（处理空响应/可恢复 thinking 中断/PROGRESS/FINAL）。</li>
     *   <li>每个关键阶段都 check {@link CancellationToken}，被取消则跳到 catch 走 cancelled 分支。</li>
     * </ol>
     *
     * <p>对外保证：本方法不抛业务异常。所有终止原因都映射成
     * {@link AgentTurnResult} 的 6 种 stopReason 之一返回，调用方用 switch 处理即可。
     *
     * @param request 本次 turn 的输入
     * @return turn 终态：包含最终消息列表、持久化动作、停止原因与详情
     * @throws NullPointerException request 为 null 时抛出
     */
    public AgentTurnResult runTurn(AgentTurnRequest request) {
        Objects.requireNonNull(request, "request");
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        List<PersistenceAction> actions = new ArrayList<>();
        int emptyResponseCount = 0;
        int recoverableThinkingRetryCount = 0;
        boolean sawToolResultThisTurn = false;
        int toolErrorCount = 0;

        try {
            request.cancellationToken().throwIfCancellationRequested(CancellationPhase.BEFORE_TURN);
            for (int stepIndex = 0; stepIndex < request.maxSteps(); stepIndex++) {
                ContextStats preCompactStats = contextStatsCalculator.calculate(List.copyOf(messages));
                messages = new ArrayList<>(contextManager.microcompact(List.copyOf(messages), preCompactStats));
                ContextStats stats = contextStatsCalculator.calculate(List.copyOf(messages));
                AutoCompactResult autoCompactResult = runAutoCompactPreflight(request.turnId(), messages, actions, stats);
                if (autoCompactResult.status() == CompactStatus.COMPACTED) {
                    messages = new ArrayList<>(autoCompactResult.messages());
                    stats = contextStatsCalculator.calculate(List.copyOf(messages));
                }
                publishEvent(new AgentEvent.ContextStatsEvent(request.turnId(), Instant.now(), stats));
                request.cancellationToken().throwIfCancellationRequested(CancellationPhase.MODEL_REQUEST);
                AgentStep step;
                try {
                    step = nextWithRetries(List.copyOf(messages), request.cancellationToken());
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.MODEL_REQUEST);
                } catch (CancellationRequestedException exception) {
                    throw exception;
                } catch (ModelRequestException exception) {
                    return modelErrorResult(messages, actions, exception);
                } catch (RuntimeException exception) {
                    return modelErrorResult(messages, actions,
                            exception.getMessage() == null || exception.getMessage().isBlank()
                                    ? "Model adapter failed"
                                    : exception.getMessage(),
                            exception.getClass().getName());
                }

                if (step == null) {
                    return modelErrorResult(messages, actions,
                            "Model adapter returned null AgentStep",
                            NullPointerException.class.getName());
                }

                if (step instanceof ToolCallsStep toolCallsStep) {
                    appendToolCallsStepProjection(request.turnId(), messages, actions, toolCallsStep);
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    List<ToolResultMessage> toolResultMessages = new ArrayList<>();

                    for (int callIndex = 0; callIndex < toolCallsStep.calls().size(); callIndex++) {
                        ToolCall call = toolCallsStep.calls().get(callIndex);
                        appendToolCallMessage(request.turnId(), messages, actions, call,
                                callIndex == toolCallsStep.calls().size() - 1
                                        ? toolCallsStep.usage()
                                        : Optional.empty());
                        publishEvent(new AgentEvent.ToolStartedEvent(request.turnId(), Instant.now(),
                                call.id(), call.toolName(), call.input()));
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
                    }

                    for (ToolCall call : toolCallsStep.calls()) {
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);

                        ToolResult result;
                        try {
                            result = toolExecutor.execute(call, createToolContext(request, call.id()));
                            request.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
                        } catch (CancellationRequestedException exception) {
                            throw exception;
                        } catch (RuntimeException exception) {
                            result = ToolResult.error(exception.getMessage() == null || exception.getMessage().isBlank()
                                    ? "Tool execution failed"
                                    : exception.getMessage());
                        }

                        if (result == null) {
                            result = ToolResult.error("Tool executor returned null ToolResult");
                        }
                        sawToolResultThisTurn = true;
                        if (result.error()) {
                            toolErrorCount++;
                        }
                        ToolResultMessage toolResultMessage = appendToolResultMessage(
                                request.turnId(), messages, actions, call, result);
                        toolResultMessages.add(toolResultMessage);
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                        if (result.awaitUser()) {
                            applyToolResultBudget(request.turnId(), messages, actions, toolResultMessages);
                            request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                            publishEvent(new AgentEvent.AwaitUserEvent(
                                    request.turnId(),
                                    Instant.now(),
                                    call.id(),
                                    awaitUserQuestion(call.id(), toolResultMessages)
                            ));
                            return AgentTurnResult.awaitUser(List.copyOf(messages), new TurnPersistencePlan(actions));
                        }
                    }
                    applyToolResultBudget(request.turnId(), messages, actions, toolResultMessages);
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    continue;
                }

                if (!(step instanceof AssistantStep assistantStep)) {
                    return modelErrorResult(messages, actions,
                            "AgentLoop only supports AssistantStep and ToolCallsStep",
                            step.getClass().getName());
                }

                if (isRecoverableThinkingStop(assistantStep) && recoverableThinkingRetryCount < 3) {
                    recoverableThinkingRetryCount++;
                    String stopReason = assistantStep.diagnostics().orElseThrow().stopReason().orElse("");
                    AssistantProgressMessage progressMessage = new AssistantProgressMessage(
                            "max_tokens".equals(stopReason)
                                    ? "Model hit max_tokens during thinking; requesting the next actionable step."
                                    : "Model returned pause_turn during thinking; requesting the next actionable step."
                    );
                    appendMessage(request.turnId(), messages, actions, progressMessage);
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    appendMessage(request.turnId(), messages, actions, new UserMessage(
                            "max_tokens".equals(stopReason)
                                    ? MAX_TOKENS_THINKING_CONTINUATION_PROMPT
                                    : PAUSE_TURN_THINKING_CONTINUATION_PROMPT
                    ));
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    continue;
                }

                if (assistantStep.content().isBlank()) {
                    emptyResponseCount++;
                    if (emptyResponseCount <= maxEmptyResponseRetries) {
                        appendMessage(request.turnId(), messages, actions,
                                new UserMessage(emptyResponseContinuationPrompt(sawToolResultThisTurn, toolErrorCount)));
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                        continue;
                    }
                    appendAssistantThinkingBlocks(request.turnId(), messages, actions, assistantStep);
                    String fallbackReason = emptyFallbackReason(sawToolResultThisTurn, toolErrorCount);
                    AssistantMessage fallbackMessage = new AssistantMessage(
                            emptyFallbackMessage(sawToolResultThisTurn, toolErrorCount, assistantStep));
                    appendMessage(request.turnId(), messages, actions, fallbackMessage);
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    return AgentTurnResult.emptyFallback(
                            List.copyOf(messages),
                            new TurnPersistencePlan(actions),
                            Optional.of(new EmptyFallbackDetails(
                                    Optional.of(fallbackReason),
                                    Optional.of(emptyFallbackDiagnostics(assistantStep, sawToolResultThisTurn,
                                            toolErrorCount)),
                                    sawToolResultThisTurn,
                                    toolErrorCount
                            ))
                    );
                }

                emptyResponseCount = 0;

                switch (assistantStep.kind()) {
                    case FINAL, UNSPECIFIED -> {
                        appendAssistantThinkingBlocks(request.turnId(), messages, actions, assistantStep);
                        AssistantMessage finalMessage = new AssistantMessage(
                                assistantStep.content(),
                                assistantStep.usage(),
                                UsageStaleness.fresh()
                        );
                        appendMessage(request.turnId(), messages, actions, finalMessage);
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                        return AgentTurnResult.finalResult(List.copyOf(messages), new TurnPersistencePlan(actions));
                    }
                    case PROGRESS -> {
                        appendAssistantThinkingBlocks(request.turnId(), messages, actions, assistantStep);
                        AssistantProgressMessage progressMessage = new AssistantProgressMessage(
                                assistantStep.content(),
                                assistantStep.usage(),
                                UsageStaleness.fresh()
                        );
                        appendMessage(request.turnId(), messages, actions, progressMessage);
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                        appendMessage(request.turnId(), messages, actions, new UserMessage(PROGRESS_CONTINUATION_PROMPT));
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    }
                }
            }

            request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
            return AgentTurnResult.maxSteps(List.copyOf(messages), new TurnPersistencePlan(actions));
        } catch (CancellationRequestedException exception) {
            return cancelledResult(request.turnId(), messages, actions, exception.cancellation());
        }
    }

    /**
     * 调一次模型，最多重试 {@link #MODEL_REQUEST_ATTEMPTS} 次。
     *
     * <p>关键设计：
     * <ul>
     *   <li>{@link CancellationRequestedException} 立即往外抛，不参与重试——取消优先级最高。</li>
     *   <li>{@link ModelRequestException} 与其他 {@link RuntimeException} 分别保留，
     *       交给上层区分"模型业务错"和"程序/网络异常"，错误信息更精确。</li>
     *   <li>每次重试前再 check 一次取消，避免在等下一轮 sleep 期间用户已经按了 Ctrl+C。</li>
     * </ul>
     *
     * @return 模型给出的下一步；正常返回时不会为 null（由 {@link ModelAdapter} 实现保证）
     * @throws CancellationRequestedException 取消信号触发时
     * @throws RuntimeException               最后一次重试仍失败时，抛出最后一次记录的异常
     */
    private AgentStep nextWithRetries(List<ChatMessage> messages, CancellationToken cancellationToken) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MODEL_REQUEST_ATTEMPTS; attempt++) {
            try {
                return modelAdapter.next(messages);
            } catch (CancellationRequestedException exception) {
                throw exception;
            } catch (ModelRequestException exception) {
                lastException = exception;
            } catch (RuntimeException exception) {
                lastException = exception;
            }
            cancellationToken.throwIfCancellationRequested(CancellationPhase.MODEL_REQUEST);
        }
        throw Objects.requireNonNull(lastException, "lastException");
    }

    private static ContextStatsCalculator defaultContextStatsCalculator() {
        return new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(128_000, 8_000));
    }

    /**
     * 自动压缩前置检查：如果 token 接近上下文窗口上限，就在调模型前先把历史摘要压缩一轮。
     *
     * <p>三种状态各推一种事件：STARTED（开始尝试）、COMPLETED（压缩成功，并写入持久化动作）、
     * FAILED（压缩失败，循环继续走旧消息）、SKIPPED（未达阈值，仅在"非常规跳过"时才推事件，
     * 避免事件流被低阈值跳过淹没）。
     */
    private AutoCompactResult runAutoCompactPreflight(String turnId, List<ChatMessage> messages,
                                                      List<PersistenceAction> actions, ContextStats stats) {
        if (autoCompactController.willAttempt(List.copyOf(messages), stats)) {
            publishEvent(new AgentEvent.AutoCompactEvent(turnId, Instant.now(),
                    AutoCompactEventType.STARTED, Optional.empty(), Optional.empty()));
        }
        AutoCompactResult result = autoCompactController.preflight(List.copyOf(messages), stats, modelAdapter);
        switch (result.status()) {
            case COMPACTED -> {
                publishEvent(new AgentEvent.AutoCompactEvent(turnId, Instant.now(),
                        AutoCompactEventType.COMPLETED, Optional.of(result.compressionResult()), Optional.empty()));
                appendAutoCompactPersistenceActions(actions, result);
            }
            case FAILED -> {
                publishEvent(new AgentEvent.AutoCompactEvent(turnId, Instant.now(),
                        AutoCompactEventType.FAILED, Optional.empty(),
                        Optional.of(result.reason().orElse("auto compact failed"))));
            }
            case SKIPPED -> publishAutoCompactSkippedIfRelevant(turnId, result);
        }
        return result;
    }

    private void publishAutoCompactSkippedIfRelevant(String turnId, AutoCompactResult result) {
        String reason = result.reason().orElse("");
        if (reason.contains("below auto compact threshold")
                || reason.contains("below auto compact minimum")
                || reason.contains("auto compact disabled")) {
            return;
        }
        publishEvent(new AgentEvent.AutoCompactEvent(turnId, Instant.now(),
                AutoCompactEventType.SKIPPED, Optional.empty(), Optional.of(reason)));
    }

    private void appendAutoCompactPersistenceActions(List<PersistenceAction> actions, AutoCompactResult result) {
        minicode.context.compact.CompressionBoundaryResult boundary = result.boundary().orElseThrow();
        actions.add(new PersistenceAction.AppendCompactBoundaryAction(boundary.summaryMessage(), boundary.metadata()));
        List<ChatMessage> retainedMessages = retainedMessagesAfterCompactBoundary(result.messages(), boundary.summaryMessage());
        if (!retainedMessages.isEmpty()) {
            actions.add(new PersistenceAction.AppendMessagesAction(retainedMessages));
        }
    }

    private List<ChatMessage> retainedMessagesAfterCompactBoundary(List<ChatMessage> compactedMessages,
                                                                   ChatMessage summaryMessage) {
        boolean skippedSummary = false;
        List<ChatMessage> retained = new ArrayList<>();
        for (ChatMessage message : compactedMessages) {
            if (message instanceof SystemMessage) {
                continue;
            }
            if (!skippedSummary && message.equals(summaryMessage)) {
                skippedSummary = true;
                continue;
            }
            retained.add(message);
        }
        return List.copyOf(retained);
    }

    /**
     * 追加一条消息：同时更新内存里的 messages、记录持久化动作、并推送 AssistantMessageEvent。
     * 这三件事必须打包在一起做，否则 UI、内存、磁盘三者会出现不一致。
     */
    private void appendMessage(String turnId, List<ChatMessage> messages, List<PersistenceAction> actions,
                               ChatMessage message) {
        messages.add(message);
        actions.add(new PersistenceAction.AppendMessagesAction(List.of(message)));
        publishEvent(new AgentEvent.AssistantMessageEvent(turnId, Instant.now(), message));
    }

    private void appendAssistantThinkingBlocks(String turnId, List<ChatMessage> messages,
                                               List<PersistenceAction> actions, AssistantStep step) {
        if (!step.thinkingBlocks().isEmpty()) {
            appendMessage(turnId, messages, actions, new AssistantThinkingMessage(step.thinkingBlocks()));
        }
    }

    private void appendToolCallMessage(String turnId, List<ChatMessage> messages, List<PersistenceAction> actions,
                                       ToolCall call, Optional<minicode.model.ProviderUsage> usage) {
        AssistantToolCallMessage message = new AssistantToolCallMessage(
                call.id(),
                call.toolName(),
                call.input(),
                usage,
                UsageStaleness.fresh()
        );
        appendMessage(turnId, messages, actions, message);
    }

    private ToolResultMessage appendToolResultMessage(String turnId, List<ChatMessage> messages,
                                                      List<PersistenceAction> actions, ToolCall call,
                                                      ToolResult result) {
        ToolResultMessage originalMessage = new ToolResultMessage(call.id(), call.toolName(), result.content(), result.error());
        ToolResultReplacementResult replacementResult = contextManager.replaceLargeToolResult(originalMessage);
        ToolResultMessage message = replacementResult.message();
        appendMessage(turnId, messages, actions, message);
        publishEvent(new AgentEvent.ToolFinishedEvent(
                turnId,
                Instant.now(),
                call.id(),
                call.toolName(),
                result.error(),
                result.awaitUser(),
                replacementResult.replacement()
        ));
        return message;
    }

    /**
     * 把本轮新增的 ToolResultMessage 交给 contextManager 做 token 预算控制：
     * 太大的工具结果会被替换成精简版（如"output truncated, 12345 bytes omitted"），
     * 防止下一次模型请求的 prompt 直接超限。
     *
     * <p>同步更新 in-memory messages、actions（持久化记录）和入参列表本身，
     * 并在确实有替换发生时推 ToolResultsBudgetedEvent。
     */
    private void applyToolResultBudget(String turnId, List<ChatMessage> messages, List<PersistenceAction> actions,
                                       List<ToolResultMessage> toolResultMessages) {
        ToolResultBudgetResult budgetResult = contextManager.applyToolResultBudget(List.copyOf(toolResultMessages));
        applyBudgetedToolResults(messages, actions, toolResultMessages, budgetResult.results());
        toolResultMessages.clear();
        toolResultMessages.addAll(budgetResult.results());
        if (!budgetResult.replacements().isEmpty()) {
            publishEvent(new ToolResultsBudgetedEvent(turnId, Instant.now(), budgetResult.replacements()));
        }
    }

    private void applyBudgetedToolResults(List<ChatMessage> messages, List<PersistenceAction> actions,
                                          List<ToolResultMessage> originalResults,
                                          List<ToolResultMessage> budgetedResults) {
        if (originalResults.size() != budgetedResults.size()) {
            throw new IllegalStateException("budgeted tool result count must match original result count");
        }
        for (int index = 0; index < originalResults.size(); index++) {
            ToolResultMessage original = originalResults.get(index);
            ToolResultMessage budgeted = budgetedResults.get(index);
            if (original.equals(budgeted)) {
                continue;
            }
            replaceToolResultMessage(messages, original, budgeted);
            replaceToolResultAction(actions, original, budgeted);
        }
    }

    private void replaceToolResultMessage(List<ChatMessage> messages, ToolResultMessage original,
                                          ToolResultMessage replacement) {
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message instanceof ToolResultMessage toolResult && sameToolResultSlot(toolResult, original)) {
                messages.set(index, replacement);
                return;
            }
        }
    }

    private void replaceToolResultAction(List<PersistenceAction> actions, ToolResultMessage original,
                                         ToolResultMessage replacement) {
        for (int actionIndex = 0; actionIndex < actions.size(); actionIndex++) {
            PersistenceAction action = actions.get(actionIndex);
            if (action instanceof PersistenceAction.AppendMessagesAction appendMessagesAction) {
                List<ChatMessage> actionMessages = appendMessagesAction.messages();
                for (int messageIndex = 0; messageIndex < actionMessages.size(); messageIndex++) {
                    ChatMessage message = actionMessages.get(messageIndex);
                    if (message instanceof ToolResultMessage toolResult && sameToolResultSlot(toolResult, original)) {
                        List<ChatMessage> replacementMessages = new ArrayList<>(actionMessages);
                        replacementMessages.set(messageIndex, replacement);
                        actions.set(actionIndex, new PersistenceAction.AppendMessagesAction(replacementMessages));
                        return;
                    }
                }
            }
        }
    }

    private boolean sameToolResultSlot(ToolResultMessage candidate, ToolResultMessage expected) {
        return candidate.toolUseId().equals(expected.toolUseId())
                && candidate.toolName().equals(expected.toolName())
                && candidate.content().equals(expected.content())
                && candidate.error() == expected.error();
    }

    private String awaitUserQuestion(String toolUseId, List<ToolResultMessage> toolResultMessages) {
        return toolResultMessages.stream()
                .filter(message -> message.toolUseId().equals(toolUseId))
                .findFirst()
                .map(ToolResultMessage::content)
                .orElse("");
    }

    private void appendToolCallsStepProjection(String turnId, List<ChatMessage> messages,
                                               List<PersistenceAction> actions, ToolCallsStep step) {
        step.content()
                .filter(content -> !content.isBlank())
                .ifPresent(content -> {
                    appendMessage(turnId, messages, actions, projectToolCallsContent(step, content));
                    if (step.contentKind() == minicode.core.step.ContentKind.PROGRESS) {
                        appendMessage(turnId, messages, actions, new UserMessage(PROGRESS_CONTINUATION_PROMPT));
                    }
                });

        if (!step.thinkingBlocks().isEmpty()) {
            appendMessage(turnId, messages, actions, new AssistantThinkingMessage(step.thinkingBlocks()));
        }
    }

    private ChatMessage projectToolCallsContent(ToolCallsStep step, String content) {
        return switch (step.contentKind()) {
            case PROGRESS -> new AssistantProgressMessage(content);
            case UNSPECIFIED -> new AssistantMessage(content);
        };
    }

    /**
     * 判断本步是否属于"可恢复的思考阶段截断"：
     * 模型在 thinking 阶段被 max_tokens 或 pause_turn 打断，content 是空但其实只是"还没想完"，
     * 这种情况下应该催它继续而不是当成空响应放弃。
     */
    private boolean isRecoverableThinkingStop(AssistantStep step) {
        if (!step.content().isBlank() || step.diagnostics().isEmpty()) {
            return false;
        }
        minicode.model.StepDiagnostics diagnostics = step.diagnostics().orElseThrow();
        String stopReason = diagnostics.stopReason().orElse("");
        if (!"pause_turn".equals(stopReason) && !"max_tokens".equals(stopReason)) {
            return false;
        }
        return !step.thinkingBlocks().isEmpty()
                || diagnostics.blockTypes().contains("thinking")
                || diagnostics.ignoredBlockTypes().contains("thinking");
    }

    /**
     * 根据上下文挑选合适的"催继续"prompt：调过工具有错 / 调过工具没错 / 没调过工具，
     * 三种话术对模型的引导效果差异很大，是 Agent 行为修正里很容易被忽略的细节。
     */
    private String emptyResponseContinuationPrompt(boolean sawToolResultThisTurn, int toolErrorCount) {
        if (toolErrorCount > 0) {
            return EMPTY_RESPONSE_AFTER_TOOL_ERROR_CONTINUATION_PROMPT;
        }
        if (sawToolResultThisTurn) {
            return EMPTY_RESPONSE_AFTER_TOOL_RESULT_CONTINUATION_PROMPT;
        }
        return EMPTY_RESPONSE_CONTINUATION_PROMPT;
    }

    private String emptyFallbackReason(boolean sawToolResultThisTurn, int toolErrorCount) {
        if (toolErrorCount > 0) {
            return "empty_after_tool_error";
        }
        if (sawToolResultThisTurn) {
            return "empty_after_tool_result";
        }
        return "empty_response_retry_exhausted";
    }

    private String emptyFallbackMessage(boolean sawToolResultThisTurn, int toolErrorCount, AssistantStep step) {
        String diagnostics = formatDiagnostics(step);
        if (toolErrorCount > 0) {
            return "The model returned an empty response after recent tool results. Stopping this turn after "
                    + toolErrorCount + (toolErrorCount == 1 ? " tool error" : " tool errors") + "."
                    + diagnostics;
        }
        if (sawToolResultThisTurn) {
            return "The model returned an empty response after recent tool results. Stopping this turn; retry or ask the model to continue from the tool output."
                    + diagnostics;
        }
        return EMPTY_RESPONSE_MESSAGE + diagnostics;
    }

    private String emptyFallbackDiagnostics(AssistantStep step, boolean sawToolResultThisTurn, int toolErrorCount) {
        List<String> parts = new ArrayList<>();
        parts.add("reason=" + emptyFallbackReason(sawToolResultThisTurn, toolErrorCount));
        parts.add("sawToolResultThisTurn=" + sawToolResultThisTurn);
        parts.add("toolErrorCount=" + toolErrorCount);
        step.diagnostics().flatMap(minicode.model.StepDiagnostics::stopReason)
                .ifPresent(stopReason -> parts.add("stopReason=" + stopReason));
        step.diagnostics().ifPresent(diagnostics -> {
            if (!diagnostics.blockTypes().isEmpty()) {
                parts.add("blocks=" + String.join(",", diagnostics.blockTypes()));
            }
            if (!diagnostics.ignoredBlockTypes().isEmpty()) {
                parts.add("ignored=" + String.join(",", diagnostics.ignoredBlockTypes()));
            }
        });
        return String.join("; ", parts);
    }

    private String formatDiagnostics(AssistantStep step) {
        if (step.diagnostics().isEmpty()) {
            return "";
        }
        minicode.model.StepDiagnostics diagnostics = step.diagnostics().orElseThrow();
        List<String> parts = new ArrayList<>();
        diagnostics.stopReason().ifPresent(stopReason -> parts.add("stopReason=" + stopReason));
        if (!diagnostics.blockTypes().isEmpty()) {
            parts.add("blocks=" + String.join(",", diagnostics.blockTypes()));
        }
        if (!diagnostics.ignoredBlockTypes().isEmpty()) {
            parts.add("ignored=" + String.join(",", diagnostics.ignoredBlockTypes()));
        }
        return parts.isEmpty() ? "" : " Diagnostics: " + String.join("; ", parts) + ".";
    }

    private ToolContext createToolContext(AgentTurnRequest request, String toolUseId) {
        return new ToolContext(request.cwd(), request.sessionId(), Optional.of(request.turnId()), Optional.of(toolUseId),
                request.cancellationToken());
    }

    /**
     * 推事件到 {@link AgentEventSink}，并吃掉所有 RuntimeException。
     *
     * <p>关键设计：UI/日志/订阅层失败属于"观察侧"问题，绝不能反过来打断核心 turn 流程
     * （比如 UI 渲染崩了，agent 也得继续把任务做完）。所以这里有意吞掉异常。
     */
    private void publishEvent(AgentEvent event) {
        try {
            eventSink.onEvent(event);
        } catch (RuntimeException ignored) {
            // Sink failures are observational and must not interrupt core turn progression.
        }
    }

    private AgentTurnResult modelErrorResult(List<ChatMessage> messages, List<PersistenceAction> actions,
                                             String errorMessage, String causeClass) {
        return AgentTurnResult.modelError(
                List.copyOf(messages),
                new TurnPersistencePlan(actions),
                new ModelErrorDetails(new TurnError(
                        errorMessage,
                        TurnErrorSource.MODEL,
                        false,
                        Optional.empty(),
                        Optional.of(causeClass)
                ))
        );
    }

    private AgentTurnResult modelErrorResult(List<ChatMessage> messages, List<PersistenceAction> actions,
                                             ModelRequestException exception) {
        String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Model request failed"
                : exception.getMessage();
        Optional<String> diagnostics = enrichedModelErrorDiagnostics(exception, messages);
        return AgentTurnResult.modelError(
                List.copyOf(messages),
                new TurnPersistencePlan(actions),
                new ModelErrorDetails(new TurnError(
                        message,
                        TurnErrorSource.MODEL,
                        exception.retryable(),
                        diagnostics,
                        Optional.of(ModelRequestException.class.getName())
                ))
        );
    }

    private Optional<String> enrichedModelErrorDiagnostics(ModelRequestException exception, List<ChatMessage> messages) {
        List<String> parts = new ArrayList<>();
        exception.diagnostics().ifPresent(parts::add);
        exception.statusCode().ifPresent(statusCode -> {
            String existingDiagnostics = exception.diagnostics().orElse("");
            String normalized = existingDiagnostics.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
            if (existingDiagnostics.isBlank()
                    || (!normalized.contains("statuscode=") && !normalized.contains("status="))) {
                parts.add("statusCode=" + statusCode);
            }
        });
        if (recentToolHistory(messages)) {
            parts.add("recentToolHistory=true");
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", parts));
    }

    private boolean recentToolHistory(List<ChatMessage> messages) {
        int start = Math.max(0, messages.size() - 12);
        for (int index = start; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message instanceof AssistantToolCallMessage || message instanceof ToolResultMessage) {
                return true;
            }
        }
        return false;
    }

    private AgentTurnResult cancelledResult(String turnId, List<ChatMessage> messages, List<PersistenceAction> actions,
                                            TurnCancellation cancellation) {
        publishEvent(new AgentEvent.TurnCancelledEvent(turnId, Instant.now(), cancellation));
        return AgentTurnResult.cancelled(
                List.copyOf(messages),
                new TurnPersistencePlan(actions),
                new CancellationDetails(cancellation)
        );
    }
}
