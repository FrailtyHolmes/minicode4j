package minicode.config;

import minicode.mcp.McpServerConfig;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 运行时配置的不可变快照：把「连哪个供应商、用哪个模型、用谁的 token、各种上限和超时」
 * 一次性凝固成一个对象，供整个应用各模块共用。
 *
 * <p>这个 record 是 {@link RuntimeConfigLoader#load} 的产物 —— Loader 会按
 * 「环境变量 &gt; cwd 项目级 settings.json &gt; home 全局 settings.json &gt; 代码默认值」
 * 的优先级链把所有字段拼装好后塞进来。一旦构造完成，调用方（{@code AnthropicModelAdapter}、
 * {@code McpToolHydrator}、{@code AgentLoop} 等）只读不写，避免并发与状态漂移。</p>
 *
 * <p>之所以选择 record：13 个字段如果用普通 class 至少要写上百行 getter / equals / 构造，
 * record 一行声明就完成；加上 final 语义天然不可变。校验逻辑统一放在 compact 构造器里，
 * 保证「非法状态不可构造」。</p>
 *
 * @param provider         供应商类型，决定走哪条 ModelAdapter 与鉴权策略
 * @param model            模型名，例如 {@code claude-sonnet-4-5}（必填，不能为空白）
 * @param baseUrl          Provider HTTP 接口前缀，例如 {@code https://api.anthropic.com}（必填，不能为空白）
 * @param apiKey           Anthropic 兼容协议的 API Key；与 {@code authToken} 至少存在一个（MOCK 除外）
 * @param authToken        Bearer Token 形式的鉴权（部分网关用），与 {@code apiKey} 二选一
 * @param maxOutputTokens  单次模型调用允许输出的 token 上限；空表示用模型默认
 * @param contextWindow    模型可见的上下文 token 总量；空表示用模型默认
 * @param maxSteps         单个 Turn 内 Agent 循环最多走多少步；空表示无业务上限。必须为正整数
 * @param providerTimeout  调用 Provider HTTP 接口的超时时间。必须为正
 * @param sourceSummary    人类可读的「本次配置从哪些来源拼出来」的描述，用于诊断打印
 * @param mcpServers       已声明的 MCP Server 配置表（key=逻辑名，value=启动参数）；可为空 Map
 */
public record RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                            Optional<String> authToken, Optional<Integer> maxOutputTokens,
                            Optional<Integer> contextWindow, Optional<Integer> maxSteps,
                            Duration providerTimeout, String sourceSummary, Map<String, McpServerConfig> mcpServers) {
    /**
     * 便捷构造器：默认 {@code maxSteps=空}、{@code providerTimeout=300s}、{@code mcpServers=空}。
     * 适用于早期不需要 Agent 步数限制和 MCP 集成的测试 / 简单场景。
     */
    public RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                         Optional<String> authToken, Optional<Integer> maxOutputTokens,
                         Optional<Integer> contextWindow, String sourceSummary) {
        this(provider, model, baseUrl, apiKey, authToken, maxOutputTokens, contextWindow, Optional.empty(),
                Duration.ofSeconds(300), sourceSummary, Map.of());
    }

    /**
     * 便捷构造器：在前一种基础上允许显式指定 {@code providerTimeout}，仍不带 MCP。
     */
    public RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                         Optional<String> authToken, Optional<Integer> maxOutputTokens,
                         Optional<Integer> contextWindow, Duration providerTimeout, String sourceSummary) {
        this(provider, model, baseUrl, apiKey, authToken, maxOutputTokens, contextWindow, Optional.empty(),
                providerTimeout, sourceSummary, Map.of());
    }

    /**
     * 便捷构造器：默认 300s 超时、不限 maxSteps，但允许指定 MCP Server 集合。
     */
    public RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                         Optional<String> authToken, Optional<Integer> maxOutputTokens,
                         Optional<Integer> contextWindow, String sourceSummary,
                         Map<String, McpServerConfig> mcpServers) {
        this(provider, model, baseUrl, apiKey, authToken, maxOutputTokens, contextWindow, Optional.empty(),
                Duration.ofSeconds(300), sourceSummary, mcpServers);
    }

    /**
     * 「权威」构造器（compact 构造器的展开形式）：所有字段都显式给出，并集中执行非空 / 非空白 /
     * 正数等校验，保证一旦构造成功，对象内部一定是合法状态。
     *
     * <p>对 {@code mcpServers} 会执行 {@link Map#copyOf} 防御性拷贝，避免外部修改影响内部状态。</p>
     *
     * @throws NullPointerException     当任何 {@code Optional} 字段为 {@code null}（应使用 {@link Optional#empty()}）
     * @throws IllegalArgumentException 当 {@code model/baseUrl/sourceSummary} 为空白、{@code providerTimeout}
     *                                  非正、或 {@code maxSteps} 存在但非正
     */
    public RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                         Optional<String> authToken, Optional<Integer> maxOutputTokens,
                         Optional<Integer> contextWindow, Optional<Integer> maxSteps,
                         Duration providerTimeout, String sourceSummary, Map<String, McpServerConfig> mcpServers) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.model = requireText(model, "model");
        this.baseUrl = requireText(baseUrl, "baseUrl");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.authToken = Objects.requireNonNull(authToken, "authToken");
        this.maxOutputTokens = Objects.requireNonNull(maxOutputTokens, "maxOutputTokens");
        this.contextWindow = Objects.requireNonNull(contextWindow, "contextWindow");
        this.maxSteps = requirePositiveOptional(maxSteps, "maxSteps");
        this.providerTimeout = requirePositive(providerTimeout, "providerTimeout");
        this.sourceSummary = requireText(sourceSummary, "sourceSummary");
        this.mcpServers = Map.copyOf(Objects.requireNonNull(mcpServers, "mcpServers"));
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Duration actual = Objects.requireNonNull(value, name);
        if (actual.isZero() || actual.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return actual;
    }

    private static Optional<Integer> requirePositiveOptional(Optional<Integer> value, String name) {
        Optional<Integer> actual = Objects.requireNonNull(value, name);
        actual.ifPresent(number -> {
            if (number <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        });
        return actual;
    }
}
