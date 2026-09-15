<script setup>
/**
 * 库 → 损耗评价 → 井筒损耗 / 地面损耗共用页面，不是单井产能页面。
 * lossKind='wellbore'：井筒放空损耗；lossKind='surface'：地面放空损耗及凝液溶解携带损耗。
 * 两者都支持直接输入最终气量、填写参数进行公式计算；当前不要求选择某口井。
 * 本页只组织输入和显示结果，分别调用 api/wellboreLoss、api/surfaceLoss 的后端接口。
 * 修改界面时：mode 分支控制两种输入方式，isSurface 分支控制地面损耗专用区域。
 */
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import NaturalGasImportDialog from '@/views/DataManagement/NaturalGasImportDialog.vue'
import { wellboreLossApi } from '@/api/wellboreLoss'
import { surfaceLossApi } from '@/api/surfaceLoss'
import { workspaceTreeData } from '@/utils/workspaceTreeState'
import { upsertReservoirLossRecordNode } from '@/utils/reservoirGeologicalLossTree'

const props = defineProps({
  reservoir: { type: Object, default: null },
  lossKind: { type: String, default: 'wellbore' }
})
// 名称随传入的储气库节点更新；所属库不以项目范围ID或单井名称代替。
const resultTitle = computed(() => `${props.reservoir?.label || '未选择库'}-${lossTitle.value}计算结果`)
// 井筒与地面放空的公式和PVT输入相同，复用表单；各自使用独立记录接口。
const isSurface = computed(() => props.lossKind === 'surface')
const lossTitle = computed(() => isSurface.value ? '地面损耗' : '井筒损耗')
const ventTitle = computed(() => isSurface.value ? '地面系统放空损耗气量' : '井筒损耗气量')
const segmentTitle = computed(() => isSurface.value ? '放空段' : '放空井段')
const pressureTitle = computed(() => isSurface.value ? '平均压力' : '井筒平均压力')
const lossApi = computed(() => isSurface.value ? surfaceLossApi : wellboreLossApi)
const condensateForm = reactive({ volume: '', gasOilRatio: '' })
const route = useRoute()
const router = useRouter()
// projectId + gasReservoirId 是原系统项目范围，不能把 gasReservoirId 当作新建储气库 ID。
// storageId 标识实际储气库；URL 中 lossRecordId 用于读取该库下已有记录，无值时创建新记录。
const projectId = computed(() => Number(props.reservoir?.projectId ?? route.query.projectId))
const gasReservoirId = computed(() => Number(props.reservoir?.gasReservoirId ?? route.query.gasReservoirId))
const storageId = computed(() => Number(props.reservoir?.storageId ?? route.query.storageId))
const recordId = computed(() => route.query.lossRecordId ? Number(route.query.lossRecordId) : null)
const mode = ref('formula')
const importDialogVisible = ref(false)
const importedFileName = ref('')
const calculation = ref(null)
const calculating = ref(false)
const saving = ref(false)
let nextVolumeId = 2
// revision跟踪输入版本，loadVersion区分详情请求，active阻止卸载后的响应回写表单。
let revision = 0
let loadVersion = 0
let active = true

// 直接输入：最终损耗气量均为 10⁴m³；凝液损耗字段只在地面损耗中使用。
const directForm = reactive({ lossVolume: '', condensateLossVolume: '' })
// 公式输入：温度按 K 原样提交（不同于地质微观损耗的 ℃），压力为 MPa，气体组分为百分数。
const formulaForm = reactive({
  averageTemperature: '', pressureBefore: '', pressureAfter: '', gasType: 0, specificGravity: '',
  h2SMoleFraction: '', co2MoleFraction: '', n2MoleFraction: '', modificationMethod: 0,
  deviationFactorMethod: 0, viscosityMethod: 0
})
// 多个放空段的容积单位为 m³；id 仅供前端列表稳定渲染，不是单井 ID 或数据库井段 ID。
const volumes = ref([{ id: 1, value: '' }])

