# GRDP-Studio 软件集成首版需求与技术基线

## 1. 文档目的

本文档是 GRDP-Studio 软件集成功能后续设计、开发、测试和验收的依据。

本文档只约束“软件集成”范围。除接入软件集成入口所需的最小共享改动外，不得修改解析融合、PVT、产能分析、项目业务、原 GRDP 平台及其他既有功能的行为。

## 2. 已确认目标

在当前 Windows 主机上，将 Avalonia 桌面端已经实现的本地模拟器联动能力迁移到 B/S 架构，使浏览器可以通过 GRDP-Studio 后端提交任务，由本机 Windows Worker 调用 PIPESIM、后续的 PIPESIM Network 和 ECLIPSE，并返回可追溯的计算结果。

首个完整闭环为：

```text
浏览器上传 PIPESIM 模型
    -> 后端保存模型版本
    -> Worker 异步验证模型并读取 Study
    -> 用户选择 Study 和运行类型
    -> 后端创建持久任务
    -> Worker 调用本机 PIPESIM 2022.1
    -> Worker 使用冻结的解析逻辑生成结果
    -> 后端保存结果和 Artifact
    -> 浏览器展示曲线、剖面、日志和运行历史
```

## 3. 强制变更边界

### 3.1 只开发软件集成

允许新增或修改：

- `GRDP-Studio/worker/` 下的软件集成 Windows Worker。
- Spring Boot 的 `com.grdp.studio.softwareintegration` 独立模块。
- Vue 的 `SoftwareIntegration` 独立模块、状态和 API。
- 软件集成所需的数据库表和迁移。
- 软件集成相关测试、配置、脚本和文档。
- `RibbonMenu.vue`、`IprInterface.vue` 等共享 Shell 文件中，仅限软件集成模式的最小接线改动。
- Windows 一键启停脚本中 Worker 的启停和健康检查。

禁止：

- 修改现有解析融合算法和结果语义。
- 修改现有 PVT、产能分析、水侵、项目树等非软件集成功能行为。
- 将软件集成逻辑继续堆入 `IprInterface.vue`。
- 通过浏览器直接启动本机进程或访问模拟器 SDK。
- 将许可证、安装路径、Cookie、密码或本机敏感配置提交到 Git。
- 为了迁移而重构无关模块。

### 3.2 共享文件修改原则

如果必须修改共享 Shell 文件：

1. 改动必须由 `workspace=software-integration` 或稳定的软件集成命令 ID 隔离。
2. 解析融合模式的原有分支和行为保持不变。
3. 增加回归测试或至少执行既有构建和关键页面验证。
4. 不顺手清理、重命名或格式化无关代码。

## 4. 参考项目与复用边界

### 4.1 Avalonia 对照基线

对照项目：

```text
C:\Users\Violet\Desktop\Ava_desktop\Avalonia_oil
```

Avalonia 桌面端只作为行为和结果对照基线，不再承载新功能开发。

可迁移复用的主要代码：

- `src/UnifiedConsole.Infrastructure/Simulators/Pipesim/PipesimPtkProcess.cs`
- `src/UnifiedConsole.Infrastructure/Simulators/Pipesim/ptk_worker.py`
- `src/UnifiedConsole.Infrastructure/Simulators/Pipesim/PipesimEnvironment.cs`
- `src/UnifiedConsole.Application/Features/PipesimIntegration/IPipesimEngineService.cs`
- `src/UnifiedConsole.Application/Features/PipesimNetworkIntegration/IPipesimNetworkEngineService.cs`
- `src/UnifiedConsole.Infrastructure/Simulators/Eclipse/EclipseAdapter.cs`
- `src/UnifiedConsole.Infrastructure/Simulators/Eclipse/EclipseSummaryParser.cs`
- `src/UnifiedConsole.Infrastructure/Processes/ExternalProcessSupervisor.cs`
- `src/UnifiedConsole.Infrastructure/Processes/ExternalProcessSupervisor.Session.cs`

不得迁移 Avalonia、Actipro、View、文件选择器、文档工作区和 ViewModel 作为 Worker 依赖。

### 4.2 冻结的解析和归一化语义

以下行为必须通过黄金样本测试保护，迁移时不得同时重写或优化：

- ECLIPSE RSM 解析。
- ECLEND 解析。
- ECLIPSE 输出新鲜度判断和 License/Fatal 分类。
- PIPESIM `_clean_number`。
- PIPESIM `normalize_curve`。
- PIPESIM `normalize_profile`。
- PIPESIM `normalize_network_profile`。
- PIPESIM `BuildSuccessResult`。
- PIPESIM `BuildSensitivitySuccess`。
- PIPESIM `ParseCurve`、`ParseProfile`、`ParseNetworkProfile`。
- 既有结果 DTO 的字段、单位、点顺序、缺失值和状态语义。

