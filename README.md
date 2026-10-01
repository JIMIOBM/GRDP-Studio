# GRDP Studio

<p align="center">
  <img src="vue/config/logo.svg" width="72" height="72" alt="GRDP Studio logo">
</p>

<h3 align="center">面向油气工程的可视化计算工作台</h3>

<p align="center">
  从模型导入、参数校验，到本机求解和结果展示，统一在浏览器中完成。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Windows-0078D4?logo=windows&logoColor=white" alt="Windows">
  <img src="https://img.shields.io/badge/frontend-Vue%203-42B883?logo=vuedotjs&logoColor=white" alt="Vue 3">
  <img src="https://img.shields.io/badge/backend-Spring%20Boot-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot">
  <img src="https://img.shields.io/badge/worker-.NET%2010-512BD4?logo=dotnet&logoColor=white" alt=".NET 10">
</p>

> 当前仓库重点是 **PIPESIM Well、PIPESIM Network 和 ECLIPSE 100 的可展示计算链路**。模拟器、许可证、模型文件和业务数据属于本机环境，不随 Git 仓库分发。

## 先看懂：一次计算发生了什么

```mermaid
flowchart LR
    U[浏览器用户] --> V[Vue 3 页面<br/>5173]
    V -->|HTTP / SSE| B[Spring Boot<br/>8080]
    B --> DB[(MySQL<br/>项目、版本、运行、结果)]
    B --> R[(Redis<br/>队列与状态)]
    B --> W[本机 Worker<br/>5150]
    W --> P[PIPESIM /<br/>PIPESIM Network]
    W --> E[ECLIPSE 100]
    P --> W
    E --> W
    W --> B
    B --> V
```

浏览器只访问 Spring Boot；Worker 只在本机回环地址监听并负责调用模拟器；Spring Boot 负责项目、模型版本、运行状态、事件和结果的持久化。这样既能把页面与求解器解耦，也能在没有模拟器时先启动页面进行演示。

### 计算链路时序

```mermaid
sequenceDiagram
    participant User as 用户
    participant Web as Vue 页面
    participant API as Spring Boot
    participant Worker as 本机 Worker
    participant Sim as PIPESIM / ECLIPSE
    participant DB as MySQL

    User->>Web: 导入模型并提交
    Web->>API: 创建模型版本 / 运行
    API->>DB: 校验 READY 版本并记录 RUNNING
    API->>Worker: 下发受控计算任务
    Worker->>Sim: 调用本机模拟器
    Sim-->>Worker: 日志、状态、结果文件
    Worker-->>API: 回传进度 / 结果 / 错误
    API->>DB: 保存结果与历史记录
    API-->>Web: 状态、曲线、诊断信息
```

## 能力概览

<table>
  <tr>
    <td align="center"><img src="vue/src/assets/ribbon-icons/PIPESIM.svg" width="48" alt="PIPESIM"><br><b>PIPESIM</b><br>井模型与网络模型适配</td>
    <td align="center"><img src="vue/src/assets/ribbon-icons/Eclipse.svg" width="48" alt="ECLIPSE"><br><b>ECLIPSE 100</b><br>DATA 模型计算链路</td>
    <td align="center">📈<br><b>结果展示</b><br>状态、曲线与历史运行</td>
    <td align="center">🧩<br><b>工程化集成</b><br>版本、事件与产物管理</td>
  </tr>
</table>

| 运行模式 | 能看到什么 | 是否需要本机模拟器 |
| --- | --- | --- |
| 页面模式 | 页面布局、工作台；登录需认证服务 | 否（模拟器） |
| 软件集成模式 | 模型导入、校验、Worker 状态和运行记录 | 执行真实计算时需要 |
| 完整计算模式 | 调用 PIPESIM/ECLIPSE 并展示真实结果 | 是，需要许可证 |

没有模拟器时，网页和后端仍可启动，对应计算能力会显示为不可用；登录和旧平台页面还需要本机已有的认证及原平台服务。

## 快速开始

### 1. 准备环境

- Windows 10/11、Git、Docker Desktop；
- JDK 21、Apache Maven 3.9.x、Node.js 20.19+ 或 22.12+、.NET 10 SDK；
- 需要真实计算时，再安装 PIPESIM 2022.1、Python Toolkit、ECLIPSE 2024.1 及相应许可证。

首次使用前可以检查工具链：

