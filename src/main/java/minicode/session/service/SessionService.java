package minicode.session.service;

import minicode.core.message.ChatMessage;
import minicode.session.factory.SessionEventFactory;
import minicode.session.model.ForkDraft;
import minicode.session.model.ForkMetadata;
import minicode.session.model.RenameDraft;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.session.runner.SessionPersistenceRunner;
import minicode.session.store.SessionMetadata;
import minicode.session.store.SessionStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Session 管理的「业务门面」：把存储层（{@link SessionStore}）的低层 JSONL 读写封装成
 * 用户能直接理解的高层操作 —— 列表、恢复、重命名、分叉。
 *
 * <p>调用关系：上层 TUI / CLI 通过本类发起 {@code list / resume / rename / fork} 等动作；
 * 本类负责参数校验、定位会话、组装 {@link TurnPersistencePlan}，再交给
 * {@link SessionPersistenceRunner} 真正落盘到 append-only 的 JSONL 文件。
 *
 * <p>关键设计取舍：
 * <ul>
 *   <li>会话按 cwd（工作目录）隔离 —— 同一个 sessionId 必须归属于明确的 cwd，跨 cwd 访问会被拒绝。</li>
 *   <li>所有写动作（rename / fork）都走「Plan + Runner」模式，便于测试和事务式批量追加。</li>
 *   <li>{@code fork} 是物理复制：把源会话的消息历史重新追加到新文件中，两条会话从此独立演进。</li>
 *   <li>sessionId 使用可注入的 {@code Supplier}，便于测试时给定可预测 ID；时间也通过 {@link Clock} 注入。</li>
 * </ul>
 */
public final class SessionService {
    /** 合法 sessionId 的字符集合：字母、数字、点、下划线、短横线。 */
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    /** {@link #fork} 时尝试生成不冲突 sessionId 的最大次数，避免极小概率的 UUID 碰撞导致死循环。 */
    private static final int MAX_FORK_ID_ATTEMPTS = 5;

    private final SessionStore store;
    private final Supplier<String> sessionIdSupplier;
    private final Clock clock;

    /**
     * 生产环境用的简易构造：sessionId 使用随机 UUID，时钟为系统 UTC。
     *
     * @param store 底层 JSONL 存储
     */
    public SessionService(SessionStore store) {
        this(store, () -> UUID.randomUUID().toString());
    }

    /**
     * 自定义 sessionId 生成策略的构造（时钟仍走系统 UTC）。常用于测试中给定可预测的 ID 序列。
     *
     * @param store              底层 JSONL 存储
     * @param sessionIdSupplier  新会话 ID 的生成器
     */
    public SessionService(SessionStore store, Supplier<String> sessionIdSupplier) {
        this(store, sessionIdSupplier, Clock.systemUTC());
    }

    /**
     * 完全可注入的构造：用于在测试中冻结时间、固定 ID。三个参数都不可为 {@code null}。
     *
     * @param store              底层 JSONL 存储
     * @param sessionIdSupplier  新会话 ID 的生成器
     * @param clock              时钟（影响事件 timestamp 与 fork metadata）
     */
    public SessionService(SessionStore store, Supplier<String> sessionIdSupplier, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 列出指定 cwd 下的所有会话元数据，按更新时间倒序（新的在前）。
     *
     * @param cwd 工作目录绝对路径，不能为空
     * @return 该工作目录下所有会话的元数据列表；如果该 cwd 下还没有任何会话则返回空列表
     */
    public List<SessionMetadata> list(String cwd) {
        return store.listSessionsByCwd(requireText(cwd, "cwd"));
    }

    /**
     * 校验给定的会话在当前 cwd 下确实可被 resume；不返回任何东西，校验失败时抛异常。
     *
     * <p>这是 {@code minicode --resume <id>} 启动前的「快速预检」：避免装配完
     * ApplicationServices 之后才发现 sessionId 不存在或属于别的 cwd。
     *
     * @param cwd       当前工作目录
     * @param sessionId 待 resume 的会话 ID
     * @throws IllegalArgumentException 如果会话不存在，或属于另一个 cwd
     */
    public void requireResumable(String cwd, String sessionId) {
        requireExistingInCwd(cwd, sessionId);
    }

    /**
     * 加载指定会话「最近一次 compact boundary 之后」的全部消息，用于 resume 时回填上下文。
     *
     * <p>之所以从 compact boundary 之后开始：早于 boundary 的内容已经被 autoCompact 摘要进
     * {@code ContextSummaryMessage} 中，再读一遍是浪费。详见 ch07 §4.5 / §6（Event Sourcing 投影）。
     *
     * @param cwd       当前工作目录
     * @param sessionId 待 resume 的会话 ID
     * @return 可直接喂给下一轮 AgentLoop 的 ChatMessage 列表，至少含 1 条
     * @throws IllegalArgumentException 如果会话不存在、属于别的 cwd，或 resume 后没有任何可用消息
     */
    public List<ChatMessage> resumeMessages(String cwd, String sessionId) {
        requireExistingInCwd(cwd, sessionId);
        List<ChatMessage> messages = store.loadMessagesSinceLatestCompactBoundary(sessionId, cwd);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Session has no resumable messages: " + sessionId);
        }
        return messages;
    }

