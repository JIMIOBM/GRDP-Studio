<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import * as XLSX from 'xlsx'
import { softwareIntegrationApi as api } from '@/api/softwareIntegration'
import EclipsePreprocessingGrid from './EclipsePreprocessingGrid.vue'
import EclipsePreprocessingCharts from './EclipsePreprocessingCharts.vue'
import EclipsePreprocessingComparison from './EclipsePreprocessingComparison.vue'
import EclipsePreprocessingSpatialResult from './EclipsePreprocessingSpatialResult.vue'
import {
  comparePreprocessingRuns,
  preprocessingRows,
  preprocessingTerminal,
  preprocessingTypes,
  unwrapPreprocessing as unwrap,
} from './eclipsePreprocessing.js'

const props = defineProps({ projectId: { type: Number, required: true } })
const emit = defineEmits(['close', 'registered', 'open-result'])
const type = ref('e100'),
  template = ref(null),
  rows = ref([]),
  sourceId = ref(null),
  modelName = ref('')
const confirmed = ref(false),
  validation = ref(null),
  error = ref(''),
  loading = ref(false),
  submitting = ref(false)
const validating = ref(false),
  importedName = ref(''),
  activeSheet = ref('注采计划'),
  search = ref(''),
  page = ref(1)
const inputFile = ref(null),
  headers = ref([]),
  mappingOpen = ref(false),
  pendingFiles = ref([])
const mapping = ref({ id: '', value: '', unit: '' }),
  builds = ref([]),
  job = ref(null)
const baselineId = ref(''),
  comparison = ref(null),
  comparing = ref(false),
  comparedIds = ref(null)
const revision = ref(0),
  builtRevision = ref(-1),
  requestId = ref(crypto.randomUUID()),
  fixedOpen = ref(false)
const spatialBuild = ref(null)
let generation = 0,
  pollGeneration = 0,
  timer,
  alive = true

const sheets = computed(() => [
  ...new Set(rows.value.map((row) => row.definition?.sheet ?? '未识别数据')),
])
const filtered = computed(() =>
  rows.value.filter(
    (row) =>
      (row.definition?.sheet ?? '未识别数据') === activeSheet.value &&
      `${row.id} ${row.definition?.field} ${row.definition?.objectName}`
        .toLowerCase()
        .includes(search.value.toLowerCase()),
  ),
)
const pageRows = computed(() => filtered.value.slice((page.value - 1) * 16, page.value * 16))
const history = computed(() => builds.value.filter((build) => build.templateId === type.value))
const comparisonChoices = computed(() =>
  history.value.filter(
    (build) =>
      build.status === 'SUCCEEDED' && build.runId && build.requestId !== job.value?.requestId,
  ),
)
const step = computed(() =>
  job.value && builtRevision.value === revision.value
    ? 4
    : validation.value?.valid
      ? 3
      : rows.value.length
        ? 2
        : 1,
)
const message = (failure) =>
  typeof failure?.msg === 'string'
    ? failure.msg
    : typeof failure?.message === 'string'
      ? failure.message
      : '请求暂未完成，请稍后重试'

function payload() {
  return {
    templateId: type.value,
    templateVersion: template.value.version,
    modelName: modelName.value,
    sourceId: sourceId.value,
    requestId: requestId.value,
    confirmedDefaults: confirmed.value,
    calculate: true,
    values: rows.value.map(({ id, value, unit, sheet, row }) => ({
      id,
      value: String(value),
      unit,
      sheet,
      row,
    })),
  }
}

function dirty() {
  revision.value++
  requestId.value = crypto.randomUUID()
  validation.value = null
  comparison.value = null
  spatialBuild.value = null
  error.value = ''
}

function applyImported(data, name) {
  sourceId.value = data.sourceId
  rows.value = preprocessingRows(template.value, data.values)
  importedName.value = name
  confirmed.value = false
  activeSheet.value = sheets.value.includes('注采计划') ? '注采计划' : sheets.value[0]
  page.value = 1
  search.value = ''
  dirty()
}

