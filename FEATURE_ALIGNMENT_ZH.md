# 功能对齐概览

这份文档简单说明 MiniCode4j 当前已经和 TypeScript 版 MiniCode 对齐的能力，以及还没有完成的方向。

TypeScript 主仓库：

```text
https://github.com/LiuMengxuan04/MiniCode
```

## 当前定位

MiniCode4j 是 MiniCode 的 Java 版实现，目标不是做一个普通聊天 CLI，而是实现一个本地优先、terminal-first 的 coding agent。

核心工作流和 TS 版保持一致：

```text
model -> tool -> model
```

模型负责理解任务和决定工具调用；工具负责读取文件、搜索代码、执行命令、写入修改；执行结果再回到模型继续推理。

## 已对齐的核心能力

- 基础 agent loop：支持模型输出、工具调用、工具结果回传、继续生成最终答复。
- Anthropic-compatible provider：可以连接兼容 Anthropic Messages API 的模型服务。
- 本地 workspace：默认以当前目录作为工作区，也支持 `--cwd` 指定工作区。
- 内置工具：支持读文件、列目录、搜索、编辑、写文件、执行命令、询问用户。
- 工具注册边界：工具统一通过 registry 注册、校验和执行。
- 权限审查：敏感命令和写入类操作执行前会经过权限确认。
- diff review：写入和修改文件时会展示变更内容，避免静默改文件。
- session：使用 append-only JSONL 保存会话，支持 `list`、`resume`、`rename`、`fork`。
- skills 最小闭环：可以发现本地 skills，并通过 `load_skill` 加载完整内容。
- context 管理：支持大工具输出预算、manual `/compact` 和自动 autoCompact。
- stdio MCP tools：已经支持最小 stdio MCP tool 发现、注册和调用闭环。
- Windows launcher：构建后可以通过 `minicode.cmd` / `minicode.ps1` 从任意目录启动。

## 和 TS 版仍有差距的部分

MiniCode4j 当前能跑通最小 coding agent 工作流，但很多体验和扩展能力还不完整。主要差距包括：

- TUI 还比较粗糙，完整的交互式渲染层、输入区、多行输入、session picker 等还没有做完。
- MCP 目前只做了 stdio tools，resources、prompts、HTTP/SSE、server 管理等还没有完成。
- Web 工具还没有实现。
- Skills 目前只有 discover/load，安装、删除、列表、版本管理还没有做。
- Provider 配置、错误诊断、安装升级、发布体验还可以继续完善。
- context / compact 已可用，但摘要质量、阈值策略和大输出查看体验还可以继续调优。

更具体的待优化方向见：

```text
IMPROVEMENTS_ZH.md
```

## 当前可用程度

目前 MiniCode4j 已经可以作为一个最小 coding agent 使用：

```powershell
cd <你的项目目录>
minicode
```

它适合用于继续验证 Java 版 agent 架构、provider 适配、工具调用、权限审查、session 恢复和上下文压缩等核心能力。

如果你期待的是接近成熟产品的完整 CLI / TUI 体验，还需要继续做 `IMPROVEMENTS_ZH.md` 中列出的优化。
