package minicode.app;

import minicode.config.RuntimeConfig;
import minicode.config.RuntimeConfigException;
import minicode.config.RuntimeConfigLoader;
import minicode.core.event.AgentEventSink;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.session.service.SessionService;
import minicode.session.store.SessionMetadata;
import minicode.session.store.SessionStore;
import minicode.tui.ConsolePermissionPromptHandler;
import minicode.tui.MiniTui;
import minicode.tui.MiniTuiEventSink;
import minicode.tui.RendererTuiBridge;
import minicode.tui.RendererTuiShell;
import minicode.tui.input.JLineTuiInput;
import minicode.tui.terminal.JLineTerminalScreen;
import minicode.tui.terminal.TerminalScreen;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.List;

/**
 * MiniCode CLI 程序入口，负责解析命令行、装配依赖并把控制权交给 TUI 主循环。
 *
 * <p>设计上 {@link #main} 只做参数解构，立即把 stdin/stdout/stderr/env 等 I/O 资源
 * 显式传给 {@link #run} 静态方法——这样 {@code run} 在单元测试里可以注入假 IO，
 * 不依赖真实 {@code System.in/out}。</p>
 *
 * <p>主流程：参数解析 → 早返回特殊分支（help/version/snake/session 子命令）→
 * 加载 {@link RuntimeConfig} → {@link #runWithServices} 装配 {@link ApplicationServices}
 * → 启动 {@link MiniTui} 或 {@link RendererTuiShell} 进入交互循环。</p>
 *
 * <p>本项目刻意不引入 Spring 等 DI 框架，所有装配逻辑集中在 {@link ApplicationServices#create}
 * 中以保证冷启动 100ms 内、依赖关系一目了然。详见 docs/tutorial/ch01。</p>
 */
public final class MiniCodeApp {
    /** 程序版本号；优先取 jar 包 manifest 中的 Implementation-Version，缺省回退到开发期 SNAPSHOT 字符串。 */
    private static final String VERSION = MiniCodeApp.class.getPackage().getImplementationVersion() == null
            ? "0.1.0-SNAPSHOT"
            : MiniCodeApp.class.getPackage().getImplementationVersion();

    private MiniCodeApp() {
    }

