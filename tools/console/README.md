# 主控制台发布与核验工具

唯一用户入口为 `http://127.0.0.1:5173/ipr?workspace=software-integration`；8080为主API，5150为求解Worker。工具针对现有Windows + Docker开发部署，要求PowerShell7；软件安装、许可证、Python与数据库由实际部署提供，不包含个人凭据。不会创建另一个用户验收前端。

## 工具责任

| 脚本 | 用途与边界 |
|---|---|
| `preflight.ps1` | 只读检查主页面、代理API、任务清单和Worker状态；错误退出1；能力正常不等于真实求解通过 |
| `backup-main.ps1` | 备份原MySQL和模型目录；在全新验证库恢复并逐行/文件SHA检查。备份必须在仓库和源目录之外；遇未知Junction拒绝；不恢复覆盖主库 |
| `archive-validation.ps1` | 临时环境项目/版本/Run API快照及文件归档，不伪称H2 SQL dump |
| `start-main.ps1` | 以已认证备份为前提，核对实际主进程/发布脚本SHA，受控替换现有主Java和Worker，不改数据库/存储；不是首次冷启动或自动回滚程序 |
| `verify-preservation.ps1` | 核对全部原主键/列、原文件及原可见项目仍保留；允许新增记录，不允许旧行变化 |
| `verify-network.ps1` | 在主API真实计算N301/N312 Network和系统分析；复用既有未改动的冻结参照/比较器，不启动临时Spring/前端 |
| `retire-validation.ps1` | 仅归档已完成后，核对明确的四套临时进程身份、端口和空闲状态再停止；不扫描杀所有Java/Node，不删文件 |

## 后续发布顺序

1. 先预检主环境、确认无运行/队列，冻结正在运行的旧JAR/DLL/配置，备份到新私有目录。不得先覆盖正在运行的target再说备份了旧版。
2. 在独立输出目录构建及运行相关测试，不在共享运行目录递归产生发布物。
3. 使用确认属于主环境的容器内Java PID和Windows Worker PID执行 `start-main.ps1`，参数提供认证备份、不可变JAR、Worker发布目录、原StorageRoot、实际eclrun路径。脚本拒绝错误目标；不强制杀忙碌任务或自动初始化另一库。
4. 检查主入口，再执行既有真实验收脚本，显式传入 `-ApiBase http://127.0.0.1:5173/api/software-integration`、`-WorkerBase http://127.0.0.1:5150`（该脚本支持时）。EX5使用 `verify-eclipse-multiwell-platform.ps1` 与 `verify-eclipse-schedule-export.ps1`，P101使用 `verify-pipesim-tubing-platform.ps1`。
5. 校验 `verify-preservation.ps1`，受控重启主Java/Worker后重复历史读取与真实导出重导入复算，记录新generation。

调用时安装和证据路径由参数显式提供；不要复制他人PID或测试Run ID。输出目录必须新建，失败文件留存。对系统分析指定官方安装根、Python3.9路径、已有冻结原生证据目录、新输出目录与原存储根；不得重新生成参照来掩盖回归。

当前开发容器本身不会自动启动Java，Windows没有新增开机Worker服务。Docker/整机重启后的冷启动封装尚不是这些脚本的责任；恢复时使用同一发布物、主库及端口，禁止另起H2/临时前端顶替。真正启动失败应停止接收任务并按已冻结的发布物人工恢复，不能仅检查HTTP200就宣布计算正常。

运行日志、数据库dump、官方模型/求解输出和私有证据不提交Git。当前发布结果和本机模型位置见 `docs/software-integration/execution/UNIFIED-PUBLISH/acceptance.md` 及人工验收手册；待验功能不因入口统一而自动标绿。
