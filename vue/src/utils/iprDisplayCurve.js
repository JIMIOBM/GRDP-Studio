// 仅用于 IPR 连线展示：整段平滑拟合不参与计算、保存或数值导出。
const clamp = (value, a, b) => Math.max(Math.min(a, b), Math.min(Math.max(a, b), value))

const bernsteinBasis = (degree, t) => {
  const basis = [1]
  for (let level = 1; level <= degree; level += 1) {
    basis[level] = 0
    for (let index = level; index > 0; index -= 1) {
      basis[index] = (1 - t) * (basis[index] || 0) + t * basis[index - 1]
    }
    basis[0] *= 1 - t
  }
  return basis
}

// 有界单调投影：约束的是显示控制点，不改动任何原始采样点。
const monotoneControls = values => {
  const blocks = []
  for (const value of values) {
    blocks.push({ sum: value, count: 1 })
    while (blocks.length > 1) {
      const right = blocks.at(-1)
      const left = blocks.at(-2)
      if (left.sum / left.count <= right.sum / right.count) break
      left.sum += right.sum
      left.count += right.count
      blocks.pop()
    }
  }
  return blocks.flatMap(block => Array(block.count).fill(clamp(block.sum / block.count, 0, 1)))
}

const fitDisplayPath = (points, intervals) => {
  if (points.length < 6) return null
  const start = points[0]
  const end = points.at(-1)
  // 有真实折返时仍保留原有走势，不能为了外观把异常或缺失抹掉。
  if ([0, 1].some(axis => points.some((point, index) => end[axis] === start[axis]
    ? point[axis] !== start[axis]
    : index > 0 && (point[axis] - points[index - 1][axis]) * Math.sign(end[axis] - start[axis]) < 0))) return null
  const times = [0]
  intervals.forEach(h => times.push(times.at(-1) + h))
  const total = times.at(-1)
  const parameters = times.map(time => time / total)
  // 按弦长均匀取显示拟合样本，避免压力密集采样使竖直尾段权重过高。
  let segment = 0
  const samples = Array.from({ length: 81 }, (_, index) => {
    const t = index / 80
    while (segment < parameters.length - 2 && parameters[segment + 1] < t) segment += 1
    const ratio = (t - parameters[segment]) / (parameters[segment + 1] - parameters[segment])
    return { t, point: start.map((_, axis) => points[segment][axis]
      + ratio * (points[segment + 1][axis] - points[segment][axis])) }
  })
  // 先使用低阶整段拟合消除局部波浪；仅在偏差过大时增加自由度。
  for (const degree of [5, 7, 9]) {
    const basis = samples.map(sample => bernsteinBasis(degree, sample.t))
    const count = degree - 1
    const matrix = Array.from({ length: count }, (_, row) => Array.from({ length: count }, (_, column) =>
      basis.reduce((sum, b) => sum + b[row + 1] * b[column + 1], 0)))
    const step = 1 / Math.max(...matrix.map(row => row.reduce((sum, value) => sum + value, 0)))
    const controls = [0, 1].map(axis => {
      const span = end[axis] - start[axis]
      if (!span) return Array(degree + 1).fill(start[axis])
      const right = Array.from({ length: count }, (_, index) => samples.reduce((sum, sample, sampleIndex) =>
        sum + basis[sampleIndex][index + 1]
          * ((sample.point[axis] - start[axis]) / span - basis[sampleIndex][degree]), 0))
      let fitted = Array.from({ length: count }, (_, index) => (index + 1) / degree)
      let accelerated = fitted.slice()
      let momentum = 1
      // 小型约束最小二乘：控制点单调，整条 Bézier 曲线就不会回摆或越界。
      for (let iteration = 0; iteration < 240; iteration += 1) {
        const next = monotoneControls(accelerated.map((value, row) => value - step
          * (matrix[row].reduce((sum, entry, column) => sum + entry * accelerated[column], 0) - right[row])))
        const nextMomentum = (1 + Math.sqrt(1 + 4 * momentum ** 2)) / 2
        accelerated = next.map((value, index) => value + (momentum - 1) / nextMomentum * (value - fitted[index]))
        fitted = next
        momentum = nextMomentum
      }
      return [start[axis], ...fitted.map(value => start[axis] + span * value), end[axis]]
    })
    const at = t => {
      const b = bernsteinBasis(degree, t)
      return controls.map((values, axis) => clamp(values.reduce((sum, value, index) => sum + value * b[index], 0), start[axis], end[axis]))
    }
    // 显示偏差不得超过各坐标跨度的 2%；不能以平滑之名大幅改变走势。
    const acceptable = samples.every(sample => at(sample.t).every((value, axis) => Math.abs(value - sample.point[axis]) <= 0.02))
      && points.every((point, index) => at(parameters[index]).every((value, axis) => Math.abs(value - point[axis]) <= 0.02))
    if (acceptable) return Array.from({ length: 1001 }, (_, index) => at(index / 1000))
  }
  return null
}

