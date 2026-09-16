<script setup>
/** 库 → 损耗评价 → 地质损耗 → 逸散性损耗。本文件包含该页面的参数状态、接口请求、模板和样式。 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { geologicalLossApi } from '@/api/geologicalLoss'
import { workspaceTreeData } from '@/utils/workspaceTreeState'
import { upsertReservoirLossRecordNode } from '@/utils/reservoirGeologicalLossTree'

const props = defineProps({ reservoir: { type: Object, default: null } })
// 当前功能固定在本页面，不通过外部参数切换成其他页面。
const featureName = '逸散性损耗'

const route = useRoute()
const router = useRouter()
const isMicroscopic = computed(() => featureName === '微观损耗')
const title = computed(() => isMicroscopic.value ? '微观损耗' : '逸散性损耗')
// 标题使用当前库节点的显示名称，不展示数据库ID，也不借用当前选中井的名称。
const resultTitle = computed(() => `${props.reservoir?.label || '未选择库'}-${title.value}计算结果`)
// projectId + gasReservoirId 共同限定原系统项目范围；gasReservoirId 不是新建储气库的 ID。
// storageId 才标识当前储气库；lossRecordId 标识该库下已保存的损耗记录，无值时为新记录。
const projectId = computed(() => Number(props.reservoir?.projectId ?? route.query.projectId))
const gasReservoirId = computed(() => Number(props.reservoir?.gasReservoirId ?? route.query.gasReservoirId))
const storageId = computed(() => Number(props.reservoir?.storageId ?? route.query.storageId))
const recordId = computed(() => route.query.lossRecordId ? Number(route.query.lossRecordId) : null)
const importDialogVisible = ref(false)
const calculating = ref(false)
const saving = ref(false)
const importedFileName = ref('')
const microscopicCalculation = ref(null)
const escapeCalculation = ref(null)
// active拦截卸载后的响应；loadVersion区分详情请求；revision标记表单编辑和计算结果是否仍匹配。
let active = true
let loadVersion = 0
let revision = 0
onBeforeUnmount(() => { active = false; loadVersion++; revision++ })

// 两种方法分别保存输入和结果，避免切换方法时混用参数。
// 微观表单：体积为 10⁴m³，压力为 MPa，温度为 ℃，饱和度及气体组分按百分数填写。
const microscopicForm = reactive({
  poreVolume: '', previousResidualSaturation: '', currentResidualSaturation: '', lowerLimitPressure: '',
  formationTemperature: '', gasType: 0, specificGravity: '', h2SMoleFraction: '', co2MoleFraction: '',
  n2MoleFraction: '', modificationMethod: 0, deviationFactorMethod: 0, viscosityMethod: 0
})
// 逸散性表单：各项气量为 10⁴m³，predictedChangeRate 为百分数，不是已除以 100 的小数。
const escapeForm = reactive({ previousCushionGasVolume: '', movableCushionGasVolume: '', unusedInventoryVolume: '', injectionVolume: '', predictedChangeRate: '' })
const outputValue = computed(() => isMicroscopic.value ? microscopicCalculation.value?.microscopicLossVolume : escapeCalculation.value?.escapeLossVolume)
const formatResult = value => Number.isFinite(Number(value)) ? Number(value).toFixed(4) : ''
const numericObject = source => Object.fromEntries(Object.entries(source).map(([key, value]) => [key, Number(value)]))
const responseData = response => response?.data ?? response

// 切换库、方法或记录时清空对应表单；与页面“重置”按钮仅清结果的行为不同。
const resetMicroscopic = () => {
  Object.assign(microscopicForm, { poreVolume: '', previousResidualSaturation: '', currentResidualSaturation: '', lowerLimitPressure: '', formationTemperature: '', gasType: 0, specificGravity: '', h2SMoleFraction: '', co2MoleFraction: '', n2MoleFraction: '', modificationMethod: 0, deviationFactorMethod: 0, viscosityMethod: 0 })
  importedFileName.value = ''
  microscopicCalculation.value = null
}
const resetEscape = () => { Object.keys(escapeForm).forEach(key => { escapeForm[key] = '' }); escapeCalculation.value = null }
// “重置”仅撤销本次计算结果，保留用户已经填写或导入的全部参数。
const resetCalculationResult = () => {
  revision++
  if (isMicroscopic.value) microscopicCalculation.value = null
  else escapeCalculation.value = null
}
const microscopicInput = () => ({ ...numericObject(microscopicForm), importedFileName: importedFileName.value || null })
const validateNumbers = values => Object.values(values).every(value => value === null || Number.isFinite(value))

// “计算”先检查必填和数值格式，再调用当前方法的后端接口；不会自动保存记录。
const calculate = async () => {
  if (calculating.value || saving.value) return
  if (!(storageId.value > 0)) { ElMessage.warning('请先选择具体储气库'); return }
  const rawForm = isMicroscopic.value ? microscopicForm : escapeForm
  if (Object.values(rawForm).some(value => value === '' || value === null || value === undefined)) {
    ElMessage.warning('请完整填写计算参数')
    return
  }
  const input = isMicroscopic.value ? microscopicInput() : numericObject(escapeForm)
  // importedFileName 只是导入来源快照，不参与公式，不能按数值参数校验。
  const calculationValues = Object.fromEntries(
    Object.entries(input).filter(([key]) => key !== 'importedFileName')
  )
  if (!validateNumbers(calculationValues)) { ElMessage.warning('请完整填写计算参数'); return }
  calculating.value = true
  const startedRevision = revision
  try {
    if (isMicroscopic.value) {
      const response = await geologicalLossApi.calculateMicroscopic({ projectId: projectId.value, gasReservoirId: gasReservoirId.value, storageId: storageId.value, input })
      if (!active || revision !== startedRevision) return
      microscopicCalculation.value = responseData(response)
    } else {
      const response = await geologicalLossApi.calculateEscape({ projectId: projectId.value, gasReservoirId: gasReservoirId.value, storageId: storageId.value, input })
      if (!active || revision !== startedRevision) return
      escapeCalculation.value = responseData(response)
    }
    ElMessage.success('计算完成')
  } finally { calculating.value = false }
}

// “保存”要求已有计算结果，提交输入与结果快照，并把返回记录同步到左侧库目录和 URL。
const save = async () => {
  if (calculating.value || saving.value) return
  if (!(storageId.value > 0)) { ElMessage.warning('请先选择具体储气库'); return }
  const calculation = isMicroscopic.value ? microscopicCalculation.value : escapeCalculation.value
  if (!calculation) { ElMessage.warning('请先完成计算再保存'); return }
  saving.value = true
  const startedRevision = revision
  const savedType = isMicroscopic.value ? 'microscopic' : 'escape'
  try {
    // 项目范围、实际储气库和损耗记录分别传入，不用记录编号代替 storageId。
    const payload = { recordId: recordId.value, projectId: projectId.value, gasReservoirId: gasReservoirId.value, storageId: storageId.value, input: isMicroscopic.value ? microscopicInput() : numericObject(escapeForm), calculation }
    const response = isMicroscopic.value ? await geologicalLossApi.saveMicroscopic(payload) : await geologicalLossApi.saveEscape(payload)
    const saved = responseData(response)
    // 保存成功就按请求时的归属更新目录；若用户已离开或改参数，不再改当前页面路由。
    upsertReservoirLossRecordNode({ treeData: workspaceTreeData, projectId: payload.projectId,
      gasReservoirId: payload.gasReservoirId, storageId: payload.storageId, lossType: savedType, record: saved })
    if (!active || revision !== startedRevision) return
    const query = { ...route.query, lossRecordId: saved.id }
    delete query.lossLibraryId
    await router.replace({ query })
    ElMessage.success(recordId.value ? '修改已保存' : `${saved.recordName}已保存`)
  } finally { saving.value = false }
}
// 点击左侧已有记录或带 lossRecordId 的地址进入时，读取该库下记录并回填输入、结果。
const loadDetail = async () => {
  const version = ++loadVersion
  if (!recordId.value) return
  const response = isMicroscopic.value ? await geologicalLossApi.getMicroscopic(recordId.value, projectId.value, gasReservoirId.value, storageId.value) : await geologicalLossApi.getEscape(recordId.value, projectId.value, gasReservoirId.value, storageId.value)
  const detail = responseData(response)
  if (!active || version !== loadVersion) return
  if (isMicroscopic.value) { Object.assign(microscopicForm, detail.input); importedFileName.value = detail.input.importedFileName || '' }
  else Object.assign(escapeForm, detail.input)
  // 回填输入会触发表单监听并清空结果，待其完成后再恢复数据库中的结果快照。
  await nextTick()
  if (!active || version !== loadVersion) return
  if (isMicroscopic.value) microscopicCalculation.value = detail.calculation
  else escapeCalculation.value = detail.calculation
}

// 只读取首个工作表中表头后的第一条非空数据：气体类型、比重、H₂S、CO₂、N₂。
// 导入的是天然气基础参数，不是完整 PVT 曲线，也不会自动计算或保存损耗。
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
    if (!validateNumbers(numbers)) throw new Error('天然气比重及气体组分必须是有效数字')
    Object.assign(microscopicForm, { gasType: gasTypes[String(row[0]).trim()], specificGravity: numbers[0], h2SMoleFraction: numbers[1], co2MoleFraction: numbers[2], n2MoleFraction: numbers[3] })
    importedFileName.value = file.name
    microscopicCalculation.value = null
    ElMessage.success('PVT基础数据导入成功')
  } catch (error) { ElMessage.error(error?.message || 'PVT数据导入失败') }
}

// 目录切换后先恢复空白表单，再按记录 ID 回填；初次进入由 onMounted 加载详情。
watch(() => [featureName, recordId.value, storageId.value], async () => { revision++; isMicroscopic.value ? resetMicroscopic() : resetEscape(); await loadDetail() })
// 参数一变就同步使旧结果失效，避免下一次点击保存时带上修改前的计算值。
watch(microscopicForm, () => { revision++; microscopicCalculation.value = null }, { deep: true, flush: 'sync' })
watch(escapeForm, () => { revision++; escapeCalculation.value = null }, { deep: true, flush: 'sync' })
onMounted(loadDetail)
</script>

<template>
  <section class="geological-loss">
    <!-- 顶部功能区：逸散性损耗标题与记录保存。 -->
    <header class="result-tabs">
      <div class="result-tab" :title="resultTitle"><span>{{ resultTitle }}</span></div>
      <div class="header-actions">

        <button class="primary" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </header>
    <main class="form-canvas">
      <div class="form-title">请输入计算参数</div>
      <div class="parameter-grid">

        <!-- 逸散性损耗参数：周期气量和预测变化率。 -->

          <label class="field"><span>上一周期储气库垫气量（10⁴m³）</span><input v-model="escapeForm.previousCushionGasVolume" placeholder="请输入" /></label>
          <label class="field"><span>本周期储气库可动垫气量（10⁴m³）</span><input v-model="escapeForm.movableCushionGasVolume" placeholder="请输入" /></label>
          <label class="field"><span>本周期储气库未动用库存量（10⁴m³）</span><input v-model="escapeForm.unusedInventoryVolume" placeholder="请输入" /></label>
          <label class="field"><span>储气库注气量（10⁴m³）</span><input v-model="escapeForm.injectionVolume" placeholder="请输入" /></label>
          <label class="field"><span>本周期预测垫气变化率（%）</span><input v-model="escapeForm.predictedChangeRate" placeholder="请输入" /></label>

      </div>

      <div class="calculation-actions"><button class="calculate" type="button" :disabled="calculating" @click="calculate">{{ calculating ? '计算中…' : '计 算' }}</button><button class="reset" type="button" @click="resetCalculationResult">重 置</button></div>
      <!-- 结果区只展示当前方法的后端返回值；尚无结果时显示占位符，不在模板内计算。 -->
      <section class="result-card"><h3>计算结果</h3><div class="result-line"><span>逸散性损耗气量（10⁴m³）：</span><strong>{{ formatResult(outputValue) || '—' }}</strong></div></section>
    </main>
  </section>
</template>

<style scoped>
/* 与单井产能工作台使用同一字体基线，表单、按钮和结果区均通过继承保持一致。 */
.geological-loss { height: 100%; min-width: 760px; overflow: auto; background: #fff; color: #202020; font-family: Arial, sans-serif; font-size: 13px; }
.result-tabs { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; justify-content: space-between; height: 34px; padding: 0 8px 0 0; border-bottom: 1px solid #e4e7ed; background: #fafafa; box-sizing: border-box; }
.result-tab { display: flex; align-self: stretch; align-items: center; justify-content: center; max-width: 430px; min-width: 190px; padding: 0 12px; overflow: hidden; border: 0; border-right: 1px solid #e4e7ed; background: #f4d000; color: #202020; font: 600 13px Arial, sans-serif; text-align: center; text-overflow: ellipsis; white-space: nowrap; box-sizing: border-box; }
.result-tab > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.header-actions, .calculation-actions { display: flex; gap: 10px; }
button { height: 27px; padding: 0 16px; border: 1px solid #c9cdd3; border-radius: 4px; background: #fff; color: #292929; font: inherit; cursor: pointer; }
button:hover { border-color: #b49a00; }
button:disabled { cursor: wait; opacity: .65; }
button.primary { min-width: 74px; border-color: #202020; background: #202020; color: #fff; }
.form-canvas { padding: 20px 18px 34px; }
.form-title { margin-bottom: 20px; font-size: 13px; font-weight: 600; }
.parameter-grid { display: grid; grid-template-columns: repeat(4, minmax(170px, 1fr)); gap: 20px 24px; }
.field { display: grid; gap: 8px; min-width: 0; color: #333; }
.field input, .field select { width: 100%; height: 34px; padding: 0 11px; border: 1px solid #d4d7dc; border-radius: 4px; background: #fff; box-sizing: border-box; color: #303133; font: inherit; outline: none; }
.field input:focus, .field select:focus { border-color: #b49a00; box-shadow: 0 0 0 2px rgba(244,208,0,.14); }
.import-note { margin-top: 14px; color: #777; font-size: 12px; }
.calculation-actions { margin-top: 24px; }
.calculation-actions button { height: 32px; }
.calculate { min-width: 72px; border-color: #d5b900; background: #f4d000; }
.reset { min-width: 72px; }
.result-card { min-height: 142px; margin-top: 36px; padding: 18px 18px 26px; border: 1px solid #ececec; border-radius: 4px; background: #f5f5f5; box-sizing: border-box; }
.result-card h3 { margin: 0 0 24px; font-size: 13px; }
.result-line { display: flex; align-items: baseline; gap: 6px; color: #333; }
.result-line strong { font-size: 15px; font-weight: 500; }
@media (max-width: 1250px) { .parameter-grid { grid-template-columns: repeat(3, minmax(170px, 1fr)); } }
@media (max-width: 920px) { .parameter-grid { grid-template-columns: repeat(2, minmax(170px, 1fr)); } }
</style>
