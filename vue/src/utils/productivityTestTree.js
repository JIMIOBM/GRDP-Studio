import { NODETYPE } from '@/constants/nodeType'

export const PRODUCTIVITY_TEST_METHODS = [
  {
    method: 'back-pressure',
    label: '回压试井',
    groupType: 'productivity-test-back-pressure-method',
    recordType: NODETYPE.NodeType_ProductivityEvaluationBackPressureWellTest
  },
  {
    method: 'isochronal',
    label: '等时试井',
    groupType: 'productivity-test-isochronal-method',
    recordType: NODETYPE.NodeType_ProductivityEvaluationIsochronalWellTest
  },
  {
    method: 'modified-isochronal',
    label: '修正等时',
    groupType: 'productivity-test-modified-isochronal-method',
    recordType: NODETYPE.NodeType_ProductivityEvaluationModifiedIsochronalWellTest
  },
  {
    method: 'one-point',
    label: '一点法',
    groupType: 'productivity-test-one-point-method',
    recordType: NODETYPE.NodeType_ProductivityEvaluationOnePointWellTest
  }
]

export const PRODUCTIVITY_TEST_METHOD_NODE_TYPES = new Set(
  PRODUCTIVITY_TEST_METHODS.map(item => item.groupType)
)

export const ensureProductivityTestMethodGroups = (testGroup, wellNode, wellName) => {
  const children = testGroup.children || []
  const recordTypes = new Set(PRODUCTIVITY_TEST_METHODS.map(item => item.recordType))
  const groups = PRODUCTIVITY_TEST_METHODS.map(method => {
    const existing = children.find(node => node.type === method.groupType)
    const group = existing || {
      id: `${wellNode.id}-productivity-test-${method.method}`,
      type: method.groupType,
      wellName,
      children: []
    }
    group.label = method.label
    group.wellName = wellName
    group.children = group.children || []

    // 兼容合并前直接挂在“产能试井”下的试井记录。
    const legacyRecords = children.filter(node => node.type === method.recordType)
    legacyRecords.forEach(record => {
      if (!group.children.some(node => node.id === record.id)) group.children.push(record)
    })
    return group
  })
  const unrelated = children.filter(node =>
    !PRODUCTIVITY_TEST_METHOD_NODE_TYPES.has(node.type) && !recordTypes.has(node.type)
  )
  testGroup.children = [...groups, ...unrelated]
  return Object.fromEntries(PRODUCTIVITY_TEST_METHODS.map((method, index) => [method.method, groups[index]]))
}
