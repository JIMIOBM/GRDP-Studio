<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import PipesimNetworkVariableTable from './PipesimNetworkVariableTable.vue'
import { branchComparison, matchedBranches, networkCsv, nodeResultRows, validatedLayout } from './networkResultInteraction'

const props = defineProps({
  result: { type: Object, default: null },
  partial: { type: Boolean, default: false },
  runId: { type: [String, Number], default: null }
})

const PROFILE_VARIABLES = [
  { variable: 'Pressure', label: '压力', color: '#2B6CB3' },
  { variable: 'Temperature', label: '温度', color: '#B32D2D' },
  { variable: 'MeanVelocityFluid', label: '流体平均速度', color: '#D97706' },
  { variable: 'DensityFluidInSitu', label: '原位流体密度', color: '#477A5B' },
  { variable: 'ZFactorGasInSitu', label: '原位气体 Z 因子', color: '#7C4D9E' }
]
const DEVICE_TYPES = [
  { key: 'well', name: '井 / 源点', color: '#27617a', path: 'M50 4 L70 36 L60 36 L60 78 L76 78 L76 94 L24 94 L24 78 L40 78 L40 36 L30 36 Z', size: 28 },
  { key: 'sink', name: '汇点', color: '#8d4540', path: 'M15 14 H85 V72 H62 V92 H38 V72 H15 Z', size: 27 },
  { key: 'junction', name: '汇管 / 连接点', color: '#65717a', path: 'M38 6 H62 V38 H94 V62 H62 V94 H38 V62 H6 V38 H38 Z', size: 20 },
  { key: 'control', name: '阀 / 节流 / 控制', color: '#9b6a16', path: 'M6 18 L50 50 L6 82 Z M94 18 L50 50 L94 82 Z', size: 25 },
  { key: 'pump', name: '泵', color: '#56669a', path: 'M50 5 A45 45 0 1 1 49.9 5 M28 26 L78 50 L28 74 Z', size: 27 },
  { key: 'compressor', name: '压缩机 / 透平', color: '#704f8c', path: 'M9 19 L91 7 L91 93 L9 81 Z M37 28 L75 50 L37 72 Z', size: 28 },
  { key: 'separator', name: '分离器', color: '#39705a', path: 'M34 5 H66 Q80 5 80 19 V81 Q80 95 66 95 H34 Q20 95 20 81 V19 Q20 5 34 5 M20 50 H80', size: 28 },
  { key: 'thermal', name: '加热 / 换热', color: '#a35d32', path: 'M8 8 H92 V92 H8 Z M20 20 L80 80 M80 20 L20 80', size: 25 },
  { key: 'process', name: '储罐 / 工艺设备', color: '#4f7467', path: 'M14 22 Q50 2 86 22 V78 Q50 98 14 78 Z M14 22 Q50 42 86 22', size: 28 },
  { key: 'pipe', name: '管线', color: '#353b40', path: 'M4 36 H78 V22 L98 50 L78 78 V64 H4 Z', size: 22 },
  { key: 'unknown', name: '其他', color: '#7b858d', path: 'M25 7 H75 L95 50 L75 93 H25 L5 50 Z M30 30 L70 70 M70 30 L30 70', size: 20 }
]

const topologyElement = ref(null)
const profileElement = ref(null)
const selectedBranch = ref('')
const comparedBranches = ref([])
const selectedProfileVariable = ref('Pressure')
const detailTab = ref('system')
const topologyPanel = ref(null)
const focusedNode = ref('')
const selectionLabel = ref('')
const linkedBranches = ref([])
const selectedNodeRows = ref([])
const viewMessage = ref('')
const isFullscreen = ref(false)
const layoutKey = computed(() => props.runId == null ? null : `grdp:network-layout:v1:run:${props.runId}`)
let topologyChart
let profileChart
let chartResizeObserver

const arrayValue = value => Array.isArray(value) ? value : []
const topologyNodes = computed(() => arrayValue(props.result?.topology?.nodes).filter(node =>
  typeof node?.id === 'string' && typeof node.componentType === 'string'))
const topologyEdges = computed(() => arrayValue(props.result?.topology?.edges).filter(edge =>
  typeof edge?.source === 'string' && typeof edge.destination === 'string'))
const topologyCounts = computed(() => props.result?.topology?.counts || {})
const countItems = computed(() => [
  { key: 'nodes', label: '节点', value: topologyCounts.value.nodes ?? '不可用' },
  { key: 'edges', label: '连接', value: topologyCounts.value.edges ?? '不可用' },
  { key: 'sources', label: '源点', value: topologyCounts.value.sources ?? '不可用' },
  { key: 'sinks', label: '汇点', value: topologyCounts.value.sinks ?? '不可用' },
  { key: 'flowlines', label: '流线', value: topologyCounts.value.flowlines ?? '不可用' }
])
const profiles = computed(() => arrayValue(props.result?.profiles).filter(profile =>
  typeof profile?.branch === 'string' && Array.isArray(profile.variables)))