    /**
     * JVM 标准入口。
     *
     * <p>本方法只做一件事：把 {@code System.in/out/err}、{@code user.home}、{@code user.dir}、
     * {@code System.getenv()} 等"全局环境"显式打包传给 {@link #run}。这样核心逻辑 {@code run}
     * 可以在测试中注入假 I/O 进行黑盒验证，无需重定向 {@code System.out}。</p>
     *
     * <p>退出码非 0 时调用 {@link System#exit} 让脚本可感知，0 时正常返回避免污染调用方进程。</p>
     */
    public static void main(String[] args) {
        int exitCode = run(
                args,
                Path.of(System.getProperty("user.home"), ".minicode-java"),
                Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
                System.in,
                System.out,
                System.err,
                System.getenv()
        );
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * 标准启动入口（外部可重入），使用默认彩蛋启动器。
     *
     * @param home  MiniCode 全局配置/数据目录（通常是 {@code ~/.minicode-java}）
     * @param cwd   工作目录，{@code --cwd} 覆盖时以参数为准
     * @param env   环境变量快照，用于配置加载和敏感字段脱敏
     * @return 进程退出码，0 表示成功；2 表示配置错误；1 表示其它运行时异常
     */
    public static int run(String[] args, Path home, Path cwd, InputStream input, OutputStream output,
                          OutputStream error, Map<String, String> env) {
        return run(args, home, cwd, input, output, error, env, MiniCodeApp::launchSnakeGame);
    }

    /**
     * 测试友好的启动入口：允许用 {@code snakeLauncher} 替换彩蛋逻辑（例如测试里塞一个空实现），
     * 避免单测启动真实的子进程。
     *
     * <p>实现采用「早返回 + 主路径」结构：先处理 help/version/snake/session 等特殊命令，
     * 这些都不需要装配模型；只有走到主路径才会加载 {@link RuntimeConfig} 并真正构造
     * {@link ApplicationServices}。</p>
     */
    public static int run(String[] args, Path home, Path cwd, InputStream input, OutputStream output,
                          OutputStream error, Map<String, String> env, Runnable snakeLauncher) {
        PrintWriter err = new PrintWriter(error, true, StandardCharsets.UTF_8);
        AppArgs appArgs;
        Path actualCwd;
        try {
            appArgs = AppArgs.parse(args);
            actualCwd = resolveActualCwd(cwd, appArgs);
        } catch (RuntimeException exception) {
            err.println("Runtime error: " + safeMessage(exception, env));
            return 1;
        }
        if (appArgs.snake()) {
            PrintWriter out = new PrintWriter(output, true, StandardCharsets.UTF_8);
            out.println("Starting SnakeGame...");
            try {
                snakeLauncher.run();
                return 0;
            } catch (RuntimeException exception) {
                err.println("Runtime error: " + safeMessage(exception, env));
                return 1;
            }
        }
        if (appArgs.help()) {
            new PrintWriter(output, true, StandardCharsets.UTF_8).println(usage());
            return 0;
        }
        if (appArgs.version()) {
            new PrintWriter(output, true, StandardCharsets.UTF_8).println("minicode " + VERSION);
            return 0;
        }
        if (appArgs.sessionCommand()) {
            return runWithServices(args, home, cwd, input, output, error,
                    (serviceHome, serviceCwd, sessionId, eventSink, permissionPromptHandler) -> {
                        throw new IllegalStateException("Session management command must not start application services.");
                    },
                    env);
        }
        RuntimeConfig runtimeConfig;
        try {
            runtimeConfig = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, actualCwd, env));
        } catch (RuntimeConfigException exception) {
            err.println("Configuration error: " + exception.getMessage());
            err.println("Configure MINICODE_PROVIDER, ANTHROPIC_MODEL or MINICODE_MODEL, and ANTHROPIC_AUTH_TOKEN or ANTHROPIC_API_KEY.");
            err.println("Mock mode is only used when MINICODE_PROVIDER=mock is explicitly set.");
            return 2;
        }
        return runWithServices(args, home, cwd, input, output, error,
                (serviceHome, serviceCwd, sessionId, eventSink, permissionPromptHandler) -> ApplicationServices.create(
                        serviceHome,
                        serviceCwd,
                        sessionId,
                        runtimeConfig,
                        eventSink,
                        permissionPromptHandler
                ),
                env);
    }

    /**
     * 测试钩子：跳过配置加载，直接由调用方提供 {@link ServicesFactory} 装配 {@link ApplicationServices}。
     *
     * <p>主流程的 {@link #run} 内部也会调用本方法，区别仅在于 servicesFactory 一个用真实
     * Anthropic 适配器、一个用 Mock。集成测试可借此注入桩对象避免触发真实网络。</p>
     */
    public static int runWithServices(String[] args, Path home, Path cwd, InputStream input, OutputStream output,
                                      OutputStream error, ServicesFactory servicesFactory) {
        return runWithServices(args, home, cwd, input, output, error, servicesFactory, Map.of());
    }

    private static int runWithServices(String[] args, Path home, Path cwd, InputStream input, OutputStream output,
                                       OutputStream error, ServicesFactory servicesFactory, Map<String, String> env) {
        PrintWriter err = new PrintWriter(error, true, StandardCharsets.UTF_8);
        try {
            runWithServicesUnchecked(args, home, cwd, input, output, error, servicesFactory);
            return 0;
        } catch (RuntimeException exception) {
            err.println("Runtime error: " + safeMessage(exception, env));
            return 1;
        }
    }

    /**
     * 真正的装配 + 主循环逻辑，未捕获异常版（外层 {@link #runWithServices} 负责把异常翻译成 exit code）。
     *
     * <p>步骤：解析 args → 决定 sessionId（resume/fork/新建）→ 选择 TUI 模式（JLine 终端 vs line mode）
     * → 通过工厂创建 {@link ApplicationServices} → 跑主循环 → 在 finally 里按相反顺序释放
     * terminal screen / services / terminal 资源。</p>
     */
    private static void runWithServicesUnchecked(String[] args, Path home, Path cwd, InputStream input,
                                                 OutputStream output, OutputStream error, ServicesFactory servicesFactory) {
        PrintWriter out = new PrintWriter(output, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(error, true, StandardCharsets.UTF_8);
        AppArgs appArgs = AppArgs.parse(args);
        Path actualCwd = resolveActualCwd(cwd, appArgs);
        Path actualHome = home.toAbsolutePath().normalize();
        SessionService sessionService = new SessionService(new SessionStore(actualHome.resolve("sessions")));
        if (appArgs.sessionCommand()) {
            handleSessionCommand(appArgs, sessionService, actualCwd.toString(), out);
            return;
        }
        String sessionId = appArgs.sessionId().orElseGet(() -> UUID.randomUUID().toString());
        if (appArgs.resumeSessionId() != null) {
            sessionService.requireResumable(actualCwd.toString(), appArgs.resumeSessionId());
            sessionId = appArgs.resumeSessionId();
        }
        if (appArgs.forkSessionId() != null) {
            sessionId = sessionService.fork(actualCwd.toString(), appArgs.forkSessionId());
            out.println("Forked session " + appArgs.forkSessionId() + " -> " + sessionId);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        Terminal terminal = createTerminal(input, output, err);
        RendererTuiBridge rendererBridge = terminal == null ? null : new RendererTuiBridge();
        PermissionPromptHandler permissionPromptHandler = rendererBridge == null
                ? new ConsolePermissionPromptHandler(reader, output)
                : rendererBridge;

        ApplicationServices services = servicesFactory.create(
                actualHome,
                actualCwd,
                sessionId,
                rendererBridge == null ? new MiniTuiEventSink(output, event -> {
                }) : rendererBridge,
                permissionPromptHandler
        );
        TerminalScreen terminalScreen = terminal == null ? null : new JLineTerminalScreen(terminal);
        try {
            if (terminal == null) {
                new MiniTui(services, reader, output,
                        effectiveMaxSteps(java.util.Optional.ofNullable(appArgs.maxStepsOverride()),
                                services.runtimeConfig())).runLoop();
            } else {
                new RendererTuiShell(services, new JLineTuiInput(terminal), terminalScreen,
                        effectiveMaxSteps(java.util.Optional.ofNullable(appArgs.maxStepsOverride()),
                                services.runtimeConfig()), rendererBridge).runLoop();
            }
        } finally {
            if (terminalScreen != null) {
                terminalScreen.close();
            }
            services.close();
            if (terminal != null) {
                try {
                    terminal.close();
                } catch (java.io.IOException ignored) {
                    // Closing the terminal is best-effort during app shutdown.
                }
            }
        }
    }

    /**
     * 尝试构造一个 JLine 真终端。若返回 {@code null} 表示当前环境不支持（如 dumb terminal、
     * IDE 控制台或重定向 IO），调用方应回退到行模式 {@link MiniTui}。
     *
     * <p>异常被吞掉只输出一行警告，避免因 TUI 不可用导致 CLI 整个挂掉。</p>
     */
    private static Terminal createTerminal(InputStream input, OutputStream output, PrintWriter err) {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .system(true)
                    .streams(input, output)
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            return terminal.getType() == null || "dumb".equalsIgnoreCase(terminal.getType()) ? null : terminal;
        } catch (RuntimeException | java.io.IOException exception) {
            err.println("TUI fallback: JLine terminal unavailable, using line mode. " + exception.getMessage());
            return null;
        }
    }

    /**
     * 决定本次运行真正使用的工作目录：优先使用 {@code --cwd} 参数，否则使用调用方传入的 cwd。
     * 若 {@code --cwd} 指向不存在或非目录的路径，抛 {@link IllegalArgumentException}。
     */
    private static Path resolveActualCwd(Path cwd, AppArgs appArgs) {
        Path actualCwd = appArgs.cwdOverride() != null
                ? appArgs.cwdOverride().toAbsolutePath().normalize()
                : cwd.toAbsolutePath().normalize();
        if (appArgs.cwdOverride() != null && (!Files.exists(actualCwd) || !Files.isDirectory(actualCwd))) {
            throw new IllegalArgumentException("--cwd must be an existing directory: " + actualCwd);
        }
        return actualCwd;
    }

    /**
     * 分发 {@code session list} / {@code session rename <id> <title>} 子命令。
     *
     * <p>这些命令不需要装配模型/工具，只读写 {@link SessionService}，因此走独立分支。</p>
     */
    private static void handleSessionCommand(AppArgs args, SessionService sessionService, String cwd, PrintWriter out) {
        List<String> command = args.remaining();
        String subcommand = command.size() > 1 ? command.get(1) : "";
        switch (subcommand) {
            case "list" -> {
                List<SessionMetadata> sessions = sessionService.list(cwd);
                if (sessions.isEmpty()) {
                    out.println("No sessions for cwd: " + cwd);
                    return;
                }
                out.println(formatSessionListHeader());
                sessions.forEach(session -> out.println(formatSessionListRow(session)));
            }
            case "rename" -> {
                if (command.size() < 4) {
                    throw new IllegalArgumentException("Usage: session rename <id> <title>");
                }
                String sessionId = command.get(2);
                String title = String.join(" ", command.subList(3, command.size()));
                sessionService.rename(cwd, sessionId, title);
                out.println("Renamed session " + sessionId + " to " + title.trim());
            }
            default -> throw new IllegalArgumentException("Usage: session list | session rename <id> <title>");
        }
    }

    private static String usage() {
        return """
                Usage:
                  minicode
                  minicode --cwd <path>
                  minicode --resume <id>
                  minicode --fork <id>
                  minicode session list
                  minicode session rename <id> <title>
                  minicode --max-steps <n>
                  minicode --version
                  minicode --help

                Options:
                  --cwd <path>       Use an explicit workspace directory.
                  --resume <id>      Resume a session for the current workspace.
                  --fork <id>        Fork a session for the current workspace.
                  --max-steps <n>    Limit one agent turn to 1..100 model/tool steps.
                  --version          Print version and exit.
                  --help             Print this help and exit.
                """;
    }

    private static String formatSessionListHeader() {
        return String.format(java.util.Locale.ROOT, "%-36s  %-40s  %-30s  %s",
                "SESSION ID", "TITLE", "UPDATED", "CWD");
    }

    private static String formatSessionListRow(SessionMetadata session) {
        return String.format(java.util.Locale.ROOT, "%-36s  %-40s  %-30s  %s",
                truncate(session.sessionId(), 36),
                truncate(session.title().orElse("(untitled)"), 40),
                truncate(session.updatedAt().toString(), 30),
                session.cwd());
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 3) {
            return value.substring(0, maxChars);
        }
        return value.substring(0, maxChars - 3) + "...";
    }

    /**
     * 把异常消息脱敏后返回：从消息文本里把 {@code ANTHROPIC_AUTH_TOKEN}/{@code ANTHROPIC_API_KEY}
     * 的实际值替换成 {@code <redacted>}，避免在错误日志里泄露密钥。
     */
    private static String safeMessage(RuntimeException exception, Map<String, String> env) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        for (String key : java.util.List.of("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY")) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                message = message.replace(value, "<redacted>");
            }
        }
        return message;
    }

    /**
     * 计算一次 Turn 内允许的最大步数，按优先级返回：CLI {@code --max-steps} &gt; 配置文件
     * {@code maxSteps} &gt; 默认值 {@link MiniTui#DEFAULT_MAX_STEPS}。
     */
    static int effectiveMaxSteps(java.util.Optional<Integer> cliMaxSteps,
                                 java.util.Optional<RuntimeConfig> runtimeConfig) {
        return cliMaxSteps
                .or(() -> runtimeConfig.flatMap(RuntimeConfig::maxSteps))
                .orElse(MiniTui.DEFAULT_MAX_STEPS);
    }

    /**
     * 启动彩蛋贪吃蛇：以同一个 Java 可执行文件 fork 一个子进程跑 {@code snake.jar}，
     * 共享当前 stdin/stdout/stderr。子进程退出码非 0 时抛异常，被外层翻译成 CLI exit 1。
     */
    private static void launchSnakeGame() {
        Path jar = snakeJarPath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        ProcessBuilder processBuilder = new ProcessBuilder(javaExecutable, "-jar", jar.toString());
        processBuilder.inheritIO();
        try {
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("SnakeGame exited with code " + exitCode);
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to start SnakeGame: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for SnakeGame.", exception);
        }
    }

    /**
     * 在一组候选位置里寻找 {@code snake.jar}。
     *
     * <p>查找顺序：{@code -Dminicode.snake.jar=...} 系统属性 → 当前 jar 同目录及父目录的
     * {@code easter-eggs/snake/snake.jar} → 仓库布局下的 {@code target/dist/...} → 当前工作目录。
     * 任何一处命中即返回；全部缺失抛 {@link IllegalStateException}。</p>
     */
    private static Path snakeJarPath() {
        java.util.ArrayList<Path> candidates = new java.util.ArrayList<>();
        String override = System.getProperty("minicode.snake.jar");
        if (override != null && !override.isBlank()) {
            candidates.add(Path.of(override));
        }
        codeSourcePath().ifPresent(codePath -> {
            Path parent = Files.isRegularFile(codePath) ? codePath.getParent() : codePath;
            if (parent != null) {
                candidates.add(parent.resolve("easter-eggs").resolve("snake").resolve("snake.jar"));
                candidates.add(parent.resolve("..").resolve("easter-eggs").resolve("snake").resolve("snake.jar"));
                candidates.add(parent.resolve("..").resolve("..").resolve("easter-eggs").resolve("snake").resolve("snake.jar"));
                candidates.add(parent.resolve("dist").resolve("minicode").resolve("easter-eggs").resolve("snake").resolve("snake.jar"));
            }
        });
        candidates.add(Path.of("easter-eggs", "snake", "snake.jar"));
        candidates.add(Path.of("target", "dist", "minicode", "easter-eggs", "snake", "snake.jar"));

        for (Path candidate : candidates) {
            Path actual = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(actual)) {
                return actual;
            }
        }
        throw new IllegalStateException("SnakeGame jar not found. Expected easter-eggs/snake/snake.jar near MiniCode.");
    }

    private static java.util.Optional<Path> codeSourcePath() {
        try {
            return java.util.Optional.of(Path.of(MiniCodeApp.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException | RuntimeException exception) {
            return java.util.Optional.empty();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * 装配 {@link ApplicationServices} 的工厂抽象。
     *
     * <p>把"如何创建 services"和"何时创建 services"解耦：主流程（真实运行）传入会读配置、
     * 连真实模型的实现；测试可以传入返回 mock 适配器的实现，无需启动真实 HTTP 客户端。</p>
     */
    @FunctionalInterface
    public interface ServicesFactory {
        /**
         * @param home                     全局数据目录
         * @param cwd                      工作目录
         * @param sessionId                本次会话 ID
         * @param eventSink                Agent 事件下游消费者（一般是 TUI 渲染器）
         * @param permissionPromptHandler  权限审批弹窗处理器
         */
        ApplicationServices create(Path home, Path cwd, String sessionId, AgentEventSink eventSink,
                                   PermissionPromptHandler permissionPromptHandler);
    }

    /**
     * 解析后的 CLI 参数包，{@link #parse} 是其唯一构造途径。
     *
     * <p>采用「消耗式解析」：每识别一个 flag/option 就从 {@code remaining} 里剔除，
     * 最后剩下的就是位置参数（如 sessionId 或 {@code session list}）。</p>
     *
     * @param resumeSessionId  {@code --resume <id>} 的值，要求该 session 已存在且属于当前 cwd
     * @param forkSessionId    {@code --fork <id>} 的值，会基于源 session 复制出新 session
     * @param cwdOverride      {@code --cwd <path>} 指定的工作目录，{@code null} 表示用调用方传入的 cwd
     * @param help             是否带了 {@code --help/-h}
     * @param version          是否带了 {@code --version}
     * @param snake            是否带了 {@code --snake} 彩蛋开关
     * @param maxStepsOverride {@code --max-steps <n>} 的值，{@code null} 表示未指定
     * @param remaining        剥离已识别参数后剩余的位置参数列表（首个可能是 session 子命令或 sessionId）
     */
    private record AppArgs(String resumeSessionId, String forkSessionId, Path cwdOverride,
                           boolean help, boolean version, boolean snake,
                           Integer maxStepsOverride, List<String> remaining) {
        private static final int DEFAULT_MAX_STEPS = MiniTui.DEFAULT_MAX_STEPS;
        private static final int MAX_MAX_STEPS = 100;

        private static AppArgs parse(String[] args) {
            List<String> remaining = new java.util.ArrayList<>(Arrays.asList(args));
            boolean help = takeFlag(remaining, "--help") || takeFlag(remaining, "-h");
            boolean version = takeFlag(remaining, "--version");
            boolean snake = takeFlag(remaining, "--snake");
            String cwd = takeOption(remaining, "--cwd");
            String maxSteps = takeOption(remaining, "--max-steps");
            String resume = takeOption(remaining, "--resume");
            String fork = takeOption(remaining, "--fork");
            if (resume != null && fork != null) {
                throw new IllegalArgumentException("Use either --resume or --fork, not both.");
            }
            return new AppArgs(resume, fork, cwd == null ? null : Path.of(cwd), help, version, snake,
                    maxSteps == null ? null : parseMaxSteps(maxSteps),
                    List.copyOf(remaining));
        }

        private boolean sessionCommand() {
            return !remaining.isEmpty() && "session".equals(remaining.getFirst());
        }

        private java.util.Optional<String> sessionId() {
            if (remaining.isEmpty()) {
                return java.util.Optional.empty();
            }
            String first = remaining.getFirst();
            if (first.startsWith("-")) {
                throw new IllegalArgumentException("Unknown argument: " + first);
            }
            return java.util.Optional.of(first);
        }

        private static String takeOption(List<String> args, String name) {
            int index = args.indexOf(name);
            if (index < 0) {
                return null;
            }
            if (index + 1 >= args.size()) {
                throw new IllegalArgumentException("Missing value for " + name);
            }
            String value = args.get(index + 1);
            args.remove(index + 1);
            args.remove(index);
            return value;
        }

        private static boolean takeFlag(List<String> args, String name) {
            boolean found = false;
            while (args.remove(name)) {
                found = true;
            }
            return found;
        }

        private static int parseMaxSteps(String value) {
            int parsed;
            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("--max-steps must be between 1 and " + MAX_MAX_STEPS);
            }
            if (parsed < 1 || parsed > MAX_MAX_STEPS) {
                throw new IllegalArgumentException("--max-steps must be between 1 and " + MAX_MAX_STEPS);
            }
            return parsed;
        }
    }
}
