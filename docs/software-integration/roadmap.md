# GRDP-Studio 软件集成后续路线图

## 1. 当前判断

当前已经完成的是三条可演示、可计算的主路径：

```text
PIPESIM 井筒：上传 .pips → 验证 → Study → nodal/profile/combined → 曲线和历史
PIPESIM Network：上传 .pips → 验证 → Study → Network Simulation/Optimizer → 拓扑、节点、支路、优化变量和历史
ECLIPSE 100：上传 .DATA → 检查 → eclrun → ECLEND/RSM → 结果和历史
```

这证明了浏览器、Spring Boot、Worker 和模拟器之间的主调用链已经打通，但不等于完整工程产品。后续开发应从演示闭环转向四个目标：

1. 让计算结果可追溯、可复算、可比较。
2. 让真实工程模型可以安全导入，而不是只支持演示样例。
3. 让失败、取消、超时、重启和部分结果有稳定行为。
4. 让三类模拟器共享项目、版本、Run、Artifact 和审计能力，但保持各自的输入和结果合同。

## 2. 推荐总顺序

不要同时把三个模拟器都扩展到很深。建议按下面顺序推进：

```text
阶段 0：演示基线和公共平台稳定
        ↓
阶段 1：PIPESIM 井筒工程能力
        ↓
阶段 2：PIPESIM Network 工程能力
        ↓
阶段 3：ECLIPSE 真实项目包和结果能力
        ↓
阶段 4：跨模拟器产品化、权限、清理和多节点
```

每个阶段都交付一条完整用户路径，不把工作拆成“只做接口”或“只做页面”。一个功能包必须至少包括输入、后端状态、Worker 执行、结果展示和对应测试。

## 3. 阶段 0：把 Demo 变成稳定基线

### 目标

让现有的 CSW_101、CSW_102、CSN_302 和 BRILLIG.DATA 成为可重复验收的固定基线。

### 工作项

- 把四类验收模型、源 SHA-256、版本号和已有 Run 证据记录清楚。
- 为每条路径整理一份“上传 → 验证 → 运行 → 结果 → 历史恢复”的操作清单。
- 把前端 Mock 测试、后端单元测试、Worker 测试和真实计算证据分开记录。
- 完成真实部署后的浏览器 smoke：模型切换、运行历史、结果显示和刷新恢复。
- 补充共享 Shell 的解析融合回归测试。
- 统一错误类别、错误消息和终态，不允许无限 loading 或无限轮询。
- 确认服务启动、Worker health、能力探测和端口检查可以重复执行。

### 阶段验收

- 四个验收模型都能在已部署版本中找到对应入口。
- 已有真实 Run 可以正常查看，不串模型、不串版本。
- 任何失败都能在页面看到可理解原因。
- 已完成的 ZIP 安全边界、Artifact 下载和清理功能要与尚未验收的依赖模型闭环分开记录，不冒充已完成。

## 4. PIPESIM 井筒路线

### P0：维持已有计算合同

已经完成的基础能力包括：

- `.pips` 上传和模型版本。
- 黑油液体井和 CSW_102 基础气井识别。
- Study 读取和选择。
- nodal、profile、combined。
- IPR/VLP 和 PT 剖面。
- Golden 结果比较。
- Run 状态、历史、取消代码路径和 Artifact 元数据。

后续首先不要重写解析器。以现有 Golden 和字段语义为基准，所有修改都要证明没有改变单位、点顺序、缺失值和部分结果语义。

### P1：井筒参数方案

当前已经批准的第一项是储层压力方案。基础气井已扩展到 nodal/profile/combined；黑油液体井保留 nodal-only。下一步按白名单逐项扩展：

1. 读取可编辑参数及官方单位。
2. 页面展示当前值、允许范围和适用模型。
3. Spring Boot 保存参数快照。
4. Worker 复制模型到隔离目录。
5. Worker 只在隔离副本修改白名单字段。
6. 写入后重新读取并确认参数真正生效。
7. 执行 nodal/profile/combined。
8. 结果页面展示方案参数和基准结果对比。
9. 验证源模型 SHA-256 不变。

基础气井的 profile/combined 会把同一压力快照作为 PT 入口压力，沿用 Avalonia 的气井 PT 调用方式；这不是对任意模型的单位换算或地层压力等价推断。

