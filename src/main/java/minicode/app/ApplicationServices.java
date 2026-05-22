package minicode.app;

import minicode.context.manager.ContextManager;
import minicode.context.accounting.TokenAccountingService;
import minicode.context.compact.CompactRequest;
import minicode.context.compact.AutoCompactController;
import minicode.context.compact.AutoCompactPolicy;
import minicode.context.compact.CompactService;
import minicode.context.compact.CompactStatus;
import minicode.context.compact.CompactTrigger;
import minicode.context.compact.ManualCompactResult;
import minicode.context.stats.ContextStatsCalculator;
import minicode.context.stats.ModelContextWindow;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.event.AgentEventSink;
import minicode.core.loop.AgentLoop;
import minicode.core.turn.AgentTurnRequest;
import minicode.core.turn.AgentTurnResult;
import minicode.core.message.ChatMessage;
import minicode.core.message.SystemMessage;
import minicode.core.loop.ModelAdapter;
import minicode.model.MockModelAdapter;
import minicode.model.ModelContextProfile;
import minicode.model.ModelMetadata;
import minicode.model.ModelMetadataResolver;
import minicode.model.anthropic.AnthropicModelAdapter;
import minicode.model.anthropic.AnthropicModelsApiClient;
import minicode.model.anthropic.HttpAnthropicTransport;
import minicode.mcp.McpRuntime;
import minicode.mcp.McpServerSummary;
import minicode.mcp.McpToolHydrator;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.service.PromptingPermissionService;
import minicode.permissions.store.JsonPermissionStore;
import minicode.permissions.store.PermissionStore;
import minicode.prompt.SystemPromptBuilder;
import minicode.session.factory.SessionEventFactory;
import minicode.session.runner.SessionPersistenceRunner;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.session.store.SessionStore;
import minicode.skills.SkillDiscovery;
import minicode.skills.SkillRegistry;
import minicode.tools.builtin.AskUserTool;
import minicode.tools.builtin.EditFileTool;
import minicode.tools.builtin.GrepFilesTool;
import minicode.tools.builtin.ListFilesTool;
import minicode.tools.builtin.LoadSkillTool;
import minicode.tools.builtin.ModifyFileTool;
import minicode.tools.builtin.PatchFileTool;
import minicode.tools.builtin.ReadFilePathAccess;
import minicode.tools.builtin.ReadFileTool;
import minicode.tools.builtin.RunCommandTool;
import minicode.tools.builtin.WriteFileTool;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResultStorage;
import minicode.workspace.WorkspacePathResolver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;

/**
 * MiniCode 的「手写 DI 容器」：把 18 个依赖一次性装配好后以 record 的形式封装暴露。
 *
 * <p>本项目刻意不使用 Spring/Guice 等框架。所有装配集中在 {@link #create} 静态工厂里，
 * 严格按拓扑序：存储层（PermissionStore/SessionStore）→ 服务层（PermissionService/SkillRegistry）→
 * 工具层（ToolRegistry + 内置工具 + MCP 工具）→ 横切层（ContextManager/ModelAdapter）→ 核心循环（AgentLoop）。
 * 这样依赖关系一目了然、零反射、冷启动 100ms 内。详见 docs/tutorial/ch01。</p>
 *
 * <p>多个上层模块共用同一个底层资源（如 {@link PermissionService} 同时被多个工具持有），
 * 单一收口保证「单例性 + 顺序性 + 生命周期」。{@link #close} 负责释放 MCP 等子进程资源。</p>
 *
 * @param toolRegistry            工具注册表，含 11 个内置工具与动态注入的 MCP 工具
 * @param permissionService       权限审批服务，所有敏感工具调用前都走它
 * @param contextManager          上下文管理：tool 输出落盘、token 预算、batch 截断
 * @param sessionStore            会话事件存储（JSON Lines）
 * @param sessionPersistenceRunner 把一次 Turn 内产生的事件批量持久化的执行器
 * @param agentLoop               Agent 主循环（model → tool → model）
 * @param modelAdapter            模型协议适配器（Anthropic/Mock）
 * @param compactService          手动/自动压缩历史的服务
 * @param systemPromptBuilder     运行时拼装 System Prompt
 * @param workspacePathResolver   解析工具传入的相对路径到 cwd
 * @param skillRegistry           Skill 元数据注册表（来自 home 与 cwd 的 SkillDiscovery 扫描结果）
 * @param mcpRuntime              MCP 运行时，持有所有子进程连接
 * @param permissionStore         持久化授权存储
 * @param permissionStorePath     {@code permissions.json} 实际路径，便于 TUI 调试时展示
 * @param home                    MiniCode 全局目录（{@code ~/.minicode-java}）
 * @param cwd                     当前工作目录
 * @param sessionId               本次会话 ID（resume/fork 时复用，否则 UUID 新生成）
 * @param runtimeConfig           运行时配置；{@link Optional#empty()} 表示测试场景下未加载真配置
 */
