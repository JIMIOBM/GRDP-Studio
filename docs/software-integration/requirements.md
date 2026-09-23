# GRDP-Studio 软件集成首版需求与技术基线

## 1. 文档目的

本文档是 GRDP-Studio 软件集成功能后续设计、开发、测试和验收的依据。

本文档只约束“软件集成”范围。除接入软件集成入口所需的最小共享改动外，不得修改解析融合、PVT、产能分析、项目业务、原 GRDP 平台及其他既有功能的行为。

## 0. 当前第一优先级：甲方可演示闭环

当前阶段的第一目标不是先完成全部工程级功能，而是尽快形成可向甲方现场演示的 B/S 闭环。ECLIPSE、PIPESIM 井筒和 PIPESIM Network 必须在同一个软件集成工作台中具备清晰、可点击、可展示的完整路径：进入对应入口、选择模型、提交验证或计算、显示运行状态、显示成功/失败结果和历史记录。

Demo-first 要求：

1. 三类软件入口必须可达，不能显示“功能正在开发中”，不能只改变一行提示文字。
2. 上传、验证、运行和结果展示必须形成真实可操作链路；失败时必须显示明确原因，不能无限 loading 或无限轮询。
3. 演示优先保证主路径可用和界面表达清晰，再逐步补充完整解析、批量任务、复杂模型和高级工程分析。
4. 演示不得伪造模拟器结果、伪造成功状态、绕过 Spring Boot/Worker 架构、直接从浏览器启动本机程序，或将固定示例数据冒充用户模型计算结果。
5. 如果本机许可证、安装、模型或运行环境导致真实计算无法完成，产品必须在页面上明确显示环境/模型失败状态；允许使用明确标注的 Demo fixture 仅用于界面演示，但不得标记为真实计算结果。
6. 每个迁移阶段都必须优先交付一条可点击演示路径，并在 `PROGRESS.md` 记录已可演示、仅可展示、尚未可用和环境限制。

### 0.1 当前交付顺序与演示验收

按以下顺序安排当前工作：修复导入、验证、计算、结果与历史链路的阻塞；改善计算入口及结果可读性；补充与本次变更相关的回归保护。当前代码已经提供 PIPESIM/ECLIPSE ZIP 工程包安全边界、依赖复制、回收站恢复、Artifact 下载与到期清理等公共能力；真实依赖工程仍需在许可证环境中单独验收，不能把单元测试当成现场计算证据。远程、多节点等后续能力继续遵守阶段 6 边界。

每个演示工作包写明用户可观察目标、影响范围和必要验证。宣称某条路径“可现场演示”须在对应部署版本的浏览器中验证：

- 对应入口可达，支持的模型能上传、验证并绑定正确版本；PIPESIM 选择已有 Study，ECLIPSE 不需要 Study。
- READY 版本能提交实际计算，页面显示持久任务状态，成功、失败、部分成功均按真实合同呈现，终态不无限轮询。
- 返回的真实曲线、表格、拓扑或 ECLIPSE Summary 可查看；缺失数据明确说明，不生成替代值。
- 切换模型或重新进入页面可恢复正确版本的历史及结果，活动任务按实际状态继续查询。
- 计算控件及主要结果在目标桌面视口可操作、可读；默认检查 1440x900 和 1280x720。诊断和审计信息可访问，不挤占主要结果区域。

构建、Mock 浏览器交互测试、真实模拟器计算、部署后浏览器检查分别记录。必要证据包含代码/部署版本、模型版本或校验值、Run ID、结果及未覆盖项。历史真实运行可用于结果展示验收，但不证明变更后的执行代码已成功计算。环境失败可证明错误处理路径，不能替代成功计算验收；明确标注的 Demo fixture 只能证明界面展示。

纯文档修改检查一致性和引用；代码修改执行受影响层构建及相关测试。界面小改不要求重跑全部模型；计算合同、解析、状态或进程管理变更应验证受影响的模拟器路径。真实 PIPESIM 运行保持全局串行，不打断活动任务，不因重复审查重复计算。复用与当前代码和环境仍适用的验证证据，遵守用户已明确的停止条件。