async function loadTemplate() {
  const token = ++generation
  pollGeneration++
  clearTimeout(timer)
  template.value = null
  rows.value = []
  builds.value = []
  sourceId.value = null
  job.value = null
  spatialBuild.value = null
  comparison.value = null
  confirmed.value = false
  loading.value = true
  error.value = ''
  validation.value = null
  importedName.value = ''
  try {
    const [definition, records] = await Promise.allSettled([
      api.getEclipsePreprocessingTemplate(type.value),
      api.listEclipsePreprocessingBuilds(props.projectId),
    ])
    if (!alive || token !== generation) return
    if (definition.status === 'rejected') throw definition.reason
    template.value = unwrap(definition.value)
    if (records.status === 'fulfilled') builds.value = unwrap(records.value)
    else error.value = `历史记录加载失败：${message(records.reason)}。仍可导入表格并创建新方案。`
    modelName.value = `表格_${template.value.caseName}`
    const pending = history.value.find((record) => !preprocessingTerminal.has(record.status))
    if (pending) {
      job.value = pending
      poll(pending.requestId)
    }
  } catch (failure) {
    if (alive && token === generation) error.value = message(failure)
  } finally {
    if (alive && token === generation) loading.value = false
  }
}
watch(type, loadTemplate, { immediate: true })
watch([activeSheet, search], () => {
  page.value = 1
})

async function loadExample() {
  const token = ++generation
  loading.value = true
  error.value = ''
  try {
    const data = unwrap(await api.loadEclipsePreprocessingExample(props.projectId, type.value))
    if (alive && token === generation)
      applyImported(data, `${template.value.caseName} 官方算例转换填写示例`)
  } catch (failure) {
    if (alive && token === generation) error.value = message(failure)
  } finally {
    if (alive && token === generation) loading.value = false
  }
}

async function chooseFiles(event) {
  const files = [...event.target.files]
  event.target.value = ''
  if (!files.length) return
  const token = ++generation
  loading.value = true
  error.value = ''
  try {
    if (files.length > 16 || files.reduce((sum, file) => sum + file.size, 0) > 8 * 1024 * 1024)
      throw new Error('最多16份文件、合计8MB。')
    const first = files[0],
      bytes = await first.arrayBuffer()
    const workbook = first.name.toLowerCase().endsWith('.csv')
      ? XLSX.read(new TextDecoder('utf-8', { fatal: true }).decode(bytes), {
          type: 'string',
          raw: true,
          sheetRows: 1,
        })
      : XLSX.read(bytes, { type: 'array', sheetRows: 1 })
    const sheetName = workbook.SheetNames.find((name) => !['说明', '固定选项'].includes(name))
    if (!sheetName) throw new Error('没有可导入的数据工作表。')
    const firstRow = XLSX.utils.sheet_to_json(workbook.Sheets[sheetName], {
      header: 1,
      raw: false,
    })[0]
    if (!alive || token !== generation) return
    headers.value = (firstRow ?? []).map((value) =>
      String(value)
        .replace(/^\uFEFF/, '')
        .trim(),
    )
    mapping.value = {
      id: headers.value.find((value) => ['参数编号', 'id', 'parameter_id'].includes(value)) ?? '',
      value: headers.value.find((value) => ['数值', 'value', '数值输入'].includes(value)) ?? '',
      unit: headers.value.find((value) => ['单位', 'unit'].includes(value)) ?? '',
    }
    pendingFiles.value = files
    mappingOpen.value = true
  } catch (failure) {
    if (alive && token === generation) error.value = message(failure)
  } finally {
    if (alive && token === generation) loading.value = false
  }
}

async function importFiles() {
  const token = ++generation
  loading.value = true
  error.value = ''
  try {
    const data = unwrap(
      await api.importEclipsePreprocessing(
        props.projectId,
        type.value,
        pendingFiles.value,
        mapping.value,
      ),
    )
    if (!alive || token !== generation) return
    applyImported(data, pendingFiles.value.map((file) => file.name).join('、'))
    mappingOpen.value = false
  } catch (failure) {
    if (alive && token === generation) error.value = message(failure)
  } finally {
    if (alive && token === generation) loading.value = false
  }
}

async function validate() {
  const current = revision.value,
    token = generation
  validating.value = true
  error.value = ''
  validation.value = null
  try {
    const result = unwrap(await api.validateEclipsePreprocessing(props.projectId, payload()))
    if (alive && token === generation && current === revision.value) validation.value = result
  } catch (failure) {
    if (alive && token === generation && current === revision.value) error.value = message(failure)
  } finally {
    if (alive && token === generation) validating.value = false
  }
}

async function build() {
  if (loading.value || submitting.value || !validation.value?.valid) return
  submitting.value = true
  error.value = ''
  const current = revision.value,
    token = generation
  try {
    const result = unwrap(await api.buildEclipsePreprocessing(props.projectId, payload()))
    if (!alive || token !== generation) return
    job.value = result
    builtRevision.value = current
    emit('registered', { projectId: props.projectId })
    poll(result.requestId)
  } catch (failure) {
    if (alive && token === generation) {
      error.value = `${message(failure)}。同一请求可安全重试，不会重复建模或计算。`
      if (failure?.data?.issues) validation.value = failure.data
    }
  } finally {
    if (alive && token === generation) submitting.value = false
  }
}

