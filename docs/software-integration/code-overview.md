# GRDP-Studio 软件集成代码导读

本文面向第一次阅读本项目代码的开发者。目标是先建立整体认识，再进入具体文件。它重点解释软件集成工作台：PIPESIM 井筒、PIPESIM Network 和 ECLIPSE 100 如何从浏览器一路调用到本机模拟器，并把结果显示回来。

## 1. 先记住一条主线

```text
浏览器 Vue
   │ 只访问 Spring Boot
   ▼
Spring Boot 后端 :8080
   │ 保存项目、模型、Run、事件和结果
   │ 通过本机 HTTP 调用
   ▼
模拟器执行服务 Worker :5150
   │ 调用 PIPESIM PTK 或 ECLIPSE eclrun
   │ 解析并归一化结果
   ▼
Worker 返回结构化结果
   │
   ▼
Spring Boot 保存结果
   │
   ▼
浏览器查询 Run 并展示曲线、拓扑、表格和历史
```

这里有三个容易混淆的角色：

- **Vue** 是页面，负责让用户上传模型、点击计算、看状态和看结果。
- **Spring Boot** 是业务后台，负责项目、模型版本、任务状态、数据库和结果记录。
- **Worker** 是运行在安装了模拟器的 Windows 电脑上的执行服务，负责真正调用 PIPESIM 或 ECLIPSE。

浏览器不会直接访问 Worker，也不会直接启动 PIPESIM。Worker 也不直接写业务数据库。

## 2. 从用户操作看完整流程

### 2.1 创建项目和上传模型

用户在软件集成工作台创建项目，然后上传文件：

- PIPESIM 当前演示主路径：单个 `.pips`。
- ECLIPSE 当前路径：单个 `.DATA`，拒绝 ZIP、目录包和 `INCLUDE`。
- ZIP 模型包的完整解包验证属于后续范围。

浏览器把文件作为 `multipart/form-data` 发给 Spring Boot：

```text
POST /software-integration/projects/{projectId}/models
```

后端会保存模型版本的元数据，例如原始文件名、大小、SHA-256、状态和相对存储键。文件内容放在配置的存储目录，不放进 MySQL BLOB。

上传后的版本通常经历：

```text
UPLOADED → VALIDATING → READY
                     ├→ INVALID
                     └→ ENVIRONMENT_ERROR
```

PIPESIM 模型由 Worker 打开并读取 Study、模型类型和必要结构；ECLIPSE DATA 先做受控静态检查，不读取 PIPESIM Study。

### 2.2 创建一次计算 Run

只有 `READY` 模型版本可以创建 Run。PIPESIM 要选择模型已有的 Study；ECLIPSE 不需要 Study。

浏览器调用：

```text
POST /software-integration/model-versions/{versionId}/runs
```

PIPESIM 请求示例：

```json
{
  "study": "Study 1",
  "runType": "nodal",
  "parameters": null
}
```

当前已批准的井筒参数方案会通过 `parameters` 传递，但只允许后端白名单字段；空参数仍表示基准计算。Network 使用 `runType: "network"`。ECLIPSE 使用固定的 ECLIPSE 运行类型，不伪造 PIPESIM Study。

Spring Boot 创建持久 Run，返回 `runId`。之后由后端调度 Worker，长时间模拟器调用不会占用数据库事务。

### 2.3 Worker 执行计算

Spring Boot 调用本机 Worker：

```text
POST http://127.0.0.1:5150/api/runs/execute
```

Worker 接收的是相对存储键、SHA-256、Study、运行类型、参数快照和超时，不接收浏览器传来的可执行命令或任意本机路径。

PIPESIM 的基本请求形态：

```json
{
  "runId": 101,
  "modelStorageKey": "models/12/34/model.pips",
  "expectedModelSha256": "...",
  "study": "Study 1",
  "runTask": "combined",
  "parameters": null,
  "timeoutSeconds": 600
}
```

Worker 会：

