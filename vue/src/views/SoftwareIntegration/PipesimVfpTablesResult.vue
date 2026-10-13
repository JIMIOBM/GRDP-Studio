<script setup>
import { computed } from 'vue'

const props = defineProps({ result: { type: Object, default: null }, run: { type: Object, default: null } })
const emit = defineEmits(['download-artifact'])
const result = computed(() => props.result)
const emptyDescription = computed(() => props.run?.result?.schemaVersion === 'pipesim-vfp-tables-result/1'
  ? '该历史结果使用旧版索引方向，不能安全解释为 ECLIPSE VFPPROD；请在新版 Worker 下重新运行。'
  : '尚未形成有效 VFP 表结果')
const axes = computed(() => result.value?.axes || {})
const tableRows = computed(() => result.value?.table?.rows || [])
const temperatureRows = computed(() => result.value?.temperatureTable?.rows || [])
const tableArtifact = computed(() => {
  const artifact = props.run?.artifacts?.find(item => item?.name === 'pipesim-vfp-table.vfpprod')
  return artifact ? { ...artifact, downloadable: Number.isSafeInteger(artifact.id) && artifact.id > 0 } : null
})
const temperatureArtifact = computed(() => {
  const artifact = props.run?.artifacts?.find(item => item?.name === 'pipesim-vfp-table-temperature.vfpprod')
  return artifact ? { ...artifact, downloadable: Number.isSafeInteger(artifact.id) && artifact.id > 0 } : null
})
const engineArtifacts = computed(() => (props.run?.artifacts || [])
  .filter(item => typeof item?.name === 'string' && item.name.startsWith('pipesim-vfp-engine-'))
  .map(item => ({
    ...item,
    displayName: item.name.slice('pipesim-vfp-engine-'.length),
    downloadable: Number.isSafeInteger(item.id) && item.id > 0
  })))
const formatNumber = value => typeof value === 'number' && Number.isFinite(value) ? value.toLocaleString('zh-CN', { maximumFractionDigits: 6 }) : '-'
const axisText = name => (Array.isArray(axes.value[name]) ? axes.value[name].map(formatNumber).join(', ') : '-')
const exportCsv = () => {
  if (!result.value) return
  const temperatureByKey = new Map(temperatureRows.value.map(row => [rowKey(row), row.values || []]))
  const pressureAxis = axes.value.outletPressuresPsi || []
  const liquidRateAxis = axes.value.liquidRatesStbPerDay || []
  const lines = [['thpIndex', 'waterCutIndex', 'gorIndex', 'artificialLiftIndex', 'thpPsi', 'liquidRateStbPerDay', 'bhpPsi', 'temperatureF'].join(',')]
  for (const row of tableRows.value) {
    const temperatures = temperatureByKey.get(rowKey(row)) || []
    ;(row.values || []).forEach((value, index) => lines.push([
      row.thpIndex, row.waterCutIndex, row.gorIndex, row.artificialLiftIndex,
      pressureAxis[row.thpIndex - 1] ?? '', liquidRateAxis[index] ?? '', value, temperatures[index] ?? ''
    ].join(',')))
  }
  const blob = new Blob([`\ufeff${lines.join('\n')}`], { type: 'text/csv;charset=utf-8' })
  const href = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = href
  anchor.download = 'pipesim-vfp-tables.csv'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(href), 0)
}
const rowKey = row => [row?.thpIndex, row?.waterCutIndex, row?.gorIndex, row?.artificialLiftIndex].join(':')
</script>

