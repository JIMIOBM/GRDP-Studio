# CC02-A：官方耦合候选包离线预检

日期：2026-10-08。状态：离线清单工具代码完成，官方候选包预检已执行；原生/API 联合求解尚未通过。

## 交付

`tools/iam/coupling_preflight.py` 使用 Python 3.9+ 标准库，不需要个人路径、第三方依赖或本地未提交探针。输出 `iam-coupling-preflight/1`：相对文件清单、大小/SHA-256、确定性的清单哈希、候选模型后缀、安装目录/适配器目录/MPI 布局观察，以及未验证门槛。

这是开发预检工具，**尚未接入平台耦合上传/运行按钮**。它不运行模拟器、不占用商业许可、不修改系统服务/环境变量、不转换模型。

安全与结论边界：

- 只读源工程，报告只能新建在工程外；已有报告不能覆盖。拒绝路径穿越、符号链接/junction、大小/文件数量超限、大小写路径碰撞、读取失败和扫描中可检测的文件变化。
- `.iam` 当作不透明原生文档处理，不使用 BinaryFormatter 反序列化，不根据报告 XML 或文件名猜映射。扫描是内容快照，不证明外部文件不会再变化；打开原生工程前应再次核对哈希。
- 目录存在不是已验证的软件版本；适配器目录名称不是兼容性认证；MPI 文件存在不是服务/凭据可用。INCLUDE 闭包、IAM 嵌入文件/引用、单位/控制映射、许可均保留 NOT_VERIFIED。
- `coupledSolveVerified` 和 `canCalculate` 始终为 false。即使所有文件存在也不得变成 READY。CLI 成功采集但未过联算门时退出码 3；输入/读取/报告写入错误是 2。

## 官方依据

本轮按 PDF 阅读技能核查 `<IAM_HOME>/IAM REST API Reference Manual.pdf`：PDF 页 8（手工映射不强制单位类型一致）、15（PipeSimModel 与 StingrayModel、ZIP/文件内容传递）、38（异步状态后等待 Idle）、40（原生工程及嵌入模型文件）、43（保存/关闭释放资源）。这些约束决定：离线清单不能代替原生模型发现、映射验证和联合数值验收。

## 本机真实结果

源：`<IAM_SAMPLES>/Legacy Cases/Reservoir to Network/`，入口 `Reservoir to Network.iam`。

- 17 个文件，9,605,934 字节。
- 清单 SHA-256：`ff0bfc45e041e818ac7d4349c69a889bf02bf0bbe8a999bce7c3f0fdf5bae6c0`。它对应本工具版本的规范化 JSON 清单，不是原生工程效果/收敛证明。
- 网络候选：`SIS2/SIS2.bpn`；没有 `.pips`。油藏候选：BRILLIG.DATA、GULFAKS.DATA、GFE300.DATA；不因此认定三者都参与同一次耦合。
- IAM REST 可执行文件存在；Stingray 目录观察到 2018/2019/2020/2021，没有同名 2022 目录。必须核对官方支持组合，不能直接断言已有适配器必然不兼容或兼容。
- 配置的 MPI 根目录有 `bin/mpiexec.exe`，没有此前 IAM 2022.1 R2SL 探针检查的 `intel64/bin/mpiexec.exe`；需要管理员/厂商确认兼容布局、服务和凭据。本轮没有安装服务或创建系统级链接。
- 预检为 BLOCKED；8 个联算门仍为 NOT_VERIFIED。扫描后重新计算，17/17 源文件清单与报告一致，没有修改官方原件。

完整 JSON 保存在本机的验收输出目录，不提交商业模型、二进制文档或个人环境文件；交接人员按下面命令生成自己的报告。

## 重复执行

从仓库根目录运行，所有占位路径换成实际安装/工程路径。报告父目录须已存在，文件名须未使用；源目录不接受 junction。

```powershell
python tools/iam/coupling_preflight.py --package "<IAM_SAMPLES>/Legacy Cases/Reservoir to Network" --entry "Reservoir to Network.iam" --iam-home "<IAM_HOME>" --pipesim-home "<PIPESIM_HOME>" --eclipse-home "<ECLIPSE_HOME>" --mpi-root "<MPI_ROOT>" --output "<EVIDENCE_DIR>/preflight.json"
python -m unittest discover -s tools/iam -p test_coupling_preflight.py -v
```

15 项标准库单元测试在当前工作树和独立发布候选均通过，包括全部文件存在也不能通过原生门、退出码、清单稳定性、链接/碰撞/读失败/扫描增长/限额和禁止覆盖报告。本轮官方操作仅为离线扫描与再次核对源哈希，未新增原生或 REST 联算 Run。

## 下一门：CC02-B，不跳到 CC03

1. 工程负责人/有许可环境先用官方 IAM 读取隔离副本，确认嵌入标记、实际引用、各模型类型/版本、物理量/单位/控制、日期步进和原生映射；不能重命名 `.bpn` 冒充现代模型。
2. 厂商或管理员确认兼容 IAM/PIPESIM/ECLIPSE/MPI/连接器/许可组合；确需转换，记录原件、工具版本、日志、前后哈希和对象映射差异。
3. 相同冻结工程先通过原生一步和多个报告点，再经 REST 复现，保存双方响应、求解诊断和资源关闭证据。HTTP 200、Idle、isFinished、文件哈希变化均不能单独作为联合求解通过标准。
4. 两侧有效边界改参均有真实响应和单位/时间对照后才开展 CC03 的正式持久化耦合接入。外部兼容未满足期间继续 CC01/CC05 的独立改参功能，不能为展示补造数据。
