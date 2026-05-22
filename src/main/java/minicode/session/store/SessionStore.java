package minicode.session.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.context.compact.CompactMetadata;
import minicode.context.compact.CompactTrigger;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantThinkingMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.model.ProviderThinkingBlock;
import minicode.model.ProviderUsage;
import minicode.model.UsageStaleness;
import minicode.session.model.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Session 持久化的「物理存储层」：把每条 {@link SessionEvent} 序列化为一行 JSON，append-only
 * 写入磁盘上的 JSONL 文件，并在 resume 时把文件回放成 {@link ChatMessage} 列表。
 *
 * <p>文件布局：{@code <root>/<base64(cwd)>/<sanitize(sessionId)>.jsonl}。
 * 同一 cwd 的所有会话集中在同一个目录下；不同 cwd 之间通过目录隔离，避免互相污染。
 *
 * <p>关键设计取舍：
 * <ul>
 *   <li><b>Append-only JSONL</b>：所有写入都用 {@code StandardOpenOption.APPEND}，依赖 OS 单次
 *       write 的原子性保证多行 JSON 不会交错；不引入任何外部数据库依赖（详见 ch07 §4.1 / §8）。</li>
 *   <li><b>按 cwd 隔离</b>：cwd 经 base64-url 编码作为目录名，避免特殊字符问题，也让同一 sessionId
 *       理论上可以出现在多个 cwd 下（{@link #findCwdsForSessionId} 用于跨 cwd 查找）。</li>
 *   <li><b>Replay 模型（Event Sourcing）</b>：写入只追加事件；读取通过 {@code readAll + 投影}
 *       重建消息列表。{@link #loadMessagesSinceLatestCompactBoundary} 把最近的 compact boundary
 *       当作 snapshot，只回放其后事件，避免长会话 resume 退化为 O(全量)。</li>
 *   <li><b>无锁</b>：单进程内 append 是安全的；多进程并发追加同一文件依赖 OS 的 append 原子性。</li>
 * </ul>
 */
public final class SessionStore {
    /** 全局共用的 Jackson ObjectMapper；该类的 read/write 都是无状态的，可以安全复用。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 所有 session 文件的根目录，例如 {@code ~/.minicode-java/sessions}。 */
    private final Path root;

    /**
     * @param root 所有 session JSONL 文件存放的根目录；目录不存在时会在第一次 append 时自动创建
     */
    public SessionStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * 把一条事件原子性地追加写入对应会话的 JSONL 文件末尾（一行一 JSON，行尾为系统换行符）。
     *
     * <p>使用 {@code StandardOpenOption.CREATE | APPEND} 组合：
     * 文件不存在则创建；存在则在末尾追加。OS 保证单次 write 在多进程并发下也不会被打断/交错。
     *
     * @param event 待持久化的事件，不可为 {@code null}
     * @throws UncheckedIOException 当目录创建或写入失败时
     */
    public void append(SessionEvent event) {
        Objects.requireNonNull(event, "event");
        Path file = sessionFile(event.sessionId(), event.cwd());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, serialize(event) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 把指定会话的整份 JSONL 文件全部读出来，按行反序列化成事件列表（保持原顺序）。
     *
     * <p>跳过空行；遇到非法 JSON 行会直接抛出 {@link IllegalArgumentException}（来自 deserialize），
     * 所以一份被人为改坏的 session 文件无法 resume —— 这是有意为之的「fail-fast」。
     *
     * @param sessionId 会话 ID
     * @param cwd       工作目录
     * @return 不可变的事件列表；文件不存在时返回空列表
     */
    public List<SessionEvent> readAll(String sessionId, String cwd) {
        Path file = sessionFile(sessionId, cwd);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<SessionEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    events.add(deserialize(line));
                }
            }
            return List.copyOf(events);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * {@link #readAll} 的语义化别名：明确表达「这是 resume 流程要用的事件序列」。
     */
    public List<SessionEvent> resumeEvents(String sessionId, String cwd) {
        return readAll(sessionId, cwd);
    }

    /**
     * 取该会话最近一条事件的 uuid，用作下一次 append 的 parentUuid 链起点。
     *
     * <p>{@code ApplicationServices} 启动时会调一次本方法以初始化
     * {@link minicode.session.factory.SessionEventFactory}，从而保证「resume 后追加的事件」
     * 与「退出前已写的事件」之间形成完整 parentUuid 链。
     *
     * @return 最新事件 uuid；文件为空或不存在时返回 {@link Optional#empty()}
     */
    public Optional<String> latestEventUuid(String sessionId, String cwd) {
        List<SessionEvent> events = readAll(sessionId, cwd);
        if (events.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(events.getLast().uuid());
    }

    /**
     * 从「最近一次 COMPACT_BOUNDARY 之后」回放出可直接喂给 AgentLoop 的 {@link ChatMessage} 列表。
     *
     * <p>算法：从后往前扫事件，找到最近一个 {@code COMPACT_BOUNDARY}；从它的下一条开始，
     * 把每条事件携带的 {@code message()} 抽出来组成消息列表。如果没有 boundary，则从头开始。
     *
     * <p>设计意图：早于 boundary 的原始事件已经被 autoCompact 摘要进 {@code ContextSummaryMessage}，
     * 不需要再回放。boundary 起到「snapshot」的作用，使 resume 复杂度从 O(全部事件) 降为 O(boundary 后)。
     * 详见 ch07 §4.5 / §6。
     *
     * @return 不可变的消息列表（可能为空）
     */
    public List<ChatMessage> loadMessagesSinceLatestCompactBoundary(String sessionId, String cwd) {
        List<SessionEvent> events = readAll(sessionId, cwd);
        int startIndex = 0;
        for (int index = events.size() - 1; index >= 0; index--) {
            if (events.get(index).type() == SessionEventType.COMPACT_BOUNDARY) {
                startIndex = index + 1;
                break;
            }
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (int index = startIndex; index < events.size(); index++) {
            events.get(index).message().ifPresent(messages::add);
        }
        return List.copyOf(messages);
    }

    /**
     * 列出某个 cwd 目录下的全部会话元数据，按更新时间倒序（最新的在前）。
     *
     * <p>对每个 {@code .jsonl} 文件做一次 {@link #readMetadataFromPath}：读出全部事件以提取
     * 标题、事件总数、修改时间等摘要信息。注意：这是「全量读」，长会话很多时会有 I/O 成本，
     * ch07 §7 中提到的优化方向之一就是把 metadata 拆出独立文件以避免逐文件全量读。
     *
     * @return 不可变的会话元数据列表；目录不存在时返回空列表
     */
    public List<SessionMetadata> listSessionsByCwd(String cwd) {
        Path dir = root.resolve(cwdDirectoryKey(cwd));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(dir)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .map(path -> readMetadataFromPath(cwd, path))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(SessionMetadata::updatedAt).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 读出单个会话的元数据；若文件不存在或不属于该 cwd（事件中的 cwd 字段不匹配），返回 empty。
     *
     * @return 会话元数据；不存在时返回 {@link Optional#empty()}
     */
    public Optional<SessionMetadata> readMetadata(String sessionId, String cwd) {
        Path file = sessionFile(sessionId, cwd);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return readMetadataFromPath(cwd, file);
    }

    /**
     * 跨所有 cwd 目录扫描，找出哪些工作目录下存在指定 sessionId 的文件。
     *
     * <p>用途：当上层 {@code SessionService} 在当前 cwd 下找不到该 sessionId 时，借助本方法判断
     * 「是否存在于另一个 cwd」，从而给出更友好的错误提示（而不是只丢一个 not found）。
     *
     * <p>实现：遍历 root 下每个 cwd 目录，若该目录内存在 {@code <sessionId>.jsonl}，
     * 就读出其首行事件以解出真实的 cwd（因为目录名是 base64-url 后的形式，不能直接当 cwd 用）。
     *
     * @return 命中该 sessionId 的所有 cwd（已排序）；未命中返回空列表
     */
    public List<String> findCwdsForSessionId(String sessionId) {
        String fileName = sanitize(sessionId) + ".jsonl";
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(root)) {
            return paths
                    .filter(Files::isDirectory)
                    .filter(path -> Files.exists(path.resolve(fileName)))
                    .map(this::cwdFromFirstEvent)
                    .flatMap(Optional::stream)
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 拼出某会话在磁盘上的绝对路径：{@code root / base64(cwd) / sanitize(sessionId).jsonl}。
     */
    private Path sessionFile(String sessionId, String cwd) {
        return root.resolve(cwdDirectoryKey(cwd)).resolve(sanitize(sessionId) + ".jsonl");
    }

    /**
     * 把单个 JSONL 文件读成 {@link SessionMetadata}。
     *
     * <p>会过滤掉「事件中 cwd 字段与传入 cwd 不一致」的文件 —— 这是一种安全检查，避免 base64
     * 目录名碰撞带来的串数据。标题优先取最近一次 RENAME 的 title，回退到首条用户消息（截断 60 字）。
     */
    private Optional<SessionMetadata> readMetadataFromPath(String cwd, Path file) {
        try {
            String fileName = file.getFileName().toString();
            String sessionId = fileName.substring(0, fileName.length() - ".jsonl".length());
            List<SessionEvent> events = readAllFromPath(file);
            if (events.isEmpty() || events.stream().anyMatch(event -> !event.cwd().equals(cwd))) {
                return Optional.empty();
            }
            FileTime modified = Files.getLastModifiedTime(file);
            Optional<String> title = latestTitle(events).or(() -> firstUserTitle(events));
            return Optional.of(new SessionMetadata(sessionId, cwd, title, events.size(),
                    modified.toInstant(), file));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private List<SessionEvent> readAllFromPath(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<SessionEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    events.add(deserialize(line));
                }
            }
            return List.copyOf(events);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 从某个 cwd 目录下任取一个 jsonl 文件的首行事件，反解出真实 cwd 字符串。
     * 用于 {@link #findCwdsForSessionId} 把 base64 目录名还原成原始路径。
     */
    private Optional<String> cwdFromFirstEvent(Path projectDir) {
        try (Stream<Path> paths = Files.list(projectDir)) {
            Optional<Path> firstJsonl = paths
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .findFirst();
            if (firstJsonl.isEmpty()) {
                return Optional.empty();
            }
            List<String> lines = Files.readAllLines(firstJsonl.orElseThrow(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (!line.isBlank()) {
                    return Optional.of(deserialize(line).cwd());
                }
            }
            return Optional.empty();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Optional<String> latestTitle(List<SessionEvent> events) {
        for (int index = events.size() - 1; index >= 0; index--) {
            Optional<MetaSessionEventDraft> meta = events.get(index).meta();
            if (meta.isPresent() && meta.orElseThrow() instanceof RenameDraft renameDraft) {
                return Optional.of(renameDraft.title());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstUserTitle(List<SessionEvent> events) {
        return events.stream()
                .flatMap(event -> event.message().stream())
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::content)
                .map(String::trim)
                .filter(content -> !content.isBlank())
                .findFirst()
                .map(content -> content.length() > 60 ? content.substring(0, 60) : content);
    }

    /**
     * 把任意字符串转成「文件名安全」的形式：把不在白名单（字母/数字/./_-）内的字符替换为下划线，
     * 全空白时降级为单个下划线。用于把 sessionId 安全地落到文件名里。
     */
    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "_" : sanitized;
    }

    /**
     * 把 cwd 编码成「URL-safe base64 无 padding」字符串作为目录名 —— 这样能 100% 还原原 cwd
     * （含 Windows 盘符、空格、中文、emoji 等），同时绕开各种文件系统对路径分隔符/特殊字符的限制。
     */
    private static String cwdDirectoryKey(String cwd) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(Objects.requireNonNull(cwd, "cwd").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 把一条事件序列化为单行 JSON。
     *
     * <p>顶层字段固定为 {@code type/uuid/timestamp/sessionId/cwd}，可选字段是
     * {@code parentUuid / logicalParentUuid / message / meta / compactMetadata}。
     * 不同事件类型选填不同字段；这种「松散 schema」让后续新增事件类型不破坏旧文件的兼容性。
     */
    private static String serialize(SessionEvent event) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", serializedEventType(event));
        root.put("uuid", event.uuid());
        root.put("timestamp", event.timestamp().toString());
        root.put("sessionId", event.sessionId());
        root.put("cwd", event.cwd());
        event.parentUuid().ifPresent(value -> root.put("parentUuid", value));
        event.logicalParentUuid().ifPresent(value -> root.put("logicalParentUuid", value));
        event.message().ifPresent(message -> root.set("message", serializeMessage(message)));
        event.meta().ifPresent(meta -> root.set("meta", serializeMeta(meta)));
        event.compactMetadata().ifPresent(metadata -> root.set("compactMetadata", serializeCompactMetadata(metadata)));
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 把一行 JSON 反序列化回 {@link SessionEvent}。
     *
     * <p>非法 JSON 会抛 {@link IllegalArgumentException}，从而让上层调用（如 resume）直接失败
     * —— 这是「fail-fast」策略，避免静默丢数据。
     */
    private static SessionEvent deserialize(String line) {
        try {
            JsonNode root = MAPPER.readTree(line);
            SessionEventType type = deserializeEventType(root.get("type").asText());
            Optional<String> parentUuid = optionalText(root, "parentUuid");
            Optional<String> logicalParentUuid = optionalText(root, "logicalParentUuid");
            Optional<ChatMessage> message = root.has("message")
                    ? Optional.of(deserializeMessage(root.get("message")))
                    : Optional.empty();
            Optional<MetaSessionEventDraft> meta = root.has("meta")
                    ? Optional.of(deserializeMeta(type, root.get("meta")))
                    : Optional.empty();
            Optional<CompactMetadata> compactMetadata = root.has("compactMetadata")
                    ? Optional.of(deserializeCompactMetadata(root.get("compactMetadata")))
                    : Optional.empty();
            return SessionEvent.create(type, root.get("uuid").asText(), Instant.parse(root.get("timestamp").asText()),
                    root.get("sessionId").asText(), root.get("cwd").asText(), parentUuid, logicalParentUuid,
                    message, meta, compactMetadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid session JSONL event", exception);
        }
    }

    private static ObjectNode serializeMessage(ChatMessage message) {
        ObjectNode node = MAPPER.createObjectNode();
        switch (message) {
            case SystemMessage systemMessage -> {
                node.put("role", "system");
                node.put("content", systemMessage.content());
            }
            case UserMessage userMessage -> {
                node.put("role", "user");
                node.put("content", userMessage.content());
            }
            case AssistantMessage assistantMessage -> {
                node.put("role", "assistant");
                node.put("content", assistantMessage.content());
                writeAssistantUsage(node, assistantMessage.providerUsage(), assistantMessage.usageStaleness());
            }
            case AssistantProgressMessage progressMessage -> {
                node.put("role", "assistant_progress");
                node.put("content", progressMessage.content());
                writeAssistantUsage(node, progressMessage.providerUsage(), progressMessage.usageStaleness());
            }
            case AssistantToolCallMessage toolCallMessage -> {
                node.put("role", "assistant_tool_call");
                node.put("toolUseId", toolCallMessage.toolUseId());
                node.put("toolName", toolCallMessage.toolName());
                node.set("input", toolCallMessage.input());
                writeAssistantUsage(node, toolCallMessage.providerUsage(), toolCallMessage.usageStaleness());
            }
            case AssistantThinkingMessage thinkingMessage -> {
                node.put("role", "assistant_thinking");
                ArrayNode blocks = node.putArray("blocks");
                for (ProviderThinkingBlock block : thinkingMessage.blocks()) {
                    ObjectNode blockNode = blocks.addObject();
                    blockNode.put("type", block.type());
                    blockNode.set("raw", block.raw());
                }
            }
            case ContextSummaryMessage summaryMessage -> {
                node.put("role", "context_summary");
                node.put("content", summaryMessage.content());
                node.put("compressedCount", summaryMessage.compressedCount());
                node.put("timestamp", summaryMessage.timestamp().toString());
            }
            case ToolResultMessage toolResultMessage -> {
                node.put("role", "tool_result");
                node.put("toolUseId", toolResultMessage.toolUseId());
                node.put("toolName", toolResultMessage.toolName());
                node.put("content", toolResultMessage.content());
                node.put("error", toolResultMessage.error());
            }
            default -> throw new IllegalArgumentException("Unsupported message type for session JSONL: "
                    + message.getClass().getSimpleName());
        }
        return node;
    }

    /**
     * 把内部 {@link SessionEventType} 转成更细粒度的 JSON {@code type} 字符串。
     *
     * <p>比如内部只有 {@code MESSAGE} 一种类型，但落盘时会按消息子类拆成 {@code user/assistant/
     * tool_call/tool_result/...} 等，便于人眼或 jq 直接过滤。反向转换由
     * {@link #deserializeEventType} 做兼容，旧的大写枚举名也能读。
     */
    private static String serializedEventType(SessionEvent event) {
        if (event.type() == SessionEventType.MESSAGE && event.message().isPresent()) {
            return switch (event.message().orElseThrow()) {
                case SystemMessage ignored -> "system";
                case UserMessage ignored -> "user";
                case AssistantMessage ignored -> "assistant";
                case AssistantThinkingMessage ignored -> "thinking";
                case AssistantProgressMessage ignored -> "progress";
                case AssistantToolCallMessage ignored -> "tool_call";
                case ToolResultMessage ignored -> "tool_result";
                case ContextSummaryMessage ignored -> "summary";
                default -> "message";
            };
        }
        return switch (event.type()) {
            case MESSAGE -> "message";
            case COMPACT_BOUNDARY -> "compact_boundary";
            case RENAME -> "rename";
            case FORK -> "fork";
        };
    }

    private static SessionEventType deserializeEventType(String value) {
        return switch (value) {
            case "system", "user", "assistant", "thinking", "progress", "tool_call", "tool_result",
                 "summary", "message", "MESSAGE" -> SessionEventType.MESSAGE;
            case "compact_boundary", "COMPACT_BOUNDARY" -> SessionEventType.COMPACT_BOUNDARY;
            case "rename", "RENAME" -> SessionEventType.RENAME;
            case "fork", "FORK" -> SessionEventType.FORK;
            default -> SessionEventType.valueOf(value);
        };
    }

    private static ChatMessage deserializeMessage(JsonNode node) {
        return switch (node.get("role").asText()) {
            case "system" -> new SystemMessage(node.get("content").asText());
            case "user" -> new UserMessage(node.get("content").asText());
            case "assistant" -> new AssistantMessage(node.get("content").asText(),
                    readProviderUsage(node), readUsageStaleness(node));
            case "assistant_progress" -> new AssistantProgressMessage(node.get("content").asText(),
                    readProviderUsage(node), readUsageStaleness(node));
            case "assistant_tool_call" -> new AssistantToolCallMessage(node.get("toolUseId").asText(),
                    node.get("toolName").asText(), node.get("input"),
                    readProviderUsage(node), readUsageStaleness(node));
            case "assistant_thinking" -> {
                List<ProviderThinkingBlock> blocks = new ArrayList<>();
                for (JsonNode block : node.get("blocks")) {
                    blocks.add(new ProviderThinkingBlock(block.get("type").asText(), block.get("raw")));
                }
                yield new AssistantThinkingMessage(blocks);
            }
            case "context_summary" -> new ContextSummaryMessage(node.get("content").asText(),
                    node.get("compressedCount").asInt(), Instant.parse(node.get("timestamp").asText()));
            case "tool_result" -> new ToolResultMessage(node.get("toolUseId").asText(), node.get("toolName").asText(),
                    node.get("content").asText(), node.get("error").asBoolean());
            default -> throw new IllegalArgumentException("Unsupported message role: " + node.get("role").asText());
        };
    }

    private static ObjectNode serializeMeta(MetaSessionEventDraft meta) {
        ObjectNode node = MAPPER.createObjectNode();
        if (meta instanceof RenameDraft renameDraft) {
            node.put("title", renameDraft.title());
            return node;
        }
        if (meta instanceof ForkDraft forkDraft) {
            ObjectNode metadata = node.putObject("metadata");
            metadata.put("sourceSessionId", forkDraft.metadata().sourceSessionId());
            forkDraft.metadata().sourceEventId().ifPresent(value -> metadata.put("sourceEventId", value));
            metadata.put("newSessionId", forkDraft.metadata().newSessionId());
            metadata.put("cwd", forkDraft.metadata().cwd());
            metadata.put("timestamp", forkDraft.metadata().timestamp().toString());
            return node;
        }
        throw new IllegalArgumentException("Unsupported meta draft: " + meta.getClass().getSimpleName());
    }

    private static void writeAssistantUsage(ObjectNode node, Optional<ProviderUsage> usage,
                                            UsageStaleness usageStaleness) {
        usage.ifPresent(value -> {
            ObjectNode usageNode = node.putObject("providerUsage");
            usageNode.put("inputTokens", value.inputTokens());
            usageNode.put("outputTokens", value.outputTokens());
            usageNode.put("totalTokens", value.totalTokens());
        });

        ObjectNode stalenessNode = node.putObject("usageStaleness");
        stalenessNode.put("stale", usageStaleness.stale());
        usageStaleness.reason().ifPresent(reason -> stalenessNode.put("reason", reason));
    }

    private static Optional<ProviderUsage> readProviderUsage(JsonNode node) {
        if (!node.has("providerUsage")) {
            return Optional.empty();
        }
        JsonNode usage = node.get("providerUsage");
        return Optional.of(new ProviderUsage(
                usage.get("inputTokens").asInt(),
                usage.get("outputTokens").asInt(),
                usage.get("totalTokens").asInt()
        ));
    }

    private static UsageStaleness readUsageStaleness(JsonNode node) {
        if (!node.has("usageStaleness")) {
            return UsageStaleness.fresh();
        }
        JsonNode staleness = node.get("usageStaleness");
        if (!staleness.get("stale").asBoolean()) {
            return UsageStaleness.fresh();
        }
        return UsageStaleness.stale(staleness.get("reason").asText());
    }

    private static MetaSessionEventDraft deserializeMeta(SessionEventType type, JsonNode node) {
        return switch (type) {
            case RENAME -> new RenameDraft(node.get("title").asText());
            case FORK -> {
                JsonNode metadata = node.get("metadata");
                yield new ForkDraft(new ForkMetadata(metadata.get("sourceSessionId").asText(),
                        optionalText(metadata, "sourceEventId"), metadata.get("newSessionId").asText(),
                        metadata.get("cwd").asText(), Instant.parse(metadata.get("timestamp").asText())));
            }
            case MESSAGE, COMPACT_BOUNDARY ->
                    throw new IllegalArgumentException(type + " does not carry meta draft");
        };
    }

    private static ObjectNode serializeCompactMetadata(CompactMetadata metadata) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("trigger", metadata.trigger().name());
        node.put("tokensBefore", metadata.tokensBefore());
        node.put("tokensAfter", metadata.tokensAfter());
        node.put("messagesCompressed", metadata.messagesCompressed());
        node.put("timestamp", metadata.timestamp().toString());
        return node;
    }

    private static CompactMetadata deserializeCompactMetadata(JsonNode node) {
        return new CompactMetadata(CompactTrigger.valueOf(node.get("trigger").asText()),
                node.get("tokensBefore").asLong(),
                node.get("tokensAfter").asLong(),
                node.get("messagesCompressed").asInt(),
                Instant.parse(node.get("timestamp").asText()));
    }

    private static Optional<String> optionalText(JsonNode node, String field) {
        return node.has(field) ? Optional.of(node.get(field).asText()) : Optional.empty();
    }
}
