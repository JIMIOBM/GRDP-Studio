import test from 'node:test'
import assert from 'node:assert/strict'

import {
  WORKSPACE_GAS_RESERVOIR_ID,
  WORKSPACE_PROJECT_ID,
  resolveWorkspaceContextId
} from '../src/constants/workspaceContext.js'

test('路由气藏编号优先于工作区默认值', () => {
  assert.equal(resolveWorkspaceContextId(null, '4', 1), 4)
})

test('嵌入组件参数优先于路由编号', () => {
  assert.equal(resolveWorkspaceContextId(7, '4', 1), 7)
})

test('非法编号会回退到工作区上下文', () => {
  assert.equal(resolveWorkspaceContextId('invalid', 0, WORKSPACE_PROJECT_ID), 6)
  assert.equal(resolveWorkspaceContextId(-1, '4.5', WORKSPACE_GAS_RESERVOIR_ID), 4)
})

test('所有候选值都无效时返回空值', () => {
  assert.equal(resolveWorkspaceContextId(undefined, '', 0), null)
})
