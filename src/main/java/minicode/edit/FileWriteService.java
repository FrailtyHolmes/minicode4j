package minicode.edit;

import minicode.permissions.api.PermissionService;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.model.PermissionResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/**
 * 文件整体写入的统一入口，集中处理"读旧内容、生成 review、走权限审批、最终落盘"四步。
 *
 * <p>所有写文件类工具（write_file、edit_file、patch_file、modify_file）最终都收敛到
 * 本 service 的 {@link #applyReviewedReplacement} 私有实现。这样做的目的是保证：
 * 任何对磁盘的修改都必须先生成 {@link EditReview}、经过 {@link PermissionService} 审批，
 * 不存在绕过 review 的旁路。</p>
 *
 * <p>关键设计取舍：</p>
 * <ul>
 *   <li>不在工具内部各自实现写入逻辑，避免审批边界被遗漏。</li>
 *   <li>对"修改后内容与原内容完全一致"的情况识别为 no-op，不打扰用户也不污染 mtime。</li>
 *   <li>统一使用 UTF-8、TRUNCATE_EXISTING 写入；上层负责把目标内容计算到位再交给本类。</li>
 * </ul>
 */
public final class FileWriteService {
    private final PermissionService permissionService;

    /**
     * 构造一个文件写入 service。
     *
     * @param permissionService 用于在落盘前请求 edit 审批的权限服务，不能为 null
     */
    public FileWriteService(PermissionService permissionService) {
        this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
    }

    /**
     * 写入或新建一个文件，自动根据目标是否存在选择 CREATE 还是 OVERWRITE。
     *
     * <p>这是 write_file 类工具最常用的入口：调用方只需提供已解析的目标路径和
     * 期望的完整新内容，本方法会读取旧内容、推断操作类型、生成对用户友好的摘要
     * （"Create file: ..." 或 "Overwrite file: ..."），然后委托
     * {@link #applyReviewedReplacement(Path, String, PermissionResource.EditOperation, String, String, Optional, PermissionContext, Runnable, Optional)}
     * 完成审批和写盘。</p>
     *
     * @param targetPath 已解析后的目标文件绝对路径
     * @param filePath 面向用户展示的文件路径（通常是相对于 cwd 的形式），用于摘要
     * @param nextContent 修改后的完整文件内容
     * @param toolUseId 触发本次写入的工具调用 id，可为空
     * @param permissionContext 权限审批所需的 session、turn 和 tool 上下文
     * @param beforeWriteCheck 用户批准后、真正写入磁盘前执行的最后一步检查回调
     * @return 文件写入结果，说明修改已应用或被判定为 no-op
     * @throws IOException 当读取旧文件或写入新内容失败时抛出
     */
    public FileWriteResult apply(Path targetPath, String filePath, String nextContent, Optional<String> toolUseId,
                                 PermissionContext permissionContext, Runnable beforeWriteCheck) throws IOException {
        Path actualTargetPath = Objects.requireNonNull(targetPath, "targetPath");
        Optional<String> beforeContent = readExistingContent(actualTargetPath);
        PermissionResource.EditOperation operation = beforeContent.isPresent()
                ? PermissionResource.EditOperation.OVERWRITE
                : PermissionResource.EditOperation.CREATE;
        return applyReviewedReplacement(
                actualTargetPath,
                filePath,
                operation,
                operation == PermissionResource.EditOperation.CREATE
                        ? "Create file: " + filePath
                        : "Overwrite file: " + filePath,
                nextContent,
                toolUseId,
                permissionContext,
                beforeWriteCheck,
                beforeContent
        );
    }
    /**
     * 对目标文件应用一次经过 review 的整文件替换。
     *
     * <p>调用方负责提前解析目标路径，并计算出修改后的完整文件内容。
     * 本方法集中管理文件写入边界：读取当前文件内容、跳过 no-op 修改、
     * 创建 edit review、请求 edit 权限、在真正写入前执行最后的检查，
     * 最后才把新内容写入磁盘。</p>
     *
     * @param targetPath 已解析后的目标文件路径
     * @param filePath 面向用户展示的文件路径，用于摘要和提示信息
     * @param operation 本次编辑操作类型，例如 EDIT、PATCH 或 MODIFY
     * @param summary 用于 review 提示的简短摘要
     * @param nextContent 修改后的完整文件内容
     * @param toolUseId 触发本次写入的工具调用 id，可为空
     * @param permissionContext 权限 review 所需的 session、turn 和 tool 上下文
     * @param beforeWriteCheck 用户批准后、真正写入磁盘前执行的检查回调
     * @return 文件写入结果，说明修改已应用或被判定为 no-op
     * @throws IOException 当读取旧文件或写入新内容失败时抛出
     */