const selectedProfile = computed(() => profiles.value.find(profile => profile.branch === selectedBranch.value) || null)
const distanceVariable = computed(() => selectedProfile.value?.variables?.find(item => item.variable === 'TotalDistance') || null)
const availableProfileVariables = computed(() => {
  const variables = selectedProfile.value?.variables || []
  return PROFILE_VARIABLES.flatMap(meta => {
    const entry = variables.find(item => item.variable === meta.variable)
    return entry ? [{ ...meta, unit: entry.unit, values: entry.values }] : []
  })
})
const unavailableProfileFields = computed(() => {
  if (!props.partial || !selectedProfile.value) return []
  const variableNames = new Set(selectedProfile.value.variables.map(item => item?.variable))
  return [
    !variableNames.has('TotalDistance') ? '总距离' : null,
    ...PROFILE_VARIABLES.filter(meta => !variableNames.has(meta.variable)).map(meta => meta.label)
  ].filter(Boolean)
})
const selectedPrimaryVariable = computed(() =>
  availableProfileVariables.value.find(item => item.variable === selectedProfileVariable.value) || null)
const profileRows = computed(() => {
  const distances = distanceVariable.value?.values || []
  const values = selectedPrimaryVariable.value?.values || []
  const length = Math.max(distances.length, values.length)
  return Array.from({ length }, (_, index) => ({
    distance: distances[index] ?? null,
    value: values[index] ?? null
  }))
})
const hasProfileSeries = computed(() => Boolean(distanceVariable.value && selectedPrimaryVariable.value && profileRows.value.length))
const comparisonOptions = computed(() => profiles.value.filter(profile => profile.branch !== selectedBranch.value).map(profile => ({
  branch: profile.branch,
  rows: branchComparison(selectedProfile.value, profile, selectedProfileVariable.value)
})))
const displayedProfiles = computed(() => [
  { branch: selectedBranch.value, rows: profileRows.value },
  ...comparisonOptions.value.filter(item => item.rows && comparedBranches.value.includes(item.branch))
])
const displayedRows = computed(() => displayedProfiles.value.flatMap(profile => profile.rows.map(row => ({ ...row, branch: profile.branch }))))
const diagnosticGroups = computed(() => [
  { key: 'errors', label: '错误', type: 'danger', items: arrayValue(props.result?.summary?.errors) },
  { key: 'warnings', label: '警告', type: 'warning', items: arrayValue(props.result?.summary?.warnings) },
  { key: 'info', label: '信息', type: 'info', items: arrayValue(props.result?.summary?.info) },
  { key: 'messages', label: '模拟器消息', type: 'info', items: arrayValue(props.result?.messages) }
])
const systemResults = computed(() => arrayValue(props.result?.system))
const nodeResults = computed(() => arrayValue(props.result?.node))
const qualityItems = computed(() => arrayValue(props.result?.quality))

const axisName = (name, unit) => unit ? `${name} (${unit})` : name
const displayValue = value => value === null || value === undefined ? '-' : value
const chartValue = value => typeof value === 'number' && Number.isFinite(value) ? value : null
const redactLocalDetails = value => String(value)
  .replace(/net\.pipe:\/\/localhost\/pipe\/[^\s'"]+/gi, 'net.pipe://localhost/pipe/[redacted]')
  .replace(/(^|[^a-z0-9])[a-z]:[\\/].*$/i, '$1[local path]')
const detailText = value => {
  if (typeof value === 'string') return redactLocalDetails(value)
  try { return redactLocalDetails(JSON.stringify(value)) }
  catch { return redactLocalDetails(value) }
}
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character])
const deviceType = componentType => {
  const type = String(componentType || '').toLowerCase()
  if (type.includes('source') || type.includes('well')) return 'well'
  if (type.includes('sink')) return 'sink'
  if (type.includes('junction') || type.includes('manifold') || type.includes('tee')) return 'junction'
  if (type.includes('valve') || type.includes('choke') || type.includes('control') || type.includes('regulator')) return 'control'
  if (type.includes('pump')) return 'pump'
  if (type.includes('compressor') || type.includes('turbine') || type.includes('rotat')) return 'compressor'
  if (type.includes('separator')) return 'separator'
  if (type.includes('heater') || type.includes('cooler') || type.includes('exchanger')) return 'thermal'
  if (type.includes('tank') || type.includes('process')) return 'process'
  if (type.includes('flowline') || type.includes('pipeline') || type.includes('pipe')) return 'pipe'
  return 'unknown'
}
const deviceMeta = componentType => DEVICE_TYPES.find(item => item.key === deviceType(componentType)) || DEVICE_TYPES.at(-1)
const shortTopologyLabel = value => {
  const text = String(value ?? '')
  return text.length > 18 ? `${text.slice(0, 17)}…` : text
}

