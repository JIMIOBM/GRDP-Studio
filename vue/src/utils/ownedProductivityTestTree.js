import { productivityTestsApi } from '@/api/productivityTests'
import {
  ensureProductivityTestMethodGroups,
  PRODUCTIVITY_TEST_METHODS
} from '@/utils/productivityTestTree'

const OWNED_METHODS = [
  { value: 'back-pressure', label: '回压试井', pageMethod: '回压试井' },
  { value: 'one-point', label: '一点法', pageMethod: '一点法' }
]
const OWNED_METHOD_VALUES = new Set(OWNED_METHODS.map(method => method.value))

const METHOD_NODE_TYPE = 'owned-productivity-test-method'
const RECORD_NODE_TYPE = 'owned-productivity-test-record'

export const OWNED_PRODUCTIVITY_METHOD_NODE_TYPES = new Set(
  PRODUCTIVITY_TEST_METHODS
    .filter(method => OWNED_METHOD_VALUES.has(method.method))
    .map(method => method.groupType)
)

export const loadOwnedProductivityTestTreeNodes = async ({
  treeData,
  projectId,
  gasReservoirId,
  wellName,
  expand = false
}) => {
  const wellNode = treeData.find(node => node.id === 'g-well')?.children?.find(node =>
    (node.wellName || node.label) === wellName
  )
  const productivityGroup = wellNode?.children?.find(node =>
    node.type === 'single-well-productivity' || node.label === '单井产能'
  )
  if (!wellNode || !productivityGroup) return []

  let testGroup = productivityGroup.children?.find(node =>
    node.type === 'productivity-test' || node.label === '产能试井'
  )
  if (!testGroup) {
    testGroup = {
      id: `${wellNode.id}-single-well-productivity-productivity-test`,
      label: '产能试井',
      type: 'productivity-test',
      wellName,
      children: []
    }
    productivityGroup.children = [...(productivityGroup.children || []), testGroup]
  }

  const responses = await Promise.all(OWNED_METHODS.map(async method => {
    const response = await productivityTestsApi.list(
      projectId, gasReservoirId, wellName, method.value
    )
    return { method, records: response?.data ?? response ?? [] }
  }))
  // 合并旧版额外生成的“回压/一点”目录，直接复用标准的
  // “回压试井/一点法”方法节点，避免同一 ID 在树中出现两次。
  testGroup.children = (testGroup.children || []).filter(node => node.type !== METHOD_NODE_TYPE)
  const methodGroups = ensureProductivityTestMethodGroups(testGroup, wellNode, wellName)
  const methodNodes = responses.map(({ method, records }) => {
    const group = methodGroups[method.value]
    group.label = method.label
    group.testMethod = method.value
    group.pageMethod = method.pageMethod
    group.wellName = wellName
    group.children = records.map(record => ({
      id: `${wellNode.id}-${method.value}-${record.id}`,
      label: `${method.label}${record.testNo}`,
      type: RECORD_NODE_TYPE,
      wellName,
      testMethod: method.value,
      pageMethod: method.pageMethod,
      testId: record.id,
      resultId: record.id,
      projectId,
      gasReservoirId,
      pressureMethods: record.pressureMethods || [],
      children: []
    }))
    return group
  })

  if (expand) {
    wellNode.expanded = true
    productivityGroup.expanded = true
    testGroup.expanded = true
  }
  return methodNodes.flatMap(node => node.children)
}

export const loadAllOwnedProductivityTestTreeNodes = async options => {
  const wells = options.treeData.find(node => node.id === 'g-well')?.children || []
  const results = await Promise.allSettled(wells.map(well =>
    loadOwnedProductivityTestTreeNodes({
      ...options,
      wellName: well.wellName || well.label
    })
  ))
  const failures = results.filter(result => result.status === 'rejected')
  if (failures.length) {
    console.warn(`有 ${failures.length} 口井的回压/一点法记录加载失败`, failures)
  }
}

export const OWNED_PRODUCTIVITY_METHOD_NODE_TYPE = METHOD_NODE_TYPE
export const OWNED_PRODUCTIVITY_RECORD_NODE_TYPE = RECORD_NODE_TYPE
