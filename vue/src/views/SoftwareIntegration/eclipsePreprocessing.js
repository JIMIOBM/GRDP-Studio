export const preprocessingTypes = [
  {
    id: 'e100',
    title: 'E100 注水开发',
    caseName: 'SPE9',
    detail: '黑油物性、相渗、孔渗与注水采油',
    color: '#2475bd',
  },
  {
    id: 'compositional',
    title: 'E300 组分',
    caseName: 'SPE3_GASCOND',
    detail: '凝析气组成、状态方程与循环注气',
    color: '#6f59bd',
  },
  {
    id: 'thermal',
    title: 'E300 热采',
    caseName: 'THERM3',
    detail: '岩石与流体热物性、蒸汽温度与干度',
    color: '#d27b37',
  },
]

export const unwrapPreprocessing = (response) => response?.data ?? response
export const preprocessingTerminal = new Set([
  'SUCCEEDED',
  'PARTIAL_SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'TIMED_OUT',
  'WORKER_LOST',
  'INVALID',
  'UNAVAILABLE',
])

export function preprocessingWellLocations(preview, well) {
  const column = preview.cells.filter(cell => cell.i === well.i && cell.j === well.j)
  const head = column.reduce((before, cell) => !before || cell.top < before.top ? cell : before, null)
  const completions = (well.completions ?? []).flatMap(connection =>
    preview.cells.filter(cell => cell.active && cell.i === connection.i && cell.j === connection.j &&
      cell.k >= connection.k1 && cell.k <= connection.k2).map(cell => ({ cell, status: connection.status })))
  return { head, completions }
}

export function preprocessingRows(template, values) {
  const fields = new Map(template.fields.map((field) => [field.id, field]))
  return values.map((value) => ({
    ...value,
    definition: fields.get(value.id) ?? null,
  }))
}

export function preprocessingTimeline(template, values) {
  const inputs = new Map(values.map((row) => [row.id, row.value]))
  const blocks = []
  for (const field of template.fields.filter((field) => field.keyword === 'TSTEP')) {
    let block = blocks.find((block) => block.id === field.block)
    if (!block) {
      block = { id: field.block, duration: 0 }
      blocks.push(block)
    }
    block.duration += Number(inputs.get(field.id)) * field.repeat
  }
  const result = []
  for (const control of template.controls) {
    const timeDays = blocks
      .slice(0, control.beforeTimeBlocks)
      .reduce((sum, block) => sum + block.duration, 0)
    const record = [...control.values]
    for (const id of control.fields) {
      const field = template.fields.find((field) => field.id === id)
      record[field.column] = inputs.get(id)
    }
    result.push({
      timeDays,
      keyword: control.keyword,
      object: record[0],
      description: record
        .slice(1)
        .map((value) => value ?? '继承')
        .join(' · '),
    })
  }
  return result.sort((a, b) => a.timeDays - b.timeDays)
}

export function preprocessingCurves(template, values, keyword) {
  const inputs = new Map(values.map((row) => [row.id, Number(row.value)]))
  const fields = template.fields.filter((field) => field.keyword === keyword)
  if (!fields.length) return null
  if (['PCRIT', 'TCRIT', 'SPECHA'].includes(keyword)) {
    return {
      x: '组分序号',
      xUnit: 'index',
      series: [
        {
          name: fields[0].field,
          unit: fields[0].unit,
          data: fields.map((field, index) => [index + 1, inputs.get(field.id)]),
        },
      ],
    }
  }
  if (keyword === 'PVTO') {
    const groups = [...new Set(fields.map((field) => field.record))]
    const series = groups.flatMap((record) => {
      const branch = fields.filter((field) => field.record === record)
      const rs = inputs.get(branch[0].id)
      const data = branch.slice(1).map((field) => inputs.get(field.id))
      return [1, 2].map((column) => ({
        name: `Rs ${rs} · ${column === 1 ? '油体积系数' : '油黏度'}`,
        unit: column === 1 ? 'rb/STB' : 'cP',
        data: data
          .filter((_, index) => index % 3 === 0)
          .map((pressure, index) => [pressure, data[index * 3 + column]]),
      }))
    })
    return { x: '压力', xUnit: 'psia', series }
  }
  const widths = {
    SWOF: 4,
    SGOF: 4,
    SWFN: 3,
    SGFN: 3,
    SOF3: 3,
    PVDG: 3,
    TEMPVD: 2,
    GASVISCT: template.components.length + 1,
    OILVISCT: template.components.length + 1,
  }
  const width = widths[keyword]
  if (!width) return null
  const rows = []
  for (const field of fields) {
    const index = Math.floor(field.streamIndex / width)
    rows[index] ??= []
    rows[index][field.streamIndex % width] = inputs.get(field.id)
  }
  return {
    x: fields[0].field,
    xUnit: fields[0].unit,
    series: Array.from({ length: width - 1 }, (_, index) => ({
      name: ['GASVISCT', 'OILVISCT'].includes(keyword)
        ? template.components[index]
        : fields[index + 1].field,
      unit: fields[index + 1].unit,
      data: rows
        .filter((row) => row.length === width && row.every(Number.isFinite))
        .map((row) => [row[0], row[index + 1]]),
    })),
  }
}

