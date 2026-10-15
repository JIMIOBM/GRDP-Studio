import { NODETYPE } from '../constants/nodeType.js'
import { getRelativePermeabilityRecords } from './relativePermeabilityRecords.js'

// 独立的单井产能路由也要有完整的公共井目录。只补缺失节点，不替换已有记录和展开状态。
const DATA_NODES = [
  ['well-data-wellhead', '井头数据', 'wellhead'],
  ['well-data-deviation', '井斜数据', 'deviation'],
  ['well-data-logging', '测井数据', 'logging'],
  ['well-data-deliverability', '产能测试', 'deliverability'],
  ['well-data-wellcompletion', '完井数据', 'wellcompletion'],
  ['well-data-otherdata', '其他数据', 'otherdata'],
  ['well-data-productiondata', '注采数据', 'productiondata'],
  ['well-data-static-pressure', '静压数据', 'staticPressure'],
  ['well-data-pvt-group', 'PVT性质', null],
  ['well-data-relative-permeability-group', '相渗数据', null]
]
const GROUPS = [
  ['data-management', '数据管理'], ['well-control-inventory', '井控库存'],
  ['single-well-productivity', '单井产能'], ['wellbore-capacity', '井筒能力'],
  ['pipeline-capacity', '管束能力'], ['production-allocation', '配产配注']
]

const findWell = (tree, wellName) => tree.find(node => node.id === 'g-well')?.children?.find(
  node => String(node.wellName || node.label) === String(wellName)
)
const findGroup = (well, type) => well?.children?.find(node => node.type === type)
const createNode = (well, type, label, extra = {}) => ({
  id: `${well.id}-${type}`, type, label, wellName: well.wellName || well.label,
  defaultExpanded: false, children: [], ...extra
})
const ensureChild = (parent, well, type, label, extra = {}) => {
  parent.children ||= []
  let node = parent.children.find(child => child.type === type)
  if (!node) {
    node = createNode(well, type, label, extra)
    parent.children.push(node)
  }
  return node
}

export const ensureWorkspaceWellNavigation = (tree, projectId, gasReservoirId) => {
  const wells = tree.find(node => node.id === 'g-well')?.children || []
  for (const well of wells) {
    const wellName = String(well.wellName || well.label || '')
    if (!wellName) continue
    well.wellName = wellName
    if (well.type === 'single-well') well.type = NODETYPE.NodeType_Well
    well.children ||= []
    for (const [type, label] of GROUPS) ensureChild(well, well, type, label)
    const data = findGroup(well, 'data-management')
    const inventory = findGroup(well, 'well-control-inventory')
    inventory.lazy = true

    for (const [type, label, dataType] of DATA_NODES) {
      const node = ensureChild(data, well, type, label, {
        ...(dataType ? { dataType } : {}),
        ...(type === 'well-data-pvt-group' ? { lazy: true } : {})
      })
      if (type === 'well-data-pvt-group') node.lazy = true
      if (type === 'well-data-static-pressure') {
        for (const [childType, childLabel, childDataType] of [
          ['well-data-calculated-static-pressure', '计算静压', 'calculatedStaticPressure'],
          ['well-data-measured-static-pressure', '实测静压', 'measuredStaticPressure']
        ]) ensureChild(node, well, childType, childLabel, { dataType: childDataType })
      }
      if (type === 'well-data-relative-permeability-group' && !node.children.length) {
        node.children = getRelativePermeabilityRecords(projectId, gasReservoirId, wellName).map(record => ({
          id: `${well.id}-well-data-relative-permeability-${record.index}`,
          type: 'well-data-relative-permeability', label: `相渗数据${record.index}`,
          wellName, relativePermeabilityIndex: record.index, children: []
        }))
      }
    }
  }
}

export const applyWorkspacePvtRecords = (tree, scope, records) => {
  const well = findWell(tree, scope.wellName)
  const group = findGroup(well, 'data-management')?.children?.find(node => node.type === 'well-data-pvt-group')
  if (!group) return
  group.children = records.map(record => ({
    id: `${well.id}-well-data-pvt-${record.pvtId || record.pvtNo}`,
    type: 'well-data-pvt', label: record.pvtName || `PVT性质${record.pvtNo}`,
    wellName: scope.wellName, projectId: scope.projectId, gasReservoirId: scope.gasReservoirId,
    pvtId: record.pvtId, pvtIndex: record.pvtNo, status: record.status,
    sourceType: record.sourceType, lastCalculatedKind: record.lastCalculatedKind, children: []
  }))
  group.loaded = true
}

