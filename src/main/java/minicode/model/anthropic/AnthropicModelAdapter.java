package minicode.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.config.RuntimeConfig;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.*;
import minicode.core.step.*;
import minicode.model.ProviderThinkingBlock;
import minicode.model.ProviderUsage;
import minicode.model.StepDiagnostics;
import minicode.model.ModelLimits;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolCall;
import minicode.tools.registry.ToolRegistry;

import java.util.*;

/**
 * Anthropic Messages API 的模型适配器实现，负责把内部 {@link ChatMessage} 序列映射成
 * Anthropic 协议的请求 JSON，再把响应反向映射回 {@link AgentStep}。
 *
 * <p>在 Agent 主循环中，{@code AgentLoop} 每一步都通过 {@link ModelAdapter#next(List)} 拿到
 * 「下一步该做什么」。本类就是这条调用的具体落地：对外只暴露一个 {@code next} 方法，
 * 内部封装了请求体构造、HTTP 发送、重试退避、响应解析、Token 计量、错误分类等全部细节。
 * 这种「策略模式 + 单方法接口」的设计让 {@code AgentLoop} 不感知 provider 差异，未来要换
 * 别的兼容 provider 只需新增同接口的实现类。</p>
 *
 * <p>关键设计点：</p>
 * <ul>
 *   <li><b>消息映射</b>：8 种内部 {@code ChatMessage} 子类按 Anthropic 协议规则转换为
 *       {@code text / tool_use / tool_result / thinking} 等 content block；同 role 的
 *       连续 block 会合并到同一条 message 里以满足 Anthropic「user/assistant 严格交替」的约束。</li>
 *   <li><b>重试机制</b>：仅对可恢复错误（429 限流、5xx 服务端错误）进行重试；优先尊重服务端
 *       {@code Retry-After} 响应头，否则使用指数退避 + 确定性抖动，最大次数由 {@code maxRetries} 控制。</li>
 *   <li><b>Token 统计</b>：把 Anthropic 的 {@code input_tokens}、{@code cache_creation_input_tokens}、
 *       {@code cache_read_input_tokens} 三项合并为「输入 Token」，供上下文压缩等决策使用。</li>
 *   <li><b>错误分类</b>：通过 {@link ProviderRequestException} 携带 {@code retryable} 语义化标记，
 *       让上层根据是否可重试做不同处理，避免硬判异常类型。</li>
 * </ul>
 *
 * @see minicode.core.loop.ModelAdapter
 * @see AnthropicTransport
 */
public final class AnthropicModelAdapter implements ModelAdapter {
    /** 复用的 Jackson {@link ObjectMapper} 实例，避免每次请求重复创建（线程安全）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 指数退避的初始延迟（毫秒），从 500ms 起始，每次失败翻倍。 */
    private static final long BASE_RETRY_DELAY_MS = 500L;
    /** 指数退避的最大延迟（毫秒），封顶 8 秒避免等待时间无限增长。 */
    private static final long MAX_RETRY_DELAY_MS = 8_000L;

    private final RuntimeConfig runtimeConfig;
    private final ToolRegistry tools;
    private final AnthropicTransport transport;
    /** 调用方显式指定的最大输出 Token 数；为空时回退到 {@link ModelLimits} 根据模型名解析。 */
    private final Optional<Integer> resolvedMaxOutputTokens;
    /** 单次 {@code next()} 调用允许的最大重试次数（不含首次发送），默认 2 即总共最多 3 次。 */
    private final int maxRetries;
    /** 重试间隔的 sleep 策略，抽象为接口便于在测试中注入零延迟实现。 */
    private final RetryDelayStrategy retryDelayStrategy;

    /**
     * 重试间隔的 sleep 策略接口。
     *
     * <p>把「等待若干毫秒」抽成接口的目的是<b>让单元测试可以传入零延迟实现</b>，
     * 否则每个重试相关的测试都要等真实秒数才能跑完。生产环境用
     * {@link #threadSleep()} 返回的默认实现，基于 {@link Thread#sleep(long)}。</p>
     */
    public interface RetryDelayStrategy {
        /**
         * 阻塞当前线程指定的毫秒数。
         *
         * @param millis 等待时长（毫秒），实现可以选择如何响应中断
         */
        void sleep(long millis);

