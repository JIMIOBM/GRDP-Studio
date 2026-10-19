import { controlPlanPeriods, controlPlanKey, validControlPlan } from './eclipseControlPlan.js'
import { waterInjectionPlanPeriods, waterInjectionPlanKey, validWaterInjectionPlan } from './eclipseWaterInjectionPlan.js'
import { groupStructureRecords, validGroupStructure } from './eclipseGroupStructure.js'

export function controlTimeline({ inspection, parameters, response, injection = false }) {
  if (!response?.available || !(injection ? validWaterInjectionPlan(parameters) : validControlPlan(parameters))) return null
  const periods = (injection ? waterInjectionPlanPeriods : controlPlanPeriods)(inspection)
  const key = injection ? waterInjectionPlanKey : controlPlanKey
  const edits = new Map(parameters.edits.map(e => [key(e), e]))
  const wells = [...new Set(parameters.edits.map(e => e.well))].map(well => ({ well, periods: periods.filter(p => p.well === well).map(p => {
    const e = edits.get(key(p)), original = injection ? p.originalInjectionRate : p.originalOilRate
    return { ...p, original, target: e ? injection ? e.targetInjectionRate : e.targetOilRate : original, modified: !!e, key: key(p) }
  }), rows: response.rows.filter(r => r.well === well).slice().sort((a, b) => a.timeDays - b.timeDays) }))
  if (!wells.length || wells.some(w => !w.periods.length || !w.rows.length)) return null
  const endTimeDays = Math.max(...wells.flatMap(w => w.periods.map(p => p.nextTimeDays)))
  if (!Number.isFinite(endTimeDays) || endTimeDays <= 0) return null
  return { wells, endTimeDays, flowUnit: response.flowUnit, pressureUnit: response.pressureUnit }
}

export function groupAllocationTree({ inspection, parameters, response, timeDays }) {
  if (!response?.available || !validGroupStructure(parameters)) return null
  const control = groupStructureRecords(inspection).find(c => c.keyword === parameters.keyword && c.objectName === parameters.objectName && c.sourceFile === parameters.sourceFile && c.lineNumber === parameters.lineNumber)
  const point = response.rows.find(r => r.timeDays === timeDays)
  if (!control || !point) return null
  const node = (name, type, value, baseline, details, children = []) => ({ name, type, value, baseline, details, children })
  const tree = node(parameters.parentGroup, '父组', point.rate, point.baselineRate,
    { target: point.target, total: point.total, contribution: point.contribution }, control.leaves.map(leaf => {
      const output = response.leaves.find(l => l.timeDays === timeDays && l.group === leaf.group)
      if (!output) return null
      const members = leaf.members.map(m => {
        const actual = response.members.find(r => r.timeDays === timeDays && r.group === leaf.group && r.well === m.well)
        return actual ? node(m.well, '井', actual.rate, actual.baselineRate, { bhp: actual.bhp, bhpLimit: actual.bhpLimit, oilRateLimit: actual.oilRateLimit, guideRate: actual.guideRate, guideTarget: actual.guideTarget, efficiency: actual.efficiency, atBhpLimit: actual.atBhpLimit, atOilLimit: actual.atOilLimit }) : null
      })
      return members.some(m => !m) ? null : node(leaf.group, '子组', output.rate, output.baselineRate, { efficiency: output.efficiency, contribution: output.contribution, total: output.total }, members)
    }))
  return tree.children.some(child => !child) ? null : tree
}