const formatLoss = value => Number.isFinite(value) ? value.toFixed(4) : isSurface.value ? '未计算' : '—'
// 后端结果字段不同：井筒读取 wellboreLossVolume，地面分别展示 ventLossVolume 和凝液损耗。
const resultText = computed(() => formatLoss(isSurface.value
  ? calculation.value?.ventLossVolume : calculation.value?.wellboreLossVolume))
const condensateResultText = computed(() => formatLoss(calculation.value?.condensateLossVolume))
const formatFactor = value => value != null && Number.isFinite(Number(value)) ? Number(value).toFixed(4) : ''
const responseData = response => response?.data ?? response
// 公式法可增删放空段，但至少保留一行；序号仅用于显示，提交时只传各段容积。
const addVolume = () => volumes.value.push({ id: nextVolumeId++, value: '' })
const removeVolume = index => {
  if (volumes.value.length === 1) return
  volumes.value.splice(index, 1)
}
// 切换输入方式保留已填参数，只使旧结果失效；隐藏表单的数据不会混入当前请求。
const setMode = value => { mode.value = value; resetResult() }

// 只提交当前方式用到的输入；直接输入不携带隐藏的公式参数，也不要求填写PVT。
const buildVentInput = () => mode.value === 'direct'
  ? { calculationMode: 'direct', inputLossVolume: Number(directForm.lossVolume) }
  : {
      calculationMode: 'formula', inputLossVolume: null,
      averageTemperatureK: Number(formulaForm.averageTemperature),
      pressureBefore: Number(formulaForm.pressureBefore), pressureAfter: Number(formulaForm.pressureAfter),
      segments: volumes.value.map(item => ({ segmentVolume: Number(item.value) })),
      gasType: Number(formulaForm.gasType), specificGravity: Number(formulaForm.specificGravity),
      h2SMoleFraction: Number(formulaForm.h2SMoleFraction), co2MoleFraction: Number(formulaForm.co2MoleFraction),
      n2MoleFraction: Number(formulaForm.n2MoleFraction),
      modificationMethod: Number(formulaForm.modificationMethod),
      deviationFactorMethod: Number(formulaForm.deviationFactorMethod),
      viscosityMethod: Number(formulaForm.viscosityMethod), importedFileName: importedFileName.value || null
    }

// 地面损耗额外携带凝液参数：直接法传最终气量，公式法传凝析油体积（10⁴m³）和气油比（m³/m³）。
const buildInput = () => ({
  ...buildVentInput(),
  ...(isSurface.value ? mode.value === 'direct' ? {
    inputCondensateLossVolume: Number(directForm.condensateLossVolume)
  } : {
    condensateVolume: Number(condensateForm.volume),
    gasOilRatio: Number(condensateForm.gasOilRatio)
  } : {})
})

// 前端检查当前方式的必填和数值格式；物性及公式适用条件仍由后端校验。
const validatePageInput = () => {
  const condensateValues = mode.value === 'direct'
    ? [directForm.condensateLossVolume] : [condensateForm.volume, condensateForm.gasOilRatio]
  if (isSurface.value && !condensateValues.every(value =>
    value != null && String(value).trim() !== '' && Number.isFinite(Number(value)) && Number(value) >= 0)) return false
  if (mode.value === 'direct') return String(directForm.lossVolume).trim() !== ''
    && Number.isFinite(Number(directForm.lossVolume)) && Number(directForm.lossVolume) >= 0
  const values = [formulaForm.averageTemperature, formulaForm.pressureBefore, formulaForm.pressureAfter,
    formulaForm.specificGravity, formulaForm.h2SMoleFraction, formulaForm.co2MoleFraction,
    formulaForm.n2MoleFraction, ...volumes.value.map(item => item.value)]
  return values.every(value => value != null && String(value).trim() !== '' && Number.isFinite(Number(value)))
}

