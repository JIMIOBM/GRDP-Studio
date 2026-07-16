'use strict'

const MPA_TO_PSIA = 145.03773773020923
const KELVIN_TO_RANKINE = 1.8

// Sutton pseudo-critical properties with Wichert-Aziz acid-gas correction.
function pseudoCriticalProperties({ specificGravity, hydrogenSulfide = 0, carbonDioxide = 0 }) {
  const gravity = positive(specificGravity, 'specificGravity')
  const h2s = fraction(hydrogenSulfide, 'hydrogenSulfide')
  const co2 = fraction(carbonDioxide, 'carbonDioxide')
  const acid = h2s + co2

  const baseTemperatureR = 169.2 + 349.5 * gravity - 74 * gravity * gravity
  const basePressurePsia = 756.8 - 131 * gravity - 3.6 * gravity * gravity
  const correctionR = acid === 0
    ? 0
    : 120 * (Math.pow(acid, 0.9) - Math.pow(acid, 1.6)) + 15 * (Math.sqrt(h2s) - Math.pow(h2s, 4))
  const temperatureR = baseTemperatureR - correctionR
  const pressurePsia = basePressurePsia * temperatureR /
    (baseTemperatureR + h2s * (1 - h2s) * correctionR)

  if (!(temperatureR > 0) || !(pressurePsia > 0)) {
    throw new RangeError('Corrected pseudo-critical properties must be positive')
  }
  return { temperatureR, pressurePsia, correctionR }
}

// Dranchuk-Abou-Kassem correlation, solved by fixed-point iteration on reduced density.
function calculateZFactor({ pressureMpa, temperatureK, specificGravity, hydrogenSulfide = 0, carbonDioxide = 0 }) {
  const pressurePsia = positive(pressureMpa, 'pressureMpa') * MPA_TO_PSIA
  const temperatureR = positive(temperatureK, 'temperatureK') * KELVIN_TO_RANKINE
  const critical = pseudoCriticalProperties({ specificGravity, hydrogenSulfide, carbonDioxide })
  const reducedPressure = pressurePsia / critical.pressurePsia
  const reducedTemperature = temperatureR / critical.temperatureR
  if (reducedTemperature < 1.0 || reducedTemperature > 3.0 || reducedPressure > 30) {
    throw new RangeError(`Gas state is outside the configured DAK range: Pr=${reducedPressure}, Tr=${reducedTemperature}`)
  }

  let z = 1
  for (let iteration = 0; iteration < 100; iteration += 1) {
    const density = 0.27 * reducedPressure / (z * reducedTemperature)
    const next = dakZ(density, reducedTemperature)
    if (!Number.isFinite(next) || next <= 0) throw new RangeError('DAK correlation produced an invalid z-factor')
    if (Math.abs(next - z) < 1e-10) return next
    z = 0.5 * z + 0.5 * next
  }
  throw new RangeError('DAK z-factor iteration did not converge')
}

function dakZ(density, temperature) {
  const [a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11] =
    [0.3265, -1.07, -0.5339, 0.01569, -0.05165, 0.5475, -0.7361, 0.1844, 0.1056, 0.6134, 0.721]
  const c1 = a1 + a2 / temperature + a3 / Math.pow(temperature, 3) +
    a4 / Math.pow(temperature, 4) + a5 / Math.pow(temperature, 5)
  const c2 = a6 + a7 / temperature + a8 / Math.pow(temperature, 2)
  const exponential = a10 * (1 + a11 * density * density) *
    (density * density / Math.pow(temperature, 3)) * Math.exp(-a11 * density * density)
  return 1 + c1 * density + c2 * density * density -
    a9 * (a7 / temperature + a8 / Math.pow(temperature, 2)) * Math.pow(density, 5) + exponential
}

function positive(value, name) {
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0) throw new RangeError(`${name} must be positive`)
  return number
}

function fraction(value, name) {
  const number = Number(value ?? 0)
  if (!Number.isFinite(number) || number < 0 || number > 1) {
    throw new RangeError(`${name} must be a mole fraction between 0 and 1`)
  }
  return number
}

module.exports = { calculateZFactor, pseudoCriticalProperties }
