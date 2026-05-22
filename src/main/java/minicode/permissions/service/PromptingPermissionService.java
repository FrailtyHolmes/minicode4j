package minicode.permissions.service;

import minicode.permissions.model.PathIntent;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.*;
import minicode.permissions.store.PermissionResourceKey;
import minicode.permissions.store.PermissionStore;
import minicode.permissions.store.PermissionStoreDecision;
import minicode.permissions.store.PermissionStoreEntry;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 通过"弹窗 + 持久化"组合实现的 {@link PermissionService}：负责把"敏感操作要不要做"
 * 这个决策转交给用户，并记住用户的选择。
 *
 * <p>典型调用链是：工具（如 RunCommandTool / EditTool）发起一次 {@code ensureXxx} 调用，
 * 服务依次走"持久化记录 → 当前 Turn 临时白名单 → 用户弹窗"三层检查；只要其中一层放行就不再骚扰用户。
 *
 * <p>关键设计取舍：
 * <ul>
 *   <li>三层检查的顺序按"成本从低到高"排：磁盘 hit 最快、内存 set 次之、最后才阻塞用户。</li>
 *   <li>{@code ALLOW_ALWAYS} / {@code DENY_ALWAYS} 才落 {@link PermissionStore}；
 *       {@code ALLOW_TURN} 仅记内存（按 turnId 隔离），{@code ALLOW_ONCE} 干脆不记。</li>
 *   <li>{@code beginTurn}/{@code endTurn} 由 AgentLoop 在每次任务边界调用，
 *       让 Turn 范围授权天然过期，遵守"最小权限"。</li>
 *   <li>UI 完全藏在 {@link PermissionPromptHandler} 后面，line mode / TUI mode / 未来 GUI
 *       都只是不同实现。</li>
 * </ul>
 */
public final class PromptingPermissionService implements PermissionService {
    /** 用来弹窗的策略对象；line mode 与 TUI mode 各自实现。 */
    private final PermissionPromptHandler promptHandler;
    /** 持久化"一直允许 / 一直拒绝"记录的存储；测试场景可注入 {@link PermissionStore#none()}。 */
    private final PermissionStore store;
    /** turnId → 该 Turn 内已经被"允许本回合"的资源集合；Turn 结束就清掉。 */
    private final Map<String, Set<PermissionResourceKey>> turnAllows = new HashMap<>();

    /**
     * 便利构造：不需要持久化的场景（典型如单元测试）使用空存储。
     *
     * @param promptHandler 弹窗策略
     */
    public PromptingPermissionService(PermissionPromptHandler promptHandler) {
        this(promptHandler, PermissionStore.none());
    }

    /**
     * 主构造：同时注入弹窗策略与持久化存储。
     *
     * @param promptHandler 弹窗策略，不能为 {@code null}
     * @param store         持久化存储，不能为 {@code null}（不需要持久化时传 {@link PermissionStore#none()}）
     */
    public PromptingPermissionService(PermissionPromptHandler promptHandler, PermissionStore store) {
        this.promptHandler = Objects.requireNonNull(promptHandler, "promptHandler");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 申请对某个文件路径的访问授权（读 / 写 / 列目录）。
     *
     * <p>读和写是不同的资源（{@link PathIntent}），互相不蕴含——授权"读"不会自动允许"写"。
     *
     * @param path    目标路径
     * @param intent  访问意图（READ / WRITE / LIST 等）
     * @param context 当前 Turn 上下文，用于 Turn 级白名单识别
     * @return 授权对象（包含作用域 / 持久化方式）
     * @throws PermissionDeniedException 用户拒绝或之前已 DENY_ALWAYS
     */
    @Override
    public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
        PermissionResource resource = new PermissionResource.PathResource(
                Objects.requireNonNull(path, "path"),
                Objects.requireNonNull(intent, "intent")
        );
        PermissionRequest request = request(PermissionRequestKind.PATH, resource, "Allow path " + intent + " access", context);
        return ensure(request, PermissionKind.PATH);
    }