第一批只建议继续井筒工程中已确认的字段。每增加一个字段，都需要 Toolkit 读取、单位、适用范围和真实结果验证，不能根据字段名猜单位。

### P2：井筒工程结果完善

- 结果表格和曲线支持更清晰的单位显示；敏感性结果页可选择单个 IPR/VLP 工况查看原始点，并导出全量扫描点 CSV。
- 支持同一模型版本的基准 Run 与方案 Run 对比；当前页面叠加 IPR/VLP/PT 曲线，并按完全相同流量/深度坐标显示输入方案和“当前−对比”差值范围，不插值或换算单位。
- 支持曲线和剖面 CSV/图片导出。
- 展示输入版本、Study、运行类型、参数快照和结果来源。
- 对许可证不可用、模型不支持、计算失败、部分结果分别显示处理建议。
- 完成真实取消和超时的现场验收。

### 当前边界

- 井筒敏感性分析已按白名单落地，仍只允许已验证模型和有限变量，不等同于任意字段编辑。
- 模板模型创建、任意字段编辑、复杂气井、水平井和未被基线批准的人工举升模型仍未开放。
- 真实运行中的取消和超时仍需要在不影响共享许可证任务的受控环境中完成现场验收。

## 5. PIPESIM Network 路线

### P0：固定真实拓扑和结果语义

已经完成：

- Network 自动识别。
- Source/Well、Sink、Flowline、Connection 检查。
- Network Study 验证。
- Network Simulation。
- 拓扑、系统、节点和支路结果。
- 单位、缺失值和 quality 标记。
- 完整结果和部分真实结果。
- 拓扑图、设备符号、节点选择、支路剖面和导出；拓扑 CSV 保留 PTK 返回的节点、组件类型、连接源/目标和源端口。

拓扑图是前端绘制的，节点和连接数据来自 PTK 返回结果。后续应优先修正数据合同和交互，不要把拓扑图改成凭经验推断的示意图。

### P1：Network 工程交互

当前已落地支路工况总览：压力单位、点数、首末返回点、端点差值、有效范围和缺失点数，支持筛选、CSV 导出、分页和跳转支路剖面。首末点严格按模拟器返回顺序，不推断实际流向。

- 点击设备后显示完整节点变量和相关支路。
- 对连接显示源端、目标端、端口和返回方向。
- 支持多支路筛选、变量切换和单位提示。
- 对完整、部分、缺失数据显示不同状态。
- Network 系统/节点变量表和剖面明细已支持分页；筛选后的 CSV 仍导出完整真实点集。
- 支持拓扑布局保存、恢复和版本绑定。
- 检查拓扑图在 1440x900、1280x720 下是否可读。
- 已补充同 Study 历史 Run 的系统/节点变量对比；仅按变量、对象和完全相同单位对齐，显示当前值、历史值和当前减历史差值。
- 拓扑 CSV 与图上的 PTK 返回结果保持一致，不根据图形布局或端点邻近关系补推流向。

Network Optimizer 首个真实切片已落地：官方 `network_opt_sim.py` 的 `Study 1` 运行、官方稀疏变量结果、单位、不可用质量和分组 CSV 已接入；真实只读 Run 111 见 `docs/software-integration/acceptance/L11.md`。应用切片已接入 `/2 + applyResults=true`：官方 `apply_results()` 只写入隔离运行副本，随后显式 `model.save()` 持久化，并发布可下载副本 Artifact；真实 Run 114 已用官方 PTK 重新打开 Artifact 验证 GasRate 回读，验收见 `docs/software-integration/acceptance/L12.md`。

### P2：Network 真实模型兼容性

- 增加更多官方 Network 样例进行验证。
- 明确支持的设备类型和不支持的组件类型。
- 对复杂设备给出模型级拒绝原因。
- 支持安全的 Network ZIP 模型包后，再处理相对依赖文件。
- 对 Network Simulation 的验证失败和运行失败进行更细的诊断分类。

### P3：Network 工程结果

