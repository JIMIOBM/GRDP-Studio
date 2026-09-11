<script setup>
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useSoftwareIntegrationStore } from '@/stores/softwareIntegration'

const store = useSoftwareIntegrationStore()
const { activeModel, activeVersion, activeVersionId, versions } = storeToRefs(store)
const inspection = computed(() => activeVersion.value?.inspection || null)
const maxWellNames = 1000
const maxScheduleEvents = 1000
const maxDateRecords = 4000
const maxLexicalValueLength = 1024

const lexicalValue = value => typeof value === 'string' && value.length > 0 && value.length <= maxLexicalValueLength
  ? value
  : null

const inspectionV2 = computed(() => {
  const value = inspection.value
  if (value?.schemaVersion !== 'eclipse-data-inspection/2') return null

  const wellNames = Array.isArray(value.wellNames)
    ? value.wellNames.slice(0, maxWellNames).map(lexicalValue).filter(Boolean)
    : []
  let dateCount = 0
  const schedule = Array.isArray(value.scheduleTimeline)
    ? value.scheduleTimeline.slice(0, maxScheduleEvents).flatMap((event, eventIndex) => {
      if (!event || typeof event !== 'object') return []
      if (event.kind === 'DATES' && Array.isArray(event.records)) {
        const records = event.records.slice(0, maxDateRecords - dateCount).flatMap((record, recordIndex) => {
          if (!record || typeof record !== 'object') return []
          const day = lexicalValue(record.day)
          const month = lexicalValue(record.month)
          const year = lexicalValue(record.year)
          const time = record.time == null ? null : lexicalValue(record.time)
          if (!day || !month || !year || (record.time != null && !time)) return []
          return [{ id: `${eventIndex}-${recordIndex}`, day, month, year, time }]
        })
        dateCount += records.length
        return records.length ? [{ id: eventIndex, kind: 'DATES', records }] : []
      }
      if (event.kind === 'TSTEP' && Array.isArray(event.steps)) {
        const steps = event.steps.filter(step => typeof step === 'string' && step.length > 0)
        return steps.length ? [{ id: eventIndex, kind: 'TSTEP', steps: steps.map((step, stepIndex) => ({ id: `${eventIndex}-${stepIndex}`, step })) }] : []
      }
      return []
    })
    : []

  return { wellNames, schedule }
})

const changeVersion = async versionId => {
  try {
    await store.selectVersion(versionId)
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '版本加载失败')
  }
}
</script>

