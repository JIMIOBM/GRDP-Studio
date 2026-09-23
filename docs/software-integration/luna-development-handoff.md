# 统一控制台后续开发任务书（交给 GPT 5.6 Luna）

编制日期：2026-09-23。项目：`C:\Users\Violet\Desktop\Bei\GRDP-Studio`。

本文是开发交接计划；截至 2026-09-23，L00—L26 已形成连续垂直切片和独立验收记录。当前已覆盖 ECLIPSE 调度/结果/二维与三维后处理、受控 Schedule/COMPDAT 方案，PIPESIM 井筒/管网计算、System Analysis、Gas Lift、VFP、ESP、井轨迹、Network Optimizer 和 Network Choke Bean Size 方案。第一需求始终是：尽快交付甲方可展示、可真实计算、可人工验收的统一控制台。每轮只完成一张明确任务卡，并留下可复现的官方模型、Run、数值、界面和自动化证据。

交接方式：在当前本地项目中可直接读取本文。现有`.gitignore`忽略`docs/`中新建的未跟踪文件，因此本文及未来新建验收记录不会自动随普通Git操作传递；交给另一台机器或新工作树时显式提供文档与当前未提交代码。本文编制未修改忽略规则或暂存文件。

## 1. 接手规则

1. 先读根目录 `AGENTS.md`，再读 `docs/software-integration/requirements.md`、`code-overview.md`、`eclipse-mvp-architecture.md`；按当前任务查阅 `roadmap.md`、官方帮助和源代码。
2. 先执行 `git status --short`，记录已有改动。当前工作区有大量未提交的软件集成代码，必须在此基础上继续，不覆盖、不清理、不重置。单独开工作树时须确认已包含这些代码，否则会从过时版本开发。
3. 架构保持 Vue → Spring Boot → Windows Worker → 官方模拟器。浏览器只访问 Spring；Worker 不直接写业务数据库。
4. 仅修改软件集成模块、相关测试和文档；解析融合算法、PVT、产能分析等原业务保持原样。共享界面只做有隔离条件的必要接线。
5. 先完成当前任务卡，再进入下一卡。遇到普通实现问题自行排查；仅在缺少许可证、必要工程数据、关键业务选择等外部条件时报告具体阻塞。
6. 当前轮不要求提交 Git 或发布远端。若用户要求提交，先按 AGENTS 复核业务逻辑、边界、界面状态和相关测试。
7. 未核实的官方接口、字段、单位和默认值不得猜测。官方目录只读；实验和方案计算使用工程副本。

## 2. 当前基线与需要纠正的假设

现有代码已覆盖井筒 nodal/profile/combined/sensitivity、Network 运行和拓扑结果、ECLIPSE DATA/ZIP/INCLUDE、ECLEND、Summary、部分二进制结果读取，以及运行历史、取消超时、工程包哈希校验等能力。不要重复建设这些基础链路。

证据等级须分开：代码存在、自动化测试通过、真实模拟器运行通过、当前部署浏览器验收通过。历史成功不证明当前修改后的部署成功。

- `roadmap.md` 第 12—18 节有历史实机证据，包括 PIPESIM 官方井筒/管网模型和 ECLIPSE BRILLIG、EX5。
- 第 17 节明确记录 EX5 Run `990113` 成功，而 EX2 Run `990111` 为 `ECLIPSE_SOLVER_FAILED`。EX2适合井与调度学习，但当前不能承诺其可直接计算。
- 一些早期“待验收”文字与后面的运行证据不一致；根据代码版本、模型哈希、部署实例和运行记录逐项核实，不能只看章节先后就认定现部署完成。
- 后端测试源有已识别的契约不一致：`HttpWorkerRunClientTests`仍有七参数构造调用，`WorkerRunExecuteRequest`当前为八参数；`SoftwareIntegrationRunFlowTests`调用了响应中不存在的`storageKey()`。先核实再修复，不为了测试向前端暴露内部存储字段。
- 上轮复核没有成功执行 Maven：当前 shell 未找到 `mvn`，项目没有已确认的 Maven Wrapper。不能据此声称本轮全量测试通过。`backend/pom.xml`要求 Java 21；先定位本机实际 JDK/Maven，记录版本和可执行路径。
- `start-grdp-ahks.bat`当前内容只负责 Worker 启动与探测，不能把它当作已经完成的三服务一键启动。
- ECLIPSE “真实计算成功”不等于“一定有三维结果”。三维页至少需要本次 Run 发布可读的 `EGRID`（几何），通常还需要 `INIT`/`UNRST`（静态/动态场值）。官方 EX6 的当前 Run `126` 只有 `ECLEND/FUNRST/MSG/PRT`，因此它用于 Problems 诊断验收，不能用来验收三维。
- 当前三维基准必须使用官方 `C:\ecl\2024.1\eclipse\data\BRILLIG.DATA`。最近核对的 BRILLIG Run `129` 为 `SUCCEEDED / VALID_FULL`，网格 `20 × 15 × 8`、活动单元 `1639`，有 `EGRID/INIT/UNRST` 和 759 个字段索引；L15 的可视交互证据见 Run `117`。L17 要解决的是三维入口的可发现性和空状态解释，不是重新伪造一套三维数据。
- 官方 EX6 当前 Run `126` 为 `PARTIAL_SUCCEEDED / VALID_PARTIAL`：ECLEND `Comments=1 / Warnings=34 / Problems=34 / Errors=0 / Bugs=0`，MSG 提取 68 条结构化诊断。Problems 是可读的部分成功，不可冒充干净基线，也不应被改写或隐藏；详见 `acceptance/L16.md`。

### 2026-09-23 当前代码审计

