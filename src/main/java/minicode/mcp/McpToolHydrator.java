package minicode.mcp;

import minicode.permissions.api.PermissionService;
import minicode.tools.api.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.nio.file.Path;

/**
 * 启动期把 MCP 服务器配置"注水"成一组可用工具的工具类。
 *
 * <p>所谓"注水"（hydrate）就是：读入若干 {@link McpServerConfig}，对每个
 * 启用的条目实际拉起子进程、握手、列出工具，最后把每个远程工具包装成
 * {@link McpBackedTool} 加入返回的 {@link McpRuntime}。
 * ApplicationServices 启动时调用本类，再把 tools 列表注册进 ToolRegistry，
 * MCP 工具便和本地工具一样可被 AgentLoop 调度。</p>
 *
 * <p>容错设计：单个 MCP 服务器启动失败不会让整个 Agent 崩。失败被记录在
 * {@link McpServerSummary}（状态 {@link McpServerStatus#ERROR} + 错误类别），
 * 其它服务器继续注水。这样面对网络抖动或单点配置错误时仍能尽力可用。</p>
 *
 * <p>本类是只暴露静态方法的工具类，构造函数私有以阻止实例化。</p>
 */
public final class McpToolHydrator {
    private McpToolHydrator() {
    }

    /**
     * 不带权限服务、用进程当前目录作为 baseCwd 的便捷重载。常见于早期测试或
     * 不需要审批的内部调用。
     *
     * @param configs 服务器名 → 配置
     * @return 注水完成的 {@link McpRuntime}
     */
    public static McpRuntime hydrate(Map<String, McpServerConfig> configs) {
        return hydrate(configs, null);
    }

    /**
     * 携带权限服务、使用进程当前目录作为 baseCwd 的便捷重载。
     *
     * @param configs 服务器名 → 配置
     * @param permissionService 权限服务，最终注入到每个 {@link McpBackedTool}；可为 null
     */
    public static McpRuntime hydrate(Map<String, McpServerConfig> configs, PermissionService permissionService) {
        return hydrate(configs, permissionService, Path.of(".").toAbsolutePath().normalize());
    }

    /**
     * 真正的注水入口：遍历所有配置，依次启动 MCP 服务器、列出工具并打包。
     *
     * <p>对每个条目：</p>
     * <ol>
     *   <li>{@code enabled=false} 直接产出 {@link McpServerStatus#DISABLED} 概要并跳过。</li>
     *   <li>否则用 {@link StdioMcpClient} 启动子进程，调用 {@code initialize}
     *       与 {@code tools/list}，把每个 descriptor 包装成 {@link McpBackedTool}。</li>
     *   <li>启动 / 握手抛出的 {@link RuntimeException} 会被吞掉，子进程关闭，
     *       概要记成 {@link McpServerStatus#ERROR} 并尽量保留 {@link McpErrorKind}。</li>
     * </ol>
     *
     * @param configs 服务器名 → 配置；不可为 null
     * @param permissionService 注入到每个工具的权限服务；为 null 表示不审批
     * @param baseCwd 解析每个服务器 cwd 时使用的基准目录；不可为 null
     * @return 包含工具列表、连接概要、客户端句柄的运行时聚合
     */
    public static McpRuntime hydrate(Map<String, McpServerConfig> configs, PermissionService permissionService,
                                     Path baseCwd) {
        List<Tool> tools = new ArrayList<>();
        List<McpServerSummary> summaries = new ArrayList<>();
        List<McpClient> clients = new ArrayList<>();
        Path actualBaseCwd = Objects.requireNonNull(baseCwd, "baseCwd").toAbsolutePath().normalize();
        for (Map.Entry<String, McpServerConfig> entry : Objects.requireNonNull(configs, "configs").entrySet()) {
            String serverName = entry.getKey();
            McpServerConfig config = entry.getValue();
            String command = summarizeCommand(config);
            if (!config.enabled()) {
                summaries.add(new McpServerSummary(serverName, command, McpServerStatus.DISABLED, 0, Optional.empty()));
                continue;
            }
            StdioMcpClient client = new StdioMcpClient(serverName, config, actualBaseCwd);
            try {
                client.start();
                List<McpToolDescriptor> descriptors = client.listTools();
                for (McpToolDescriptor descriptor : descriptors) {
                    tools.add(permissionService == null
                            ? new McpBackedTool(serverName, descriptor, client)
                            : new McpBackedTool(serverName, descriptor, client, permissionService));
                }
                clients.add(client);
                summaries.add(new McpServerSummary(serverName, command, McpServerStatus.CONNECTED,
                        descriptors.size(), Optional.empty()));
            } catch (RuntimeException exception) {
                client.close();
                McpErrorKind kind = exception instanceof McpException mcpException
                        ? mcpException.kind()
                        : McpErrorKind.TOOL_CALL_FAILED;
                summaries.add(new McpServerSummary(serverName, command, McpServerStatus.ERROR, 0,
                        Optional.of(messageOrDefault(exception)), Optional.of(kind)));
            }
        }
        return new McpRuntime(tools, summaries, clients);
    }

    /** 取配置的可读 endpoint 摘要（用于在概要里展示这条 server 怎么连）。 */
    private static String summarizeCommand(McpServerConfig config) {
        return config.endpointSummary();
    }

    /**
     * 提取异常的可读消息；若消息为空白则退而求其次返回异常类型的简单名，
     * 避免概要里出现空字符串让用户摸不着头脑。
     */
    private static String messageOrDefault(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
