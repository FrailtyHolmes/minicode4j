package minicode.permissions.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.permissions.model.PermissionKind;
import minicode.permissions.model.PermissionResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 把"用户已审批的权限决策"以 JSON 文件形式落盘的 {@link PermissionStore} 实现。
 *
 * <p>权限系统只持久化 {@code ALLOW_ALWAYS} 与 {@code DENY_ALWAYS} 这种"一直生效"的决策，
 * 让用户审批一次之后下次再遇到相同资源不必再问。{@code ALLOW_ONCE} / {@code ALLOW_TURN}
 * 这种短时授权由 {@link minicode.permissions.service.PromptingPermissionService}
 * 自行在内存中管理，不会进到这里。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>用 Jackson 直接读写一个 {"version":..., "entries":[...]} 的 JSON，
 *       结构稳定、人类可读、便于用户手动审计 / 编辑。</li>
 *   <li>所有读写都加 {@code synchronized}，防止多线程同时改盘上文件造成损坏；
 *       内存里用 {@link LinkedHashMap} 保留写入顺序，让 JSON 输出稳定。</li>
 *   <li>采用"懒加载"——首次访问时才读盘，避免应用启动时强行 IO。</li>
 *   <li>载入失败统一包成 {@link UncheckedIOException}，让上层用 try/catch 不被 IOException 污染签名。</li>
 * </ul>
 *
 * <p>典型调用方：{@code PromptingPermissionService#ensure} 在弹窗前后查询 / 写入决策。
 */
public final class JsonPermissionStore implements PermissionStore {
    /** 写盘时附带的 schema 版本，未来字段升级时可用于兼容老文件。 */
    private static final int VERSION = 1;
    /** 共享的 Jackson 序列化器；开启缩进让 JSON 文件人类可读。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 落盘文件路径，例如 {@code ~/.minicode-java/permissions.json}。 */
    private final Path file;
    /** 内存里持有的全部条目；以 {@link PermissionResourceKey} 为 key 实现去重。 */
    private final Map<PermissionResourceKey, PermissionStoreEntry> entries = new LinkedHashMap<>();
    /** 是否已经从磁盘加载过；配合"懒加载"避免重复读文件。 */
    private boolean loaded;

    /**
     * 创建一个绑定到指定 JSON 文件的存储。
     *
     * <p>构造时不会立刻读盘，文件在首次 {@link #find}/{@link #save}/{@link #entries}
     * 调用时才会被读取（甚至文件不存在也允许，会当成空记录处理）。
     *
     * @param file 用于持久化的 JSON 文件路径，不能为 {@code null}
     */
    public JsonPermissionStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * 返回当前绑定的 JSON 文件路径。
     *
     * <p>主要给测试 / 诊断信息使用，业务代码不需要直接读这个路径。
     *
     * @return 持久化文件的绝对或相对路径，由构造时传入
     */
    public Path file() {
        return file;
    }

    /**
     * 查询某个资源是否已经有持久化的审批决策。
     *
     * <p>会按需触发懒加载。返回的 {@link PermissionStoreEntry} 包含 ALLOW / DENY，
     * 由调用方决定如何使用（见 {@code PromptingPermissionService#ensure}）。
     *
     * @param resource 需要审批的资源（路径、命令、编辑、MCP 工具等的统一抽象）
     * @return 命中则返回条目，未命中返回 {@link Optional#empty()}
     */
    @Override
    public synchronized Optional<PermissionStoreEntry> find(PermissionResource resource) {
        loadIfNeeded();
        return Optional.ofNullable(entries.get(PermissionResourceKey.from(resource)));
    }

    /**
     * 写入一条决策。同一资源 key 上已有的条目会被新条目直接覆盖，
     * 之后立即把整个内存映射重新序列化回 JSON 文件。
     *
     * <p>"先写内存再立即落盘"使得即使进程崩溃也最多丢失正在写入的那一条，
     * 已经返回成功的调用一定持久化成功。
     *
     * @param entry 要写入的条目，不能为 {@code null}
     */
    @Override
    public synchronized void save(PermissionStoreEntry entry) {
        loadIfNeeded();
        PermissionStoreEntry actualEntry = Objects.requireNonNull(entry, "entry");
        entries.put(actualEntry.resourceKey(), actualEntry);
        write();
    }

    /**
     * 返回所有已落盘条目的不可变快照，按写入顺序排列。
     *
     * <p>主要给"列出所有已授权资源"这类管理 / 调试场景使用。
     *
     * @return 当前全部条目的不可变副本
     */
    @Override
    public synchronized List<PermissionStoreEntry> entries() {
        loadIfNeeded();
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    /**
     * 懒加载入口：第一次访问时把 JSON 文件读进 {@link #entries}。
     *
     * <p>无论本次是否真的读到内容（文件可能根本不存在），都把 {@link #loaded}
     * 置为 {@code true}，确保不会重复 IO。读到的格式如果非法（比如 entries 不是数组），
     * 当前实现选择"静默跳过"而不是抛错，避免一份坏文件让 Agent 直接起不来。
     */
    private void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            JsonNode entriesNode = root.get("entries");
            if (entriesNode == null || !entriesNode.isArray()) {
                return;
            }
            for (JsonNode entryNode : entriesNode) {
                PermissionStoreEntry entry = readEntry(entryNode);
                entries.put(entry.resourceKey(), entry);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read permission store " + file, exception);
        }
    }

    /**
     * 把单条 JSON 节点解析成 {@link PermissionStoreEntry}。
     *
     * <p>所有字段都通过 {@link #requiredText} 强制存在 + 非空，以便用户手改坏文件时尽早报错。
     *
     * @param entryNode JSON 中的一条 {@code entries[i]} 节点
     * @return 解析得到的领域对象
     * @throws IllegalArgumentException 缺少必填字段或字段类型不对时
     */
    private static PermissionStoreEntry readEntry(JsonNode entryNode) {
        JsonNode keyNode = entryNode.path("resourceKey");
        PermissionResourceKey key = new PermissionResourceKey(
                requiredText(keyNode, "type"),
                requiredText(keyNode, "fingerprint")
        );
        return new PermissionStoreEntry(
                PermissionStoreDecision.valueOf(requiredText(entryNode, "decision")),
                PermissionKind.valueOf(requiredText(entryNode, "kind")),
                key,
                Instant.parse(requiredText(entryNode, "createdAt"))
        );
    }

    /**
     * 从 JSON 节点中读取一个"必须存在的非空文本字段"。
     *
     * @param node  父节点
     * @param field 字段名
     * @return 字段值
     * @throws IllegalArgumentException 字段缺失、不是文本、或文本为空白时
     */
    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Invalid permission store entry field: " + field);
        }
        return value.asText();
    }

    /**
     * 把当前内存中的全部条目重新写回 JSON 文件，必要时自动创建父目录。
     *
     * <p>采用全量重写而不是增量追加：内存里 entries 已经是去重过的最新映射，
     * 整体重写最简单也最不容易出错；代价就是每次 save 都会写整文件，
     * 对权限存储这种小数据量场景完全够用。
     */
    private void write() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MAPPER.writeValue(file.toFile(), toJson());
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write permission store " + file, exception);
        }
    }

    /**
     * 把内存中的 {@link #entries} 序列化成对应的 JSON 树结构。
     *
     * <p>顶层结构为 {@code {"version":N,"entries":[...]}}，每个条目包含
     * decision / kind / resourceKey / createdAt 四个字段。
     */
    private ObjectNode toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", VERSION);
        ArrayNode entriesNode = root.putArray("entries");
        for (PermissionStoreEntry entry : entries.values()) {
            ObjectNode entryNode = entriesNode.addObject();
            entryNode.put("decision", entry.decision().name());
            entryNode.put("kind", entry.kind().name());
            ObjectNode keyNode = entryNode.putObject("resourceKey");
            keyNode.put("type", entry.resourceKey().type());
            keyNode.put("fingerprint", entry.resourceKey().fingerprint());
            entryNode.put("createdAt", entry.createdAt().toString());
        }
        return root;
    }
}