首版没有跨模拟器结果融合需求。未来融合必须位于解析结果之后，不得写进解析器或模拟器 Adapter。

## 5. GRDP 前后端规范

### 5.1 前端规范

- 延续 Vue 3 `<script setup>`、Element Plus、ECharts 和 scoped SCSS。
- 页面组件使用 PascalCase。
- API 使用独立领域模块，禁止新增类似 `docker.js` 的跨领域大文件。
- 使用稳定 ID，不使用中文显示文本作为命令或资源主键。
- 软件集成共享状态使用独立 Pinia Store，不复用解析融合的模块级全局 refs。
- 所有计时器、图表、SSE/WebSocket 和事件监听在组件卸载时释放。
- 长任务以持久 `runId` 和结构化状态为准，不解析人类日志判断完成。
- API 使用 Spring 统一响应：

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

### 5.2 后端规范

新增包：

```text
com.grdp.studio.softwareintegration
├── controller
├── dto
├── entity
├── mapper
├── service
│   └── impl
├── client
├── artifact
├── execution
└── support
```

要求：

- Controller 只负责绑定、校验和响应包装。
- Request/Response 使用独立 DTO，优先 Java record。
- Entity 不直接暴露给前端。
- Mapper 使用 MyBatis-Plus `BaseMapper`。
- 长时间模拟器调用不得位于数据库事务中。
- 状态修改使用短事务。
- HTTP 状态和业务状态保持一致。
- 日志必须包含 projectId、modelId、runId、workerId 等关联 ID。
- 不记录密码、许可证、完整模型内容或敏感本机路径。

## 6. 首版部署拓扑

首版只支持当前 Windows 单机：

```text
GRDP-Studio Vue :5173
        -> Spring Boot :8080
        -> Windows .NET Worker（仅本机受控接口）
        -> PIPESIM 2022.1 Python Toolkit
```

要求：

- 浏览器不直接访问 Worker。
- 浏览器不提交可执行命令或可信本机绝对路径。
- Spring Boot 是任务和数据状态的唯一入口。
- Worker 位于 `GRDP-Studio/worker`。
- Worker 由当前登录用户以后台进程运行，不安装为 Windows Service。
- Worker 纳入 `start-grdp-ahks.bat` 和 `stop-grdp-ahks.bat`。
- 一键启动必须检查 Worker 和 PIPESIM 能力健康状态。
- 当前许可证环境始终可用，脚本不启动或修改许可证服务，只做只读探测。
- 接口保留未来改为远程 Worker、注册、心跳和任务领取的扩展边界。

## 7. 软件项目与左侧树

### 7.1 独立软件项目

软件集成维护独立项目，不直接关联现有 GRDP 项目、气藏或井，也不向解析融合数据库写入结果。

首版项目操作：

- 新建。
- 重命名。
- 删除。
- 暂不支持整项目导入和导出。

### 7.2 删除规则

- 删除项目进入回收站 30 天。
- 回收站期间项目、模型和记录可恢复。
- 30 天后物理清理相关模型和结果文件。
- 有运行中任务的项目不能删除。

### 7.3 左侧树

交互参考 Avalonia，视觉严格遵循 GRDP 当前页面规范：

```text
未打开项目
```

或：

```text
软件项目名称
└── 井筒模型
    ├── 模型A.pips
    └── 模型B.pips
```

规则：

- 项目根默认展开。
- 无资源时显示不可点击的“暂无项目资源”。
- 单击节点只改变选择。
- 双击模型打开或激活中央模型页面。
- 不扫描和展示任意文件系统内容。
- 不创建没有真实功能的占位节点。
- 模型版本、Study、运行历史和结果放在中央页面，不继续加深左侧树层级。
- 进入软件集成工作区时使用独立软件项目树，不混入解析融合节点。

## 8. 首版 PIPESIM 范围

### 8.1 目标版本和模型

- PIPESIM 2022.1。
- 支持严格黑油单井。
- 支持 Avalonia 已批准的 CSW_102 型基础气井。
- 不支持复杂气井、水平井、人工举升和其他未被 Avalonia 批准的模型。
- `PIPESIM Network` 指 PIPESIM 2022.1 内置 Network Simulation，不是独立产品。

### 8.2 首版计算能力

- 节点分析。
- PT 剖面。
- 节点分析和 PT 剖面组合运行。
- 只允许选择模型已有 Study。
- 首版不允许网页覆盖模型参数。
- 首版不包含敏感性分析。
- 首版不包含模板模型创建。
- 首版不包含 PIPESIM Network 和 ECLIPSE 的正式迁移实现。