- 当前工作树仍是大型未提交软件集成改动，`git diff --stat` 为 93 个已跟踪文件、约 10050 行新增和 464 行删除，另有新的结果组件、参数合同、Worker 解析器和测试文件；Luna 不得重置、覆盖或从旧提交重新实现。
- PIPESIM Worker 当前明确接受 `nodal/profile/combined/sensitivity/network/system-analysis/network-optimizer/gas-lift-performance/gas-lift-diagnostics/vfp-tables/esp-curves/trajectory`。模型被识别为 `READY` 不代表每个专项任务都适用，页面必须继续按模型类型和检查能力门控。
- ECLIPSE 当前已有独立合同：`WELOPEN`、`WCONHIST ORAT`、`WCONINJE RATE`、预测初始段 `WCONPROD ORAT`、单条 `COMPDAT OPEN/SHUT`；不得把这些白名单能力描述成任意 deck 编辑。
- 当前代码级复核通过：Python 归一化 `65/65`、Worker PIPESIM/ECLIPSE 相关合同 `158/158`、前端结果单测 `7/7`、Vue production build；Java 21 + Maven 下 L26 相关后端定向测试 `91/91`。构建仅有既有 Sass/Rollup/chunk-size 警告。
- L25 已完成官方求解尖峰：`C:\ecl\macros\eclrun.exe` 为 2024.1；EX5 基线与仅修改 `BASE.SCH:116` CF=3.028→1.5 的方案均退出码 0、ECLEND Problems/Errors/Bugs 为 0，RSM/FUNSMRY/FUNRST/PRT/MSG 均产生真实差异。L25 生产代码和 Worker 定向测试已完成；当前 5173 人工 Run 尚未重新执行。
- L26 已完成官方尖峰和代码接入：`CSN_301_Small Network.pips` 的 `Choke` BeanSize 原值 `2.0 in`，隔离副本改为 `3.0 in` 后基线/方案均为 `Completed / VALID_FULL`，系统变量真实变化；Worker 定向测试 `158/158`、Vue production build 已通过。当前 5173 人工基线/方案 Run 尚未执行，不能把 L26 标为部署验收通过。
- 当前 5150 Worker 为 `UP/idle`，generation `7c92551502e84cbeb6b6a941fd5554a9`；8080 与 5173 均有 HTTP 响应。健康响应只证明服务可达，不证明当前未验收任务已通过真实模拟器计算。
- 本轮已定位并使用项目本地工具链：JDK `C:\Users\Violet\Desktop\Bei\.tools\jdk-21.0.12.1+1`、Maven `C:\Users\Violet\Desktop\Bei\.tools\apache-maven-3.9.16`。L26 后端源码编译通过，软件集成定向测试 `91/91` 通过。Maven 全量测试仍有 65 个既有管线测试因仓库缺少 `sql\pipeline_capacity.sql` 报错；这些错误与本轮软件集成改动无关，不得标为全量通过，也不得顺手修改解析融合/管线模块。
- 当前验收债务应与新开发分开：L17—L21 的文档仍包含当前 5173 人工复验项；L23/L24 已有真实 API/部署 Run 证据。补截图和 Run ID 不得顺带改结果合同，新功能也不得用旧 Run 冒充当前部署验收。
- 当前继续开发任务是 L26：在 `CSN_301_Small Network.pips` 的 `Study 1` 上验证 Choke Bean Size 受控方案；入口名称为“创建 Choke Bean Size 方案”，目标值建议从 `2.0 in` 改为 `3.0 in`。L25 的 EX5 人工验收仍单独保留，不得用 L26 证据替代。

## 3. 官方资料和代码入口

### 官方资料

| 资料 | 本机路径 | 用途 |
|---|---|---|
| Schedule 教程 | `C:\ecl\2024.1\schedule\tutorials` | 工程数据和练习案例，不是已确认的编程SDK |
| EX2教程说明 | `C:\ecl\2024.1\schedule\sche_ug\dataedit.html` | 交互编辑、井组控制网络、井事件与历史计算 |
| EX3教程说明 | `C:\ecl\2024.1\schedule\sche_ug\3dvisprd.html` | 历史/预测、重启工作流 |
| Schedule手册 | `C:\ecl\2024.1\manuals\EPP\ScheduleUserGuide.pdf` | 文件与业务操作说明 |
| ECLIPSE关键字手册 | `C:\ecl\2024.1\manuals\EclipseReferenceManual.pdf` | 关键字字段、默认项、单位和适用条件 |
| ECLIPSE文件格式手册 | `C:\ecl\2024.1\manuals\FileFormatsReferenceManual.pdf` | EGRID/INIT/UNRST/SMSPEC/UNSMRY 等文件格式和记录语义 |
| PIPESIM帮助 | `C:\Program Files\Schlumberger\PIPESIM2022.1\Developer Tools\Python Toolkit\Help` | PTK API 与任务说明；先看 `examples.html` |
| PIPESIM示例 | `C:\Program Files\Schlumberger\PIPESIM2022.1\Developer Tools\Python Toolkit\Examples` | 官方脚本和匹配模型；递归查找对应文件 |
| PIPESIM井模型 | `C:\Program Files\Schlumberger\PIPESIM2022.1\Case Studies\Well Models` | CSW_101/105/124 等官方 `.pips` 验收输入 |
| PIPESIM管网模型 | `C:\Program Files\Schlumberger\PIPESIM2022.1\Case Studies\Network Models` | CSN_301—313 官方 `.pips` 验收输入 |

Schedule 集成先采用工程文件读取/受控生成加现有 eclrun 执行链路，不假设存在可直接调用的 Schedule Python SDK，也不把启动 Schedule 桌面窗口当成控制台功能完成。

下一阶段必须先复现相应官方示例，再设计平台合同：ESP 读取以 `get_esp_curve_results.py` 为依据，井轨迹以 `get_set_well_trajectory.py` 为依据，System Analysis/Network Optimizer/VFP 继续沿用已验收的官方脚本。示例中的 `set_*` 或 `apply_results()` 不构成自动写回授权；首个新切片一律先只读，写入另立受控方案卡。

已在本机再次确认的 PIPESIM 官方“脚本—模型—平台功能”映射：

| 官方脚本（均位于 Python Toolkit `Examples`） | 匹配官方模型/用途 | 平台边界 |
|---|---|---|
| `get_esp_curve_results.py` | `CSW_124_Dual ESP Well.pips`，读取 `Well_1 / B-ESP` 的 ESP 曲线 | L20 首先只读曲线；不改泵、电机或电缆 |
| `get_set_esp_motor_and_cable.py` | 官方基础井上的 ESP/电机/电缆设置示范 | 仅作为后续写入设计参考，不纳入 L20 |
| `get_set_well_trajectory.py` | `CSW_101_Basic Oil Well.pips`，读取/设置井轨迹 | L21 只使用 `get_trajectory`；`set_trajectory` 另立任务 |
| `wellperformancecurvessim_run_simulation.py` | `CSN_301_Small Network.pips`，井性能曲线任务 | 作为 Network 扩展参考，不能替代 ESP 专项验收 |
| `networksim.py` | 官方 CSN 网络模型，常规 Network 计算 | 已有 Network 主链路的官方依据 |
| `network_opt_sim.py` | `CSN_312_Gas Lift Optimization.pips` | L11/L12 已验收；L22 扩展兼容性 |
| `systemanalysissim_run_simulation.py` | 官方 System Analysis 案例 | L05 已验收 |
| `vfp_tables_simulation_with_sensitivities.py` | 官方井模型的 VFP 表生成 | L10 已验收 |

### 代码入口（项目根目录相对路径）

| 层 | 优先阅读 |
|---|---|
| 前端容器 | `vue/src/views/SoftwareIntegration/PipesimModelRunPage.vue`、`EclipseDataInspectionOverview.vue`、`EclipseRunResult.vue` |
| 状态与请求 | `vue/src/stores/softwareIntegration.js`、`vue/src/api/softwareIntegration.js` |
| 后端业务 | `backend/src/main/java/com/grdp/studio/softwareintegration/service/impl/SoftwareIntegrationRunServiceImpl.java`、`SoftwareIntegrationServiceImpl.java` |
| 调度与校验 | 同模块 `execution/SoftwareIntegrationRunDispatcher.java`、`EclipseSummaryResultValidator.java`、`support/EclipseDataInspectionValidator.java` |
| Worker工程检查 | `worker/Inspection/EclipseDataInspector.cs`、`EclipseDataInspectionService.cs`、`worker/Execution/EclipseDeckPackageResolver.cs` |
| Worker计算 | `worker/Execution/EclipseRunService.cs`、`EclipsePackageIntegrity.cs`、`PtkRunService.cs`、`worker/Contracts/WorkerContracts.cs` |
| PIPESIM适配 | `worker/ptk_run.py`、`ptk_network.py`、`ptk_normalization.py`，新增功能尽量使用独立模块 |
| 回归保护 | `worker/tests/Worker`、`worker/tests/PythonNormalization`、后端软件集成测试、`vue/tests/e2e/software-integration-demo.spec.js` |

