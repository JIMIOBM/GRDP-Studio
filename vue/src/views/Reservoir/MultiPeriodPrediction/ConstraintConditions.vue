<script setup>
import { computed, reactive, ref, watch, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { validateConstraintForm } from '@/api/reservoirPredictionApi'
import { storageCatalogApi } from '@/api/storageCatalog'
import { erosionApi } from '@/api/wellboreErosion'
import { sandProductionApi } from '@/api/sandProduction'
import { liquidLoadingApi } from '@/api/wellboreRisk'
import {
    getReservoirPredictionContext, originalConstraintForm, copyConstraintForm
} from './reservoirPredictionContext'
import {
    loadExistingStorageWells, calculateStorageConstraints, aggregateStorageBoundaries
} from './constraintWellRunner'
import { reservoirConstraintRecordApi } from '@/api/reservoirConstraintRecordApi'

const props = defineProps({
    projectId: { type: [Number, String], required: true },
    gasReservoirId: { type: [Number, String], required: true },
    storageId: { type: [Number, String], required: true },
    storageName: { type: String, default: '' },
    constraintRecordId: { type: [Number, String], default: null }
})
const emit = defineEmits(['constraints-calculated', 'constraints-invalidated'])
const context = computed(() => getReservoirPredictionContext(
    props.projectId, props.gasReservoirId, props.storageId
))
const formData = reactive(context.value.constraintDraft
    ? copyConstraintForm(context.value.constraintDraft) : originalConstraintForm())
if (context.value.objectiveQ != null && context.value.objectiveQ !== '') {
    formData.otherData.targetProduction = context.value.objectiveQ
}
const isCalculating = ref(false)
const progressText = ref('')
const calcError = ref('')
const calculationResults = ref(context.value.constraintWellResults || [])
const boundarySummary = ref(context.value.constraintBoundaries || null)
const calculatedAt = ref('')
const totalWells = computed(() => calculationResults.value.length)
const completeWells = computed(() => calculationResults.value.filter(well => well.complete).length)
let runVersion = 0
let switchingScope = false
const constraintSaving = ref(false)
const currentRecordId = ref('')
const currentRecordName = ref('')
const savedRecordLoading = ref(false)
const savedRecordError = ref('')
let recordLoadVersion = 0

// 已保存子节点的 prop 是编辑对象的权威 ID；不能因为详情 GET 失败而变成新增。
function parseConstraintRecordId(value) {
    if (value === null || value === undefined || String(value).trim() === '') return null
    const id = Number(value)
    return Number.isSafeInteger(id) && id > 0 ? id : null
}

function resolveConstraintRecordToSave() {
    const openedId = parseConstraintRecordId(props.constraintRecordId)
    const loadedId = parseConstraintRecordId(currentRecordId.value)
    if (props.constraintRecordId != null && String(props.constraintRecordId).trim() &&
        !openedId) {
        throw new Error('当前约束子节点的记录 ID 不合法，不能保存')
    }
    if (openedId && loadedId !== openedId) {
        throw new Error(`正在修改约束记录 ${openedId}，但它尚未成功加载；已阻止误新增其它节点`)
    }
    return openedId ?? loadedId
}

// API 的包装层有时返回 Axios data 或 Spring Boot 统一响应对象。
function unwrapSavedConstraintWriteResponse(raw) {
    let value = raw
    for (let depth = 0; depth < 5; depth++) {
        if (!value || typeof value !== 'object' || Array.isArray(value)) break
        if (parseConstraintRecordId(value.id)) return value
        value = value.record ?? value.data ?? value.result
    }
    throw new Error('保存接口没有返回正确的记录 ID')
}

function buildConstraintRecordPayload() {
    const active = context.value
    if (!active.constraintDraft || !active.constraintBoundaries) {
        throw new Error('缺少已应用的四项边界条件，请先计算并应用约束后再保存')
    }
    const boundaries = active.constraintBoundaries || boundarySummary.value
    if (!boundaries?.complete) throw new Error('约束边界未完成，不能保存')
    // 后端 DTO 读取 title() 而不是 name()；历史记录需原样保留名称。
    const recordId = resolveConstraintRecordToSave()
    const recordTitle = currentRecordName.value.trim()
    if (recordId && !recordTitle) {
        throw new Error('当前历史约束记录缺少原名称，请先重新打开该节点')
    }
    return {
        id: recordId,
        title: recordTitle || null,
        name: recordTitle || null, // 保留已有前端 API 对 name 的兼容字段
        projectId: Number(props.projectId),
        gasReservoirId: Number(props.gasReservoirId),
        storageId: Number(props.storageId),
        state: 'CALCULATED',
        formJson: active.constraintDraft,
        wellResultsJson: active.constraintWellResults || calculationResults.value || [],
        boundariesJson: boundaries
    }
}


async function handleSaveConstraint() {
    if (constraintSaving.value || savedRecordLoading.value || isCalculating.value) return
    let payload
    try { payload = buildConstraintRecordPayload() } catch (error) {
        ElMessage.warning(error?.message || '当前约束条件不能保存')
        return
    }
    constraintSaving.value = true
    const saveContext = context.value
    const saveRecordId = props.constraintRecordId
    try {
        const idToUpdate = payload.id
        // 已保存节点始终 PUT 该 ID；只有真正的新建表单才允许 POST。
        const response = idToUpdate
            ? await reservoirConstraintRecordApi.update(idToUpdate, payload)
            : await reservoirConstraintRecordApi.save(payload)
        const saved = unwrapSavedConstraintWriteResponse(response)
        const savedId = parseConstraintRecordId(saved.id)
        if (idToUpdate && savedId !== idToUpdate) {
            throw new Error(`更新的记录 ID 发生变化（原 ${idToUpdate}，返回 ${savedId}），请检查后端，不能当作新节点`)
        }
        // 修改计算数据不是“重命名”；标题应始终沿用该历史节点的原名称。
        const savedTitle = idToUpdate
            ? payload.title
            : String(saved.title || saved.name || payload.title || `约束条件${savedId}`)
        if (idToUpdate) {
            const returnedTitle = saved.title ?? saved.name
            if (returnedTitle != null && String(returnedTitle).trim() !== savedTitle) {
                throw new Error(`后端把“${savedTitle}”错误地改名为“${returnedTitle}”，请部署后端标题保护修复`)
            }
        }
        if (saveContext === context.value && String(saveRecordId ?? '') === String(props.constraintRecordId ?? '')) {
            currentRecordId.value = String(savedId)
            currentRecordName.value = savedTitle
        }
        saveContext.constraintRecordId = savedId
        window.dispatchEvent(new CustomEvent('grdp:reservoir-constraints-saved', {
            detail: {
                scopeKey: `${payload.projectId}:${payload.gasReservoirId}:${payload.storageId}`,
                id: savedId,
                recordName: savedTitle,
                storageId: payload.storageId,
                projectId: payload.projectId,
                gasReservoirId: payload.gasReservoirId,
                action: idToUpdate ? 'updated' : 'created'
            }
        }))
        ElMessage.success(idToUpdate
            ? `约束条件已更新：${savedTitle}（记录 ID：${savedId}）`
            : `约束条件已新建：${savedTitle}`)
    } catch (error) {
        ElMessage.error('保存失败：' + (error?.message || '未知错误'))
    } finally {
        constraintSaving.value = false
    }
}

// 使用详情真实字段来识别 payload，而不是无条件取 response.data。
// 兼容 axios、{data:{...}}、{data:{record:{...}}} 和直接返回的数据库行。
function unwrapSavedConstraintDetail(raw) {
    let value = raw
    const visited = new Set()
    for (let depth = 0; depth < 6; depth++) {
        if (!value || typeof value !== 'object' || Array.isArray(value)) break
        if (value.formJson != null || value.form_json != null || value.draft != null) {
            return value
        }
        if (value.success === false || (Number.isFinite(Number(value.code)) && Number(value.code) >= 400)) {
            throw new Error(value.msg || value.message || '后端拒绝读取约束记录')
        }
        if (visited.has(value)) break
        visited.add(value)
        value = value.record ?? value.data
    }
    const fields = raw && typeof raw === 'object' ? Object.keys(raw).join(', ') : '(无)'
    throw new Error(`约束详情未返回 formJson/form_json，顶层返回字段：${fields}`)
}

// 同一个 MySQL JSON 字段在不同后端序列化方式下可能返回 JSON 对象、
// JSON 字符串，甚至被重复序列化的 JSON 字符串；只做明确可验证的解析。
function parseSavedConstraintJson(value, label, expected) {
    if (value == null) return null
    let parsed = value
    for (let depth = 0; depth < 3 && typeof parsed === 'string'; depth++) {
        try { parsed = JSON.parse(parsed) }
        catch { throw new Error(`保存记录中的${label}不是有效 JSON`) }
    }
    if (expected === 'object' &&
        parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed
    if (expected === 'array' && Array.isArray(parsed)) return parsed
    throw new Error(`保存记录中的${label}不是有效的${expected === 'array' ? '数组' : '对象'}`)
}

function storedConstraintObject(value, label) {
    return parseSavedConstraintJson(value, label, 'object')
}
function storedConstraintWells(value) {
    return value == null ? [] : parseSavedConstraintJson(value, '逐井结果', 'array')
}

watch(() => [
    props.constraintRecordId, props.projectId, props.gasReservoirId, props.storageId
], async ([recordId, projectId, gasReservoirId, storageId]) => {
    const version = ++recordLoadVersion
    savedRecordError.value = ''
    if (!recordId) {
        savedRecordLoading.value = false
        currentRecordId.value = ''
        currentRecordName.value = ''
        switchingScope = false
        return
    }

    // 避免旧计算任务在详情 GET 返回后覆盖刚刚载入的历史结果。
    runVersion++
    isCalculating.value = false
    savedRecordLoading.value = true
    switchingScope = true
    try {
        const response = await reservoirConstraintRecordApi.detail(recordId)
        if (version !== recordLoadVersion) return
        const detail = unwrapSavedConstraintDetail(response)

        if (detail.id != null && String(detail.id) !== String(recordId)) {
            throw new Error('接口返回了其它约束记录，已拒绝覆盖页面')
        }
        for (const [camel, snake, expected] of [
            ['projectId', 'project_id', projectId],
            ['gasReservoirId', 'gas_reservoir_id', gasReservoirId],
            ['storageId', 'storage_id', storageId]
        ]) {
            const actual = detail[camel] ?? detail[snake]
            if (actual != null && String(actual) !== String(expected)) {
                throw new Error('该约束记录不属于当前库，已拒绝加载')
            }
        }

        const draft = storedConstraintObject(
            detail.draft ?? detail.formJson ?? detail.form_json, '约束表单')
        const boundaries = storedConstraintObject(
            detail.boundaries ?? detail.boundariesJson ?? detail.boundaries_json, '约束边界')
        const wells = storedConstraintWells(
            detail.wellResults ?? detail.wellResultsJson ?? detail.well_results_json)
        if (!draft || !boundaries) throw new Error('保存记录缺少表单或四项边界')
        if (!draft.gasProperties || !draft.calcMethod || !draft.otherData) {
            throw new Error('保存记录的表单结构不完整，无法还原当时的输入')
        }
        if (!['liquidMin', 'erosionMax', 'sandMax', 'exportPressure']
            .every(key => boundaries[key] !== null && boundaries[key] !== undefined &&
                Number.isFinite(Number(boundaries[key])))) {
            throw new Error('保存记录缺少有效的积液、冲蚀、出砂或外输压力边界值')
        }

        const normalizedBoundaries = { ...boundaries }
        for (const key of ['liquidMin', 'erosionMax', 'sandMax',
            'exportPressure', 'productionMax']) {
            if (normalizedBoundaries[key] !== undefined &&
                normalizedBoundaries[key] !== null) {
                normalizedBoundaries[key] = Number(normalizedBoundaries[key])
            }
        }
        const clean = copyConstraintForm(draft)

        // 只有校验与解析全部成功，才一次性更新页面和共享 context。
        for (const group of ['gasProperties', 'calcMethod', 'otherData']) {
            for (const field of Object.keys(formData[group])) delete formData[group][field]
            Object.assign(formData[group], clean[group])
        }
        const active = context.value
        active.constraintDraft = clean
        active.constraintWellResults = wells
        active.constraintBoundaries = normalizedBoundaries
        active.constraintRecordId = Number(recordId)
        active.appliedAt = Date.now()
        calculationResults.value = wells
        boundarySummary.value = normalizedBoundaries
        calculatedAt.value = '已加载已保存记录'
        calcError.value = ''
        progressText.value = ''
        currentRecordId.value = String(recordId)
        currentRecordName.value = String(
            detail.title ?? detail.name ?? detail.recordName ?? `约束条件${recordId}`)
        emit('constraints-calculated', {
            projectId: Number(projectId), gasReservoirId: Number(gasReservoirId),
            storageId: Number(storageId),
            inputs: clean, calculatedLimits: wells,
            boundaries: normalizedBoundaries, source: '数据库已保存的库级约束记录'
        })
        ElMessage.success(`已恢复约束条件：${currentRecordName.value}`)
    } catch (error) {
        if (version === recordLoadVersion) {
            savedRecordError.value = `读取约束记录 ${recordId} 失败：${error?.message || '未知错误'}`
            ElMessage.error(savedRecordError.value)
            console.warn('[库约束条件] 历史记录恢复失败', error)
        }
    } finally {
        if (version === recordLoadVersion) {
            switchingScope = false
            savedRecordLoading.value = false
        }
    }
}, { immediate: true })

onBeforeUnmount(() => { runVersion++; recordLoadVersion++ })

function invalidate() {
    if (switchingScope) return
    runVersion++
    calcError.value = ''
    progressText.value = ''
    calculationResults.value = []
    boundarySummary.value = null
    calculatedAt.value = ''
    context.value.constraintDraft = null
    context.value.appliedAt = null
    context.value.constraintWellResults = null
    context.value.constraintBoundaries = null
    emit('constraints-invalidated')
}
watch(formData, invalidate, { deep: true, flush: 'sync' })
watch(() => context.value.objectiveQ, value => {
    if (switchingScope) return
    if (value != null && value !== '' && Number.isFinite(Number(value))) {
        switchingScope = true
        try { formData.otherData.targetProduction = String(value) }
        finally { switchingScope = false }
    }
})
watch(() => formData.otherData.targetProduction, value => {
    if (switchingScope) return
    if (value != null && value !== '' && Number.isFinite(Number(value))) {
        context.value.objectiveQ = String(value)
    }
})
watch(() => [props.projectId, props.gasReservoirId, props.storageId], () => {
    runVersion++
    isCalculating.value = false
    calcError.value = ''
    progressText.value = ''
    calculationResults.value = context.value.constraintWellResults || []
    boundarySummary.value = context.value.constraintBoundaries || null
    calculatedAt.value = ''
    const replacement = context.value.constraintDraft || originalConstraintForm()
    switchingScope = true
    try {
        const cleanReplacement = copyConstraintForm(replacement)
        for (const key of ['gasProperties', 'calcMethod', 'otherData']) {
            for (const field of Object.keys(formData[key])) delete formData[key][field]
            Object.assign(formData[key], cleanReplacement[key])
        }
    } finally { switchingScope = false }
})

async function handleCalculateConstraints() {
    if (isCalculating.value || savedRecordLoading.value) return
    const version = ++runVersion
    isCalculating.value = true
    calcError.value = ''
    calculationResults.value = []
    boundarySummary.value = null
    calculatedAt.value = ''
    progressText.value = '校验约束参数…'
    const projectId = props.projectId
    const gasReservoirId = props.gasReservoirId
    const storageId = props.storageId
    const activeContext = context.value
    // 新一轮计算启动后，旧的约束结果不再视为有效；防止失败时仍沿用旧值。
    activeContext.constraintDraft = null
    activeContext.appliedAt = null
    activeContext.constraintWellResults = null
    activeContext.constraintBoundaries = null
    emit('constraints-invalidated')
    try {
        validateConstraintForm(formData)
        const snapshot = copyConstraintForm(formData)
        progressText.value = '读取当前库已有井…'
        const wellNames = await loadExistingStorageWells({
            catalogApi: storageCatalogApi, projectId, gasReservoirId, storageId
        })
        if (version !== runVersion) return
        progressText.value = `准备对当前库 ${wellNames.length} 口井计算出砂、冲蚀、积液约束…`
        const wells = await calculateStorageConstraints({
            wellNames, projectId, gasReservoirId, form: snapshot,
            erosionApi, sandProductionApi, liquidLoadingApi,
            concurrency: 3,
            stillCurrent: () => version === runVersion,
            onProgress: (done, all) => { progressText.value = `已处理 ${done} / ${all} 口井` }
        })
        if (version !== runVersion) return
        calculationResults.value = wells
        calculatedAt.value = new Date().toLocaleString('zh-CN')
        const complete = wells.filter(row => row.complete).length
        if (complete !== wells.length) {
            // 允许检查部分结果，但不能把未完成的约束作为“已计算成功”传给方案生成。
            activeContext.constraintDraft = null
            activeContext.appliedAt = null
            activeContext.constraintWellResults = null
            activeContext.constraintBoundaries = null
            progressText.value = `已计算 ${complete} / ${wells.length} 口井，未完成项见下方原因`
            ElMessage.warning('部分单井约束缺少历史参数或接口失败，请补全后重新计算并保存')
            return
        }
        const boundaries = aggregateStorageBoundaries(wells, snapshot)
        activeContext.constraintDraft = snapshot
        activeContext.constraintWellResults = wells
        activeContext.constraintBoundaries = boundaries
        activeContext.appliedAt = Date.now()
        boundarySummary.value = boundaries
        progressText.value = `${wells.length} 口井的三类约束均完成，已形成四项库级边界值`
        emit('constraints-calculated', {
            projectId: Number(projectId), gasReservoirId: Number(gasReservoirId),
            storageId, ...snapshot, inputs: snapshot,
            calculatedLimits: wells, boundaries, source: '单井出砂/冲蚀/积液真实计算接口'
        })
        ElMessage.success('约束计算完成，保存后可在方案生成页面选择')
    } catch (error) {
        if (version !== runVersion) return
        calcError.value = error?.message || '约束条件计算失败'
        progressText.value = ''
        ElMessage.error(calcError.value)
    } finally {
        if (version === runVersion) isCalculating.value = false
    }
}

watch(() => [props.projectId, props.gasReservoirId, props.storageId], () => {
    currentRecordId.value = ''
    currentRecordName.value = ''
})


function displayValue(result) {
    return result?.available ? result.value.toFixed(4) : '—'
}
</script>

<template>
    <div class="constraint-conditions-workspace">
        <div class="result-tabs">
            <div class="result-tab">
                多周期预测 - 约束条件{{ storageName ? ` - ${storageName}` : '' }}
            </div>
        </div>
        <div class="content-area">
            <p v-if="currentRecordId && !savedRecordLoading" class="constraint-record-hint" role="status">
                当前编辑：{{ currentRecordName || '已保存约束条件' }}（记录 ID：{{ currentRecordId }}）。
                重新计算后点击“保存当前约束”将更新该记录，不会新建或自动更名。
            </p>
            <p v-if="savedRecordLoading" class="constraint-record-hint" role="status">
                正在从数据库恢复已保存的约束条件…
            </p>
            <p v-if="savedRecordError" class="constraint-error" role="alert">{{ savedRecordError }}</p>
            <fieldset class="field-section" :disabled="isCalculating || savedRecordLoading">
                <legend class="sec-label">气体性质</legend>
                <div class="parameter-grid cols-5">
                    <label class="field">
                        <span>天然气类型</span>
                        <select v-model="formData.gasProperties.gasType">
                            <option value="干气">干气</option>
                            <option value="湿气">湿气</option>
                            <option value="凝析气">凝析气</option>
                        </select>
                    </label>
                    <label class="field">
                        <span>天然气比重(dless)</span>
                        <input v-model="formData.gasProperties.dless" type="number" step="any" min="0">
                    </label>
                    <label class="field">
                        <span>H₂S摩尔百分含量(%)</span>
                        <input v-model="formData.gasProperties.h2sContent" type="number" step="any" min="0" max="100">
                    </label>
                    <label class="field">
                        <span>CO₂摩尔百分含量(%)</span>
                        <input v-model="formData.gasProperties.co2Content" type="number" step="any" min="0" max="100">
                    </label>
                    <label class="field">
                        <span>N₂摩尔百分含量(%)</span>
                        <input v-model="formData.gasProperties.n2Content" type="number" step="any" min="0" max="100">
                    </label>
                </div>
            </fieldset>

            <fieldset class="field-section" :disabled="isCalculating || savedRecordLoading">
                <legend class="sec-label">计算方法</legend>
                <div class="parameter-grid cols-3">
                    <label class="field">
                        <span>非烃气体修正方法</span>
                        <select v-model="formData.calcMethod.correctionMethod">
                            <option value="Wichert-Aziz 修正方法">Wichert-Aziz 修正方法</option>
                            <option value="Carr-Kobayashi-Burrous 修正方法">Carr-Kobayashi-Burrous 修正方法</option>
                        </select>
                    </label>
                    <label class="field">
                        <span>天然气偏差系数计算方法</span>
                        <select v-model="formData.calcMethod.deviationMethod">
                            <option value="Dranchuk-Abu-Kassem 方法">Dranchuk-Abu-Kassem 方法</option>
                            <option value="Dranchuk-Purvis-Robinson 方法">Dranchuk-Purvis-Robinson 方法</option>
                            <option value="Hall-Yarborough 方法">Hall-Yarborough 方法</option>
                        </select>
                    </label>
                    <label class="field">
                        <span>天然气粘度计算方法</span>
                        <select v-model="formData.calcMethod.viscosityMethod">
                            <option value="Lee-Gonzalez-Eakin 方法">Lee-Gonzalez-Eakin 方法</option>
                            <option value="Carr-Kobayashi-Burrous 方法">Carr-Kobayashi-Burrous 方法</option>
                            <option value="Sutton 方法">Sutton 方法</option>
                        </select>
                    </label>
                </div>
            </fieldset>

            <fieldset class="field-section" :disabled="isCalculating || savedRecordLoading">
                <legend class="sec-label">其它数据</legend>
                <div class="parameter-grid cols-3">
                    <label class="field">
                        <span>产能方程形式</span>
                        <select v-model="formData.otherData.productionEquationType">
                            <option value="压力形式">压力形式</option>
                            <option value="压力平方形式">压力平方形式</option>
                            <option value="拟压力形式">拟压力形式</option>
                        </select>
                    </label>
                    <label class="field">
                        <span>原始地层压力(MPa)</span>
                        <input v-model="formData.otherData.initialPressure" type="number" step="any" min="0">
                    </label>
                    <label class="field">
                        <span>地层温度(°C)</span>
                        <input v-model="formData.otherData.reservoirTemperature" type="number" step="any">
                    </label>
                    <label class="field">
                        <span>物质平衡方程类型</span>
                        <select v-model="formData.otherData.materialBalanceType">
                            <option value="定容气藏">定容气藏</option>
                            <option value="水驱气藏">水驱气藏</option>
                        </select>
                    </label>
                    <label class="field">
                        <span>井组目标产量(10⁴m³/d)</span>
                        <input v-model="formData.otherData.targetProduction" type="number" step="any" min="0">
                    </label>
                    <label class="field">
                        <span>预测开始时间</span>
                        <input v-model="formData.otherData.forecastStartDate" type="date">
                    </label>
                    <label class="field">
                        <span>预测时间步长(d)</span>
                        <input v-model="formData.otherData.forecastTimeStep" type="number" step="1" min="1">
                    </label>
                    <label class="field">
                        <span>预测时间步数(d)</span>
                        <input v-model="formData.otherData.forecastTotalDays" type="number" step="1" min="1">
                    </label>
                    <label class="field">
                        <span>井口外输压力(MPa)</span>
                        <input v-model="formData.otherData.wellheadExportPressure" type="number" step="any" min="0">
                    </label>
                    <label class="field">
                        <span>单井废弃产量(10⁴m³/d)</span>
                        <input v-model="formData.otherData.abandonmentRate" type="number" step="any" min="0">
                    </label>
                </div>
            </fieldset>

            <div class="form-actions">
                <button class="calculate-button" type="button" :disabled="isCalculating || constraintSaving || savedRecordLoading"
                    @click="handleCalculateConstraints">
                    {{ isCalculating ? '计算中…' : '计算约束' }}
                </button>
                <button class="calculate-button" type="button" :disabled="isCalculating || constraintSaving || savedRecordLoading"
                    @click="handleSaveConstraint">
                    {{ constraintSaving ? '保存中…' : '保存当前约束' }}
                </button>
                <span class="help-text">使用当前库已有井的出砂、冲蚀、积液计算资料，不需要额外填写井筒参数。</span>
            </div>
            <p v-if="progressText" class="progress-text" aria-live="polite">{{ progressText }}</p>
            <p v-if="calcError" class="constraint-error" role="alert">{{ calcError }}</p>

            <div v-if="boundarySummary" class="boundary-summary" role="status">
                <div class="boundary-summary-title">用于方案生成的库级边界条件</div>
                <div class="boundary-summary-grid">
                    <div class="boundary-summary-item">
                        <span>积液下限</span>
                        <strong>{{ boundarySummary.liquidMin.toFixed(4) }}</strong>
                        <small>10⁴m³/d · 最低气量</small>
                    </div>
                    <div class="boundary-summary-item">
                        <span>冲蚀上限</span>
                        <strong>{{ boundarySummary.erosionMax.toFixed(4) }}</strong>
                        <small>10⁴m³/d · 最高气量</small>
                    </div>
                    <div class="boundary-summary-item">
                        <span>出砂上限</span>
                        <strong>{{ boundarySummary.sandMax.toFixed(4) }}</strong>
                        <small>10⁴m³/d · 最高气量*</small>
                    </div>
                    <div class="boundary-summary-item">
                        <span>外输压力</span>
                        <strong>{{ boundarySummary.exportPressure.toFixed(4) }}</strong>
                        <small>MPa · 固定设定值</small>
                    </div>
                </div>
                <p class="boundary-summary-note">
                    按 {{ boundarySummary.wellCount }} 口井分别汇总；考虑逐井出砂和冲蚀的共同限制后，
                    井组可用上限为 {{ Number.isFinite(boundarySummary.productionMax) ? boundarySummary.productionMax.toFixed(4) : '—' }} (10⁴m³/d)。
                    保存当前约束后，可在方案生成页面按记录名称选择；若只选择部分井，将使用该记录的逐井结果重新汇总。
                    总图气量下界取积液下限与外输压力数值的较大值，上界取冲蚀上限与出砂上限的较小值。
                    *出砂限制按当前接口“临界出砂气量为上限”的约定处理，需与业务模型确认。
                </p>
            </div>

            <div v-if="calculationResults.length" class="results-wrap">
                <div class="results-heading">
                    <strong>当前库井筒约束计算结果</strong>
                    <span>已完成 {{ completeWells }} / {{ totalWells }} 口井</span>
                    <span v-if="calculatedAt">计算时间：{{ calculatedAt }}</span>
                </div>
                <div class="table-scroll">
                    <table class="result-table">
                        <thead>
                            <tr>
                                <th>井名</th>
                                <th>出砂临界气量（10⁴m³/d）</th>
                                <th>冲蚀临界气量（10⁴m³/d）</th>
                                <th>积液临界气量（10⁴m³/d）</th>
                                <th>计算状态</th>
                            </tr>
                        </thead>
                        <tbody>
                            <tr v-for="well in calculationResults" :key="well.wellName">
                                <td class="well-name">{{ well.wellName }}</td>
                                <td>
                                    <strong v-if="well.sand.available">{{ displayValue(well.sand) }}</strong>
                                    <span v-else class="missing-value" :title="well.sand.reason">—（{{ well.sand.reason
                                        }}）</span>
                                </td>
                                <td>
                                    <strong v-if="well.erosion.available">{{ displayValue(well.erosion) }}</strong>
                                    <span v-else class="missing-value" :title="well.erosion.reason">—（{{
                                        well.erosion.reason }}）</span>
                                </td>
                                <td>
                                    <strong v-if="well.liquid.available">{{ displayValue(well.liquid) }}</strong>
                                    <span v-else class="missing-value" :title="well.liquid.reason">—（{{
                                        well.liquid.reason }}）</span>
                                </td>
                                <td><span :class="well.complete ? 'status-ok' : 'status-warn'">{{ well.complete ? '全部完成'
                                    : '存在未完成项' }}</span></td>
                            </tr>
                        </tbody>
                    </table>
                </div>
                <p class="result-hint">只有所有井的三类约束均计算成功时，才会标记为“已应用”。未完成的约束不会被当作零值或模拟结果。</p>
            </div>
        </div>
    </div>
</template>

<style lang="scss" scoped>
.constraint-conditions-workspace {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    background: #fff;
    color: #252525;
    font: 13px/1.5 "Microsoft YaHei", "Segoe UI", Arial, sans-serif;
}

.result-tabs {
    height: 34px;
    flex: 0 0 34px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    border-bottom: 1px solid #e4e7ed;
    background: #fafafa;
}

.result-tab {
    height: 34px;
    line-height: 34px;
    max-width: min(430px, 100%);
    padding: 0 12px;
    border-right: 1px solid #e4e7ed;
    box-sizing: border-box;
    background: #f4d000;
    color: #202020;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 13px;
}

.content-area {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 16px 24px 24px;
    background: #fff;
}

.section-title {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0 0 12px;
    color: #1f2937;
    font-size: 15px;
    font-weight: 700;
    line-height: 22px;
    letter-spacing: 0.2px;
}

.section-title::before {
    width: 4px;
    height: 15px;
    flex: 0 0 4px;
    border-radius: 2px;
    background: #d5b900;
    content: '';
}

.results-title {
    margin-top: 24px;
}

.field-section {
    border: 0;
    padding: 0;
    margin: 0 0 12px;
    min-width: 0;
}

.sec-label {
    width: 100%;
    height: 22px;
    padding: 0;
    margin: 8px 0 7px;
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 500;
    font-size: 13px;
    color: #252525;
}

.sec-label::after {
    content: '';
    height: 1px;
    flex: 1;
    background: #999;
}

.parameter-grid {
    display: grid;
    gap: 8px;

    &.cols-1 {
        grid-template-columns: 1fr;
    }

    &.cols-3 {
        grid-template-columns: repeat(3, 1fr);
    }

    &.cols-4 {
        grid-template-columns: repeat(4, 1fr);
    }

    &.cols-5 {
        grid-template-columns: repeat(5, 1fr);
    }
}

.field {
    display: block;
    min-width: 0;
    margin-bottom: 0;
}

.field span {
    display: block;
    margin-bottom: 3px;
    color: #252525;
    font-size: 12px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.field input,
.field select {
    display: block;
    width: 100%;
    min-width: 0;
    height: 24px;
    padding: 0 8px;
    box-sizing: border-box;
    border: 1px solid #aaa;
    border-radius: 3px;
    background: #fff;
    color: #333;
    font: inherit;
    font-size: 12px;
}

.field input:focus-visible,
.field select:focus-visible {
    outline: 1px solid #d5b900;
    outline-offset: 1px;
}

.form-actions {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 16px;
}

.calculate-button {
    min-width: 110px;
    height: 30px;
    padding: 0 22px;
    border: 1px solid #d5b900;
    border-radius: 4px;
    background: #f4d000;
    color: #222;
    font: inherit;
    font-weight: 500;
    cursor: pointer;
}

.calculate-button:hover:not(:disabled) {
    background: #ffe033;
}

.calculate-button:disabled {
    opacity: 0.6;
    cursor: wait;
}

.help-text,
.constraint-note,
.result-hint {
    margin: 12px 0 0;
    color: #909399;
    font-size: 12px;
}

.calc-notice {
    margin: 8px 0 0;
    padding: 8px 12px;
    color: #a56a15;
    font-size: 12px;
    line-height: 1.6;
    background: #fdf6ec;
    border: 1px solid #faecd8;
    border-radius: 4px;
}

.progress-text {
    color: #536577;
    font-size: 13px;
    margin: 10px 0;
}

.constraint-error {
    margin: 8px 0 0;
    padding: 8px 12px;
    color: #d9534f;
    font-size: 12px;
    line-height: 1.6;
    background: #fdf2f2;
    border: 1px solid #f5d6d6;
    border-radius: 4px;
}

/* 库级四个单值边界，下方保留逐井明细供核对 */
.boundary-summary {
    margin-top: 18px;
    border: 1px solid #e3d8a1;
    border-radius: 4px;
    padding: 12px;
    background: #fffdf4;
}

.boundary-summary-title {
    font-weight: 600;
    font-size: 14px;
    margin-bottom: 10px;
}

.boundary-summary-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 10px;
}

.boundary-summary-item {
    background: white;
    border: 1px solid #eee8cf;
    padding: 10px;
    display: flex;
    flex-direction: column;
    gap: 5px;
}

.boundary-summary-item span,
.boundary-summary-item small {
    color: #666;
    font-size: 12px;
}

.boundary-summary-item strong {
    color: #293342;
    font-size: 19px;
    font-variant-numeric: tabular-nums;
}

.boundary-summary-note {
    font-size: 12px;
    color: #706849;
    margin: 10px 0 0;
}

@media (max-width: 1000px) {
    .boundary-summary-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
    }
}

.results-wrap {
    margin-top: 20px;
}

.results-heading {
    display: flex;
    gap: 14px;
    align-items: baseline;
    flex-wrap: wrap;
    margin-bottom: 12px;
}

.results-heading strong {
    font-size: 15px;
    font-weight: 700;
}

.results-heading span {
    color: #909399;
    font-size: 12px;
}

.table-scroll {
    width: 100%;
    overflow-x: auto;
}

.result-table {
    width: 100%;
    border-collapse: collapse;
    min-width: 800px;
    text-align: left;
    font-size: 13px;
}

.result-table th {
    white-space: nowrap;
    color: #536273;
    background: #f3f5f7;
    font-weight: 600;
    padding: 11px 12px;
    border-bottom: 1px solid #e2e5e9;
}

.result-table td {
    padding: 11px 12px;
    border-bottom: 1px solid #e2e5e9;
    vertical-align: top;
}

.result-table tr:hover td {
    background: #fbfbf9;
}

.result-table .well-name {
    font-weight: 600;
}

.result-table strong,
.status-ok {
    color: #176e57;
}

.missing-value {
    color: #aa5a4c;
    display: inline-block;
    max-width: 230px;
}

.status-warn {
    color: #a5681f;
}

.result-hint {
    margin: 13px 0;
}

@media (max-width: 1400px) {
    .parameter-grid {
        grid-template-columns: repeat(2, 1fr) !important;
    }
}

@media (max-width: 900px) {
    .content-area {
        padding: 14px 16px 24px;
    }

    .parameter-grid {
        grid-template-columns: 1fr !important;
    }
}

.record-bar {
    margin: 0 0 14px;
    padding: 12px 14px;
    border: 1px solid #e4e7ed;
    border-radius: 4px;
    background: #fafbfc;
}

.record-bar-title {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 8px;
}

.record-bar-title strong {
    font-size: 14px;
}

.record-bar-title span {
    color: #909399;
    font-size: 12px;
}

.record-list {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin-bottom: 10px;
    max-height: 180px;
    overflow-y: auto;
}

.record-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 6px 10px;
    border: 1px solid #eceef1;
    border-radius: 3px;
    background: #fff;
}

.record-item.active {
    border-color: #d5b900;
    background: #fffdf4;
}

.record-name {
    border: 0;
    background: none;
    text-align: left;
    cursor: pointer;
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 0;
}

.record-name strong {
    color: #293342;
    font-size: 13px;
}

.record-name small {
    color: #909399;
    font-size: 11px;
}

.record-ops {
    display: flex;
    gap: 6px;
}

.record-ops button,
.record-secondary {
    height: 24px;
    padding: 0 10px;
    border: 1px solid #d0d5dc;
    border-radius: 3px;
    background: #fff;
    color: #444;
    font: inherit;
    font-size: 12px;
    cursor: pointer;
}

.record-ops button:hover,
.record-secondary:hover {
    border-color: #d5b900;
    color: #b39b00;
}

.record-actions {
    display: flex;
    gap: 8px;
    align-items: center;
}
</style>