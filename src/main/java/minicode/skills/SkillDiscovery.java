package minicode.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 启动期扫描磁盘上的 Skill 目录，把每一个 {@code SKILL.md} 解析成 {@link LoadedSkill}。
 *
 * <p>"Skill" 是给 Agent 的一段操作手册（markdown），与 Tool 不同：Tool 是动作、有副作用，
 * Skill 是知识、只是文本。Agent 启动时只读取 SKILL.md 的描述（YAML front matter 的
 * {@code description} 或正文第一段），等 LLM 真正决定加载某个 skill 再读完整内容——
 * 这就是"两阶段加载"，让大量 skill 不会撑爆 system prompt。
 *
 * <p>扫描遵循固定的优先级顺序（{@link SkillSource} 的枚举顺序），同名 skill 以**先发现的为准**：
 * <ol>
 *   <li>项目级 Java skill（{@code <cwd>/.minicode/skills}）</li>
 *   <li>用户级 Java skill（{@code <appHome>/skills}）</li>
 *   <li>项目级 / 用户级 TS 兼容路径（{@code .mini-code/skills}）</li>
 *   <li>Claude Code 兼容路径（{@code .claude/skills}）</li>
 * </ol>
 * 这样"项目内自定义"永远盖过"全局共用"，符合最近原则。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>所有 IO / 解析失败都做成 best-effort——只跳过坏 skill，绝不让一份坏 SKILL.md 拖垮启动。</li>
 *   <li>路径都先 {@code toAbsolutePath().normalize()} 并校验 {@code startsWith(root)}，
 *       防止 skill 目录里通过符号链接 / {@code ..} 逃逸到根目录之外。</li>
 *   <li>描述提取做了 front matter 与正文两条 fallback，最大程度兼容用户随手写的 markdown。</li>
 * </ul>
 */
public final class SkillDiscovery {
    /** 应用主目录（用户级配置位置），如 {@code ~/.minicode-java}。 */
    private final Path appHome;
    /** 当前工作目录，对应"项目级" skill 的根。 */
    private final Path cwd;
    /** 操作系统的 HOME 目录，对应一些"全局兼容"路径（{@code ~/.claude/skills} 等）。 */
    private final Path osHome;

    /**
     * 便利构造：操作系统 HOME 通过 {@code System.getProperty("user.home")} 取得。
     *
     * @param appHome 应用主目录
     * @param cwd     当前工作目录
     */
    public SkillDiscovery(Path appHome, Path cwd) {
        this(appHome, cwd, Path.of(System.getProperty("user.home")));
    }

    /**
     * 主构造：三个路径都会被立即标准化为绝对路径，便于后续做"是否越界"校验。
     *
     * @param appHome 应用主目录
     * @param cwd     当前工作目录
     * @param osHome  操作系统 HOME 目录（测试时可注入临时目录）
     */
    public SkillDiscovery(Path appHome, Path cwd, Path osHome) {
        this.appHome = Objects.requireNonNull(appHome, "appHome").toAbsolutePath().normalize();
        this.cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        this.osHome = Objects.requireNonNull(osHome, "osHome").toAbsolutePath().normalize();
    }

    /**
     * 扫描所有已知的 skill 根目录，返回去重后的 skill 列表。
     *
     * <p>同名 skill 按 {@link #roots()} 给出的优先级保留第一次出现的——项目内的覆盖全局的。
     *
     * @return 已发现的全部 skill；列表不可变，顺序为发现顺序
     */
    public List<LoadedSkill> discover() {
        LinkedHashMap<String, LoadedSkill> byName = new LinkedHashMap<>();
        for (SkillRoot root : roots()) {
            for (LoadedSkill skill : listSkillDirs(root)) {
                byName.putIfAbsent(skill.name(), skill);
            }
        }
        return List.copyOf(byName.values());
    }

    /**
     * 返回所有 skill 根目录及其 {@link SkillSource} 标签，顺序就是优先级。
     *
     * <p>为了平滑迁移老命名（{@code .mini-code}）以及兼容 Claude Code（{@code .claude}），
     * 这里同时扫多个候选位置。
     */
    private List<SkillRoot> roots() {
        return List.of(
                new SkillRoot(cwd.resolve(".minicode").resolve("skills"), SkillSource.PROJECT_JAVA),
                new SkillRoot(appHome.resolve("skills"), SkillSource.USER_JAVA),
                new SkillRoot(cwd.resolve(".mini-code").resolve("skills"), SkillSource.PROJECT_TS),
                new SkillRoot(osHome.resolve(".mini-code").resolve("skills"), SkillSource.USER_TS),
                new SkillRoot(cwd.resolve(".claude").resolve("skills"), SkillSource.COMPAT_PROJECT),
                new SkillRoot(osHome.resolve(".claude").resolve("skills"), SkillSource.COMPAT_USER)
        );
    }