不要求一次阅读所有代码。每卡先沿其用户入口追踪到执行与结果，再决定修改位置。

## 4. 推进顺序与里程碑

| 任务 | 依赖 | 本轮用户可见成果 | 放行标准 |
|---|---|---|---|
| L00 固化当前演示基线 | 无 | 现有三类模型路径稳定，错误可定位 | 已完成；见 `acceptance/L00.md` |
| L01 核实调度计算案例 | L00 | 确认哪个官方调度案例可运行，EX2失败有结论 | 已完成；EX2 保留为失败诊断案例，EX1 派生副本真实运行通过；见 `acceptance/L01.md` |
| L02 井与调度只读界面 | L00；可与L01排查穿插 | EX2井列表、井组树、完井、时间轴和来源 | 首个 `/4` 只读垂直切片已完成；官方 EX1 真实检查通过，见 `acceptance/L02.md`；计算闭环已在 L03/L04 完成首个切片 |
| L03 井级结果与基线对比 | L01 | 选井看真实生产/注入曲线和历史结果 | 已完成；BRILLIG Run 72 真实 Summary、筛选、导出和比较通过，见 `acceptance/L03.md` |
| L04 首个调度方案闭环 | L01、L02、L03 | 修改一口井某日启停，真实运行并比较 | 已完成；EX5 Run 80/81 的 `SHUT/OPEN` 真实计算、参数和源包隔离通过，见 `acceptance/L04.md` |
| L05 PIPESIM系统分析 | L00；首个调度闭环后优先 | 一个官方System Analysis工况表与曲线 | 已完成；官方 PTK 与统一控制台 Run 87 均返回 3 工况、17 点剖面，见 `acceptance/L05.md` |
| L06 Network单参数方案 | L00；先核实已有实现 | 一个入口边界参数的基线/方案比较 | 已完成；Run 88/89 真实基线/方案、参数回读、收敛状态和结果差异均可核验，见 `acceptance/L06.md` |
| L07 ECLIPSE历史转预测 | L04 | EX3历史期、重启及预测结果 | 已完成首个可验收垂直切片；多主 DATA 明确选择、历史 Run→重启 Artifact→预测 DATA 绑定和真实结果均已通过，见 `acceptance/L07.md` |
| L08 气举性能 | L05 | 注气量与性能曲线 | 已完成；Run 99 返回 11 个官方工况，见 `acceptance/L08.md` |
| L09 气举诊断 | L08或已验证独立案例 | 阀状态和逐阀数据 | 已完成首个真实切片；Run 100、逐阀结果和缺失值可核验，见 `acceptance/L09.md` |
| L10 VFP生成 | 匹配官方模型可运行 | 维度、单位和VFP表导出 | 已完成首个真实单井切片；Run 104、4行BHP/4行TEMP、单位转换和导出验收见 `acceptance/L10.md` |
| L11 Network优化 | L06 | 官方优化器真实计算、变量/对象结果展示和不可用值审计 | Run 111 `SUCCEEDED / VALID_FULL`，27变量、4井、3流线、1汇点可回读；当前不宣称全局最优或写回源模型 |
| L12 Network优化应用 | L11 | 隔离副本应用官方优化结果并发布审计 Artifact | 已完成；Run 114 真实应用和源模型不变见 `acceptance/L12.md` |
| L13 ECLIPSE WCONHIST ORAT | L04 | 单井单日期生产目标与启停受控方案 | 已完成；EX5 Run 115 `SUCCEEDED / VALID_FULL`，兼容 Run 116 见 `acceptance/L13.md` |
| L14 ECLIPSE 网格/动态字段切片 | L03 | EGRID/INIT/UNRST 索引、ACTNUM 还原、二维层读取和 CSV | 已完成；BRILLIG Run 117，20×15×8、活动单元1639、UNRST时间步1–8，见 `acceptance/L14.md` |
| L15 ECLIPSE 三维几何/完井定位/编辑草案 | L14 | 真实 corner-point 几何、旋转缩放、完井 I/J/K 定位、派生编辑草案 | 已完成；BRILLIG Run 117，见 `acceptance/L15.md`；草案不写回模拟器 |
| L16 ECLIPSE Problems 部分成功诊断 | L00 | 保留原始 Problems，结构化展示 MSG，允许读取部分结果 | 已完成；官方 EX6 Run 126，34 Problems、68 条诊断，见 `acceptance/L16.md` |
| L17 三维结果入口与可发现性闭环 | L15 | 运行后无需翻找即可进入 3D；无三维时给出准确原因 | 代码与自动化已完成；人工部署验收见 `acceptance/L17.md` |
| L18 Problems 分类与处置建议 | L16 | 诊断按模式归组，原文和证据可展开，建议不自动改模型 | 代码与专项自动化已完成；真实 EX6 部署验收见 `acceptance/L18.md` |
| L19 WCONINJE 受控注入方案 | L13 | 单井单日期单控制量，隔离副本计算并与基线比较 | 代码与专项自动化已完成；真实 EX5 部署验收见 `acceptance/L19.md` |
| L20 PIPESIM ESP 曲线 | L05 | 官方 ESP 曲线、泵型/频率/BEP 和原始点导出 | 代码、官方适配器实机证据和自动化已完成；待当前 5173 用官方 CSW_124_Dual ESP Well.pips 人工运行 |
| L21 PIPESIM 井轨迹只读 | L20 可并行 | MD/TVD/倾角/方位读取和三维定位，不写回 | 代码、官方适配器实机证据和自动化已完成；待当前 5173 用 CSW_101、CSW_105 人工运行 |
| L22 PIPESIM Network 兼容矩阵 | L06/L11 | 多个官方网络模型逐个验证并明确支持/不支持原因 | 代码、四个官方模型适配器实机证据和自动化已完成；待当前 5173 逐模型人工运行，见 `acceptance/L22.md` |
| L23 ECLIPSE WCONPROD 预测初始段 | L04、L07 | 在 EX3 预测初始段受控修改一口生产井的 ORAT 和 OPEN/SHUT，并与成功基线比较 | 代码、合同、Worker 隔离编辑、API 真实 Run 和专项测试已完成；当前 5173 重新上传完整 ZIP 的浏览器证据待补，见 `acceptance/L23.md` |
| L24 ECLIPSE COMPDAT 完井状态 | L15、L23 | 从检查记录选择单条完井段，隔离修改 OPEN/SHUT 并真实重算 | 已完成；EX5 基线 Run 84、方案 Run 133 均为 `SUCCEEDED/VALID_FULL`，5/5 输出变化，见 `acceptance/L24.md` |
| L25 ECLIPSE COMPDAT 连接因子 | L24、官方 EX5 | 从检查记录选择一条数值 CF 完井段，隔离把原 CF 改为目标值并真实重算 | 官方尖峰与代码/自动化已完成；当前 5173 重新上传 EX5 的基线/方案人工证据待补，见 `acceptance/L25.md` |
| L26 PIPESIM Network Choke Bean Size | L06、官方 CSN_301 | 从 v3 检查记录选择一个 Choke，隔离修改 Bean Size、回读并真实重算 | 官方尖峰、Worker/Spring/Vue 和自动化已完成；当前 5173 人工基线/方案证据待补，见 `acceptance/L26.md` |