const graphData = () => {
  const nodes = [...topologyNodes.value].sort((left, right) => String(left.id).localeCompare(String(right.id)))
  const edges = topologyEdges.value
  const ids = nodes.map(node => String(node.id))
  const knownIds = new Set(ids)
  const indegrees = new Map(ids.map(id => [id, 0]))
  const nextNodes = new Map(ids.map(id => [id, []]))
  edges.forEach(edge => {
    const source = String(edge.source)
    const destination = String(edge.destination)
    if (!knownIds.has(source) || !knownIds.has(destination)) return
    indegrees.set(destination, indegrees.get(destination) + 1)
    nextNodes.get(source).push(destination)
  })
  nextNodes.forEach(destinations => destinations.sort((left, right) => left.localeCompare(right)))

  const levels = new Map()
  const queue = ids.filter(id => indegrees.get(id) === 0).sort((left, right) => left.localeCompare(right))
  queue.forEach(id => levels.set(id, 0))
  for (let index = 0; index < queue.length; index += 1) {
    const source = queue[index]
    nextNodes.get(source).forEach(destination => {
      levels.set(destination, Math.max(levels.get(destination) || 0, (levels.get(source) || 0) + 1))
      indegrees.set(destination, indegrees.get(destination) - 1)
      if (indegrees.get(destination) === 0) queue.push(destination)
    })
  }

  // Remaining nodes are cycles. Keeping them in a deterministic rank makes every returned item visible without inferring flow.
  ids.filter(id => !levels.has(id)).forEach((id, index) => {
    const incomingLevels = edges
      .filter(edge => String(edge.destination) === id && levels.has(String(edge.source)))
      .map(edge => levels.get(String(edge.source)))
    levels.set(id, incomingLevels.length ? Math.max(...incomingLevels) + 1 : index)
  })

  const columns = new Map()
  ids.forEach(id => {
    const level = levels.get(id) || 0
    if (!columns.has(level)) columns.set(level, [])
    columns.get(level).push(id)
  })
  const categories = DEVICE_TYPES.map(item => ({ name: item.name, itemStyle: { color: item.color } }))
  const data = nodes.map(node => {
    const id = String(node.id)
    const level = levels.get(id) || 0
    const column = columns.get(level)
    const row = column.indexOf(id)
    const device = deviceMeta(node.componentType)
    return {
      id,
      name: id,
      shortLabel: shortTopologyLabel(id),
      componentType: node.componentType,
      category: DEVICE_TYPES.findIndex(item => item.key === device.key),
      x: level * 178,
      y: (row - (column.length - 1) / 2) * 72,
      symbol: `path://${device.path}`,
      symbolSize: device.size,
      itemStyle: { color: device.color, borderColor: '#fff', borderWidth: 1.2 }
    }
  })
  const links = edges.map((edge, index) => ({
    id: `edge-${index}`,
    source: String(edge.source),
    target: String(edge.destination),
    sourcePort: edge.sourcePort
  }))
  return { categories, data, links }
}

