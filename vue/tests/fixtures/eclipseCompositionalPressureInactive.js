import { readFileSync } from 'node:fs'
import { compositionalPressureFixture } from './eclipseCompositionalPressure.js'
export const compositionalPressureInactiveFixture = () => {
  const f = compositionalPressureFixture(), native = JSON.parse(readFileSync(new URL('./eclipseCompositionalPressure/inactive-native.json', import.meta.url), 'utf8'))
  f.parameters.targetBhpLowerLimit = 600
  f.pressure.parameters = { ...f.parameters }
  f.pressure.result.summary = native.summary; f.pressure.result.eclEnd = native.eclEnd
  f.native.pressure = native
  return f
}