    /**
     * 给会话设定一个人类可读的标题；底层做法是追加一条 RENAME 事件。
     *
     * <p>因为是 append-only，旧标题不会被「真删除」，但 {@link SessionStore#listSessionsByCwd} 在
     * 提取标题时会从后往前扫，所以最新一次 rename 永远生效。
     *
     * @param cwd       当前工作目录
     * @param sessionId 目标会话 ID
     * @param title     新标题，不能为空白；前后空白会被去除
     */
    public void rename(String cwd, String sessionId, String title) {
        String actualTitle = requireTitle(title);
        requireExistingInCwd(cwd, sessionId);
        runnerFor(cwd, sessionId).apply(new TurnPersistencePlan(List.of(
                new PersistenceAction.AppendSessionEventAction(new RenameDraft(actualTitle))
        )));
    }

    /**
     * 从已有会话「分叉」出一个新会话，类比 {@code git branch}。
     *
     * <p>实现步骤：
     * <ol>
     *   <li>校验源会话存在并属于当前 cwd；</li>
     *   <li>调 {@link #resumeMessages} 取出最近 compact boundary 之后的全部消息；</li>
     *   <li>分配一个不冲突的新 sessionId；</li>
     *   <li>在新会话文件里先写一条 {@code FORK} 事件（含源会话 ID 与最后一个事件的 uuid 作为「分叉点」），
     *       再追加上一步取出的全部消息，作为新会话的初始历史。</li>
     * </ol>
     *
     * <p>注意：fork 是物理复制（只复制可见消息历史，而不是整份 JSONL 字节），从此两条会话独立演进、
     * 互不影响；当前不支持 merge。
     *
     * @param cwd              当前工作目录
     * @param sourceSessionId  源会话 ID
     * @return 新创建的子会话 ID
     */
    public String fork(String cwd, String sourceSessionId) {
        requireExistingInCwd(cwd, sourceSessionId);
        List<ChatMessage> messages = resumeMessages(cwd, sourceSessionId);
        String newSessionId = allocateForkSessionId(cwd);
        Optional<String> sourceEventId = store.latestEventUuid(sourceSessionId, cwd);
        SessionPersistenceRunner runner = runnerFor(cwd, newSessionId);
        runner.apply(new TurnPersistencePlan(List.of(
                new PersistenceAction.AppendSessionEventAction(new ForkDraft(new ForkMetadata(
                        sourceSessionId,
                        sourceEventId,
                        newSessionId,
                        cwd,
                        Instant.now(clock)
                ))),
                new PersistenceAction.AppendMessagesAction(messages)
        )));
        return newSessionId;
    }

    /**
     * 为指定会话构造一次性的 {@link SessionPersistenceRunner}：
     * 内部预读「最后一个事件的 uuid」作为 parentUuid 链的起点，保证后续追加的 event 之间形成单调链。
     */
    private SessionPersistenceRunner runnerFor(String cwd, String sessionId) {
        return new SessionPersistenceRunner(store, new SessionEventFactory(
                sessionId,
                cwd,
                clock,
                () -> UUID.randomUUID().toString(),
                store.latestEventUuid(sessionId, cwd)
        ));
    }

    /**
     * 为 {@link #fork} 反复尝试生成不冲突的新 sessionId，最多 {@value MAX_FORK_ID_ATTEMPTS} 次。
     * 极小概率连续碰撞时直接抛出，避免无声无息的死循环。
     */
    private String allocateForkSessionId(String cwd) {
        for (int attempt = 0; attempt < MAX_FORK_ID_ATTEMPTS; attempt++) {
            String candidate = requireSessionId(sessionIdSupplier.get());
            if (store.readMetadata(candidate, cwd).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate unique fork session id after "
                + MAX_FORK_ID_ATTEMPTS + " attempts.");
    }

    /**
     * 校验「该 sessionId 在当前 cwd 下确实存在」。
     *
     * <p>分三种结果：在当前 cwd 命中则直接通过；若该 sessionId 文件存在于另一个 cwd，则抛出
     * 「属于别的 cwd」的明确错误，避免用户误以为会话丢失；都没找到则抛出「会话不存在」。
     */
    private void requireExistingInCwd(String cwd, String sessionId) {
        String actualCwd = requireText(cwd, "cwd");
        String actualSessionId = requireSessionId(sessionId);
        if (store.readMetadata(actualSessionId, actualCwd).isPresent()) {
            return;
        }
        List<String> otherCwds = store.findCwdsForSessionId(actualSessionId).stream()
                .filter(candidate -> !candidate.equals(actualCwd))
                .toList();
        if (!otherCwds.isEmpty()) {
            throw new IllegalArgumentException("Session " + actualSessionId
                    + " belongs to a different cwd: " + otherCwds.getFirst());
        }
        throw new IllegalArgumentException("Session not found: " + actualSessionId);
    }

    private static String requireTitle(String value) {
        if (Objects.requireNonNull(value, "title").isBlank()) {
            throw new IllegalArgumentException("Session title must not be blank.");
        }
        return value.trim();
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireSessionId(String sessionId) {
        String value = requireText(sessionId, "sessionId");
        if (!SESSION_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid session id: " + value);
        }
        return value;
    }
}