首个交付里程碑：L00—L04。它提供“上传工程→看井与调度→创建方案→真实计算→结果对比→历史恢复”的完整甲方演示。

第二个里程碑：L05—L06，为井筒与管网各增加一条可调参计算路径。第三个里程碑 L07—L16 已扩展到历史预测、气举、VFP、优化、Schedule 编辑、二维/三维结果和真实诊断。第四个里程碑 L17—L24 已扩展到可发现性、诊断归组、ESP、井轨迹、Network 兼容和更多受控 ECLIPSE 方案，其中部分卡仍需当前 5173 人工复验。第五个里程碑从 L25 开始，把“参数真的改变→官方求解→业务结果真的改变→历史可恢复”做成连续方案能力；跨模拟器自动耦合、多节点部署或任意模型编辑仍不纳入这些卡。

不承诺固定四周或模型工作速度。先记录L00/L01实际耗时、模拟器运行时间和环境阻塞，再估算后续交付日期；进度按通过验收的任务卡统计。

## 5. 首批任务卡

### L00：当前演示基线（已完成）

工作内容：

- 核实运行环境与工具链，修复已证实的测试契约问题；保留工程包清单和内部存储边界。
- 对现有井筒、Network、ECLIPSE执行与页面做检查。官方案例选CSW_101/CSW_102、CSN_302、BRILLIG/EX5；复用仍适用的历史证据，受修改影响的真实计算重新验证。
- 区分“创建Run失败”和“模拟器计算失败”：页面保留明确、脱敏的原因和可用Run ID，不能只弹“稍后重试”。按实际复现决定是否需改代码。
- 确认前端、Spring、Worker如何启动、当前部署版本及端口；已有运行任务不被重启打断。

验收：受影响测试及构建通过；三类入口、上传校验、运行状态、真实结果或明确失败、历史恢复可在部署浏览器检查。完整三类计算演示仍需各自成功证据。既有解析融合入口回归正常。

交付：`docs/software-integration/acceptance/L00.md`，写明现部署事实、命令结果、证据适用版本和未通过项。某个模拟器受环境阻塞时继续其他独立工作，不把L00总体标为通过。

### L01：EX2诊断与调度计算基线（已完成）

工作内容：

- 阅读EX2教程和实际文件；结合已有失败记录定位输入格式、依赖、模拟器版本或许可证问题，不能预设原因。
- 在隔离副本复现官方执行和现有Worker执行，核对退出码、新鲜ECLEND与脱敏诊断。记录源包哈希。
- 若需准备教学工程，按官方步骤在副本中进行，完整记录转换/修改。派生工程使用独立名称，不能冒称未修改的官方原件。
- EX2未能通过时，保留它作为调度读取验收案例；根据官方文档选择已证实可运行且有适用井控的EX5或其他案例作为方案计算基线，记录差异，不声称EX2运行成功。

验收：有一套可重复真实计算、能输出必要井级Summary、能承载后续启停方案的工程。ECLEND门槛、输入哈希和运行清理通过；EX2阻塞原因和适用范围明确。

### L02：井与调度只读界面（首个垂直切片已完成）

范围：新增“井与调度”页签。首批从主DATA及其INCLUDE中的SCHEDULE读取；EX2的`BASE.SCH`是主要数据来源。`.EV/.NET/.TRJ`是后续扩展输入，不要第一轮同时实现所有Schedule专用格式。

工作内容：

- 先明确并记录版本化结果合同：井/井组、完井记录、事件日期/顺序、字段/单位、来源相对文件及行号、覆盖范围和诊断。复用已有检查流程；核实负载规模后决定内嵌检查结果或受控分页接口。
- 按官方规则处理引号、注释、斜杠终止、重复/默认项、相对INCLUDE、START/DATES/TSTEP与同日事件顺序。不能用简单逐行正则替换充当完整deck解析器。
- 核实并支持EX2涉及的WELSPECS、GRUPTREE、COMPDAT/COMPDATM、WELOPEN、WCONHIST、WCONINJE。支持不了的语义展示“未解析”，不得补默认值或声称全覆盖。
- 页面展示井列表、井组树、完井表、事件时间轴、按井/日期筛选和来源定位。先展示原始事件，只有状态继承语义完整时才展示“某日有效状态”。
- 区分井控分组关系与地面管网拓扑。旧inspection/历史版本缺少调度字段时保持可读，并给出重新检查入口。

验收：独立依据官方输入抽查井名、井组关系、完井记录和事件字段；测试使用有明确期望的夹具，不用同一个解析器生成“期望结果”。覆盖日期继承、同日事件、重复项、缺失依赖、未知语义和切换模型无串数据。页面可用不代表计算已经验收。

### L03：井级Summary与对比

工作内容：

- 复用当前RSM和SMSPEC/UNSMRY/S####读取，先核实现有合同已提供哪些井级/井组级序列，只补缺口。
- 展示可用的生产/注入率、累计量或井底压力；具体变量以工程请求并实际返回的数据为准。区分历史控制输入与模拟预测输出。
- 如果需要增加SUMMARY请求，在明确命名的派生副本中添加并记录差异；不修改上传源工程。
- 对比按对象、变量、单位和真实日期/时间坐标匹配，显示基线、方案及“方案−基线”。不同坐标不按数组下标硬配，不静默插值或转换单位；缺失值不当零。

验收：官方原始输出与页面抽样数值、时间、单位一致；刷新和切换历史Run后对象绑定正确。至少一条真实井级曲线可展示。

### L04：最小调度方案闭环

首批只开放一口已存在井、一个已有调度日期的整井启停（WELOPEN，精确字段语义按官方手册确认）。不同时开放生产目标、注入目标或完井级编辑。

工作内容：

- 页面“创建方案”→选井/日期/启停状态→预览差异→计算→与基线比较。
- 参数合同绑定原始modelVersion、井、日期、操作及baselineRun；后端和Worker同时校验，拒绝未知字段、不存在井、越界日期及不支持语义。
- 修改仅作用于Run隔离副本，保留记录顺序、同日事件优先级和后续事件覆盖关系。不能简单追加关键字而不解释其生效区间。
- 保存源工程清单、派生输入清单及哈希、参数快照和修改摘要；生成后重新读取验证语义、检查依赖，运行前验证派生包。原工程完整性仍独立复核，不能关闭现有哈希校验来允许方案运行。
- 明确哪些审计材料持久保存、哪些工作目录运行后删除。沿用现有Artifact受控发布机制，不直接暴露任意本机文件。
- 旧的空参数基线运行保留原行为；历史Run显示实际方案输入。

验收：基线与方案均有真实成功Run；原始源包哈希不变，派生输入与预览一致，启停参数回读正确。选取活动井和有响应的时段演示可解释的井级变化；若无变化需说明后续事件/约束等原因，不强行造差异。覆盖非法输入拒绝、失败后再次运行、历史恢复和清理。

L04后才考虑第二张井控编辑卡：先核实历史WCONHIST与预测WCONPROD/注入WCONINJE差异、控制模式、压力限制和单位，不能将历史实测输入编辑等同预测目标优化。

