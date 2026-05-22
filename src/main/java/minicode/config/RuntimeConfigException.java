package minicode.config;

/**
 * 配置体系专用的非受检异常，用于在「加载 / 解析 / 校验」运行时配置出错时抛出。
 *
 * <p>典型触发场景：用户没有配置模型名、没有配置鉴权信息、{@code settings.json} 文件读取失败、
 * 或者把 {@code provider} 写成了不被支持的值。这些情况都不是程序员的编码 bug，而是
 * 「外部输入不合规」，因此用 {@link RuntimeException} 系列而不是受检异常 —— 调用方
 * （主要是 {@link RuntimeConfigLoader}）直接抛出，由顶层的 {@code MiniCodeApp.run}
 * 捕获后翻译成命令行错误信息返回非零退出码。</p>
 *
 * <p>设计为 {@code final}：这条异常只在 config 包内被生产，没有「业务子类」需要继承的必要。</p>
 */
public final class RuntimeConfigException extends RuntimeException {
    /**
     * 用一段说明文字构造异常。适用于「配置缺失 / 取值非法」这类自描述场景。
     *
     * @param message 给最终用户看的错误说明（建议直接告知缺哪个字段、应该怎么填）
     */
    public RuntimeConfigException(String message) {
        super(message);
    }

    /**
     * 用一段说明文字 + 底层原因构造异常。适用于「IO 读取失败 / JSON 解析失败」等需要保留
     * 底层堆栈的场景。
     *
     * @param message 上层语义的错误说明
     * @param cause   触发本次失败的底层异常（例如 {@link java.io.IOException}）
     */
    public RuntimeConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
