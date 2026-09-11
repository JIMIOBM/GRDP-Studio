<script setup>
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useSoftwareIntegrationStore } from '@/stores/softwareIntegration'

const store = useSoftwareIntegrationStore()
const { activeModel, activeVersion, activeVersionId, versions } = storeToRefs(store)
const inspection = computed(() => activeVersion.value?.inspection || null)
const inspectionSchemas = new Set(['eclipse-data-inspection/1', 'eclipse-data-inspection/2'])
const sectionOrder = ['RUNSPEC', 'GRID', 'EDIT', 'PROPS', 'REGIONS', 'SOLUTION', 'SUMMARY', 'SCHEDULE']
const phaseOrder = ['OIL', 'WATER', 'GAS']
const unitSystems = new Set(['METRIC', 'FIELD', 'LAB', 'PVT-M'])
const maxWellNames = 1000
const maxScheduleEvents = 1000
const maxDateRecords = 4000
const maxTstepSteps = 8000
const maxLexicalValueLength = 1024

const lexicalValue = value => typeof value === 'string' && value.length > 0 && value.length <= maxLexicalValueLength &&
  !/[\\/:]/.test(value) && !value.includes('..') && !/[\u0000-\u001f\u007f]/.test(value)
  ? value
  : null

const orderedValues = (value, allowed) => Array.isArray(value) && value.every((item, index) =>
  typeof item === 'string' && allowed.indexOf(item) >= 0 && (index === 0 || allowed.indexOf(value[index - 1]) < allowed.indexOf(item)))
  ? value
  : null
const safeDimensions = value => value && typeof value === 'object' && Object.keys(value).length === 3 &&
  ['nx', 'ny', 'nz'].every(key => Number.isInteger(value[key]) && value[key] > 0 && value[key] <= 1000000)
  ? value
  : null
const isSafeFileName = value => typeof value === 'string' && value.length > 0 && value.length <= 255 && !/[\\/]/.test(value)
const safeFileName = value => isSafeFileName(value) ? value : '-'
const validationSummary = computed(() => {
  const code = typeof activeVersion.value?.validationMessage === 'string'
    ? activeVersion.value.validationMessage.match(/\bECLIPSE_[A-Z0-9_]{1,55}\b/)?.[0]
    : null
  return code ? `验证未通过（${code}）` : activeVersion.value?.status === 'READY' ? '-' : '验证未通过或暂不可用'
})

const inspectionDetails = computed(() => {
  const value = inspection.value
  if (!value || typeof value !== 'object' || !inspectionSchemas.has(value.schemaVersion)) return null
  const expectedFields = value.schemaVersion === 'eclipse-data-inspection/2'
    ? ['schemaVersion', 'caseName', 'sections', 'unitSystem', 'phases', 'dimensions', 'wellNames', 'scheduleTimeline']
    : ['schemaVersion', 'caseName', 'sections', 'unitSystem', 'phases', 'dimensions']
  if (Object.keys(value).length !== expectedFields.length || !expectedFields.every(key => Object.hasOwn(value, key)) ||
    !isSafeFileName(value.caseName) || !orderedValues(value.sections, sectionOrder) || !orderedValues(value.phases, phaseOrder) ||
    !(value.unitSystem === null || unitSystems.has(value.unitSystem)) || !(value.dimensions === null || safeDimensions(value.dimensions))) return null
  return {
    schemaVersion: value.schemaVersion,
    sections: value.sections,
    unitSystem: value.unitSystem,
    phases: value.phases,
    dimensions: value.dimensions
  }
})