## 6. 后续各卡的最小范围

### L05 System Analysis

本卡已完成首个真实垂直切片：官方 `systemanalysissim_run_simulation.py`、严格输入/结果合同、Worker 多工况状态机、Spring 运行链路和 Vue 工况表/曲线已接通。当前只支持 `liquidFlowRate` 扫描和固定 `FL-2 / 600 psia` 边界；继续开发从 L06 开始，不把本卡扩大为任意 System Analysis 参数编辑。

在官方Examples定位`systemanalysissim_run_simulation.py`及匹配模型。先复现一个官方任务，记录真实API参数、结果形态和单位；新增一个任务类型、一个白名单扫描变量和工况数量上限，接通Worker、后端校验、页面工况表/曲线和历史。与同模型同设置的官方脚本比较，数值容差在比对前根据变量量级确定。禁止改变现有nodal/profile结果语义。

### L06 Network单参数方案

本卡已完成首个真实垂直切片：现有 `NetworkScenarioParameters` 两端实现已收敛为严格白名单，页面只提交用户勾选的边界覆盖值，Worker 在隔离副本读取 Study 条件并执行真实 Network 计算。官方 `CSN_302_Gas Transmission Network.pips` 的 Run 88 基线和 Run 89 `Terminal.pressure=900` 方案均为 `SUCCEEDED / VALID_FULL`；结果页新增方案验收卡，并保留同版本同 Study 的系统量、节点量、支路原始点和收敛诊断比较。真实数值、Artifact 和人工验收步骤见 `docs/software-integration/acceptance/L06.md`。

后续从 L07 开始；不得把 L06 扩大为多参数优化或任意 Network 参数编辑。

### L07 EX3历史—预测

L07 首个垂直切片已完成。控制台对受控 EX3 工程包要求明确选择主 DATA，并使用 `eclipse-history-forecast-parameters/1` 严格绑定同版本成功历史 Run、预测 DATA、重启 Artifact 的名称/SHA-256 和 report 编号。模型版本 25 的历史 Run 95 产生 `EX3.FUNRST`，预测 Run 97 在隔离副本中从 report 14 准备 `EX3_PRED.DATA` 并真实运行成功，ECLEND Problems/Errors/Bugs 均为 0。验收包是基于官方依赖的派生 fixture，官方目录未修改；官方 `BASE_ALL.SCH` / `BASE_HIST.SCH` 的非收敛 Problem 仍按真实结果保留为诊断样本。详细证据和人工步骤见 `acceptance/L07.md`。后续继续沿用明确主入口、受控包清单、Artifact 完整性和真实 ECLEND，不把时间轴展示误称为重启计算完成。

### L08 气举性能（已完成）

L08 已按官方 `Gas Lift Performance` 案例完成首个真实垂直切片。控制台新增 `gas-lift-performance` 运行类型、严格输入/结果合同、官方模型的 11 点注气量—液量曲线、边界卡、原始点表、CSV 导出和历史恢复。真实 Run 99 为 `SUCCEEDED / VALID_FULL`，模型版本 26，官方源 SHA-256 为 `c7195df7...ef06b73`，结果峰值为 2.0 mmscf/d → 1570.008579464397 STB/d。详细人工步骤、全量数值、Artifact 和测试证据见 `docs/software-integration/acceptance/L08.md`。本卡未修改解析融合，也未把曲线结果解释为逐阀诊断。

### L09 气举诊断（已完成首个真实切片）

L09 已按官方 Python Toolkit 的 `Gas Lift Diagnostics` 示例完成首个真实闭环。控制台新增 `gas-lift-diagnostics` 运行类型、严格输入/结果合同、官方固定注气诊断模式、11 个真实注气工况、逐阀状态/气量/压力/阀型数据、缺失值显式展示、CSV 导出和运行历史恢复。真实 Run 100 为 `SUCCEEDED / VALID_FULL`，模型版本 26，4 个阀，官方 PTK 返回的工况和逐阀字段已由 Worker 原样归一化后通过后端合同校验。当前切片固定 `FIXEDINJECTION`、`THROTTLING=ON`、`USEPHASERATIO=true`，不把它扩大宣称为所有诊断模式或任意敏感性编辑；下一步另立 L10 VFP 卡。详细参数、字段、真实数值和人工验收步骤见 `docs/software-integration/acceptance/L09.md`。

### L10 VFP表（已完成首个真实切片）

已按官方 `vfp_tables_simulation_with_sensitivities.py` 接入 `vfp-tables`。控制台、Spring、Worker 和 Vue 使用严格的 `pipesim-vfp-tables-parameters/1` / `pipesim-vfp-tables-result/1` 合同，真实 Run 104 返回 ECLIPSE `VFPPROD` 的 BHP 与 TEMP 表、LIQ/WCT/GOR/THP/ALQ 轴、官方原始文本和受控 CSV 导出。

解析时遵循官方实际格式：表体每行是 LIQ/WCT/GOR/ALQ 四个索引，THP 是 `values` 数组对应的轴；控制台公开单位为 WCT fraction、GOR MSCF/STB，Worker 调用 FIELD Toolkit 时做显式单位转换。当前只验收单井单配置生成和展示，不自动把生成表耦合进 ECLIPSE 或 Schedule。详细参数、真实数值、人工步骤和边界见 `docs/software-integration/acceptance/L10.md`。

### L11 Network Optimizer

L11 已完成首个真实 Network Optimizer 垂直切片。按官方 `network_opt_sim.py` 调用 `model.tasks.networkoptimizersimulation.run()`，统一合同为 `pipesim-network-optimizer-parameters/1` / `pipesim-network-optimizer-result/1`，页面展示官方变量元数据、井、流线、汇点、Summary、不可用质量标记和 CSV。真实只读 Run 111 使用官方 `CSN_312_Gas Lift Optimization.pips` 的 `Study 1`，返回 `SUCCEEDED / VALID_FULL`、27 个变量、4 口井、3 条流线、1 个汇点；官方 NaN 保留为 `null + UNAVAILABLE`。

官方结果是按对象稀疏变量集返回，不能要求每个对象补齐所有变量；工程单位中的 `/` 也不是路径。下一条 L12 已按 `network_opt_sim_apply_results.py` 接入 `/2 + applyResults=true`：运行副本内调用官方 `apply_results()`，回读可稳定识别的 GasRate，并发布 `pipesim-network-optimizer-applied.pips`；不写回源模型、不把一次运行称为全局最优或正式推荐方案。详细边界见 `acceptance/L11.md` 和 `acceptance/L12.md`。

### L13 ECLIPSE Schedule WCONHIST ORAT

L13 已完成第二个受控 Schedule 编辑切片。前端只暴露已有 DATES 下已有 `WCONHIST` 行的 `ORAT` 目标值和 `OPEN/SHUT` 状态，参数合同为 `eclipse-schedule-parameters/2`；Spring 与 Worker 拒绝未知字段、未知井、非 DATES 日期、非 ORAT 模式和越界目标值。Worker 在隔离副本中保留原行其他字段和记录顺序，官方 EX5 的 Run 115 已以 `SUCCEEDED / VALID_FULL` 完成，且源 ZIP SHA-256 未变。详细真实证据和人工步骤见 `acceptance/L13.md`。

