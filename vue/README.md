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