    /**
     * 列出某个根目录下的所有 skill 子目录，把每一个解析成 {@link LoadedSkill}。
     *
     * <p>根目录不存在直接返回空列表；遍历过程中遇到任何 IO 异常也"吞掉"返回空，
     * 保证 skill 发现是 best-effort 的——一份坏 skill 不能让 Agent 起不来。
     */
    private List<LoadedSkill> listSkillDirs(SkillRoot root) {
        Path normalizedRoot = root.path().toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            return List.of();
        }
        List<LoadedSkill> results = new ArrayList<>();
        try (Stream<Path> paths = Files.list(normalizedRoot)) {
            paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> readSkill(normalizedRoot, path, root.source(), results));
        } catch (IOException exception) {
            return List.of();
        }
        return results;
    }

    /**
     * 读取单个 skill 子目录里的 {@code SKILL.md} 并追加到结果列表。
     *
     * <p>关键校验：
     * <ul>
     *   <li>用 {@code startsWith(root)} 二次确认 skill 目录与 SKILL.md 没有越出根目录，
     *       防止符号链接 / {@code ..} 造成的目录穿越。</li>
     *   <li>目录名不能为空白——空字符串当 skill 名会让 SystemPrompt 出错。</li>
     *   <li>解析失败会被静默丢弃；这一步注释里也明确说"discovery is best-effort"。</li>
     * </ul>
     */
    private void readSkill(Path root, Path skillDir, SkillSource source, List<LoadedSkill> results) {
        Path normalizedSkillDir = skillDir.toAbsolutePath().normalize();
        if (!normalizedSkillDir.startsWith(root)) {
            return;
        }
        Path skillPath = normalizedSkillDir.resolve("SKILL.md").toAbsolutePath().normalize();
        if (!skillPath.startsWith(root)) {
            return;
        }
        String name = normalizedSkillDir.getFileName().toString();
        if (name.isBlank()) {
            return;
        }
        try {
            String content = Files.readString(skillPath, StandardCharsets.UTF_8);
            results.add(new LoadedSkill(name, extractDescription(content), skillPath, source, content));
        } catch (IOException | RuntimeException exception) {
            // Discovery is best-effort: malformed or unreadable skills must not block app startup.
        }
    }

    /**
     * 从一段 markdown 文本中提取"skill 一句话描述"，给 system prompt 使用。
     *
     * <p>提取顺序：
     * <ol>
     *   <li>优先看顶部 YAML front matter 里的 {@code description:} 字段；</li>
     *   <li>没有则取正文里第一个非空、非标题段落的第一行；</li>
     *   <li>仍找不到时返回兜底文案 {@code "No description provided."}。</li>
     * </ol>
     *
     * <p>包私有方便单元测试直接调用。
     *
     * @param markdown SKILL.md 全文
     * @return 抽取出的描述（永远非空）
     */
    static String extractDescription(String markdown) {
        String normalized = Objects.requireNonNull(markdown, "markdown").replace("\r\n", "\n");
        FrontMatterSplit split = splitLeadingFrontMatter(normalized);
        String frontMatterDescription = extractFrontMatterDescription(split.frontMatter());
        if (!frontMatterDescription.isBlank()) {
            return frontMatterDescription;
        }
        normalized = split.body();
        for (String rawBlock : normalized.split("\n\n")) {
            String block = rawBlock.trim();
            if (block.isEmpty() || block.startsWith("#")) {
                continue;
            }
            for (String rawLine : block.split("\n")) {
                String line = rawLine.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    return line.replace("`", "");
                }
            }
        }
        return "No description provided.";
    }

    /**
     * 把 markdown 拆成 "front matter" + "正文" 两段。
     *
     * <p>规则：必须以 {@code "---\n"} 开头，再找到下一行 {@code "---"} 才算一段合法 front matter；
     * 任何不符的情况都返回 (空 front matter, 整段当正文)，保持解析鲁棒性。
     */
    private static FrontMatterSplit splitLeadingFrontMatter(String markdown) {
        if (!markdown.startsWith("---\n")) {
            return new FrontMatterSplit("", markdown);
        }
        int end = markdown.indexOf("\n---\n", 4);
        if (end < 0) {
            return new FrontMatterSplit("", markdown);
        }
        return new FrontMatterSplit(markdown.substring(4, end), markdown.substring(end + "\n---\n".length()));
    }

    /**
     * 在已经切出来的 front matter 文本里查找 {@code description:} 字段值。
     *
     * <p>支持单 / 双引号包裹，并会去掉反引号；找不到返回空字符串（让上层 fallback 到正文段落）。
     */
    private static String extractFrontMatterDescription(String frontMatter) {
        if (frontMatter.isBlank()) {
            return "";
        }
        for (String rawLine : frontMatter.split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("description:")) {
                continue;
            }
            String value = line.substring("description:".length()).trim();
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1).trim();
            }
            return value.replace("`", "");
        }
        return "";
    }

    /**
     * markdown 拆分结果。
     *
     * @param frontMatter 顶部 YAML front matter 内容（不含两侧的 {@code ---} 分隔行）；没有时为空串
     * @param body        剩余的正文部分
     */
    private record FrontMatterSplit(String frontMatter, String body) {
    }

    /**
     * 一个 skill 根目录的描述：路径 + 来源标签。
     *
     * @param path   该根目录的路径
     * @param source 标签，区分项目级 / 用户级 / 兼容路径，最终会写到 {@link LoadedSkill}
     */
    private record SkillRoot(Path path, SkillSource source) {
        private SkillRoot {
            path = Objects.requireNonNull(path, "path");
            source = Objects.requireNonNull(source, "source");
        }
    }
}
