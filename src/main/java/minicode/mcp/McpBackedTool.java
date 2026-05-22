package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.model.PermissionDeniedException;
import minicode.permissions.model.PermissionResource;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 把"远程 MCP 工具"伪装成本地 {@link Tool} 的代理。
 *
 * <p>MCP（Model Context Protocol）服务器以子进程的形态对外暴露一组工具，
 * 本类通过 {@link McpClient} 跨进程调用它们，并向上把自己包装成与本地工具
 * 完全相同的 {@code Tool} 接口。这样 ToolRegistry 在注册和分发时，无需关心
 * 工具背后是本地 Java 实现还是远程 stdio 子进程。</p>
 *
 * <p>每个 MCP 工具的对外名称会被加上 {@code mcp__&lt;server&gt;__} 前缀（见
 * {@link McpToolName#wrappedName}），避免与本地工具同名冲突。所有 MCP 工具
 * 默认被视为敏感操作：若注入了 {@link PermissionService}，{@link #run} 会先
 * 走权限审批，被拒就以错误态 {@link ToolResult} 返回，不会发起远程调用。</p>
 *
 * <p>协议层细节由 {@link StdioMcpClient} 负责（JSON-RPC 2.0 over stdio：
 * {@code initialize} → {@code tools/list} → {@code tools/call}），本类只关心
 * 把入参 / 出参在 {@code Tool} 语义和 MCP 语义之间转换。</p>
 */
public final class McpBackedTool implements Tool {
    /** 复用的 Jackson {@link ObjectMapper}，用于构造空的 JSON object 兜底。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 所属 MCP 服务器的逻辑名（来自 settings.json 中 mcpServers 的 key），用于工具名前缀和权限校验。 */
    private final String serverName;
    /** 远程工具描述（原始名称、说明、输入 schema），由 {@code tools/list} 拉取得到。 */
    private final McpToolDescriptor descriptor;
    /** 与 MCP 服务器实际通信的客户端，调用 {@code tools/call} 时使用。 */
    private final McpClient client;
    /** 可选的权限服务；为空表示跳过审批（如测试或显式信任的场景）。 */
    private final Optional<PermissionService> permissionService;
    /** 已规范化的输入 JSON Schema，确保上层永远拿到一个 object 类型的 schema。 */
    private final JsonNode inputSchema;
    /** 暴露给 ToolRegistry 的元数据（含加前缀的工具名、来源 MCP、能力集等）。 */
    private final ToolMetadata metadata;

    /**
     * 构造一个不做权限审批的 MCP 工具代理（通常用于测试或自动化场景）。
     *
     * @param serverName MCP 服务器逻辑名，必须非空白
     * @param descriptor 远程工具描述
     * @param client 已建好连接的 MCP 客户端
     */
    public McpBackedTool(String serverName, McpToolDescriptor descriptor, McpClient client) {
        this(serverName, descriptor, client, Optional.empty());
    }

    /**
     * 构造一个会先走权限审批再调用的 MCP 工具代理（生产路径默认用这个）。
     *
     * @param serverName MCP 服务器逻辑名
     * @param descriptor 远程工具描述
     * @param client 已建好连接的 MCP 客户端
     * @param permissionService 权限服务，调用前会对该 MCP 工具做 ensure 校验
     */
    public McpBackedTool(String serverName, McpToolDescriptor descriptor, McpClient client,
                         PermissionService permissionService) {
        this(serverName, descriptor, client, Optional.of(permissionService));
    }

    private McpBackedTool(String serverName, McpToolDescriptor descriptor, McpClient client,
                          Optional<PermissionService> permissionService) {
        this.serverName = requireText(serverName, "serverName");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.client = Objects.requireNonNull(client, "client");
        this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
        this.inputSchema = normalizeInputSchema(descriptor.inputSchema().orElse(null));
        this.metadata = new ToolMetadata(
                McpToolName.wrappedName(serverName, descriptor.name()),
                descriptor.description().isBlank()
                        ? "Call MCP tool " + descriptor.name() + " from server " + serverName + "."
                        : descriptor.description(),
                inputSchema,
                ToolOrigin.MCP,
                Set.of(ToolCapability.COMMAND),
                ToolStatus.AVAILABLE
        );
    }

    @Override
    public ToolMetadata metadata() {
        return metadata;
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema;
    }

    /**
     * 校验并规范化输入。
     *
     * <p>MCP 协议要求 {@code tools/call} 的 {@code arguments} 是一个 JSON object。
     * 这里做最低限度的兜底：null / missing 节点会被替换成空 object；任何非 object
     * 输入直接拒绝，避免协议层报错。具体字段约束交由远程服务器自身校验。</p>
     */
    @Override
    public ValidationResult validateInput(JsonNode input) {
        if (input == null || input.isNull() || input.isMissingNode()) {
            return ValidationResult.valid(MAPPER.createObjectNode());
        }
        if (!input.isObject()) {
            return ValidationResult.invalid(List.of("MCP tool input must be a JSON object"));
        }
        return ValidationResult.valid(input);
    }

    /**
     * 执行远程 MCP 工具调用。
     *
     * <p>流程：先（可选）调用 {@link PermissionService#ensureMcpTool} 做权限审批；
     * 通过则调用 {@link McpClient#callTool}，把返回的 JSON 结果交给
     * {@link McpToolResultFormatter} 格式化成统一的 {@link ToolResult}。</p>
     *
     * <p>权限被拒会返回带说明的错误 ToolResult，不会抛出异常，确保 AgentLoop
     * 在面对本地工具与 MCP 工具时拥有一致的失败语义。</p>
     */
    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
        if (permissionService.isPresent()) {
            try {
                permissionService.orElseThrow().ensureMcpTool(new PermissionResource.McpToolResource(
                        serverName,
                        descriptor.name(),
                        metadata.name(),
                        metadata.description()
                ), new PermissionContext(toolContext.sessionId(), toolContext.turnId(), toolContext.toolUseId()));
            } catch (PermissionDeniedException exception) {
                String message = exception.feedback()
                        .map(feedback -> "Permission denied: " + feedback)
                        .orElse("Permission denied");
                return ToolResult.error(message);
            }
        }
        return McpToolResultFormatter.toToolResult(client.callTool(descriptor.name(), normalizedInput));
    }

    /** 返回工具所属的 MCP 服务器逻辑名（即 settings.json 中的配置 key）。 */
    public String serverName() {
        return serverName;
    }

    /**
     * 返回 MCP 服务器侧的原始工具名（不带 {@code mcp__&lt;server&gt;__} 前缀）。
     * 用于跨进程发起 {@code tools/call} 时拼装 params。
     */
    public String originalToolName() {
        return descriptor.name();
    }

    /**
     * 把远程返回的 schema 规范化为 object 类型；缺失或非 object 时返回一个
     * 允许任意属性的兜底 schema，保证下游 LLM 总能拿到合法的 JSON Schema。
     */
    private static JsonNode normalizeInputSchema(JsonNode schema) {
        if (schema != null && schema.isObject()) {
            return schema;
        }
        ObjectNode fallback = MAPPER.createObjectNode();
        fallback.put("type", "object");
        fallback.put("additionalProperties", true);
        return fallback;
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
