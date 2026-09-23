<script setup>
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useSoftwareIntegrationStore } from '@/stores/softwareIntegration'

const props = defineProps({ embedded: { type: Boolean, default: false } })
const store = useSoftwareIntegrationStore()
const { activeModel, activeVersion, activeVersionId, versions } = storeToRefs(store)
const inspection = computed(() => activeVersion.value?.inspection || null)
const inspectionSchemas = new Set(['eclipse-data-inspection/1', 'eclipse-data-inspection/2', 'eclipse-data-inspection/3', 'eclipse-data-inspection/4'])
const sectionOrder = ['RUNSPEC', 'GRID', 'EDIT', 'PROPS', 'REGIONS', 'SOLUTION', 'SUMMARY', 'SCHEDULE']
const phaseOrder = ['OIL', 'WATER', 'GAS']
const unitSystems = new Set(['METRIC', 'FIELD', 'LAB', 'PVT-M'])
const maxWellNames = 1000
const maxScheduleEvents = 1000
const maxDateRecords = 4000
const maxTstepSteps = 8000
const maxLexicalValueLength = 1024
const maxScheduleMetadataWells = 1000
const maxScheduleMetadataGroups = 1000
const maxScheduleMetadataRecords = 4000
const maxScheduleCompletions = 4000
const maxScheduleMetadataValues = 128
const scheduleRecordKeywords = new Set(['WELSPECS', 'WELSPECL', 'GRUPTREE', 'COMPDAT', 'COMPDATM', 'WELOPEN', 'WCONHIST', 'WCONINJE', 'WCONPROD'])
const scheduleWellFilter = ref('')
const scheduleDateFilter = ref('')

const lexicalValue = value => typeof value === 'string' && value.length > 0 && value.length <= maxLexicalValueLength &&
  !/[\\/]/.test(value) && !value.includes('://') && !value.includes('..') && !/[\u0000-\u001f\u007f]/.test(value)
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
const isSafeRelativePath = value => typeof value === 'string' && value.length > 0 && value.length <= 512 &&
  !value.startsWith('/') && !value.endsWith('/') && !value.includes('\\') && !value.includes(':') &&
  !value.includes('..') && !value.includes('//') && !/[\u0000-\u001f\u007f]/.test(value)
const isSafeLineNumber = value => Number.isSafeInteger(value) && value > 0 && value <= 2000000
const isSha256 = value => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const validationSummary = computed(() => {
  const code = typeof activeVersion.value?.validationMessage === 'string'
    ? activeVersion.value.validationMessage.match(/\bECLIPSE_[A-Z0-9_]{1,55}\b/)?.[0]
    : null
  return code ? `验证未通过（${code}）` : activeVersion.value?.status === 'READY' ? '-' : '验证未通过或暂不可用'
})

const inspectionDetails = computed(() => {
  const value = inspection.value
  if (!value || typeof value !== 'object' || !inspectionSchemas.has(value.schemaVersion)) return null
  const expectedFields = value.schemaVersion === 'eclipse-data-inspection/1'
    ? ['schemaVersion', 'caseName', 'sections', 'unitSystem', 'phases', 'dimensions']
    : value.schemaVersion === 'eclipse-data-inspection/2'
    ? ['schemaVersion', 'caseName', 'sections', 'unitSystem', 'phases', 'dimensions', 'wellNames', 'scheduleTimeline']
    : value.schemaVersion === 'eclipse-data-inspection/3'
    ? ['schemaVersion', 'caseName', 'sections', 'unitSystem', 'phases', 'dimensions', 'wellNames', 'scheduleTimeline', 'packageFiles']
    : ['schemaVersion', 'caseName', 'sections', 'unitSystem', 'phases', 'dimensions', 'wellNames', 'scheduleTimeline', 'packageFiles', 'scheduleMetadata']
  if (Object.keys(value).length !== expectedFields.length || !expectedFields.every(key => Object.hasOwn(value, key)) ||
    !isSafeFileName(value.caseName) || !orderedValues(value.sections, sectionOrder) || !orderedValues(value.phases, phaseOrder) ||
    !(value.unitSystem === null || unitSystems.has(value.unitSystem)) || !(value.dimensions === null || safeDimensions(value.dimensions))) return null
  return {
    schemaVersion: value.schemaVersion,
    sections: value.sections,
    unitSystem: value.unitSystem,
    phases: value.phases,
    dimensions: value.dimensions,
    packageFiles: ['eclipse-data-inspection/3', 'eclipse-data-inspection/4'].includes(value.schemaVersion) ? value.packageFiles : null
  }
})