public record ApplicationServices(ToolRegistry toolRegistry,
                                  PermissionService permissionService,
                                  ContextManager contextManager,
                                  SessionStore sessionStore,
                                  SessionPersistenceRunner sessionPersistenceRunner,
                                  AgentLoop agentLoop,
                                  ModelAdapter modelAdapter,
                                  CompactService compactService,
                                  SystemPromptBuilder systemPromptBuilder,
                                  WorkspacePathResolver workspacePathResolver,
                                  SkillRegistry skillRegistry,
                                  McpRuntime mcpRuntime,
                                  PermissionStore permissionStore,
                                  Path permissionStorePath,
                                  Path home,
                                  Path cwd,
                                  String sessionId,
                                  Optional<RuntimeConfig> runtimeConfig) {
    private static final Duration MODEL_METADATA_TIMEOUT = Duration.ofSeconds(3);
    private static final int LARGE_TOOL_RESULT_THRESHOLD_CHARS = 200_000;
    private static final int TOOL_RESULT_BATCH_BUDGET_CHARS = 400_000;
    private static final int TOOL_RESULT_PREVIEW_CHARS = 20_000;

    /**
     * 兼容构造器：调用方未提供 {@code runtimeConfig} 时，等价于传入 {@link Optional#empty()}。
     * 主要供测试或无配置场景使用。
     */
    public ApplicationServices(ToolRegistry toolRegistry,
                               PermissionService permissionService,
                               ContextManager contextManager,
                               SessionStore sessionStore,
                               SessionPersistenceRunner sessionPersistenceRunner,
                               AgentLoop agentLoop,
                               ModelAdapter modelAdapter,
                               CompactService compactService,
                               SystemPromptBuilder systemPromptBuilder,
                               WorkspacePathResolver workspacePathResolver,
                               SkillRegistry skillRegistry,
                               McpRuntime mcpRuntime,
                               PermissionStore permissionStore,
                               Path permissionStorePath,
                               Path home,
                               Path cwd,
                               String sessionId) {
        this(toolRegistry, permissionService, contextManager, sessionStore, sessionPersistenceRunner, agentLoop,
                modelAdapter, compactService, systemPromptBuilder, workspacePathResolver, skillRegistry, mcpRuntime,
                permissionStore, permissionStorePath, home, cwd, sessionId, Optional.empty());
    }

    /**
     * 紧凑构造器：对 18 个字段做非空校验、把 {@code home} 规范化成绝对路径、保证 {@code sessionId} 非空白。
     *
     * <p>「非法状态不可构造」是 record 的标准做法——校验放在构造点，外部拿到的实例一定是合法的，
     * 后续业务代码可以省掉大量 null 检查。</p>
     */
    public ApplicationServices {
        toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        permissionService = Objects.requireNonNull(permissionService, "permissionService");
        contextManager = Objects.requireNonNull(contextManager, "contextManager");
        sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        sessionPersistenceRunner = Objects.requireNonNull(sessionPersistenceRunner, "sessionPersistenceRunner");
        agentLoop = Objects.requireNonNull(agentLoop, "agentLoop");
        modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
        compactService = Objects.requireNonNull(compactService, "compactService");
        systemPromptBuilder = Objects.requireNonNull(systemPromptBuilder, "systemPromptBuilder");
        workspacePathResolver = Objects.requireNonNull(workspacePathResolver, "workspacePathResolver");
        skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry");
        mcpRuntime = Objects.requireNonNull(mcpRuntime, "mcpRuntime");
        permissionStore = Objects.requireNonNull(permissionStore, "permissionStore");
        permissionStorePath = Objects.requireNonNull(permissionStorePath, "permissionStorePath");
        home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        cwd = Objects.requireNonNull(cwd, "cwd");
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig");
    }

    /**
     * 测试用装配入口：{@link ModelAdapter} 由调用方注入（通常是 {@link MockModelAdapter}），
     * 不会读取 {@link RuntimeConfig}，也不会启动 MCP 子进程。
     *
     * <p>用于不依赖真实模型的单元测试或集成测试。</p>
     */
    public static ApplicationServices create(Path home, Path cwd, String sessionId, ModelAdapter modelAdapter,
                                             AgentEventSink eventSink,
                                             PermissionPromptHandler permissionPromptHandler) {
        Path actualHome = Objects.requireNonNull(home, "home");
        Path actualCwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        Path permissionStorePath = actualHome.resolve("permissions.json");
        PermissionStore permissionStore = new JsonPermissionStore(permissionStorePath);
        PermissionService permissionService = new PromptingPermissionService(permissionPromptHandler, permissionStore);
        WorkspacePathResolver workspacePathResolver = new WorkspacePathResolver();
        SkillRegistry skillRegistry = new SkillRegistry(new SkillDiscovery(actualHome, actualCwd).discover());

        ToolRegistry registry = createBuiltInToolRegistry(permissionService, workspacePathResolver, skillRegistry);

        ContextManager contextManager = new ContextManager(
                new ToolResultStorage(actualHome.resolve("tool-results")),
                LARGE_TOOL_RESULT_THRESHOLD_CHARS,
                TOOL_RESULT_BATCH_BUDGET_CHARS,
                TOOL_RESULT_PREVIEW_CHARS
        );
        SessionStore sessionStore = new SessionStore(actualHome.resolve("sessions"));
        Optional<String> lastEventUuid = sessionStore.latestEventUuid(sessionId, actualCwd.toString());
        SessionPersistenceRunner persistenceRunner = new SessionPersistenceRunner(
                sessionStore,
                new SessionEventFactory(sessionId, actualCwd.toString(),
                        java.time.Clock.systemUTC(),
                        () -> UUID.randomUUID().toString(),
                        lastEventUuid)
        );
        CompactService compactService = new CompactService();
        AgentLoop agentLoop = new AgentLoop(modelAdapter, eventSink, registry, contextManager,
                new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(128_000, 8_000)),
                new AutoCompactController(compactService, AutoCompactPolicy.defaults()));
        return new ApplicationServices(registry, permissionService, contextManager, sessionStore,
                persistenceRunner, agentLoop, modelAdapter, compactService, new SystemPromptBuilder(), workspacePathResolver,
                skillRegistry, McpRuntime.empty(), permissionStore, permissionStorePath, actualHome,
                actualCwd, sessionId, Optional.empty());
    }

    /**
     * 主流程的装配入口。
     *
     * <p>根据 {@link RuntimeConfig#provider()}：MOCK 用 {@link MockModelAdapter}（本地造数），
     * 其它走 {@link AnthropicModelAdapter} + 真实 HTTP transport。
     * 模型的 {@code maxOutputTokens} 通过查 Anthropic Models API 解析得到。</p>
     */
    public static ApplicationServices create(Path home, Path cwd, String sessionId, RuntimeConfig runtimeConfig,
                                             AgentEventSink eventSink,
                                             PermissionPromptHandler permissionPromptHandler) {
        Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        return createWithModelFactory(home, cwd, sessionId, eventSink, permissionPromptHandler, (registry, metadata) ->
                runtimeConfig.provider() == ProviderKind.MOCK
                        ? new MockModelAdapter("mock final")
                        : new AnthropicModelAdapter(runtimeConfig, registry,
                                new HttpAnthropicTransport(java.net.http.HttpClient.newHttpClient(),
                                        runtimeConfig.providerTimeout()),
                                Optional.of(resolveModelContextProfile(runtimeConfig, metadata).resolvedMaxOutputTokens())),
                Optional.of(runtimeConfig));
    }

    /**
     * 同时携带 {@link RuntimeConfig} 和已构造好的 {@link ModelAdapter} 的装配入口。
     *
     * <p>用于「想要走配置驱动的 MCP/上下文窗口逻辑，但模型适配器走自定义实现」的测试场景。</p>
     */
    public static ApplicationServices create(Path home, Path cwd, String sessionId, RuntimeConfig runtimeConfig,
                                             ModelAdapter modelAdapter,
                                             AgentEventSink eventSink,
                                             PermissionPromptHandler permissionPromptHandler) {
        Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        Objects.requireNonNull(modelAdapter, "modelAdapter");
        return createWithModelFactory(home, cwd, sessionId, eventSink, permissionPromptHandler,
                (ignored, metadata) -> modelAdapter, Optional.of(runtimeConfig));
    }

    /**
     * 三个 public {@code create} 方法的共同骨架——把"如何创造 ModelAdapter"参数化，
     * 其余装配步骤完全一致。
     *
     * <p>装配顺序按依赖图拓扑序：存储 → 服务 → 工具 → 上下文/模型 → 主循环。
     * MCP 工具在内置工具之后注入到同一个 {@link ToolRegistry}，模型对它们一视同仁。</p>
     */
    private static ApplicationServices createWithModelFactory(Path home, Path cwd, String sessionId,
                                                              AgentEventSink eventSink,
                                                              PermissionPromptHandler permissionPromptHandler,
                                                              java.util.function.BiFunction<ToolRegistry, Optional<ModelMetadata>, ModelAdapter> modelFactory,
                                                              Optional<RuntimeConfig> runtimeConfig) {
        Path actualHome = Objects.requireNonNull(home, "home");
        Path actualCwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        Path permissionStorePath = actualHome.resolve("permissions.json");
        PermissionStore permissionStore = new JsonPermissionStore(permissionStorePath);
        PermissionService permissionService = new PromptingPermissionService(permissionPromptHandler, permissionStore);
        WorkspacePathResolver workspacePathResolver = new WorkspacePathResolver();
        SkillRegistry skillRegistry = new SkillRegistry(new SkillDiscovery(actualHome, actualCwd).discover());

        ToolRegistry registry = createBuiltInToolRegistry(permissionService, workspacePathResolver, skillRegistry);
        McpRuntime mcpRuntime = runtimeConfig
                .map(config -> McpToolHydrator.hydrate(config.mcpServers(), permissionService, actualCwd))
                .orElseGet(McpRuntime::empty);
        mcpRuntime.tools().forEach(registry::register);
        ContextManager contextManager = new ContextManager(
                new ToolResultStorage(actualHome.resolve("tool-results")),
                LARGE_TOOL_RESULT_THRESHOLD_CHARS,
                TOOL_RESULT_BATCH_BUDGET_CHARS,
                TOOL_RESULT_PREVIEW_CHARS
        );
        Optional<ModelMetadata> modelMetadata = runtimeConfig.flatMap(ApplicationServices::fetchModelMetadata);
        ModelAdapter modelAdapter = Objects.requireNonNull(modelFactory.apply(registry, modelMetadata), "modelAdapter");
        SessionStore sessionStore = new SessionStore(actualHome.resolve("sessions"));
        Optional<String> lastEventUuid = sessionStore.latestEventUuid(sessionId, actualCwd.toString());
        SessionPersistenceRunner persistenceRunner = new SessionPersistenceRunner(
                sessionStore,
                new SessionEventFactory(sessionId, actualCwd.toString(),
                        java.time.Clock.systemUTC(),
                        () -> UUID.randomUUID().toString(),
                        lastEventUuid)
        );
        CompactService compactService = new CompactService();
        AgentLoop agentLoop = runtimeConfig
                .map(config -> new AgentLoop(modelAdapter, eventSink, registry, contextManager,
                        new ContextStatsCalculator(new TokenAccountingService(), modelContextWindow(config, modelMetadata)),
                        new AutoCompactController(compactService, AutoCompactPolicy.defaults())))
                .orElseGet(() -> new AgentLoop(modelAdapter, eventSink, registry, contextManager));
        return new ApplicationServices(registry, permissionService, contextManager, sessionStore,
                persistenceRunner, agentLoop, modelAdapter, compactService, new SystemPromptBuilder(), workspacePathResolver,
                skillRegistry, mcpRuntime, permissionStore, permissionStorePath, actualHome,
                actualCwd, sessionId, runtimeConfig);
    }

    private static ModelContextWindow modelContextWindow(RuntimeConfig runtimeConfig) {
        return modelContextWindow(runtimeConfig, Optional.empty());
    }

    private static ModelContextWindow modelContextWindow(RuntimeConfig runtimeConfig, Optional<ModelMetadata> metadata) {
        ModelContextProfile profile = resolveModelContextProfile(runtimeConfig, metadata);
        return new ModelContextWindow(profile.contextWindow(), profile.outputReserve());
    }

    private static ModelContextProfile resolveModelContextProfile(RuntimeConfig runtimeConfig,
                                                                  Optional<ModelMetadata> metadata) {
        return new ModelMetadataResolver().resolve(
                runtimeConfig.model(),
                runtimeConfig.contextWindow(),
                runtimeConfig.maxOutputTokens(),
                metadata
        );
    }

    private static Optional<ModelMetadata> fetchModelMetadata(RuntimeConfig runtimeConfig) {
        if (!shouldFetchModelMetadata(runtimeConfig)) {
            return Optional.empty();
        }
        return metadataClient(runtimeConfig).fetch(runtimeConfig.model());
    }

    private static boolean shouldFetchModelMetadata(RuntimeConfig runtimeConfig) {
        return runtimeConfig.provider() != ProviderKind.MOCK;
    }

    private static AnthropicModelsApiClient metadataClient(RuntimeConfig runtimeConfig) {
        HttpAnthropicTransport transport = new HttpAnthropicTransport(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(MODEL_METADATA_TIMEOUT)
                        .build(),
                MODEL_METADATA_TIMEOUT
        );
        return new AnthropicModelsApiClient(runtimeConfig, transport);
    }

    /**
     * 显式 new 出全部内置工具并注册到 {@link ToolRegistry}。
     *
     * <p>没有用 ServiceLoader/反射扫描——MiniCode 是单体 CLI，工具集合是固定的；显式 new 的好处是
     * IDE 能直接跳转、新增工具改一个方法可见全貌、零反射启动开销。真正动态扩展走 MCP 协议。</p>
     */
    private static ToolRegistry createBuiltInToolRegistry(PermissionService permissionService,
                                                         WorkspacePathResolver workspacePathResolver,
                                                         SkillRegistry skillRegistry) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new AskUserTool());
        registry.register(new LoadSkillTool(skillRegistry));
        registry.register(new ReadFileTool(ReadFilePathAccess.fromPermissionService(permissionService),
                workspacePathResolver));
        registry.register(new RunCommandTool(permissionService, java.time.Duration.ofSeconds(5),
                workspacePathResolver));
        registry.register(new ListFilesTool(permissionService, workspacePathResolver));
        registry.register(new GrepFilesTool(permissionService, workspacePathResolver));
        registry.register(new WriteFileTool(permissionService, workspacePathResolver));
        registry.register(new EditFileTool(permissionService, workspacePathResolver));
        registry.register(new PatchFileTool(permissionService, workspacePathResolver));
        registry.register(new ModifyFileTool(permissionService, workspacePathResolver));
        return registry;
    }

    /**
     * 把"用户已有的对话历史"包装成一次新的 {@link AgentTurnRequest}：
     * 自动生成 turnId、注入当前 cwd/sessionId、把历史消息前置一条最新的 System Prompt。
     */
    public AgentTurnRequest turnRequest(List<ChatMessage> messages, int maxSteps) {
        return new AgentTurnRequest(
                UUID.randomUUID().toString(),
                cwd,
                sessionId,
                withFreshSystemPrompt(messages),
                maxSteps,
                Optional.empty()
        );
    }

    /**
     * 执行一次完整的 Turn：刷新 System Prompt 后转交 {@link AgentLoop}，
     * 用 try/finally 确保 turnId 在权限服务里 begin/end 配对（让 ALLOW_ONCE 授权按 Turn 隔离）。
     */
    public AgentTurnResult runTurn(AgentTurnRequest request) {
        AgentTurnRequest actualRequest = Objects.requireNonNull(request, "request");
        actualRequest = new AgentTurnRequest(
                actualRequest.turnId(),
                actualRequest.cwd(),
                actualRequest.sessionId(),
                withFreshSystemPrompt(actualRequest.messages()),
                actualRequest.maxSteps(),
                actualRequest.modelName(),
                actualRequest.cancellationToken()
        );
        permissionService.beginTurn(actualRequest.turnId());
        try {
            return agentLoop.runTurn(actualRequest);
        } finally {
            permissionService.endTurn(actualRequest.turnId());
        }
    }

    /**
     * 加载当前 session 自最近一次 compact 边界以来的全部消息——上层用来拼装下一次 Turn 的输入历史。
     */
    public List<ChatMessage> sessionMessages() {
        return sessionStore.loadMessagesSinceLatestCompactBoundary(sessionId, cwd.toString());
    }

    /**
     * 用户在 TUI 里手动触发的"压缩历史"操作。
     *
     * <p>调 {@link CompactService} 让模型把当前 session 历史摘要成一条总结消息。若成功生成 boundary，
     * 还会把 boundary 之后保留下来的非系统消息一并写回 session store——
     * 后续加载时 {@link SessionStore#loadMessagesSinceLatestCompactBoundary} 就能跳过被压缩的历史。</p>
     */
    public ManualCompactResult manualCompact() {
        ManualCompactResult result = compactService.compact(new CompactRequest(
                withFreshSystemPrompt(sessionMessages()),
                modelAdapter,
                CompactTrigger.MANUAL
        ));
        if (result.status() == CompactStatus.COMPACTED) {
            List<PersistenceAction> actions = new ArrayList<>();
            actions.add(new PersistenceAction.AppendCompactBoundaryAction(
                    result.boundary().orElseThrow().summaryMessage(),
                    result.boundary().orElseThrow().metadata()
            ));
            List<ChatMessage> retainedMessages = retainedMessagesAfterCompactBoundary(result);
            if (!retainedMessages.isEmpty()) {
                actions.add(new PersistenceAction.AppendMessagesAction(retainedMessages));
            }
            sessionPersistenceRunner.apply(new TurnPersistencePlan(actions));
        }
        return result;
    }

    /** 返回 MCP 服务器连接概况，TUI 用其展示状态/工具数。 */
    public List<McpServerSummary> mcpServerSummaries() {
        return mcpRuntime.summaries();
    }

    /**
     * 释放需要显式回收的资源（目前主要是 MCP 子进程）。
     * 在 {@link MiniCodeApp} 的 finally 段调用，保证用户 Ctrl+C 退出也会触发清理。
     */
    public void close() {
        mcpRuntime.close();
    }

    /**
     * 计算 compact 之后还需要重新写入 session store 的消息：跳过所有 SystemMessage，
     * 并跳过第一条与 boundary summary 相同的消息（避免重复落盘）。
     */
    private List<ChatMessage> retainedMessagesAfterCompactBoundary(ManualCompactResult result) {
        ChatMessage summary = result.boundary().orElseThrow().summaryMessage();
        boolean skippedSummary = false;
        List<ChatMessage> retained = new ArrayList<>();
        for (ChatMessage message : result.messages()) {
            if (message instanceof SystemMessage) {
                continue;
            }
            if (!skippedSummary && message.equals(summary)) {
                skippedSummary = true;
                continue;
            }
            retained.add(message);
        }
        return List.copyOf(retained);
    }

    /**
     * 把传入消息列表前面所有的 SystemMessage 剥掉，重新拼一条最新的 System Prompt 放在头部。
     *
     * <p>为什么每次 Turn 都要重拼？因为 cwd 下的文件、注册的工具、加载的 Skill 都可能在两次 Turn 之间变化，
     * 用旧 prompt 会让模型「看不见」这些变化。重拼成本可控（毫秒级字符串拼接），收益是上下文实时。</p>
     */
    private List<ChatMessage> withFreshSystemPrompt(List<ChatMessage> messages) {
        List<ChatMessage> refreshed = new ArrayList<>();
        refreshed.add(new SystemMessage(systemPromptBuilder.build(
                new SystemPromptBuilder.Input(home, cwd, toolRegistry, skillRegistry.summaries(),
                        mcpRuntime.summaries())
        )));
        for (ChatMessage message : messages) {
            if (!(message instanceof SystemMessage)) {
                refreshed.add(message);
            }
        }
        return List.copyOf(refreshed);
    }
}
