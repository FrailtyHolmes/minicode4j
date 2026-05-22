package minicode.edit;

import minicode.permissions.model.PermissionResource;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
/**
 * 描述一次待审查的文件修改。
 *
 * <p>EditReview 是文件写入前交给权限系统和用户审查的结构化摘要。
 * 它记录目标路径、操作类型、修改说明、diff 预览、修改前后规模、
 * 截断状态、审查指纹以及可选的完整 diff 引用。写入工具不应绕过
 * 该 review 直接修改文件。</p>
 *
 * <p>该 record 由 {@link EditReviewFactory} 构造，再交给 PermissionService
 * 做最终审批。所有写入入口（新建、整文件覆盖、精确替换、unified diff 应用、
 * 多段 modify）都会先生成一个 EditReview，统一审批通过后才落盘，从而保证
 * 用户可以在写盘前看到要发生的所有变更。</p>
 *
 * <p>设计取舍：用 record 而不是普通类，是因为这里只承载只读快照，
 * 用紧凑构造器（compact constructor）集中做参数校验和行尾规范化，
 * 避免每个调用方各自处理 diff 的 \r\n 与 \n 不一致问题。</p>
 *
 * @param path 已解析的目标文件绝对路径
 * @param operation 本次编辑的操作类型，例如 CREATE、OVERWRITE、EDIT、PATCH 或 MODIFY
 * @param summary 用于审批提示的一句话摘要，不能为空白
 * @param diffPreview 用于展示给用户的 diff 文本，构造时行尾会被规范化为 \n
 * @param beforeChars 修改前文件字符数，用于规模提示，必须为非负
 * @param afterChars 修改后文件字符数，用于规模提示，必须为非负
 * @param beforeExists 修改前目标文件是否已存在，用于区分 CREATE 与 OVERWRITE 等场景
 * @param truncated diffPreview 是否被截断；超长 diff 通常会截断预览并通过 diffRef 引用完整内容
 * @param reviewFingerprint 内容指纹，用于幂等审批和"同一改动是否已批准过"的判断，不能为空白
 * @param diffRef 完整 diff 的外部引用 id（如持久化输出 id），预览未被截断时通常为 {@link Optional#empty()}
 */

public record EditReview(Path path, PermissionResource.EditOperation operation, String summary,
                         String diffPreview, long beforeChars, long afterChars, boolean beforeExists,
                         boolean truncated, String reviewFingerprint, Optional<String> diffRef) {
    /**
     * 紧凑构造器：集中校验必填字段并规范化 diff 行尾。
     *
     * <p>对所有引用字段做 null 检查；要求 summary 与 reviewFingerprint
     * 不为空白；要求 beforeChars / afterChars 不为负；将 diffPreview 中的
     * \r\n、\r 统一替换为 \n，避免下游展示和指纹比对受平台行尾差异影响。</p>
     */
    public EditReview {
        path = Objects.requireNonNull(path, "path");
        operation = Objects.requireNonNull(operation, "operation");
        if (Objects.requireNonNull(summary, "summary").isBlank()) {
            throw new IllegalArgumentException("edit summary must not be blank");
        }
        diffPreview = normalizeLineEndings(Objects.requireNonNull(diffPreview, "diffPreview"));
        if (beforeChars < 0) {
            throw new IllegalArgumentException("beforeChars must not be negative");
        }
        if (afterChars < 0) {
            throw new IllegalArgumentException("afterChars must not be negative");
        }
        reviewFingerprint = Objects.requireNonNull(reviewFingerprint, "reviewFingerprint");
        if (reviewFingerprint.isBlank()) {
            throw new IllegalArgumentException("reviewFingerprint must not be blank");
        }
        diffRef = Objects.requireNonNull(diffRef, "diffRef");
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
