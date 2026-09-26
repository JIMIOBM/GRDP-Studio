// 仅用于库级空间展示：不写回、平滑或修改单井产能结果。
export const PRODUCTIVITY_COLORS = ['#b6d9f5', '#a3e0dd', '#d4ecc3', '#fff0bb', '#f3bf8c', '#dc8864']
export const GEOLOGY_COLORS = ['#f7f4e8', '#e3eef3', '#b5d6e8', '#80b2d2', '#568bb7']
export const numeric = value => value === null || value === undefined || typeof value === 'boolean'
  || typeof value === 'object' || String(value).trim() === '' ? null : Number.isFinite(Number(value)) ? Number(value) : null

export function readCoordinateRows(response) {
  const payload = response?.data?.data ?? response?.data ?? response
  const rows = Array.isArray(payload) ? payload : payload?.items ?? payload?.rows
  if (!Array.isArray(rows)) throw new Error('井头坐标接口返回格式不正确')
  return rows
}

// 坐标只按井名对应；同名井存在不同坐标时标记异常，不任取一条制造“真实井位”。
export function matchWellCoordinates(wells, rows) {
  const byName = new Map()
  for (const row of rows) {
    const name = String(row?.wellName ?? '').trim()
    if (!name) continue
    if (!byName.has(name)) byName.set(name, [])
    byName.get(name).push({ x: numeric(row.xCoordinate), y: numeric(row.yCoordinate) })
  }
  return wells.map(well => {
    const records = byName.get(String(well.wellName ?? '').trim()) || []
    const valid = records.filter(row => row.x !== null && row.y !== null)
    const unique = new Map(valid.map(row => [`${row.x},${row.y}`, row]))
    const coordinate = unique.size === 1 ? [...unique.values()][0] : null
    return { ...well, xCoordinate: coordinate?.x ?? null, yCoordinate: coordinate?.y ?? null,
      coordinateIssue: coordinate ? '' : unique.size > 1 ? '同井名坐标不唯一' : '缺少有效坐标' }
  })
}

export function validBounds(value) {
  if (!value) return null
  const bounds = Object.fromEntries(['minX', 'maxX', 'minY', 'maxY'].map(key => [key, numeric(value[key])]))
  if (Object.values(bounds).some(n => n === null) || bounds.maxX <= bounds.minX || bounds.maxY <= bounds.minY
    || !Number.isFinite(bounds.maxX - bounds.minX) || !Number.isFinite(bounds.maxY - bounds.minY)) return null
  return bounds
}

export function coordinateBounds(wells, extraBounds = null) {
  const points = wells.filter(well => numeric(well.xCoordinate) !== null && numeric(well.yCoordinate) !== null)
  const xs = points.map(p => Number(p.xCoordinate)), ys = points.map(p => Number(p.yCoordinate))
  const extra = validBounds(extraBounds)
  if (extra) { xs.push(extra.minX, extra.maxX); ys.push(extra.minY, extra.maxY) }
  if (!xs.length) return null
  let minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys)
  const span = Math.max(maxX - minX, maxY - minY, 1), padding = span * .1
  // 扩展观察范围而非分别缩放 X/Y，真实距离比例始终保留。
  const centerX = minX + (maxX - minX) / 2, centerY = minY + (maxY - minY) / 2
  const halfX = Math.max((maxX - minX) / 2 + padding, span * .16)
  const halfY = Math.max((maxY - minY) / 2 + padding, span * .16)
  return { minX: centerX - halfX, maxX: centerX + halfX, minY: centerY - halfY, maxY: centerY + halfY }
}

export function spatialLayout(wells, bounds) {
  const valid = validBounds(bounds)
  if (!valid) return null
  const dx = valid.maxX - valid.minX, dy = valid.maxY - valid.minY, scale = 16 / Math.max(dx, dy)
  const centerX = valid.minX + dx / 2, centerY = valid.minY + dy / 2
  const project = (x, y) => ({ x: (x - centerX) * scale, z: -(y - centerY) * scale })
  return { bounds: valid, scale, planeWidth: dx * scale, planeDepth: dy * scale, project,
    wells: wells.filter(well => numeric(well.xCoordinate) !== null && numeric(well.yCoordinate) !== null)
      .map(well => ({ ...well, position: project(well.xCoordinate, well.yCoordinate) })) }
}