function poll(id) {
  const token = ++pollGeneration
  clearTimeout(timer)
  const update = async () => {
    try {
      const result = unwrap(await api.getEclipsePreprocessingBuild(props.projectId, id))
      if (!alive || token !== pollGeneration) return
      job.value = result
      builds.value = [result, ...builds.value.filter((build) => build.requestId !== id)]
      if (error.value.startsWith('计算状态读取失败：')) error.value = ''
      if (preprocessingTerminal.has(result.status)) return
    } catch (failure) {
      if (!alive || token !== pollGeneration) return
      error.value = `计算状态读取失败：${message(failure)}。正在保留原请求并重试。`
    }
    if (alive && token === pollGeneration) timer = setTimeout(update, 2000)
  }
  update()
}

async function resume(record) {
  const token = ++generation
  loading.value = true
  error.value = ''
  try {
    const input = unwrap(await api.getEclipsePreprocessingInputs(props.projectId, record.requestId))
    if (!alive || token !== generation) return
    if (input.templateId !== template.value.id || input.templateVersion !== template.value.version)
      throw new Error('该记录使用旧模板，请下载原工程留档，使用当前模板重新导入后计算。')
    applyImported(
      { sourceId: input.sourceId, values: input.values },
      `生成记录 ${record.requestId.slice(0, 8)} 的工程参数`,
    )
    modelName.value = input.modelName
    confirmed.value = input.confirmedDefaults
    job.value = record
    baselineId.value = record.requestId
    builtRevision.value = -1
  } catch (failure) {
    if (alive && token === generation) error.value = message(failure)
  } finally {
    if (alive && token === generation) loading.value = false
  }
}

async function compare() {
  const baseline = history.value.find((build) => build.requestId === baselineId.value)
  if (!baseline?.runId || !job.value?.runId) return
  const token = generation,
    currentId = job.value.requestId
  comparing.value = true
  comparison.value = null
  error.value = ''
  try {
    const [current, original, currentInput, originalInput] = await Promise.all([
      api.getRun(job.value.runId),
      api.getRun(baseline.runId),
      api.getEclipsePreprocessingInputs(props.projectId, currentId),
      api.getEclipsePreprocessingInputs(props.projectId, baseline.requestId),
    ])
    if (
      !alive ||
      token !== generation ||
      currentId !== job.value?.requestId ||
      baseline.requestId !== baselineId.value
    )
      return
    const result = comparePreprocessingRuns(
      unwrap(current),
      unwrap(original),
      unwrap(currentInput),
      unwrap(originalInput),
    )
    if (result.message) error.value = result.message
    else {
      comparison.value = result
      comparedIds.value = {
        current: job.value.runId,
        baseline: baseline.runId,
      }
    }
  } catch (failure) {
    if (alive && token === generation) error.value = message(failure)
  } finally {
    if (alive && token === generation) comparing.value = false
  }
}

