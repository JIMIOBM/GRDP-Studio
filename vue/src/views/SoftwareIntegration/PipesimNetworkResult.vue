<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import PipesimNetworkVariableTable from './PipesimNetworkVariableTable.vue'

const props = defineProps({
  result: { type: Object, default: null },
  partial: { type: Boolean, default: false }
})

const PROFILE_VARIABLES = [
  { variable: 'Pressure', label: '压力', color: '#2B6CB3' },
  { variable: 'Temperature', label: '温度', color: '#B32D2D' },
  { variable: 'MeanVelocityFluid', label: '流体平均速度', color: '#D97706' },
  { variable: 'DensityFluidInSitu', label: '原位流体密度', color: '#477A5B' },
  { variable: 'ZFactorGasInSitu', label: '原位气体 Z 因子', color: '#7C4D9E' }
]
const TOPOLOGY_FAMILIES = [
  { key: 'upstream', name: '上游 / 井', color: '#216B93' },
  { key: 'transport', name: '输送', color: '#3F6178' },
  { key: 'control', name: '控制', color: '#B7791F' },
  { key: 'rotating', name: '旋转设备', color: '#76539B' },
  { key: 'process', name: '工艺设备', color: '#4D7E67' },
  { key: 'terminal', name: '出口', color: '#A94F4A' },
  { key: 'quiet', name: '连接 / 其他', color: '#8995A3' }
]

const topologyElement = ref(null)
const profileElement = ref(null)
const selectedBranch = ref('')
const selectedProfileVariable = ref('Pressure')
const detailTab = ref('system')
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
const topologyFamily = componentType => {
  const type = String(componentType || '').toLowerCase()
  if (type.includes('source') || type.includes('well')) return 'upstream'
  if (type.includes('sink')) return 'terminal'
  if (type.includes('flowline') || type.includes('pipeline') || type.includes('pipe')) return 'transport'
  if (type.includes('junction') || type.includes('manifold') || type.includes('tee')) return 'quiet'
  if (type.includes('valve') || type.includes('choke') || type.includes('control') || type.includes('regulator')) return 'control'
  if (type.includes('pump') || type.includes('compressor') || type.includes('turbine') || type.includes('rotat')) return 'rotating'
  if (type.includes('separator') || type.includes('heater') || type.includes('cooler') || type.includes('exchanger') || type.includes('tank') || type.includes('process')) return 'process'
  return 'quiet'
}
const topologyFamilyMeta = componentType =>
  TOPOLOGY_FAMILIES.find(family => family.key === topologyFamily(componentType)) || TOPOLOGY_FAMILIES.at(-1)
