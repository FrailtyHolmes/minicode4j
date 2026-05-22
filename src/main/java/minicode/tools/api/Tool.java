package minicode.tools.api;

import com.fasterxml.jackson.databind.JsonNode;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.result.ToolResult;

/**
 * 所有工具（Tool）的统一契约：把「LLM 想调用的一段 JSON」变成一次可校验、可执行、可观测的动作。
 *
 * <p>Agent 接收到 LLM 的工具调用请求后，会通过 {@link minicode.tools.registry.ToolRegistry}
 * 找到对应的实现类，依次完成：暴露元数据 → 给出 JSON Schema → 校验入参 → 真正执行。
 * 这个接口就是这条链路上每一站的契约——任何想接入 Agent 的工具，都必须实现它。
 *
 * <p>设计上把「描述自己」「校验入参」「执行」三件事拆开，是为了：
 * 一方面让 LLM 在调用前就能拿到完整 schema 自我修正参数；
 * 另一方面让 ToolRegistry 在真正执行之前先把脏数据挡在门外，
 * 保证 {@link #run} 拿到的永远是规范化后的输入。
 *
 * <p>实现约定（非常重要）：{@link #run} 内部应该把所有可预期异常 catch 后转成
 * {@link ToolResult#error(String)}，<b>不要让异常逃出方法</b>。
 * ToolRegistry 虽然有兜底 try-catch，但那是最后一道防线，不是免责理由。
 */
public interface Tool {
    /**
     * 返回工具的元数据：名字、描述、能力分类、状态等。
     *
     * <p>一般用 {@code private static final} 常量在类加载时构造一次（典型单例），
     * 因为它会被 {@code SystemPromptBuilder} 反复读取拼系统提示，
     * 且内容在运行期不变。例外是 MCP 这类需要从远端拉取 schema 的动态工具。
     *
     * @return 不可为 {@code null} 的工具元数据
     */
    ToolMetadata metadata();

    /**
     * 返回这个工具的入参 JSON Schema。
     *
     * <p>虽然 {@link #metadata()} 里已经包含了 schema，但这里单独再开一个方法，
     * 是为了让「只关心 schema」的调用方（例如发请求给 LLM 的适配层）
     * 不必把整坨 metadata 都拖出来。属于细粒度暴露，避免无用数据传输。
     *
     * @return 描述本工具入参结构的 JSON Schema 节点
     */
    JsonNode inputSchema();

    /**
     * 校验 + 规范化 LLM 给的入参。
     *
     * <p>这一步在 {@link #run} 之前由 ToolRegistry 调用。LLM 输出的 JSON 经常不规范
     * （把数字写成字符串 "42"、字段前后带空格、缺可选字段等），
     * 所以校验时顺手做规范化，把「洗干净」的 {@code normalizedInput} 通过
     * {@link ValidationResult#normalizedInput()} 带回去给下游。
     *
     * <p>典型实现用 {@code ToolInputValidation} 链式 DSL，声明字段是否必需、
     * 类型、范围、跨字段约束等，比手写 if 判断短得多。
     *
     * <p>实现可以抛 {@link RuntimeException}：ToolRegistry 会 catch 后转成
     * {@link ToolResult#error(String)}，不会传染给 AgentLoop。
     *
     * @param input LLM 原始给的输入 JSON 节点（可能是 {@code null} 或非 object）
     * @return 校验结果；通过时携带规范化后的 input，失败时携带错误列表
     */
    ValidationResult validateInput(JsonNode input);

    /**
     * 工具的真正执行逻辑——读文件、跑命令、调远端 API 等等都在这里。
     *
     * <p>调用契约：传进来的 {@code normalizedInput} 是 {@link #validateInput} 返回的规范化结果，
     * 业务代码可以直接用，不必再做 defensive coding。
     *
     * <p>异常处理约定：实现内部应该把 IO 错、参数错、外部依赖错全部 catch，
     * 转成 {@link ToolResult#error(String)} 返回。<b>不要把异常抛给 ToolRegistry。</b>
     * 唯一例外是取消令牌的 {@code CancellationRequestedException}——这个必须透传，
     * 让 AgentLoop 感知到取消信号。
     *
     * @param normalizedInput 已经过 {@link #validateInput} 校验和规范化的入参
     * @param toolContext     调用上下文，含 cwd、sessionId、取消令牌等运行时信息
     * @return 工具执行结果（成功 / 失败 / 等待用户），<b>不允许返回 {@code null}</b>
     */
    ToolResult run(JsonNode normalizedInput, ToolContext toolContext);
}
