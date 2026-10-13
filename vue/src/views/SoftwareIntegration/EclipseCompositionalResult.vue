<script setup>
import { computed, ref, watch } from 'vue'
import EclipseSummaryExplorer from './EclipseSummaryExplorer.vue'
import { componentOf, compositionalResult, compositionalObject, compositionalCsv } from './eclipseCompositional'

const props = defineProps({ run: { type: Object, default: null }, inspection: { type: Object, default: null } })
const result = computed(() => compositionalResult(props.run, props.inspection))
const componentIndex = ref(null), hideUndefined = ref(true)
watch(() => props.run?.id, () => { componentIndex.value = null; hideUndefined.value = true })
const selected = computed(() => result.value?.summary.series.filter(s => (!hideUndefined.value || s.objectName !== ':+:+:+:+') &&
  (!componentIndex.value || componentOf(s, result.value.simulation)?.index === componentIndex.value)) || [])
const rows = computed(() => selected.value.map(s => ({ ...s, object: compositionalObject(s, props.inspection.dimensions),
  component: componentOf(s, result.value.simulation)?.name || '—', last: s.points.at(-1).value, time: s.points.at(-1).timeDays })))
const plotted = computed(() => rows.value.map(s => ({ keyword: s.keyword, unit: s.unit, points: s.points,
  objectName: `${s.object}${s.component === '—' ? '' : ' · ' + s.component} [${s.vectorIndex + 1}]`, sourceRunId: props.run.id })))
const exportAll = () => {
  if (!result.value) return
  const url = URL.createObjectURL(new Blob(['\uFEFF' + compositionalCsv(result.value)], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = `E300-${props.run.id}-完整原始曲线.csv`; link.click(); URL.revokeObjectURL(url)
}
</script>

<template>
  <section aria-label="ECLIPSE 300 组分计算结果">
    <el-empty v-if="!run" description="选择组分计算运行后查看结果" />
    <el-alert v-else-if="!result" type="warning" :closable="false" title="组分结果或模型来源核对未通过，请查看运行诊断；不展示合并或推算曲线。" />
    <template v-else>
      <h2>ECLIPSE 300 · {{ result.simulation.componentCount }}组分</h2>
      <p>Peng-Robinson状态方程；起算{{ result.summary.startDate }}。工程请求的{{ result.summary.series.length }}条原始曲线、每条{{ result.summary.series[0].points.length }}个实际时刻完整保留。</p>
      <el-alert v-if="run.resultContract === 'VALID_PARTIAL'" type="warning" :closable="false" :title="`求解报告${result.eclEnd.problems}个Problem，请查看诊断；此运行不能作为完整成功方案。`" />
      <p>Comment {{ result.eclEnd.comments }} · Warning {{ result.eclEnd.warnings }} · Problem {{ result.eclEnd.problems }} · Error {{ result.eclEnd.errors }} · Bug {{ result.eclEnd.bugs }}</p>
      <label>组分
        <el-select v-model="componentIndex" clearable placeholder="全部指标" aria-label="E300结果组分">
          <el-option v-for="(name, i) in result.simulation.componentNames" :key="i" :value="i + 1" :label="`${i + 1} · ${name}`" />
        </el-select>
      </label>
      <el-checkbox v-model="hideUndefined">隐藏未定义槽位</el-checkbox>
      <el-button @click="exportAll">导出全部原始曲线CSV</el-button>
      <p>WXMF为生产井液相组分摩尔分数，BXMF/BYMF为网格液相/气相组分摩尔分数，数值无量纲。仅显示工程实际请求的组分；未请求的组分不补算。重复曲线保留各自原始列编号，未定义槽位可取消隐藏查看。</p>
      <el-table :data="rows" border size="small" max-height="340" empty-text="当前筛选下没有曲线；未请求的组分不会补算" aria-label="E300组分与原始指标">
        <el-table-column label="原始列" width="80"><template #default="{ row }">{{ row.vectorIndex + 1 }}</template></el-table-column>
        <el-table-column prop="keyword" label="指标" width="110" /><el-table-column prop="object" label="井／网格／对象" min-width="145" />
        <el-table-column prop="component" label="组分" width="100" /><el-table-column prop="unit" label="原生单位" width="115" />
        <el-table-column prop="time" label="最后实际时刻（天）" min-width="155" /><el-table-column prop="last" label="最后实际值" min-width="140" />
      </el-table>
      <EclipseSummaryExplorer :series="plotted" :run-id="run.id" />
      <p>在“结果文件”下载eclipse-compositional-input-package.zip，另一项目上传后直接按E300复算。此卡为等温组分模型；热采与E300参数编辑另行接入。</p>
    </template>
  </section>
</template>