演示工作包满足其目标和必需验证后可标记通过，其他缺口继续记录。此结论不代表第 18 节的完整首版交付完成。结果真实性、源模型不变、安全边界、许可证串行及原业务隔离不得因演示优先而放宽。

### 2026-09-15 授权扩展：PIPESIM 井筒方案参数

用户已明确批准扩展原首版参数限制，新增“读取井筒参数 → 网页编辑 → 独立方案副本计算 → 与基准结果比较”的闭环。本授权优先于第 8.2 节关于井筒参数覆盖的原首版限制，不开放任意字段、任意命令或源模型写入。

- 首批仅针对已支持的黑油单井和基础气井，继续使用已有 Study 和 nodal/profile/combined 任务。
- 字段须根据本地官方 Toolkit 和只读桌面实现核实，明确单位、适用模型、范围与读回验证后才能开放编辑，不猜测单位。
- Spring Boot 持久化方案参数快照并随 Run 返回；Worker 仅在隔离副本应用白名单字段。源模型版本及校验值不变，空参数基准运行保留原行为。
- 各层拒绝非有限数值、未知字段和不适用模型的参数。设置失败不得回退默认值并报告方案计算成功。
- 结果显示方案来源和参数快照，以真实方案运行验证参数生效、结果可追溯、源文件不变及许可证释放。
- 当前方案任务矩阵为：基础气井支持 nodal/profile/combined；黑油液体井仅支持 nodal。基础气井的 profile/combined 按 Avalonia 语义将同一 `reservoirPressurePsi` 快照同时作为 PT `InletPressure`，并保留现有气体流量控制；其他模型或任务仍拒绝非空参数。该 PT 入口压力语义不推广为任意模型的通用地层压力等价。
- 不自动开放 Network 参数、ECLIPSE deck 编辑、批量敏感性、模板创建或跨模拟器耦合。

## 2. 已确认目标

在当前 Windows 主机上，将 Avalonia 桌面端已经实现的本地模拟器联动能力迁移到 B/S 架构，使浏览器可以通过 GRDP-Studio 后端提交任务，由本机模拟器执行服务（Worker）调用 PIPESIM 井筒、PIPESIM Network 及 ECLIPSE 100，并返回可追溯的计算结果。ECLIPSE 当前范围按第 8.4 节 MVP 合同执行。

首个完整闭环为：

```text
浏览器上传 PIPESIM 井筒或管网模型
    -> 后端保存模型版本
    -> Worker 异步验证模型并读取 Study
    -> 用户选择已有 Study 和与模型版本匹配的运行类型
    -> 后端创建持久任务
    -> Worker 调用本机 PIPESIM 2022.1 井筒或 Network Simulation
    -> Worker 使用冻结的解析逻辑生成结果
    -> 后端保存结果和 Artifact
    -> 浏览器展示井筒曲线/剖面或管网拓扑/支路剖面，以及诊断和运行历史
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
- ECLIPSE SMSPEC/UNSMRY 二进制 Summary 解析。
- ECLIPSE 仅生成 `S####` 分步 Summary 时，按分步文件顺序读取真实 Summary，不因缺少单一 UNSMRY 而伪造或丢弃结果。
- ECLIPSE EGRID 网格元数据索引（NX/NY/NZ/活动网格数）；不在 Run JSON 中展开大体场。
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
├── 井筒模型
│   ├── 模型A.pips
│   └── 模型B.pips
└── 管网模型
    └── 模型C.pips
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
- 支持包含上游 Source/Well、Sink、Flowline 和有效 Connection 的 PIPESIM 管网模型。
- 管网验收基线使用 PIPESIM 2022.1 官方 `CSN_302_Gas Transmission Network.pips`。

### 8.2 首版计算能力

- 节点分析。
- PT 剖面。
- 节点分析和 PT 剖面组合运行。
- PIPESIM Network 稳态管网模拟。
- 管网拓扑、系统结果、节点结果和全部返回支路剖面归一化。
- 只允许选择模型已有 Study。
- 网页参数覆盖仅按第 0 节授权白名单开放：当前只有井筒地层压力方案，按模型类型和运行任务矩阵执行；不得据此开放任意字段或源模型写入。
- 首版不包含敏感性分析。
- 首版不包含模板模型创建。
- ECLIPSE 100 MVP 按第 8.4 节独立合同交付，不使用 PIPESIM Study。