const renderTopologyChart = async (useSaved = true) => {
  await nextTick()
  if (!topologyElement.value || !topologyNodes.value.length) {
    topologyChart?.dispose()
    topologyChart = null
    return
  }
  if (topologyChart && topologyChart.getDom() !== topologyElement.value) {
    topologyChart.dispose()
    topologyChart = null
  }
  topologyChart ||= echarts.init(topologyElement.value)
  const { categories, data, links } = graphData()
  if (useSaved && layoutKey.value) {
    try {
      const positions = validatedLayout(JSON.parse(localStorage.getItem(layoutKey.value)), topologySignature.value, data.map(item => item.id))
      positions?.forEach((position, id) => Object.assign(data.find(item => item.id === id), { x: position.x, y: position.y }))
    } catch { viewMessage.value = '本机布局不可读取，已使用默认布局。' }
  }
  topologyChart.setOption({
    animation: false,
    tooltip: {
      trigger: 'item',
      formatter: params => {
        if (params.dataType === 'edge') {
          const port = params.data.sourcePort === null || params.data.sourcePort === undefined || params.data.sourcePort === '' ? '-' : params.data.sourcePort
          return `返回的连接方向<br/><strong>${escapeHtml(params.data.source)} → ${escapeHtml(params.data.target)}</strong><br/>源端口：${escapeHtml(port)}`
        }
        return `<strong>${escapeHtml(params.data.name)}</strong><br/>组件类型：${escapeHtml(params.data.componentType)}<br/>符号分类：${escapeHtml(DEVICE_TYPES[params.data.category]?.name || '其他')}`
      }
    },
    series: [{
      type: 'graph',
      layout: 'none',
      preserveAspect: 'contain',
      left: 38,
      right: 48,
      top: 16,
      bottom: 26,
      roam: true,
      draggable: true,
      data,
      links,
      categories,
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [0, 6],
      label: { show: true, position: 'right', distance: 5, color: '#344254', fontSize: 10, formatter: params => params.data.shortLabel, overflow: 'truncate', width: 120, hideOverlap: true },
      labelLayout: { hideOverlap: true },
      lineStyle: { color: '#25292d', width: 1.15, opacity: 0.88, curveness: 0 },
      emphasis: { focus: 'adjacency', lineStyle: { width: 2, opacity: 1 } }
    }]
  }, true)
  topologyChart.off('click')
  topologyChart.on('click', params => {
    if (params.dataType === 'node') selectNode(params.data.id)
    else if (params.dataType === 'edge') {
      focusedNode.value = ''
      selectionLabel.value = `${params.data.source} → ${params.data.target}`
      selectedNodeRows.value = []
      // A connection has no branch ID in the result contract. A unique common
      // BranchEquipment membership is sufficient; a nearby endpoint alone is not.
      const sourceBranches = matchedBranches(profiles.value, [params.data.source])
      const targetBranches = matchedBranches(profiles.value, [params.data.target])
      linkedBranches.value = sourceBranches.filter(branch => targetBranches.includes(branch))
      if (linkedBranches.value.length === 1) selectedBranch.value = linkedBranches.value[0]
    }
  })
  topologyChart.resize()
}

const selectNode = id => {
  if (!id) { focusedNode.value = ''; selectionLabel.value = ''; selectedNodeRows.value = []; linkedBranches.value = []; return }
  focusedNode.value = id
  selectionLabel.value = id
  selectedNodeRows.value = nodeResultRows(nodeResults.value, id)
  linkedBranches.value = matchedBranches(profiles.value, [id])
  if (linkedBranches.value.length === 1) selectedBranch.value = linkedBranches.value[0]
  topologyChart?.dispatchAction({ type: 'downplay', seriesIndex: 0 })
  topologyChart?.dispatchAction({ type: 'highlight', seriesIndex: 0, name: id })
  const series = topologyChart?.getModel().getSeriesByIndex(0)
  const data = series?.getData()
  const index = data?.indexOfName(id)
  if (index >= 0) {
    const point = data.getItemLayout(index)
    if (point?.every(Number.isFinite)) topologyChart.setOption({ series: [{ center: point }] })
  }
}
const saveLayout = () => {
  if (!layoutKey.value || !topologyChart) return
  const data = topologyChart.getModel().getSeriesByIndex(0).getData()
  const positions = topologyNodes.value.map(node => {
    const point = data.getItemLayout(data.indexOfName(node.id))
    return { id: node.id, x: point?.[0], y: point?.[1] }
  })
  const snapshot = { signature: topologySignature.value, positions }
  if (!validatedLayout(snapshot, topologySignature.value, topologyNodes.value.map(node => node.id))) {
    viewMessage.value = '布局尚未就绪，请稍后保存。'
    return
  }
  try {
    localStorage.setItem(layoutKey.value, JSON.stringify(snapshot))
    viewMessage.value = '当前运行的布局已保存到本浏览器。'
  } catch { viewMessage.value = '浏览器存储不可用，布局未保存。' }
}
const toggleFullscreen = async () => {
  try {
    if (document.fullscreenElement === topologyPanel.value) await document.exitFullscreen()
    else await topologyPanel.value?.requestFullscreen()
  } catch { viewMessage.value = '当前浏览器不支持全屏。' }
}
const onFullscreenChange = () => {
  isFullscreen.value = document.fullscreenElement === topologyPanel.value
  nextTick(resizeCharts)
}
const download = (href, name) => {
  const anchor = document.createElement('a')
  anchor.href = href
  anchor.download = `network-${props.runId ?? 'result'}-${name}`
  anchor.click()
}
const exportImage = chart => {
  if (!chart) return
  download(chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' }), chart === topologyChart ? 'topology.png' : 'profile.png')
}
const exportCsv = () => {
  const rows = [['运行ID', 'Study', '分支', '总距离', '距离单位', '变量', '数值', '变量单位'],
    ...displayedRows.value.map(row => [props.runId, props.result.study, row.branch, row.distance, distanceVariable.value?.unit, selectedProfileVariable.value, row.value, selectedPrimaryVariable.value?.unit])]
  const url = URL.createObjectURL(new Blob([networkCsv(rows)], { type: 'text/csv;charset=utf-8' }))
  download(url, 'profile.csv')
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

const renderProfileChart = async () => {
  await nextTick()
  if (!profileElement.value || !hasProfileSeries.value) {
    profileChart?.dispose()
    profileChart = null
    return
  }
  if (profileChart && profileChart.getDom() !== profileElement.value) {
    profileChart.dispose()
    profileChart = null
  }
  profileChart ||= echarts.init(profileElement.value)
  const primary = selectedPrimaryVariable.value
  profileChart.setOption({
    animation: false,
    color: [primary.color, '#477A5B', '#D97706', '#7C4D9E', '#B32D2D'],
    title: {
      text: `${selectedBranch.value} 分支剖面`,
      left: 'center',
      top: 8,
      textStyle: { color: '#303133', fontSize: 15, fontWeight: 600 }
    },
    tooltip: { trigger: 'axis', renderMode: 'richText', axisPointer: { type: 'cross' } },
    legend: { top: 34, type: 'scroll' },
    grid: { left: 76, right: 40, top: 78, bottom: 78, containLabel: true },
    xAxis: {
      type: 'value',
      name: axisName('总距离', distanceVariable.value.unit),
      nameLocation: 'middle',
      nameGap: 44,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } }
    },
    yAxis: {
      type: 'value',
      name: axisName(primary.label, primary.unit),
      nameLocation: 'middle',
      nameGap: 58,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } }
    },
    dataZoom: [
      { type: 'inside', xAxisIndex: 0 },
      { type: 'slider', xAxisIndex: 0, height: 18, bottom: 18 }
    ],
    series: displayedProfiles.value.map((profile, index) => ({
      name: profile.branch,
      type: 'line',
      showSymbol: profile.rows.length <= 80,
      symbolSize: 5,
      connectNulls: false,
      lineStyle: { width: 2, type: index ? 'dashed' : 'solid' },
      data: profile.rows.map(row => [chartValue(row.distance), chartValue(row.value)])
    }))
  }, true)
  profileChart.resize()
}