const cross = (a, b, c) => (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
export function convexHull(points) {
  const sorted = [...new Map(points.map(p => [`${p.x},${p.y}`, p])).values()].sort((a, b) => a.x - b.x || a.y - b.y)
  if (sorted.length < 3) return sorted
  const half = list => {
    const result = []
    for (const point of list) {
      while (result.length > 1 && cross(result.at(-2), result.at(-1), point) <= 0) result.pop()
      result.push(point)
    }
    return result
  }
  return [...half(sorted).slice(0, -1), ...half([...sorted].reverse()).slice(0, -1)]
}
export function insideHull(point, hull) {
  return hull.length >= 3 && hull.every((p, i) => cross(p, hull[(i + 1) % hull.length], point) >= -1e-10)
}

export function prepareSpatialSamples(points, bounds) {
  const domain = validBounds(bounds)
  if (!domain) return { samples: [], hull: [], message: '暂无可用井坐标' }
  const scale = Math.max(domain.maxX - domain.minX, domain.maxY - domain.minY)
  const samples = new Map()
  for (const p of points) {
    const x = numeric(p.x), y = numeric(p.y), value = numeric(p.value)
    if (x === null || y === null || value === null || value < 0) continue
    const key = `${x},${y}`
    if (samples.has(key) && samples.get(key).value !== value)
      return { samples: [], hull: [], message: '存在同坐标、不同产能的井，无法生成唯一的产能等值线' }
    samples.set(key, { x: (x - domain.minX) / scale, y: (y - domain.minY) / scale, value })
  }
  const values = [...samples.values()], hull = convexHull(values)
  if (values.length < 3) return { samples: values, hull, message: '至少需要 3 个不同井位的有效产能，当前只显示井位和柱子' }
  // 以样点覆盖跨度归一化判断退化，避免仅因底图范围较大而错误禁用。
  const span = Math.max(Math.max(...values.map(p => p.x)) - Math.min(...values.map(p => p.x)),
    Math.max(...values.map(p => p.y)) - Math.min(...values.map(p => p.y)))
  const area = hull.reduce((sum, p, i) => sum + cross(hull[0], p, hull[(i + 1) % hull.length]), 0) / 2
  if (hull.length < 3 || area < span * span * .002)
    return { samples: values, hull, message: '有效井位共线或接近共线，无法可靠生成二维等值线' }
  return { samples: values, hull, scale, message: '' }
}

/** IDW p=2。先缩放权重/数值以避免小距离或大数值溢出；样点处精确通过原值。 */
export function idwValue(x, y, samples) {
  if (!samples.length) return null
  const distances = samples.map(point => (point.x - x) ** 2 + (point.y - y) ** 2)
  const exact = distances.findIndex(distance => distance < 1e-24)
  if (exact >= 0) return samples[exact].value
  const nearest = Math.min(...distances), maxValue = Math.max(...samples.map(p => p.value))
  if (maxValue === 0) return 0
  let total = 0, weighted = 0
  samples.forEach((p, index) => { const weight = nearest / distances[index]; total += weight; weighted += weight * (p.value / maxValue) })
  return weighted / total * maxValue
}

// 仅生成绘图用的井间估计场；原始井点产能、业务计算结果和数据库记录均不修改。
export function buildSpatialField(points, bounds, resolution = 96) {
  const prepared = prepareSpatialSamples(points, bounds)
  if (prepared.message) return { message: prepared.message, count: prepared.samples.length }
  const { samples, hull, scale } = prepared
  const nx = Math.max(24, Math.min(144, Math.round(resolution))), ny = nx
  const values = new Float64Array((nx + 1) * (ny + 1)); values.fill(NaN)
  for (let j = 0; j <= ny; j++) for (let i = 0; i <= nx; i++) {
    const x = i / nx * (bounds.maxX - bounds.minX) / scale
    const y = (1 - j / ny) * (bounds.maxY - bounds.minY) / scale
    // 只在样点凸包内估计，绝不填满没有数据支持的库外区域。
    if (insideHull({ x, y }, hull)) values[j * (nx + 1) + i] = idwValue(x, y, samples)
  }
  const min = Math.min(...samples.map(p => p.value)), max = Math.max(...samples.map(p => p.value))
  return { values, nx, ny, min, max, count: samples.length, message: '', constant: min === max,
    hull: hull.map(p => ({ x: p.x * scale / (bounds.maxX - bounds.minX), y: 1 - p.y * scale / (bounds.maxY - bounds.minY) })) }
}

/** 模拟厚度场只依赖平面位置，与真实产能无关；仅用于演示/底图导入练习。 */
export function buildSimulatedGeology(resolution = 100) {
  const nx = resolution, ny = resolution, values = new Float64Array((nx + 1) * (ny + 1))
  for (let j = 0; j <= ny; j++) for (let i = 0; i <= nx; i++) {
    const x = i / nx, y = j / ny
    const ridge = Math.exp(-((x - .55) ** 2 / .13 + (y - (.32 + .28 * x)) ** 2 / .035))
    const lobe = Math.exp(-((x - .19) ** 2 / .015 + (y - .76) ** 2 / .04))
    values[j * (nx + 1) + i] = 20 + 65 * ridge + 13 * lobe
  }
  return { values, nx, ny, min: 20, max: 100, message: '', simulated: true }
}

// 三角剖分网格提取等值线，避免 marching squares 的鞍点歧义；不改变插值场或原始数据。
export function contourSegments(field, levels) {
  if (!field?.values) return []
  const { nx, ny, values } = field, result = []
  for (const level of levels) {
    const segments = []
    for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) {
      const cell = [[i, j], [i + 1, j], [i + 1, j + 1], [i, j + 1]].map(([x, y]) =>
        ({ x: x / nx, y: y / ny, v: values[y * (nx + 1) + x] }))
      for (const ids of [[0, 1, 2], [0, 2, 3]]) {
        const triangle = ids.map(n => cell[n])
        if (triangle.some(p => !Number.isFinite(p.v))) continue
        const hits = []
        triangle.forEach((a, k) => {
          const b = triangle[(k + 1) % 3]
          if ((a.v < level) !== (b.v < level)) {
            const t = (level - a.v) / (b.v - a.v)
            hits.push({ x: a.x + t * (b.x - a.x), y: a.y + t * (b.y - a.y) })
          }
        })
        if (hits.length === 2 && Math.hypot(hits[0].x - hits[1].x, hits[0].y - hits[1].y) > 1e-10) segments.push(hits)
      }
    }
    result.push({ level, segments })
  }
  return result
}