const inspectionV2 = computed(() => {
  const value = inspection.value
  if (!inspectionDetails.value || !['eclipse-data-inspection/2', 'eclipse-data-inspection/3', 'eclipse-data-inspection/4'].includes(value?.schemaVersion) ||
    !Array.isArray(value.wellNames) || value.wellNames.length > maxWellNames ||
    !Array.isArray(value.scheduleTimeline) || value.scheduleTimeline.length > maxScheduleEvents) return null

  const wellNames = value.wellNames.map(lexicalValue)
  if (wellNames.some(name => !name)) return null
  let dateCount = 0
  let tstepCount = 0
  const withSource = value.schemaVersion === 'eclipse-data-inspection/4'
  const schedule = value.scheduleTimeline.flatMap((event, eventIndex) => {
      if (!event || typeof event !== 'object') return []
      if (event.kind === 'DATES' && Array.isArray(event.records)) {
        if (Object.keys(event).length !== (withSource ? 4 : 2) || event.records.length + dateCount > maxDateRecords ||
          (withSource && (!isSafeRelativePath(event.sourceFile) || !isSafeLineNumber(event.lineNumber)))) return []
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
        return [{ id: eventIndex, kind: 'DATES', records, sourceFile: withSource ? event.sourceFile : null, lineNumber: withSource ? event.lineNumber : null }]
      }
      if (event.kind === 'TSTEP' && Array.isArray(event.steps)) {
        if (Object.keys(event).length !== (withSource ? 4 : 2) || event.steps.length + tstepCount > maxTstepSteps ||
          (withSource && (!isSafeRelativePath(event.sourceFile) || !isSafeLineNumber(event.lineNumber)))) return []
        const steps = event.steps.filter(step => typeof step === 'string' && /^\+?\d+(?:\.\d+)?(?:[Ee][+-]?\d+)?$/.test(step) && Number.isFinite(Number(step)) && Number(step) >= 0)
        if (steps.length !== event.steps.length) return []
        tstepCount += steps.length
        return [{ id: eventIndex, kind: 'TSTEP', steps: steps.map((step, stepIndex) => ({ id: `${eventIndex}-${stepIndex}`, step })), sourceFile: withSource ? event.sourceFile : null, lineNumber: withSource ? event.lineNumber : null }]
      }
      return []
    })
  if (schedule.length !== value.scheduleTimeline.length) return null

  return { wellNames, schedule }
})
const inspectionV3 = computed(() => {
  const value = inspection.value
  if (!inspectionV2.value || !['eclipse-data-inspection/3', 'eclipse-data-inspection/4'].includes(value?.schemaVersion) ||
    !Array.isArray(value.packageFiles) || value.packageFiles.length < 1 || value.packageFiles.length > 4096) return null
  const paths = new Set()
  const files = value.packageFiles.flatMap(file => {
    if (!file || typeof file !== 'object' || Object.keys(file).length !== 3 ||
      !isSafeRelativePath(file.relativePath) || paths.has(file.relativePath) ||
      !Number.isSafeInteger(file.sizeBytes) || file.sizeBytes < 0 || file.sizeBytes > 64 * 1024 * 1024 ||
      !isSha256(file.sha256)) return []
    paths.add(file.relativePath)
    return [{ relativePath: file.relativePath, sizeBytes: file.sizeBytes, sha256: file.sha256 }]
  })
  return files.length === value.packageFiles.length ? files : null
})
const inspectionV4 = computed(() => {
  const value = inspection.value
  if (!inspectionV2.value || value?.schemaVersion !== 'eclipse-data-inspection/4' ||
    !value.scheduleMetadata || typeof value.scheduleMetadata !== 'object' || Object.keys(value.scheduleMetadata).length !== 4) return null
  const metadata = value.scheduleMetadata
  if (!Array.isArray(metadata.wells) || metadata.wells.length > maxScheduleMetadataWells ||
    !Array.isArray(metadata.groups) || metadata.groups.length > maxScheduleMetadataGroups ||
    !Array.isArray(metadata.records) || metadata.records.length > maxScheduleMetadataRecords ||
    !Array.isArray(metadata.completions) || metadata.completions.length > maxScheduleCompletions) return null
  const wells = metadata.wells.flatMap(well => {
    if (!well || typeof well !== 'object' || Object.keys(well).length !== 4 ||
      !isSafeRelativePath(well.sourceFile) || !isSafeLineNumber(well.lineNumber)) return []
    const name = lexicalValue(well.name)
    const group = well.group == null ? null : lexicalValue(well.group)
    return name && (well.group == null || group) ? [{ name, group, sourceFile: well.sourceFile, lineNumber: well.lineNumber }] : []
  })
  const groups = metadata.groups.flatMap(group => {
    if (!group || typeof group !== 'object' || Object.keys(group).length !== 4 ||
      !isSafeRelativePath(group.sourceFile) || !isSafeLineNumber(group.lineNumber)) return []
    const name = lexicalValue(group.name)
    const parent = group.parent == null ? null : lexicalValue(group.parent)
    return name && (group.parent == null || parent) ? [{ name, parent, sourceFile: group.sourceFile, lineNumber: group.lineNumber }] : []
  })
  const records = metadata.records.flatMap(record => {
    if (!record || typeof record !== 'object' || Object.keys(record).length !== 4 ||
      !isSafeRelativePath(record.sourceFile) || !isSafeLineNumber(record.lineNumber) ||
      !scheduleRecordKeywords.has(record.keyword) || !Array.isArray(record.values) ||
      record.values.length === 0 || record.values.length > maxScheduleMetadataValues) return []
    const values = record.values.map(lexicalValue)
    return values.every(Boolean) ? [{ keyword: record.keyword, values, sourceFile: record.sourceFile, lineNumber: record.lineNumber }] : []
  })
  const completions = metadata.completions.flatMap(completion => {
    if (!completion || typeof completion !== 'object' || Object.keys(completion).length !== 9 ||
      !['COMPDAT', 'COMPDATM'].includes(completion.keyword) || !lexicalValue(completion.well) ||
      !['i', 'j', 'k1', 'k2', 'status'].every(field => lexicalValue(completion[field])) ||
      !isSafeRelativePath(completion.sourceFile) || !isSafeLineNumber(completion.lineNumber)) return []
    return [{ keyword: completion.keyword, well: completion.well, i: completion.i, j: completion.j,
      k1: completion.k1, k2: completion.k2, status: completion.status,
      sourceFile: completion.sourceFile, lineNumber: completion.lineNumber }]
  })
  const names = new Set(wells.map(well => well.name))
  const groupNames = new Set(groups.map(group => group.name))
  return wells.length === metadata.wells.length && names.size === wells.length &&
    groups.length === metadata.groups.length && groupNames.size === groups.length &&
    records.length === metadata.records.length && completions.length === metadata.completions.length
    ? { wells, groups, records, completions }
    : null
})
const normalizedFilter = value => String(value || '').trim().toLocaleUpperCase()
const filteredScheduleWells = computed(() => {
  const filter = normalizedFilter(scheduleWellFilter.value)
  return inspectionV4.value?.wells.filter(well => !filter || well.name.toLocaleUpperCase() === filter) || []
})
const filteredScheduleCompletions = computed(() => {
  const filter = normalizedFilter(scheduleWellFilter.value)
  return inspectionV4.value?.completions.filter(completion => !filter || completion.well.toLocaleUpperCase() === filter) || []
})
const filteredScheduleRecords = computed(() => {
  const filter = normalizedFilter(scheduleWellFilter.value)
  return inspectionV4.value?.records.filter(record => !filter || record.values.some(value => value.toLocaleUpperCase() === filter)) || []
})
const filteredSchedule = computed(() => {
  const filter = normalizedFilter(scheduleDateFilter.value)
  if (!inspectionV2.value || !filter) return inspectionV2.value?.schedule || []
  return inspectionV2.value.schedule.flatMap(event => {
    if (event.kind !== 'DATES') return []
    const records = event.records.filter(record => `${record.day} ${record.month} ${record.year}`.toLocaleUpperCase().includes(filter))
    return records.length ? [{ ...event, records }] : []
  })
})
const clearScheduleFilters = () => {
  scheduleWellFilter.value = ''
  scheduleDateFilter.value = ''
}
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
    <header v-if="!props.embedded" class="model-header">
      <div>
        <div class="title-line">
          <h1>{{ activeModel.name }}</h1>
          <el-tag :type="activeVersion?.status === 'READY' ? 'success' : 'warning'">{{ activeVersion?.status || '无版本' }}</el-tag>
        </div>
        <p>ECLIPSE 100 .DATA 模型</p>
      </div>
    </header>

    <div v-if="!props.embedded" class="version-control">
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

    <section v-if="inspectionV3" class="overview-section" aria-labelledby="eclipse-package-files-title">
      <h2 id="eclipse-package-files-title">工程依赖清单</h2>
      <p class="inspection-hint">仅展示包内主 DATA 与已解析的相对 INCLUDE 文件；路径和 SHA-256 来自 Worker 验证，不暴露本机绝对路径。</p>
      <el-table :data="inspectionV3" border size="small" max-height="360">
        <el-table-column type="index" label="#" width="54" align="center" />
        <el-table-column prop="relativePath" label="包内相对路径" min-width="230" show-overflow-tooltip />
        <el-table-column label="大小" width="120" align="right"><template #default="{ row }">{{ row.sizeBytes.toLocaleString() }} bytes</template></el-table-column>
        <el-table-column prop="sha256" label="SHA-256" min-width="360" show-overflow-tooltip><template #default="{ row }"><span class="monospace">{{ row.sha256 }}</span></template></el-table-column>
      </el-table>
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
        <div class="schedule-filter-bar" aria-label="Schedule 筛选">
          <el-select v-model="scheduleWellFilter" clearable placeholder="按井筛选" size="small" class="schedule-filter-control">
            <el-option v-for="wellName in inspectionV2.wellNames" :key="wellName" :value="wellName" :label="wellName" />
          </el-select>
          <el-input v-model="scheduleDateFilter" clearable placeholder="按日期筛选，例如 1970 JAN" size="small" class="schedule-filter-control" />
          <el-button size="small" @click="clearScheduleFilters">清除筛选</el-button>
          <span class="schedule-filter-hint">来源列可定位到包内文件和行号</span>
        </div>
        <div v-if="filteredSchedule.length" class="schedule-sections">
          <section v-for="event in filteredSchedule" :key="event.id" class="schedule-section" :aria-label="event.kind">
            <h3>{{ event.kind }}</h3>
            <el-table v-if="event.kind === 'DATES'" :data="event.records" border size="small" max-height="300">
              <el-table-column type="index" label="#" width="54" align="center" />
              <el-table-column prop="day" label="日" min-width="100" />
              <el-table-column prop="month" label="月" min-width="100" />
              <el-table-column prop="year" label="年" min-width="100" />
              <el-table-column label="时间" min-width="120"><template #default="{ row }">{{ row.time || '-' }}</template></el-table-column>
              <el-table-column v-if="event.sourceFile" label="来源" min-width="180"><template #default="{ row: _row }">{{ event.sourceFile }}:{{ event.lineNumber }}</template></el-table-column>
            </el-table>
            <el-table v-else :data="event.steps" border size="small" max-height="300">
              <el-table-column type="index" label="#" width="54" align="center" />
              <el-table-column prop="step" label="步长" min-width="180" />
              <el-table-column v-if="event.sourceFile" label="来源" min-width="180"><template #default="{ row: _row }">{{ event.sourceFile }}:{{ event.lineNumber }}</template></el-table-column>
            </el-table>
          </section>
        </div>
        <el-empty v-else :description="scheduleDateFilter ? '没有匹配的日期记录' : '未识别到 DATES 或 TSTEP 计划记录'" :image-size="56" />
      </section>
    </template>

    <section v-if="inspectionV4" class="overview-section" aria-labelledby="eclipse-schedule-metadata-title">
      <h2 id="eclipse-schedule-metadata-title">井组关系与调度关键记录</h2>
      <p class="inspection-hint">仅展示 Worker 从官方 Schedule 关键字中提取的显示安全元数据；数值保持原始词法，不推断单位，也不替代 ECLIPSE 计算结果。</p>
      <div class="schedule-metadata-grid">
        <section class="schedule-metadata-card" aria-label="井组关系">
          <h3>井与组</h3>
          <el-table :data="filteredScheduleWells" border size="small" max-height="280">
            <el-table-column type="index" label="#" width="54" align="center" />
            <el-table-column prop="name" label="井名" min-width="160" />
            <el-table-column label="所属组" min-width="160"><template #default="{ row }">{{ row.group || '未指定' }}</template></el-table-column>
            <el-table-column label="来源" min-width="180"><template #default="{ row }">{{ row.sourceFile }}:{{ row.lineNumber }}</template></el-table-column>
          </el-table>
        </section>
        <section class="schedule-metadata-card" aria-label="组树">
          <h3>组树</h3>
          <el-table :data="inspectionV4.groups" border size="small" max-height="280">
            <el-table-column type="index" label="#" width="54" align="center" />
            <el-table-column prop="name" label="组" min-width="160" />
            <el-table-column label="父组" min-width="160"><template #default="{ row }">{{ row.parent || '未指定' }}</template></el-table-column>
            <el-table-column label="来源" min-width="180"><template #default="{ row }">{{ row.sourceFile }}:{{ row.lineNumber }}</template></el-table-column>
          </el-table>
        </section>
      </div>
      <section class="schedule-metadata-card schedule-records-card" aria-label="完井记录">
        <h3>完井记录（COMPDAT/COMPDATM）</h3>
        <el-table :data="filteredScheduleCompletions" border size="small" max-height="360">
          <el-table-column type="index" label="#" width="54" align="center" />
          <el-table-column prop="keyword" label="关键字" width="120" />
          <el-table-column prop="well" label="井名" min-width="140" />
          <el-table-column prop="i" label="I" width="70" />
          <el-table-column prop="j" label="J" width="70" />
          <el-table-column prop="k1" label="K1" width="70" />
          <el-table-column prop="k2" label="K2" width="70" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column label="来源" min-width="180"><template #default="{ row }">{{ row.sourceFile }}:{{ row.lineNumber }}</template></el-table-column>
        </el-table>
      </section>
      <section class="schedule-metadata-card schedule-records-card" aria-label="调度关键字记录">
        <h3>关键字记录（原始词法）</h3>
        <el-table :data="filteredScheduleRecords" border size="small" max-height="360">
          <el-table-column type="index" label="#" width="54" align="center" />
          <el-table-column prop="keyword" label="关键字" width="120" />
          <el-table-column label="记录值" min-width="360" show-overflow-tooltip><template #default="{ row }"><span class="monospace">{{ row.values.join('  ') }}</span></template></el-table-column>
          <el-table-column label="来源" min-width="180"><template #default="{ row }">{{ row.sourceFile }}:{{ row.lineNumber }}</template></el-table-column>
        </el-table>
      </section>
    </section>
  </section>
