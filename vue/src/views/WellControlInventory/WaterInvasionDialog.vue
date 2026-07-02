cat > /mnt/user-data/outputs/WaterInvasionDialog.vue << 'VEOF'
<script setup>
/**
 * WaterInvasionDialog.vue
 * 水侵动态分析对话框（仿「流动平衡」弹窗风格）
 *
 * 路径：src/views/WellControlInventory/WaterInvasionDialog.vue
 */
import { ref, computed, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { wellApi, waterInvasionApi, parametersApi, nodeApi } from '@/api/docker'

/* ===== Props / Emits ===== */
const props = defineProps({
  visible:        { type: Boolean,          default: false },
  projectId:      { type: [Number, String], default: 1 },
  gasReservoirId: { type: [Number, String], default: 1 },
  analysisType:   { type: Number,           default: 1 },
  nodeType:       { type: Number,           default: 14 }
})
const emit = defineEmits(['update:visible', 'confirm'])

const close = () => emit('update:visible', false)

/* ===== 顶部控制参数 ===== */
const params = ref({
  useActualStaticPressure:  true,
  enableWaterGasRatioLimit: false,
  waterGasRatioLimit:       0
})

/* ===== 井表格状态 ===== */
const loading        = ref(false)
const wells          = ref([])
const fields         = ref([])
const keyword        = ref('')
const tableRef       = ref()
const selectedWells  = ref([])
const confirming     = ref(false)

const columns = computed(() =>
    fields.value.filter(f => f.name_cn && f.name_cn.trim() !== '')
)

const filteredWells = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return wells.value
  return wells.value.filter(w => String(w.wellName ?? '').toLowerCase().includes(kw))
})

/* ===== 拉取默认水气比上限 ===== */
const fetchMinWaterGasRatio = async () => {
  try {
    const res = await parametersApi.getMinWaterGasRatio(props.projectId, props.gasReservoirId)
    params.value.waterGasRatioLimit = res.data?.vaule ?? 0
  } catch (e) {
    console.warn('获取生产水气比上限失败', e)
  }
}

/* ===== 拉取井列表 ===== */
const loadWells = async () => {
  loading.value = true
  try {
    const res = await wellApi.getWells(props.projectId, props.gasReservoirId)
    wells.value  = res.data?.wells  || []
    fields.value = res.data?.fields || []
    await nextTick()
    wells.value.forEach(row => tableRef.value?.toggleRowSelection(row, true))
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '井列表加载失败')
  } finally {
    loading.value = false
  }
}

watch(
    () => props.visible,
    val => {
      if (val) {
        keyword.value = ''
        fetchMinWaterGasRatio()
        loadWells()
      }
    }
)

const onSelectionChange = rows => { selectedWells.value = rows }
const clearFilter       = ()   => { keyword.value = '' }
const indicatorFilter   = ()   => ElMessage.info('指标过滤功能待接入')
const createSingleWell  = ()   => ElMessage.info('创建单井分析功能待接入')

/* ===== 轮询逻辑（修复版）===== */

/**
 * 修复了两个原始 bug：
 *
 * Bug 1 — 先查后等：原来 setTimeout 在 check 之后，
 *   第一轮（i=1）是"立即查询"，如果上次分析已有数据就立刻返回旧结果，
 *   第二次及之后的分析全部拿到的是旧数据。
 *   修复：把 setTimeout 移到查询前（先等再查）。
 *
 * Bug 2 — 不区分新旧数据：只检查 subNodes.length > 0，
 *   没有验证本次选中的井是否真的出现在结果里。
 *   修复：同时检查所有选中的井名都存在于 subNodes 中。
 *
 * 额外改进：
 *   - POST 之前先快照当前 subNodes（beforeSubNodes），
 *     用于区分"新增井"和"重新分析同一批井"两种场景。
 *   - 新增井场景：等所有选中的井名出现在 subNodes 里。
 *   - 重新分析场景：subNodes 已包含这批井，先等一个完整周期，
 *     再检查（给后端至少 intervalMs 的运算时间）。
 */