const inspectionV2 = computed(() => {
  const value = inspection.value
  if (!inspectionDetails.value || value?.schemaVersion !== 'eclipse-data-inspection/2' ||
    !Array.isArray(value.wellNames) || value.wellNames.length > maxWellNames ||
    !Array.isArray(value.scheduleTimeline) || value.scheduleTimeline.length > maxScheduleEvents) return null

  const wellNames = value.wellNames.map(lexicalValue)
  if (wellNames.some(name => !name)) return null
  let dateCount = 0
  let tstepCount = 0
  const schedule = value.scheduleTimeline.flatMap((event, eventIndex) => {
      if (!event || typeof event !== 'object') return []
      if (event.kind === 'DATES' && Array.isArray(event.records)) {
        if (Object.keys(event).length !== 2 || event.records.length + dateCount > maxDateRecords) return []
        const records = event.records.slice(0, maxDateRecords - dateCount).flatMap((record, recordIndex) => {
          if (!record || typeof record !== 'object' || Object.keys(record).length !== 4) return []
          const day = lexicalValue(record.day)
          const month = lexicalValue(record.month)
          const year = lexicalValue(record.year)
          const time = record.time == null ? null : lexicalValue(record.time)
          if (!/^(?:[1-9]|[12][0-9]|3[01])$/.test(day || '') || !/^(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)$/.test(month || '') ||
            !/^\d{4}$/.test(year || '') || (record.time != null && !/^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/.test(time || ''))) return []
          return [{ id: `${eventIndex}-${recordIndex}`, day, month, year, time }]
        })
        if (records.length !== event.records.length) return []
        dateCount += records.length
        return [{ id: eventIndex, kind: 'DATES', records }]
      }
      if (event.kind === 'TSTEP' && Array.isArray(event.steps)) {
        if (Object.keys(event).length !== 2 || event.steps.length + tstepCount > maxTstepSteps) return []
        const steps = event.steps.filter(step => typeof step === 'string' && /^\+?\d+(?:\.\d+)?(?:[Ee][+-]?\d+)?$/.test(step) && Number.isFinite(Number(step)) && Number(step) >= 0)
        if (steps.length !== event.steps.length) return []
        tstepCount += steps.length
        return [{ id: eventIndex, kind: 'TSTEP', steps: steps.map((step, stepIndex) => ({ id: `${eventIndex}-${stepIndex}`, step })) }]
      }
      return []
    })
  if (schedule.length !== value.scheduleTimeline.length) return null

  return { wellNames, schedule }
})
const validInspection = computed(() => inspectionDetails.value && (inspectionDetails.value.schemaVersion === 'eclipse-data-inspection/1' || inspectionV2.value))
const eclipseExecutionAvailable = computed(() => activeVersion.value?.status === 'READY' && activeVersion.value?.modelKind === 'eclipse_100' && Boolean(validInspection.value))
const inspectionUnavailableMessage = computed(() => {
  if (activeVersion.value?.status !== 'READY') return '模型仍在验证或未通过验证；ECLIPSE 检查和执行入口暂不可用。'
  return '此 READY ECLIPSE 版本缺少有效的持久化检查信息；执行入口不可用。'
})

const changeVersion = async versionId => {
  try {
    await store.selectVersion(versionId)
  } catch {
    ElMessage.error('版本加载失败')
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
        <div><dt>文件名</dt><dd>{{ safeFileName(activeVersion?.originalName) }}</dd></div>
        <div><dt>版本</dt><dd>v{{ activeVersion?.versionNo || '-' }}</dd></div>
        <div><dt>SHA-256</dt><dd class="monospace">{{ activeVersion?.sha256 || '-' }}</dd></div>
        <div><dt>大小</dt><dd>{{ activeVersion?.sizeBytes ?? '-' }} bytes</dd></div>
        <div><dt>验证状态</dt><dd>{{ activeVersion?.status || '-' }}</dd></div>
        <div class="wide"><dt>验证消息</dt><dd>{{ validationSummary }}</dd></div>
      </dl>
    </section>

    <section class="overview-section">
      <h2>DATA 检查概览</h2>
      <template v-if="inspectionDetails">
        <dl class="metadata-grid">
          <div><dt>架构版本</dt><dd>{{ inspectionDetails.schemaVersion }}</dd></div>
          <div><dt>单位制</dt><dd>{{ inspectionDetails.unitSystem ?? '未知' }}</dd></div>
          <div><dt>检查状态</dt><dd>有效</dd></div>
          <div class="wide"><dt>段</dt><dd><template v-if="inspectionDetails.sections.length"><el-tag v-for="section in inspectionDetails.sections" :key="section" class="value-tag">{{ section }}</el-tag></template><span>未识别到支持的段</span></dd></div>
          <div class="wide"><dt>相态</dt><dd><template v-if="inspectionDetails.phases.length"><el-tag v-for="phase in inspectionDetails.phases" :key="phase" class="value-tag">{{ phase }}</el-tag></template><span>未识别到支持的相态</span></dd></div>
          <template v-if="inspectionDetails.dimensions">
            <div><dt>NX</dt><dd>{{ inspectionDetails.dimensions.nx }}</dd></div>
            <div><dt>NY</dt><dd>{{ inspectionDetails.dimensions.ny }}</dd></div>
            <div><dt>NZ</dt><dd>{{ inspectionDetails.dimensions.nz }}</dd></div>
          </template>
          <div v-else><dt>网格维度</dt><dd>未知</dd></div>
        </dl>
      </template>
      <p v-else class="inspection-unavailable">{{ inspectionUnavailableMessage }}</p>
    </section>

    <section class="overview-section eclipse-execution-availability">
      <h2>ECLIPSE 执行可用性</h2>
      <div v-if="eclipseExecutionAvailable" class="execution-slot" data-study="null" data-run-type="eclipse">
        <el-tag type="success">可由运行页面执行</el-tag>
        <span>此版本使用固定执行契约：Study 为无，运行类型为 ECLIPSE。</span>
      </div>
      <p v-else class="inspection-unavailable">{{ inspectionUnavailableMessage }}</p>
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
.inspection-unavailable { margin: 0; padding: 16px; color: #909399; font-size: 13px; }.execution-slot { display: flex; align-items: center; gap: 10px; padding: 16px; color: #606266; font-size: 13px; }
@media (max-width: 760px) { .eclipse-inspection-overview { padding: 16px; }.metadata-grid { grid-template-columns: 1fr; }.metadata-grid .wide { grid-column: auto; }.metadata-grid > div { border-bottom: 1px solid #ebeef5; }.metadata-grid > div:last-child { border-bottom: 0; } }
</style>