### 8.3 并发与超时

- PIPESIM 全局严格单任务。
- 其他验证或运行任务排队等待。
- 默认运行超时 10 分钟，可通过配置调整。
- 支持取消排队任务。
- 支持取消运行任务。
- 运行取消失败时终止并重启 Python Worker。

## 9. 模型上传和版本

### 9.1 上传类型

支持：

- 单个 `.pips` 文件。
- 包含主 `.pips`、可选 `.pipr` 和其他相对依赖的 ZIP 模型包。

ZIP 规则：

- 默认只允许一个主 `.pips`。
- 拒绝绝对路径、`..` 路径穿越、符号链接和重解析点逃逸。
- 限制压缩包大小、解压总大小、文件数量和目录深度。
- 上传文件最大 500MB。

### 9.2 模型版本

- 同一项目再次上传同名模型时创建新版本。
- 历史版本和历史运行记录保持绑定。
- 新任务默认选择最新 READY 版本。
- 用户可以明确选择旧版本复算。
- 模型版本默认长期保留，除非用户删除项目或模型。

### 9.3 异步验证

上传完成后立即创建异步验证任务：

```text
UPLOADED
    -> VALIDATING
    -> READY
    -> INVALID
    -> ENVIRONMENT_ERROR
```

Worker 验证：

- PIPESIM/PTK 环境。
- 模型可打开。
- 模型属于首版支持范围。
- 模型类型是黑油单井或批准的基础气井。
- Study 列表可读取。

只有 READY 模型可以创建运行任务。

## 10. 文件存储

默认根目录：

```text
C:\GRDP-Data
```

建议结构：

```text
C:\GRDP-Data
├── models
│   └── <modelId>\<versionId>
├── jobs
│   └── <runId>
│       ├── input
│       ├── work
│       └── output
├── artifacts
│   └── <runId>
└── logs
```

要求：

- 路径通过配置提供，代码不写死。
- 文件不放在 Git 仓库、桌面目录或 MySQL BLOB 中。
- MySQL 只保存元数据、状态、校验值和相对存储键。
- 每个任务使用独立工作目录。
- 不允许直接修改原始上传模型版本。

## 11. 任务状态和恢复

运行任务状态：

```text
CREATED
QUEUED
CLAIMED
PREPARING
RUNNING_NODAL
RUNNING_PROFILE
COLLECTING
SUCCEEDED
FAILED
CANCEL_REQUESTED
CANCELLED
TIMED_OUT
WORKER_LOST
```

规则：

- 任务状态持久化到数据库。
- 排队任务在平台重启后继续排队。
- 重启时处于运行、准备或收集状态的任务标记为 `WORKER_LOST` 或失败。
- 不自动重试被中断的模拟器任务。
- 用户可以基于原参数快照手动重试。
- 前端显示真实阶段和已用时间，不伪造求解百分比。
- 组合运行明确显示节点分析和 PT 剖面阶段。

## 12. 结果与 Artifact

### 12.1 页面结果

- 展示 Avalonia DTO 语义一致的节点分析曲线。
- 展示 PT 剖面表格和曲线。
- 展示运行状态、阶段、耗时、错误和清理结果。
- 展示模型版本、Study、运行类型和运行历史。

### 12.2 原始结果包

完整结果 Artifact 包括：

- PTK 原始响应 JSON。
- Worker 运行日志。
- 任务参数快照。
- PIPESIM 在任务目录中新生成的文件。
- 文件名、大小和 SHA-256 Artifact 清单。

不包括：

- 许可证文件或许可证内容。
- 密码和会话 Cookie。
- 本机敏感配置。
- 重复的输入模型副本；输入模型由 modelVersion 引用。

### 12.3 保留策略

- 模型版本长期保留。
- 解析结果和原始结果包保留 30 天。
- 数据库中的任务记录和审计信息长期保留。
- 临时工作目录在结果成功发布后可提前清理。
- Artifact 到期后页面显示已过期，不删除任务历史。

## 13. 建议数据库表

使用 snake_case，统一 `software_integration_*` 前缀：

```text
software_integration_project
software_integration_model
software_integration_model_version
software_integration_model_study
software_integration_run
software_integration_run_event
software_integration_artifact
software_integration_worker
software_integration_worker_capability
```

建议公共字段：

```text
id
status
version
created_at
updated_at
created_by
updated_by
deleted_at
```

首版单用户使用 `administrator`，但保留审计字段，后续再增加多用户项目权限。

## 14. 建议 API 范围

### 14.1 浏览器 API

