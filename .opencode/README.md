# GRDP-Studio 多 Agent 使用说明

本项目保留 OpenCode 内置 `Build` 作为主 Agent。主 Agent 负责接收需求、调用子 Agent、协调文件所有权、汇总结果和最终验收。项目没有修改全局 `default_agent`，也没有增加一个只能转发消息的 orchestrator。

## 四个子 Agent

| Agent | 用途 | 是否修改代码 |
| --- | --- | --- |
| `@architect` | 读取需求和代码，设计接口、状态机、工作包与测试矩阵 | 否 |
| `@executor` | 按一个明确工作包实现、调试和验证 | 是，仅限许可范围 |
| `@reviewer` | 独立读取 diff、运行验证、报告缺陷和验收结论 | 否 |
| `@vision` | 分析截图、设计稿和 PDF 中的可见 UI 问题 | 否 |

子 Agent 不能通过 `opencode run --agent architect` 作为 primary 直接启动。在 OpenCode 会话中输入 `@architect`、`@executor`、`@reviewer` 或 `@vision` 调用；主 Agent 也可以根据 description 自动委托。

## 标准流程

### 1. 设计

```text
@architect
为“PIPESIM 节点分析运行”设计可执行方案。
读取 requirements.md、PROGRESS.md 和当前代码；只开发软件集成，不修改解析融合行为。
请给出接口、状态机、Worker 协议、工作包所有权、依赖关系和验收矩阵。
```

### 2. 实现

每次只给 Executor 一个工作包。若工作包文件互不重叠，可以在同一个主会话中并行启动多个 Executor 子会话。

```text
@executor
实现工作包：Worker 节点分析执行。
文件所有权仅限 worker/**。
输入契约：<粘贴 architect 确认的契约>。
验收：dotnet build；CSW_101 返回 IPR/VLP；不得修改 Avalonia。
```

### 3. 审查

```text
@reviewer
审查本轮 PIPESIM 节点分析改动。
读取完整 diff，运行可用构建和 CSW_101/CSW_102 验收。
重点检查状态机、许可证串行、解析语义和解析融合回归。
```

### 4. 返工

Reviewer 返回 `REWORK` 时，把具体 Findings 原样交给 Executor；修复后再次调用 Reviewer。Reviewer 返回 `BLOCKED` 时，先解决环境或需求阻塞，不要求 Executor 猜测绕过。

## 需求拆解模板

一个工作包必须包含：

```text
目标：用户可以观察到的单一结果
文件所有权：该 Agent 可以编辑的目录或文件
输入：已冻结的接口、DTO、事件或样例
输出：代码、迁移、API、页面或 Artifact
依赖：必须先完成的工作包
禁止项：不可修改的业务和文件
验收：明确命令、测试模型和期望结果
```

适合并行：

- 接口已经冻结，且 Worker、后端、前端分别编辑不同目录。
- 一方只研究，另一方实现不相关文件。
- 独立 Reviewer 在实现完成后检查，不与 Executor 同时改代码。

必须串行：

- 两个工作包会编辑同一个共享文件。
- 后端 DTO/状态机尚未冻结，前端依赖其字段。
- 真实 PIPESIM PTK 操作会竞争单个许可证；验收任务必须全局串行。
- 数据库迁移依赖上一版 Schema。

## 当前项目边界

- 权威需求：`docs/software-integration/requirements.md`；外部 `C:\Users\Violet\Desktop\Bei\PRO.MD` 仅保留为原始备份。
- Avalonia 仅作只读行为基线：`C:\Users\Violet\Desktop\Ava_desktop\Avalonia_oil`。
- Executor 自动可写软件集成目录；共享 Shell、依赖和应用配置需要人工确认。
- Agent 禁止 commit、push、reset、checkout 和 clean。
- 真实验收模型为 CSW_101 与 CSW_102；必须区分代码错误和许可证/环境错误。