- 结果按节点、连接、支路、系统变量分类保存。
- 支持不同 Run 的 Network 结果对比。
- 支持不同 Run 的 Network 结果对比：当前已覆盖支路剖面、系统变量和节点变量；连接级结果仍以返回的支路/节点合同为准，不根据拓扑推断。
- 支持支路变量 CSV/图片导出。
- 支持异常节点和 quality 标记定位：仅对能与当前返回节点、变量、支路和点号唯一匹配的 `node.*` / `profiles.*` 路径提供定位；系统级标记保留为系统结果，不根据拓扑邻近关系猜测。
- 在结果页面保留原始单位，不在前端自行换算未批准单位。

### 暂不优先

- Network 参数批量敏感性和任意优化器参数编辑。
- Network Optimizer 结果写回原始模型：明确禁止；当前只允许应用到隔离方案副本，并要求保存、下载和重新打开验证。
- 自动生成网络模型。
- 根据图形位置推断实际流向。
- 把 Network 结果直接融合进原解析融合业务。

## 6. ECLIPSE 路线

### P0：稳定单 DATA MVP

已经完成：

- 单 `.DATA` 上传。
- 单 DATA 和单主 DATA ZIP 的安全工程包边界。
- DATA 静态检查。
- ECLIPSE 2024.1 能力探测。
- 隔离副本执行。
- 官方 `eclrun` 调用。
- 新鲜 ECLEND 检查。
- Errors/Problems/Bugs 成功门槛。
- License/Fatal 诊断分类。
- 取消、超时和清理代码路径。
- RSM Summary 解析。
- SMSPEC/UNSMRY 二进制 Summary 解析；对只生成 `S####` 分步 Summary 的工程，按文件序列读取并合并时间点。
- ECLEND、RSM、输出文件和 Artifact 元数据展示。
- BRILLIG.DATA 真实 B/S 成功证据。

### P1：真实单文件工程数据

先接入已经可以在本机 ECLIPSE 独立运行的真实 DATA 文件，要求：

- 单文件路径不含外部依赖。
- 不依赖外部文件。
- 本地 ECLIPSE 运行成功。
- ECLEND 中 Errors、Problems、Bugs 为零。
- 原始 DATA 上传后 SHA-256 不变。
- 页面能展示真实 ECLEND、RSM 或明确说明没有 RSM。

这一步主要验证真实数据不会改变当前执行链路。

### P2：真实项目包

真实油藏项目通常不是一个文件，而是：

```text
主 DATA
GRID / INIT / PVT / SCAL
INCLUDE 文件
其他相对依赖
```

因此需要新增：

- ZIP 上传。
- 安全解压。
- 主 DATA 识别。
- INCLUDE 依赖扫描。
- 禁止绝对路径和 `..` 路径穿越。
- 禁止符号链接和 reparse point 逃逸。
- 文件数量、目录深度和总解压大小限制。
- 全部依赖文件的 SHA-256 清单。
- 运行前依赖完整性检查。
- 依赖文件统一复制到 Run 隔离目录。

当前 Worker 已实现包内相对 INCLUDE 图校验、缺失/越界/循环/重解析点拒绝、完整工程复制和 v3 依赖清单（相对路径、大小、SHA-256）；Spring 会把已验证清单绑定到 ECLIPSE Run，Worker 在输入副本和工作副本启动前逐文件复核，并在结果发布前复核原始工程包，包发生变化时不启动或发布有效结果。仍需用真实含 INCLUDE 的 ECLIPSE 工程完成一次许可证环境现场验收。

### P3：真实工程结果

当前解析 ECLEND、RSM Summary，并对 EGRID 生成网格元数据索引。真实油藏项目后续通常还需要：

- EGRID 网格结果。
- INIT 初始状态。
- UNRST 动态结果。
- 压力场和饱和度场。
- 井级生产和注入结果。
- 井组结果。
- 时间序列和预测方案对比。
- 网格或剖面可视化。

