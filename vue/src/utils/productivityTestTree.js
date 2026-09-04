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

const METHOD_LABELS = new Map([
  ['回压', 'back-pressure'],
  ['回压试井', 'back-pressure'],
  ['等时', 'isochronal'],
  ['等时试井', 'isochronal'],
  ['修正等时', 'modified-isochronal'],
  ['修正等时试井', 'modified-isochronal'],
  ['一点', 'one-point'],
  ['一点法', 'one-point']
])
const METHOD_BY_VALUE = new Map(PRODUCTIVITY_TEST_METHODS.map(item => [item.method, item]))
const METHOD_BY_GROUP_TYPE = new Map(PRODUCTIVITY_TEST_METHODS.map(item => [item.groupType, item]))
const METHOD_BY_RECORD_TYPE = new Map(PRODUCTIVITY_TEST_METHODS.map(item => [item.recordType, item]))

const nodeLabel = node => String(node?.label || node?.nodeTitle || node?.name || '').trim()
const methodFromLabel = node => METHOD_LABELS.get(nodeLabel(node)) || null
const methodFromNode = node => {
  const declared = String(node?.testMethod || node?.method || '').trim()
  if (METHOD_BY_VALUE.has(declared)) return declared
  return METHOD_BY_RECORD_TYPE.get(node?.type)?.method || methodFromLabel(node)
}
const hasRecordIdentity = node => [node?.testId, node?.resultId, node?.evaluationId]
  .some(value => value !== null && value !== undefined && value !== '')
const recordKey = node => [
  node?.id,
  node?.testId,
  node?.resultId,
  node?.evaluationId,
  nodeLabel(node)
].find(value => value !== null && value !== undefined && value !== '')

export const ensureProductivityTestMethodGroups = (testGroup, wellNode, wellName) => {
  const children = testGroup.children || []
  const originalGroupChildren = new Map(children
    .filter(node => PRODUCTIVITY_TEST_METHOD_NODE_TYPES.has(node?.type))
    .map(node => [node, [...(node.children || [])]]))
  const groups = Object.fromEntries(PRODUCTIVITY_TEST_METHODS.map(method => {
    const existing = children.find(node => node?.type === method.groupType)
    const group = existing || {
      id: `${wellNode.id}-productivity-test-${method.method}`,
      type: method.groupType,
      wellName,
      children: []
    }
    group.label = method.label
    group.wellName = wellName
    group.children = []
    return [method.method, group]
  }))
  const recordKeys = Object.fromEntries(PRODUCTIVITY_TEST_METHODS.map(method => [method.method, new Set()]))
  const unrelated = []

  const appendRecord = (method, node) => {
    const group = groups[method]
    if (!group) return
    const key = recordKey(node)
    if (key !== undefined && recordKeys[method].has(String(key))) return
    if (key !== undefined) recordKeys[method].add(String(key))
    group.children.push(node)
  }

  const collect = (node, fallbackMethod = null) => {
    if (!node) return
    const canonicalMethod = METHOD_BY_GROUP_TYPE.get(node.type)?.method
    if (canonicalMethod) {
      const groupChildren = originalGroupChildren.get(node) || []
      groupChildren.forEach(child => collect(child, canonicalMethod))
      return
    }

    const explicitMethod = methodFromNode(node)
    const method = explicitMethod || fallbackMethod
    const exactMethodLabel = methodFromLabel(node)
    const recordMethod = METHOD_BY_RECORD_TYPE.get(node.type)?.method

    // 旧版把“回压/一点”等方法本身也保存成目录节点。只吸收其真实子记录，
    // 不再把这些容器作为第二套目录展示；空容器直接丢弃。
    if (exactMethodLabel && !hasRecordIdentity(node)) {
      const nestedChildren = node.children || []
      nestedChildren.forEach(child => collect(child, exactMethodLabel))
      return
    }

    if (method && (recordMethod || hasRecordIdentity(node))) {
      appendRecord(method, node)
      return
    }
    unrelated.push(node)
  }

  children.forEach(node => collect(node))
  testGroup.children = [
    ...PRODUCTIVITY_TEST_METHODS.map(method => groups[method.method]),
    ...unrelated
  ]
  return groups
}
