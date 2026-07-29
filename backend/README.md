# GRDP Studio Backend

GRDP Studio 的 Spring Boot 业务后端。当前版本不接管登录，现有 Vue/Vite
登录流程仍然调用原平台认证服务。

## 技术栈

- JDK 21 LTS
- Spring Boot 4.1.0
- Apache Maven 3.9.16
- MyBatis-Plus 3.5.17
- MySQL 8.4
- Redis 7.4

MyBatis-Plus 使用 Spring Boot 4 专用依赖：

```xml
<artifactId>mybatis-plus-spring-boot4-starter</artifactId>
```

## 目录

```text
backend/
├─ compose.yml                         MySQL、Redis 开发环境
├─ deploy/mysql/init/001_schema.sql   MySQL 初始化脚本
├─ pom.xml
└─ src/
   ├─ main/
   │  ├─ java/com/grdp/studio/
   │  │  ├─ cache/                    Redis 封装
   │  │  ├─ common/                   统一返回和异常处理
   │  │  ├─ config/                   Web、MyBatis-Plus、HTTP 配置
   │  │  ├─ integration/              Go 原平台接口客户端
   │  │  ├─ project/                  项目 CRUD 示例模块
   │  │  └─ system/                   系统接口
   │  └─ resources/application.yml
   └─ test/                            H2 隔离测试
```

## 启动开发环境

在项目根目录执行：

```powershell
Copy-Item backend/.env.example backend/.env
npm run infra:up
```

等待 MySQL 和 Redis 健康后启动后端：

```powershell
npm run backend
```

也可以直接使用 Maven：

```powershell
mvn -f backend/pom.xml spring-boot:run
```

前端的 Vite 代理已经将 `/api` 转发到 `http://localhost:8080`。

## 验证

```powershell
mvn -f backend/pom.xml test
```

健康检查：

```text
GET http://127.0.0.1:8080/system/health
GET http://127.0.0.1:8080/actuator/health
```

## 示例业务接口

```text
GET    /project/page?page=1&size=20&keyword=
GET    /project/tree
GET    /project/{id}
POST   /project
PUT    /project/{id}
DELETE /project/{id}
```

创建或更新请求：

```json
{
  "name": "项目名称",
  "description": "项目说明"
}
```

接口统一返回：

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

## 调用 Go 原平台

业务服务通过 `OriginalPlatformClient` 调用 Go 接口，不要在控制器中直接拼接
第三方 URL。默认地址为：

```text
http://127.0.0.1:9920
```

可以通过环境变量修改：

```powershell
$env:ORIGINAL_PLATFORM_BASE_URL = 'http://127.0.0.1:9920'
```

当前没有为该客户端暴露公共代理接口，也没有修改原平台登录流程。