### 8.3 并发与超时

- PIPESIM 全局严格单任务。
- 其他验证或运行任务排队等待。
- 默认运行超时 10 分钟，可通过配置调整。
- 支持取消排队任务。
- 支持取消运行任务。
- 运行取消失败时终止并重启 Python Worker。

### 8.4 ECLIPSE 100 MVP

- 目标版本为 ECLIPSE 100 2024.1，Worker 通过官方 `eclrun.exe` 调用，不直接以 `eclipse.exe` 作为生产入口。
- 首版接受单个 `.DATA` 或包含一个主 `.DATA` 的安全 ZIP 工程包。`INCLUDE` 只能引用 ZIP 内、且相对于当前文件的依赖；绝对路径、包外路径、缺失依赖、循环依赖和不安全重解析点必须拒绝。
- 首版不提供 Study 选择、参数覆盖、模板生成或对输入 deck 的文本替换；一次运行只执行上传版本的隔离副本，浏览器不得构造本机路径或命令。
- 验证成功的版本使用 `modelKind=eclipse_100` 和 `simulatorType=ECLIPSE_100`；运行类型固定为 `eclipse`，不伪造 PIPESIM Study。
- 运行成功必须同时满足：`eclrun` 退出码为零、本次生成新鲜 `.ECLEND`、`.ECLEND` 中 Errors/Problems/Bugs 均为零，且受控诊断不含 License 或 Fatal 分类。
- 首版页面展示 ECLEND 计数、运行状态、受控诊断、输出 Artifact 清单和本次新鲜 `.RSM` 解析出的 Summary 序列；未生成或无法解析 RSM 不得伪造曲线。
- 取消时 Worker 必须确认进程树退出，并执行官方 `eclrun kill <case>` 和 `eclrun check <case>` 清理；清理状态随运行记录持久化。

## 9. 模型上传和版本

### 9.1 上传类型

PIPESIM 首版目标类型：

- 单个 `.pips` 文件。
- 包含主 `.pips`、可选 `.pipr` 和其他相对依赖的 ZIP 模型包。

当前最稳定的可计算演示路径使用单 `.pips` 或单 `.DATA`。PIPESIM 和 ECLIPSE ZIP 已完成安全解包与完整工程复制；ECLIPSE `INCLUDE` 依赖会在 Worker 中递归校验后以原始相对路径执行。真实含 INCLUDE 的现场模拟器验收仍需单独记录，不得用单元测试冒充现场许可证验收。

ECLIPSE 100 接受单 `.DATA` 或单主 `.DATA` ZIP 包，按第 8.4 节执行。

ZIP 规则：

- 默认只允许一个主 `.pips` 或一个主 `.DATA`。
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

PIPESIM Worker 验证：

- PIPESIM/PTK 环境。
- 模型可打开。
- 模型属于首版支持范围。
- 模型类型是黑油单井、批准的基础气井或满足必需拓扑约束的 PIPESIM Network。
- Study 列表可读取。
- Network Study 可通过 PIPESIM `networksimulation.validate`。

ECLIPSE 按第 8.4 节验证独立 DATA 输入边界并返回受控检查信息；不要求 PIPESIM/PTK 可用，不读取 Study。运行前单独检查 ECLIPSE 能力，静态 DATA 检查通过不能证明实际计算成功。

验证成功后必须将 `modelKind` 持久化到模型版本。历史 READY 版本不得通过可变的父模型类型推断；迁移前无法区分黑油和基础气井的历史井筒版本使用明确的 `legacy_well` 标记。

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
RUNNING_NETWORK
RUNNING_ECLIPSE
COLLECTING
SUCCEEDED
PARTIAL_SUCCEEDED
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
- 管网运行明确显示 `RUNNING_NETWORK` 阶段。
- ECLIPSE 运行明确显示 `RUNNING_ECLIPSE` 阶段。
- `PARTIAL_SUCCEEDED` 仅用于已通过受控部分结果校验的真实输出，页面明确标识，不冒充完整成功。

## 12. 结果与 Artifact

### 12.1 页面结果