<template>
  <section class="vfp-result" aria-label="PIPESIM VFP 表结果">
    <el-empty v-if="!result" :description="emptyDescription" :image-size="72" />
    <template v-else>
      <div class="result-toolbar">
        <div><strong>VFP 表 {{ result.tableNumber }}</strong><span>{{ result.producer }} · {{ result.reservoirSimulator }} · {{ result.table.valueName }} ({{ result.table.unit }})</span></div>
        <div class="result-actions">
          <el-button type="primary" plain @click="exportCsv">导出 VFP CSV</el-button>
          <el-button v-if="tableArtifact" plain @click="emit('download-artifact', tableArtifact)">下载官方 VFPPROD</el-button>
          <el-button v-if="temperatureArtifact" plain @click="emit('download-artifact', temperatureArtifact)">下载温度 VFPPROD</el-button>
          <el-button v-for="artifact in engineArtifacts" :key="artifact.id" plain :disabled="!artifact.downloadable" @click="emit('download-artifact', artifact)">下载引擎文件 · {{ artifact.displayName }}</el-button>
        </div>
      </div>
      <div class="boundary-grid">
        <div><span>生产井</span><strong>{{ result.producer }}</strong></div>
        <div><span>井底基准深度</span><strong>{{ formatNumber(result.bottomHoleDatumDepth) }}</strong></div>
        <div><span>液量轴</span><strong>{{ axes.liquidRatesStbPerDay?.length || 0 }} 点</strong></div>
        <div><span>出口压力轴</span><strong>{{ axes.outletPressuresPsi?.length || 0 }} 点</strong></div>
      </div>
      <div class="axis-card">
        <div><b>Liquid (STB/d)</b><span>{{ axisText('liquidRatesStbPerDay') }}</span></div>
        <div><b>THP (psia)</b><span>{{ axisText('outletPressuresPsi') }}</span></div>
        <div><b>WCT (fraction)</b><span>{{ axisText('waterCutFraction') }}</span></div>
        <div><b>GOR (MSCF/STB)</b><span>{{ axisText('gorMscfPerStb') }}</span></div>
        <div><b>ALQ (psia)</b><span>{{ axisText('artificialLiftInjectionDpPsi') }}</span></div>
      </div>
      <el-table :data="tableRows" border stripe size="small" aria-label="VFP BHP 表">
        <el-table-column prop="thpIndex" label="THP 索引" width="100" />
        <el-table-column prop="waterCutIndex" label="WCT 索引" width="100" />
        <el-table-column prop="gorIndex" label="GOR 索引" width="100" />
        <el-table-column prop="artificialLiftIndex" label="ALQ 索引" width="100" />
        <el-table-column label="按液量轴排列的 BHP (psia)" min-width="320"><template #default="{ row }">{{ row.values?.map((value, index) => `${formatNumber(axes.liquidRatesStbPerDay?.[index])}: ${formatNumber(value)}`).join(' · ') || '-' }}</template></el-table-column>
      </el-table>
      <el-collapse class="raw-content">
        <el-collapse-item title="温度表与官方原始 VFP 文本" name="raw">
          <p class="temperature-summary">温度表 {{ result.temperatureTable.valueName }} ({{ result.temperatureTable.unit }})：{{ temperatureRows.length }} 行；导出 CSV 已合并 BHP 与温度值。</p>
          <pre>{{ result.vfpTableContent }}</pre>
          <pre>{{ result.vfpTableWithTemperatureContent }}</pre>
        </el-collapse-item>
      </el-collapse>
    </template>
  </section>
</template>

<style lang="scss" scoped>
.vfp-result { min-width: 0; }
.result-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 14px; }
.result-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.result-toolbar div { display: flex; flex-direction: column; gap: 5px; }
.result-toolbar span, .axis-card span, .boundary-grid span { color: #697586; font-size: 12px; }
.boundary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-bottom: 14px; }
.boundary-grid div, .axis-card { border: 1px solid #e3e7ed; background: #f8fafc; padding: 10px; }
.boundary-grid div { display: flex; flex-direction: column; gap: 5px; }
.axis-card { display: grid; gap: 8px; margin-bottom: 14px; }
.axis-card div { display: grid; grid-template-columns: 180px 1fr; gap: 12px; }
.axis-card span { word-break: break-word; }
.raw-content { margin-top: 14px; }
.temperature-summary { color: #697586; }
pre { max-height: 240px; overflow: auto; white-space: pre-wrap; background: #f6f7f9; padding: 10px; font-size: 11px; }
@media (max-width: 900px) { .boundary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