const resizeCharts = () => {
  topologyChart?.resize()
  profileChart?.resize()
}
const resetTopologyView = () => {
  try { if (layoutKey.value) localStorage.removeItem(layoutKey.value) } catch { /* View reset remains available without storage. */ }
  viewMessage.value = '已恢复默认布局。'
  renderTopologyChart(false)
}
const observeChartElements = async () => {
  await nextTick()
  chartResizeObserver?.disconnect()
  if (!chartResizeObserver) return
  if (topologyElement.value) chartResizeObserver.observe(topologyElement.value)
  if (profileElement.value) chartResizeObserver.observe(profileElement.value)
}

watch(profiles, value => {
  if (!value.some(profile => profile.branch === selectedBranch.value)) selectedBranch.value = value[0]?.branch || ''
}, { immediate: true })
watch(availableProfileVariables, value => {
  if (!value.some(item => item.variable === selectedProfileVariable.value)) {
    selectedProfileVariable.value = value.find(item => item.variable === 'Pressure')?.variable || value[0]?.variable || ''
  }
}, { immediate: true })
const topologySignature = computed(() => JSON.stringify({
  nodes: topologyNodes.value.map(node => [node.id, node.componentType]),
  edges: topologyEdges.value.map(edge => [edge.source, edge.destination, edge.sourcePort ?? null])
}))
watch(topologySignature, () => {
  renderTopologyChart()
  observeChartElements()
})
watch(() => props.runId, () => {
  focusedNode.value = ''
  selectionLabel.value = ''
  selectedNodeRows.value = []
  linkedBranches.value = []
  viewMessage.value = ''
  renderTopologyChart()
})
watch(() => props.result, () => {
  renderProfileChart()
  observeChartElements()
})
watch([selectedBranch, selectedProfileVariable], renderProfileChart)
watch([() => props.runId, () => props.result, selectedBranch, selectedProfileVariable], () => { comparedBranches.value = [] })
watch(comparedBranches, renderProfileChart)
onMounted(() => {
  window.addEventListener('resize', resizeCharts)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  if (typeof ResizeObserver !== 'undefined') chartResizeObserver = new ResizeObserver(resizeCharts)
  renderTopologyChart()
  renderProfileChart()
  observeChartElements()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  chartResizeObserver?.disconnect()
  topologyChart?.dispose()
  profileChart?.dispose()
  topologyChart = null
  profileChart = null
  chartResizeObserver = null
})
</script>

