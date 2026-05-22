package minicode.tui;

import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionChoice;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.model.PermissionPromptResult;
import minicode.permissions.model.PermissionRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 行模式（line mode）下的权限审批弹窗实现：用普通 stdin/stdout 完成"打印问题→读一行回答"的同步交互。
 *
 * <p>当 JLine 真终端不可用（IDE 控制台、非交互 shell 等）时由 {@link minicode.app.MiniCodeApp} 选用本类；
 * 真终端可用时改走 {@link RendererTuiBridge}（带高亮和键盘选择）。两者实现 {@link PermissionPromptHandler}
 * 同一个接口——{@link minicode.permissions.service.PromptingPermissionService} 只面向接口编程，
 * 不感知 UI 模式差异（策略 + 依赖倒置）。详见 docs/tutorial/ch08。</p>
 *
 * <p>用户输入支持四种格式：选项编号（如 {@code 1}）、key（如 {@code allow_once}）、
 * 带方括号的 key（如 {@code [allow_once]}）和完整 label（大小写/空白不敏感）。</p>
 */
public final class ConsolePermissionPromptHandler implements PermissionPromptHandler {
    private final BufferedReader input;
    private final PrintWriter output;

    /**
     * 便捷构造器：直接以原始 {@link InputStream} 创建一个 UTF-8 的 {@link BufferedReader}。
     * 非测试场景下一般就传 {@code System.in}。
     */
    public ConsolePermissionPromptHandler(InputStream input, OutputStream output) {
        this(new BufferedReader(new InputStreamReader(Objects.requireNonNull(input, "input"), StandardCharsets.UTF_8)),
                output);
    }

    /**
     * 主构造器：调用方自备 {@link BufferedReader}（测试可注入 {@link java.io.StringReader}）。
     * 输出统一包成 auto-flush 的 UTF-8 {@link PrintWriter}，避免每次手动 flush 漏行。
     */
    public ConsolePermissionPromptHandler(BufferedReader input, OutputStream output) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = new PrintWriter(Objects.requireNonNull(output, "output"), true, StandardCharsets.UTF_8);
    }

    /**
     * 同步打印审批信息并阻塞等待用户输入。
     *
     * <p>流程：打印 title/body/facts → 列出全部 choice → 循环读取直到匹配到合法选项 →
     * 若所选选项要求反馈（如 {@code DENY_WITH_FEEDBACK}），再读一行 feedback。
     * 用户没填 feedback 时回退到默认的"Permission denied by user"。</p>
     *
     * @throws UncheckedIOException 当底层 reader IO 异常时——上层 {@link minicode.permissions.service.PromptingPermissionService}
     *                              捕获后会拒绝本次工具调用
     */
    @Override
    public PermissionPromptResult prompt(PermissionRequest request) {
        output.println("permission: " + request.details().title());
        output.println(request.details().body());
        for (String fact : request.details().facts()) {
            output.println("  " + fact);
        }
        output.println("Waiting for permission choice. Enter a number, key, [key], or label.");
        renderChoices(request);
        try {
            PermissionChoice choice = readChoice(request);
            if (choice.requiresFeedback()) {
                output.print("Feedback: ");
                output.flush();
                String feedback = input.readLine();
                if (feedback == null || feedback.isBlank()) {
                    feedback = "Permission denied by user";
                }
                return PermissionPromptResult.deny(choice.key(), choice.decision(), feedback);
            }
            if (isAllow(choice.decision())) {
                return PermissionPromptResult.allow(choice.key(), choice.decision());
            }
            return PermissionPromptResult.deny(choice.key(), choice.decision(), null);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 循环读取一行用户输入，匹配到合法 choice 才返回。
     *
     * <p>遇到 EOF（{@code readLine()} 返回 null）认为是非交互场景，按 {@link #fallbackDenyChoice}
     * 给出"安全默认"——拒绝。这样把 stdin 关掉时不会卡死。</p>
     */
    private PermissionChoice readChoice(PermissionRequest request) throws IOException {
        output.print("Permission choice: ");
        output.flush();
        while (true) {
            String line = input.readLine();
            if (line == null) {
                return fallbackDenyChoice(request);
            }
            Optional<PermissionChoice> selected = selectChoice(request, line);
            if (selected.isPresent()) {
                return selected.orElseThrow();
            }
            output.println("Unknown permission choice: " + line.trim()
                    + ". Enter one of the listed numbers, keys, [keys], or labels.");
            output.print("Permission choice: ");
            output.flush();
        }
    }

    private void renderChoices(PermissionRequest request) {
        for (int index = 0; index < request.choices().size(); index++) {
            PermissionChoice choice = request.choices().get(index);
            output.println("  " + (index + 1) + ") " + choice.label() + " [" + choice.key() + "]");
        }
    }

    /**
     * 非交互/输入流关闭时的回退选项：优先选 {@code DENY_ONCE}，
     * 没有则选第一个非允许选项，最后兜底选最后一项。保证「不确定时拒绝」的安全姿态。
     */
    private static PermissionChoice fallbackDenyChoice(PermissionRequest request) {
        return request.choices().stream()
                .filter(choice -> choice.decision() == PermissionDecision.DENY_ONCE)
                .findFirst()
                .or(() -> request.choices().stream().filter(choice -> !isAllow(choice.decision())).findFirst())
                .orElseGet(() -> request.choices().getLast());
    }

    /**
     * 把用户输入解析成 {@link PermissionChoice}。
     *
     * <p>解析顺序：先尝试当成 1-based 编号；不是数字再按 key/label 大小写不敏感匹配。
     * 都没命中返回 {@link Optional#empty()}，由调用方提示并重读。</p>
     */
    private static Optional<PermissionChoice> selectChoice(PermissionRequest request, String line) {
        if (line == null) {
            return Optional.empty();
        }
        String normalized = normalize(line);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        try {
            int choiceNumber = Integer.parseInt(normalized);
            if (choiceNumber >= 1 && choiceNumber <= request.choices().size()) {
                return Optional.of(request.choices().get(choiceNumber - 1));
            }
        } catch (NumberFormatException ignored) {
            // Continue with key/label matching.
        }
        for (PermissionChoice choice : request.choices()) {
            if (normalize(choice.key()).equals(normalized) || normalize(choice.label()).equals(normalized)) {
                return Optional.of(choice);
            }
        }
        return Optional.empty();
    }

    /**
     * 标准化用户输入：去前后空白、剥掉一对外层方括号（如 {@code [allow]}）、
     * 折叠多空白为单空格、转小写。让"输入容错"集中在一个地方实现。
     */
    private static String normalize(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * 判断一个 {@link PermissionDecision} 是否属于"允许"语义（包括 ALLOW_ONCE / ALLOW_TURN / ALLOW_ALWAYS）。
     * 用于决定本次审批要返回 {@link PermissionPromptResult#allow} 还是 {@link PermissionPromptResult#deny}。
     */
    private static boolean isAllow(PermissionDecision decision) {
        return decision == PermissionDecision.ALLOW_ONCE
                || decision == PermissionDecision.ALLOW_TURN
                || decision == PermissionDecision.ALLOW_ALWAYS;
    }
}