const pollNodeResult = async (beforeSubNodes = [], maxRetries = 20, intervalMs = 1500) => {
  const selectedNames = selectedWells.value.map(w => w.wellName)

  // 判断本次选中的井里是否有之前不存在的（新增井场景 vs 重新分析场景）
  const beforeTitleSet = new Set(beforeSubNodes.map(s => s.nodeTitle))
  const hasNewWells    = selectedNames.some(n => !beforeTitleSet.has(n))

  for (let i = 0; i < maxRetries; i++) {
    // ★ 关键修复：先等待，再查询
    //   原来是先查再等，导致第一轮立刻拿到旧数据就返回
    await new Promise(r => setTimeout(r, intervalMs))

    const nodeRes  = await nodeApi.getNode(props.projectId, props.gasReservoirId, props.nodeType)
    const subNodes = nodeRes.data?.node?.subNodes ?? []

    if (hasNewWells) {
      // 新增井场景：等所有选中的井名都出现在 subNodes 里
      const allPresent = selectedNames.every(name =>
          subNodes.some(s => s.nodeTitle === name)
      )
      if (allPresent) return nodeRes.data

    } else {
      // 重新分析场景：无法通过 subNodes 列表判断新旧
      // 已经等了一个完整的 intervalMs，认为后端有合理时间处理
      // 只要 subNodes 非空就返回（后续 WaterInvasionContent 会重新拉详细数据）
      if (subNodes.length > 0) return nodeRes.data
    }
  }

  throw new Error(
      `分析超时（${((maxRetries * intervalMs) / 1000).toFixed(0)}s），请手动刷新查看结果`
  )
}

/* ===== 确定提交 ===== */
const confirm = async () => {
  if (selectedWells.value.length === 0) {
    ElMessage.warning('请至少选择一口井')
    return
  }

  const payload = {
    gasReservoirId:          Number(props.gasReservoirId),
    projectId:               Number(props.projectId),
    analysisType:            props.analysisType,
    wellNames:               selectedWells.value.map(w => w.wellName),
    useActualStaticPressure: params.value.useActualStaticPressure,
    waterGasRatioLimit: params.value.enableWaterGasRatioLimit
        ? params.value.waterGasRatioLimit
        : -1
  }

  confirming.value = true
  try {
    // Step 0：POST 前先快照当前 subNodes，用于轮询时区分新旧数据
    let beforeSubNodes = []
    try {
      const snap = await nodeApi.getNode(props.projectId, props.gasReservoirId, props.nodeType)
      beforeSubNodes = snap.data?.node?.subNodes ?? []
    } catch { /* 第一次分析时后端没有节点，正常忽略 */ }

    // Step 1：POST 触发后端异步计算
    await waterInvasionApi.analyze(payload)
    ElMessage.info('分析计算中，请稍候…')

    // Step 2：轮询等待（先等后查，最多 30s）
    const raw = await pollNodeResult(beforeSubNodes)

    // Step 3：把井列表的 id 映射到 subNodes.nodeId 上
    const enrichedSubNodes = (raw.node?.subNodes ?? []).map(sub => {
      const matched = selectedWells.value.find(w => w.wellName === sub.nodeTitle)
      return matched ? { ...sub, nodeId: matched.id ?? sub.nodeId } : sub
    })
    const enriched = {
      ...raw,
      node: { ...raw.node, subNodes: enrichedSubNodes }
    }

    console.log('【水侵动态分析】节点树（已映射）：', enriched)
    emit('confirm', enriched)
    ElMessage.success('水侵动态分析完成')
    close()

  } catch (e) {
    ElMessage.error(e.message || '分析失败，请稍后重试')
    console.error('【水侵动态分析】失败', e)
  } finally {
    confirming.value = false
  }
}
</script>