export function fieldColor(value, min, max, palette = PRODUCTIVITY_COLORS) {
  const ratio = Math.max(0, Math.min(1, max > min ? (value - min) / (max - min) : .5)) * (palette.length - 1)
  const index = Math.min(palette.length - 2, Math.floor(ratio)), t = ratio - index
  const rgb = hex => [1, 3, 5].map(offset => parseInt(hex.slice(offset, offset + 2), 16))
  const a = rgb(palette[index]), b = rgb(palette[index + 1])
  return a.map((channel, n) => Math.round(channel + (b[n] - channel) * t))
}

/** 返回浏览器 Canvas，供平面纹理和模拟底图下载共用；无 WebGL 依赖。 */
export function paintSpatialMap({ field, maximum = 1, fill = true, lines = true, simulated = false, size = 1024 }) {
  const canvas = document.createElement('canvas'); canvas.width = size; canvas.height = size
  const ctx = canvas.getContext('2d'); ctx.fillStyle = '#f5f7f8'; ctx.fillRect(0, 0, size, size)
  if (!field?.values) return canvas
  const palette = simulated ? GEOLOGY_COLORS : PRODUCTIVITY_COLORS
  const min = simulated ? 20 : 0, max = simulated ? 100 : maximum
  if (fill) {
    const raster = document.createElement('canvas'); raster.width = field.nx + 1; raster.height = field.ny + 1
    const target = raster.getContext('2d'), pixels = target.createImageData(raster.width, raster.height)
    field.values.forEach((original, index) => {
      let value = original
      if (!Number.isFinite(value) && field.hull?.length) {
        // 纹理边缘补一个像素仅用于抗锯齿；下方仍严格裁剪到凸包，不向库外外推产能。
        const x = index % raster.width, y = Math.floor(index / raster.width)
        const neighbors = []
        for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
          if (x + dx < 0 || x + dx >= raster.width || y + dy < 0 || y + dy >= raster.height) continue
          const candidate = field.values[(y + dy) * raster.width + x + dx]
          if (Number.isFinite(candidate)) neighbors.push(candidate)
        }
        if (neighbors.length) value = neighbors.reduce((sum, n) => sum + n / neighbors.length, 0)
      }
      if (!Number.isFinite(value)) return
      pixels.data.set([...fieldColor(value, min, max, palette), 235], index * 4)
    })
    target.putImageData(pixels, 0, 0)
    ctx.save()
    if (field.hull?.length) {
      ctx.beginPath(); field.hull.forEach((p, i) => i ? ctx.lineTo(p.x * size, p.y * size) : ctx.moveTo(p.x * size, p.y * size)); ctx.closePath(); ctx.clip()
    }
    ctx.imageSmoothingEnabled = true; ctx.drawImage(raster, 0, 0, size, size); ctx.restore()
  }
  if (lines && !field.constant) {
    const levels = simulated ? [30, 40, 50, 60, 70, 80] : Array.from({ length: 7 }, (_, i) => maximum * (i + 1) / 8)
    const occupied = []
    for (const { level, segments } of contourSegments(field, levels)) {
      ctx.beginPath()
      for (const [a, b] of segments) { ctx.moveTo(a.x * size, a.y * size); ctx.lineTo(b.x * size, b.y * size) }
      ctx.strokeStyle = simulated ? '#537c9d' : '#5f7c83'; ctx.lineWidth = 1.4; ctx.stroke()
      // 只标少量数值，避免密集标注压住井位。长曲线的分段位置作为候选。
      let written = 0
      for (let n = Math.floor(segments.length * .2); n < segments.length && written < 2; n += Math.max(1, Math.floor(segments.length / 12))) {
        const [a, b] = segments[n], x = (a.x + b.x) / 2 * size, y = (a.y + b.y) / 2 * size
        if (x < 40 || x > size - 40 || y < 40 || y > size - 40 || occupied.some(p => Math.hypot(x - p.x, y - p.y) < 95)) continue
        const text = level > 1e6 || level < .01 ? level.toExponential(1) : Number(level.toPrecision(4)).toLocaleString('zh-CN')
        ctx.font = '19px "Microsoft YaHei", sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
        const w = ctx.measureText(text).width + 10
        ctx.fillStyle = '#ffffffd9'; ctx.fillRect(x - w / 2, y - 12, w, 24)
        ctx.fillStyle = '#3a5b6a'; ctx.fillText(text, x, y); occupied.push({ x, y }); written++
      }
    }
  }
  return canvas
}

export function imagePlacement(imageBounds, domain) {
  const image = validBounds(imageBounds), area = validBounds(domain)
  if (!image || !area) return null
  return { x: (image.minX - area.minX) / (area.maxX - area.minX), y: (area.maxY - image.maxY) / (area.maxY - area.minY),
    width: (image.maxX - image.minX) / (area.maxX - area.minX), height: (image.maxY - image.minY) / (area.maxY - area.minY) }
}