    public FileWriteResult applyReviewedReplacement(Path targetPath, String filePath,
                                                    PermissionResource.EditOperation operation,
                                                    String summary,
                                                    String nextContent,
                                                    Optional<String> toolUseId,
                                                    PermissionContext permissionContext,
                                                    Runnable beforeWriteCheck) throws IOException {
        return applyReviewedReplacement(
                targetPath,
                filePath,
                operation,
                summary,
                nextContent,
                toolUseId,
                permissionContext,
                beforeWriteCheck,
                readExistingContent(Objects.requireNonNull(targetPath, "targetPath"))
        );
    }


    /**
     * 执行文件写入链路的核心实现。
     *
     * <p>该方法接收已经读取好的旧内容，判断是否为 no-op，生成 edit review，
     * 请求 edit 权限，并在权限通过后写入新内容。所有公开写入入口最终都应
     * 收敛到这里，确保文件修改不会绕过 review 和 permission 边界。</p>
     *
     * @param targetPath 已解析后的目标文件路径
     * @param filePath 面向用户展示的文件路径，用于摘要和提示信息
     * @param operation 本次编辑操作类型，例如 CREATE、OVERWRITE、EDIT、PATCH 或 MODIFY
     * @param summary 用于 review 提示的简短摘要
     * @param nextContent 修改后的完整文件内容
     * @param toolUseId 触发本次写入的工具调用 id，可为空
     * @param permissionContext 权限 review 所需的 session、turn 和 tool 上下文
     * @param beforeWriteCheck 用户批准后、真正写入磁盘前执行的检查回调
     * @param beforeContent 已读取到的旧文件内容；文件不存在时为空
     * @return 文件写入结果，说明修改已应用或被判定为 no-op
     * @throws IOException 当读取旧文件或写入新内容失败时抛出
     */

    private FileWriteResult applyReviewedReplacement(Path targetPath, String filePath,
                                                     PermissionResource.EditOperation operation,
                                                     String summary,
                                                     String nextContent,
                                                     Optional<String> toolUseId,
                                                     PermissionContext permissionContext,
                                                     Runnable beforeWriteCheck,
                                                     Optional<String> beforeContent) throws IOException {
        Path actualTargetPath = Objects.requireNonNull(targetPath, "targetPath");
        String actualFilePath = Objects.requireNonNull(filePath, "filePath");
        PermissionResource.EditOperation actualOperation = Objects.requireNonNull(operation, "operation");
        String actualSummary = Objects.requireNonNull(summary, "summary");
        if (actualSummary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        String actualNextContent = Objects.requireNonNull(nextContent, "nextContent");
        Optional<String> actualToolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
        PermissionContext actualPermissionContext = Objects.requireNonNull(permissionContext, "permissionContext");
        Runnable actualBeforeWriteCheck = Objects.requireNonNull(beforeWriteCheck, "beforeWriteCheck");
        Optional<String> actualBeforeContent = Objects.requireNonNull(beforeContent, "beforeContent");

        if (actualBeforeContent.filter(actualNextContent::equals).isPresent()) {
            return FileWriteResult.noOp("No changes needed for " + actualFilePath);
        }

        PermissionResource.EditResource review = EditReviewFactory.review(
                actualTargetPath,
                actualOperation,
                actualSummary,
                actualBeforeContent,
                actualNextContent,
                actualToolUseId
        );

        permissionService.ensureEdit(review, actualPermissionContext);
        actualBeforeWriteCheck.run();

        Files.writeString(
                actualTargetPath,
                actualNextContent,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        return FileWriteResult.applied(actualOperation, "Applied reviewed changes to " + actualFilePath);
    }

    /**
     * 读取目标路径已有的文件内容。
     *
     * <p>文件不存在时返回 {@link Optional#empty()}，调用方据此区分 CREATE / OVERWRITE。
     * 如果目标路径指向目录，会直接抛出 {@link IOException}，避免误把目录当文件覆盖。
     * 文件存在时按 UTF-8 一次性读入，适合本类处理的"中小型源代码与配置"场景。</p>
     *
     * @param targetPath 目标文件路径
     * @return 旧文件内容，不存在时为空
     * @throws IOException 当目标是目录或读取失败时抛出
     */
    private static Optional<String> readExistingContent(Path targetPath) throws IOException {
        if (!Files.exists(targetPath)) {
            return Optional.empty();
        }
        if (Files.isDirectory(targetPath)) {
            throw new IOException("Expected file but found directory: " + targetPath);
        }
        return Optional.of(Files.readString(targetPath, StandardCharsets.UTF_8));
    }

}