function save(bytes, name) {
  const url = URL.createObjectURL(bytes),
    link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
async function downloadTemplate(format, blank) {
  try {
    save(
      await api.downloadEclipsePreprocessingTemplate(type.value, format, blank),
      `${template.value.caseName}_${blank ? '空白模板' : '填写示例'}.${format}`,
    )
  } catch (failure) {
    error.value = message(failure)
  }
}
async function downloadBuild(record) {
  try {
    save(
      await api.downloadEclipsePreprocessingBuild(props.projectId, record.requestId),
      `${template.value.caseName}_工程.zip`,
    )
  } catch (failure) {
    error.value = message(failure)
  }
}
function exportCurrent() {
  const quote = (value) => '"' + String(value ?? '').replaceAll('"', '""') + '"'
  const lines = [
    ['参数编号', '数据表', '对象或范围', '字段', '数值', '单位', '来源'],
    ...rows.value.map((row) => [
      row.id,
      row.definition?.sheet,
      row.definition?.objectName,
      row.definition?.field,
      row.value,
      row.unit,
      `${row.sheet}:${row.row}`,
    ]),
  ]
  save(
    new Blob(['\uFEFF' + lines.map((line) => line.map(quote).join(',')).join('\r\n')], {
      type: 'text/csv;charset=UTF-8',
    }),
    `${template.value.caseName}_当前参数.csv`,
  )
}
function focusIssue(issue) {
  const row = rows.value.find((value) => value.id === issue.id)
  if (row) {
    activeSheet.value = row.definition?.sheet ?? '未识别数据'
    search.value = issue.id
  }
}
onBeforeUnmount(() => {
  alive = false
  generation++
  pollGeneration++
  clearTimeout(timer)
})
</script>

<template>
  <section class="preprocessing-workbench" aria-label="ECLIPSE Excel CSV前处理">
    <header class="preprocessing-header">
      <div>
        <h1>ECLIPSE 表格前处理</h1>
        <span>Excel / CSV 数据录入、模型预览与计算</span>
      </div>
      <el-button @click="emit('close')">返回统一控制台</el-button>
    </header>
    <nav class="flow" aria-label="表格建模流程">
      <div
        v-for="(label, index) in ['选择类型', '导入数据', '修正与预览', '生成并计算']"
        :key="label"
        :class="{ active: step >= index + 1 }"
      >
        <i>{{ index + 1 }}</i
        ><span>{{ label }}</span>
      </div>
    </nav>
    <div class="type-cards" role="group" aria-label="模型类型">
      <button
        v-for="item in preprocessingTypes"
        :key="item.id"
        :disabled="loading || submitting"
        :class="{ selected: type === item.id }"
        :aria-pressed="type === item.id"
        :title="item.detail"
        type="button"
        @click="type = item.id"
      >
        <strong>{{ item.title }}</strong
        ><small>{{ item.caseName }}</small>
      </button>
    </div>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      class="feedback"
    />
    <div v-loading="loading" class="workbench-body">
      <template v-if="template">
        <section class="import-card">
          <div class="section-title">
            <div>
              <h2>表格导入</h2>
            </div>
            <el-tag effect="plain">{{ template.classification }}</el-tag>
          </div>
          <p class="scope">{{ template.scope }}</p>
          <div class="download-row">
            <strong>填写示例</strong
            ><el-button @click="downloadTemplate('xlsx', false)">下载教学 Excel</el-button
            ><el-button @click="downloadTemplate('csv', false)">下载对应 CSV</el-button
            ><el-button link type="primary" @click="downloadTemplate('xlsx', true)"
              >空白 Excel</el-button
            ><el-button link type="primary" @click="downloadTemplate('csv', true)"
              >空白 CSV</el-button
            >
          </div>
          <div class="import-actions">
            <input
              ref="inputFile"
              class="hidden"
              type="file"
              multiple
              accept=".xlsx,.csv"
              @change="chooseFiles"
            /><el-button type="primary" :disabled="submitting" @click="inputFile.click()"
              >导入 Excel / CSV</el-button
            ><el-button :disabled="submitting" @click="loadExample">载入填写示例</el-button
            ><span>{{ importedName || '一个多工作表.xlsx，或UTF-8 CSV；合计不超过8MB。' }}</span>
          </div>
          <details class="source-details">
            <summary>来源、固定工程选项与适用范围</summary>
            <p>
              ECLIPSE 2024.1 / {{ template.sourceLocation }}<br />模板 {{ template.version }} ·
              FIELD 单位 · 源 SHA-256：{{ template.sourceSha256 }}
            </p>
            <p>
              模板维数与井组拓扑固定。原始井组、相态选项、数值设置及源文件默认语义可在下方查看。导入参数不会被自动补零或归一化。
            </p>
            <p>生成时的输出整理：{{ template.outputPolicy }}</p>
            <el-button @click="fixedOpen = true">查看完整参考工程与字段来源</el-button>
          </details>
        </section>

        <section v-if="rows.length" class="editor-card">
          <div class="section-title">
            <div>
              <h2>参数数据</h2>
            </div>
            <el-button :disabled="loading || !validation?.valid" @click="exportCurrent"
              >导出当前 CSV</el-button
            >
          </div>
          <div class="model-name">
            <label for="preprocessing-model-name">模型名称</label
            ><el-input
              id="preprocessing-model-name"
              v-model="modelName"
              maxlength="80"
              :disabled="loading || submitting"
              @input="dirty"
            />
          </div>
          <div class="sheet-tabs">
            <button
              v-for="sheet in sheets"
              :key="sheet"
              type="button"
              :class="{ active: activeSheet === sheet }"
              @click="activeSheet = sheet"
            >
              {{ sheet }}
              <small>{{
                rows.filter((row) => (row.definition?.sheet ?? '未识别数据') === sheet).length
              }}</small>
            </button>
          </div>
          <el-input
            v-model="search"
            placeholder="搜索井、参数、组分或网格位置"
            clearable
            aria-label="搜索前处理参数"
            class="table-search"
          />
          <el-table
            :data="pageRows"
            :row-key="(row) => row.id + '|' + row.sheet + '|' + row.row"
            max-height="500"
            class="parameters-table"
            ><el-table-column label="对象 / 范围" min-width="210"
              ><template #default="{ row }"
                >{{ row.definition?.objectName || row.id }}<small>{{ row.id }}</small></template
              ></el-table-column
            ><el-table-column label="参数" min-width="160"
              ><template #default="{ row }">{{
                row.definition?.field || '未知参数'
              }}</template></el-table-column
            ><el-table-column label="数值" min-width="180"
              ><template #default="{ row }"
                ><el-input
                  v-model="row.value"
                  :aria-label="`表格参数 ${row.id}`"
                  :disabled="loading || submitting"
                  @input="dirty" /></template></el-table-column
            ><el-table-column label="单位" min-width="160"
              ><template #default="{ row }"
                ><el-input
                  v-model="row.unit"
                  :aria-label="`表格单位 ${row.id}`"
                  :disabled="loading || submitting"
                  @input="dirty"
                /><small>要求：{{ row.definition?.unit }}</small></template
              ></el-table-column
            ><el-table-column label="教学原值" min-width="110"
              ><template #default="{ row }">{{
                row.definition?.baseValue
              }}</template></el-table-column
            ><el-table-column label="导入位置" min-width="150"
              ><template #default="{ row }"
                >{{ row.sheet }} · 第{{ row.row }}行</template
              ></el-table-column
            ></el-table
          >
          <el-pagination
            v-model:current-page="page"
            :page-size="16"
            :total="filtered.length"
            layout="total, prev, pager, next"
          />
          <el-checkbox
            v-model="confirmed"
            :disabled="loading || submitting"
            class="defaults-confirmation"
            @change="dirty"
            >我已查看并确认本次沿用的教学模板单位、固定工程选项和适用范围。</el-checkbox
          >
          <div class="validation-actions">
            <el-button
              type="primary"
              :loading="validating"
              :disabled="loading || !confirmed || submitting"
              @click="validate"
              >检查数据并预览</el-button
            ><span v-if="validation?.valid" class="valid-label">检查通过 · 可生成新模型</span
            ><span v-else>修改后须重新检查，缺失工程参数不会自动填充。</span>
          </div>
          <div v-if="validation && !validation.valid" class="error-list" role="alert">
            <strong>{{ validation.issues.length }} 项输入错误，尚未生成模型或提交计算</strong
            ><el-table :data="validation.issues.slice(0, 100)" max-height="300"
              ><el-table-column prop="sheet" label="工作表" width="150" /><el-table-column
                prop="row"
                label="行"
                width="70"
              /><el-table-column prop="field" label="字段" min-width="150" /><el-table-column
                prop="message"
                label="问题"
                min-width="280"
              /><el-table-column width="75"
                ><template #default="{ row }"
                  ><el-button link type="primary" @click="focusIssue(row)"
                    >定位</el-button
                  ></template
                ></el-table-column
              ></el-table
            ><small v-if="validation.issues.length > 100">先显示前100项，修正后再次检查。</small>
          </div>
        </section>

        <section v-if="validation?.valid" class="preview-section">
          <div class="section-title">
            <div>
              <h2>模型输入预览</h2>
            </div>
            <el-tag type="info">输入预览，尚未求解</el-tag>
          </div>
          <EclipsePreprocessingGrid :preview="validation.preview" /><EclipsePreprocessingCharts
            :key="template.id"
            :template="template"
            :values="rows"
          />
          <div class="build-card">
            <div>
              <strong>生成完整工程，并提交真实计算</strong>
              <p>保存原表格、模板版本、源文件校验值与本次参数。计算结果进入统一控制台。</p>
            </div>
            <el-button type="primary" :loading="submitting" :disabled="loading" @click="build"
              >生成并计算</el-button
            >
          </div>
        </section>

        <section v-if="job" class="job-card">
          <div class="section-title">
            <div>
              <h2>
                {{ job.status === 'SUCCEEDED' ? '计算完成' : '生成与计算记录' }}
              </h2>
            </div>
            <el-tag :type="job.status === 'SUCCEEDED' ? 'success' : 'info'">{{
              job.status === 'UNAVAILABLE' ? '模型或运行已不可用' : job.status
            }}</el-tag>
          </div>
          <p>
            模型版本 #{{ job.versionId }} <span v-if="job.runId"> · 运行 #{{ job.runId }}</span> ·
            {{ job.message || '状态来自原生计算运行记录' }}
          </p>
          <p v-if="builtRevision !== revision" class="stale-note">
            当前输入与这条生成记录尚未绑定；此处显示上次工程的实际状态。
          </p>
          <div class="job-actions">
            <el-button @click="downloadBuild(job)">下载生成工程与来源</el-button
            ><el-button v-if="job.runId && job.status !== 'UNAVAILABLE'" type="primary" @click="emit('open-result', job)"
              >查看完整计算结果</el-button
            ><el-button v-if="job.status === 'SUCCEEDED'" @click="spatialBuild = { ...job }"
              >查看三维计算结果</el-button
            ><el-button v-if="!job.runId" @click="poll(job.requestId)">刷新状态</el-button>
          </div>
          <p class="hash">DATA SHA-256：{{ job.deckSha256 }}</p>
          <div
            v-if="job.status === 'SUCCEEDED' && comparisonChoices.length"
            class="comparison-actions"
          >
            <el-select
              popper-class="preprocessing-select-menu"
              v-model="baselineId"
              placeholder="选择修改前的成功计算"
              aria-label="前处理对比基线"
              style="width: 320px"
              ><el-option
                v-for="record in comparisonChoices"
                :key="record.requestId"
                :value="record.requestId"
                :label="`运行 #${record.runId} · 模型版本 #${record.versionId}`" /></el-select
            ><el-button :loading="comparing" :disabled="!baselineId" @click="compare"
              >对比两次真实计算</el-button
            >
          </div>
        </section>
        <EclipsePreprocessingComparison
          v-if="comparison"
          :comparison="comparison"
          :current-run-id="comparedIds.current"
          :baseline-run-id="comparedIds.baseline"
        />
        <EclipsePreprocessingSpatialResult
          v-if="spatialBuild"
          :key="spatialBuild.runId"
          :build="spatialBuild"
        />

        <section v-if="history.length" class="history-card">
          <div class="section-title">
            <div>
              <h2>已生成模型与计算记录</h2>
            </div>
          </div>
          <el-table :data="history" row-key="requestId"
            ><el-table-column prop="versionId" label="模型版本" width="120" /><el-table-column
              prop="runId"
              label="运行"
              width="100"
            /><el-table-column label="状态" min-width="120"><template #default="{ row }">
              <span :title="row.message">{{ row.status === 'UNAVAILABLE' ? '模型或运行已不可用' : row.status }}</span>
            </template></el-table-column><el-table-column
              label="操作"
              min-width="280"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="resume(row)">基于此版本继续编辑</el-button
                ><el-button v-if="row.runId && row.status !== 'UNAVAILABLE'" link type="primary" @click="emit('open-result', row)"
                  >查看结果</el-button
                ><el-button
                  v-if="row.status === 'SUCCEEDED'"
                  link
                  type="primary"
                  @click="spatialBuild = { ...row }"
                  >三维场值</el-button
                ><el-button link @click="downloadBuild(row)">下载工程</el-button></template
              ></el-table-column
            ></el-table
          >
        </section>
      </template>
    </div>
    <el-dialog
      v-model="mappingOpen"
      title="确认表格列名对应"
      width="580px"
      :close-on-click-modal="false"
      ><p>参数编号用于对应模板工程，数值和单位必须明确。表头选择适用于本次导入文件。</p>
      <el-form label-width="110px"
        ><el-form-item
          v-for="(label, key) in {
            id: '参数编号列',
            value: '数值列',
            unit: '单位列',
          }"
          :key="key"
          :label="label"
          ><el-select
            popper-class="preprocessing-select-menu"
            v-model="mapping[key]"
            :aria-label="label"
            ><el-option
              v-for="header in headers"
              :key="header"
              :value="header"
              :label="header" /></el-select></el-form-item></el-form
      ><template #footer
        ><el-button @click="mappingOpen = false">取消</el-button
        ><el-button
          type="primary"
          :loading="loading"
          :disabled="!mapping.id || !mapping.value || !mapping.unit"
          @click="importFiles"
          >确认列名并导入</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="fixedOpen" title="参考工程与固定选项" width="85%"
      ><p>
        表中列出的数值由导入数据替换。其余相态、井组拓扑与数值选项沿用原官方模板。生成后的完整工程可下载核对。
      </p>
      <pre class="source-code">{{ template?.fixedOptions }}</pre>
    </el-dialog>
  </section>