// “计算”只更新结果，不保存；用输入版本号丢弃等待期间已失效的响应。
const calculate = async () => {
  if (!(storageId.value > 0)) { ElMessage.warning('请先选择具体储气库'); return }
  if (calculating.value || saving.value) return
  if (!validatePageInput()) { ElMessage.warning('请完整填写计算参数'); return }
  // 两种方式均交给服务端校验；直接方式传最终气量（井筒一项、地面两项），不使用 PVT 参数。
  calculating.value = true
  const startedRevision = revision
  try {
    const response = await lossApi.value.calculate({ projectId: projectId.value,
      gasReservoirId: gasReservoirId.value, storageId: storageId.value, input: buildInput() })
    // 请求期间编辑参数、切换记录或重置时，丢弃已过期的计算响应。
    if (!active || startedRevision !== revision) return
    calculation.value = responseData(response)
    ElMessage.success('计算完成')
  } finally { calculating.value = false }
}

// “重置”只清除计算结果，不清空已经填写或导入的参数。
const resetResult = () => { revision++; calculation.value = null }
// “保存”要求当前参数已计算：保存输入、结果和库归属，再更新左侧记录节点及 URL。
const save = async () => {
  if (!(storageId.value > 0)) { ElMessage.warning('请先选择具体储气库'); return }
  if (saving.value || calculating.value) return
  if (!calculation.value) { ElMessage.warning('请先完成计算再保存'); return }
  saving.value = true
  const scope = { recordId: recordId.value, projectId: projectId.value, gasReservoirId: gasReservoirId.value, storageId: storageId.value }
  const savedLossKind = props.lossKind
  const startedRevision = revision
  try {
    const response = await lossApi.value.save({ ...scope, input: buildInput(), calculation: calculation.value })
    const saved = responseData(response)
    // 落库结果更新到请求时的库目录；只有页面仍是同一版本，才回写其记录ID。
    upsertReservoirLossRecordNode({ treeData: workspaceTreeData, projectId: scope.projectId,
      gasReservoirId: scope.gasReservoirId, storageId: scope.storageId, lossType: savedLossKind, record: saved })
    if (active && startedRevision === revision) {
      await router.replace({ query: { ...route.query, lossRecordId: saved.id } })
    }
    ElMessage.success(scope.recordId ? '修改已保存' : `${saved.recordName}已保存`)
  } finally { saving.value = false }
}

// 回填已有记录：恢复计算方式、直接/公式参数、多个放空段，以及保存时的结果快照。
const loadDetail = async () => {
  const version = ++loadVersion
  if (!recordId.value) return
  const detail = responseData(await lossApi.value.get(recordId.value, projectId.value, gasReservoirId.value, storageId.value))
  if (!active || version !== loadVersion) return
  condensateForm.volume = detail.input.condensateVolume ?? ''
  condensateForm.gasOilRatio = detail.input.gasOilRatio ?? ''
  mode.value = detail.input.calculationMode
  directForm.lossVolume = detail.input.inputLossVolume ?? ''
  directForm.condensateLossVolume = detail.input.inputCondensateLossVolume
    ?? (isSurface.value && mode.value === 'direct' ? detail.calculation?.condensateLossVolume : '') ?? ''
  Object.assign(formulaForm, {
    averageTemperature: detail.input.averageTemperatureK ?? '', pressureBefore: detail.input.pressureBefore ?? '',
    pressureAfter: detail.input.pressureAfter ?? '', gasType: detail.input.gasType ?? 0,
    specificGravity: detail.input.specificGravity ?? '', h2SMoleFraction: detail.input.h2SMoleFraction ?? '',
    co2MoleFraction: detail.input.co2MoleFraction ?? '', n2MoleFraction: detail.input.n2MoleFraction ?? '',
    modificationMethod: detail.input.modificationMethod ?? 0,
    deviationFactorMethod: detail.input.deviationFactorMethod ?? 0, viscosityMethod: detail.input.viscosityMethod ?? 0
  })
  volumes.value = detail.input.segments?.length
    ? detail.input.segments.map(item => ({ id: nextVolumeId++, value: item.segmentVolume }))
    : [{ id: nextVolumeId++, value: '' }]
  importedFileName.value = detail.input.importedFileName || ''
  // 等表单变更监听完成后再恢复已保存结果，避免加载动作被识别为用户编辑。
  await nextTick()
  if (active && version === loadVersion) calculation.value = detail.calculation
}