- 展示 Avalonia DTO 语义一致的节点分析曲线。
- 展示 PT 剖面表格和曲线。
- 展示运行状态、阶段、耗时、错误和清理结果。
- 展示模型版本、Study、运行类型和运行历史。
- 管网结果展示有向拓扑、节点/连接统计、系统变量、节点变量和全部支路剖面。
- 管网支路可切换并绘制 PIPESIM 返回的距离、压力、温度、速度、密度和气体 Z 因子等序列。
- 展示 PIPESIM summary、messages 和结构化 quality 诊断。
- ECLIPSE 结果页展示 ECLEND、Summary、输出文件清单以及可解析的 EGRID 网格元数据；网格压力/饱和度场必须通过后续分页接口读取，不得塞入当前 Run JSON。
- ECLIPSE 允许下载 Worker 生成的受控二进制结果 Artifact（EGRID、INIT、UNRST、UNSMRY、SMSPEC 和 S####）；PRT/MSG 等文本诊断只展示安全元数据，不作为原始文件下载。
- ECLIPSE 结果契约可附带受限二进制场索引：文件、关键字、数据类型、数量、字节范围和 Fortran 数据分段；索引不包含场值，后续分页读取必须按索引范围访问。

### 12.2 管网结果契约

管网成功结果使用固定根契约：

```text
schemaVersion = pipesim-network-result/1
model_kind = network
runTask = network
resultContract = VALID_FULL
study = requested existing Study
simulationState = Completed
topology / system / node / profiles / summary / messages / quality
```

规则：

- `topology` 包含节点、连接及 nodes/edges/sources/sinks/flowlines 计数。
- `system` 和 `node` 保留 PTK 返回的全部变量、名称和值。
- `profiles` 保留 PTK 返回的全部支路；每支路必须有非空且等长的 `TotalDistance` 和 `Pressure`。
- 单位直接来自 PTK，不猜测、不换算未批准单位。
- 非有限值、PIPESIM 缺失哨兵 `1.2345e25`/`-1e31` 和数值型原生缺失值统一写为 `null`。
- 每个清洗后的数值 `null` 必须有且只有一个同路径 quality 项，code 为 `NON_FINITE` 或 `UNAVAILABLE`。
- 本机盘符路径及私有 `net.pipe` 标识必须在 Worker Artifact 和浏览器 API 返回前脱敏。

### 12.3 原始结果包

完整结果 Artifact 包括：

- PTK 原始响应 JSON。
- Worker 运行日志。
- 任务参数快照。
- PIPESIM 在任务目录中新生成的文件。
- 文件名、大小和 SHA-256 Artifact 清单。

ECLIPSE 例外：二进制结果只按受控文件名和 `application/octet-stream` 发布，下载名统一增加 `eclipse-output-` 前缀；PRT、MSG、RSM、ECLEND 等文本结果不直接发布，以避免泄露本机路径、主机和许可证诊断。

不包括：

- 许可证文件或许可证内容。
- 密码和会话 Cookie。
- 本机敏感配置。
- 重复的输入模型副本；输入模型由 modelVersion 引用。