1. 在该 Run 的隔离目录准备输入副本。
2. 获取 PIPESIM 全局执行锁，保证许可证不会被并发占用。
3. 启动 Python PTK 或官方 ECLIPSE `eclrun`。
4. 记录结构化阶段事件。
5. 解析结果并按冻结合同归一化。
6. 清理进程树和临时目录。
7. 返回结果、错误、Artifact 元数据和清理状态。

PIPESIM 井筒常见阶段包括：

```text
CREATED → QUEUED → CLAIMED → PREPARING
        → RUNNING_NODAL / RUNNING_PROFILE / RUNNING_NETWORK
        → COLLECTING → SUCCEEDED
```

组合运行中如果节点结果真实有效但剖面失败，可以是 `PARTIAL_SUCCEEDED`；页面必须明确标为部分真实结果，不能当成完整成功。

ECLIPSE 成功必须同时满足：`eclrun` 退出码正确、本次生成新鲜 `.ECLEND`、Errors/Problems/Bugs 均为零，且没有受控 License/Fatal 诊断。RSM 没有生成或无法解析时，页面显示缺失说明，不补造曲线。

### 2.4 浏览器查询和展示

浏览器不会等待一个长 HTTP 请求直到模拟器结束，而是用 `runId` 轮询持久状态：

```text
GET /software-integration/runs/{runId}
GET /software-integration/model-versions/{versionId}/runs?limit=50
POST /software-integration/runs/{runId}/cancel
```

页面根据状态决定是否继续轮询。进入终态后停止轮询；组件卸载、切换模型或切换模拟器时会清理定时器，避免旧模型的响应覆盖新模型。

## 3. 代码目录怎么对应这条主线

### 3.1 后端 `backend/`

软件集成代码位于：

```text
backend/src/main/java/com/grdp/studio/softwareintegration/
├── controller/   对外 HTTP 接口，只做参数绑定、校验和响应包装
├── dto/          请求和响应对象，不直接暴露数据库实体
├── entity/       项目、模型、版本、Run、事件、Artifact 数据表映射
├── mapper/       MyBatis-Plus 数据访问
├── service/      项目、模型、能力和 Run 的业务入口
├── execution/    Run 调度、状态机、结果校验和持久化执行流程
├── client/       调用 Worker 的 HTTP 客户端
├── artifact/     结果文件元数据和受控发布
└── support/      路径归一化、诊断脱敏、DATA 检查和 Schema 初始化
```

最先阅读的文件：

- `controller/SoftwareIntegrationController.java`：项目、上传、能力接口。
- `controller/SoftwareIntegrationRunController.java`：创建、查询、取消和历史接口。
- `service/impl/SoftwareIntegrationServiceImpl.java`：项目和模型版本管理。
- `service/impl/SoftwareIntegrationRunServiceImpl.java`：Run 创建和查询入口。
- `execution/SoftwareIntegrationRunDispatcher.java`：把持久 Run 交给 Worker 执行。
- `execution/SoftwareIntegrationRunStore.java`：Run 状态、事件和结果的保存。
- `client/HttpWorkerRunClient.java`：Spring Boot 到 Worker 的调用和响应转换。
- `execution/PipesimWellResultValidator.java`、`PipesimNetworkResultValidator.java`、`EclipseSummaryResultValidator.java`：防止不符合合同的结果进入页面。

数据库迁移在：

```text
backend/deploy/mysql/migrations/
```

主要表的关系可以理解为：

```text
Project
  └─ Model
      └─ ModelVersion
          └─ Run
              ├─ RunEvent
              └─ Artifact
```

Run 绑定具体的 `projectId`、`modelId` 和 `modelVersionId`，所以历史结果不会串到另一个模型。

### 3.2 Worker `worker/`

```text
worker/
├── Program.cs                 HTTP 服务入口和依赖注册
├── Contracts/                 Worker 请求、状态、结果和错误合同
├── Execution/                 排队、锁、进程监督、PIPESIM/ECLIPSE 执行
├── Inspection/                ECLIPSE DATA 静态检查
├── Storage/                   存储根目录、相对键和 Artifact 文件
├── ptk_validate.py            PIPESIM 模型验证
├── ptk_run.py                 PIPESIM 井筒运行
├── ptk_network.py             PIPESIM Network 运行和结果整理
├── ptk_normalization.py       曲线、剖面、网络数据归一化
└── tests/                     C#、Python 和 Golden 测试
```