const dateOf = (input) => input?.values?.find((value) => value.id === 'project.startDate')?.value

export function preprocessingSpatialResult(run, build, detail) {
  const model = detail?.models?.find((entry) => entry.id === build?.modelId)
  const version = model?.versions?.find((entry) => entry.id === build?.versionId)
  if (
    !build ||
    !version ||
    detail?.project?.id !== build.projectId ||
    run?.id !== build.runId ||
    run.projectId !== build.projectId ||
    run.modelId !== build.modelId ||
    run.modelVersionId !== build.versionId ||
    run.status !== 'SUCCEEDED' ||
    run.resultContract !== 'VALID_FULL' ||
    run.resultExpired ||
    !run.result?.grid ||
    run.result.fieldIndex?.schemaVersion !== 'eclipse-binary-field-index/1' ||
    !run.result.fieldIndex.files?.length ||
    !run.artifacts?.length
  ) {
    throw new Error('本次完整运行的原生三维文件不可用，请检查运行记录和结果保留状态。')
  }
  return {
    run,
    grid: run.result.grid,
    fields: run.result.fieldIndex.files.flatMap((file) =>
      file.fields.map((field) => ({
        file: file.name,
        byteOrder: file.byteOrder,
        ...field,
      })),
    ),
    artifacts: run.artifacts,
    unitSystem: version.inspection?.unitSystem ?? '',
    wellCompletions: version.inspection?.scheduleMetadata?.completions ?? [],
  }
}

const validClock = (series) =>
  Array.isArray(series?.points) &&
  series.points.length > 0 &&
  series.points.every(
    (point, index) =>
      Number.isFinite(point.timeDays) &&
      Number.isFinite(point.value) &&
      point.timeDays >= 0 &&
      (index === 0 || point.timeDays > series.points[index - 1].timeDays),
  )

function indexedSeries(run) {
  const occurrences = new Map()
  return (run.result?.summary?.series ?? []).map((series) => {
    const identity = JSON.stringify([
      series.keyword,
      series.objectName,
      series.number ?? null,
      series.unit,
    ])
    const occurrence = occurrences.get(identity) ?? 0
    occurrences.set(identity, occurrence + 1)
    return { ...series, key: `${identity}:${occurrence}` }
  })
}

export function comparePreprocessingRuns(current, baseline, currentInput, baselineInput) {
  if (
    !currentInput ||
    !baselineInput ||
    currentInput.templateId !== baselineInput.templateId ||
    currentInput.templateVersion !== baselineInput.templateVersion ||
    !dateOf(currentInput) ||
    dateOf(currentInput) !== dateOf(baselineInput)
  )
    return {
      metrics: [],
      message: '请选择相同模板版本、相同起算日期的两次计算。',
    }
  if (
    [current, baseline].some(
      (run) =>
        run?.runType !== 'eclipse' ||
        run.status !== 'SUCCEEDED' ||
        run.resultContract !== 'VALID_FULL' ||
        run.resultExpired ||
        !run.result?.summary?.series?.length,
    )
  ) {
    return { metrics: [], message: '只有结果未过期的完整成功计算可以比较。' }
  }
  const before = new Map(indexedSeries(baseline).map((series) => [series.key, series]))
  const allowed = new Set([
    'FOPT',
    'FGPT',
    'FWIT',
    'FPR',
    'WOPT',
    'WWIT',
    'WTEMP',
    'WBHP',
    'FHLT',
    'FHLR',
  ])
  const names = {
    FOPT: '累计产油',
    FGPT: '累计产气',
    FWIT: '累计注水',
    FPR: '平均地层压力',
    WOPT: '单井累计产油',
    WWIT: '单井累计注水',
    WTEMP: '井温',
    WBHP: '井底压力',
    FHLT: '累计热损失',
    FHLR: '热损失速率',
  }
  const metrics = []
  for (const series of indexedSeries(current)) {
    if (!allowed.has(series.keyword)) continue
    const original = before.get(series.key)
    if (!original || !series.unit || !validClock(series) || !validClock(original)) continue
    const clock = new Map(original.points.map((point) => [point.timeDays, point.value]))
    const points = series.points
      .filter((point) => clock.has(point.timeDays))
      .map((point) => ({
        timeDays: point.timeDays,
        current: point.value,
        baseline: clock.get(point.timeDays),
        difference: point.value - clock.get(point.timeDays),
      }))
    if (!points.length) continue
    metrics.push({
      ...series,
      label: `${names[series.keyword]}${series.objectName ? ' · ' + series.objectName : ''} · ${series.keyword}`,
      points,
      final: points.at(-1),
    })
  }
  const priority = ['FOPT', 'FGPT', 'FWIT', 'FPR', 'WOPT', 'WTEMP', 'WWIT', 'WBHP', 'FHLT', 'FHLR']
  metrics.sort((a, b) => priority.indexOf(a.keyword) - priority.indexOf(b.keyword))
  return {
    metrics,
    message: metrics.length ? '' : '没有相同对象、原单位和真实报告时刻的可比指标。',
  }
}