### 12.4 保留策略

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
GET    /software-integration/runs/{runId}/artifacts/{artifactId}/download
GET    /software-integration/runs/{runId}/artifacts/{artifactId}/range?offset={offset}&length={length}
```

实际 URL 可在详细设计中按 GRDP 现有 Controller 规范调整，但资源关系和职责不得混入其他业务 API。`range` 仅允许受控 ECLIPSE 二进制 Artifact，服务端限制单次读取长度并返回 206；它不是任意文件读取接口。

### 14.2 Worker API

首版为本机受控接口，至少包括：

```text
GET  /api/health
GET  /api/capabilities
POST /api/models/validate
POST /api/runs/execute
POST /api/runs/{runId}/cancel
GET  /api/runs/{runId}
```

Spring Boot 负责持久任务状态；Worker 不作为业务数据库的直接写入者。

## 15. 验收基线

真实模型：

```text
C:\Program Files\Schlumberger\PIPESIM2022.1\Case Studies\Well Models\CSW_101_Basic Oil Well.pips
C:\Program Files\Schlumberger\PIPESIM2022.1\Case Studies\Well Models\CSW_102_Basic Gas Well.pips
C:\Program Files\Schlumberger\PIPESIM2022.1\Case Studies\Network Models\CSN_302_Gas Transmission Network.pips
```

验收要求：

1. 使用同一模型、同一 Study 和同一运行类型。
2. 核对已有冻结 Golden、metadata sidecar 与源校验值。重新捕获仅限明确批准的基线建立或变更任务，不得为通过比较而覆盖基线；保持 Avalonia 仓库只读，输出保存到批准的外部位置。
3. 比较节点分析 IPR/VLP 曲线。
4. 比较 PT 剖面各序列。
5. 比较单位、点顺序、缺失值和状态。
6. 比较组合运行与独立运行结果语义。
7. 验证模型原文件未被修改。
8. 验证取消、超时和 Worker 重启后的清理。
9. 验证结果 Artifact 可下载且清单校验通过。
10. 验证软件集成改动没有影响解析融合及其他页面。
11. 验证 CSN_302 自动识别为 `network`，错误井筒运行类型被拒绝。
12. 验证管网结果契约、全部支路、PTK 单位、quality-null 对应和浏览器展示。
13. 验证 API、事件、验证消息和 Artifact 不暴露本机敏感路径或私有管道标识。

仓库已保存 CSW_101/CSW_102 的六组脱敏 Golden 结果及 metadata sidecar。Network 以官方 CSN_302 的真实 B/S Run、Artifact 清单、源 SHA 和契约校验作为当前验收证据，不得用人工构造结果替代。

ECLIPSE 当前成功样本为官方 `BRILLIG.DATA`，以输入版本校验值、真实 Run、新鲜 ECLEND、清理确认、输出元数据及实际 RSM 序列作为证据。未返回 RSM 时必须明确说明，不能据此伪造曲线。按第 0.1 节选择本次受影响的验收路径；冻结 Golden 不为通过测试而重新生成或覆盖。

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

### 阶段 4：PIPESIM Network

- 管网模型自动识别和 Study 验证。
- Network Simulation 串行运行及 `RUNNING_NETWORK` 状态。
- 拓扑、系统、节点、支路剖面和 quality 结果契约。
- B/S 拓扑图、支路曲线、变量表和诊断展示。
- 官方 CSN_302 真实闭环验收。

### 阶段 5：ECLIPSE 100 MVP

- 单 `.DATA`、无 `INCLUDE` 的验证和隔离执行。
- `ECLRUN` 2024.1 能力探测、ECLEND/诊断成功判定、取消清理和 Artifact 发布。
- RSM Summary 解析及 B/S 结果展示。
- 独立 ECLIPSE 契约测试与一份真实成功 deck 的端到端验收。

### 阶段 6：后续能力

- 敏感性分析。
- 模板模型创建。
- ECLIPSE 参数覆盖和网格/二进制结果解析。
- 多用户权限。
- 远程或多计算节点。
- 显式发布结果到解析融合。

阶段 6 内容不属于当前 MVP，不得提前混入实现。

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
- CSN_302 的 Network Study、拓扑规模、支路结果和 source SHA-256。

这些是环境验证项，不得成为修改其他业务代码的理由。

## 18. 完整首版交付定义

本节定义完整首版交付；当前演示工作包按第 0.1 节验收，不因暂缓项未实现而否定已验证的局部结果。完整首版只有同时满足以下条件才算完成：

- 用户可创建独立软件项目。
- 用户可上传 `.pips` 或 ZIP，并形成模型版本。
- Worker 可异步验证 CSW_101/CSW_102 类型井筒模型及合规 Network 模型并读取 Study。
- 用户可选择 Study，提交节点分析、PT 剖面、组合任务或管网模拟。
- 用户可上传单个无 INCLUDE 的 ECLIPSE `.DATA`，验证后无需 Study 即可运行；按第 8.4 节判定成功，展示 ECLEND、实际 RSM 或明确缺失说明、历史及输出元数据。
- PIPESIM 严格单任务执行，其他任务排队。
- 支持取消、10 分钟超时和异常重启恢复。
- 页面显示真实阶段、井筒曲线/剖面、管网拓扑/支路结果、运行历史和错误。
- 原始结果包可下载。
- 文件保留、回收站和 30 天清理策略生效。
- Worker 纳入统一一键启停。
- 黄金测试证明迁移前后解析结果一致。
- 解析融合和其他非软件集成功能没有行为回归。