<template>
  <el-dialog
      :model-value="visible"
      title="动态分析-水侵动态分析"
      width="900px"
      draggable
      destroy-on-close
      @close="close"
      class="water-invasion-dialog"
  >
    <!-- 顶部控制参数 -->
    <div class="params-bar">
      <div class="param-row">
        <el-checkbox v-model="params.useActualStaticPressure" class="dark-checkbox">
          优先使用实测静压
        </el-checkbox>
        <el-checkbox
            v-model="params.enableWaterGasRatioLimit"
            class="dark-checkbox"
            style="margin-left: 32px"
        >
          生产水气比上限(m³/10⁴m³)：
        </el-checkbox>
        <el-input-number
            v-model="params.waterGasRatioLimit"
            :disabled="!params.enableWaterGasRatioLimit"
            :controls="false"
            :precision="4"
            class="param-input"
        />
      </div>
    </div>

    <!-- 搜索 + 过滤按钮 -->
    <div class="filter-bar">
      <span class="filter-label">选择要执行动态分析的井：</span>
      <el-input
          v-model="keyword"
          placeholder="请输入内容"
          clearable
          :prefix-icon="Search"
          class="filter-search"
      />
      <el-button @click="indicatorFilter">指标过滤</el-button>
      <el-button @click="clearFilter">清除过滤</el-button>
    </div>

    <!-- 井表格 -->
    <el-table
        ref="tableRef"
        :data="filteredWells"
        v-loading="loading"
        border
        height="360"
        style="width: 100%"
        @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="46" align="center" />
      <el-table-column label="" width="52" align="center">
        <template #default="{ $index }">
          <span class="row-index">{{ $index + 1 }}</span>
        </template>
      </el-table-column>
      <el-table-column
          v-for="col in columns"
          :key="col.name"
          :prop="col.name"
          :label="col.name_cn"
          align="center"
          min-width="120"
      />
    </el-table>

    <!-- 底部按钮 -->
    <template #footer>
      <el-button @click="createSingleWell">创建单井分析</el-button>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="confirming" @click="confirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<style lang="scss" scoped>
:deep(.el-dialog__header) {
  background-color: #2d2d2d;
  margin-right: 0;
  padding: 13px 20px;
  border-radius: 4px 4px 0 0;
}
:deep(.el-dialog__title) {
  color: #ffffff;
  font-size: 14px;
  font-weight: 500;
  line-height: 1;
}
:deep(.el-dialog__headerbtn) {
  top: 0;
  height: 44px;
  display: flex;
  align-items: center;
  .el-icon { color: #a0a8b4; font-size: 16px; transition: color 0.2s; }
  &:hover .el-icon { color: #ffffff; }
}
:deep(.el-dialog__body)   { padding: 20px 24px 8px; }
:deep(.el-dialog__footer) { padding: 12px 24px 18px; border-top: 1px solid #ebeef5; }

.params-bar {
  margin-bottom: 14px;
  padding-bottom: 14px;
  border-bottom: 1px solid #ebeef5;
  .param-row {
    display: flex;
    align-items: center;
    margin-bottom: 12px;
    &:last-child { margin-bottom: 0; }
  }
  .param-input { width: 180px; }
  .dark-checkbox {
    flex-shrink: 0;
    :deep(.el-checkbox__label) { font-size: 13px; color: #303133; }
    :deep(.el-checkbox__inner) { border-color: #333; }
    :deep(.el-checkbox__input.is-checked .el-checkbox__inner),
    :deep(.el-checkbox__input.is-indeterminate .el-checkbox__inner) {
      background-color: #333;
      border-color: #333;
    }
    :deep(.el-checkbox__input.is-checked + .el-checkbox__label) { color: #303133; }
  }
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  .filter-label { font-size: 13px; color: #303133; white-space: nowrap; flex-shrink: 0; }
  .filter-search { width: 240px; margin-left: auto; }
}

:deep(.el-table__header-wrapper th.el-table__cell) {
  background-color: #f5f7fa;
  color: #606266;
  font-size: 13px;
  font-weight: 500;
}
:deep(.el-table__body-wrapper td.el-table__cell) {
  font-size: 13px;
  color: #303133;
  padding: 8px 0;
}

.row-index {
  color: #e6a23c;
  font-size: 13px;
  font-weight: 500;
}
</style>
VEOF
echo "done"