<template>
  <section v-if="activeModel" class="eclipse-inspection-overview">
    <header class="model-header">
      <div>
        <div class="title-line">
          <h1>{{ activeModel.name }}</h1>
          <el-tag :type="activeVersion?.status === 'READY' ? 'success' : 'warning'">{{ activeVersion?.status || '无版本' }}</el-tag>
        </div>
        <p>ECLIPSE 100 .DATA 模型</p>
      </div>
    </header>

    <div class="version-control">
      <span>模型版本</span>
      <el-select :model-value="activeVersionId" @change="changeVersion">
        <el-option v-for="version in versions" :key="version.id" :value="version.id" :label="`v${version.versionNo} · ${version.status}`" />
      </el-select>
    </div>

    <section class="overview-section">
      <h2>文件信息</h2>
      <dl class="metadata-grid">
        <div><dt>文件名</dt><dd>{{ activeVersion?.originalName || '-' }}</dd></div>
        <div><dt>版本</dt><dd>v{{ activeVersion?.versionNo || '-' }}</dd></div>
        <div><dt>SHA-256</dt><dd class="monospace">{{ activeVersion?.sha256 || '-' }}</dd></div>
        <div><dt>大小</dt><dd>{{ activeVersion?.sizeBytes ?? '-' }} bytes</dd></div>
        <div><dt>验证状态</dt><dd>{{ activeVersion?.status || '-' }}</dd></div>
        <div class="wide"><dt>验证消息</dt><dd>{{ activeVersion?.validationMessage || '-' }}</dd></div>
      </dl>
    </section>

    <section v-if="inspection" class="overview-section">
      <h2>DATA 检查概览</h2>
      <dl class="metadata-grid">
        <div><dt>架构版本</dt><dd>{{ inspection.schemaVersion }}</dd></div>
        <div><dt>算例名称</dt><dd>{{ inspection.caseName }}</dd></div>
        <div><dt>单位制</dt><dd>{{ inspection.unitSystem ?? '未知' }}</dd></div>
        <div class="wide"><dt>段</dt><dd><template v-if="inspection.sections?.length"><el-tag v-for="section in inspection.sections" :key="section" class="value-tag">{{ section }}</el-tag></template><span v-else>未识别到支持的段</span></dd></div>
        <div class="wide"><dt>相态</dt><dd><template v-if="inspection.phases?.length"><el-tag v-for="phase in inspection.phases" :key="phase" class="value-tag">{{ phase }}</el-tag></template><span v-else>未识别到支持的相态</span></dd></div>
        <template v-if="inspection.dimensions">
          <div><dt>NX</dt><dd>{{ inspection.dimensions.nx }}</dd></div>
          <div><dt>NY</dt><dd>{{ inspection.dimensions.ny }}</dd></div>
          <div><dt>NZ</dt><dd>{{ inspection.dimensions.nz }}</dd></div>
        </template>
        <div v-else><dt>网格维度</dt><dd>未知</dd></div>
      </dl>
    </section>

    <template v-if="inspectionV2">
      <section class="overview-section" aria-labelledby="eclipse-well-names-title">
        <h2 id="eclipse-well-names-title">井名</h2>
        <div class="inspection-content">
          <div v-if="inspectionV2.wellNames.length" class="well-tags" aria-label="识别到的井名">
            <el-tag v-for="(wellName, index) in inspectionV2.wellNames" :key="`${wellName}-${index}`" class="value-tag">{{ wellName }}</el-tag>
          </div>
          <p v-else class="empty-copy">未识别</p>
        </div>
      </section>

      <section class="overview-section" aria-labelledby="eclipse-schedule-title">
        <h2 id="eclipse-schedule-title">计划记录</h2>
        <div v-if="inspectionV2.schedule.length" class="schedule-sections">
          <section v-for="event in inspectionV2.schedule" :key="event.id" class="schedule-section" :aria-label="event.kind">
            <h3>{{ event.kind }}</h3>
            <el-table v-if="event.kind === 'DATES'" :data="event.records" border size="small" max-height="300">
              <el-table-column type="index" label="#" width="54" align="center" />
              <el-table-column prop="day" label="日" min-width="100" />
              <el-table-column prop="month" label="月" min-width="100" />
              <el-table-column prop="year" label="年" min-width="100" />
              <el-table-column label="时间" min-width="120"><template #default="{ row }">{{ row.time || '-' }}</template></el-table-column>
            </el-table>
            <el-table v-else :data="event.steps" border size="small" max-height="300">
              <el-table-column type="index" label="#" width="54" align="center" />
              <el-table-column prop="step" label="步长" min-width="180" />
            </el-table>
          </section>
        </div>
        <el-empty v-else description="未识别到 DATES 或 TSTEP 计划记录" :image-size="56" />
      </section>
    </template>
  </section>
</template>

<style lang="scss" scoped>
.eclipse-inspection-overview { min-width: 0; min-height: 0; padding: 22px 28px 30px; color: #303133; }
.model-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding-bottom: 16px; border-bottom: 1px solid #e4e7ed; }
.title-line { display: flex; align-items: center; gap: 10px; }
h1 { margin: 0; font-size: 19px; font-weight: 600; }.model-header p { margin: 5px 0 0; color: #909399; font-size: 12px; }
.version-control { display: flex; align-items: center; gap: 12px; padding: 18px 0; }.version-control > span { color: #606266; font-size: 12px; }.version-control .el-select { width: 210px; }
.overview-section { margin-top: 16px; border: 1px solid #e4e7ed; }.overview-section h2 { margin: 0; padding: 12px 16px; border-bottom: 1px solid #e4e7ed; font-size: 14px; font-weight: 600; }
.metadata-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0; margin: 0; }.metadata-grid > div { min-width: 0; padding: 13px 16px; border-bottom: 1px solid #ebeef5; }.metadata-grid > div:nth-last-child(-n + 3) { border-bottom: 0; }.metadata-grid .wide { grid-column: span 3; }
dt { margin-bottom: 5px; color: #909399; font-size: 12px; } dd { min-width: 0; margin: 0; overflow-wrap: anywhere; color: #303133; font-size: 13px; }.monospace { font-family: Consolas, monospace; }.value-tag { margin-right: 6px; }
.inspection-content { padding: 16px; }.well-tags { display: flex; flex-wrap: wrap; gap: 6px; }.well-tags .value-tag { margin: 0; }.empty-copy { margin: 0; color: #909399; font-size: 13px; }.schedule-sections { padding: 16px; }.schedule-section + .schedule-section { margin-top: 20px; }.schedule-section h3 { margin: 0 0 10px; color: #606266; font-size: 13px; font-weight: 600; }
@media (max-width: 760px) { .eclipse-inspection-overview { padding: 16px; }.metadata-grid { grid-template-columns: 1fr; }.metadata-grid .wide { grid-column: auto; }.metadata-grid > div { border-bottom: 1px solid #ebeef5; }.metadata-grid > div:last-child { border-bottom: 0; } }
</style>