当前已按受控 Artifact 发布 EGRID、INIT、UNRST、UNSMRY、SMSPEC 和 S#### 二进制结果，下载文件保留大小和 SHA-256；Worker 生成受限的二进制关键字/分段索引，并从 UNRST 的真实 SEQNUM 绑定时间步，结果页可按 200 个值分页读取选定字段，并对与网格全量/活动单元严格匹配的字段读取二维层切片。L14 已用真实 BRILLIG Run 117 验收：EGRID/INIT/UNRST 分别可索引，UNRST 时间步为 1–8；官方零长度 MESS 控制记录会被安全跳过，不再导致整个 UNRST 文件索引失败。二进制字段目录已支持按文件、时间步、关键字筛选和分页，切片面板与该索引共享真实字段合同，并已与同一受控时间步状态双向联动；每次只请求对应的二进制范围，选定层显示基于真实值的基础最小/最大色标，并可导出当前层真实值及 I/J 坐标 CSV。L15 在结果页复用 Three.js/OrbitControls，按真实 EGRID `COORD/ZCORN/ACTNUM` 绘制 corner-point 三维活动单元，消费 `COMPDAT/COMPDATM` 的 I/J/K 完井记录进行定位，并支持点击单元、修改派生场值和导出编辑草案；真实 BRILLIG Run 117 已现场验收三维 PRESSURE 读取与草案生成，含完井记录的 E2E 也已覆盖井定位。PRT/MSG/RSM/ECLEND 等文本输出继续只保留安全元数据。

### P4：ECLIPSE 方案计算

真实工程常见需求包括基准方案、注水方案、注气方案、井控调整和预测方案。正确做法是：

```text
原始模型版本
    ↓
创建不可变方案副本
    ↓
修改白名单参数
    ↓
保存参数快照
    ↓
运行方案
    ↓
与基准 Run 对比
```

禁止直接改写用户上传的原始 DATA。第一批参数应先由工程人员确认关键字、单位、范围和回读方式。当前已完成多个受控切片：L04 的单井 `WELOPEN` 启停、L13 的单井单日期 `WCONHIST ORAT + OPEN/SHUT`、L23 的预测初始段 `WCONPROD ORAT`、L24 的单完井段 `COMPDAT OPEN/SHUT`，以及 L14 的真实二进制结果读取；L13 真实 Run 115、L14 真实 Run 117 和 L24 的 EX5 Run 133 均已通过真实计算验收，证据见对应 `docs/software-integration/acceptance/L13.md`、`L14.md`、`L23.md` 与 `L24.md`。

L14 的结果读取是只读受控能力：字段目录从 `EGRID/INIT/UNRST` 索引生成，动态时间步来自真实 `SEQNUM`，二维层只通过 Artifact Range 读取选定字段，并按 `ACTNUM` 还原活动单元位置。L15 在此只读基础上增加三维展示、受控完井定位和派生编辑草案，但仍不宣称任意场值物理计算、原始二进制写回或自动生成下一次模拟输入已完成。L24 只把已核对的单条 `COMPDAT` 状态草案转换为隔离 Schedule 输入并重新计算，不能据此宣称已完成任意完井属性编辑。

### 暂不优先

- ECLIPSE 任意 deck 文本编辑。
- 自动修改任意关键字。
- `WCONHIST` 的 `WRAT/GRAT`、`WCONPROD/WCONINJE`、压力控制、批量多井和任意 Schedule 文本编辑。
- 批量敏感性分析。
- 模板生成。
- 直接解析全部二进制结果但没有分页和存储方案。
- 把原始油藏数据文件直接当成可运行 DATA。

## 7. 三类模拟器共用的平台能力

这些能力不属于某一个模拟器，但决定系统能否从 Demo 变成产品：

### 7.1 模型和版本

- 每次上传产生不可变版本。
- 保存源文件名、大小、SHA-256、模型类型和验证状态。
- Run 固定绑定 project、model、modelVersion。
- 旧版本可以查看和复算。
- 不修改原始上传文件。

### 7.2 Run 和恢复

- 持久状态和事件。
- 排队和全局执行锁。
- 取消、超时和 Worker 重启恢复。
- 运行中、成功、失败、部分成功和环境失败有明确语义。
- 前端只按结构化状态轮询，不解析日志判断完成。

### 7.3 Artifact 和审计

- 保存输入版本引用、参数快照、结果合同、输出文件清单和 SHA-256。
- 不保存许可证、密码、Cookie、完整敏感路径和原始日志中的敏感内容。
- 增加 Artifact 下载权限控制和 30 天清理。
- 结果页能够回答“用哪个版本、哪个 Study/Case、什么参数、哪次 Run 算出来的”。

### 7.4 环境和能力

- Worker health。
- PIPESIM 能力。
- Network 能力。
- ECLIPSE 版本能力。
- 许可证不可用和软件未安装的区别。
- 能力不可用时只禁用对应的新计算，历史结果仍可查看。

