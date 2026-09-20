// 当前解析融合工作台固定使用的项目与气藏。
// IPR 与单井产能共享同一棵目录树，必须使用完全相同的上下文，
// 否则路由切换时后加载页面会用另一气藏的结果覆盖共享目录。
export const resolveWorkspaceContextId = (...candidates) => {
  for (const candidate of candidates) {
    if (candidate === null || candidate === undefined || candidate === '') continue
    const value = Number(candidate)
    if (Number.isSafeInteger(value) && value > 0) return value
  }
  return null
}

// 本地数据库范围通过未入库的 .env.development.local 配置，不改共享默认编号。
export const WORKSPACE_PROJECT_ID = resolveWorkspaceContextId(import.meta.env?.VITE_WORKSPACE_PROJECT_ID, 6)
export const WORKSPACE_GAS_RESERVOIR_ID = resolveWorkspaceContextId(import.meta.env?.VITE_WORKSPACE_GAS_RESERVOIR_ID, 4)