// 切换库、记录或损耗类型时恢复空白表单；页面“重置”按钮不调用这里，避免清空用户输入。
const clearNewRecord = () => {
  mode.value = 'formula'; directForm.lossVolume = ''; directForm.condensateLossVolume = ''
  condensateForm.volume = ''; condensateForm.gasOilRatio = ''
  Object.assign(formulaForm, { averageTemperature: '', pressureBefore: '', pressureAfter: '', gasType: 0,
    specificGravity: '', h2SMoleFraction: '', co2MoleFraction: '', n2MoleFraction: '',
    modificationMethod: 0, deviationFactorMethod: 0, viscosityMethod: 0 })
  volumes.value = [{ id: nextVolumeId++, value: '' }]
  importedFileName.value = ''; calculation.value = null
}

// 导入首张工作表中表头后的第一条非空记录，列顺序：气体类型、比重、H₂S、CO₂、N₂。
// 只填充公式法的天然气基础参数；温压、放空段容积和凝液参数仍需在页面填写。
const handleGasImport = async ({ file, options }) => {
  try {
    const extension = file.name.split('.').pop()?.toLowerCase()
    if (!['xlsx', 'xls', 'csv'].includes(extension)) throw new Error('仅支持 .xlsx、.xls、.csv 表格文件')
    const XLSX = await import('xlsx')
    const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' })
    const sheet = workbook.Sheets[workbook.SheetNames[0]]
    let rows = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: '' })
    if (options?.removeEmptyRows) rows = rows.filter(row => row.some(value => String(value).trim()))
    const row = rows.slice(1).find(item => item.some(value => String(value).trim()))
    if (!row) throw new Error('文件中没有可导入的天然气基础数据')
    const gasTypes = { 干气: 0, 湿气: 1, 凝析气: 2 }
    if (!(String(row[0]).trim() in gasTypes)) throw new Error('天然气类型只能为干气、湿气或凝析气')
    const numbers = row.slice(1, 5).map(Number)
    if (numbers.some(value => !Number.isFinite(value))) throw new Error('天然气比重及气体组分必须是有效数字')
    Object.assign(formulaForm, { gasType: gasTypes[String(row[0]).trim()], specificGravity: numbers[0],
      h2SMoleFraction: numbers[1], co2MoleFraction: numbers[2], n2MoleFraction: numbers[3] })
    importedFileName.value = file.name
    calculation.value = null
    ElMessage.success('PVT基础数据导入成功')
  } catch (error) { ElMessage.error(error?.message || 'PVT数据导入失败') }
}

// 同步使计算结果失效，防止编辑参数后立即保存旧结果；详情回填在nextTick后单独恢复结果。
watch([directForm, formulaForm, volumes, condensateForm], resetResult, { deep: true, flush: 'sync' })
// 首次进入及后续切换均走同一路径：清旧状态，有记录 ID 时再加载详情，避免跨库复用。
watch([recordId, projectId, gasReservoirId, storageId, () => props.lossKind], async () => {
  loadVersion++; resetResult(); clearNewRecord()
  await loadDetail()
}, { immediate: true })
onBeforeUnmount(() => { active = false; revision++; loadVersion++ })
</script>

