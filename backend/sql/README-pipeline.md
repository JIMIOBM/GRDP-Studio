# 单井管束能力

入口为“当前井 → 管束能力”。井身份统一引用数据管理，不建立另一个井档案；每井一份拓扑、组分 PVT、温度参数和边界工况。所有页面使用现有数据管理/试井页面的布局，结果视图采用单画布切换和可拖动图例。

## 数据与计算关系

1. 管网拓扑维护管道几何、连接关系、设备及额定限值，是这些数据的唯一编辑来源。
2. PVT 模型维护完整气体摩尔组成和 PR/SRK/BWRS 方法，独立于数据管理的固定温度 PVT 曲线。
3. 温度模型维护材料层、敷设条件、环境和每管气体导热系数 λ；密度、Cp、Standing 黏度由统一物性服务提供。
4. 边界条件维护全部工况的井口供气量、压力、温度及下游节点分输量、压力。输入值不再设置“数据用途”。
5. 管流计算按工况遍历单井树状管网，保存同一批管道、设备和水合物评价结果；关键设备、水合物及实测对比读取这份快照。

每条边界是独立稳态工况，不强制24小时、整点或等时间间隔。时间自由输入，显示 YYYY/MM/DD hh:mm，保存时校验真实日期和唯一性，规范保存到分钟；输入未完成时不弹格式错误。最多744条工况；计算逐行报告失败，其他行继续。失败时刻图表断线。

树状分支按下游分输量守恒确定输量；一个未知末端可由井口总量求余额，多个未知支路不平均分摊。分支采用井口温压正向计算。无中途分输的单线可由两端压力反算流量或由末端压力/流量反算入口压力。普通连接点未取气时分输量可空。其余实测值用于结果对比。

## 页面职责

| 页面 | 输入与操作 | 输出 |
| --- | --- | --- |
| 管网拓扑结构 | 编辑当前井唯一图、管道和同位置有序设备并保存 | 已保存连接关系和参数 |
| 边界条件 | 可收起参数设置；模板下载、本地导入、保存；序号独立列 | 全部工况的可编辑节点数据 |
| 管流计算 | 热力与摩阻方法、JT；计算全部工况、保存 | 每管入口/出口温压和流量，出口温度/压力/流量时间图 |
| 关键设备 | 只读拓扑设备资料，前往管流计算更新 | 每工况每设备温压、流量、功率、限值和裕度，设备时间图 |
| 水合物 | 当前组分PVT、经验方法、可用水/水条件未知；计算、保存同一批结果 | 每工况每管最小温差及真实位置、同点温压和平衡温度，温度对比/裕度时间图 |
| 结果对比 | 同一批结果和其边界实测快照 | 节点计算值、实测值及偏差 |
| 温度模型六节点 | 可收起参数设置；导入、计算、保存 | 数据列表/结果分析；材料层可增删；已保存有效结果优先显示 |

“约束条件”仅为导航目录，点击展开或收起子节点，不打开页面；冲蚀、冻堵仍停用。

设备按设备节点及节点内顺序标识，名称不作为唯一键。阀门使用局部阻力系数，压缩机使用固定压缩比和效率；设备后的状态传给全部下游支路。限值只评价设备两端最大压力和压缩机所需功率，不含特性曲线、喘振和堵塞流量边界。计算成功与设备满足限值分别记录。

水合物使用有原始文献依据的 Safamirzaei（2015）气体相对密度经验式，不是老师原公式或完整组分相平衡模型。气体比重由实际 PVT 摩尔组成计算，沿程在同一点以压力计算平衡温度并与实际温度比较；设备前后点也检查。只有全部采样点可评价时才报告全管最小裕度。越出本实现采用的文献验证范围显示“未评价”，不返回伪造安全温度、不改气样。水条件未知时，进入温压形成区只给条件性判断；不预测生成量、速率或堵塞。公式、单位、验证范围见[水合物方法](hydrate-method.md)。

温度前四项分别实现老师资料的内壁换热、材料层导热、外部换热和总传热。材料/环境输入独立保存；λ由用户提供。总传热独立预览仍使用串联热力内核，管流全工况入口使用树状网络求解器。Z/Cp检查点只用于独立物性计算展示，不要求先保存检查点才能管流联算。算法细节、资料疑点与适用范围见[物性算法](gas-properties-methods.md)。

