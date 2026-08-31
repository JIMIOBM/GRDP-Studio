import { productivityTestsApi } from '@/api/productivityTests'

const OWNED_METHODS = [
  { value: 'back-pressure', label: '回压', pageMethod: '回压试井' },
  { value: 'one-point', label: '一点', pageMethod: '一点法' }
]

const METHOD_NODE_TYPE = 'owned-productivity-test-method'
const RECORD_NODE_TYPE = 'owned-productivity-test-record'

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
  const preserved = (testGroup.children || []).filter(node => node.type !== METHOD_NODE_TYPE)
  const methodNodes = responses.map(({ method, records }) => ({
    id: `${wellNode.id}-productivity-test-${method.value}`,
    label: method.label,
    type: METHOD_NODE_TYPE,
    testMethod: method.value,
    pageMethod: method.pageMethod,
    wellName,
    children: records.map(record => ({
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
  }))
  testGroup.children = [...preserved, ...methodNodes]

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
