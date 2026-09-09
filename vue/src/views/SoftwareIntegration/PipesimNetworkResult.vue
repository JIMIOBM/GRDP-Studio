<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import PipesimNetworkVariableTable from './PipesimNetworkVariableTable.vue'

const props = defineProps({
  result: { type: Object, default: null }
})

const PROFILE_VARIABLES = [
  { variable: 'Pressure', label: '压力', color: '#2B6CB3' },
  { variable: 'Temperature', label: '温度', color: '#B32D2D' },
  { variable: 'MeanVelocityFluid', label: '流体平均速度', color: '#D97706' },
  { variable: 'DensityFluidInSitu', label: '原位流体密度', color: '#477A5B' },
  { variable: 'ZFactorGasInSitu', label: '原位气体 Z 因子', color: '#7C4D9E' }
]
const COMPONENT_COLORS = ['#2B6CB3', '#D97706', '#477A5B', '#7C4D9E', '#B45353', '#3F7C85', '#6B7280']

const topologyElement = ref(null)
const profileElement = ref(null)
const selectedBranch = ref('')
const selectedProfileVariable = ref('Pressure')
const detailTab = ref('system')
let topologyChart
let profileChart
let chartResizeObserver

const topologyNodes = computed(() => props.result?.topology?.nodes || [])
const topologyEdges = computed(() => props.result?.topology?.edges || [])
const topologyCounts = computed(() => props.result?.topology?.counts || {})
const countItems = computed(() => [
  { key: 'nodes', label: '节点', value: topologyCounts.value.nodes },
  { key: 'edges', label: '连接', value: topologyCounts.value.edges },
  { key: 'sources', label: '源点', value: topologyCounts.value.sources },
  { key: 'sinks', label: '汇点', value: topologyCounts.value.sinks },
  { key: 'flowlines', label: '流线', value: topologyCounts.value.flowlines }
])
const profiles = computed(() => props.result?.profiles || [])
const selectedProfile = computed(() => profiles.value.find(profile => profile.branch === selectedBranch.value) || null)
const distanceVariable = computed(() => selectedProfile.value?.variables?.find(item => item.variable === 'TotalDistance') || null)
const availableProfileVariables = computed(() => {
  const variables = selectedProfile.value?.variables || []
  return PROFILE_VARIABLES.flatMap(meta => {
    const entry = variables.find(item => item.variable === meta.variable)
    return entry ? [{ ...meta, unit: entry.unit, values: entry.values }] : []
  })
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
const diagnosticGroups = computed(() => [
  { key: 'errors', label: '错误', type: 'danger', items: props.result?.summary?.errors || [] },
  { key: 'warnings', label: '警告', type: 'warning', items: props.result?.summary?.warnings || [] },
  { key: 'info', label: '信息', type: 'info', items: props.result?.summary?.info || [] },
  { key: 'messages', label: '模拟器消息', type: 'info', items: props.result?.messages || [] }
])

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
const componentColor = componentType => {
  const type = String(componentType || '')
  const lower = type.toLowerCase()
  if (lower.includes('source')) return '#2B6CB3'
  if (lower.includes('sink')) return '#B45353'
  let hash = 0
  for (let index = 0; index < type.length; index += 1) hash = ((hash << 5) - hash + type.charCodeAt(index)) | 0
  return COMPONENT_COLORS[Math.abs(hash) % COMPONENT_COLORS.length]
}
const componentSymbol = componentType => {
  const lower = String(componentType || '').toLowerCase()
  if (lower.includes('source')) return 'circle'
  if (lower.includes('sink')) return 'rect'
  if (lower.includes('junction')) return 'diamond'
  return 'roundRect'
}

const graphData = () => {
  const nodes = topologyNodes.value
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

  const levels = new Map()
  const queue = ids.filter(id => indegrees.get(id) === 0)
  queue.forEach(id => levels.set(id, 0))
  for (let index = 0; index < queue.length; index += 1) {
    const source = queue[index]
    nextNodes.get(source).forEach(destination => {
      levels.set(destination, Math.max(levels.get(destination) || 0, (levels.get(source) || 0) + 1))
      indegrees.set(destination, indegrees.get(destination) - 1)
      if (indegrees.get(destination) === 0) queue.push(destination)
    })
  }
  ids.forEach(id => {
    if (levels.has(id)) return
    const incomingLevels = edges
      .filter(edge => String(edge.destination) === id && levels.has(String(edge.source)))
      .map(edge => levels.get(String(edge.source)))
    levels.set(id, incomingLevels.length ? Math.max(...incomingLevels) + 1 : 0)
  })

  const columns = new Map()
  ids.forEach(id => {
    const level = levels.get(id) || 0
    if (!columns.has(level)) columns.set(level, [])
    columns.get(level).push(id)
  })
  const categories = [...new Set(nodes.map(node => node.componentType))].map(name => ({
    name,
    itemStyle: { color: componentColor(name) }
  }))
  const data = nodes.map(node => {
    const id = String(node.id)
    const level = levels.get(id) || 0
    const column = columns.get(level)
    const row = column.indexOf(id)
    return {
      id,
      name: id,
      componentType: node.componentType,
      category: categories.findIndex(category => category.name === node.componentType),
      x: level * 230,
      y: (row - (column.length - 1) / 2) * 92,
      symbol: componentSymbol(node.componentType),
      symbolSize: 34,
      itemStyle: { color: componentColor(node.componentType), borderColor: '#fff', borderWidth: 1.5 }
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

const renderTopologyChart = async () => {
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
  topologyChart.setOption({
    animation: false,
    tooltip: {
      trigger: 'item',
      formatter: params => {
        if (params.dataType === 'edge') {
          const port = params.data.sourcePort ? `<br/>源端口：${escapeHtml(params.data.sourcePort)}` : ''
          return `${escapeHtml(params.data.source)} → ${escapeHtml(params.data.target)}${port}`
        }
        return `<strong>${escapeHtml(params.data.name)}</strong><br/>组件类型：${escapeHtml(params.data.componentType)}`
      }
    },
    legend: {
      show: categories.length > 0 && categories.length <= 10,
      top: 8,
      left: 'center',
      data: categories.map(category => category.name),
      itemWidth: 14,
      itemHeight: 10,
      textStyle: { color: '#606266', fontSize: 11 }
    },
    series: [{
      type: 'graph',
      layout: 'none',
      left: 54,
      right: 70,
      top: categories.length && categories.length <= 10 ? 52 : 30,
      bottom: 34,
      roam: true,
      draggable: false,
      data,
      links,
      categories,
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [0, 8],
      label: { show: true, position: 'right', color: '#303133', fontSize: 11 },
      lineStyle: { color: '#8A98AA', width: 1.4, opacity: 0.82, curveness: 0.04 },
      emphasis: { focus: 'adjacency', lineStyle: { width: 2.2, opacity: 1 } }
    }]
  }, true)
  topologyChart.resize()
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
    color: [primary.color],
    title: {
      text: `${selectedBranch.value} 分支剖面`,
      left: 'center',
      top: 8,
      textStyle: { color: '#303133', fontSize: 15, fontWeight: 600 }
    },
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    grid: { left: 76, right: 40, top: 58, bottom: 78, containLabel: true },
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
    series: [{
      name: primary.label,
      type: 'line',
      showSymbol: profileRows.value.length <= 80,
      symbolSize: 5,
      connectNulls: false,
      lineStyle: { width: 2, color: primary.color },
      data: profileRows.value.map(row => [chartValue(row.distance), chartValue(row.value)])
    }]
  }, true)
  profileChart.resize()
}

const resizeCharts = () => {
  topologyChart?.resize()
  profileChart?.resize()
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
watch(() => props.result, () => {
  renderTopologyChart()
  renderProfileChart()
  observeChartElements()
})
watch([selectedBranch, selectedProfileVariable], renderProfileChart)
onMounted(() => {
  window.addEventListener('resize', resizeCharts)
  if (typeof ResizeObserver !== 'undefined') chartResizeObserver = new ResizeObserver(resizeCharts)
  renderTopologyChart()
  renderProfileChart()
  observeChartElements()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
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
        <p>Study：{{ result.study }}</p>
      </div>
      <el-tag type="success">{{ result.simulationState === 'Completed' ? '仿真完成' : result.simulationState }}</el-tag>
    </header>

    <div class="count-strip" aria-label="管网拓扑统计">
      <div v-for="item in countItems" :key="item.key" class="count-item">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </div>
    </div>

    <section class="result-panel topology-panel">
      <div class="panel-heading">
        <div><h3>有向拓扑</h3><p>箭头表示流向；可拖动画布并缩放查看节点和连接。</p></div>
        <span>{{ topologyNodes.length }} 个节点 / {{ topologyEdges.length }} 条连接</span>
      </div>
      <div v-if="topologyNodes.length" ref="topologyElement" class="topology-chart" />
      <el-empty v-else description="当前结果没有拓扑节点" :image-size="72" />
    </section>

    <section class="result-panel profile-panel">
      <div class="panel-heading profile-heading">
        <div><h3>分支剖面</h3><p>TotalDistance 为横轴；空值按曲线间断显示。</p></div>
        <span v-if="selectedProfile">{{ selectedProfile.pointCount }} 个点</span>
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
      <div v-if="hasProfileSeries" ref="profileElement" class="profile-chart" />
      <el-empty v-else description="当前分支没有可绘制的主变量剖面" :image-size="72" />
      <el-table v-if="hasProfileSeries" :data="profileRows" border size="small" max-height="280">
        <el-table-column type="index" label="#" width="54" align="center" />
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
        <el-tab-pane :label="`系统结果 (${result.system.length})`" name="system">
          <PipesimNetworkVariableTable title="系统结果" :entries="result.system" empty-text="当前运行没有系统结果" />
        </el-tab-pane>
        <el-tab-pane :label="`节点结果 (${result.node.length})`" name="node">
          <PipesimNetworkVariableTable title="节点结果" :entries="result.node" empty-text="当前运行没有节点结果" />
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
          <div class="quality-heading"><strong>质量标记</strong><span>{{ result.quality.length }} 条</span></div>
          <el-table v-if="result.quality.length" :data="result.quality" border size="small" max-height="320">
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
.topology-chart { width: 100%; height: 430px; min-height: 320px; border: 1px solid #e5eaf1; background: #fbfcfe; }
.profile-controls { display: grid; grid-template-columns: minmax(220px, 1fr) minmax(220px, 1fr); gap: 14px; margin-bottom: 12px; }
.profile-controls label > span { display: block; margin-bottom: 6px; color: #606266; font-size: 12px; }
.profile-controls .el-select { width: 100%; }
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
  .count-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .count-item { border-bottom: 1px solid #e8edf3; }
  .count-item:nth-child(2n) { border-right: 0; }
  .count-item:last-child { border-bottom: 0; }
  .profile-controls, .diagnostic-grid { grid-template-columns: 1fr; }
  .topology-chart { height: 350px; }
  .profile-chart { height: 350px; }
}
</style>
