import { productivityTestsApi } from '@/api/productivityTests'
import {
  ensureProductivityTestTreeNodes,
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
  expand = false,
  testMethod = null
}) => {
  const branch = ensureProductivityTestTreeNodes(treeData, wellName)
  if (!branch) return []
  const { wellNode, productivityGroup, testGroup } = branch

  const requestedMethods = testMethod
    ? OWNED_METHODS.filter(method => method.value === testMethod)
    : OWNED_METHODS
  const responses = await Promise.all(requestedMethods.map(async method => {
    const response = await productivityTestsApi.list(
      projectId, gasReservoirId, wellName, method.value
    )
    return { method, records: response?.data ?? response ?? [] }
  }))
  // 统一归并旧版“回压/一点”目录和标准方法目录，避免重复或挂错层级。
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
    group.loaded = true
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
  wells.forEach(well => ensureProductivityTestTreeNodes(options.treeData, well.wellName || well.label))
}

export const OWNED_PRODUCTIVITY_METHOD_NODE_TYPE = METHOD_NODE_TYPE
export const OWNED_PRODUCTIVITY_RECORD_NODE_TYPE = RECORD_NODE_TYPE