### 7.5 测试和验收

- 单元测试：解析器、状态机、合同、脱敏和路径安全。
- Worker 测试：启动、取消、超时、进程树清理。
- 后端测试：版本绑定、状态转换和错误分类。
- 前端测试：模型切换、轮询、历史恢复和结果展示。
- 真实验收：按受影响的模拟器串行执行，不重复占用许可证。

## 8. 每个功能包的完成标准

以后每项开发都用下面的格式，不要只写“完成接口”或“完成页面”：

```text
功能名称：
用户能完成的事情：
支持的模拟器：PIPESIM / Network / ECLIPSE
输入：模型版本、Study/Case、参数
后端变化：接口、状态、数据库
Worker 变化：调用、解析、清理
前端变化：操作、状态、结果
测试：单元、Mock、真实模型、部署浏览器
证据：代码版本、模型 SHA、Run ID、结果
未完成：明确列出
```

只有当用户路径、真实计算、结果展示和历史恢复都完成时，才把功能标记为“可演示”。只有满足完整需求中的恢复、Artifact、保留和安全要求，才标记为“首版完成”。

## 9. 建议的近期任务顺序

### 验收收口（与新开发分开）

1. 在当前 5173 部署补齐 L17—L21 标注的人工 Run、两个桌面尺寸、刷新和历史恢复证据；不借此重写已通过合同。
2. 固定 BRILLIG、EX6、EX5、CSW_124、CSW_101/105 和四个 Network 官方模型的用途，不能交叉替代验收。
3. 记录当前 Worker generation、模型 SHA、Run ID 和部署时间；历史 Run 只证明其对应代码/模型版本。

### 当前开发卡：L26

1. 使用官方 Schedule 教程明确示例的 COMPDAT Connection Factor，先在 EX5 隔离副本把 `BASE.SCH:116` 的 `G1/I14/J2/K1` 从 `3.028` 改为 `1.5`，完成官方 `eclrun` 求解尖峰。
2. 尖峰必须找到可复核的业务结果变化；只有文件哈希变化、日志变化或静态页面变化不能作为 L25 放行依据。
3. L25 官方 EX5 尖峰已通过：基线/方案均以官方 eclrun 2024.1 退出码 0 完成，ECLEND Problems/Errors/Bugs 为 0，RSM/FUNSMRY/FUNRST/PRT/MSG 均出现真实数值或诊断差异。代码已新增独立 `eclipse-completion-factor-parameters/1`，覆盖现有 inspection v4 的 COMPDAT CF 读取、隔离编辑/原值回读、基线对比、结果页和历史参数恢复；不得扩展现有 COMPDAT OPEN/SHUT 合同。当前只剩当前 5173 部署的人工基线/方案 Run 证据。
4. 完整任务和验收门槛见 `docs/software-integration/acceptance/L25.md` 与 `luna-development-handoff.md`。L25 仍是当前 5173 的人工验收债务，不阻塞独立的 L26 开发。

### L25 后建议顺序

1. L26：PIPESIM Network Choke Bean Size 单参数方案。官方尖峰、Worker/Spring/Vue 合同和自动化已完成；当前只剩当前 5173 的人工基线/方案证据，验收见 `docs/software-integration/acceptance/L26.md`。
2. L27：PIPESIM ESP 单参数方案。先从 `get_set_esp_motor_and_cable.py` 选一个官方模型可稳定回读且计算响应明确的频率/级数参数；不同时开放泵、电机、电缆全部字段。
3. L28：ECLIPSE 基线/方案同时间步动态场差异。仅对同时具有兼容 EGRID/UNRST 的两次 Run 做同关键字、同单位、同网格索引差值和三维着色，不跨网格插值。
4. 完成这些单参数闭环后再规划批量敏感性、方案队列和优化；多用户权限、多计算节点、跨模拟器自动耦合继续后置。

## 10. 总体判断

项目下一阶段不应继续堆演示页面，而应围绕三条真实链路逐步增加工程数据、方案计算、结果对比、恢复和审计能力。每次只推进一条完整用户路径，保持当前已经验证的 PIPESIM Golden、Network 结果合同和 ECLIPSE ECLEND/RSM 合同不变。

## 11. 本轮已完成的 PIPESIM 工程包闭环