不要把 L13 扩大成任意 WCONHIST、WCONPROD、WCONINJE 或 Schedule 文本编辑；增加新的控制模式必须先核对官方关键字语义、单位、范围、回读方式和结果影响，再建立新的版本化合同和真实验收样例。

### L14 ECLIPSE 网格与动态字段二维切片

L14 已完成真实二进制结果读取垂直切片。Worker 对 `EGRID/INIT/UNRST` 建立受限字段索引，跳过官方零长度 `MESS` 控制记录，并从真实 `SEQNUM` 绑定 `UNRST` 时间步；后端通过受控 Artifact Range 提供局部读取，结果页完成字段筛选、活动单元还原、二维层切片和 CSV 导出。BRILLIG Run 117 为 `SUCCEEDED / VALID_FULL`，网格 `20 × 15 × 8`、活动单元 `1639`，`UNRST` 共有 8 个真实时间步。详细证据见 `acceptance/L14.md`；三维边界以后续 L15 为准。

### L15 ECLIPSE 三维几何、完井定位和场值编辑草案（已完成）

从真实 `COORD/ZCORN/ACTNUM` 生成 corner-point 活动网格，支持旋转、缩放、层筛选、按真实场值着色和单元 I/J/K 点击。完井定位只消费 inspection 已解析的 `COMPDAT/COMPDATM` I/J/K，不用虚构井轨迹；字段编辑仅生成 `eclipse-field-edit-draft/1`、`derived-postprocess-only` JSON，不写回 EGRID/INIT/UNRST，也不代表完成重新计算。真实 BRILLIG Run 117 及 23/23 浏览器回归见 `acceptance/L15.md`。

### L16 ECLIPSE Problems 部分成功与真实诊断（已完成）

Worker 已从真实 MSG/ECLEND 提取 Warnings/Problems，并将“有可读输出、Errors/Bugs 为 0、但 Problems 大于 0”的计算标记为 `PARTIAL_SUCCEEDED / VALID_PARTIAL`。Spring 接受并持久化这类部分结果，Vue 保留原始英文诊断及级别/类别/代码，不把 Problems 隐藏、降级或改写成成功。官方 EX6 Run 126 有 34 Problems、34 Warnings、68 条结构化诊断；只有 `ECLEND/FUNRST/MSG/PRT`，所以该模型用于诊断展示，不用于三维。详见 `acceptance/L16.md`。

## 7. 下一阶段任务卡与官方模型验收矩阵

L17—L24 已按下表形成代码和验收记录，其中标注待人工复验的卡先作为并行验收债务收口，不重新开发。新开发从 L25 开始，继续坚持“上传官方模型→平台真实执行/读取→界面展示→历史恢复→自动化→验收记录”；只写代码或只跑官方脚本不能标记完成。

| 卡 | 上传/导入的官方模型 | 本机准确路径 | 验收操作 | 必须看到的通过证据 |
|---|---|---|---|---|
| L17 三维入口 | 单文件 `BRILLIG.DATA` | `C:\ecl\2024.1\eclipse\data\BRILLIG.DATA` | 作为 ECLIPSE 100 导入、运行；在结果页直接点“3D可视化/三维结果” | `SUCCEEDED/VALID_FULL`；EGRID `20×15×8`、活动单元 `1639`；可选择 UNRST PRESSURE、旋转/缩放/点击单元；1280×720 不靠滚动猜入口；历史 Run 可恢复 |
| L18 Problems 诊断 | 项目验收包 `official-ex6-eclipse.zip`（源自官方 ex6） | `C:\Users\Violet\Desktop\Bei\GRDP-Studio\test-data\official-ex6-eclipse.zip`；官方源 `C:\ecl\2024.1\schedule\tutorials\ex6` | 上传、运行、打开“求解诊断” | `PARTIAL_SUCCEEDED/VALID_PARTIAL`；34 Problems、34 Warnings、68 条原始诊断可追溯；分类汇总不丢原文；页面明确说明没有 EGRID/INIT/UNRST，不能显示 3D |
| L19 注入方案 | `ECLIPSE_EX5_INCLUDE.zip` | `C:\Users\Violet\Desktop\Bei\GRDP-Studio\test-data\ECLIPSE_EX5_INCLUDE.zip`；官方源 `C:\ecl\2024.1\schedule\tutorials\ex5` | 先跑无参数基线，再选已存在注入井/日期做一个 WCONINJE 白名单参数方案 | 基线与方案都是真实 Run；参数回读、派生行、源 ZIP 哈希不变；结果差异可解释；非法井/日期/单位被拒绝 |
| L20 ESP 曲线 | `CSW_124_Dual ESP Well.pips` | `C:\Program Files\Schlumberger\PIPESIM2022.1\Case Studies\Well Models\CSW_124_Dual ESP Well.pips` | 上传为井模型，执行新增 ESP 曲线任务 | 与官方 `get_esp_curve_results.py` 一致地读取 `Well_1 / B-ESP`；显示制造商/型号/频率及 Qmin/BEP/Qmax/曲线原始点和单位；CSV 可导出；首卡不写回泵参数 |
| L21 井轨迹 | `CSW_101_Basic Oil Well.pips`；扩展用 `CSW_105_Horizontal Oil Well.pips` | `C:\Program Files\Schlumberger\PIPESIM2022.1\Case Studies\Well Models\...` | 上传，打开井轨迹结果；先基础井，再水平井 | `get_trajectory` 的 MD/TVD/倾角/方位和单位可回读、表格/三维轨迹一致；缺失字段显式为空；不调用 `set_trajectory`、不修改上传源模型 |
| L22 Network兼容矩阵 | 首选 `CSN_313_ESP Optimization.pips`，之后逐个 303/304/308 | `C:\Program Files\Schlumberger\PIPESIM2022.1\Case Studies\Network Models` | 每个模型单独上传、检查、选择 Study、运行 | 每个模型记录“完整支持/部分支持/明确拒绝”，组件、任务、Study、收敛和结果合同有证据；不能用一个模型成功推断全部网络模型成功 |
| L23 WCONPROD 预测初始段 | `official-eclipse-ex3-l07-v2.zip`，上传时主入口选 `EX3.DATA` | `C:\Users\Violet\Desktop\Bei\GRDP-Studio\test-data\official-eclipse-ex3-l07-v2.zip`；官方源 `C:\ecl\2024.1\schedule\tutorials\ex3` | 先运行无参数成功基线，再在主 DATA 依赖闭包的首个 `WCONPROD` 预测生产块中选择 `HORW2`、`FORECAST_INITIAL`、`SHUT`、`ORAT=4500` 创建方案并运行；`OPEN` 仍是可选输入但不作为此包的稳定通过值 | 两次都是真实 ECLIPSE Run；方案显示 WCONPROD、基线 Run、目标值和输出 SHA-256 差异；原 ZIP 哈希不变；非生产井/错误 phase/非 ORAT 被拒绝 |
| L24 COMPDAT 完井段状态 | `ECLIPSE_EX5_INCLUDE.zip` | `C:\Users\Violet\Desktop\Bei\GRDP-Studio\test-data\ECLIPSE_EX5_INCLUDE.zip`；官方源 `C:\ecl\2024.1\schedule\tutorials\ex5` | 先跑无参数基线，再选择 inspection 中 `BASE.SCH:116` 的 `G1 / I14,J2,K1-1`，把 `OPEN` 改为 `SHUT` 后重新运行 | 基线 Run 84 与方案 Run 133 都是 `SUCCEEDED/VALID_FULL`；Problems/Errors/Bugs 为 0；5/5 输出 SHA-256 改变；事件包含 `Applied COMPDAT`；源 ZIP 哈希不变；非法来源/坐标/同状态/COMPDATM 被拒绝 |