const rows = value => {
  const data = value?.data?.data ?? value?.data ?? value?.items ?? value?.rows ?? value
  return Array.isArray(data) ? data : data ? [data] : []
}
const nodeName = node => node?.wellName || node?.nodeTitle || node?.label || ''
const children = node => node?.subNodes ?? node?.children ?? []
const findWellNode = (root, wellName) => children(root).find(node => nodeName(node) === wellName)
const resultId = row => row?.DynamicOriginalGasInPlaceId ?? row?.dynamicOriginalGasInPlaceId ??
  row?.dynamicOriginalGasInplaceId ?? row?.dynamicOriginalGasInplaceID ?? row?.id
const isMaterial = row => {
  const type = Number(row?.dynamicOriginalGasInplaceType ?? row?.dynamicOriginalGasInPlaceType)
  const description = String(row?.dynamicOriginalGasInplaceMethodDescription || row?.dynamicOriginalGasInPlaceMethodDescription || '')
  return [1, 2, 3, 4].includes(type) || /(?:定容|封闭)气藏物质平衡.*(?:实测静压|计算静压)/.test(description)
}
const isFlow = row => {
  const type = Number(row?.dynamicOriginalGasInplaceType ?? row?.dynamicOriginalGasInPlaceType)
  const description = String(row?.dynamicOriginalGasInplaceMethodDescription || row?.dynamicOriginalGasInPlaceMethodDescription || '')
  return [5, 6].includes(type) || /流动(?:物质)?平衡.*(?:井口流压|井底流压)/.test(description)
}
const TYPICAL_TYPES = new Map(Object.entries(NODETYPE)
  .filter(([name]) => /TypicalCurve(?:Blasingame|AG|NPI|Transient|Wattenbarger)$/.test(name))
  .map(([name, type]) => [type, name.endsWith('Blasingame') ? 'Blasingame'
    : name.endsWith('AG') ? 'Agarwal-Gardner'
      : name.endsWith('NPI') ? 'NPI'
        : name.endsWith('Transient') ? 'Transient' : 'Wattenbarger']))
const inventoryOrder = new Map([
  [NODETYPE.NodeType_WaterInvasionAnalysis, 10], [NODETYPE.NodeType_DynamicOriginalGasInplace, 20],
  [NODETYPE.NodeType_FlowingBalanceMethodBasedOnBottomPressure, 30],
  [NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame, 40],
  [NODETYPE.NodeType_AnalysisMethods, 50], ['well-data-diagnostic-curve-group', 55],
  [NODETYPE.NodeType_TypicalCurve, 60]
])
const replaceType = (group, type, nodes) => {
  const previous = group.children?.find(node => node.type === type)
  if (previous && nodes.length && typeof previous.expanded === 'boolean') {
    nodes[0].expanded = previous.expanded
  }
  group.children = [...(group.children || []).filter(node => node.type !== type), ...nodes]
}