```powershell
git --version
docker --version
docker compose version
java -version
mvn -version
node --version
npm --version
dotnet --version
```

如果 `mvn` 或 `java` 无法识别，请先把 JDK 21 的 `JAVA_HOME` 和 Maven 的 `bin` 目录加入当前用户的环境变量；README 中的 Maven 命令默认从 `PATH` 查找 `mvn`。

模拟器、许可证、模型文件和业务数据不随 Git 仓库分发。

## 新主机还需要的外部服务

本仓库不包含原 GRDP 平台的认证服务和旧业务 API。默认登录链路还需要：

| 服务 | 默认地址 | 用途 |
| --- | --- | --- |
| 认证服务 | `http://127.0.0.1:9919` | 登录、会话和身份校验 |
| 原平台 API | `http://127.0.0.1:9920` | 旧业务页面和兼容接口 |

因此，陌生主机仅拉取本仓库可以完成页面、后端和软件集成 Worker 的本地启动，但不能凭本仓库单独完成登录和旧平台业务。要完整使用现有工作台，还必须先按部署环境安装并启动这两个外部服务；如果只验证软件集成 API，可以暂不安装模拟器和原平台，但登录页面不会成功。

### 2. 克隆代码并进入项目

```powershell
git clone -b violet/feature/software-integration-ui https://github.com/JIMIOBM/GRDP-Studio.git
cd GRDP-Studio
```

### 3. 初始化本机配置

```powershell
Copy-Item backend/.env.example backend/.env
```

`backend/.env` 只用于本机和 Docker Compose，禁止提交。Spring Boot 聚合命令所需的环境变量在启动步骤中显式设置。默认端口如下：

| 服务 | 端口 | 用途 |
| --- | ---: | --- |
| MySQL | 32000 | 项目、模型、运行和结果数据 |
| Redis | 6379 | 队列、缓存和状态 |
| Spring Boot | 8080 | 浏览器唯一访问的后端 API |
| Worker | 5150 | 本机模拟器适配与进程监督 |
| Vue/Vite | 5173 | 浏览器开发页面 |

真实计算机器至少要检查 `worker/appsettings.json` 中 `Worker.StorageRoot` 和 `Worker.PipesimHome`。Worker 默认从 PIPESIM 安装目录寻找 `PythonToolkitModules.zip`，从 `%LOCALAPPDATA%\Programs\Python\Python39\python.exe` 寻找 Python；如果本机路径不同，可在 `Worker` 节点补充 `PipesimPtkPath`、`PythonPath` 和 `EclrunPath`。这些路径应使用本机配置，不能写入共享提交。

### 4. 安装前端依赖

```powershell
npm ci
cd vue
npm ci
npx playwright install chromium
npm run build
cd ..
```

### 5. 推荐启动方式

项目提供了聚合开发命令，页面演示不需要手动维护多个终端：Docker 在后台运行，`npm run dev` 会同时启动 Spring Boot 和 Vue。

**基础设施：MySQL 和 Redis**

```powershell
docker compose --env-file backend/.env -f backend/compose.yml up -d --wait
docker ps
```

应该看到 `grdp-mysql` 和 `grdp-redis`。Docker 数据卷会保留本机数据。

首次启动后端时，软件集成所需的 `software_integration_*` 表会按当前代码幂等创建，不需要手动导入数据库备份。

**页面模式：后端和前端**

确认 `JAVA_HOME` 指向 JDK 21。Spring Boot 不会自动读取 `backend/.env`，所以首次启动时在同一个 PowerShell 窗口设置后端连接参数：

```powershell
$env:MYSQL_URL='jdbc:mysql://127.0.0.1:32000/grdp_studio?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false'
$env:MYSQL_USERNAME='grdp'
$env:MYSQL_PASSWORD='grdp_dev_password'
$env:REDIS_HOST='127.0.0.1'
$env:REDIS_PORT='6379'
$env:REDIS_PASSWORD='grdp_redis_password'
$env:SERVER_PORT='8080'
$env:ORIGINAL_PLATFORM_BASE_URL='http://127.0.0.1:9920'
npm run dev
```

该命令通过 `concurrently` 聚合启动 Spring Boot 和 Vue。浏览器访问：<http://127.0.0.1:5173/login>

如果要直接使用 PIPESIM 或 ECLIPSE 的真实计算链路，可用统一启动命令同时启动 Spring Boot、Vue 和本机 Worker：

```powershell
npm run dev:all
```