Worker API 可以先看 `worker/README.md`。核心接口是：

```text
GET  /api/health
GET  /api/capabilities
POST /api/models/validate
POST /api/runs/execute
GET  /api/runs/{runId}
POST /api/runs/{runId}/cancel
```

Worker 不保存项目业务状态，只负责执行和返回观察结果。Spring Boot 才是 Run 的最终业务事实来源。

### 3.3 前端 `vue/`

软件集成前端位于：

```text
vue/src/
├── api/softwareIntegration.js             HTTP 请求封装
├── stores/softwareIntegration.js          Pinia 状态、轮询、模型切换
└── views/SoftwareIntegration/
    ├── SoftwareIntegrationWorkspace.vue   左侧项目树和中央工作区
    ├── PipesimModelRunPage.vue             井筒、Network、ECLIPSE 的运行容器
    ├── PipesimNodalResult.vue              IPR/VLP 曲线
    ├── PipesimProfileResult.vue            PT 剖面
    ├── PipesimNetworkResult.vue            网络拓扑和支路结果
    ├── EclipseDataInspectionOverview.vue  DATA 检查
    ├── EclipseRunResult.vue                ECLEND、RSM 和 Artifact 展示
    └── PipesimRunHistory.vue               运行历史
```

前端通常不直接在每个页面里写完整的请求流程，而是通过 `useSoftwareIntegrationStore()` 统一处理：

- 当前项目、模型、版本和模拟器类型。
- 项目详情和模型验证轮询。
- Run 创建、当前 Run、历史 Run 和轮询。
- 取消 Run、错误信息和能力状态。
- 切换模型时丢弃旧请求的结果。

`vue/src/api/softwareIntegration.js` 是请求地址的集中位置。页面组件负责显示，Store 负责状态协调，API 文件负责 HTTP 调用。

## 4. 接口和数据格式怎么读

### 4.1 Spring Boot 的统一响应

普通软件集成接口通常包装成：

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

创建 Run 可能返回 HTTP 201；Worker 接受执行可能返回 HTTP 202。这两个 HTTP 状态表示“已创建/已接受”，不表示计算已经成功。真正结果要等后续查询 Run。

### 4.2 PIPESIM 井筒结果

页面会检查结果合同后再显示。核心结构类似：

```json
{
  "schemaVersion": "pipesim-well-result/1",
  "model_kind": "black_oil_liquid",
  "runTask": "combined",
  "resultContract": "VALID_FULL",
  "units": {
    "flow": { "displayUnit": null, "semantics": "unspecified" },
    "pressure": { "displayUnit": null, "semantics": "unspecified" }
  },
  "ipr": [{ "flow": 1.0, "pressure": 2.0 }],
  "vlp": [{ "flow": 1.0, "pressure": 2.0 }],
  "profile": [{ "depth": 1.0, "pressure": 2.0, "temperature": 3.0 }]
}
```

前端的 `PipesimNodalResult.vue` 读取 `ipr` 和 `vlp`，`PipesimProfileResult.vue` 读取 `profile`。如果数组为空、字段不合法或合同与 Run 不匹配，页面不会强行绘图。

### 4.3 PIPESIM Network 结果

Network 结果使用 `pipesim-network-result/1`，包括：

- `topology`：节点、连接、Source、Sink、Flowline 等。
- `system`：系统级变量。
- `node`：节点变量。
- `profiles`：每条返回支路的距离、压力、温度等序列。
- `summary`、`messages`、`quality`：诊断和缺失值说明。

`VALID_FULL` 表示完整结果合同通过；`VALID_PARTIAL` 表示只保留了经过安全校验的真实部分结果。页面会显示“部分真实计算结果”，不会补造缺失拓扑或数值。

### 4.4 ECLIPSE 结果

ECLIPSE 页面主要展示：

- DATA 文件的静态检查信息。
- ECLEND 的 Comments、Warnings、Problems、Errors、Bugs。
- 受控诊断。
- 输出文件的大小、SHA-256 和 Artifact 元数据。
- 本次真实 RSM 解析出的 Summary 序列。