// 与 /ipr 的井控库存读取同一组接口。每项独立成功后才更新对应节点；失败不清空已有结果。
export const loadWorkspaceWellInventory = async (tree, scope, apis) => {
  const well = findWell(tree, scope.wellName)
  const inventory = findGroup(well, 'well-control-inventory')
  if (!inventory) return
  const { projectId, gasReservoirId, wellName } = scope
  const tasks = await Promise.allSettled([
    apis.waterInvasionApi.records(scope),
    apis.diagnosticCurveApi.listRecords(projectId, gasReservoirId, wellName),
    apis.nodeApi.getNode(projectId, gasReservoirId, NODETYPE.NodeType_DynamicOriginalGasInplace, { silentError: true }),
    apis.nodeApi.getNode(projectId, gasReservoirId, NODETYPE.NodeType_ProductivityInstabilityAnalysis, { silentError: true }),
    apis.materialBalanceApi.getAverageFormationPressure(projectId, gasReservoirId, wellName, { silentError: true })
  ])
  const [water, diagnostic, material, analysis, pressure] = tasks
  if (water.status === 'fulfilled') {
    const records = rows(water.value)
    replaceType(inventory, NODETYPE.NodeType_WaterInvasionAnalysis, records.length ? [{
      id: `water-invasion-${wellName}`, type: NODETYPE.NodeType_WaterInvasionAnalysis,
      label: '水侵分析', wellName,
      waterInvasionRecordId: records.find(item => item.taskStatus === 'COMPLETED')?.id,
      children: []
    }] : [])
  }
  if (diagnostic.status === 'fulfilled') {
    const records = rows(diagnostic.value)
    replaceType(inventory, 'well-data-diagnostic-curve-group', [{
      id: `${well.id}-diagnostic-curve-group`, type: 'well-data-diagnostic-curve-group',
      label: '诊断曲线', wellName, defaultExpanded: false,
      children: records.map(record => ({
        id: `${well.id}-diagnostic-curve-${record.diagnosticId}`,
        type: 'well-data-diagnostic-curve', label: record.diagnosticName || `诊断曲线${record.diagnosticNo}`,
        wellName, diagnosticId: record.diagnosticId, diagnosticNo: record.diagnosticNo,
        projectId, gasReservoirId, status: record.status, children: []
      }))
    }])
  }
  const materialWell = material.status === 'fulfilled' ? findWellNode(material.value?.data?.node, wellName) : null
  if (material.status === 'fulfilled' && pressure.status === 'fulfilled') {
    const materialRows = rows(pressure.value?.data).filter(isMaterial)
    replaceType(inventory, NODETYPE.NodeType_DynamicOriginalGasInplace, materialWell && materialRows.length ? [{
      id: materialWell.nodeId || `material-balance-${wellName}`,
      type: NODETYPE.NodeType_DynamicOriginalGasInplace, label: '物质平衡',
      wellName, raw: { ...materialWell, materialBalanceRows: materialRows }, children: []
    }] : [])
  }
  if (material.status === 'fulfilled') {
    const dynamicNode = children(materialWell).find(node =>
      (node.nodeType ?? node.type) === NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame)
    replaceType(inventory, NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame, dynamicNode?.nodeId ? [{
      id: dynamicNode.nodeId, type: NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame,
      label: '动态平衡', wellName, raw: dynamicNode, children: []
    }] : [])
  }
  if (pressure.status === 'fulfilled') {
    const flowRows = rows(pressure.value?.data).filter(isFlow)
    replaceType(inventory, NODETYPE.NodeType_FlowingBalanceMethodBasedOnBottomPressure, flowRows.length ? [{
      id: resultId(flowRows[0]) || `flow-balance-${wellName}`,
      type: NODETYPE.NodeType_FlowingBalanceMethodBasedOnBottomPressure,
      label: '流动平衡', wellName, raw: { ...flowRows[0], flowBalanceRows: flowRows }, children: []
    }] : [])
  }
  if (analysis.status === 'fulfilled') {
    const root = analysis.value?.data?.node
    const analyticRoot = children(root).find(node => node.nodeType === NODETYPE.NodeType_AnalysisMethods)
    const analyticWell = findWellNode(analyticRoot, wellName)
    replaceType(inventory, NODETYPE.NodeType_AnalysisMethods, analyticWell?.nodeId ? [{
      id: analyticWell.nodeId, type: NODETYPE.NodeType_AnalysisMethods,
      label: '解析法', wellName, resultId: analyticWell.nodeId,
      analysisId: analyticWell.nodeId, raw: analyticWell, children: []
    }] : [])
    const typical = []
    const visit = (node, currentWell = '', inTypical = false) => {
      if (!node) return
      const name = nodeName(node)
      const nextWell = name === wellName ? name : currentWell
      const type = node.nodeType ?? node.type
      const nextTypical = inTypical || type === NODETYPE.NodeType_TypicalCurve ||
        ['图版法', '典型曲线', '诊断曲线'].includes(name)
      if (nextWell === wellName && nextTypical && TYPICAL_TYPES.has(type)) {
        typical.push({ id: node.nodeId || `${well.id}-typical-${type}`, type,
          label: TYPICAL_TYPES.get(type), wellName, raw: node, children: [] })
      }
      for (const child of children(node)) visit(child, nextWell, nextTypical)
    }
    visit(root)
    replaceType(inventory, NODETYPE.NodeType_TypicalCurve, typical.length ? [{
      id: `${well.id}-diagnostic-curve`, type: NODETYPE.NodeType_TypicalCurve,
      label: '图版法', wellName, defaultExpanded: false, children: typical
    }] : [])
  }
  inventory.children.sort((left, right) => (inventoryOrder.get(left.type) ?? 999) - (inventoryOrder.get(right.type) ?? 999))
  // IPR 的 childrenLoaded 还涵盖 PVT 等节点，不能用这里只加载库存的结果冒充整井已加载。
  inventory.loaded = tasks.every(task => task.status === 'fulfilled')
}
