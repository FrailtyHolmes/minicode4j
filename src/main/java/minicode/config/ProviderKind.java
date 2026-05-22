package minicode.config;

/**
 * 大模型供应商类型枚举：用类型代替字符串来表达「连哪一家 LLM」。
 *
 * <p>整个项目里，只要涉及「该用哪种 {@code ModelAdapter}、是否需要 API Key、走哪条调用路径」
 * 的判断，都通过这个枚举决定。它由 {@link RuntimeConfigLoader} 在启动时从配置（环境变量
 * 或 {@code settings.json}）解析出来，作为 {@link RuntimeConfig#provider()} 的字段固定下来。</p>
 *
 * <p>之所以做成枚举而不是字符串：编译期就能穷举所有分支（如 switch 不写 default 也能让
 * 编辑器提示遗漏），同时避免拼写错误流入运行时。</p>
 */
public enum ProviderKind {
    /** 假供应商，用于离线 / 单元测试 / 本地演示，不会真的发起 HTTP 请求。 */
    MOCK,
    /** 真实 Anthropic 官方 API，或任何「Anthropic 兼容协议」的网关（如自建代理）。 */
    ANTHROPIC;

    /**
     * 把字符串配置值解析成对应的枚举常量。
     *
     * <p>解析规则：</p>
     * <ul>
     *   <li>{@code null} 或空白字符串 → 默认返回 {@link #ANTHROPIC}（与项目「默认连官方」的取舍一致）。</li>
     *   <li>{@code "mock"}（忽略大小写、忽略前后空白）→ {@link #MOCK}。</li>
     *   <li>{@code "anthropic"} 或 {@code "anthropic-compatible"} → {@link #ANTHROPIC}。</li>
     *   <li>其余值 → 抛出 {@link RuntimeConfigException}，让调用方早失败。</li>
     * </ul>
     *
     * @param value 来自环境变量或 {@code settings.json} 的原始字符串
     * @return 对应的枚举常量
     * @throws RuntimeConfigException 当字符串不在上述合法集合内时
     */
    public static ProviderKind parse(String value) {
        if (value == null || value.isBlank()) {
            return ANTHROPIC;
        }
        return switch (value.trim().toLowerCase()) {
            case "mock" -> MOCK;
            case "anthropic", "anthropic-compatible" -> ANTHROPIC;
            default -> throw new RuntimeConfigException("Unsupported provider: " + value);
        };
    }
}
