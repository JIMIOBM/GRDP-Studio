// 当前解析融合工作台固定使用的项目与气藏。
// IPR 与单井产能共享同一棵目录树，必须使用完全相同的上下文，
// 否则路由切换时后加载页面会用另一气藏的结果覆盖共享目录。
export const WORKSPACE_PROJECT_ID = 6
export const WORKSPACE_GAS_RESERVOIR_ID = 4
