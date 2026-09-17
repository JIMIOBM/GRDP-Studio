export const PIPELINE_FLOW_VERSION = 'series-gas-2.5'

/** Equal numeric inputs do not make results from a previous boundary rule current. */
export const isCurrentPipelineFlowResult = result => result?.algorithmVersion === PIPELINE_FLOW_VERSION

