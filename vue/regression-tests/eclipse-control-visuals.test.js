import test from 'node:test'
import assert from 'node:assert/strict'
import { controlTimeline, groupAllocationTree } from '../src/views/SoftwareIntegration/eclipseControlVisuals.js'
import { controlPlanFixture } from '../tests/fixtures/eclipseControlPlan.js'
import { groupStructureFixture } from '../tests/fixtures/eclipseGroupStructure.js'
import { controlPlanResponse } from '../src/views/SoftwareIntegration/eclipseControlPlan.js'
import { groupStructureResponse } from '../src/views/SoftwareIntegration/eclipseGroupStructure.js'

test('timeline includes unmodified covering controls and keeps report boundary in ending interval', () => {
  const { inspection, parameters, summary } = controlPlanFixture(), response = controlPlanResponse(summary(parameters), summary(), parameters, inspection)
  const model = controlTimeline({ inspection, parameters, response })
  assert.equal(model.endTimeDays, 3650); assert.equal(model.wells.length, 2)
  const first = model.wells[0]
  assert.deepEqual(first.periods.map(p => [p.timeDays, p.nextTimeDays, p.original, p.target, p.modified]), [[0, 365, 500, 500, false], [365, 730, 500, 250, true], [730, 1095, 500, 350, true], [1095, 3650, 500, 450, true]])
  assert.equal(first.rows.find(r => r.timeDays === 365).target, 500)
  assert.equal(first.rows.find(r => r.timeDays === 730).target, 250)
  assert.equal(first.periods[1].sourceFile, inspection.caseName); assert.ok(first.periods[1].lineNumber > 0)
})
test('unavailable or invalid control plans produce no timeline; constrained output stays observable', () => {
  const { inspection, parameters, summary } = controlPlanFixture()
  assert.equal(controlTimeline({ inspection, parameters, response: { available: false } }), null)
  assert.equal(controlTimeline({ inspection, parameters: {}, response: { available: true } }), null)
  const response = controlPlanResponse(summary(parameters, 'limited'), summary(), parameters, inspection)
  assert.equal(response.constrained, true)
  assert.ok(controlTimeline({ inspection, parameters, response }).wells[0].rows.some(r => r.atBhpLimit && !r.targetReached))
})
for (const keyword of ['GCONPROD', 'WGRUPCON', 'GEFAC']) test(`${keyword} tree retains group membership, measured rates and separate efficiency contributions`, () => {
  const { inspection, p: parameters, summary } = groupStructureFixture(keyword), response = groupStructureResponse(summary(true), summary(), parameters, inspection)
  const tree = groupAllocationTree({ inspection, parameters, response, timeDays: 10 })
  assert.equal(tree.name, 'P'); assert.equal(tree.children.length, 2); assert.equal(tree.children.flatMap(c => c.children).length, 4)
  assert.equal(tree.value, response.rows[0].rate); assert.equal(tree.details.total, response.rows[0].total)
  assert.ok(Math.abs(tree.children.reduce((s, c) => s + c.details.contribution, 0) - tree.value) < 1e-9)
  for (const child of tree.children) {
    assert.equal(child.children.reduce((s, c) => s + c.value, 0), child.value)
    assert.equal(child.details.contribution, child.value * child.details.efficiency)
  }
  if (keyword === 'GEFAC') { assert.equal(tree.children[0].details.efficiency, .5); assert.notEqual(tree.children[0].value, tree.children[0].details.contribution) }
  if (keyword === 'WGRUPCON') assert.equal(tree.children[0].children[1].details.guideRate, 3)
})
test('missing actual report or member never becomes a zero or invented tree node', () => {
  const { inspection, p: parameters, summary } = groupStructureFixture(), response = groupStructureResponse(summary(true), summary(), parameters, inspection)
  assert.equal(groupAllocationTree({ inspection, parameters, response, timeDays: 9 }), null)
  response.members.pop()
  assert.equal(groupAllocationTree({ inspection, parameters, response, timeDays: 10 }), null)
})
