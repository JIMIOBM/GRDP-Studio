/**
 * wellGroupPrediction.js（网络层）
 * 库级「协同预测-一体化耦合优化」调用的原平台「动态预测-井组动态预测」接口。
 * 请求组装与响应解析等纯函数见 @/utils/wellGroupPrediction，此处只保留需登录态的接口调用，
 * 均经 /docker-api 代理到原平台 9920（见 docker.js）。
 *
 *  - 默认名称：GET  /projectanalysis/dynamicprediction//{projectId}/{gasReservoirId}/2/defaultname
 *  - 创建任务：POST /projectanalysis/dynamicprediction
 *      { gasReservoirId, projectId, wellNames, dynamicPredictionType: 2,
 *        dynamicPredictionName, productivityEvaluationForm }
 *  - 任务详情：GET  /projectanalysis/dynamicprediction/{projectId}/{gasReservoirId}/{dynamicPredictionId}
 *  - 启动预测：POST /projectanalysis/dynamicprediction/Calc
 *  - 绘图：GET /projectanalysis/dynamicprediction/result/wells/chart/{projectId}/{gasReservoirId}/{dynamicPredictionId}
 *
 * dynamicPredictionType：1 = 气藏动态预测，2 = 井组动态预测，3 = 单井动态预测。
 */
import client from '@/api/docker'
import { unwrapResponse } from '@/utils/wellGroupPrediction'

export {
  PRODUCTIVITY_EVALUATION_FORMS,
  WELL_GROUP_RESERVOIR_FIELDS,
  buildPredictionResultPayload,
  buildWellGroupCalcRequest,
  dateTextToPredictionStartTime,
  evaluateTargetDemand,
  extractWellGroupTaskNodes,
  fingerprintWellGroupPoints,
  normalizeWellGroupChart,
  predictionStartTimeToDateText
} from '@/utils/wellGroupPrediction'

function ensureApiPayload(response, operation) {
  const payload = unwrapResponse(response)
  if (typeof payload === 'string' && /<\s*(?:!doctype\s+html|html\b)/i.test(payload)) {
    throw new Error(`${operation} 返回的是HTML而不是JSON。请检查 docker.js 的 /docker-api 代理以及原平台会话。`)
  }
  if (payload && typeof payload === 'object' && payload.success === false) {
    throw new Error(`${operation} 失败：${payload.message || payload.msg || '原平台返回失败状态'}`)
  }
  return payload
}

function checkId(value, name) {
  const number = Number(value)
  if (!Number.isSafeInteger(number) || number < 0) throw new Error(`${name}必须是非负整数`)
  return number
}

export const wellGroupPredictionApi = {
  // 新建井组弹框打开时先取平台默认名称（井组 predictionType=2）；
  // 路径按原平台抓包在 dynamicprediction 后保留两个斜杠，params 为空的 bodyData 展开。
  getDefaultName: (projectId, gasReservoirId) =>
    client.get(
      `/projectanalysis/dynamicprediction//${checkId(projectId, 'projectId')}/${checkId(gasReservoirId, 'gasReservoirId')}/2/defaultname`,
      { silentError: true }
    ).then(response => ensureApiPayload(response, '获取井组动态预测默认名称')),

  // 创建井组任务；成功后返回 dynamicPredictionId / dynamicPredictionName。
  createTask: ({ projectId, gasReservoirId, wellNames, name, productivityEvaluationForm }) =>
    client.post('/projectanalysis/dynamicprediction', {
      gasReservoirId: checkId(gasReservoirId, 'gasReservoirId'),
      projectId: checkId(projectId, 'projectId'),
      wellNames: (Array.isArray(wellNames) ? wellNames : []).map(String),
      dynamicPredictionType: 2,
      dynamicPredictionName: String(name),
      productivityEvaluationForm: Number(productivityEvaluationForm) || 1
    }, { silentError: true }).then(response => ensureApiPayload(response, '创建井组动态预测任务')),

  // 任务详情：reservoirInput（总体参数）+ wellInputs（各井参数与井输入编号）由平台带出。
  getDetail: (projectId, gasReservoirId, dynamicPredictionId) =>
    client.get(
      `/projectanalysis/dynamicprediction/${checkId(projectId, 'projectId')}/${checkId(gasReservoirId, 'gasReservoirId')}/${checkId(dynamicPredictionId, 'dynamicPredictionId')}`,
      { silentError: true }
    ).then(response => ensureApiPayload(response, '获取井组预测参数详情')),

  // 动态预测目录树（nodeType 60）；井组任务为其下 nodeType=79 的叶子节点。
  getTaskTree: (projectId, gasReservoirId) =>
    client.get(
      `/projectanalysis/node/${checkId(projectId, 'projectId')}/${checkId(gasReservoirId, 'gasReservoirId')}/60`,
      { silentError: true }
    ).then(response => ensureApiPayload(response, '查询动态预测目录树')),

  // 启动预测：整体参数作为 reservoirInput 提交；平台异步计算，完成后从 /chart 取曲线。
  calculate: payload => client.post(
    '/projectanalysis/dynamicprediction/Calc', payload,
    {
      timeout: 600000,
      silentError: true,
      headers: { 'X-Project-Id': String(checkId(payload.projectId, 'projectId')) }
    }
  ).then(response => ({
    data: ensureApiPayload(response, 'Calc'),
    headers: response.headers || {},
    status: response.status
  })),

  // 井组绘图（type=wells，无 wellName 查询参数）。
  getChart: (projectId, gasReservoirId, dynamicPredictionId) =>
    client.get(
      `/projectanalysis/dynamicprediction/result/wells/chart/${checkId(projectId, 'projectId')}/${checkId(gasReservoirId, 'gasReservoirId')}/${checkId(dynamicPredictionId, 'dynamicPredictionId')}`,
      { timeout: 60000, silentError: true }
    ).then(response => ensureApiPayload(response, '原平台井组绘图数据查询')),

  // 删除平台上的预测任务（连带其输入与结果，平台侧不可恢复）。
  // 与原平台前端删除调用一致：DELETE /dynamicprediction/{p}/{g}，body 字段为 predictionId。
  removeTask: ({ projectId, gasReservoirId, predictionId }) =>
    client.delete(
      `/projectanalysis/dynamicprediction/${checkId(projectId, 'projectId')}/${checkId(gasReservoirId, 'gasReservoirId')}`,
      {
        data: {
          projectId: checkId(projectId, 'projectId'),
          gasReservoirId: checkId(gasReservoirId, 'gasReservoirId'),
          predictionId: checkId(predictionId, 'predictionId')
        },
        silentError: true
      }
    ).then(response => ensureApiPayload(response, '删除原平台井组预测任务'))
}