</template>

<style lang="scss" scoped>
.eclipse-inspection-overview { min-width: 0; min-height: 0; padding: 0; color: #303133; }
.model-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding-bottom: 16px; border-bottom: 1px solid #e4e7ed; }
.title-line { display: flex; align-items: center; gap: 10px; }
h1 { margin: 0; font-size: 19px; font-weight: 600; }.model-header p { margin: 5px 0 0; color: #909399; font-size: 12px; }
.version-control { display: flex; align-items: center; gap: 12px; padding: 18px 0; }.version-control > span { color: #606266; font-size: 12px; }.version-control .el-select { width: 210px; }
.overview-section { margin-top: 8px; border-bottom: 1px solid #e4e7ed; }.overview-section h2 { margin: 0; padding: 7px 10px; border-top: 1px solid #e4e7ed; border-bottom: 1px solid #e4e7ed; background: #f5f5f2; font-size: 13px; font-weight: 600; }
.metadata-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0; margin: 0; }.metadata-grid > div { min-width: 0; padding: 9px 10px; border-bottom: 1px solid #f0f1f2; }.metadata-grid > div:nth-last-child(-n + 3) { border-bottom: 0; }.metadata-grid .wide { grid-column: span 3; }
dt { margin-bottom: 5px; color: #909399; font-size: 12px; } dd { min-width: 0; margin: 0; overflow-wrap: anywhere; color: #303133; font-size: 13px; }.monospace { font-family: Consolas, monospace; }.value-tag { margin-right: 6px; }
.inspection-content { padding: 10px; }.well-tags { display: flex; flex-wrap: wrap; gap: 6px; }.well-tags .value-tag { margin: 0; }.empty-copy { margin: 0; color: #909399; font-size: 13px; }.schedule-sections { padding: 10px; }.schedule-section + .schedule-section { margin-top: 12px; }.schedule-section h3 { margin: 0 0 7px; color: #606266; font-size: 13px; font-weight: 600; }.inspection-hint { margin: 0; padding: 9px 10px 0; color: #737a84; font-size: 12px; }
.schedule-filter-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 10px 10px 0; }.schedule-filter-control { width: 190px; }.schedule-filter-hint { color: #909399; font-size: 12px; }
.schedule-metadata-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; padding: 10px; }.schedule-metadata-card { min-width: 0; }.schedule-metadata-card h3 { margin: 0 0 7px; color: #606266; font-size: 13px; font-weight: 600; }.schedule-records-card { padding: 0 10px 10px; }
.inspection-unavailable { margin: 0; padding: 10px; color: #909399; font-size: 13px; }.execution-slot { display: flex; align-items: center; gap: 10px; padding: 9px 10px; color: #606266; font-size: 13px; }
@media (max-width: 760px) { .eclipse-inspection-overview { padding: 16px; }.metadata-grid { grid-template-columns: 1fr; }.metadata-grid .wide { grid-column: auto; }.metadata-grid > div { border-bottom: 1px solid #ebeef5; }.metadata-grid > div:last-child { border-bottom: 0; }.schedule-metadata-grid { grid-template-columns: 1fr; } }
</style>