```text
POST   /software-integration/projects
GET    /software-integration/projects
GET    /software-integration/projects/{projectId}
PUT    /software-integration/projects/{projectId}
DELETE /software-integration/projects/{projectId}

POST   /software-integration/projects/{projectId}/models
GET    /software-integration/models/{modelId}
POST   /software-integration/models/{modelId}/versions
GET    /software-integration/model-versions/{versionId}

POST   /software-integration/model-versions/{versionId}/runs
GET    /software-integration/runs/{runId}
GET    /software-integration/runs/{runId}/events
POST   /software-integration/runs/{runId}/cancel
POST   /software-integration/runs/{runId}/retry
GET    /software-integration/runs/{runId}/artifacts
```

实际 URL 可在详细设计中按 GRDP 现有 Controller 规范调整，但资源关系和职责不得混入其他业务 API。

### 14.2 Worker API

首版为本机受控接口，至少包括：

```text
GET  /worker/health
GET  /worker/capabilities
POST /worker/models/validate
POST /worker/runs/execute
POST /worker/runs/{runId}/cancel
GET  /worker/runs/{runId}
```

Spring Boot 负责持久任务状态；Worker 不作为业务数据库的直接写入者。

## 15. 验收基线

真实模型：

```text
C:\Program Files\Schlumberger\PIPESIM2022.1\Case Studies\Well Models\CSW_101_Basic Oil Well.pips
C:\Program Files\Schlumberger\PIPESIM2022.1\Case Studies\Well Models\CSW_102_Basic Gas Well.pips
```

验收要求：

1. 使用同一模型、同一 Study 和同一运行类型。
2. Avalonia 重新生成并保存基线结果。
3. 比较节点分析 IPR/VLP 曲线。
4. 比较 PT 剖面各序列。
5. 比较单位、点顺序、缺失值和状态。
6. 比较组合运行与独立运行结果语义。
7. 验证模型原文件未被修改。
8. 验证取消、超时和 Worker 重启后的清理。
9. 验证结果 Artifact 可下载且清单校验通过。
10. 验证软件集成改动没有影响解析融合及其他页面。

仓库当前只记录了 CSW_101/CSW_102 的历史通过结论，没有保存基线结果文件；实施时必须重新生成可比较的脱敏黄金结果。

## 16. 分阶段开发顺序

### 阶段 0：保护基线

- 固化 Avalonia 解析和 DTO 行为。
- 生成 CSW_101、CSW_102 黄金结果。
- 建立 Worker 解析回归测试。

### 阶段 1：空任务闭环

- 软件项目 CRUD。
- Worker 健康和能力探测。
- 持久任务状态。
- 排队、阶段事件、取消、失败和恢复。
- 使用 dry-run Adapter 验证端到端链路。

### 阶段 2：模型管理

- `.pips` 和 ZIP 上传。
- Artifact 存储。
- 模型版本。
- 异步验证。
- Study 读取。
- 左树与中央模型详情。

### 阶段 3：PIPESIM 单井运行

- 节点分析。
- PT 剖面。
- 组合运行。
- 阶段进度。
- 结果展示和下载。
- 取消、超时、清理和重启恢复。

### 阶段 4：后续能力

- 敏感性分析。
- 模板模型创建。
- PIPESIM Network。
- ECLIPSE。
- 多用户权限。
- 远程或多计算节点。
- 显式发布结果到解析融合。

阶段 4 内容不属于当前首版，不得提前混入首版实现。

## 17. 当前非阻塞环境核对项

以下内容在实施前通过自动探测和构建确认，不需要改变产品范围：

- 当前机器的 .NET 10 SDK 是否满足 Worker 构建。
- Python 3.8/3.9 实际路径。
- PIPESIM 2022.1 PTK 模块实际路径。
- 当前许可证探测结果。
- Worker 本机端口及冲突策略。
- `C:\GRDP-Data` 磁盘空间和目录权限。
- Spring 数据库迁移执行机制。
- CSW_101/CSW_102 的具体 Study 名称和黄金结果文件。

这些是环境验证项，不得成为修改其他业务代码的理由。

## 18. 完成定义

首版只有同时满足以下条件才算完成：

- 用户可创建独立软件项目。
- 用户可上传 `.pips` 或 ZIP，并形成模型版本。
- Worker 可异步验证 CSW_101/CSW_102 类型模型并读取 Study。
- 用户可选择 Study，提交节点分析、PT 剖面或组合任务。
- PIPESIM 严格单任务执行，其他任务排队。
- 支持取消、10 分钟超时和异常重启恢复。
- 页面显示真实阶段、曲线、剖面、运行历史和错误。
- 原始结果包可下载。
- 文件保留、回收站和 30 天清理策略生效。
- Worker 纳入统一一键启停。
- 黄金测试证明迁移前后解析结果一致。
- 解析融合和其他非软件集成功能没有行为回归。
