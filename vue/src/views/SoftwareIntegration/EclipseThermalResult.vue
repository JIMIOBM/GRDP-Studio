<script setup>
import { computed, ref, watch } from 'vue'
import EclipseSummaryExplorer from './EclipseSummaryExplorer.vue'
import { thermalResult, thermalObject, thermalCsv, thermalCategory } from './eclipseThermal'

const props = defineProps({ run: { type: Object, default: null }, inspection: { type: Object, default: null } })
const result = computed(() => thermalResult(props.run, props.inspection))
const category = ref('temperature'), hideUndefined = ref(true)
watch(() => props.run?.id, () => { category.value = 'temperature'; hideUndefined.value = true })
const selected = computed(() => result.value?.summary.series.filter(s => (!hideUndefined.value || s.objectName !== ':+:+:+:+') &&
  (category.value === 'all' || thermalCategory(s.keyword) === category.value)) || [])
const rows = computed(() => selected.value.map(s => ({ ...s, object: thermalObject(s, props.inspection.dimensions), time: s.points.at(-1).timeDays, last: s.points.at(-1).value })))
const plotted = computed(() => rows.value.map(s => ({ keyword: s.keyword, unit: s.unit, points: s.points, objectName: s.object + ' [' + (s.vectorIndex + 1) + ']', sourceRunId: props.run.id })))
const exportAll = () => {
  if (!result.value) return
  const url = URL.createObjectURL(new Blob(['\uFEFF' + thermalCsv(result.value)], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = 'E300-热采-' + props.run.id + '-完整原始曲线.csv'; link.click(); URL.revokeObjectURL(url)
}
</script>

<template>
  <section aria-label="ECLIPSE 300 热采计算结果">
    <el-empty v-if="!run" description="选择热采运行后查看结果" />
    <el-alert v-else-if="!result" type="warning" :closable="false" title="热采结果或模型来源核对未通过，请查看诊断。" />
    <template v-else>
      <h2>ECLIPSE 300 · 蒸汽驱热采</h2>
      <p>LIVEOIL热物性；{{ result.simulation.componentCount }}种油组分：{{ result.simulation.componentNames.join('、') }}，水与蒸汽单独参与热计算。起算{{ result.summary.startDate }}，完整保留{{ result.summary.series.length }}条曲线、每条{{ result.summary.series[0].points.length }}个实际时刻。</p>
      <p>Comment {{ result.eclEnd.comments }} · Warning {{ result.eclEnd.warnings }} · Problem {{ result.eclEnd.problems }} · Error {{ result.eclEnd.errors }} · Bug {{ result.eclEnd.bugs }}</p>
      <el-alert v-if="run.resultContract === 'VALID_PARTIAL'" type="warning" :closable="false" title="求解存在Problem，此运行不能作为完整成功方案。" />
      <el-select v-model="category" aria-label="E300热采结果类型">
        <el-option label="井温与网格温度" value="temperature" /><el-option label="产汽量与累计产汽" value="steam" />
        <el-option label="热损失与累计热损失" value="heat" /><el-option label="全部原始指标" value="all" />
      </el-select>
      <el-checkbox v-model="hideUndefined">隐藏未定义槽位</el-checkbox>
      <el-button @click="exportAll">导出全部热采原始曲线CSV</el-button>
      <p>BTEMP为网格温度，WTEMP为井温；DEG F是华氏度，DEG C是摄氏度，图表保持原生单位。WINJTEMP的输入温度用于定义注入流体焓，实际井温随井底压力变化，不必等于输入温度。未请求的温度或热指标不会补算。</p>
      <el-table :data="rows" border size="small" max-height="340" empty-text="当前筛选下没有曲线；请在工程SUMMARY中请求需要的热指标" aria-label="E300温度与热指标">
        <el-table-column label="原始列" width="80"><template #default="{ row }">{{ row.vectorIndex + 1 }}</template></el-table-column>
        <el-table-column prop="keyword" label="指标" width="110" /><el-table-column prop="object" label="井／网格／对象" min-width="145" />
        <el-table-column prop="unit" label="原生单位" width="115" /><el-table-column prop="time" label="最后实际时刻（天）" min-width="155" /><el-table-column prop="last" label="最后实际值" min-width="140" />
      </el-table>
      <EclipseSummaryExplorer :series="plotted" :run-id="run.id" />
      <p>在“结果文件”下载eclipse-thermal-input-package.zip，另一项目上传后直接复算。当前支持完整热模型计算；注汽干度、温度和热物性编辑继续按计划扩展。</p>
    </template>
  </section>
</template>
