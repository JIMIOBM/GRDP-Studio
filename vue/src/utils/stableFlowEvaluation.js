import { NODETYPE } from '@/constants/nodeType'
import { nodeApi, productivityEvaluationApi } from '@/api/docker'

/**
 * 旧平台稳定流计算结果适配层。
 *
 * 旧平台的 calc 接口只触发计算，完整结果需要再通过“节点树 -> 结果节点ID -> 结果明细”
 * 两步读取。本文件供理论稳定流和动态稳定流共同使用，避免两套页面对旧平台结果
 * 做出不同解释。
 */

const unwrap = response => response?.data?.data ?? response?.data ?? response ?? {}
const childrenOf = node => [node?.children, node?.subNodes, node?.nodes, node?.analysisNodes]
  .flatMap(value => Array.isArray(value) ? value : value ? [value] : [])
const labelOf = node => String(
  node?.wellName ?? node?.nodeTitle ?? node?.name ?? node?.title ?? node?.label ?? ''
).trim()

const collectNodes = root => {
  const nodes = []
  const walk = value => {
    if (!value || typeof value !== 'object') return
    if (Array.isArray(value)) return value.forEach(walk)
    nodes.push(value)
    childrenOf(value).forEach(walk)
  }
  walk(root)
  return nodes
}

const findFlowEquationResultNode = (payload, wellName, preferredWellType = '') => {
  const allNodes = collectNodes(payload?.node ?? payload)
  const wellNode = allNodes.find(node =>
    Number(node?.nodeType ?? node?.type) === NODETYPE.NodeType_Well &&
    labelOf(node) === String(wellName)
  ) || allNodes.find(node => labelOf(node) === String(wellName))
  if (!wellNode) return null

  const wellNodes = collectNodes(wellNode)
  const branches = wellNodes.filter(node =>
    Number(node?.nodeType ?? node?.type) === NODETYPE.NodeType_ProductivityEvaluationByFlowEquation ||
    labelOf(node).includes('渗流方程')
  )
  // 同一口井可能同时存在水平井和直/斜井分支，优先匹配本次计算使用的井型。
  const preferredBranch = branches.find(node => {
    const title = labelOf(node)
    return preferredWellType === 'horizontal'
      ? title.includes('水平')
      : preferredWellType === 'vertical'
        ? /斜直|直井|斜井/.test(title)
        : false
  })
  const branch = preferredBranch || branches[0]
  if (!branch) return null

  // 旧平台可能保留多个结果节点，nodeId 最大的节点视为最新一次计算结果。
  const resultNode = collectNodes(branch)
    .filter(node =>
      Number(node?.nodeType ?? node?.type) ===
        NODETYPE.NodeType_ProductivityEvaluationFlowEquationInterpretationResult &&
      Number(node?.nodeId ?? node?.id) > 0
    )
    .sort((left, right) => Number(right?.nodeId ?? right?.id) - Number(left?.nodeId ?? left?.id))[0]
  if (!resultNode) return null

  const branchTitle = labelOf(branch)
  return {
    nodeId: Number(resultNode.nodeId ?? resultNode.id),
    wellType: branchTitle.includes('水平') ? 'horizontal' : 'vertical',
    branchTitle
  }
}

const wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

const evaluationMethods = [
  {
    key: 'pressure',
    label: '压力法',
    nodeType: NODETYPE.NodeType_ProductivityEvaluationByPressure
  },
  {
    key: 'pressure-squared',
    label: '压力平方方法',
    nodeType: NODETYPE.NodeType_ProductivityEvaluationByPressureSquared
  },
  {
    key: 'pseudo-pressure',
    label: '拟压力',
    nodeType: NODETYPE.NodeType_ProductivityEvaluationByPseudoPressure
  }
]

const loadMethodResult = async ({
  projectId,
  gasReservoirId,
  wellName,
  preferredWellType,
  method
}) => {
  let resultNode = null
  // calc 返回后节点落库存在短暂延迟，因此最多轮询8次，而不是立即判定计算失败。
  for (let attempt = 0; attempt < 8 && !resultNode; attempt += 1) {
    if (attempt) await wait(400)
    const nodeResponse = await nodeApi.getNode(
      projectId,
      gasReservoirId,
      method.nodeType,
      { silentError: true }
    )
    resultNode = findFlowEquationResultNode(unwrap(nodeResponse), wellName, preferredWellType)
  }

  if (!resultNode) {
    throw new Error(`没有找到井 ${wellName} 的${method.label}渗流方程结果节点`)
  }

  const resultResponse = await productivityEvaluationApi.getResult(
    projectId,
    gasReservoirId,
    resultNode.nodeId,
    {
      silentError: true,
      headers: {
        'Cache-Control': 'no-cache',
        Pragma: 'no-cache'
      }
    }
  )
  return {
    ...resultNode,
    method: method.key,
    methodLabel: method.label,
    detail: unwrap(resultResponse)
  }
}

export const loadStableFlowMethodResult = async ({
  projectId,
  gasReservoirId,
  wellName,
  preferredWellType = '',
  methodKey
}) => {
  const method = evaluationMethods.find(item => item.key === methodKey)
  if (!method) throw new Error(`不支持的稳定流计算形式：${methodKey}`)
  return loadMethodResult({
    projectId,
    gasReservoirId,
    wellName,
    preferredWellType,
    method
  })
}

export const loadStableFlowEvaluationResults = async ({
  projectId,
  gasReservoirId,
  wellName,
  preferredWellType = '',
  calculationResults = []
}) => {
  // 三种结果互不阻塞：其中一种读取失败时，另外两种仍可展示并报告局部错误。
  const settledResults = await Promise.allSettled(evaluationMethods.map(method =>
    loadMethodResult({ projectId, gasReservoirId, wellName, preferredWellType, method })
  ))
  const resultsByMethod = {}
  settledResults.forEach((result, index) => {
    if (result.status === 'fulfilled') resultsByMethod[evaluationMethods[index].key] = result.value
  })
  const pseudoPressureResult = resultsByMethod['pseudo-pressure']
  if (!pseudoPressureResult) {
    throw settledResults[2]?.reason || new Error(`没有找到井 ${wellName} 的拟压力渗流方程结果节点`)
  }

  return {
    ...pseudoPressureResult,
    wellName,
    resultsByMethod,
    resultErrors: settledResults.reduce((errors, result, index) => {
      if (result.status === 'rejected') errors[evaluationMethods[index].key] = result.reason?.message || String(result.reason)
      return errors
    }, {}),
    calculationResults
  }
}

export const runStableFlowEvaluation = async ({
  projectId,
  gasReservoirId,
  wellName,
  preferredWellType = ''
}) => {
  // 首次计算同时触发 evaluationForm=1、2、3，随后统一读取三种最新结果。
  const calculationResults = await productivityEvaluationApi.calculateStableFlowEquations({
    projectId,
    gasReservoirId,
    wellNames: [wellName]
  }, { silentError: true })
  return loadStableFlowEvaluationResults({
    projectId,
    gasReservoirId,
    wellName,
    preferredWellType,
    calculationResults
  })
}
