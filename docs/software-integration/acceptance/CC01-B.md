# CC01-B：地层压力字段校验与请求构造收口

日期：2026-10-08。承接 CC01-A；这是一项可在 GitHub 基线上独立运行的参数编辑切片，不宣称全部参数面板已经统一。

## 已实现及边界

- 压力方案的适用性、严格数值校验、请求快照抽出为纯函数 `pressureScenarioParameters.js`。继续使用 `pipesim-well-parameters/1` 与 `reservoirPressurePsi`，不改变 Worker 的求解方向、模型对象或单位换算。
- 空值/非法数值在地层压力输入框下提示；提交被阻止，不重复弹窗。纠正后提示消失，关闭方案或切换任务/版本后清除校验状态。
- 不支持当前模型/任务是上下文错误，不错误地归因于输入的压力值。基础气井仍保留 nodal/profile/combined，黑油液井仍只开放 nodal 压力方案；不扩展到注入井或所有 Study。
- `0 < pressure <= 100000` 是既有平台编辑范围，不是官方 PTK 的通用物理范围。单位仍为 psia（绝压）；Worker 仍负责官方 describe/get/set/get 回读与单位拒绝。前端数值有效不代表原生求解成功。
- 其他参数面板的逐字段提示、完整逐对象能力原因仍待下一切片；没有修改解析融合、登录、模拟器或通用界面样式。

官方依据：`<PTK>/Help/sixgill.html` 的 Completion.RESERVOIRPRESSURE 与 Model.describe/get_value/set_value；完整阅读 `<PTK>/Examples/get_set_value.py`，其示例修改的是 N301 的 Choke，不把示例对象名、单位或取值直接套到井压力。此次复核了现有 Worker 压力合同和 psia/回读保护。

## 人工验收

文件相对于开发人员自己的 PIPESIM 安装目录，两者本机均已核对存在：

| 上传文件 | 操作与预期 |
|---|---|
| `Case Studies/Well Models/CSW_101_Basic Oil Well.pips` | 选已有 Study、节点分析，勾选“使用地层压力方案”。保持空值点击运行：字段下只出现校验提示，不创建 Run。输入有效压力再运行：提交准确的 psia 参数快照；原始模型不改动。原生是否成功由真实诊断确认。 |
| `Case Studies/Well Models/CSW_102_Basic Gas Well.pips` | 选 PT 剖面或组合运行，填有效压力再算。任务不能偷偷改成 nodal；继续保留基础气井 PT 入口压力的现有说明。 |

再做反例：出错后切到 PT/节点分析，或关闭方案；旧字段错误应消失，原值运行请求中的 parameters 为 null。已提交运行不能因为编辑/重复点击产生第二个任务。

刷新开发页面即可加载当前源码；部署环境需发布对应前端构建。没有改变 Spring/Worker 合同，不要求为本切片替换商业引擎。

## 回归及证据范围

可重复命令：

```powershell
node --test vue/regression-tests/pressure-scenario-parameters.test.js vue/regression-tests/run-feedback.test.js vue/regression-tests/software-integration-client.test.js vue/regression-tests/run-type-options.test.js
cd vue
npx playwright test tests/e2e/software-integration-demo.spec.js
npm run build
```

新增纯函数测试覆盖精确边界、空值、字符串/布尔值、NaN/Infinity、不支持任务、未启用方案和独立快照；新增浏览器测试覆盖就地错误、无 POST、无弹窗、纠正后准确提交和关闭/切换后的原值运行。

浏览器使用 API 合同夹具，不是本轮重新原生计算通过的证明。代码先从已发布 HEAD 构造独立候选再验证、提交，避免把本地未提交 ESP/井筒/调度功能混入本卡。

已复核请求与现有 Worker 的严格数值/psia/回读保护一致；关闭方案仍为 parameters:null，基础气井 PT/combined 不改变任务。独立候选：41 项参数/反馈/能力单元、33 项浏览器回归及生产构建通过。本地加上角点几何和场值竞态检查共 53 项单元通过，生产构建通过；现有 Sass 弃用和大包警告仍在，不属于本卡修改范围。

当前工作树的软件集成页面及 Store 生命周期浏览器回归为 83 项通过（含本卡两项），覆盖受理互斥、禁止重复 POST、取消、轮询恢复、导航竞态和历史选择。较新的本地工程功能未并入本卡提交；不把 83 项夹具通过称为 GitHub 全部工程功能交付或商业软件全模型实跑通过。
