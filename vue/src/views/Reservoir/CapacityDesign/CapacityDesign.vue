<script setup>
/**
 * 库 → 库容设计 → 运行压力 / 库容参数。
 *
 * 交互约定：
 *  1) 一次只显示当前菜单项对应的**一个**参数，不再把六项一次性铺开；
 *  2) 输入框直接带出该项已保存的值，可直接改写后点「保存」写回档案，
 *     保存返回的值会留在输入框里；
 *  3) 清空输入框后再保存，表示把该项置回未填写。
 *
 * 六项参数始终整体提交：其余五项取输入框带出的当前值，
 * 因此单项录入不会影响其它已保存的参数。
 * 「孔隙体积」暂未实现，仍在 ReservoirWorkspaceContent 走占位分支。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { storageCapacityApi } from '@/api/storageCapacity'

const props = defineProps({
  reservoir: { type: Object, default: null },
  command: { type: Object, default: null }
})

const route = useRoute()
// projectId + gasReservoirId 共同限定原系统项目范围；storageId 才标识当前储气库。
const projectId = computed(() => Number(props.reservoir?.projectId ?? route.query.projectId))
const gasReservoirId = computed(() => Number(props.reservoir?.gasReservoirId ?? route.query.gasReservoirId))
const storageId = computed(() => Number(props.reservoir?.storageId ?? route.query.storageId))
const title = computed(() => `${props.reservoir?.label || '未选择库'}-库容设计`)

// 六项参数：单位口径与库级既有模块一致（压力 MPa，气量 10⁴m³）。
const ALL_FIELDS = [
  { key: 'upperLimitPressure', label: '上限压力', unit: 'MPa' },
  { key: 'lowerLimitPressure', label: '下限压力', unit: 'MPa' },
  { key: 'storageCapacity', label: '库容量', unit: '10⁴m³' },
  { key: 'workingGasVolume', label: '工作气量', unit: '10⁴m³' },
  { key: 'cushionGasVolume', label: '垫气量', unit: '10⁴m³' },
  { key: 'supplementaryCushionGasVolume', label: '补充垫气量', unit: '10⁴m³' }
]

// 菜单项名 → 参数字段：左侧库目录与顶部 Ribbon 的六个叶子项各对应一项。
const FIELD_KEY_BY_COMMAND = {
  上限压力: 'upperLimitPressure',
  下限压力: 'lowerLimitPressure',
  库容量: 'storageCapacity',
  工作气量: 'workingGasVolume',
  垫气量: 'cushionGasVolume',
  补充垫气量: 'supplementaryCushionGasVolume'
}

// 档案里的六个值：从后端读回、被导入覆盖、被输入框直接编辑。
const design = reactive(Object.fromEntries(ALL_FIELDS.map(field => [field.key, ''])))
const importedFileName = ref('')
const loading = ref(false)
const saving = ref(false)
const importInput = ref(null)
// 档案里是否已有该库的库容设计；没有记录时「清空」无意义，按钮置灰。
const hasSavedDesign = ref(false)

// active 拦截卸载后的响应；loadVersion 区分前后两次读取，避免切库时旧响应覆盖新库。
let active = true
let loadVersion = 0
onBeforeUnmount(() => { active = false; loadVersion++ })

// 当前菜单项对应的参数；未匹配到（例如直接进页面）时不显示任何输入框。
const activeField = computed(() => {
  const key = FIELD_KEY_BY_COMMAND[props.command?.name]
  return key ? ALL_FIELDS.find(field => field.key === key) : null
})
// 输入框直接绑定当前参数的档案值：读回、导入、保存后都会显示在框里；
// 用户编辑也直接写回该字段，切换菜单项时看到的就是该项自己的值。
const draft = computed({
  get: () => {
    const field = activeField.value
    return field ? String(design[field.key] ?? '') : ''
  },
  set: value => {
    const field = activeField.value
    if (field) design[field.key] = value
  }
})
// 输入框已带出当前值，提示词只说明该填哪一项。
const activeFieldPlaceholder = computed(() => {
  const field = activeField.value
  return field ? `请输入${field.label}` : ''
})
const unwrap = response => response?.data ?? response
// 空串表示「未填写」，提交时转成 null；非法数字返回 NaN 以便拦截。
const numericOrNull = value => {
  const text = String(value ?? '').trim()
  if (!text) return null
  const parsed = Number(text)
  return Number.isFinite(parsed) ? parsed : NaN
}

const resetDesign = () => {
  ALL_FIELDS.forEach(field => { design[field.key] = '' })
  importedFileName.value = ''
}

const fillDesign = input => {
  if (!input) return
  ALL_FIELDS.forEach(field => {
    const value = input[field.key]
    design[field.key] = value === null || value === undefined ? '' : String(value)
  })
  importedFileName.value = input.importedFileName || ''
}

const loadDesign = async () => {
  if (!(storageId.value > 0)) { resetDesign(); hasSavedDesign.value = false; return }
  const version = ++loadVersion
  loading.value = true
  try {
    const detail = unwrap(await storageCapacityApi.get(projectId.value, gasReservoirId.value, storageId.value))
    if (!active || version !== loadVersion) return
    resetDesign()
    // 未保存时后端 data 为空（Jackson 的 non_null 会让 data 键整个消失），
    // 信封对象没有 input 属性，因此必须按嵌套属性判断，不能写成 if (detail)。
    hasSavedDesign.value = Boolean(detail?.input)
    if (detail?.input) fillDesign(detail.input)
  } finally {
    if (active && version === loadVersion) loading.value = false
  }
}

// 提交前校验：格式、至少一项、压力大小关系、气量为负。
const validate = values => {
  for (const field of ALL_FIELDS) {
    if (Number.isNaN(values[field.key])) return `${field.label}必须是有效数字`
  }
  if (ALL_FIELDS.every(field => values[field.key] === null)) return '请至少填写一项库容设计参数'
  const upper = values.upperLimitPressure
  const lower = values.lowerLimitPressure
  if (lower !== null && lower <= 0) return '下限压力必须大于0'
  if (upper !== null && upper <= 0) return '上限压力必须大于0'
  if (upper !== null && lower !== null && upper <= lower) return '上限压力必须大于下限压力'
  if (['storageCapacity', 'workingGasVolume', 'cushionGasVolume', 'supplementaryCushionGasVolume']
    .some(key => values[key] !== null && values[key] < 0)) return '库容参数不能为负数'
  return ''
}

// 「保存」：把输入框里的当前值写回该项，再整体提交六项参数。
// 输入框已带出该项的档案值；把输入框清空再保存，表示把该项置回未填写。
const save = async () => {
  if (saving.value || loading.value) return
  if (!(storageId.value > 0)) { ElMessage.warning('请先选择具体储气库'); return }
  if (!activeField.value) { ElMessage.warning('请从顶部菜单选择要填写的参数'); return }

  const target = activeField.value
  const typed = numericOrNull(draft.value)
  const values = Object.fromEntries(ALL_FIELDS.map(field => [field.key, numericOrNull(design[field.key])]))
  if (typed !== null) values[target.key] = typed

  const hasAnything = ALL_FIELDS.some(field => values[field.key] !== null)
  if (typed === null && !hasAnything) { ElMessage.warning(`请输入${target.label}`); return }

  const message = validate(values)
  if (message) { ElMessage.warning(message); return }

  saving.value = true
  try {
    const saved = unwrap(await storageCapacityApi.save({
      projectId: projectId.value,
      gasReservoirId: gasReservoirId.value,
      storageId: storageId.value,
      input: { ...values, importedFileName: importedFileName.value || null }
    }))
    if (!active) return
    // 以服务端返回的值回填：输入框里留下的就是真正落库的数字。
    if (saved?.input) fillDesign(saved.input)
    hasSavedDesign.value = true
    ElMessage.success(typed !== null ? `${target.label}已保存` : '库容设计已保存')
  } finally {
    saving.value = false
  }
}

// 「清空」：删除该储气库的整条库容设计（六项全部回到未填写）。
// 清空属不可撤销操作，按项目既有约定用 ElMessageBox 二次确认。
const clearDesign = async () => {
  if (saving.value || loading.value) return
  if (!(storageId.value > 0)) { ElMessage.warning('请先选择具体储气库'); return }
  try {
    await ElMessageBox.confirm(
      '清空当前储气库的库容设计？已保存的六项参数与输入框里尚未保存的内容都会被清除，此操作不可撤销。',
      '清空库容设计',
      { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' }
    )
    await storageCapacityApi.remove(projectId.value, gasReservoirId.value, storageId.value)
    if (!active) return
    resetDesign()
    hasSavedDesign.value = false
    ElMessage.success('库容设计已清空')
  } catch (error) {
    // 用户点「取消」或关闭弹窗不是错误，静默返回。
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.response?.data?.msg || error?.message || '清空库容设计失败')
    }
  }
}

const openImport = () => importInput.value?.click()

// 只读取首个工作表：第一列为参数名称、第二列为数值，按名称匹配，不依赖列顺序。
// 导入只写入页面状态，仍需点「保存」才会落库。
const handleImportFile = async event => {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  try {
    const extension = file.name.split('.').pop()?.toLowerCase()
    if (!['xlsx', 'xls', 'csv'].includes(extension)) throw new Error('仅支持 .xlsx、.xls、.csv 表格文件')
    const XLSX = await import('xlsx')
    const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' })
    const sheet = workbook.Sheets[workbook.SheetNames[0]]
    const rows = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: '' })
      .filter(row => row.some(cell => String(cell).trim() !== ''))
    if (rows.length < 2) throw new Error('文件中没有可导入的参数行')
    const keyByLabel = new Map(ALL_FIELDS.map(field => [field.label, field.key]))
    const unknown = []
    let imported = 0
    for (const row of rows.slice(1)) {
      const label = String(row[0] ?? '').trim()
      if (!label) continue
      const key = keyByLabel.get(label)
      if (!key) { unknown.push(label); continue }
      const value = numericOrNull(row[1])
      if (value === null || Number.isNaN(value)) throw new Error(`「${label}」的数值无效`)
      design[key] = String(value)
      imported++
    }
    if (!imported) {
      throw new Error(`未识别到库容设计参数${unknown.length ? `（文件中的参数名：${unknown.join('、')}）` : ''}`)
    }
    importedFileName.value = file.name
    ElMessage.success(unknown.length
      ? `已导入 ${imported} 项参数（未识别：${unknown.join('、')}），请点「保存」写入`
      : `已导入 ${imported} 项参数，请点「保存」写入`)
  } catch (error) {
    ElMessage.error(error?.message || '导入失败')
  }
}

// 可选提示：库容量与「工作气量 + 垫气量」口径不一致时提醒，但不阻断保存。
const volumeHint = computed(() => {
  const capacity = numericOrNull(design.storageCapacity)
  const working = numericOrNull(design.workingGasVolume)
  const cushion = numericOrNull(design.cushionGasVolume)
  if ([capacity, working, cushion].some(value => value === null || Number.isNaN(value))) return ''
  if (capacity === 0) return ''
  const sum = working + cushion
  const deviation = Math.abs(capacity - sum) / Math.abs(capacity)
  if (deviation <= 0.01) return ''
  return `工作气量 + 垫气量 = ${sum}，与库容量 ${capacity} 相差 ${(deviation * 100).toFixed(1)}%，请确认口径`
})

// 切换菜单项无需清空输入框：draft 绑定的就是当前参数自己的值。
watch(storageId, async () => { resetDesign(); await loadDesign() })
onMounted(loadDesign)
</script>

<template>
  <section class="capacity-design">
    <header class="result-tabs">
      <div class="result-tab" :title="title"><span>{{ title }}</span></div>
      <div class="header-actions">
        <input ref="importInput" class="hidden-file" type="file" accept=".xlsx,.xls,.csv"
               @change="handleImportFile" />
        <button class="secondary" type="button" :disabled="saving || !activeField" @click="openImport">导 入</button>
        <!-- 清空整条库容设计；没有已保存记录时无意义，按钮置灰。 -->
        <button class="secondary danger" type="button"
                :disabled="saving || loading || !hasSavedDesign" @click="clearDesign">清 空</button>
      </div>
    </header>

    <main class="form-canvas">
      <p v-if="!(storageId > 0)" class="empty-hint">请先在左侧选择一个储气库。</p>
      <p v-else-if="!activeField" class="empty-hint">请从顶部菜单选择要填写的参数。</p>
      <template v-else>
        <!-- 只显示当前菜单项对应的一个参数。输入框直接带出该项已保存的值，可随时改写后保存；
             保存后以服务端返回的值回填，数字就留在输入框里。 -->
        <label class="field" :for="`capacity-input-${activeField.key}`">
          <span class="field-label">
            {{ activeField.label }}（{{ activeField.unit }}）
          </span>
          <input :id="`capacity-input-${activeField.key}`" v-model="draft"
                 :placeholder="activeFieldPlaceholder" inputmode="decimal" autocomplete="off"
                 @keyup.enter="save" />
        </label>

        <div class="submit-row">
          <button class="primary submit" type="button" :disabled="saving || loading" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>

        <p v-if="volumeHint" class="volume-hint">{{ volumeHint }}</p>
      </template>
    </main>
  </section>
</template>

<style scoped>
/* 与库级损耗评价页面使用同一套视觉基线：34px 页签、同样的按钮与提示样式。 */
.capacity-design { height: 100%; min-width: 520px; overflow: auto; background: #fff; color: #202020; font-family: Arial, sans-serif; font-size: 13px; }
.result-tabs { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; justify-content: space-between; height: 34px; padding: 0 8px 0 0; border-bottom: 1px solid #e4e7ed; background: #fafafa; box-sizing: border-box; }
.result-tab { display: flex; align-self: stretch; align-items: center; justify-content: center; max-width: 430px; min-width: 190px; padding: 0 12px; overflow: hidden; border-right: 1px solid #e4e7ed; background: #f4d000; color: #202020; font: 600 13px Arial, sans-serif; text-align: center; text-overflow: ellipsis; white-space: nowrap; box-sizing: border-box; }
.result-tab > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.header-actions { display: flex; align-items: center; gap: 10px; }
.hidden-file { display: none; }
button { height: 27px; padding: 0 16px; border: 1px solid #c9cdd3; border-radius: 4px; background: #fff; color: #292929; font: inherit; cursor: pointer; }
button:hover { border-color: #b49a00; }
button:disabled { cursor: wait; opacity: .65; }
button.primary { min-width: 74px; border-color: #202020; background: #202020; color: #fff; }
/* 危险操作配色沿用 PipelineCapacity 的既有约定（.danger{color:#bc504b}）。 */
button.danger { color: #bc504b; }
button.danger:hover:not(:disabled) { border-color: #bc504b; }
.form-canvas { padding: 28px 18px 34px; max-width: 640px; }
.field { display: grid; gap: 10px; min-width: 0; color: #333; }
.field-label { font-size: 13px; font-weight: 600; }
.field input { width: 100%; height: 34px; padding: 0 11px; border: 1px solid #d4d7dc; border-radius: 4px; background: #fff; box-sizing: border-box; color: #303133; font: inherit; outline: none; }
.field input:focus { border-color: #b49a00; box-shadow: 0 0 0 2px rgba(244,208,0,.14); }
.submit-row { margin-top: 22px; }
button.submit { height: 32px; min-width: 84px; }
.volume-hint { margin: 20px 0 0; padding: 8px 10px; border-left: 3px solid #f4d000; background: #f5f5f5; color: #333; font-size: 12px; }
.empty-hint { margin: 0; color: #909399; font-size: 13px; }
</style>