const componentSymbol = componentType => {
  const type = String(componentType || '').toLowerCase()
  if (type.includes('well')) return 'circle'
  if (type.includes('source')) return 'triangle'
  if (type.includes('sink')) return 'rect'
  if (type.includes('flowline') || type.includes('pipeline') || type.includes('pipe')) return 'roundRect'
  if (type.includes('junction') || type.includes('manifold') || type.includes('tee')) return 'circle'
  if (type.includes('valve') || type.includes('choke') || type.includes('control') || type.includes('regulator')) return 'diamond'
  if (type.includes('pump') || type.includes('compressor') || type.includes('turbine') || type.includes('rotat')) return 'pin'
  if (type.includes('separator') || type.includes('heater') || type.includes('cooler') || type.includes('exchanger') || type.includes('tank') || type.includes('process')) return 'rect'
  return 'circle'
}
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
  const categories = TOPOLOGY_FAMILIES.map(family => ({ name: family.name, itemStyle: { color: family.color } }))
  const data = nodes.map(node => {
    const id = String(node.id)
    const level = levels.get(id) || 0
    const column = columns.get(level)
    const row = column.indexOf(id)
    const family = topologyFamilyMeta(node.componentType)
    const quiet = family.key === 'quiet'
    return {
      id,
      name: id,
      shortLabel: shortTopologyLabel(id),
      componentType: node.componentType,
      category: TOPOLOGY_FAMILIES.findIndex(category => category.key === family.key),
      x: level * 178,
      y: (row - (column.length - 1) / 2) * 72,
      symbol: componentSymbol(node.componentType),
      symbolSize: quiet ? 14 : 26,
      itemStyle: { color: family.color, borderColor: quiet ? '#D4DCE5' : '#FFFFFF', borderWidth: quiet ? 1 : 1.5, opacity: quiet ? 0.72 : 1 }
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
          return `返回的连接方向<br/><strong>${escapeHtml(params.data.source)} → ${escapeHtml(params.data.target)}</strong>${port}`
        }
        return `<strong>${escapeHtml(params.data.name)}</strong><br/>组件类型：${escapeHtml(params.data.componentType)}<br/>功能分类：${escapeHtml(TOPOLOGY_FAMILIES[params.data.category]?.name || '连接 / 其他')}`
      }
    },
    series: [{
      type: 'graph',
      layout: 'none',
      left: 38,
      right: 48,
      top: 16,
      bottom: 26,
      roam: true,
      draggable: false,
      data,
      links,
      categories,
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [0, 8],
      label: { show: true, position: 'right', distance: 5, color: '#344254', fontSize: 10, formatter: params => params.data.shortLabel, overflow: 'truncate', width: 120, hideOverlap: true },
      labelLayout: { hideOverlap: true },
      lineStyle: { color: '#7D91A5', width: 1.25, opacity: 0.78, curveness: 0.06 },
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
const resetTopologyView = () => {
  topologyChart?.dispatchAction({ type: 'restore' })
  topologyChart?.resize()
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

    <section class="result-panel topology-panel">
      <div class="panel-heading">
        <div><h3>有向拓扑</h3><p>箭头表示 PIPESIM 返回的连接方向，不推断实际流向；可拖动画布并缩放查看。</p></div>
        <div class="topology-heading-actions">
          <span>{{ topologyNodes.length }} 个节点 / {{ topologyEdges.length }} 条连接</span>
          <button type="button" class="topology-fit-button" @click="resetTopologyView">适应视图</button>
        </div>
      </div>
      <div class="topology-legend" aria-label="拓扑功能分类图例">
        <ul>
          <li v-for="family in TOPOLOGY_FAMILIES" :key="family.key">
            <span class="topology-legend-swatch" :style="{ backgroundColor: family.color }" aria-hidden="true" />
            <span>{{ family.name }}</span>
          </li>
        </ul>
      </div>
      <div v-if="topologyNodes.length" ref="topologyElement" class="topology-chart" />
      <el-empty v-else :description="partial ? '当前部分结果未提供可展示的拓扑' : '当前结果没有拓扑节点'" :image-size="72" />
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
      <p v-if="unavailableProfileFields.length" class="profile-unavailable">未返回的剖面字段（不可用）：{{ unavailableProfileFields.join('、') }}</p>
      <div v-if="hasProfileSeries" ref="profileElement" class="profile-chart" />
      <el-empty v-else :description="partial ? '当前部分结果未提供可绘制的距离和变量剖面' : '当前分支没有可绘制的主变量剖面'" :image-size="72" />
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
.topology-legend { margin: -2px 0 10px; overflow-x: auto; scrollbar-width: thin; }
.topology-legend ul { display: flex; width: max-content; min-width: 100%; justify-content: center; gap: 14px; margin: 0; padding: 0; list-style: none; }
.topology-legend li { display: flex; align-items: center; gap: 5px; flex: 0 0 auto; color: #606266; font-size: 11px; line-height: 16px; white-space: nowrap; }
.topology-legend-swatch { width: 12px; height: 8px; border-radius: 1px; }
.topology-chart { width: 100%; height: 400px; min-height: 300px; border: 1px solid #e5eaf1; background: #fbfcfe; }
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
  .topology-legend ul { justify-content: flex-start; }
  .count-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .count-item { border-bottom: 1px solid #e8edf3; }
  .count-item:nth-child(2n) { border-right: 0; }
  .count-item:last-child { border-bottom: 0; }
  .profile-controls, .diagnostic-grid { grid-template-columns: 1fr; }
  .topology-chart { height: 330px; min-height: 280px; }
  .profile-chart { height: 350px; }
}
</style>