L24 后新增一个兼容性前置修复：PIPESIM 官方 Well Models 目录的 43 个 `.pips` 已在当前 Worker 上完成识别回归，全部返回 `READY`；简单模型按现有计算能力分类，复杂模型归入 `legacy_well`，并已开放通用 Nodal、PT Profile、Combined、井轨迹入口。`CSW_131_IPO_SC_GL_Design.pips` 已用官方 PTK 完成真实 Nodal/Profile/Combined 回归，`OilWell_PPO_GLV_FDI_Black.pips` 已用 PTK 真实井名 `Well_1` 完成 VFP 回归。详见 `acceptance/PIPESIM_WELL_MODEL_COMPATIBILITY.md`。专用气举/VFP/ESP/敏感性仍需按结构单独验收，不能把“已识别”冒充成“全部专项计算已完成”。

验收模型选择规则：

- 验收三维只选 BRILLIG；EX6 没有本次三维 Artifact，不能因页面没有三维就判 L15/L17 失败。
- 验收 Problems 只选 EX6；它不是干净计算基线，不能用于 Schedule 参数效果比较。
- 验收 Schedule 参数优先用已验证干净的 EX5；EX2 保留作输入/诊断学习，EX3用于历史—预测与重启，模型职责不能混用。
- 验收 PIPESIM 功能必须上传与官方示例匹配的 `.pips`。不得把网络模型当井模型，也不得把“能打开”当作相应任务已通过。
- 若上传 ZIP，ZIP 内必须保留主 DATA、相对 INCLUDE 和依赖结构；若直接上传 `.DATA` 只适合其依赖可由现有导入链路完整解析的模型。

### L17：三维入口与可发现性闭环（代码已完成，待人工部署验收）

目标不是重做 L15 的渲染器，而是让甲方在运行 BRILLIG 后无需展开 571 条 Summary 或猜测折叠区即可进入三维。结果页已提供明显的“曲线 / 2D / 3D / 诊断”结果导航，按本次 Run 的 Artifact 能力显示；3D 可用时显示网格/字段摘要和直接入口，不可用时列出缺少的 EGRID/INIT/UNRST，不留空白。代码、构建和自动化证据见 `acceptance/L17.md`，尚需在当前 5173 部署用 BRILLIG/EX6 人工确认。

边界与验收：复用 `EclipseGrid3D.vue` 和现有 Range 接口，不复制解析逻辑，不修改解析融合模块。使用 BRILLIG Run 验证 `20×15×8 / 1639`、UNRST PRESSURE、交互和历史恢复；再使用 EX6 Run 验证“无三维数据”的解释。覆盖 1440×900 与 1280×720，入口已放入固定结果导航。人工操作按 `acceptance/L17.md` 执行。

### L18：Problems 分类与处置建议（代码已完成，待真实 EX6 部署验收）

在 L16 原始诊断不丢失的基础上，已按可验证文本模式归组为井势/井求解、非线性与时间步、线性方程收敛、压力/PVT边界和其他诊断；展示出现次数和原文，并使用“建议审阅”措辞。分类不重新解释求解器、不自动改输入、不保证能消除问题。EX6 的 34 Problems/34 Warnings/68 条诊断仍是固定回归基线。详细边界和人工步骤见 `acceptance/L18.md`。

### L19：WCONINJE 受控注入方案（代码已完成，待真实 EX5 部署验收）

已核对 EX5 的真实 `WCONINJE` 行并完成版本化合同 `eclipse-schedule-parameters/3`：只开放一个已存在注入井、一个已有 DATES、一个 `RATE` 数值字段，流体类型和控制模式从检查结果回读。前端、Spring/Worker 双重白名单、隔离副本替换、历史/结果显示和 Worker 测试已完成；真实 EX5 基线与方案计算按 `acceptance/L19.md` 补验。不要把注入输入解释为自动优化结果；EX6 不作为此卡基线。

### L20：PIPESIM ESP 曲线（代码已完成，待当前部署人工验收）

已在 PIPESIM 2022.1 官方 Python 环境复现 `get_esp_curve_results.py`，并按真实返回的频率曲线、Qmin/BEP/Qmax 原始点、单位和泵元数据设计 `pipesim-esp-curves-result/1`。统一控制台现在对 `CSW_124_Dual ESP Well.pips` 运行官方所需 PT Profile/Nodal 两次读取，展示 `Well_1 / B-ESP` 的制造商、型号、频率、级数、曲线原始点和 CSV；请求/结果有 Spring、Worker 双重校验，任务只读，不写回泵、电机、电缆。自动化与实机证据见 `acceptance/L20.md`。当前剩余动作是启动/刷新 5173 部署，用官方模型完成一次人工 Run 并记录 Run ID；不要把 Mock 回归或官方脚本单独运行当作部署验收。

### L21：PIPESIM 井轨迹只读（代码已完成，待当前部署人工验收）

已按 `get_set_well_trajectory.py` 和 `Model.get_trajectory` 的真实 DataFrame 列接入 `trajectory` 任务。平台只调用 `get_trajectory`，以 `pipesim-well-trajectory-parameters/1` 请求和 `pipesim-well-trajectory-result/1` 结果合同保留 MD/TVD/Inclination/Azimuth/MaxDogLegSeverity、单位和空值；前端展示表格、Three.js 三维/俯视/侧视折线、派生 X/Y/Z 和 CSV。官方 `CSW_101_Basic Oil Well.pips` 已实测 `Well_1` 12 点，`CSW_105_Horizontal Oil Well.pips` 已实测 `Hor_Well` 8 点；部署验收需按 `acceptance/L21.md` 逐点复核。只有只读结果与官方脚本逐点一致后，才另立“受控轨迹编辑”任务卡。

### L22：Network兼容矩阵

已完成当前兼容性修复和官方适配器矩阵。旧 Network 结果校验只接受数值标量，导致官方 CSN_313 等模型中的布尔/枚举节点指标被降为 `VALID_PARTIAL`；现在系统/节点标量允许安全的 native bool/非数字枚举文本，仍拒绝数字字符串、非有限数和未清理哨兵，Profile 数值曲线门槛不放宽。

当前 Python Toolkit 实机证据：`CSN_313_ESP Optimization.pips`、`CSN_303_Looped Network.pips`、`CSN_304_Offshore Oil Network.pips`、`CSN_308_Water Injection Network.pips` 均在 `Study 1` 完成 `Completed / VALID_FULL`，拓扑和结果数量见 `acceptance/L22.md`。需要在当前 5173 部署中逐个上传、运行和记录 Run ID；官方 PTK 直接成功不等于浏览器部署已经验收。