</template>

<style scoped>
.preprocessing-workbench {
  --el-color-primary: #303133;
  --el-color-primary-light-3: #5d5e60;
  --el-color-primary-light-5: #858689;
  --el-color-primary-light-7: #b4b5b7;
  --el-color-primary-light-8: #d2d3d4;
  --el-color-primary-light-9: #fff8d8;
  --el-color-primary-dark-2: #111;
  --el-border-radius-base: 2px;
  --el-component-size: 28px;
  --el-font-size-base: 13px;
  --preprocessing-accent: #f4d000;
  padding: 12px;
  min-height: 100%;
  min-width: 0;
  color: #333;
  background: #fff;
  font-size: 13px;
}
.preprocessing-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 40px;
  padding: 8px 12px;
  border: 1px solid #deded9;
  border-left: 3px solid var(--preprocessing-accent);
  background: #f5f5f1;
}
.preprocessing-header > div {
  display: flex;
  align-items: baseline;
  gap: 16px;
  flex-wrap: wrap;
}
h1 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.preprocessing-header > div > span {
  font-size: 12px;
  color: #777;
}
.flow {
  display: flex;
  flex-wrap: wrap;
  gap: 26px;
  padding: 9px 12px;
  border-bottom: 1px solid #e4e7ed;
}
.flow > div {
  display: flex;
  align-items: center;
  gap: 7px;
  color: #999;
  font-size: 12px;
}
.flow i {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border-radius: 2px;
  background: #f0f0ed;
  font-style: normal;
  font-size: 11px;
}
.flow .active {
  color: #333;
}
.flow .active i {
  background: #f4d000;
  color: #202020;
}
.type-cards {
  display: flex;
  flex-wrap: wrap;
  margin: 10px 0 12px;
  border-bottom: 1px solid #dcdcd6;
}
.type-cards button {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-height: 34px;
  padding: 7px 16px;
  border: 1px solid #dcdcd6;
  border-bottom: 0;
  margin-right: -1px;
  background: #fff;
  color: #333;
  font: inherit;
  cursor: pointer;
}
.type-cards button:hover {
  background: #fff8d8;
}
.type-cards button.selected {
  background: #f4d000;
  border-color: #d6b20d;
  position: relative;
}
.type-cards button:disabled {
  cursor: wait;
  opacity: 0.65;
}
.type-cards button:focus-visible,
.sheet-tabs button:focus-visible {
  outline: 2px solid #b99500;
  outline-offset: -2px;
}
.type-cards strong {
  font-size: 13px;
  font-weight: 500;
}
.type-cards small {
  color: #777;
  font-size: 11px;
}
.type-cards .selected small {
  color: #514600;
}
.feedback {
  margin: 10px 0;
}
.workbench-body {
  min-height: 180px;
  min-width: 0;
}
.import-card,
.editor-card,
.job-card,
.history-card {
  padding: 12px;
  margin-bottom: 12px;
  border: 1px solid #deded9;
  background: #fff;
}
.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 30px;
  margin: -12px -12px 10px;
  padding: 6px 12px;
  border-bottom: 1px solid #deded9;
  background: #f5f5f1;
}
h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}
.scope {
  color: #777;
  font-size: 12px;
  line-height: 1.7;
  margin: 0 0 8px;
}
.download-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin: 8px 0;
}
.download-row strong {
  margin-right: 8px;
  font-size: 12px;
  font-weight: 400;
  color: #555;
}
.import-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 9px 0;
  border-top: 1px solid #eee;
  border-bottom: 1px solid #eee;
}
.import-actions span {
  color: #777;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.hidden {
  display: none;
}
.source-details {
  margin-top: 8px;
  color: #777;
  font-size: 12px;
  line-height: 1.7;
}
.source-details summary {
  color: #555;
  cursor: pointer;
}
.source-details p {
  margin: 8px 0;
  overflow-wrap: anywhere;
}
.model-name {
  display: flex;
  align-items: center;
  gap: 12px;
  max-width: 470px;
  margin: 10px 0;
}
.model-name label {
  white-space: nowrap;
  font-size: 12px;
  color: #555;
}
.sheet-tabs {
  display: flex;
  overflow: auto;
  border-bottom: 1px solid #e0e0e0;
  margin: 10px 0;
}
.sheet-tabs button {
  flex-shrink: 0;
  min-height: 30px;
  padding: 5px 12px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  background: #fff;
  color: #555;
  font: inherit;
  font-size: 12px;
  white-space: nowrap;
  cursor: pointer;
}
.sheet-tabs button:hover {
  background: #fff8d8;
}
.sheet-tabs button.active {
  color: #202020;
  box-shadow: inset 0 -3px 0 #f4d000;
  font-weight: 600;
}
.sheet-tabs small {
  margin-left: 5px;
  font-size: 11px;
  color: #888;
  font-weight: 400;
}
.table-search {
  max-width: 430px;
  margin: 0 0 9px;
}
.parameters-table {
  border: 1px solid #deded9;
}
.parameters-table small {
  display: block;
  margin-top: 1px;
  color: #888;
  font-size: 11px;
  line-height: 16px;
}
.parameters-table :deep(th.el-table__cell) {
  background: #f5f5f1;
  color: #555;
  font-size: 12px;
}
.parameters-table :deep(.el-input__wrapper) {
  background: #fffdf0;
}
:deep(.el-table) {
  --el-table-border-color: #e1e1dc;
  --el-table-header-bg-color: #f5f5f1;
  --el-table-row-hover-bg-color: #fffbed;
  color: #333;
  font-size: 12px;
}
:deep(.el-table th.el-table__cell) {
  color: #555;
  padding: 6px 0;
}
:deep(.el-table td.el-table__cell) {
  padding: 5px 0;
}
:deep(.el-input__wrapper),
:deep(.el-select__wrapper) {
  min-height: 26px;
  font-size: 12px;
  border-radius: 2px;
}
:deep(.el-input__inner),
:deep(.el-select__selected-item) {
  font-size: 12px;
}
:deep(.el-input__wrapper.is-focus),
:deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px #b99500 inset;
}
:deep(.el-button) {
  height: 28px;
  padding: 6px 10px;
  font-size: 12px;
  border-radius: 2px;
}
:deep(.el-button + .el-button) {
  margin-left: 0;
}
:deep(.el-button.is-link) {
  height: auto;
  padding: 2px 4px;
}
:deep(.el-button--primary:not(.is-link)) {
  --el-button-bg-color: #252525;
  --el-button-border-color: #252525;
  --el-button-hover-bg-color: #050505;
  --el-button-hover-border-color: #050505;
  --el-button-active-bg-color: #303133;
  --el-button-active-border-color: #303133;
}
:deep(.el-tag) {
  border-radius: 2px;
  font-size: 11px;
}
:deep(.el-pagination) {
  --el-pagination-hover-color: #202020;
  font-size: 12px;
}
.el-pagination {
  justify-content: flex-end;
  margin-top: 8px;
}
.defaults-confirmation {
  height: auto;
  max-width: 100%;
  margin: 12px 0 8px;
}
.defaults-confirmation :deep(.el-checkbox__label) {
  white-space: normal;
  color: #555;
  font-size: 12px;
  line-height: 1.7;
}
.validation-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.validation-actions span {
  color: #777;
  font-size: 12px;
}
.validation-actions .valid-label {
  color: #44805d;
}
.error-list {
  margin-top: 12px;
  padding: 10px;
  border: 1px solid #edd5cf;
  background: #fffaf8;
}
.error-list > strong {
  display: block;
  color: #a24938;
  font-size: 12px;
  margin-bottom: 8px;
}
.error-list > small {
  color: #777;
}
.preview-section {
  margin: 12px 0;
  padding: 12px;
  border: 1px solid #deded9;
  min-width: 0;
}
.preview-section > .section-title {
  margin-bottom: 12px;
}
.build-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid #deded9;
  border-left: 3px solid #f4d000;
  background: #f5f5f1;
}
.build-card strong {
  color: #333;
  font-size: 13px;
  font-weight: 500;
}
.build-card p {
  margin: 4px 0 0;
  color: #777;
  font-size: 12px;
  line-height: 1.6;
}
.job-card > p {
  margin: 8px 0;
  color: #666;
  font-size: 12px;
  line-height: 1.7;
}
.job-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.job-card .hash {
  margin-top: 8px;
  color: #888;
  font-size: 11px;
  overflow-wrap: anywhere;
}
.job-card .stale-note {
  color: #96732c;
}
.comparison-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #e6e6e0;
}
.source-code {
  padding: 12px;
  max-height: 65vh;
  overflow: auto;
  background: #f5f5f1;
  color: #555;
  font-size: 12px;
  line-height: 1.7;
}
:deep(.eclipse-grid-3d-panel) {
  border-color: #deded9;
  border-radius: 0;
}
:deep(.eclipse-grid-3d-panel .panel-heading) {
  color: #333;
}
:deep(.eclipse-grid-3d-panel .panel-heading h3) {
  color: #333;
  font-size: 14px;
}
@media (max-width: 900px) {
  .preprocessing-header {
    align-items: flex-start;
  }
  .preprocessing-header > div {
    gap: 4px;
    flex-direction: column;
  }
  .flow {
    gap: 15px;
  }
  .type-cards button {
    padding: 7px 10px;
    gap: 6px;
  }
}
@media (max-width: 650px) {
  .preprocessing-workbench {
    padding: 8px;
  }
  .type-cards button {
    flex: 1;
    flex-direction: column;
    gap: 3px;
  }
  .type-cards small {
    font-size: 10px;
  }
  .flow {
    gap: 10px;
    padding: 8px 0;
  }
  .section-title,
  .build-card {
    flex-wrap: wrap;
  }
}

:deep(.el-dialog) {
  border-radius: 2px;
  padding: 16px;
}
:deep(.el-dialog__title) {
  font-size: 14px;
  font-weight: 500;
}
:global(.preprocessing-select-menu) {
  --el-color-primary: #303133;
  --el-color-primary-light-9: #fff8d8;
  --el-border-radius-base: 2px;
  --el-font-size-base: 12px;
}
</style>