<template>
  <section class="wellbore-loss" :class="{ 'surface-loss': isSurface }">
    <!-- 顶部功能区：标题随损耗类型变化；PVT 导入只在公式法显示，保存适用于两种方式。 -->
    <header class="result-tabs">
      <div class="result-tab" :title="resultTitle"><span>{{ resultTitle }}</span></div>
      <div class="header-actions">
        <button v-if="mode === 'formula'" class="secondary" type="button" :disabled="saving" @click="importDialogVisible = true">导入PVT</button>
        <button class="primary" type="button" :disabled="saving || calculating" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </header>

    <!-- 保存期间禁用表单交互，保证正在保存的参数与结果对应。 -->
    <main class="form-canvas" :inert="saving">
      <div v-if="isSurface" class="form-title">{{ mode === 'direct' ? '地面损耗' : '地面系统放空损耗' }}</div>
      <div class="mode-row">
        <span class="mode-label">计算方式</span>
        <div class="mode-switch" role="tablist" :aria-label="`${lossTitle}计算方式`">
          <button :class="{ active: mode === 'direct' }" role="tab" :aria-selected="mode === 'direct'" type="button" @click="setMode('direct')">直接输入</button>
          <button :class="{ active: mode === 'formula' }" role="tab" :aria-selected="mode === 'formula'" type="button" @click="setMode('formula')">公式计算</button>
        </div>
      </div>

      <!-- 直接输入区：井筒填写一项损耗气量，地面还需填写凝液溶解携带损耗气量。 -->
      <template v-if="mode === 'direct'">
        <div class="form-title">{{ isSurface ? '请输入损耗气量' : '请输入井筒损耗气量' }}</div>
        <div class="parameter-grid direct-grid">
          <label class="field"><span>{{ ventTitle }}（10⁴m³）</span><input v-model="directForm.lossVolume" placeholder="请输入" /></label>
          <label v-if="isSurface" class="field"><span>凝液溶解携带损耗气量（10⁴m³）</span><input v-model="directForm.condensateLossVolume" placeholder="请输入" /></label>
        </div>
      </template>

      <!-- 公式输入区：多个放空段容积、放空前后压力、温度，以及天然气 PVT 基础参数。 -->
      <template v-else>
        <div class="form-title">请输入计算参数</div>
        <section class="volume-section">
          <div class="section-heading">
            <div><strong>{{ segmentTitle }}容积</strong></div>
            <button class="add-button" type="button" @click="addVolume">＋ 添加{{ isSurface ? '放空段' : '井段' }}</button>
          </div>
          <div class="volume-list">
            <div v-for="(item, index) in volumes" :key="item.id" class="volume-row">
              <span class="volume-index">{{ isSurface ? '放空段' : '井段' }} {{ index + 1 }}</span>
              <label class="field compact"><span>{{ segmentTitle }}容积（m³）</span><input v-model="item.value" placeholder="请输入" /></label>
              <button class="remove-button" type="button" :disabled="volumes.length === 1" @click="removeVolume(index)">删除</button>
            </div>
          </div>
        </section>

        <!-- 温压由用户填写；放空前后偏差系数由计算接口返回，只读显示，不手工输入。 -->
        <div class="parameter-grid primary-parameters">
          <label class="field"><span>{{ segmentTitle }}天然气平均温度（K）</span><input v-model="formulaForm.averageTemperature" placeholder="请输入" /></label>
          <label class="field"><span>放空前{{ pressureTitle }}（MPa）</span><input v-model="formulaForm.pressureBefore" placeholder="请输入" /></label>
          <label class="field"><span>放空后{{ pressureTitle }}（MPa）</span><input v-model="formulaForm.pressureAfter" placeholder="请输入" /></label>
          <label class="field readonly"><span>放空前偏差系数</span><input :value="formatFactor(calculation?.deviationFactorBefore)" readonly placeholder="PVT自动计算" /></label>
          <label class="field readonly"><span>放空后偏差系数</span><input :value="formatFactor(calculation?.deviationFactorAfter)" readonly placeholder="PVT自动计算" /></label>
        </div>

        <div class="subheading">PVT参数</div>
        <div class="parameter-grid">
          <label class="field"><span>天然气类型</span><select v-model="formulaForm.gasType"><option :value="0">干气</option><option :value="1">湿气</option><option :value="2">凝析气</option></select></label>
          <label class="field"><span>天然气比重（dless）</span><input v-model="formulaForm.specificGravity" placeholder="请输入" /></label>
          <label class="field"><span>H₂S摩尔百分含量（%）</span><input v-model="formulaForm.h2SMoleFraction" placeholder="请输入" /></label>
          <label class="field"><span>CO₂摩尔百分含量（%）</span><input v-model="formulaForm.co2MoleFraction" placeholder="请输入" /></label>
          <label class="field"><span>N₂摩尔百分含量（%）</span><input v-model="formulaForm.n2MoleFraction" placeholder="请输入" /></label>
          <label class="field"><span>非烃气体修正方法</span><select v-model="formulaForm.modificationMethod"><option :value="0">Wichert-Aziz 修正方法</option><option :value="1">Carr-Kobayashi-Burrows 方法</option></select></label>
          <label class="field"><span>天然气偏差系数计算方法</span><select v-model="formulaForm.deviationFactorMethod"><option :value="0">Dranchuk-Abu-Kassem 方法</option><option :value="1">Dranchuk-Purvis-Robinson 方法</option><option :value="2">Hall-Yarborough 方法</option></select></label>
          <label class="field"><span>天然气黏度计算方法</span><select v-model="formulaForm.viscosityMethod"><option :value="0">Lee-Gonzalez-Eakin 方法</option><option :value="1">Carr-Kobayashi-Burrows 方法</option><option :value="2">Sutton 方法</option></select></label>
        </div>
        <div v-if="importedFileName" class="import-note">已导入：{{ importedFileName }}</div>
      </template>

      <!-- 只有公式法输入凝析油体积和气油比；直接模式在上方输入最终凝液损耗气量。 -->
      <section v-if="isSurface && mode === 'formula'" class="condensate-section">
        <div class="subheading">凝液溶解携带损耗</div>
        <div class="parameter-grid">
          <label class="field"><span>分离器出口凝析油体积（10⁴m³）</span><input v-model="condensateForm.volume" placeholder="请输入" /></label>
          <label class="field"><span>分离器出口凝析油溶解气油比（m³/m³）</span><input v-model="condensateForm.gasOilRatio" placeholder="请输入" /></label>
        </div>
      </section>

      <!-- 计算与保存分离；“重置”只清结果。地面结果区分别显示放空损耗和凝液损耗。 -->
      <div class="calculation-actions">
        <button class="calculate" type="button" :disabled="calculating" @click="calculate">{{ calculating ? '计算中…' : '计 算' }}</button>
        <button class="reset" type="button" @click="resetResult">重 置</button>
      </div>
      <section class="result-card">
        <h3>计算结果</h3>
        <!-- 两种方式均分别展示放空损耗和凝液损耗；切换方式或重置只清除结果。 -->
        <div :class="{ 'surface-result-grid': isSurface }" aria-live="polite">
          <div class="result-line"><span>{{ ventTitle }}（10⁴m³）：</span><strong>{{ resultText }}</strong></div>
          <div v-if="isSurface" class="result-line"><span>凝液溶解携带损耗气量（10⁴m³）：</span><strong>{{ condensateResultText }}</strong></div>
        </div>
      </section>
    </main>
    <NaturalGasImportDialog v-model="importDialogVisible" import-kind="data" @confirm="handleGasImport" />
  </section>
