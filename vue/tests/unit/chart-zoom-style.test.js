import test from 'node:test'
import assert from 'node:assert/strict'
import { compactChartSlider } from '../../src/views/SoftwareIntegration/chartZoomStyle.js'

test('compact slider removes preview and extra move bar while retaining range handles', () => {
  const slider = compactChartSlider()
  assert.equal(slider.type, 'slider')
  assert.equal(slider.height, 6)
  assert.equal(slider.showDataShadow, false)
  assert.equal(slider.brushSelect, false)
  assert.equal(slider.handleSize, 12)
  assert.equal(compactChartSlider(12).bottom, 12)
  assert.notEqual(compactChartSlider().handleStyle, slider.handleStyle)
})