<template>
  <section v-if="result" class="network-result">
    <header class="network-result-header">
      <div>
        <span class="network-kicker">PIPESIM NETWORK</span>
        <h2>管网稳态模拟结果</h2>
        <p>Study：{{ result.study || '不可用' }}</p>
      </div>
      <el-tag :type="partial ? 'warning' : 'success'">{{ partial ? '部分真实计算结果' : (result.simulationState === 'Completed' ? '仿真完成' : result.simulationState) }}</el-tag>
    </header>

    <div class="count-strip" aria-label="管网拓扑统计">
      <div v-for="item in countItems" :key="item.key" class="count-item">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </div>
    </div>

    <section ref="topologyPanel" class="result-panel topology-panel">
      <div class="panel-heading">
        <div><h3>管网组态</h3><p>拖动节点调整当前视图；拖动空白处平移，滚轮缩放。箭头仅表示返回的连接方向，不代表实际流向。</p></div>
        <div class="topology-heading-actions">
          <span>{{ topologyNodes.length }} 个节点 / {{ topologyEdges.length }} 条连接</span>
          <button type="button" class="topology-fit-button" data-testid="network-topology-fit" aria-label="重新应用确定性布局并适应视图" @click="resetTopologyView">适应视图</button>
        </div>
      </div>
      <div class="network-view-tools">
        <el-select v-model="focusedNode" filterable clearable placeholder="搜索设备并定位" aria-label="搜索管网设备" @change="selectNode">
          <el-option v-for="node in topologyNodes" :key="node.id" :value="node.id" :label="node.id" />
        </el-select>
        <el-button size="small" :disabled="!layoutKey" @click="saveLayout">保存布局</el-button>
        <el-button size="small" @click="toggleFullscreen">{{ isFullscreen ? '退出全屏' : '全屏' }}</el-button>
        <el-button size="small" :disabled="!topologyNodes.length" @click="exportImage(topologyChart)">导出拓扑图片</el-button>
        <span role="status">{{ viewMessage }}</span>
      </div>
      <details class="topology-legend">
        <summary>设备符号</summary>
        <ul>
          <li v-for="device in DEVICE_TYPES" :key="device.key" :data-device-type="device.key">
            <svg class="topology-legend-symbol" viewBox="0 0 100 100" aria-hidden="true"><path :d="device.path" :fill="device.color" /></svg>
            <span>{{ device.name }}</span>
          </li>
        </ul>
      </details>
      <div
        v-if="topologyNodes.length"
        ref="topologyElement"
        class="topology-chart"
        role="application"
        aria-label="PIPESIM 返回的管网组态，可拖动节点、平移和滚轮缩放"
        data-testid="network-topology-canvas"
      />
      <el-empty v-else :description="partial ? '当前部分结果未提供可展示的拓扑' : '当前结果没有拓扑节点'" :image-size="72" />
      <div v-if="selectionLabel" class="network-selection" aria-label="管网点选结果">
        <div class="selection-heading"><strong>{{ selectionLabel }}</strong><span v-if="!linkedBranches.length">未返回可精确匹配的支路结果</span><el-button v-for="branch in linkedBranches" :key="branch" link @click="selectedBranch = branch">查看 {{ branch }} 剖面</el-button></div>
        <el-table v-if="selectedNodeRows.length" :data="selectedNodeRows" border size="small" max-height="200"><el-table-column prop="variable" label="变量" /><el-table-column prop="unit" label="单位" /><el-table-column label="返回值"><template #default="{ row }">{{ detailText(row.value) }}</template></el-table-column></el-table>
        <p v-else>未返回与该设备标识一致的节点变量。</p>
      </div>
    </section>

    <section class="result-panel profile-panel">
      <div class="panel-heading profile-heading">
        <div><h3>分支剖面</h3><p>TotalDistance 为横轴；空值按曲线间断显示。</p></div>
        <div class="network-view-tools"><span v-if="selectedProfile">{{ selectedProfile.pointCount }} 个点</span><el-button size="small" :disabled="!hasProfileSeries" @click="exportCsv">导出剖面 CSV</el-button><el-button size="small" :disabled="!hasProfileSeries" @click="exportImage(profileChart)">导出剖面图片</el-button></div>
      </div>
      <div v-if="profiles.length" class="profile-controls">
        <label>
          <span>分支</span>
          <el-select v-model="selectedBranch" filterable placeholder="选择分支">
            <el-option v-for="profile in profiles" :key="profile.branch" :label="profile.branch" :value="profile.branch" />
          </el-select>
        </label>
        <label>
          <span>主变量</span>
          <el-select v-model="selectedProfileVariable" :disabled="!availableProfileVariables.length" placeholder="选择剖面变量">
            <el-option
              v-for="variable in availableProfileVariables"
              :key="variable.variable"
              :label="axisName(variable.label, variable.unit)"
              :value="variable.variable"
            />
          </el-select>
        </label>
      </div>
      <div v-if="profiles.length > 1" class="branch-comparison">
        <span>对比支路</span>
        <el-select v-model="comparedBranches" multiple filterable clearable :multiple-limit="4" placeholder="选择同单位支路（最多 4 条）" aria-label="对比管网支路">
          <el-option v-for="item in comparisonOptions" :key="item.branch" :label="item.branch" :value="item.branch" :disabled="!item.rows" />
        </el-select>
        <small>实线为当前支路，虚线为对比支路；各自使用原始距离，不插值、不对齐起点。单位或数据不匹配的支路不可选。</small>
      </div>
      <p v-if="unavailableProfileFields.length" class="profile-unavailable">未返回的剖面字段（不可用）：{{ unavailableProfileFields.join('、') }}</p>
      <div v-if="hasProfileSeries" ref="profileElement" class="profile-chart" />
      <el-empty v-else :description="partial ? '当前部分结果未提供可绘制的距离和变量剖面' : '当前分支没有可绘制的主变量剖面'" :image-size="72" />
      <el-table v-if="hasProfileSeries" :data="displayedRows" border size="small" max-height="280">
        <el-table-column type="index" label="#" width="54" align="center" />
        <el-table-column prop="branch" label="支路" min-width="140" />
        <el-table-column :label="axisName('总距离', distanceVariable.unit)" min-width="180">
          <template #default="{ row }"><span :class="{ missing: row.distance === null }">{{ displayValue(row.distance) }}</span></template>
        </el-table-column>
        <el-table-column :label="axisName(selectedPrimaryVariable.label, selectedPrimaryVariable.unit)" min-width="180">
          <template #default="{ row }"><span :class="{ missing: row.value === null }">{{ displayValue(row.value) }}</span></template>
        </el-table-column>
      </el-table>
    </section>

    <section class="result-panel detail-panel">
      <el-tabs v-model="detailTab" class="network-detail-tabs">
        <el-tab-pane :label="`系统结果 (${systemResults.length})`" name="system">
          <PipesimNetworkVariableTable title="系统结果" :entries="systemResults" :empty-text="partial ? '当前部分结果未提供系统结果' : '当前运行没有系统结果'" />
        </el-tab-pane>
        <el-tab-pane :label="`节点结果 (${nodeResults.length})`" name="node">
          <PipesimNetworkVariableTable title="节点结果" :entries="nodeResults" :empty-text="partial ? '当前部分结果未提供节点结果' : '当前运行没有节点结果'" />
        </el-tab-pane>
        <el-tab-pane label="消息与质量" name="diagnostics">
          <div class="diagnostic-grid">
            <article v-for="group in diagnosticGroups" :key="group.key" class="diagnostic-group">
              <header><strong>{{ group.label }}</strong><el-tag :type="group.type" size="small">{{ group.items.length }}</el-tag></header>
              <ul v-if="group.items.length">
                <li v-for="(item, index) in group.items" :key="index">{{ detailText(item) }}</li>
              </ul>
              <span v-else>无</span>
            </article>
          </div>
          <div class="quality-heading"><strong>质量标记</strong><span>{{ qualityItems.length }} 条</span></div>
          <el-table v-if="qualityItems.length" :data="qualityItems" border size="small" max-height="320">
            <el-table-column prop="path" label="数据路径" min-width="260" show-overflow-tooltip />
            <el-table-column prop="code" label="代码" min-width="180" show-overflow-tooltip />
          </el-table>
          <el-empty v-else description="没有质量标记" :image-size="64" />
        </el-tab-pane>
      </el-tabs>
    </section>
  </section>
  <el-empty v-else description="当前运行没有有效的管网结果" :image-size="72" />