L22 不扩展为任意 Network 编辑、批量运行或“所有模型全支持”。L23 已完成 EX3 预测段受控 WCONPROD，L24 已完成 EX5 单条 COMPDAT 状态方案；两者均不等于任意 Schedule 编辑。

L23 只覆盖 EX3 主 DATA 依赖闭包中首个 `WCONPROD` 预测生产块里的 ORAT 行，采用 `eclipse-schedule-parameters/4`；L24 只覆盖 EX5 已检查到的单条 `COMPDAT` 状态，采用 `eclipse-completion-parameters/1`，精确绑定源文件、行号和 I/J/K。两卡都不是任意 Schedule 文本编辑，也不是自动优化。后续 L25 必须先完成新的官方语义、单位、写回格式和真实响应确认，再决定是否扩展另一个单字段方案；暂不进入跨模拟器自动参数传递。

## 8. 测试入口与验收记录

以下从项目根目录执行，先定位工具链。它们是入口，不是本次已执行通过的声明；只运行与当前变更相关的检查，发布里程碑再做必要的组合回归。

```powershell
# Java 21 + 本机已定位的Maven；mvn不在PATH时用已核实绝对路径
mvn -f backend/pom.xml test
dotnet test worker/tests/Worker/Grdp.SoftwareIntegration.Worker.Tests.csproj
# 使用能运行现有单测的Python；PTK实机调用另用Worker配置的官方Python
python -m unittest discover -s worker/tests/PythonNormalization -p "test_*.py"
node --test vue/tests/unit/result-presentation.test.js
npm --prefix vue run build
npm --prefix vue run test:e2e -- tests/e2e/software-integration-demo.spec.js --project=chromium
```

项目根目录`npm test`当前是占位失败脚本；不要用它代表测试套件。Playwright配置使用4173测试服务；Mock页面通过不代表5173实际部署或真实模拟器通过。

每卡记录到`docs/software-integration/acceptance/Lxx.md`，并简要更新`PROGRESS.md`的软件集成进度：

```text
任务、日期、用户可完成的操作：
本轮改动文件（与接手时已有改动区分）：
官方依据：文件路径、章节/API、适用版本：
测试/构建：命令、实际结果、未运行项：
代码/部署标识：commit及未提交差异说明或构建哈希、Worker generation：
输入：模型版本、源/派生包SHA-256、Study/Case、参数快照：
真实运行：Run ID、终态、结果合同、ECLEND/收敛信息、清理：
浏览器：操作步骤、1440x900及1280x720、刷新/历史恢复：
数值核对：变量、对象、单位、时间点、参考值、实际值、容差：
结论：仅展示 / 真实计算通过 / 部署演示通过 / 阻塞：
未完成与下一卡：
```

真实PIPESIM调用串行，不打断共享许可证上的任务。源文件不可变、结果真实性和模型版本绑定贯穿所有卡。资料不足或许可证失败时记录真实原因，继续不受影响的开发；不能用Mock替代成功计算验收。

## 9. 可直接发送给 Luna 的任务提示词

### 下一轮：L26（当前应直接发给 Luna）

```text
请在当前GRDP-Studio项目直接执行开发任务。
先阅读AGENTS.md和docs/software-integration/luna-development-handoff.md，再按其中要求阅读需求及代码入口。
先读取 acceptance/L22.md、L25.md、L26.md 和当前 git diff；保留现有未提交改动，不重做已通过的 ECLIPSE 包解析、二进制字段读取、三维渲染、COMPDAT 合同和 PIPESIM Network 主链路。
本轮任务是 L26“PIPESIM Network Choke Bean Size 受控方案”。第一需求是甲方可展示、可真实计算、可人工验收；不得修改解析融合部分。

官方依据：
1. C:\Program Files\Schlumberger\PIPESIM2022.1\Developer Tools\Python Toolkit\Examples\get_set_value.py，确认使用 `model.get_value`/`model.set_value` 读写 Choke 的 `Parameters.Choke.BEANSIZE`。
2. C:\Program Files\Schlumberger\PIPESIM2022.1\Developer Tools\Python Toolkit\Help，核对官方 Toolkit 的 Model、Study、Choke 参数和 Network Simulation 语义；不得根据页面字段名猜测单位。
3. 官方模型 C:\Program Files\Schlumberger\PIPESIM2022.1\Case Studies\Network Models\CSN_301_Small Network.pips；`Study 1` 中 `Choke` 原 BeanSize 为 `2.0 in`，验收目标为 `3.0 in`。

必须先做临时隔离副本尖峰：只在临时目录通过官方 Python Toolkit 把 `Choke` BeanSize 改为 `3.0`，回读确认，再执行官方 Network Simulation；记录状态、结果合同、系统/节点/支路业务结果、源包 SHA-256 和清理状态。尖峰不通过时停止新增合同，保留证据并报告，不能靠文件哈希或页面静态变化宣称业务结果改变。

尖峰已通过，继续维护独立版本化合同 `pipesim-network-choke-bean-size-parameters/1`，不放宽旧的 `pipesim-network-parameters/1`。Spring 与 Worker 双重校验同版本成功无参数 Network 基线、Study、Choke、原 BeanSize 和正有限目标值。Worker 只在隔离副本调用官方 `set_value`，回读通过后才执行 Network Simulation，并记录 `Applied Network Choke BeanSize ...` 审计事件；上传源包及哈希不变。

前端提供“创建 Choke Bean Size 方案”，明确展示 `Study 1`、`Choke`、`2.0 in→3.0 in`、基线 Run 和官方调用说明。结果页必须展示 Network 结果合同、审计消息和至少一个真实系统/节点/支路结果差异；刷新和历史恢复后参数及结果仍存在。该卡不伪造 ECLIPSE 三维网格，也不把一次 Choke 参数方案宣称为任意 Network 编辑。

测试覆盖 inspection/参数/结果严格合同、未知 Choke、非有限值、零负值、同值、路径穿越、错模型/错 Study/错基线、源包完整性和回读失败。执行 Python、Worker、后端软件集成定向测试、Vue unit/build/E2E；后端必须使用 Java 21 和可用 Maven。最后用当前 5173 真实上传 `CSN_301_Small Network.pips`，先无参数运行基线，再创建 Choke 方案运行，记录 Run ID、模型 SHA、原值/目标值、系统结果差异、清理状态和浏览器证据，更新 acceptance/L26.md；未完成真实部署运行时状态保持“代码完成/待验收”，不要自动提交 Git。
```

### 后续轮：完成 L17 后按矩阵替换为下一卡

```text
继续执行docs/software-integration/luna-development-handoff.md中的L19任务。
先读取已完成任务的acceptance记录和当前git diff，确认依赖与未提交工作，不重做已通过且仍适用的部分。
本轮完成该任务卡的实现、相关测试、真实案例验证及文档。按任务要求接入现有统一控制台，第一需求是可展示、可计算、可验收，解析融合部分不变。
环境阻塞时给出具体证据，继续独立可完成部分，不把未完成项标记通过。完成后更新本卡acceptance记录和PROGRESS.md，报告下一卡编号；不要自动提交Git。
```

将第二段中的任务号按 L25 顺序替换，并严格使用第7节指定的官方模型。若一张卡需多轮，对同一卡续做并读取已有证据；按功能和验收结果结束，不能按对话轮数假定完成。