        /**
         * 返回基于 {@link Thread#sleep(long)} 的默认实现。
         *
         * <p>遇到 {@link InterruptedException} 时会重新设置中断标志位，
         * 并以 {@code retryable=true} 抛出 {@link ProviderRequestException}，
         * 让上层选择是否继续重试或终止。</p>
         *
         * @return 生产环境使用的真实 sleep 策略
         */
        static RetryDelayStrategy threadSleep() {
            return millis -> {
                try {
                    Thread.sleep(Math.max(0L, millis));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ProviderRequestException("Provider retry sleep interrupted",
                            Optional.empty(), true, exception);
                }
            };
        }
    }

    /**
     * 生产环境最常用的便捷构造器，使用默认的 {@link HttpAnthropicTransport} 真实 HTTP 传输。
     *
     * @param runtimeConfig 运行时配置（模型名、baseUrl、鉴权信息等）
     * @param tools         工具注册表，用于把工具 schema 透传给模型
     */
    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools) {
        this(runtimeConfig, tools, new HttpAnthropicTransport());
    }

    /**
     * 允许注入自定义 {@link AnthropicTransport} 的构造器，便于测试时替换为 mock 实现。
     *
     * @param runtimeConfig 运行时配置
     * @param tools         工具注册表
     * @param transport     HTTP 传输层抽象
     */
    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools, AnthropicTransport transport) {
        this(runtimeConfig, tools, transport, 2);
    }

    /**
     * 在自定义 transport 基础上额外指定最大输出 Token 数的构造器。
     *
     * @param runtimeConfig            运行时配置
     * @param tools                    工具注册表
     * @param transport                HTTP 传输层抽象
     * @param resolvedMaxOutputTokens  显式指定的 max_tokens；为空则回退到 {@link ModelLimits}
     */
    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools, AnthropicTransport transport,
                                 Optional<Integer> resolvedMaxOutputTokens) {
        this(runtimeConfig, tools, transport, resolvedMaxOutputTokens, 2);
    }

    /**
     * 指定重试次数的构造器，使用默认的 {@link RetryDelayStrategy#threadSleep()} 策略。
     *
     * @param runtimeConfig 运行时配置
     * @param tools         工具注册表
     * @param transport     HTTP 传输层抽象
     * @param maxRetries    最大重试次数（不含首次发送），需 {@code >= 0}
     */
    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools, AnthropicTransport transport,
                                 int maxRetries) {
        this(runtimeConfig, tools, transport, Optional.empty(), maxRetries, RetryDelayStrategy.threadSleep());
    }

    /**
     * 同时指定 max_tokens 与重试次数的构造器。
     *
     * @param runtimeConfig            运行时配置
     * @param tools                    工具注册表
     * @param transport                HTTP 传输层抽象
     * @param resolvedMaxOutputTokens  显式指定的 max_tokens
     * @param maxRetries               最大重试次数
     */
    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools, AnthropicTransport transport,
                                 Optional<Integer> resolvedMaxOutputTokens, int maxRetries) {
        this(runtimeConfig, tools, transport, resolvedMaxOutputTokens, maxRetries, RetryDelayStrategy.threadSleep());
    }

    /**
     * 测试常用的构造器，可以注入零延迟的 {@link RetryDelayStrategy} 让单测无需真实等待。
     *
     * @param runtimeConfig       运行时配置
     * @param tools               工具注册表
     * @param transport           HTTP 传输层抽象
     * @param maxRetries          最大重试次数
     * @param retryDelayStrategy  重试间隔的 sleep 策略
     */
    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools, AnthropicTransport transport,
                                 int maxRetries, RetryDelayStrategy retryDelayStrategy) {
        this(runtimeConfig, tools, transport, Optional.empty(), maxRetries, retryDelayStrategy);
    }

    /**
     * 全参数的规范构造器（其他构造器最终都会委托到这里）。
     *
     * <p>所有不可空依赖都会被 {@link Objects#requireNonNull} 校验，
     * {@code resolvedMaxOutputTokens} 内的非正数会被过滤掉以避免无效的 max_tokens 透传。
     * {@code maxRetries} 为负数时直接抛 {@link IllegalArgumentException} 阻止误用。</p>
     *
     * @param runtimeConfig            运行时配置（不可空）
     * @param tools                    工具注册表（不可空）
     * @param transport                HTTP 传输层（不可空）
     * @param resolvedMaxOutputTokens  显式 max_tokens；正数才生效
     * @param maxRetries               最大重试次数，需 {@code >= 0}
     * @param retryDelayStrategy       sleep 策略（不可空）
     * @throws IllegalArgumentException 当 {@code maxRetries} 为负数时
     * @throws NullPointerException     当任何不可空参数为 {@code null} 时
     */
    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools, AnthropicTransport transport,
                                 Optional<Integer> resolvedMaxOutputTokens, int maxRetries,
                                 RetryDelayStrategy retryDelayStrategy) {
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.resolvedMaxOutputTokens = Objects.requireNonNull(resolvedMaxOutputTokens, "resolvedMaxOutputTokens")
                .filter(value -> value > 0);
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        this.maxRetries = maxRetries;
        this.retryDelayStrategy = Objects.requireNonNull(retryDelayStrategy, "retryDelayStrategy");
    }

    /**
     * 发起一次模型调用，把 {@link ChatMessage} 历史交给 Anthropic 并返回下一步动作。
     *
     * <p>这是 {@link ModelAdapter} 接口的唯一实现方法，也是 {@code AgentLoop} 主循环每一步
     * 都要调用的入口。整个流程是典型的「4 段式 HTTP 客户端」：</p>
     * <ol>
     *   <li>构造请求体（{@link #requestBody}）；</li>
     *   <li>带重试发送（{@link #sendWithRetries}）；</li>
     *   <li>解析响应体 JSON（{@link #parseBody}）；</li>
     *   <li>检查 HTTP 状态码并把响应映射成 {@link AgentStep}（{@link #parseStep}）。</li>
     * </ol>
     *
     * <p>HTTP 非 2xx 状态会被包装成 {@link ProviderRequestException} 抛出，
     * 同时携带 {@code retryable} 语义化标记给上层判断。</p>
     *
     * @param messages 当前对话的全部消息历史（含 system / user / assistant / tool_result 等）
     * @return 模型的下一步动作：{@link minicode.core.step.AssistantStep} 表示纯文本回复，
     *         {@link minicode.core.step.ToolCallsStep} 表示需要执行工具
     * @throws ProviderRequestException HTTP 错误或响应无法解析时
     */
    @Override
    public AgentStep next(List<ChatMessage> messages) {
        JsonNode requestBody = requestBody(messages);
        AnthropicTransport.Response response = sendWithRetries(requestBody);
        JsonNode data = parseBody(response.body());
        if (!response.ok()) {
            throw new ProviderRequestException(extractErrorMessage(data, response.statusCode()),
                    Optional.of(response.statusCode()), shouldRetryStatus(response.statusCode()));
        }
        return parseStep(data);
    }

    /**
     * 带重试的 HTTP 发送：对 429 限流和 5xx 服务端错误自动重试，最多 {@code maxRetries} 次。
     *
     * <p>重试控制流程：</p>
     * <ul>
     *   <li>首次发送总会执行；之后每次循环判断是否需要重试。</li>
     *   <li>2xx 响应或 4xx 不可重试状态（401/403 等）会立即返回，由调用方处理。</li>
     *   <li>遇到可重试状态码或抛出 {@code retryable=true} 的 {@link ProviderRequestException}
     *       时，按 {@link #retryDelayMs} 计算等待时长后再次尝试。</li>
     *   <li>{@code retryable=false} 的异常立即向上抛出，不重试。</li>
     * </ul>
     *
     * @param requestBody 已经构造好的请求体
     * @return 最终一次的响应（可能仍然是失败响应，由调用方决定如何处理）
     * @throws ProviderRequestException 所有尝试都失败或遇到不可重试错误时
     */
    private AnthropicTransport.Response sendWithRetries(JsonNode requestBody) {
        String url = runtimeConfig.baseUrl().replaceAll("/+$", "") + "/v1/messages";
        Map<String, String> actualHeaders = headers();
        ProviderRequestException lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                AnthropicTransport.Response response = transport.post(url, actualHeaders, requestBody);
                if (response.ok() || !shouldRetryStatus(response.statusCode()) || attempt >= maxRetries) {
                    return response;
                }
                retryDelayStrategy.sleep(retryDelayMs(response, attempt + 1));
            } catch (ProviderRequestException exception) {
                lastException = exception;
                if (!exception.retryable() || attempt >= maxRetries) {
                    throw exception;
                }
                retryDelayStrategy.sleep(retryDelayMs(null, attempt + 1));
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new ProviderRequestException("Provider request failed before receiving a response");
    }

    /**
     * 构造发往 Anthropic 的 HTTP 请求头，包含必填的 {@code anthropic-version} 与鉴权信息。
     *
     * <p>鉴权同时支持两种方式，{@code authToken} 优先于 {@code apiKey}：</p>
     * <ul>
     *   <li>{@code Authorization: Bearer <token>}：来自 {@code ANTHROPIC_AUTH_TOKEN}，
     *       与 LiteLLM、OpenRouter 等代理网关兼容性更好。</li>
     *   <li>{@code x-api-key: <key>}：来自 {@code ANTHROPIC_API_KEY}，Anthropic 私有方案，
     *       仅在 authToken 缺失时启用。</li>
     * </ul>
     *
     * @return 可直接交给 transport 的请求头映射
     */
    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("anthropic-version", "2023-06-01");
        runtimeConfig.authToken().ifPresent(token -> headers.put("Authorization", "Bearer " + token));
        if (runtimeConfig.authToken().isEmpty()) {
            runtimeConfig.apiKey().ifPresent(key -> headers.put("x-api-key", key));
        }
        return headers;
    }

    /**
     * 构造 Anthropic Messages API 的请求体 JSON。
     *
     * <p>Anthropic 协议有几个反直觉的约定：</p>
     * <ol>
     *   <li>{@code system} 是<b>顶级字段</b>，不放在 messages 里。</li>
     *   <li>每条 message 的 {@code content} 必须是<b>数组</b>，元素是 block。</li>
     *   <li>{@code tools} 是简化版 JSON Schema 列表。</li>
     *   <li>{@code max_tokens} 必填；优先使用调用方显式传入的值，否则按模型名解析推荐值。</li>
     * </ol>
     *
     * @param messages 当前对话的全部消息历史
     * @return 可直接序列化为 HTTP body 的 JSON 节点
     */
    private JsonNode requestBody(List<ChatMessage> messages) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", runtimeConfig.model());
        root.put("system", systemText(messages));
        root.set("messages", toProviderMessages(messages));
        root.set("tools", toolSchemas());
        root.put("max_tokens", resolvedMaxOutputTokens.orElseGet(() ->
                ModelLimits.resolveMaxOutputTokens(runtimeConfig.model(), runtimeConfig.maxOutputTokens())));
        return root;
    }

    /**
     * 把消息列表里所有 {@link SystemMessage} 的内容拼成单个字符串，作为请求体的顶层 {@code system} 字段。
     *
     * <p>多条 system 消息之间用空行（{@code \n\n}）分隔，没有 system 消息时返回空串。
     * 这一步是 Anthropic 协议的硬性要求：system prompt 不能放在 messages 数组里。</p>
     *
     * @param messages 当前对话的全部消息历史
     * @return 合并后的系统提示文本，可能为空字符串
     */
    private String systemText(List<ChatMessage> messages) {
        return messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .map(SystemMessage::content)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    /**
     * 把内部 8 种 {@link ChatMessage} 子类转换为 Anthropic 协议的 messages 数组。
     *
     * <p>映射规则：</p>
     * <ul>
     *   <li>{@link SystemMessage}：跳过（已被合并到顶层 {@code system}）。</li>
     *   <li>{@link UserMessage}：{@code user} 角色的 {@code text} block。</li>
     *   <li>{@link ContextSummaryMessage}：以 {@code user} 角色注入「先前对话摘要」前缀。</li>
     *   <li>{@link AssistantThinkingMessage}：原样回传 thinking block（Anthropic 协议要求）。</li>
     *   <li>{@link AssistantProgressMessage}：用 {@code <progress>...</progress>} 包裹后作为 assistant text。</li>
     *   <li>{@link AssistantMessage}：{@code assistant} 角色的 {@code text} block。</li>
     *   <li>{@link AssistantToolCallMessage}：{@code tool_use} block，含 id/name/input。</li>
     *   <li>{@link ToolResultMessage}：{@code tool_result} block，注意<b>必须用 {@code user} 角色包</b>
     *       —— 这是 Anthropic 的协议约定，因为「工具结果是用户提供给 assistant 的信息」。</li>
     * </ul>
     *
     * <p>所有 block 通过 {@link #pushBlock} 写入，会自动合并连续同 role 的消息，
     * 满足 Anthropic「user/assistant 严格交替」的硬性约束。</p>
     *
     * @param messages 当前对话的全部消息历史
     * @return Anthropic 协议要求格式的 messages 数组
     */
    private ArrayNode toProviderMessages(List<ChatMessage> messages) {
        ArrayNode converted = MAPPER.createArrayNode();
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                continue;
            }
            if (message instanceof UserMessage user) {
                pushBlock(converted, "user", textBlock(user.content()));
            } else if (message instanceof ContextSummaryMessage summary) {
                pushBlock(converted, "user", textBlock("[Context Summary from earlier conversation]\n" + summary.content()));
            } else if (message instanceof AssistantThinkingMessage thinking) {
                for (ProviderThinkingBlock block : thinking.blocks()) {
                    pushBlock(converted, "assistant", block.raw());
                }
            } else if (message instanceof AssistantProgressMessage progress) {
                pushBlock(converted, "assistant", textBlock("<progress>\n" + progress.content() + "\n</progress>"));
            } else if (message instanceof AssistantMessage assistant) {
                pushBlock(converted, "assistant", textBlock(assistant.content()));
            } else if (message instanceof AssistantToolCallMessage toolCall) {
                ObjectNode block = MAPPER.createObjectNode();
                block.put("type", "tool_use");
                block.put("id", toolCall.toolUseId());
                block.put("name", toolCall.toolName());
                block.set("input", toolCall.input());
                pushBlock(converted, "assistant", block);
            } else if (message instanceof ToolResultMessage result) {
                ObjectNode block = MAPPER.createObjectNode();
                block.put("type", "tool_result");
                block.put("tool_use_id", result.toolUseId());
                block.put("content", result.content());
                block.put("is_error", result.error());
                pushBlock(converted, "user", block);
            }
        }
        return converted;
    }

    /**
     * 把一个 content block 追加到 messages 数组，必要时合并到上一条同 role 的消息里。
     *
     * <p>Anthropic API 要求 messages 数组里 {@code user} 与 {@code assistant} 严格交替，
     * 不能连续出现两条同 role 的消息。本方法的合并逻辑确保这一约束：</p>
     * <ul>
     *   <li>若数组末尾消息的 role 与新 block 相同，则把新 block append 到该消息的 content 数组里；</li>
     *   <li>否则新建一个 {@code {role, content:[block]}} 消息。</li>
     * </ul>
     *
     * <p>这是适配层最容易踩坑的地方：例如「工具结果 + 续命提示」会产生两个相邻 user 消息，
     * 不合并的话直接被 API 拒绝（HTTP 400）。</p>
     *
     * @param messages 目标 messages 数组（会被原地修改）
     * @param role     新 block 所属的角色（{@code user} 或 {@code assistant}）
     * @param block    要追加的 content block
     */
    private void pushBlock(ArrayNode messages, String role, JsonNode block) {
        if (!messages.isEmpty() && role.equals(messages.get(messages.size() - 1).get("role").asText())) {
            ((ArrayNode) messages.get(messages.size() - 1).get("content")).add(block);
            return;
        }
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", role);
        message.set("content", MAPPER.createArrayNode().add(block));
        messages.add(message);
    }

    private ObjectNode textBlock(String text) {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    /**
     * 把工具注册表里的所有工具序列化为 Anthropic 协议的 {@code tools} 数组。
     *
     * <p>每个工具产出 {@code {name, description, input_schema}} 三段式结构，
     * 模型据此决定何时以及如何调用工具。{@code input_schema} 是简化版 JSON Schema。</p>
     *
     * @return 可直接放入请求体的工具 schema 数组
     */
    private ArrayNode toolSchemas() {
        ArrayNode schemas = MAPPER.createArrayNode();
        for (Tool tool : tools.list()) {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("name", tool.metadata().name());
            schema.put("description", tool.metadata().description());
            schema.set("input_schema", tool.inputSchema());
            schemas.add(schema);
        }
        return schemas;
    }

    /**
     * 把 HTTP 响应体字符串解析为 JSON 节点；空 body 与解析失败都退化为可用的兜底节点。
     *
     * <p>当响应体不是合法 JSON（例如代理网关返回纯文本错误）时，会构造
     * {@code {"error":{"message": <原始 body>}}} 让上层 {@link #extractErrorMessage}
     * 仍能拿到可读的错误信息，避免二次抛 JSON 解析异常掩盖真正的根因。</p>
     *
     * @param body 原始响应体，可能为 {@code null} 或空白
     * @return 已解析的 JSON 节点，永不为 {@code null}
     */
    private JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception exception) {
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.putObject("error").put("message", body.trim());
            return fallback;
        }
    }

    /**
     * 把 Anthropic 响应 JSON 解析为 {@link AgentStep}（{@link minicode.core.step.AssistantStep}
     * 或 {@link minicode.core.step.ToolCallsStep}）。
     *
     * <p>解析逻辑遍历 {@code content} 数组里每一个 block：</p>
     * <ul>
     *   <li>{@code text}：累计到 {@code textParts}，最后用换行拼成完整文本。</li>
     *   <li>{@code tool_use}：转成 {@link ToolCall}，缺失 input 时回退为空对象。</li>
     *   <li>{@code thinking} / {@code redacted_thinking}：原样收集为 {@link ProviderThinkingBlock}，
     *       下一轮请求必须原样回传（Anthropic 协议要求）。</li>
     *   <li>未知类型：记入 {@code ignoredBlockTypes} 但不报错，未来 Anthropic 增加新 block 类型时不会让程序崩。</li>
     * </ul>
     *
     * <p>有 tool_use block 时返回 {@code ToolCallsStep}；否则把文本经
     * {@link #parseAssistantText} 检测 {@code <final>} / {@code <progress>} 标签后返回
     * {@code AssistantStep}。{@link StepDiagnostics} 与 {@link ProviderUsage} 一并附带，
     * 供上层做诊断与上下文压缩决策。</p>
     *
     * @param data 已解析的响应 JSON
     * @return 主循环可执行的下一步动作
     */
    private AgentStep parseStep(JsonNode data) {
        JsonNode content = data.get("content");
        List<ToolCall> toolCalls = new ArrayList<>();
        List<String> textParts = new ArrayList<>();
        List<ProviderThinkingBlock> thinkingBlocks = new ArrayList<>();
        List<String> blockTypes = new ArrayList<>();
        LinkedHashSet<String> ignoredBlockTypes = new LinkedHashSet<>();

        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
            String type = block.path("type").asText("");
            blockTypes.add(type);
            switch (type) {
                case "text" -> textParts.add(block.path("text").asText(""));
                case "tool_use" -> toolCalls.add(new ToolCall(
                        block.path("id").asText(),
                        block.path("name").asText(),
                        block.path("input").isMissingNode() ? MAPPER.createObjectNode() : block.path("input")
                ));
                case "thinking", "redacted_thinking" -> thinkingBlocks.add(new ProviderThinkingBlock(type, block));
                default -> ignoredBlockTypes.add(type);
            }
            }
        }

        ParsedText parsedText = parseAssistantText(String.join("\n", textParts).trim());
        StepDiagnostics diagnostics = new StepDiagnostics(
                optionalText(data.path("stop_reason").asText("")),
                blockTypes,
                List.copyOf(ignoredBlockTypes)
        );
        Optional<ProviderUsage> usage = normalizeUsage(data.get("usage"));
        if (!toolCalls.isEmpty()) {
            return new ToolCallsStep(
                    toolCalls,
                    optionalText(parsedText.content()),
                    parsedText.kind() == AssistantKind.PROGRESS ? ContentKind.PROGRESS : ContentKind.UNSPECIFIED,
                    thinkingBlocks,
                    Optional.of(diagnostics),
                    usage
            );
        }
        return new AssistantStep(parsedText.content(), parsedText.kind(), thinkingBlocks, Optional.of(diagnostics), usage);
    }

    /**
     * 解析 assistant 文本里的 {@code <final>} / {@code <progress>} 标记，识别消息的语义类型。
     *
     * <p>项目约定模型用如下标签包裹特殊语义的输出：</p>
     * <ul>
     *   <li>{@code <final>...</final>} 或 {@code [FINAL]} 前缀：终态回答，标记为 {@link AssistantKind#FINAL}。</li>
     *   <li>{@code <progress>...</progress>} 或 {@code [PROGRESS]} 前缀：中间进度，标记为 {@link AssistantKind#PROGRESS}。</li>
     *   <li>无任何标记：保持 {@link AssistantKind#UNSPECIFIED}，由上层默认处理。</li>
     * </ul>
     *
     * <p>结尾闭合标签使用大小写不敏感的正则匹配，便于容忍模型偶尔的大小写差异。</p>
     *
     * @param raw 模型返回的原始文本（已合并过多个 text block）
     * @return 解析后的 {@link ParsedText}（剥离标签后的内容 + 语义 kind）
     */
    private ParsedText parseAssistantText(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("<final>")) {
            return new ParsedText(trimmed.substring("<final>".length()).replaceAll("(?i)</final>", "").trim(),
                    AssistantKind.FINAL);
        }
        if (trimmed.startsWith("[FINAL]")) {
            return new ParsedText(trimmed.substring("[FINAL]".length()).trim(), AssistantKind.FINAL);
        }
        if (trimmed.startsWith("<progress>")) {
            return new ParsedText(trimmed.substring("<progress>".length()).replaceAll("(?i)</progress>", "").trim(),
                    AssistantKind.PROGRESS);
        }
        if (trimmed.startsWith("[PROGRESS]")) {
            return new ParsedText(trimmed.substring("[PROGRESS]".length()).trim(), AssistantKind.PROGRESS);
        }
        return new ParsedText(trimmed, AssistantKind.UNSPECIFIED);
    }

    /**
     * 把 Anthropic 的 {@code usage} 对象规范化为内部 {@link ProviderUsage}。
     *
     * <p>关键点：Anthropic 把输入 Token 拆成<b>三个字段</b>分别计费：</p>
     * <ul>
     *   <li>{@code input_tokens}：本次请求新增的输入 Token；</li>
     *   <li>{@code cache_creation_input_tokens}：写入 prompt cache 的 Token；</li>
     *   <li>{@code cache_read_input_tokens}：从 prompt cache 命中的 Token（便宜很多）。</li>
     * </ul>
     *
     * <p>从「上下文窗口占用」的视角看三者都算 input，所以这里把它们加起来当总输入。
     * 这一计算对 Ch 6 的 autoCompact 决策至关重要——错误的 Token 估算会导致压缩不触发或过早触发。</p>
     *
     * @param usage 原始 usage JSON 节点，可能为 {@code null}
     * @return 规范化的 usage；当总 Token 数为 0 时返回 {@link Optional#empty()}
     */
    private Optional<ProviderUsage> normalizeUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return Optional.empty();
        }
        int input = usage.path("input_tokens").asInt(0)
                + usage.path("cache_creation_input_tokens").asInt(0)
                + usage.path("cache_read_input_tokens").asInt(0);
        int output = usage.path("output_tokens").asInt(0);
        int total = input + output;
        return total <= 0 ? Optional.empty() : Optional.of(new ProviderUsage(input, output, total));
    }

    private Optional<String> optionalText(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /**
     * 从错误响应里按优先级抽取最可读的错误信息。
     *
     * <p>查找顺序：{@code error.message} → {@code error}（字符串形式）→ {@code message} →
     * 兜底字符串 {@code "Model request failed: <status>"}。这样无论 Anthropic 用哪种错误结构，
     * 用户都能看到尽可能具体的原因。</p>
     *
     * @param data   响应 JSON 节点
     * @param status HTTP 状态码（用于兜底文案）
     * @return 用于异常消息的人类可读字符串
     */
    private String extractErrorMessage(JsonNode data, int status) {
        String nested = data.path("error").path("message").asText("");
        if (!nested.isBlank()) {
            return nested;
        }
        String error = data.path("error").asText("");
        if (!error.isBlank()) {
            return error;
        }
        String message = data.path("message").asText("");
        return message.isBlank() ? "Model request failed: " + status : message;
    }

    /**
     * 判断给定 HTTP 状态码是否值得重试。
     *
     * <p>策略：仅重试 429（限流）和 5xx（服务端错误）。4xx 非限流错误（401 鉴权失败、
     * 403 无权限、400 请求格式错等）属于客户端问题，重试无益，直接抛出让上层处理。</p>
     *
     * @param status HTTP 状态码
     * @return 是否应当对此状态码触发重试
     */
    private boolean shouldRetryStatus(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    /**
     * 计算下一次重试前的等待毫秒数。
     *
     * <p>策略优先级：</p>
     * <ol>
     *   <li>若响应里有 {@code Retry-After} 头，<b>优先听服务器的</b>（{@link #parseRetryAfterMs}）；</li>
     *   <li>否则使用<b>指数退避</b>：500ms → 1s → 2s → 4s → 8s 封顶；</li>
     *   <li>叠加<b>确定性抖动</b>（基于 {@code hash(model, attempt)}），避免多实例集群同步重试。</li>
     * </ol>
     *
     * <p>{@code 1L << (attempt - 1)} 的位移用 {@code Math.min(..., 10)} 限制了最大移位次数，
     * 防止 attempt 很大时发生整数溢出。</p>
     *
     * @param response 失败响应（用于读取 Retry-After 头），异常路径下可能为 {@code null}
     * @param attempt  本次将要进行的尝试次数（从 1 开始计），用于指数退避与抖动种子
     * @return 等待毫秒数，已封顶到 {@link #MAX_RETRY_DELAY_MS}
     */
    private long retryDelayMs(AnthropicTransport.Response response, int attempt) {
        Long retryAfter = parseRetryAfterMs(response);
        if (retryAfter != null) {
            return retryAfter;
        }
        long base = Math.min(BASE_RETRY_DELAY_MS * (1L << Math.max(0, Math.min(attempt - 1, 10))),
                MAX_RETRY_DELAY_MS);
        long jitter = Math.floorMod(Objects.hash(runtimeConfig.model(), attempt), Math.max(1L, base / 4L + 1L));
        return Math.min(MAX_RETRY_DELAY_MS, base + jitter);
    }

    /**
     * 解析 HTTP {@code Retry-After} 响应头并换算成毫秒数。
     *
     * <p>RFC 7231 允许两种格式，本方法都支持：</p>
     * <ul>
     *   <li><b>delta-seconds</b>：纯数字（含小数），表示等待秒数；</li>
     *   <li><b>HTTP-date</b>：RFC 1123 格式时间戳，表示「在该时间点之后再来」，
     *       这里换算成距当前的毫秒差，负值会被裁剪为 0。</li>
     * </ul>
     *
     * <p>响应头查找同时兼容大小写形式（{@code retry-after} 与 {@code Retry-After}），
     * 不同 transport 实现可能保留不同大小写。</p>
     *
     * @param response 响应对象，可能为 {@code null}（异常路径）
     * @return 服务器要求的等待毫秒数；无可用值返回 {@code null}
     */
    private Long parseRetryAfterMs(AnthropicTransport.Response response) {
        if (response == null) {
            return null;
        }
        List<String> values = response.headers().get("retry-after");
        if (values == null) {
            values = response.headers().get("Retry-After");
        }
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.getFirst();
        try {
            double seconds = Double.parseDouble(value);
            if (seconds >= 0.0d) {
                return Math.round(seconds * 1000.0d);
            }
        } catch (NumberFormatException ignored) {
            // Fall through to HTTP date parsing.
        }
        try {
            long epochMillis = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli();
            return Math.max(0L, epochMillis - System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * {@link #parseAssistantText} 的输出载体，承载剥离 {@code <final>}/{@code <progress>}
     * 标签后的纯文本及其语义类型。
     *
     * @param content 已剥离标签、修剪空白的正文文本
     * @param kind    文本的语义类型（FINAL / PROGRESS / UNSPECIFIED）
     */
    private record ParsedText(String content, AssistantKind kind) {
    }
}