</template>

<style lang="scss" scoped>
.branch-comparison { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 12px; font-size: 12px; }
.branch-comparison .el-select { width: min(480px, 100%); }
.branch-comparison small { flex-basis: 100%; color: #73777d; line-height: 1.6; }
.network-view-tools { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; font-size: 12px; }.network-view-tools .el-select { width: 220px; }.network-view-tools .el-button + .el-button { margin-left: 0; }.network-selection { border-top: 1px solid #dcdfe6; padding-top: 8px; font-size: 12px; }.network-selection p, .selection-heading > span { color: #73777d; }.selection-heading { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }.topology-panel:fullscreen { overflow: auto; padding: 16px; background: #fff; }.topology-panel:fullscreen .topology-chart { height: 65vh; }
.network-result { min-width: 0; display: flex; flex-direction: column; gap: 16px; }
.network-result-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 18px; border: 1px solid #e1e7ef; border-top: 3px solid #f4d000; background: #f8fafc; }
.network-kicker { color: #2b6cb3; font-size: 11px; font-weight: 700; letter-spacing: .08em; }
.network-result-header h2 { margin: 3px 0 0; font-size: 17px; }
.network-result-header p { margin: 5px 0 0; color: #737a84; font-size: 12px; }
.count-strip { display: grid; grid-template-columns: repeat(5, minmax(90px, 1fr)); border: 1px solid #e1e7ef; background: #fff; }
.count-item { min-width: 0; padding: 13px 16px; border-right: 1px solid #e8edf3; }
.count-item:last-child { border-right: 0; }
.count-item span { display: block; color: #737a84; font-size: 12px; }
.count-item strong { display: block; margin-top: 3px; color: #2b3d52; font-size: 20px; font-weight: 600; }
.result-panel { min-width: 0; padding: 16px; border: 1px solid #e1e7ef; background: #fff; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.panel-heading h3 { margin: 0; font-size: 15px; }
.panel-heading p { margin: 4px 0 0; color: #909399; font-size: 12px; }
.panel-heading > span { flex: 0 0 auto; color: #737a84; font-size: 12px; }
.topology-heading-actions { display: flex; align-items: center; justify-content: flex-end; gap: 10px; color: #737a84; font-size: 12px; white-space: nowrap; }
.topology-fit-button { padding: 4px 8px; border: 1px solid #cbd6e2; border-radius: 2px; color: #42566b; background: #fff; font: inherit; cursor: pointer; }
.topology-fit-button:hover { border-color: #2b6cb3; color: #1f5f96; }
.topology-fit-button:focus-visible { outline: 2px solid #8fc0e8; outline-offset: 2px; }
.topology-legend { margin: -2px 0 8px; color: #606266; font-size: 11px; }.topology-legend summary { width: max-content; cursor: pointer; user-select: none; }.topology-legend[open] summary { margin-bottom: 7px; }
.topology-legend ul { display: flex; width: max-content; min-width: 100%; flex-wrap: wrap; gap: 7px 14px; margin: 0; padding: 0; list-style: none; }
.topology-legend li { display: flex; align-items: center; gap: 5px; flex: 0 0 auto; color: #606266; font-size: 11px; line-height: 16px; white-space: nowrap; }
.topology-legend-symbol { width: 17px; height: 17px; overflow: visible; }
.topology-chart { width: 100%; height: 400px; min-height: 300px; border: 1px solid #d9dddf; background-color: #f8f9f8; background-image: linear-gradient(#dfe3e2 1px, transparent 1px), linear-gradient(90deg, #dfe3e2 1px, transparent 1px), linear-gradient(#cbd1cf 1px, transparent 1px), linear-gradient(90deg, #cbd1cf 1px, transparent 1px); background-size: 16px 16px, 16px 16px, 80px 80px, 80px 80px; }
.profile-controls { display: grid; grid-template-columns: minmax(220px, 1fr) minmax(220px, 1fr); gap: 14px; margin-bottom: 12px; }
.profile-controls label > span { display: block; margin-bottom: 6px; color: #606266; font-size: 12px; }
.profile-controls .el-select { width: 100%; }
.profile-unavailable { margin: 0 0 12px; color: #9a4d00; font-size: 12px; font-weight: 600; }
.profile-chart { width: 100%; height: 410px; min-height: 300px; margin-bottom: 14px; border: 1px solid #e5eaf1; background: #fff; }
.detail-panel { padding-top: 4px; }
.network-detail-tabs :deep(.el-tabs__header) { margin-bottom: 14px; }
.network-detail-tabs :deep(.el-tabs__active-bar) { background: #f4d000; }
.network-detail-tabs :deep(.el-tabs__item.is-active) { color: #303133; font-weight: 600; }
.diagnostic-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.diagnostic-group { min-width: 0; min-height: 96px; padding: 12px 14px; border: 1px solid #e5eaf1; background: #fafbfc; }
.diagnostic-group header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.diagnostic-group > span { display: block; margin-top: 14px; color: #a8abb2; font-size: 12px; }
.diagnostic-group ul { max-height: 170px; margin: 10px 0 0; padding-left: 19px; overflow: auto; color: #606266; font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.quality-heading { display: flex; align-items: center; justify-content: space-between; margin: 18px 0 10px; font-size: 13px; }
.quality-heading span { color: #909399; font-size: 12px; }
.missing { color: #a8abb2; }
@media (max-width: 760px) {
  .network-result-header, .panel-heading { align-items: flex-start; flex-direction: column; }
  .topology-heading-actions { width: 100%; justify-content: space-between; white-space: normal; }
  .count-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .count-item { border-bottom: 1px solid #e8edf3; }
  .count-item:nth-child(2n) { border-right: 0; }
  .count-item:last-child { border-bottom: 0; }
  .profile-controls, .diagnostic-grid { grid-template-columns: 1fr; }
  .topology-chart { height: 330px; min-height: 280px; }
  .profile-chart { height: 350px; }
}
</style>
