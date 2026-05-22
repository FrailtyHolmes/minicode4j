package minicode.context.manager;

import minicode.core.message.ChatMessage;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ToolResultMessage;
import minicode.context.stats.ContextStats;
import minicode.model.UsageStaleness;
import minicode.tools.result.ToolResultBudgetResult;
import minicode.tools.result.ToolResultReplacementRecord;
import minicode.tools.result.ToolResultReplacementResult;
import minicode.tools.result.ToolResultReplacementTrigger;
import minicode.tools.result.ToolResultStorage;
import minicode.tools.result.ToolResultStorageRef;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 上下文管理器：负责"大工具结果落盘"与"microcompact 清理可重读历史"两条核心降级策略。
 *
 * <p>它解决的问题：Coding Agent 跑久了，工具结果（尤其 {@code read_file} / {@code run_command}）
 * 会迅速吃掉上下文窗口。本类提供三个层级的处理：
 * <ul>
 *   <li>{@link #replaceLargeToolResult} — 单个 result 超过 {@code largeToolResultThreshold}（如 200KB）
 *       立即落盘，messages 里只留 {@code <persisted-output>} 占位符 + 预览 + 真实文件路径，
 *       LLM 看到占位符后可自行调 {@code read_file} 取回完整内容。</li>
 *   <li>{@link #applyToolResultBudget} — 一次工具批量返回的总字符数超过 {@code toolResultBatchBudget}
 *       时，按"贪心从最大开始落盘"策略让总和回到预算之内。</li>
 *   <li>{@link #microcompact(List, ContextStats)} — 整体 utilization &gt;= 0.50 时，把白名单里
 *       可重读工具的旧 result 替换为占位符 marker，保留最近 3 个；幂等只读结果"丢了 LLM 可重新调"。</li>
 * </ul>
 *
 * <p>设计取舍：
 * <ul>
 *   <li>占位符是 <b>in-band 自描述</b> XML，不需要单独的"占位符表"——LLM 直接读得懂 PATH 字段。</li>
 *   <li>{@link #replacementCache} 按 (toolUseId, toolName, contentHash, trigger) 去重，
 *       保证同一份内容被多次替换时只落盘一次。</li>
 *   <li>提供 {@link #noOp()} 工厂，便于测试 / 关闭场景下用零开销实例替换。</li>
 * </ul>
 */
public class ContextManager {
    /** microcompact 用来替换旧工具结果的占位文本，LLM 看到此 marker 即知道"原内容已清理，需要重新调工具"。 */
    private static final String MICROCOMPACT_MARKER = "[Output cleared for context space. Full output was already returned earlier in this session.]";
    /** microcompact 触发阈值：utilization 达到 50% 才开始清理。 */
    private static final double MICROCOMPACT_UTILIZATION = 0.50d;
    /** microcompact 保留最近 N 个工具结果不动；最近的 result 通常是 LLM 当前推理的依据。 */
    private static final int MICROCOMPACT_RETAIN_RECENT_RESULTS = 3;
    /** 可被 microcompact 清理的工具白名单：仅限"幂等只读"工具，丢了可重新调。 */
    private static final java.util.Set<String> MICROCOMPACTABLE_TOOLS = java.util.Set.of(
            "read_file", "run_command", "list_files", "grep_files"
    );
    private final ToolResultStorage storage;
    /** 单个工具结果触发落盘的字符数阈值（如 200_000）。 */
    private final int largeToolResultThreshold;
    /** 一次批量返回的工具结果总字符数预算（如 400_000），超过则贪心落盘大的。 */
    private final int toolResultBatchBudget;
    /** 落盘后保留在占位符里的预览字符数（如 20_000），让 LLM 大致知道内容是什么。 */
    private final int previewChars;
    /** 为 true 时所有方法都退化为不修改输入直接返回，用于关闭上下文管理的测试场景。 */
    private final boolean noOp;
    /** 内容指纹去重缓存，避免相同内容被多次落盘成多份磁盘文件。 */
    private final Map<ReplacementCacheKey, ToolResultReplacementRecord> replacementCache = new HashMap<>();

    /**
     * 简化构造：批量预算与单条阈值取相同值。
     *
     * @param storage                  落盘存储后端
     * @param largeToolResultThreshold 单条工具结果触发落盘的字符数阈值
     * @param previewChars             落盘后保留在占位符里的预览字符数
     */
    public ContextManager(ToolResultStorage storage, int largeToolResultThreshold, int previewChars) {
        this(storage, largeToolResultThreshold, largeToolResultThreshold, previewChars);
    }

    /**
     * 完整构造，分别指定单条阈值与批量预算。
     *
     * @param storage                  落盘存储后端，不能为 null
     * @param largeToolResultThreshold 单条工具结果触发落盘的字符数阈值，必须非负
     * @param toolResultBatchBudget    批量工具结果总字符数预算，必须非负
     * @param previewChars             落盘后保留的预览字符数，必须非负
     * @throws IllegalArgumentException 任一阈值为负
     */
    public ContextManager(ToolResultStorage storage, int largeToolResultThreshold, int toolResultBatchBudget,
                          int previewChars) {
        this.storage = Objects.requireNonNull(storage, "storage");
        if (largeToolResultThreshold < 0) {
            throw new IllegalArgumentException("largeToolResultThreshold must be non-negative");
        }
        if (toolResultBatchBudget < 0) {
            throw new IllegalArgumentException("toolResultBatchBudget must be non-negative");
        }
        if (previewChars < 0) {
            throw new IllegalArgumentException("previewChars must be non-negative");
        }
        this.largeToolResultThreshold = largeToolResultThreshold;
        this.toolResultBatchBudget = toolResultBatchBudget;
        this.previewChars = previewChars;
        this.noOp = false;
    }

    private ContextManager() {
        this.storage = null;
        this.largeToolResultThreshold = Integer.MAX_VALUE;
        this.toolResultBatchBudget = Integer.MAX_VALUE;
        this.previewChars = 0;
        this.noOp = true;
    }

    /**
     * 创建一个不做任何处理的占位实例。
     *
     * <p>所有方法都会原样返回输入，适合测试或显式关闭上下文管理的场景；
     * 与正常实例的区别只是 {@code noOp} 标志位。
     *
     * @return 不做任何动作的 {@code ContextManager}
     */
    public static ContextManager noOp() {
        return new ContextManager();
    }

    /**
     * 若单个工具结果内容超过 {@code largeToolResultThreshold}，将其内容落盘并替换为
     * {@code <persisted-output>} 占位符。
     *
     * <p>占位符里包含 STORAGE_REF / PATH / ORIGINAL_CHARS / PREVIEW 四类信息，
     * LLM 可基于 PREVIEW 推理，必要时通过 {@code read_file} 读 PATH 取回完整内容。
     *
     * <p>已是占位符的内容会原样返回，避免反复落盘。
     *
     * @param message 待检查的工具结果消息
     * @return 替换结果：包含可能被替换的新消息以及落盘记录（未触发时记录为空）
     */
    public ToolResultReplacementResult replaceLargeToolResult(ToolResultMessage message) {
        ToolResultMessage actualMessage = Objects.requireNonNull(message, "message");
        if (noOp) {
            return new ToolResultReplacementResult(actualMessage, Optional.empty());
        }
        String content = actualMessage.content();
        if (content.length() <= largeToolResultThreshold || isPersistedOutput(content)) {
            return new ToolResultReplacementResult(actualMessage, Optional.empty());
        }

        ToolResultReplacementRecord record = replacementFor(
                actualMessage,
                content,
                ToolResultReplacementTrigger.SINGLE_RESULT_TOO_LARGE
        );
        ToolResultMessage replacementMessage = new ToolResultMessage(
                actualMessage.toolUseId(),
                actualMessage.toolName(),
                record.replacementContent(),
                actualMessage.error()
        );
        return new ToolResultReplacementResult(replacementMessage, Optional.of(record));
    }

    /**
     * 当一批工具结果的总字符数超过 {@code toolResultBatchBudget} 时，按"贪心从最大开始落盘"
     * 让总和回到预算之内。
     *
     * <p>典型场景：LLM 一次同时调 3 个 {@code read_file}，每个 100KB（单个未超 200KB 阈值），
     * 但总和 300KB 超过批量预算，需要把最大的那个先落盘。
     *
     * <p>算法说明：
     * <ol>
     *   <li>过滤掉已是 {@code <persisted-output>} 占位符的项（不再二次落盘）。</li>
     *   <li>按内容长度<b>从大到小</b>排序，作为落盘候选。</li>
     *   <li>循环落盘候选，每次重新计算总长度；当总和落到预算以下且至少落盘了一项时停止。</li>
     * </ol>
     *
     * @param results 一次返回的多条工具结果消息（不可为 null）
     * @return 包含调整后的消息列表与落盘记录的结果对象；未触发时返回原列表
     */
    public ToolResultBudgetResult applyToolResultBudget(List<ToolResultMessage> results) {
        List<ToolResultMessage> actualResults = List.copyOf(Objects.requireNonNull(results, "results"));
        if (noOp || actualResults.isEmpty()) {
            return new ToolResultBudgetResult(actualResults, List.of());
        }

        int totalChars = actualResults.stream().mapToInt(message -> message.content().length()).sum();
        if (totalChars <= toolResultBatchBudget) {
            return new ToolResultBudgetResult(actualResults, List.of());
        }

        List<ToolResultMessage> budgetedResults = new ArrayList<>(actualResults);
        List<ToolResultReplacementRecord> replacements = new ArrayList<>();
        List<Integer> candidateIndexes = new ArrayList<>();
        for (int index = 0; index < budgetedResults.size(); index++) {
            if (!isPersistedOutput(budgetedResults.get(index).content())) {
                candidateIndexes.add(index);
            }
        }
        candidateIndexes.sort(Comparator
                .comparingInt((Integer index) -> budgetedResults.get(index).content().length())
                .reversed());

        for (int index : candidateIndexes) {
            if (totalChars <= toolResultBatchBudget && !replacements.isEmpty()) {
                break;
            }
            ToolResultMessage original = budgetedResults.get(index);
            String originalContent = original.content();
            ToolResultReplacementRecord replacement = replacementFor(
                    original,
                    originalContent,
                    ToolResultReplacementTrigger.BATCH_BUDGET_EXCEEDED
            );
            ToolResultMessage replacementMessage = new ToolResultMessage(
                    original.toolUseId(),
                    original.toolName(),
                    replacement.replacementContent(),
                    original.error()
            );
            budgetedResults.set(index, replacementMessage);
            replacements.add(replacement);
            totalChars = totalChars - originalContent.length() + replacementMessage.content().length();
        }

        return new ToolResultBudgetResult(budgetedResults, replacements);
    }

    /**
     * 兼容重载：不带 stats 时直接返回消息副本，<b>不做</b>压缩。
     *
     * <p>仅用于不需要看使用率就能调用的旧路径；正式触发 microcompact 应使用
     * {@link #microcompact(List, ContextStats)}。
     *
     * @param messages 当前对话消息列表
     * @return 输入列表的不可变副本
     */
    public List<ChatMessage> microcompact(List<ChatMessage> messages) {
        return List.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    /**
     * 在 utilization &gt;= 50% 时清理"可重读"工具的旧结果，把内容替换为 {@link #MICROCOMPACT_MARKER}。
     *
     * <p>关键洞察：{@code read_file} / {@code list_files} / {@code grep_files} / {@code run_command}
     * 这些工具的结果可被重新获取，所以可以"忘记"，相比 autoCompact 几乎无感损失。
     * 已落盘的 {@code <persisted-output>} 与已替换为 marker 的内容不会被再次清理。
     *
     * <p>处理顺序：先收集所有可压缩 result 的下标，超过保留数量 3 的部分按"最旧的先清"被替换；
     * 一旦发生替换，会调用 {@link #markProviderUsageStale} 把对应的助手消息 usage 标记为陈旧
     * （因为底层 provider 上报的 token 数已不再匹配实际消息内容）。
     *
     * @param messages 当前对话消息列表（不可为 null）
     * @param stats    当前上下文画像，决定是否触发
     * @return 清理后的消息列表；未触发或没有可清理项时原样返回
     */
    public List<ChatMessage> microcompact(List<ChatMessage> messages, ContextStats stats) {
        List<ChatMessage> actualMessages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(stats, "stats");
        if (noOp || actualMessages.isEmpty()) {
            return actualMessages;
        }
        if (stats.utilization() < MICROCOMPACT_UTILIZATION) {
            return actualMessages;
        }
        List<Integer> compactableIndexes = new ArrayList<>();
        for (int index = 0; index < actualMessages.size(); index++) {
            ChatMessage message = actualMessages.get(index);
            if (message instanceof ToolResultMessage toolResult
                    && MICROCOMPACTABLE_TOOLS.contains(toolResult.toolName())
                    && !toolResult.content().startsWith("<persisted-output ")
                    && !MICROCOMPACT_MARKER.equals(toolResult.content())) {
                compactableIndexes.add(index);
            }
        }
        int clearCount = compactableIndexes.size() - MICROCOMPACT_RETAIN_RECENT_RESULTS;
        if (clearCount <= 0) {
            return actualMessages;
        }
        List<ChatMessage> compacted = new ArrayList<>(actualMessages);
        boolean changed = false;
        for (int index = 0; index < clearCount; index++) {
            int messageIndex = compactableIndexes.get(index);
            ToolResultMessage original = (ToolResultMessage) compacted.get(messageIndex);
            compacted.set(messageIndex, new ToolResultMessage(
                    original.toolUseId(),
                    original.toolName(),
                    MICROCOMPACT_MARKER,
                    original.error()
            ));
            changed = true;
        }
        return changed ? List.copyOf(markProviderUsageStale(compacted)) : actualMessages;
    }

    /**
     * microcompact 之后把所有助手消息携带的 provider usage 标记为陈旧。
     *
     * <p>因为 provider 上报的 token 数是基于"压缩前"的内容统计的；内容被替换为 marker 后，
     * 原 usage 数已不准确，必须打上 stale 标记，避免后续按陈旧 usage 决策。
     */
    private List<ChatMessage> markProviderUsageStale(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>(messages.size());
        String reason = "tool_result content was microcompacted after this provider usage was recorded";
        for (ChatMessage message : messages) {
            result.add(markProviderUsageStale(message, reason));
        }
        return result;
    }

    private ChatMessage markProviderUsageStale(ChatMessage message, String reason) {
        UsageStaleness staleness = UsageStaleness.stale(reason);
        return switch (message) {
            case AssistantMessage assistant when assistant.providerUsage().isPresent()
                    && !assistant.usageStaleness().stale() ->
                    new AssistantMessage(assistant.content(), assistant.providerUsage(), staleness);
            case AssistantProgressMessage progress when progress.providerUsage().isPresent()
                    && !progress.usageStaleness().stale() ->
                    new AssistantProgressMessage(progress.content(), progress.providerUsage(), staleness);
            case AssistantToolCallMessage toolCall when toolCall.providerUsage().isPresent()
                    && !toolCall.usageStaleness().stale() ->
                    new AssistantToolCallMessage(toolCall.toolUseId(), toolCall.toolName(), toolCall.input(),
                            toolCall.providerUsage(), staleness);
            default -> message;
        };
    }

    /**
     * 落盘内容并构造替换记录；同一份内容（按 toolUseId/toolName/SHA-256/trigger 去重）只落盘一次。
     *
     * <p>该方法<b>有显著副作用</b>：会真正向 {@link ToolResultStorage} 写盘并更新内存缓存。
     */
    private ToolResultReplacementRecord replacementFor(ToolResultMessage message, String content,
                                                       ToolResultReplacementTrigger trigger) {
        ReplacementCacheKey key = new ReplacementCacheKey(
                message.toolUseId(),
                message.toolName(),
                contentHash(content),
                trigger
        );
        ToolResultReplacementRecord cached = replacementCache.get(key);
        if (cached != null) {
            return cached;
        }

        ToolResultStorageRef storageRef = Objects.requireNonNull(storage, "storage").store(content);
        String preview = preview(content);
        String replacementContent = replacementContent(message, storageRef, content, preview);
        ToolResultReplacementRecord record = new ToolResultReplacementRecord(
                message.toolUseId(),
                message.toolName(),
                trigger,
                storageRef,
                replacementContent,
                preview,
                content.length(),
                preview.length(),
                replacementContent.length()
        );
        replacementCache.put(key, record);
        return record;
    }

    /**
     * 拼装 {@code <persisted-output>} 占位符文本：把 LLM 重新读取所需的全部元信息
     * （toolUseId、toolName、storageRef、PATH、ORIGINAL_CHARS、PREVIEW）放进一个自描述 XML 块。
     */
    private String replacementContent(ToolResultMessage message, ToolResultStorageRef storageRef, String content,
                                      String preview) {
        return String.join("\n",
                "<persisted-output toolUseId=\"" + message.toolUseId() + "\" toolName=\"" + message.toolName() + "\">",
                "STORAGE_REF: " + storageRef.id(),
                "PATH: " + storageRef.path(),
                "BYTES: " + storageRef.bytes(),
                "ORIGINAL_CHARS: " + content.length(),
                "PREVIEW:",
                preview,
                "</persisted-output>"
        );
    }

    private String preview(String content) {
        return content.substring(0, Math.min(previewChars, content.length()));
    }

    private static boolean isPersistedOutput(String content) {
        return content.startsWith("<persisted-output ");
    }

    private static String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ReplacementCacheKey(String toolUseId, String toolName, String contentHash,
                                       ToolResultReplacementTrigger trigger) {
        private ReplacementCacheKey {
            requireText(toolUseId, "toolUseId");
            requireText(toolName, "toolName");
            requireText(contentHash, "contentHash");
            trigger = Objects.requireNonNull(trigger, "trigger");
        }

        private static void requireText(String value, String name) {
            if (Objects.requireNonNull(value, name).isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }
}