- PIPESIM 井筒和 Network 验证成功后生成 v2 inspection，包含模型版本目录内全部伴随文件的相对路径、大小和 SHA-256。
- Spring 派发 nodal、profile、combined、sensitivity、network 时转发已持久化的 `packageFiles`；Worker 在源包、隔离副本和结果发布前再次校验。
- 工程包文件发生新增、删除、替换或内容变化时，运行不会启动或不会发布成功结果，并返回结构化 `PTK_PACKAGE_INTEGRITY_MISMATCH`。
- 前端新增“工程包”页签，展示依赖文件、大小和 SHA-256，便于甲方演示版本冻结和计算可追溯性。
- 仍需现场验收：用真实 CSW_101、CSW_102、CSN_302 重新验证并运行，确认 PIPESIM PTK 对各类伴随文件的实际目录结构都能被完整复制。

## 12. 2026-09-18 真实运行验收证据

本次验收使用当前机器上真实安装的 PIPESIM 2022.1、ECLIPSE 运行环境和 `C:\GRDP-Data\models` 中的真实模型，所有 Run 均通过 Worker 执行，未使用人工构造的模拟结果：

| 功能 | 模型 | Run ID | 结果 | 关键证据 |
|---|---|---:|---|---|
| PIPESIM 井筒 Nodal | CSW_101 Basic Oil Well | 990101 | `SUCCEEDED` / `VALID_FULL` | IPR/VLP 完整曲线，30 个点，进程树与隔离目录清理确认 |
| PIPESIM 井筒 Sensitivity | CSW_101 Basic Oil Well | 990102 | `SUCCEEDED` / `VALID_FULL` | `reservoirPressure` = 4000/5000/6000 三个工况，每个工况均有完整 IPR/VLP |
| PIPESIM 井筒 Profile | CSW_102 Basic Gas Well | 990103 | `SUCCEEDED` / `VALID_FULL` | `basic_gas`，16 个深度剖面点，压力与温度均有数值 |
| PIPESIM 井筒 Combined | CSW_102 Basic Gas Well | 990104 | `SUCCEEDED` / `VALID_FULL` | 同一次 Run 返回完整 IPR、VLP 和 PT 剖面，状态事件按 Nodal → Profile → Collecting 完整闭合 |
| PIPESIM Network | CSN_302 Gas Transmission Network | 990302 | `SUCCEEDED` / `VALID_FULL` | 12 节点、12 条边、2 个源、1 个汇、6 条流线 |
| ECLIPSE | BRILLIG.DATA | 990401 | `SUCCEEDED` / `VALID_FULL` | ECLEND 无 errors/problems/bugs，结果 Artifact 已发布 |

本表中的 Run 均在 Worker generation `98db9a0b6ed143e0b0f4eee12c3dc663` 上完成。当前共享 Worker 未重启，仍返回 PIPESIM inspection v1；因此上面记录的是“真实计算链路已验证”，而本轮工作区中新增的 PIPESIM v2 工程包清单运行时验收，需要在下次受控部署新版 Worker 后再单独确认，不能将代码级测试替代部署验收。

## 13. 新版 Worker 工程包运行时验收

为避免影响共享的 5150 Worker，本轮使用独立端口 `5151`、独立临时存储根目录和工作区最新代码构建了新版 Worker。运行时证据如下：

- Worker generation：`a07e9aacbbdd4294b5805a357a9c3be8`。
- CSW_102 验证返回 `pipesim-well-inspection/2`，工程包清单包含 `CSW_102_Basic Gas Well.pips`、`74900` bytes 和 SHA-256 `71f369bc...352f3a7`。
- 携带该工程包清单的真实 Profile Run `990106` 成功完成，结果为 `VALID_FULL`，返回 16 个 PT 剖面点，且 `Process-tree exit confirmed`、输入目录和工作目录均清理完成。
- 使用错误模型路径的 Run `990105` 被 Worker 在启动计算前拒绝，返回 `PTK_PACKAGE_INTEGRITY_MISMATCH`，没有产生结果 Artifact；这证明模型路径和工程包清单不会被静默放宽。

因此，PIPESIM v2 工程包闭环已经完成独立新版 Worker 的运行时验证；部署共享 Worker 时仍需执行同样的受控替换和 5150 smoke 检查。

