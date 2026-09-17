# 融合管理平台 · 前端（Vue 3）

解析融合一体化油藏工作平台前端，基于 Vue 3 + Vite + Element Plus。
顶部功能区（Ribbon）界面按设计稿 1:1 还原，目录结构按既定规范组织。

## 运行

```bash
npm install        # 安装依赖
npm run dev        # 本地开发，默认 http://localhost:5173
npm run build      # 生产打包
```

后端默认通过 `vite.config.js` 中的代理 `/api -> http://localhost:8080`，按需修改。

## 登录说明

开发环境直接在新平台 `/login` 输入旧平台账号密码即可，不需要先打开旧平台登录。
新平台通过 `/docker-auth/login` 调用旧平台认证服务并校验会话，成功后才进入工作台。
浏览器使用 HttpOnly 会话 Cookie 访问旧接口和计算通知；密码不会写入本地存储，代理不共享用户会话。

- 旧平台认证服务仍需运行，默认地址为 `http://127.0.0.1:9919`。
- 旧平台业务接口/通知服务默认地址为 `http://127.0.0.1:9920`。
- 可在 `vue/.env.local` 中配置 `DOCKER_AUTH_BASE_URL`，修改后重启开发服务器。
- 兼容旧平台不同版本的 `ahksoil_identity_session` 和 `grdp_identity_session`，
  以认证服务实际返回并校验通过的会话为准，不依赖旧页面脚本中的名称。
  自定义部署可通过 `DOCKER_SESSION_COOKIE_NAME` 指定名称；登录与 HTTP/WebSocket 代理使用相同规则。
- 认证失败时停留在登录页，不生成本地登录标记。
- 此登录桥接当前由 Vite 开发服务器提供；`npm run build` 不会将该服务打包进静态页面。
  正式部署须在服务端提供同名登录接口及 HTTP/WebSocket 代理，启用 HTTPS、安全会话和后端权限校验，不能只部署 `dist`。

## 主要路由

| 路径            | 页面                         |
| --------------- | ---------------------------- |
| `/login`        | 登录                         |
| `/register`     | 注册                         |
| `/ipr`          | 解析融合工作台（功能区界面） |
| `/front/*`      | 前台布局及子页面             |
| `/back/*`       | 后台管理布局及子页面         |

## 目录结构

```
vue/
├── config/                 # 应用级配置 + logo
├── src/
│   ├── api/                # 接口封装
│   ├── assets/             # 静态资源
│   ├── components/
│   │   └── RibbonMenu.vue  # 顶部功能区菜单（单独抽出的组件）
│   ├── router/             # 路由 + 登录守卫
│   ├── style/              # 全局样式
│   ├── utils/              # axios 实例
│   ├── views/
│   │   ├── back/           # 后台子页面
│   │   ├── front/          # 前台子页面
│   │   ├── IprInterface.vue# 功能区工作台（设计稿 1）
│   │   ├── TreeNode.vue    # 递归树节点（井/库/库群）
│   │   ├── Login.vue / Register.vue / Front.vue / Back.vue / 404.vue
│   ├── App.vue
│   └── main.js
├── index.html
├── jsconfig.json
├── package.json
└── vite.config.js
```

## 功能区（RibbonMenu）说明

`RibbonMenu.vue` 完全数据驱动：`tabs → groups → columns → items`。
默认内置“解析融合 / 软件集成 / 多周期优化 / 多目标决策 / 可视化”五个页签，
其中“解析融合”按设计稿还原了数据管理、井控库存、单井产能、井筒能力、管束能力、配产配注六个分组。
点击任意功能项会向父组件 `emit('command', { group, name })`，在 `IprInterface.vue` 中接收处理。
如需调整内容，可直接改组件内 `defaultTabs`，或通过 `:tabs` 传入自定义配置。