</template>

<style scoped>
.wellbore-loss { height: 100%; min-width: 760px; overflow: auto; background: #fff; color: #202020; font-family: Arial, sans-serif; font-size: 13px; }
.result-tabs { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; justify-content: space-between; height: 34px; padding-right: 8px; border-bottom: 1px solid #e4e7ed; background: #fafafa; box-sizing: border-box; }
.result-tab { display: flex; align-self: stretch; align-items: center; justify-content: center; min-width: 190px; padding: 0 12px; border-right: 1px solid #e4e7ed; background: #f4d000; font-weight: 600; box-sizing: border-box; }
.result-tab { max-width: 430px; overflow: hidden; }
.result-tab > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.header-actions, .calculation-actions { display: flex; gap: 10px; }
button { height: 27px; padding: 0 16px; border: 1px solid #c9cdd3; border-radius: 4px; background: #fff; color: #292929; font: inherit; cursor: pointer; }
button:hover { border-color: #b49a00; }
button:disabled { cursor: not-allowed; opacity: .45; }
button.primary { min-width: 74px; border-color: #202020; background: #202020; color: #fff; }
.form-canvas { padding: 20px 18px 34px; }
.mode-row { display: flex; align-items: center; gap: 18px; margin-bottom: 26px; }
.mode-label, .form-title { font-weight: 600; }
.mode-switch { display: inline-flex; padding: 2px; border: 1px solid #d9dce1; border-radius: 5px; background: #f5f6f7; }
.mode-switch button { min-width: 88px; border: 0; background: transparent; }
.mode-switch button.active { background: #f4d000; color: #202020; box-shadow: 0 1px 3px rgba(0,0,0,.12); }
.form-title { margin-bottom: 18px; }
.parameter-grid { display: grid; grid-template-columns: repeat(4, minmax(170px, 1fr)); gap: 20px 24px; }
.direct-grid { max-width: 430px; grid-template-columns: 1fr; }
.field { display: grid; gap: 8px; min-width: 0; color: #333; }
.field input, .field select { width: 100%; height: 34px; padding: 0 11px; border: 1px solid #d4d7dc; border-radius: 4px; background: #fff; color: #303133; font: inherit; outline: none; box-sizing: border-box; }
.field input:focus, .field select:focus { border-color: #b49a00; box-shadow: 0 0 0 2px rgba(244,208,0,.14); }
.field.readonly input { background: #f5f6f7; color: #606266; }
.volume-section { margin-bottom: 22px; border: 1px solid #e4e7ed; border-radius: 4px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; min-height: 46px; padding: 0 14px; border-bottom: 1px solid #e8eaed; background: #fafafa; }
.section-heading strong { margin-right: 12px; }
.add-button { border-color: #d5b900; background: #fffbe0; }
.volume-list { padding: 12px 14px; }
.volume-row { display: grid; grid-template-columns: 76px minmax(210px, 390px) 66px; align-items: end; gap: 14px; padding: 10px 0; border-bottom: 1px dashed #ebecef; }
.volume-row:last-child { border-bottom: 0; }
.volume-index { align-self: center; color: #606266; }
.field.compact { gap: 6px; }
.remove-button { padding: 0 10px; color: #606266; }
.primary-parameters { margin-bottom: 26px; }
.subheading { margin: 0 0 16px; padding-top: 18px; border-top: 1px solid #eceef1; font-weight: 600; }
.condensate-section { margin-top: 26px; }
.import-note { margin-top: 14px; color: #777; font-size: 12px; }
.calculation-actions { margin-top: 26px; }
.calculation-actions button { height: 32px; min-width: 72px; }
.calculate { border-color: #d5b900; background: #f4d000; }
.result-card { min-height: 142px; margin-top: 34px; padding: 18px 18px 26px; border: 1px solid #ececec; border-radius: 4px; background: #f5f5f5; box-sizing: border-box; }
.result-card h3 { margin: 0 0 24px; font-size: 13px; }
.result-line { display: flex; align-items: baseline; gap: 6px; color: #333; }
.result-line strong { font-size: 15px; font-weight: 500; }
/* 仅调整地面页面：压力、温度与自动PVT结果分行，保持井筒页面的既有布局。 */
.surface-loss .primary-parameters { grid-template-columns: repeat(6, minmax(0, 1fr)); }
.surface-loss .primary-parameters .field { grid-column: span 2; }
.surface-loss .primary-parameters .readonly { grid-column: span 3; }
.surface-loss .condensate-section .parameter-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.surface-loss .direct-grid { max-width: none; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.surface-result-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.surface-result-grid .result-line { flex-direction: column; gap: 12px; }
@media (max-width: 1250px) { .parameter-grid { grid-template-columns: repeat(3, minmax(170px, 1fr)); } }
@media (max-width: 920px) { .parameter-grid { grid-template-columns: repeat(2, minmax(170px, 1fr)); } }
</style>
