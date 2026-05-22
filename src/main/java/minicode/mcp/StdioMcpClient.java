package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 通过 stdio + JSON-RPC 2.0 与 MCP 服务器子进程通信的客户端实现。
 *
 * <p>MCP 协议规定客户端启动时先发 {@code initialize}，握手成功再发
 * {@code notifications/initialized}，之后才能调用 {@code tools/list} 和
 * {@code tools/call}。本类按这个顺序在 {@link #start()} 里完成全部握手，
 * 并把后续的请求 / 响应都封装进同步方法，让上层（{@link McpBackedTool}）
 * 像调本地方法一样使用。</p>
 *
 * <p>线帧格式遵循 LSP 风格：每条消息前缀
 * {@code Content-Length: &lt;n&gt;\r\n\r\n}，紧跟 {@code n} 字节 JSON 体。
 * 这与简化教程里的"按行 JSON"略有差异，但能保证多字节 / 大消息可靠传输。</p>
 *
 * <p>所有公开方法都是 {@code synchronized}，确保任意时刻只有一次请求在飞，
 * 顺序读取响应即可正确配对——不需要为不同 id 维护回调表。读响应被异步化
 * 是为了用 {@link CompletableFuture#get(long, TimeUnit)} 加超时，避免子进程
 * 卡死时永远阻塞调用线程。</p>
 *
 * <p>容错：所有底层 IO 异常被包装为 {@link McpException}，并尽量带上
 * 准确的 {@link McpErrorKind}，便于上层（如 {@link McpToolHydrator}）展示。</p>
 */
public final class StdioMcpClient implements McpClient {
    /** 复用的 Jackson {@link ObjectMapper}，做 JSON 序列化 / 反序列化。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** MCP 服务器的逻辑名，仅用于错误信息和日志展示。 */
    private final String serverName;
    /** 启动子进程所需的命令、参数、环境变量、超时等配置。 */
    private final McpServerConfig config;
    /** 解析 {@code config.cwd()} 相对路径时使用的基准目录。 */
    private final Path baseCwd;
    /** 已启动的子进程；未启动或已关闭时为 null。 */
    private Process process;
    /** 进程关闭后仍可向调用方暴露的句柄，便于做最终的资源审计。 */
    private ProcessHandle lastProcessHandle;
    /** JSON-RPC 请求 id 自增计数器，从 1 开始。 */
    private int nextId = 1;

    /**
     * 便捷构造：使用进程当前目录作为 baseCwd。
     *
     * @param serverName 服务器逻辑名（不可空白）
     * @param config 服务器启动配置
     */
    public StdioMcpClient(String serverName, McpServerConfig config) {
        this(serverName, config, Path.of(".").toAbsolutePath().normalize());
    }

    /**
     * 完整构造：显式指定基准工作目录。{@link McpToolHydrator} 在批量启动时
     * 会传项目根目录进来，确保各 server 的 {@code cwd} 在多调用方场景下
     * 解析一致。
     *
     * @param serverName 服务器逻辑名
     * @param config 服务器启动配置
     * @param baseCwd 解析相对 cwd 的基准目录，会被规范化为绝对路径
     */
    public StdioMcpClient(String serverName, McpServerConfig config, Path baseCwd) {
        this.serverName = requireText(serverName, "serverName");
        this.config = Objects.requireNonNull(config, "config");
        this.baseCwd = Objects.requireNonNull(baseCwd, "baseCwd").toAbsolutePath().normalize();
    }

    /**
     * 启动子进程并完成 MCP 握手。已经在跑的进程会直接 no-op。
     *
     * <p>步骤：拉起子进程 → 发送 {@code initialize} 请求并等待响应 →
     * 发送 {@code notifications/initialized} 通知（无需响应）。
     * 任意一步失败会抛 {@link McpException}，由调用方（如 hydrator）兜住。</p>
     */
    @Override
    public synchronized void start() {
        if (process != null && process.isAlive()) {
            return;
        }
        spawnProcess();
        request("initialize", initializeParams(), config.initializeTimeout(), McpErrorKind.HANDSHAKE_FAILED);
        notify("notifications/initialized", MAPPER.createObjectNode());
    }

    /**
     * 调用 {@code tools/list}，把服务器声明的工具列表转换成 {@link McpToolDescriptor}。
     *
     * <p>容错：服务器返回的 {@code tools} 字段缺失 / 不是数组时，返回空列表
     * 而非抛异常；单个工具如果连 {@code name} 都没有就静默跳过，避免一个
     * 异常工具拖垮整个服务器的注册流程。</p>
     */
    @Override
    public synchronized List<McpToolDescriptor> listTools() {
        JsonNode result = request("tools/list", MAPPER.createObjectNode(), config.callTimeout(), McpErrorKind.LIST_TOOLS_FAILED);
        JsonNode tools = result.get("tools");
        if (tools == null || !tools.isArray()) {
            return List.of();
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            JsonNode inputSchema = tool.get("inputSchema");
            descriptors.add(new McpToolDescriptor(
                    name,
                    tool.path("description").asText(""),
                    inputSchema == null || inputSchema.isNull() ? Optional.empty() : Optional.of(inputSchema)
            ));
        }
        return List.copyOf(descriptors);
    }

    /**
     * 调用远程的某个工具：发出 {@code tools/call} 并返回 {@code result} 子节点。
     *
     * <p>{@code arguments} 为 null / null 节点时会替换成空 object，遵循 MCP
     * 协议中 arguments 必须是 object 的约束。</p>
     *
     * @param name 远程工具的原始名（注意：不带 {@code mcp__server__} 前缀）
     * @param arguments LLM 给出的实参 JSON object
     * @return 服务器返回的 {@code result} 节点（不含 JSON-RPC 信封）
     */
    @Override
    public synchronized JsonNode callTool(String name, JsonNode arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null || arguments.isNull() || arguments.isMissingNode()
                ? MAPPER.createObjectNode()
                : arguments);
        return request("tools/call", params, config.callTimeout(), McpErrorKind.TOOL_CALL_FAILED);
    }

    /**
     * 暴露子进程的 {@link ProcessHandle}，供外部（如健康监控、TUI 状态栏）
     * 查询 PID 和存活状态。
     *
     * <p>进程存活时返回当前句柄，{@link #close()} 后仍能拿到上一次的句柄
     * （{@link #lastProcessHandle}），方便事后审计。从未启动则返回空。</p>
     */
    public synchronized Optional<ProcessHandle> processHandle() {
        if (process != null) {
            lastProcessHandle = process.toHandle();
            return Optional.of(lastProcessHandle);
        }
        return Optional.ofNullable(lastProcessHandle);
    }

    /**
     * 关闭 stdio 流并等待子进程退出，确保不留下僵尸进程。
     *
     * <p>关闭策略采用三段递进：先关 stdin / stdout / stderr 触发对方收到 EOF
     * 自然退出 → 1 秒还没退就 {@link Process#destroy()} 发 SIGTERM →
     * 再 1 秒未退就 {@link Process#destroyForcibly()} 发 SIGKILL。
     * 期间被 {@link InterruptedException} 打断会正确恢复中断标记后强杀。</p>
     */
    @Override
    public synchronized void close() {
        if (process == null) {
            return;
        }
        Process current = process;
        try {
            current.getOutputStream().close();
            current.getInputStream().close();
            current.getErrorStream().close();
        } catch (IOException ignored) {
        }
        try {
            if (!current.waitFor(1, TimeUnit.SECONDS)) {
                current.destroy();
            }
            if (!current.waitFor(1, TimeUnit.SECONDS)) {
                current.destroyForcibly();
                current.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        } finally {
            process = null;
        }
    }

    /**
     * 用 {@link ProcessBuilder} 真正拉起 MCP 子进程。
     *
     * <p>解析 {@code config.cwd()}（相对路径基于 baseCwd 解析）、注入额外环境变量、
     * 启动后立刻保存 {@link ProcessHandle}。命令为空白或 {@link IOException}
     * 都会被翻译为带上下文（命令、原因）的 {@link McpException}，方便用户定位。</p>
     */
    private void spawnProcess() {
        String command = config.command();
        if (command.isBlank()) {
            throw new McpException(McpErrorKind.START_FAILED,
                    "MCP server \"" + serverName + "\" has no command configured.");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.command().addAll(config.args());
        builder.directory(config.cwd()
                .map(cwd -> baseCwd.resolve(cwd).toAbsolutePath().normalize())
                .orElse(baseCwd)
                .toFile());
        builder.environment().putAll(config.env());
        try {
            process = builder.start();
            lastProcessHandle = process.toHandle();
        } catch (IOException exception) {
            throw new McpException(McpErrorKind.START_FAILED,
                    "Failed to start MCP server \"" + serverName + "\" using command \"" + command + "\"."
                            + startFailureDetail(command, exception),
                    exception);
        }
    }

    /**
     * 给启动失败的异常补一段易读说明。常见的"命令找不到"会被转写成
     * 引导用户去装包 / 检查 PATH 的提示，普通错误就附上原始 message。
     */
    private static String startFailureDetail(String command, IOException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("CreateProcess error=2") || message.toLowerCase(Locale.ROOT).contains("no such file")) {
            return "\nCommand not found: " + command + ". Install it first and ensure it is available in PATH.";
        }
        return message.isBlank() ? "" : "\n" + message;
    }

    /**
     * 发送一次 JSON-RPC 请求并阻塞读取响应（带超时）。这是 {@link #start}、
     * {@link #listTools}、{@link #callTool} 共同依赖的核心算法。
     *
     * <p>组装 {@code {jsonrpc, id, method, params}} 写入 stdin，然后通过
     * {@link #readMessageWithTimeout} 在限定时间内读完一条响应。
     * 服务器返回 {@code error} 字段会被转换成 {@link McpException}。</p>
     *
     * @param method JSON-RPC 方法名
     * @param params 请求参数
     * @param timeout 等待响应的上限
     * @param failureKind 转译失败时使用的错误类别
     * @return 响应中的 {@code result} 节点
     */
    private JsonNode request(String method, JsonNode params, Duration timeout, McpErrorKind failureKind) {
        ensureRunning();
        int id = nextId++;
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params);
        writeMessage(message);
        JsonNode response = readMessageWithTimeout(timeout, method, failureKind);
        if (response.has("error")) {
            throw new McpException(failureKind, "MCP " + serverName + ": "
                    + response.path("error").path("message").asText("request failed"));
        }
        return response.path("result");
    }

    /**
     * 发送一条 JSON-RPC 通知（无 id、无返回）。MCP 中的
     * {@code notifications/initialized} 走的就是这条路径。
     */
    private void notify(String method, JsonNode params) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params);
        writeMessage(message);
    }

    private ObjectNode initializeParams() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "minicode-java").put("version", "0.1.0");
        return params;
    }

    /**
     * 将一个 JSON 节点按 {@code Content-Length} 帧格式写入子进程 stdin。
     * 写入异常被翻译为 {@link McpErrorKind#PROCESS_EXITED}，因为这通常意味着
     * 对端已经退出导致 SIGPIPE。
     */
    private void writeMessage(JsonNode message) {
        ensureRunning();
        try {
            byte[] body = MAPPER.writeValueAsBytes(message);
            OutputStream stdin = process.getOutputStream();
            stdin.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            stdin.write(body);
            stdin.flush();
        } catch (IOException exception) {
            throw new McpException(McpErrorKind.PROCESS_EXITED,
                    "MCP server \"" + serverName + "\" closed while writing request.", exception);
        }
    }

    /**
     * 把阻塞式读取 {@link #readMessage} 包装到 {@link CompletableFuture} 上，
     * 用 {@code get(timeout)} 实现带超时的等待。
     *
     * <p>{@link TimeoutException} 翻译成 {@link McpErrorKind#TIMEOUT}；
     * 已经是 {@link McpException} 的原样抛；其它异常会试着剥出 cause 后包装。</p>
     */
    private JsonNode readMessageWithTimeout(Duration timeout, String method, McpErrorKind failureKind) {
        CompletableFuture<JsonNode> future = CompletableFuture.supplyAsync(() -> readMessage(method, failureKind));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new McpException(McpErrorKind.TIMEOUT,
                    "MCP " + serverName + ": request timed out for " + method, exception);
        } catch (McpException exception) {
            throw exception;
        } catch (Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof McpException mcpException) {
                throw mcpException;
            }
            throw new McpException(failureKind, "MCP " + serverName + ": request failed for " + method, cause);
        }
    }

    /**
     * 从子进程 stdout 解析下一条带 {@code Content-Length} 头的 JSON-RPC 帧。
     *
     * <p>算法：用一个滑动匹配状态机逐字节扫描，直到看到帧分隔符 {@code \r\n\r\n}；
     * 然后从 header 中读出 {@code Content-Length}，再 {@code readNBytes} 读完整 body。
     * 任何提前 EOF 都视为子进程退出（{@link McpErrorKind#PROCESS_EXITED}），
     * 缺 Content-Length 视为 {@link McpErrorKind#PROTOCOL_ERROR}。</p>
     */
    private JsonNode readMessage(String method, McpErrorKind failureKind) {
        try {
            InputStream stdout = process.getInputStream();
            byte[] separator = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < separator.length) {
                int next = stdout.read();
                if (next < 0) {
                    throw new McpException(McpErrorKind.PROCESS_EXITED,
                            "MCP server \"" + serverName + "\" closed before completing " + method + ".");
                }
                header.write(next);
                matched = next == separator[matched] ? matched + 1 : next == separator[0] ? 1 : 0;
            }
            int contentLength = contentLength(header.toString(StandardCharsets.US_ASCII));
            if (contentLength <= 0) {
                throw new McpException(McpErrorKind.PROTOCOL_ERROR,
                        "MCP " + serverName + ": missing Content-Length for " + method + ".");
            }
            byte[] body = stdout.readNBytes(contentLength);
            if (body.length < contentLength) {
                throw new McpException(McpErrorKind.PROCESS_EXITED,
                        "MCP server \"" + serverName + "\" closed during " + method + " response.");
            }
            return MAPPER.readTree(body);
        } catch (McpException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new McpException(McpErrorKind.PROTOCOL_ERROR,
                    "MCP " + serverName + ": invalid response payload for " + method + ".", exception);
        } catch (RuntimeException exception) {
            throw new McpException(failureKind,
                    "MCP " + serverName + ": failed to read response for " + method + ".", exception);
        }
    }

    /**
     * 从已读到的 header 文本中找出 {@code Content-Length} 行并解析为整数；
     * 找不到时返回 -1，让上层抛 PROTOCOL_ERROR。匹配时大小写不敏感。
     */
    private int contentLength(String headerText) {
        for (String line : headerText.split("\\r\\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        return -1;
    }

    private void ensureRunning() {
        if (process == null || !process.isAlive()) {
            throw new McpException(McpErrorKind.PROCESS_EXITED,
                    "MCP server \"" + serverName + "\" is not running.");
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