const tangents = (values, intervals) => {
  const slopes = intervals.map((h, index) => (values[index + 1] - values[index]) / h)
  const endpoint = (h0, h1, d0, d1) => {
    const slope = ((2 * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
    if (Math.sign(slope) !== Math.sign(d0)) return 0
    return Math.sign(d0) !== Math.sign(d1) && Math.abs(slope) > Math.abs(3 * d0)
      ? 3 * d0 : slope
  }
  return values.map((_, index) => {
    if (index === 0) return endpoint(intervals[0], intervals[1], slopes[0], slopes[1])
    if (index === values.length - 1) {
      const last = slopes.length - 1
      return endpoint(intervals[last], intervals[last - 1], slopes[last], slopes[last - 1])
    }
    const previous = slopes[index - 1]
    const next = slopes[index]
    if (!previous || !next || Math.sign(previous) !== Math.sign(next)) return 0
    const w1 = 2 * intervals[index] + intervals[index - 1]
    const w2 = intervals[index] + 2 * intervals[index - 1]
    return (w1 + w2) / (w1 / previous + w2 / next)
  })
}

const smoothRun = points => {
  if (points.length < 3) return points.slice()
  const x = points.map(point => point[0])
  const y = points.map(point => point[1])
  const minimum = [Math.min(...x), Math.min(...y)]
  const span = [Math.max(...x) - minimum[0], Math.max(...y) - minimum[1]]
  if (!span.every(Number.isFinite)) return points.slice()
  const scales = span.map(value => value || 1)
  // 弦长参数化同时兼容采气、注气、竖直段和不均匀间隔，不排序或更改原有点。
  const normalized = points.map(point => point.map((value, axis) => (value - minimum[axis]) / scales[axis]))
  const intervals = normalized.slice(1).map((point, index) =>
    Math.hypot(point[0] - normalized[index][0], point[1] - normalized[index][1]))
  if (intervals.some(value => !Number.isFinite(value) || value === 0)) return points.slice()
  const fitted = fitDisplayPath(normalized, intervals)
  if (fitted) {
    const display = fitted.map(point => point.map((value, axis) => minimum[axis] + scales[axis] * value))
    display[0] = points[0]
    display[display.length - 1] = points.at(-1)
    return display
  }
  // 短序列、真实非单调数据或偏差超限时，仅消除折角，不强制改写曲线形状。
  const derivatives = [0, 1].map(axis => tangents(normalized.map(point => point[axis]), intervals))
  const steps = Math.max(1, Math.min(64, Math.ceil(1000 / intervals.length)))
  const display = [points[0]]
  intervals.forEach((h, index) => {
    const start = normalized[index]
    const end = normalized[index + 1]
    const c1 = start.map((value, axis) => clamp(value + h * derivatives[axis][index] / 3, value, end[axis]))
    const c2 = end.map((value, axis) => clamp(value - h * derivatives[axis][index + 1] / 3, start[axis], value))
    for (let step = 1; step < steps; step += 1) {
      const t = step / steps
      const u = 1 - t
      display.push(start.map((value, axis) => {
        const interpolated = u ** 3 * value + 3 * u ** 2 * t * c1[axis]
          + 3 * u * t ** 2 * c2[axis] + t ** 3 * end[axis]
        return clamp(minimum[axis] + scales[axis] * interpolated, points[index][axis], points[index + 1][axis])
      }))
    }
    // 端点直接使用原值，避免插值舍入影响截距和原有采样点。
    display.push(points[index + 1])
  })
  return display
}

export const smoothIprDisplayPoints = data => {
  const display = []
  let run = []
  const flush = () => {
    display.push(...smoothRun(run))
    run = []
  }
  for (const point of data) {
    if (!Array.isArray(point) || !Number.isFinite(point[0]) || !Number.isFinite(point[1])) {
      flush()
      display.push([null, null])
      continue
    }
    const previous = run.at(-1)
    if (!previous || previous[0] !== point[0] || previous[1] !== point[1]) run.push(point)
  }
  flush()
  return display
}

// 画线用稠密显示点，取值/提示仍用原始系列；不把插值点当作计算数据。
export const buildIprDisplaySeries = series => {
  // 零流量散点及其标签沿用原展示方式。
  if (series.type !== 'line') return [series]
  return [
    {
      ...series,
      data: smoothIprDisplayPoints(series.data || []),
      smooth: false,
      showSymbol: false,
      symbol: 'none',
      silent: true,
      tooltip: { show: false, trigger: 'none' },
      axisPointer: { show: false },
      emphasis: { disabled: true }
    },
    {
      ...series,
      // 原始系列只负责悬停取值；同名展示系列负责连线和图例，避免提示插值数值。
      smooth: false,
      lineStyle: { ...series.lineStyle, opacity: 0 },
      emphasis: { disabled: true }
    }
  ]
}
