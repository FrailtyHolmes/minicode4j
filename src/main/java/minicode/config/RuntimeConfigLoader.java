package minicode.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import minicode.mcp.McpServerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 把分散在「环境变量 / 项目级 settings.json / 全局 settings.json / 代码默认值」四处的配置
 * 拼装成一个不可变的 {@link RuntimeConfig}。
 *
 * <p>这是启动期的「配置水合」入口。{@code MiniCodeApp.run} 在装配 {@code ApplicationServices}
 * 之前会调用 {@link #load(Path, Path)}；任何缺失关键字段（如模型名、鉴权信息）都会以
 * {@link RuntimeConfigException} 抛出，让启动早失败。</p>
 *
 * <p>核心设计是 <b>4 层优先级链</b>（从高到低）：</p>
 * <ol>
 *   <li>真实环境变量（如 {@code export MINICODE_MODEL=...}）</li>
 *   <li>cwd 项目级 {@code <cwd>/.minicode/settings.json}（可入 git，团队共享）</li>
 *   <li>home 全局 {@code ~/.minicode-java/settings.json}（个人偏好）</li>
 *   <li>代码内硬编码默认值（兜底）</li>
 * </ol>
 *
 * <p>每个 {@code settings.json} 内部还允许两种结构：顶层字段（如 {@code "model": "..."}）
 * 与 {@code env} 块（如 {@code "env": {"ANTHROPIC_API_KEY": "..."}}），后者用来在没有
 * shell 环境变量时模拟同名变量。具体顺序见 {@link #firstText}。</p>
 *
 * <p>类被声明为 {@code final} 且构造器私有：纯静态工具类，没有实例状态。</p>
 */
public final class RuntimeConfigLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";
    private static final Duration DEFAULT_PROVIDER_TIMEOUT = Duration.ofSeconds(300);

    private RuntimeConfigLoader() {
    }

    /**
     * 加载配置所需的「外部世界输入」：home 目录、cwd 目录、环境变量 Map。
     *
     * <p>把这三样东西做成 record 注入而不是直接读 {@link System#getenv()}，是为了让单元测试
     * 能传入伪造的环境（fake env / 临时目录），保证 {@link RuntimeConfigLoader#load} 100%
     * 可测，与外部状态解耦。</p>
     *
     * <p>构造时会把 {@code home/cwd} 规范化为绝对路径，并对 {@code env} 做防御性拷贝。</p>
     *
     * @param home 全局配置根目录，通常为 {@code ~/.minicode-java}
     * @param cwd  当前工作目录，项目级配置会从其下的 {@code .minicode/settings.json} 读取
     * @param env  环境变量快照（一般来自 {@link System#getenv()}，测试时可注入假数据）
     */
    public record Input(Path home, Path cwd, Map<String, String> env) {
        public Input {
            home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
            cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
            env = Map.copyOf(Objects.requireNonNull(env, "env"));
        }
    }

    /**
     * 便捷重载：自动捕获当前进程的真实环境变量，再委派给 {@link #load(Input)}。
     * 生产代码（{@code MiniCodeApp.run}）走这个入口；测试代码请直接构造 {@link Input}
     * 调用 {@link #load(Input)}。
     *
     * @param home 全局配置根目录
     * @param cwd  当前工作目录
     * @return 校验完成的不可变运行时配置
     * @throws RuntimeConfigException 当模型未配置、鉴权缺失（非 MOCK 模式）、或 settings.json 解析失败
     */
    public static RuntimeConfig load(Path home, Path cwd) {
        return load(new Input(home, cwd, System.getenv()));
    }

    /**
     * 完整的配置加载流水线：
     *
     * <ol>
     *   <li>读两份 {@code settings.json}（home + cwd），缺文件返回空对象，IO 错误抛异常。</li>
     *   <li>按 4 层优先级抽取每个字段：{@link #firstText} / {@link #firstEnvText} /
     *       {@link #firstTopLevelText} 用于不同语义的字段。</li>
     *   <li>合并两份配置里的 {@code mcpServers} 块（cwd 覆盖 home，同字段做字段级 merge）。</li>
     *   <li>校验：{@code model} 必须有；非 MOCK 模式必须至少有 {@code apiKey} 或 {@code authToken}。</li>
     *   <li>所有结果塞进 {@link RuntimeConfig} 返回，并附带一个 {@code sourceSummary} 字符串
     *       记录配置来源路径，便于诊断打印。</li>
     * </ol>
     *
     * @param input 已规范化的外部输入（home / cwd / env）
     * @return 通过校验的不可变运行时配置
     * @throws RuntimeConfigException 当关键字段缺失、不合法或 settings.json 解析失败
     */
    public static RuntimeConfig load(Input input) {
        Objects.requireNonNull(input, "input");
        Path homeSettingsPath = input.home().resolve("settings.json");
        Path cwdSettingsPath = input.cwd().resolve(".minicode").resolve("settings.json");
        JsonNode homeSettings = readSettings(homeSettingsPath);
        JsonNode cwdSettings = readSettings(cwdSettingsPath);
        ProviderKind provider = ProviderKind.parse(firstText(input.env(), homeSettings, cwdSettings,
                "MINICODE_PROVIDER", "provider", "ANTHROPIC"));
        String model = firstNonBlank(
                firstEnvText(input.env(), homeSettings, cwdSettings, "MINICODE_MODEL"),
                firstEnvText(input.env(), homeSettings, cwdSettings, "ANTHROPIC_MODEL"),
                firstTopLevelText(homeSettings, cwdSettings, "model"),
                firstTopLevelText(homeSettings, cwdSettings, "anthropicModel")
        );
        String baseUrl = firstText(input.env(), homeSettings, cwdSettings, "ANTHROPIC_BASE_URL", "baseUrl",
                DEFAULT_ANTHROPIC_BASE_URL);
        Optional<String> apiKey = optionalText(firstText(input.env(), homeSettings, cwdSettings,
                "ANTHROPIC_API_KEY", "apiKey", ""));
        Optional<String> authToken = optionalText(firstText(input.env(), homeSettings, cwdSettings,
                "ANTHROPIC_AUTH_TOKEN", "authToken", ""));
        Optional<Integer> maxOutputTokens = positiveInteger(firstText(input.env(), homeSettings, cwdSettings,
                "MINICODE_MAX_OUTPUT_TOKENS", "maxOutputTokens", ""));
        Optional<Integer> contextWindow = positiveInteger(firstText(input.env(), homeSettings, cwdSettings,
                "MINICODE_CONTEXT_WINDOW", "contextWindow", ""));
        Optional<Integer> maxSteps = positiveInteger(firstTopLevelText(homeSettings, cwdSettings, "maxSteps"));
        Duration providerTimeout = providerTimeout(firstText(input.env(), homeSettings, cwdSettings,
                "MINICODE_PROVIDER_TIMEOUT_SECONDS", "providerTimeoutSeconds", ""));
        Map<String, McpServerConfig> mcpServers = mcpServers(homeSettings, cwdSettings);

        if (model.isBlank()) {
            throw new RuntimeConfigException("No model configured. Set MINICODE_MODEL, ANTHROPIC_MODEL, or home settings.json model.");
        }
        if (provider != ProviderKind.MOCK && apiKey.isEmpty() && authToken.isEmpty()) {
            throw new RuntimeConfigException("No auth configured. Set ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN in env or home settings.json.");
        }

        return new RuntimeConfig(
                provider,
                model,
                baseUrl,
                apiKey,
                authToken,
                maxOutputTokens,
                contextWindow,
                maxSteps,
                providerTimeout,
                "home=" + input.home() + "; cwd=" + input.cwd()
                        + "; homeSettings=" + homeSettingsPath
                        + "; cwdSettings=" + cwdSettingsPath
                        + "; env"
                ,
                mcpServers
        );
    }

    /**
     * 合并两份 {@code settings.json} 的 {@code mcpServers} 段。先 home、后 cwd，
     * 因此 cwd 项目级配置在「同名 server 字段冲突」时覆盖 home 全局配置（与 4 层优先级链一致）。
     */
    private static Map<String, McpServerConfig> mcpServers(JsonNode homeSettings, JsonNode cwdSettings) {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();
        mergeMcpServers(merged, homeSettings == null ? null : homeSettings.get("mcpServers"));
        mergeMcpServers(merged, cwdSettings == null ? null : cwdSettings.get("mcpServers"));
        return Map.copyOf(merged);
    }

    /**
     * 把一份 {@code mcpServers} JSON 对象逐项合并进 {@code target}：同 key 走字段级 merge
     * （见 {@link #mergeMcpServer}），新 key 直接添加。非对象节点静默忽略，不报错。
     */
    private static void mergeMcpServers(Map<String, McpServerConfig> target, JsonNode servers) {
        if (servers == null || !servers.isObject()) {
            return;
        }
        servers.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            if (node == null || !node.isObject()) {
                return;
            }
            McpServerConfig existing = target.get(entry.getKey());
            target.put(entry.getKey(), mergeMcpServer(existing, node));
        });
    }

    /**
     * 单个 MCP Server 的字段级 merge：新值非空白则覆盖、否则保留旧值；{@code env} 做 Map 叠加
     * （新值同 key 覆盖旧值）；{@code initializeTimeout / callTimeout} 暂不从 JSON 读，沿用旧值。
     */
    private static McpServerConfig mergeMcpServer(McpServerConfig existing, JsonNode node) {
        String command = settingsText(node, "command");
        if (command.isBlank() && existing != null) {
            command = existing.command();
        }
        List<String> args = node.has("args") && node.get("args").isArray()
                ? stringList(node.get("args"))
                : existing == null ? List.of() : existing.args();
        Map<String, String> env = new LinkedHashMap<>();
        if (existing != null) {
            env.putAll(existing.env());
        }
        JsonNode envNode = node.get("env");
        if (envNode != null && envNode.isObject()) {
            envNode.fields().forEachRemaining(entry -> env.put(entry.getKey(), entry.getValue().asText("")));
        }
        String cwd = settingsText(node, "cwd");
        if (cwd.isBlank() && existing != null) {
            cwd = existing.cwd().orElse(null);
        }
        boolean enabled = node.has("enabled") ? node.get("enabled").asBoolean(true) : existing == null || existing.enabled();
        Duration initializeTimeout = existing == null ? null : existing.initializeTimeout();
        Duration callTimeout = existing == null ? null : existing.callTimeout();
        return new McpServerConfig(command, args, cwd, env, enabled, initializeTimeout, callTimeout);
    }

    private static List<String> stringList(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(item -> result.add(item.asText("")));
        return List.copyOf(result);
    }

    /**
     * 读 {@code settings.json}：不存在 → 返回空对象节点（让后续 lookup 一律得到空字符串，
     * 而不是到处判 null）；存在但解析失败 → 抛 {@link RuntimeConfigException}（提早暴露
     * 写错的 JSON）。
     */
    private static JsonNode readSettings(Path path) {
        if (!Files.exists(path)) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(path.toFile());
        } catch (IOException exception) {
            throw new RuntimeConfigException("Failed to read settings file: " + path, exception);
        }
    }

    /**
     * 4 层优先级链的核心实现：依次尝试
     * <ol>
     *   <li>真实环境变量 {@code env[envName]}</li>
     *   <li>cwd settings.json 的 {@code env.envName}</li>
     *   <li>home settings.json 的 {@code env.envName}</li>
     *   <li>cwd settings.json 的顶层 {@code settingsName}</li>
     *   <li>home settings.json 的顶层 {@code settingsName}</li>
     *   <li>{@code fallback}</li>
     * </ol>
     * 任一层取到非空白字符串即返回（已 trim），后面的层不再尝试。
     */
    private static String firstText(Map<String, String> env, JsonNode homeSettings, JsonNode cwdSettings,
                                    String envName, String settingsName, String fallback) {
        String envValue = env.get(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String cwdEnvValue = settingsEnvText(cwdSettings, envName);
        if (!cwdEnvValue.isBlank()) {
            return cwdEnvValue;
        }
        String homeEnvValue = settingsEnvText(homeSettings, envName);
        if (!homeEnvValue.isBlank()) {
            return homeEnvValue;
        }

        String cwdTopLevelValue = settingsText(cwdSettings, settingsName);
        if (!cwdTopLevelValue.isBlank()) {
            return cwdTopLevelValue;
        }
        String homeTopLevelValue = settingsText(homeSettings, settingsName);
        if (!homeTopLevelValue.isBlank()) {
            return homeTopLevelValue;
        }
        return fallback;
    }

    /**
     * 只走「环境变量层」的优先级链：真实 env → cwd 的 env 块 → home 的 env 块。
     * 用于那些只通过环境变量名表达的字段（如 {@code MINICODE_MODEL}），不需要顶层 fallback。
     */
    private static String firstEnvText(Map<String, String> env, JsonNode homeSettings, JsonNode cwdSettings,
                                       String envName) {
        String envValue = env.get(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        String cwdEnvValue = settingsEnvText(cwdSettings, envName);
        if (!cwdEnvValue.isBlank()) {
            return cwdEnvValue;
        }
        return settingsEnvText(homeSettings, envName);
    }

    /**
     * 只走「顶层字段层」的优先级链：cwd 顶层 → home 顶层。用于那些只在 JSON 顶层声明、
     * 没有对应环境变量的字段（如 {@code anthropicModel}、{@code maxSteps}）。
     */
    private static String firstTopLevelText(JsonNode homeSettings, JsonNode cwdSettings, String settingsName) {
        String cwdTopLevelValue = settingsText(cwdSettings, settingsName);
        if (!cwdTopLevelValue.isBlank()) {
            return cwdTopLevelValue;
        }
        return settingsText(homeSettings, settingsName);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String settingsEnvText(JsonNode settings, String envName) {
        JsonNode env = settings == null ? null : settings.get("env");
        if (env == null || !env.isObject()) {
            return "";
        }
        return text(env.get(envName));
    }

    private static String settingsText(JsonNode settings, String settingsName) {
        return text(settings == null ? null : settings.get(settingsName));
    }

    private static String text(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        String text = value.asText("");
        return text.isBlank() ? "" : text.trim();
    }

    private static Optional<String> optionalText(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private static Optional<Integer> positiveInteger(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    /**
     * 把字符串形式的「超时秒数」解析为 {@link Duration}：非正数或无法解析时回退到
     * {@link #DEFAULT_PROVIDER_TIMEOUT}（300 秒）。
     */
    private static Duration providerTimeout(String value) {
        Optional<Integer> seconds = positiveInteger(value);
        return seconds.map(Duration::ofSeconds).orElse(DEFAULT_PROVIDER_TIMEOUT);
    }
}
