// Select one primary notice; the original diagnostics remain in the result.
// A successful UI action must not cover an invalid or partial computation.
export const buildRunNotice = state => {
  if (state.operationNotice?.type === 'danger') return state.operationNotice
  if (state.pollingUnavailable) return { type: 'warning', message: '运行状态暂时无法刷新', action: 'refresh' }
  if (state.networkTopologyUnavailable) return { type: 'danger', message: '管网结果不可用：已隐藏未通过校验的数据。', action: 'history' }
  if (state.networkContractRejected) return { type: 'warning', message: '结果未通过展示契约', action: 'history' }
  if (state.networkWellPerformanceContractRejected) return { type: 'warning', message: '井性能曲线结果未通过合同校验；请检查运行参数和 PWI 文件。', action: 'history' }
  if (state.terminalGuidance) return { type: 'warning', message: state.terminalGuidance }
  if (state.resultExpired) return { type: 'warning', message: '解析结果已过期，运行记录仍保留。请在诊断与输出文件中查看可用文件。' }
  if (state.isPartial) return { type: 'warning', message: '组合运行部分成功：节点分析结果可用，PT 剖面失败。' }
  if (state.isNetworkPartial) return { type: 'warning', message: '部分真实计算结果：部分管网结果不可用。' }
  return state.operationNotice
}