    /**
     * 申请执行一个 shell 命令的授权。
     *
     * <p>资源 identity 用的是"完整命令 + 参数"，意味着 {@code rm -rf /tmp/a} 与
     * {@code rm -rf /tmp/b} 是两个独立资源——避免"一次授权放任所有 rm"的安全坑。
     *
     * @param signature      命令签名（可执行文件 + 参数列表）
     * @param classification 风险分类（READ / WRITE / NETWORK 等）
     * @param context        当前 Turn 上下文
     * @return 授权对象
     * @throws PermissionDeniedException 用户拒绝或之前已 DENY_ALWAYS
     */
    @Override
    public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                         PermissionContext context) {
        PermissionResource resource = new PermissionResource.CommandResource(
                Objects.requireNonNull(signature, "signature"),
                Objects.requireNonNull(classification, "classification")
        );
        PermissionRequest request = request(PermissionRequestKind.COMMAND, resource, "Allow command execution", context);
        return ensure(request, PermissionKind.COMMAND);
    }

    /**
     * 申请一次具体文件编辑的授权。
     *
     * <p>编辑场景比"读 / 写"更细：连同 diff 摘要一起送进弹窗，让用户能看到要改什么再决定，
     * 因此 {@link PermissionResource.EditResource} 内带了 diff 预览 / 字数 / 指纹等信息。
     *
     * @param resource 编辑资源（含 diff 与摘要）
     * @param context  当前 Turn 上下文
     * @return 授权对象
     * @throws PermissionDeniedException 用户拒绝
     */
    @Override
    public PermissionGrant ensureEdit(PermissionResource.EditResource resource, PermissionContext context) {
        PermissionRequest request = request(
                PermissionRequestKind.EDIT,
                Objects.requireNonNull(resource, "resource"),
                "Allow file edit",
                context
        );
        return ensure(request, PermissionKind.EDIT);
    }

    /**
     * 申请调用某个 MCP（Model Context Protocol）工具的授权。
     *
     * <p>MCP 工具来自外部进程，副作用未知，因此跟内置工具一样需要审批。
     * 资源 identity 由 server 名 + 工具名组成。
     *
     * @param resource MCP 工具资源（server / 工具名 / 描述）
     * @param context  当前 Turn 上下文
     * @return 授权对象
     * @throws PermissionDeniedException 用户拒绝
     */
    @Override
    public PermissionGrant ensureMcpTool(PermissionResource.McpToolResource resource, PermissionContext context) {
        PermissionRequest request = request(
                PermissionRequestKind.MCP_TOOL,
                Objects.requireNonNull(resource, "resource"),
                "Allow MCP tool call",
                context
        );
        return ensure(request, PermissionKind.MCP_TOOL);
    }

    /**
     * 整个权限系统的核心调度方法：把一次审批请求按"持久化 → Turn 内 → 弹窗"三层依次走完。
     *
     * <p>流程：
     * <ol>
     *   <li>查 {@link PermissionStore}：命中 DENY 直接拒；命中 ALLOW 直接放行。</li>
     *   <li>查当前 Turn 的内存白名单：命中则按 TURN 作用域放行。</li>
     *   <li>都没命中 → 调 {@link PermissionPromptHandler#prompt} 让用户做选择。</li>
     *   <li>根据用户选择更新存储 / Turn 白名单，并构造 {@link PermissionGrant} 返回；
     *       拒绝则抛 {@link PermissionDeniedException} 让上层把"被拒"反馈给 LLM。</li>
     * </ol>
     *
     * @param request 已经构造好的请求（含资源 / 选项 / 上下文）
     * @param kind    资源种类，决定 grant 与 store entry 怎么打 tag
     * @return 授权 grant；不会返回 null
     * @throws PermissionDeniedException                  用户选择拒绝，或之前已 DENY_ALWAYS
     * @throws IllegalArgumentException 用户的选择 key 与 decision 不一致时（防御式校验）
     */
    private PermissionGrant ensure(PermissionRequest request, PermissionKind kind) {
        PermissionResourceKey key = PermissionResourceKey.from(request.resource());
        Optional<PermissionStoreEntry> stored = store.find(request.resource());
        if (stored.isPresent() && stored.orElseThrow().decision() == PermissionStoreDecision.DENY) {
            throw new PermissionDeniedException(request, Optional.empty(), Optional.empty());
        }
        if (stored.isPresent() && stored.orElseThrow().decision() == PermissionStoreDecision.ALLOW) {
            return grant(kind, request.resource(), PermissionGrantScope.ALWAYS, PermissionPersistence.USER);
        }
        if (turnAllowed(request.context(), key)) {
            return grant(kind, request.resource(), PermissionGrantScope.TURN, PermissionPersistence.MEMORY);
        }

        PermissionPromptResult result = Objects.requireNonNull(promptHandler.prompt(request), "prompt result");
        PermissionChoice choice = choiceFor(request, result);
        if (choice.decision() != result.decision()) {
            throw new IllegalArgumentException("Permission choice decision does not match prompt result decision");
        }
        if (!isAllow(choice.decision())) {
            if (choice.decision() == PermissionDecision.DENY_ALWAYS) {
                store.save(new PermissionStoreEntry(
                        PermissionStoreDecision.DENY,
                        kind,
                        key,
                        Instant.now()
                ));
            }
            throw new PermissionDeniedException(request, Optional.of(choice.key()), result.feedback());
        }
        if (choice.decision() == PermissionDecision.ALLOW_ALWAYS) {
            store.save(new PermissionStoreEntry(
                    PermissionStoreDecision.ALLOW,
                    kind,
                    key,
                    Instant.now()
            ));
        }
        if (choice.decision() == PermissionDecision.ALLOW_TURN) {
            request.context().turnId().ifPresent(turnId ->
                    turnAllows.computeIfAbsent(turnId, ignored -> new HashSet<>()).add(key)
            );
        }
        return grant(kind, request.resource(), scopeFor(choice.decision()), persistenceFor(choice.decision()));
    }

    /**
     * 开始一个新的 Turn：在内存白名单里登记一个空集合。
     *
     * <p>由 AgentLoop 在每轮用户消息开始处调用。配合 {@link #endTurn} 形成边界，
     * 让 {@code ALLOW_TURN} 类型授权"过完这一轮就过期"，避免跨任务残留。
     *
     * @param turnId 唯一标识当前 Turn 的字符串，不能为空白
     * @throws IllegalArgumentException turnId 为空白时
     */
    @Override
    public synchronized void beginTurn(String turnId) {
        if (Objects.requireNonNull(turnId, "turnId").isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        turnAllows.computeIfAbsent(turnId, ignored -> new HashSet<>());
    }

    /**
     * 结束一个 Turn：把该 Turn 的内存白名单整个删掉，让 {@code ALLOW_TURN} 授权立刻失效。
     *
     * @param turnId 要结束的 Turn id
     * @throws IllegalArgumentException turnId 为空白时
     */
    @Override
    public synchronized void endTurn(String turnId) {
        if (Objects.requireNonNull(turnId, "turnId").isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        turnAllows.remove(turnId);
    }

    /**
     * 判断"当前 Turn 是否已经允许过这个资源"。
     *
     * <p>当请求上下文里没有 turnId（比如非交互场景）时一律返回 false，让流程退到弹窗或持久化检查。
     */
    private synchronized boolean turnAllowed(PermissionContext context, PermissionResourceKey key) {
        return context.turnId()
                .map(turnAllows::get)
                .map(keys -> keys.contains(key))
                .orElse(false);
    }

    private static PermissionGrant grant(PermissionKind kind, PermissionResource resource,
                                         PermissionGrantScope scope, PermissionPersistence persistence) {
        return new PermissionGrant(kind, resource, scope, persistence, Instant.now(), Optional.empty());
    }

    /**
     * 把一个资源 + 原因组装成完整的 {@link PermissionRequest}。
     *
     * <p>负责生成 requestId、根据资源种类挑选合适的展示文案与可选项（"允许一次 / 一直允许 / 拒绝"等）。
     */
    private static PermissionRequest request(PermissionRequestKind kind, PermissionResource resource, String reason,
                                             PermissionContext context) {
        PermissionContext actualContext = Objects.requireNonNull(context, "context");
        return new PermissionRequest(
                UUID.randomUUID().toString(),
                kind,
                resource,
                reason,
                detailsFor(kind, resource),
                choicesFor(resource),
                true,
                PermissionScope.ONCE,
                actualContext
        );
    }

    /**
     * 根据资源种类生成弹窗里展示给用户的"详情卡片"——标题、说明、关键事实列表。
     *
     * <p>不同资源（路径 / 命令 / 编辑 / MCP）展示重点不一样，因此用 sealed 模式匹配。
     */
    private static PermissionRequestDetails detailsFor(PermissionRequestKind kind, PermissionResource resource) {
        return switch (resource) {
            case PermissionResource.PathResource pathResource -> new PermissionRequestDetails(
                    "Path access",
                    "The model requested " + pathResource.intent() + " access.",
                    List.of("Path: " + pathResource.path())
            );
            case PermissionResource.CommandResource commandResource -> new PermissionRequestDetails(
                    "Command execution",
                    "The model requested command execution.",
                    List.of("Command: " + commandResource.signature().executable()
                            + commandResource.signature().arguments().stream()
                            .reduce("", (left, right) -> left + " " + right),
                            "Classification: " + commandResource.classification())
            );
            case PermissionResource.EditResource editResource -> new PermissionRequestDetails(
                    "Edit review",
                    "Review the proposed file change before it is applied.",
                    editFacts(editResource)
            );
            case PermissionResource.McpToolResource mcpToolResource -> new PermissionRequestDetails(
                    "MCP tool call",
                    "The model requested a tool exposed by a local MCP server.",
                    List.of(
                            "Server: " + mcpToolResource.serverName(),
                            "Tool: " + mcpToolResource.toolName(),
                            "Wrapped name: " + mcpToolResource.wrappedName(),
                            "Description: " + mcpToolResource.description()
                    )
            );
        };
    }

    /**
     * 给用户呈现的可选项列表。
     *
     * <p>编辑场景特意屏蔽了"一直允许"——因为 diff 内容每次都不一样，"永久放行某次 diff"没有意义。
     */
    private static List<PermissionChoice> choicesFor(PermissionResource resource) {
        if (resource instanceof PermissionResource.EditResource) {
            return List.of(
                    PermissionChoice.allowOnce("allow_once", "Allow this edit"),
                    PermissionChoice.allowTurn("allow_turn", "Allow this edit target this turn"),
                    PermissionChoice.denyOnce("deny_once", "Deny"),
                    PermissionChoice.denyWithFeedback("deny_feedback", "Deny with feedback")
            );
        }
        return List.of(
                PermissionChoice.allowOnce("allow_once", "Allow once"),
                PermissionChoice.allowTurn("allow_turn", allowTurnLabel(resource)),
                PermissionChoice.allowAlways("allow_always", "Allow always"),
                PermissionChoice.denyOnce("deny_once", "Deny once"),
                PermissionChoice.denyAlways("deny_always", "Deny always"),
                PermissionChoice.denyWithFeedback("deny_feedback", "Deny with feedback")
        );
    }

    private static String allowTurnLabel(PermissionResource resource) {
        return switch (resource) {
            case PermissionResource.CommandResource ignored -> "Allow this command this turn";
            case PermissionResource.PathResource pathResource ->
                    pathResource.intent() == PathIntent.LIST
                            ? "Allow this directory this turn"
                            : "Allow this path this turn";
            case PermissionResource.EditResource ignored -> "Allow this edit target this turn";
            case PermissionResource.McpToolResource ignored -> "Allow this MCP tool this turn";
        };
    }

    /**
     * 把一次编辑请求拆成弹窗上一行行展示的"事实列表"——路径、操作、字数、diff 预览等。
     */
    private static List<String> editFacts(PermissionResource.EditResource editResource) {
        List<String> facts = new java.util.ArrayList<>();
        facts.add("Path: " + displayPath(editResource.path()));
        facts.add("Operation: " + editResource.operation());
        facts.add("Summary: " + editResource.summary());
        facts.add("Before chars: " + editResource.beforeChars());
        facts.add("After chars: " + editResource.afterChars());
        facts.add("Preview truncated: " + editResource.truncated());
        facts.add("Review fingerprint: " + editResource.reviewFingerprint());
        editResource.diffRef().ifPresent(ref -> facts.add("Diff ref: " + ref));
        facts.add("Diff preview:");
        facts.addAll(editResource.diffPreview().lines().toList());
        return facts;
    }

    private static String displayPath(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    /**
     * 把 {@link PermissionPromptResult}（用户回的结果）映射回原请求里的具体 {@link PermissionChoice}。
     *
     * <p>优先按 choiceKey 精确查找；若结果没带 key，则尝试用 decision 唯一匹配，
     * 多于一项时报错——避免歧义被静默吞掉。
     */
    private static PermissionChoice choiceFor(PermissionRequest request, PermissionPromptResult result) {
        if (result.choiceKey().isPresent()) {
            String key = result.choiceKey().orElseThrow();
            return request.choices().stream()
                    .filter(choice -> choice.key().equals(key))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown permission choice: " + key));
        }
        List<PermissionChoice> matching = request.choices().stream()
                .filter(choice -> choice.decision() == result.decision())
                .toList();
        if (matching.size() == 1) {
            return matching.getFirst();
        }
        throw new IllegalArgumentException("Permission prompt result must include a choice key");
    }

    private static boolean isAllow(PermissionDecision decision) {
        return decision == PermissionDecision.ALLOW_ONCE
                || decision == PermissionDecision.ALLOW_TURN
                || decision == PermissionDecision.ALLOW_ALWAYS;
    }

    private static PermissionGrantScope scopeFor(PermissionDecision decision) {
        return switch (decision) {
            case ALLOW_ONCE -> PermissionGrantScope.ONCE;
            case ALLOW_TURN -> PermissionGrantScope.TURN;
            case ALLOW_ALWAYS -> PermissionGrantScope.ALWAYS;
            case DENY_ONCE, DENY_ALWAYS, DENY_WITH_FEEDBACK ->
                    throw new IllegalArgumentException("deny decisions cannot become grants");
        };
    }

    private static PermissionPersistence persistenceFor(PermissionDecision decision) {
        return decision == PermissionDecision.ALLOW_ALWAYS
                ? PermissionPersistence.USER
                : PermissionPersistence.MEMORY;
    }
}