## 14. PIPESIM Network v2 运行时验收

使用独立新版 Worker generation `214b32d3a5f745ec9df617e0827a78ca` 对真实 `CSN_302_Gas Transmission Network.pips` 进行了同样的运行时验收：

- 验证返回 `pipesim-network-inspection/2`，工程包清单包含模型文件、`98841` bytes 和 SHA-256 `53ff6d76...661053`。
- 携带该清单的真实 Network Run `990107` 成功完成，结果为 `VALID_FULL`。
- 结果包含 12 个节点、12 条边、2 个源、1 个汇和 6 条流线；求解器收敛，系统变量、节点变量、支路剖面和质量信息均已归一化。
- 运行结束后确认进程树、输入目录和工作目录均清理完成。

## 15. ECLIPSE v2 运行时验收

使用独立新版 Worker generation `6e30becb82734e55b818c7770d5c19de` 对真实 `BRILLIG.DATA` 完成了 DATA 包清单和计算验收：

- `/api/models/inspect` 返回 `eclipse-data-inspection/3`，包含 `BRILLIG.DATA` 的大小 `453204` bytes 和 SHA-256 `bbce6250...1d26ca`。
- 携带该清单的真实 ECLIPSE Run `990108` 成功完成，结果为 `VALID_FULL`；ECLEND 统计为 `errors=0`、`problems=0`、`bugs=0`。
- 结果包含 INIT、S0001–S0010、SMSPEC 等输出 Artifact，以及二进制字段索引；Artifact SHA-256 已登记，且 ECLIPSE 进程树和临时目录均已清理。

## 16. 真实取消与超时清理验收

使用独立新版 Worker generation `069bf324c0684ced86af33458558eb54` 和真实 `CSW_101_Basic Oil Well.pips` 完成了两条受控异常路径：

- Run `990109`：在 `RUNNING_NODAL` 阶段发送取消请求，最终状态为 `CANCELLED`，错误码 `RUN_CANCELLED`，`killUsed=true`，且进程树、输入目录、工作目录均确认清理。
- Run `990110`：使用 1 秒运行超时，最终状态为 `TIMED_OUT`，错误码 `RUN_TIMEOUT`，`killUsed=true`，且 `processTreeExitConfirmed=true`、输入目录和工作目录均已删除。

这两条证据补齐了当前首版定义中“真实运行中的取消、超时和进程清理”要求；没有在共享许可证任务上做强制终止操作。

## 17. 真实 ECLIPSE INCLUDE 工程验收

使用 ECLIPSE 2024.1 自带的真实 Schedule Tutorial 工程 `EX5.DATA`，在独立 Worker generation `5784a68b03e14dc38608619b96e21056` 上完成了包含依赖的成功计算：

- DATA 检查返回 `eclipse-data-inspection/3`，工程包清单包含 `BASE.SCH`、`EX5.DATA` 和 `EX5.GRDECL` 三个相对文件，并逐项记录大小和 SHA-256。
- 真实 ECLIPSE Run `990113` 成功，结果为 `VALID_FULL`；ECLEND 为 `errors=0`、`problems=0`、`bugs=0`，返回 `EX5.RSM`、`EX5.MSG`、`EX5.PRT` 和 ECLEND 元数据。
- 进程树、输入目录和工作目录均清理完成。
- 同一实例对真实 `EX2.DATA` 的 Run `990111` 返回 `ECLIPSE_SOLVER_FAILED`，被正确分类为失败且没有发布成功结果，证明 INCLUDE 工程不会因“能解析”就被伪造为成功。

## 18. Worker Release 发布物验收

在不重启共享 5150 Worker 的前提下，使用 `dotnet publish -c Release -r win-x64 --self-contained false` 生成独立发布目录，并在 5151 端口启动 smoke 实例：

- 发布包包含 13 个文件，总大小约 `700195` bytes。
- `Grdp.SoftwareIntegration.Worker.dll` SHA-256：`d3ee73fc588649b3853f6d39af11bc516893e305a439452f688a0c812fad84d5`。
- 独立发布实例返回 `UP / idle`，PIPESIM Well、PIPESIM Network、ECLIPSE 100 均为 `AVAILABLE`。

该发布物可作为后续 5150 受控替换和回滚对象；当前共享实例仍保持运行，未被本次 smoke 影响。