该命令会把 Worker 纳入同一个 `concurrently` 会话；在该会话按 `Ctrl+C` 会同时停止后端、前端和 Worker。原 `npm run dev` 仍保留为不启动 Worker 的页面/API 开发模式。

部署机上的 `start-grdp-ahks.bat` 会在 Worker 健康检查通过后继续检查 `/api/capabilities`，只有 PIPESIM Well、PIPESIM Network 和 ECLIPSE 100 均为 `AVAILABLE` 才会报告启动成功；`stop-grdp-ahks.bat` 在发现活动 Run 时拒绝停止，避免误杀计算。这样展示或交付前可以尽早发现 Toolkit、Python、许可证、`eclrun.exe` 或模型环境不完整的问题。

后端健康检查：<http://127.0.0.1:8080/actuator/health>

**真实计算：按需启动 Worker**

只有执行 PIPESIM 或 ECLIPSE 任务时才需要 Worker。推荐使用上面的 `npm run dev:all` 统一启动；也可以在 IDE 的任务配置中启动，或单独运行：

```powershell
dotnet run --project worker/Grdp.SoftwareIntegration.Worker.csproj
```

检查：<http://127.0.0.1:5150/api/health> 和 <http://127.0.0.1:5150/api/capabilities>

启动关系可以记成：

```text
Docker Desktop → MySQL/Redis → npm run dev:all（后端 + 前端 + Worker）→ 模拟器
```

### 6. 停止服务

停止 `npm run dev` 或 `npm run dev:all` 后，在项目根目录运行：

```powershell
docker compose --env-file backend/.env -f backend/compose.yml down
```

该命令不会删除 Docker 数据卷。除非明确要清空本机数据库，否则不要使用 `down -v`。

## 目录导航

| 目录 | 负责内容 |
| --- | --- |
| `vue/` | Vue 页面、路由、API 调用和结果可视化 |
| `backend/` | Spring Boot API、持久化、运行编排和结果查询 |
| `worker/` | .NET Worker、进程监督、模拟器调用和结果归一化 |
| `backend/compose.yml` | 本地 MySQL/Redis 基础设施 |

## 常见检查

```powershell
# 检查端口是否被监听
Get-NetTCPConnection -LocalPort 32000,6379,8080,5150,5173 -State Listen

# 查看数据库表
docker exec grdp-mysql mysql -uroot -pgrdp_root_password -D grdp_studio -e "SHOW TABLES;"
```

网页、后端和 Worker 启动成功，不代表模拟器一定可用。真实计算还必须满足 Toolkit、Python、许可证、`eclrun.exe` 和模型文件均可访问；能力接口会区分代码、模型、许可证和安装环境问题。

## 验证命令

```powershell
# 前端
cd vue
npm run build
npm run test:e2e
cd ..

# Worker 与后端
dotnet build worker/Grdp.SoftwareIntegration.Worker.csproj
dotnet test worker/tests/Worker/Grdp.SoftwareIntegration.Worker.Tests.csproj
mvn -f backend/pom.xml test
```

构建通过只代表代码可以构建，不等于 PIPESIM/ECLIPSE 真实计算已经完成。真实计算应额外记录模型、版本、Run ID、结果和失败原因。

## 常见问题

<details>
<summary>没有安装 PIPESIM 或 ECLIPSE，能启动项目吗？</summary>

可以。先运行页面模式，网页和后端可以启动；执行对应模型时，能力状态会提示缺少模拟器、Toolkit、许可证或模型文件。
</details>

<details>
<summary>为什么浏览器不能直接访问 Worker？</summary>

这是架构约束。浏览器只访问 Spring Boot，Spring Boot 负责鉴权、校验、持久化和任务编排，再由本机 Worker 访问模拟器，避免把本地路径和可执行命令暴露给浏览器。
</details>

<details>
<summary>为什么页面能打开，但登录失败？</summary>

登录依赖本机的认证服务 `127.0.0.1:9919` 和原平台 API `127.0.0.1:9920`。这两个服务不在本仓库中，必须由部署环境单独安装和启动；没有它们时只能验证不依赖登录的本地服务。
</details>

<details>
<summary>数据库在哪里？需要导入其他数据库备份吗？</summary>

本项目的集成数据库由 Docker MySQL 管理，默认数据卷为 `grdp_mysql_data`。真实业务数据库和备份不随仓库提供，除非部署方案明确要求，否则不要直接导入外部备份。
</details>