ECLIPSE 没有 PIPESIM Study。没有可用 RSM 时，页面明确显示“未返回可用 Summary”，不会用示例曲线代替。

## 5. 一次请求在代码中如何追踪

以“点击 PIPESIM 节点分析”为例，可以按以下顺序定位：

1. 在 `PipesimModelRunPage.vue` 找到运行按钮和 `store.createRun(...)`。
2. 在 `stores/softwareIntegration.js` 找到 `createRun`，确认它读取了当前 `versionId`、Study、运行类型和参数。
3. 在 `api/softwareIntegration.js` 找到 `createRun` 的 HTTP 地址。
4. 在 `SoftwareIntegrationRunController.java` 找到后端入口。
5. 进入 `SoftwareIntegrationRunServiceImpl.java`，看 READY 检查、版本绑定和 Run 创建。
6. 进入 `SoftwareIntegrationRunDispatcher.java`，看何时调用 Worker。
7. 进入 `HttpWorkerRunClient.java`，看发给 Worker 的请求字段。
8. 在 `worker/Program.cs` 和 `Execution/` 找路由、队列、PIPESIM 调用和事件状态。
9. 在 `ptk_run.py`、`ptk_normalization.py` 找实际 PTK 调用和结果字段。
10. 返回后在 `PipesimWellResultValidator.java` 和前端 `validateWellResult` 找结果校验。
11. 最后看 `PipesimNodalResult.vue` 或 `PipesimProfileResult.vue` 如何把数组交给 ECharts 或表格。

遇到问题时，先用 `runId` 查后端 Run，再用 `modelVersionId` 查模型版本，不要先猜页面问题。Run 的状态、事件、错误和 cleanup 信息比日志文本更可靠。

## 6. 启动和排查的最短路径

服务启动后，先检查：

```text
Spring Boot: http://127.0.0.1:8080/actuator/health
Vue:         http://127.0.0.1:5173/login
Worker:      http://127.0.0.1:5150/api/health
能力探测:    http://127.0.0.1:5150/api/capabilities
```

排查顺序建议：

1. 页面是否能登录并进入软件集成工作台。
2. Spring Boot health 是否正常。
3. Worker health 和 capabilities 是否正常。
4. 模型是否是 `READY`，模型类型是否正确。
5. Run 是否创建并进入 `QUEUED`、`RUNNING_*` 或终态。
6. Run detail 中的 `error.category`、`error.code`、事件和 cleanup 是否说明原因。
7. 结果是否通过对应的后端和前端合同校验。

常见判断：

- Worker 不可达：先查 Worker 进程、端口 5150 和本机配置。
- License/环境错误：不应把模型标成 INVALID，也不应伪造成功结果。
- Run 已成功但页面没结果：检查 Run detail、`resultContract`、模型版本绑定和前端校验。
- 页面一直 loading：检查轮询是否仍在运行、Run 是否已进入终态，以及组件是否切换了模型。
- 旧结果串到新模型：检查 `runId`、`modelId`、`modelVersionId` 的一致性保护。

## 7. 这个项目目前的边界

当前优先保证三条可展示、可计算路径：

```text
PIPESIM 井筒：CSW_101 / CSW_102
PIPESIM Network：CSN_302
ECLIPSE 100：BRILLIG.DATA
```

当前仍有一些完整产品功能没有做完，例如 ZIP 解包验证、Artifact 下载、回收站恢复、到期清理、持久验证队列和多计算节点。这些不会改变已经实现的主调用链，也不应在阅读当前代码时误认为已经完成。

阅读代码时优先关注：

1. 一条真实计算链路能否从浏览器走到模拟器再回来。
2. Run 是否持久化并能恢复。
3. 结果是否经过合同校验、保留单位和缺失值语义。
4. 浏览器是否只访问 Spring Boot。
5. 源模型是否保持不变，临时计算目录和敏感路径是否受到保护。

这五点基本覆盖了本项目“如何调用、如何传数据、如何计算、如何展示、如何追踪问题”的核心。