## 数据库

本模块唯一建表文件为 [pipeline_capacity.sql](pipeline_capacity.sql)，以最终结构创建八张表，不包含历次补丁、临时清理函数或示例数据；末尾保留八张表完整的注释 DROP，按依赖顺序回退。

| 表 | 用途 |
| --- | --- |
| pipeline_model | 每井可编辑边界和计算参数、最后实际计算输入快照、乐观锁版本 |
| pipeline_topology | 每井拓扑画布与版本 |
| pipeline_topology_node | 拓扑节点及设备参数 |
| pipeline_topology_edge | 管道连接和几何参数 |
| pipeline_pvt_model | 每井完整组成和状态方程方法 |
| pipeline_gas_model | Z/Cp独立检查点、计算结果与组成来源 |
| pipeline_temperature | 每管温度参数、材料层和已保存温度结果 |
| pipeline_batch_run | 一次保存的全工况输入、拓扑和管道/设备/水合物结果快照 |

设备和管段从拓扑派生的实际计算参数包含在JSON快照中，不另设重复明细表。已删除旧单次运行表及其接口。修改边界、拓扑、PVT、温度参数、水条件或算法版本会使批量结果失效，不能混用不同版本结果。保存仅接受服务器计算令牌，校验当前井及源版本并在同一事务内保存，重复保存同一令牌不产生重复记录。

## 代码索引

后端源码位于 ../src/main/java/com/grdp/studio/pipeline/，对应测试位于 ../src/test/java/com/grdp/studio/pipeline/。

| 文件或文件组 | 职责 |
| --- | --- |
| PipelineController / PipelineStorage / PipelineModelSections | 读取当前井模型、独立保存边界草稿 |
| PipelineBatch | 全工况计算、临时结果、来源校验和事务保存 |
| PipelineNetworkCalculator | 树状连接、分输守恒、设备明细与沿程水合物汇总 |
| PipelineCalculator / PipelineDtos | 水力热力内核和工程单位契约 |
| PipelineBoundary / PipelineBoundaryTime / PipelineFlowTopology | 边界校验、分钟时间、拓扑解析与独立热力预览 |
| PipelineTopology / PipelineWellContext | 拓扑持久化、单井数据归属 |
| PipelinePvtModel / PipelinePvtComposition / PipelineGasModel | 组成来源及Z/Cp检查点 |
| PipelineGasProperties / PipelineStandingViscosity / PipelinePvtThermalProperties | EOS和统一物性服务 |
| PipelineTemperature / PipelineTemperatureCalculator | 温度六节点所用参数、计算与独立热力预览 |
| PipelineHydrateModel | 文献经验平衡温度、方法元数据和适用性判断 |

前端主页面位于 ../../vue/src/views/PipelineCapacity/；统一状态在 ../../vue/src/composables/usePipelineWorkspace.js；接口在 ../../vue/src/api/pipelineCapacity.js；pipeline开头的工具与测试在 ../../vue/src/utils/。页面职责由名称对应：Flow、Boundary、Topology、PvtModel、Temperature、GasProperty、Constraint、Comparison。PipelineTimeChart负责时间图；PipelineChart仍供总传热的独立温度分布预览使用。

## 接口与验证

主要入口：GET /pipeline-capacity/model、PATCH /pipeline-capacity/model/boundary；POST /pipeline-capacity/batch/calculate、POST /pipeline-capacity/batch/save、GET /pipeline-capacity/batch/latest。拓扑、PVT及温度子模块继续使用各自的保存/计算接口。

从项目根目录执行：

~~~powershell
mvn -f backend/pom.xml '-Dtest=Pipeline*Tests' test
node --test vue/src/utils/pipeline*.test.js
npm --prefix vue run build
~~~

测试覆盖独立公式数值、单位、来源与跨井隔离、事务和并发、分支守恒、设备前后状态、限值、分钟工况、失败断线、水合物最小裕度与未评价、保存重载及页面交互。测试通过验证的是实现一致性，不替代真实气样或专业软件验收。
