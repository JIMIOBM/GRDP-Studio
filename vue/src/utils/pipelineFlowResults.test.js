import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { PIPELINE_FLOW_VERSION, isCurrentPipelineFlowResult } from './pipelineFlowResults.js'

test('old boundary-rule results remain stale even when their numeric input snapshot matches', () => {
  const result = { algorithmVersion: 'series-gas-2.3', points: [{ pressureMpa: 6, temperatureC: 30 }] }
  assert.equal(isCurrentPipelineFlowResult(result), false)
  assert.equal(isCurrentPipelineFlowResult({ ...result, algorithmVersion: PIPELINE_FLOW_VERSION }), true)
  assert.equal(isCurrentPipelineFlowResult({ points: result.points }), false)
  const calculator = fs.readFileSync(new URL('../../../backend/src/main/java/com/grdp/studio/pipeline/PipelineCalculator.java', import.meta.url), 'utf8')
  assert.equal(calculator.match(/VERSION\s*=\s*"([^"]+)"/)[1], PIPELINE_FLOW_VERSION,
    'Frontend current-result check must match the version emitted by the backend')
})

