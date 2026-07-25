<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { dataManagementApi } from '@/api/docker'

const props = defineProps({
  dataType: {
    type: String,
    required: true
  },
  wellName: {
    type: String,
    required: true
  },
  projectId: {
    type: [Number, String],
    required: true
  },
  gasReservoirId: {
    type: [Number, String],
    required: true
  }
})

const DATA_CONFIG = {
  wellhead: {
    title: '井头数据',
    keys: [
      'wellName',
      'xCoordinate',
      'yCoordinate',
      'kellyBushing',
      'measuredDepth',
      'trueVerticalDepth'
    ]
  },
  deviation: {
    title: '井斜数据',
    keys: [
      'wellName',
      'measuredDepth',
      'inclination',
      'azimuth',
      'xCoordinate',
      'yCoordinate',
      'zCoordinate',
      'xDeviation',
      'yDeviation',
      'trueVerticalDepth'
    ]
  },
  logging: {
    title: '测井数据',
    keys: [
      'wellName',
      'interpretationNumber',
      'stratigraphy',
      'topMeasuredDepth',
      'bottomMeasuredDepth',
      'thickness',
      'porosity',
      'permeability',
      'irreducibleWaterSaturation',
      'interpretationConclusion',
      'topVerticalDepth',
      'bottomVerticalDepth',
      'VvrticalThickness'
    ]
  }
}

const FALLBACK_FIELDS = {
  wellName: { name_cn: '井名', unit_label: '' },
  xCoordinate: { name_cn: '横坐标', unit_label: 'm' },
  yCoordinate: { name_cn: '纵坐标', unit_label: 'm' },
  kellyBushing: { name_cn: '补心海拔', unit_label: 'm' },
  measuredDepth: { name_cn: '测量深度', unit_label: 'm' },
  trueVerticalDepth: { name_cn: '真实垂直深度', unit_label: 'm' },
  inclination: { name_cn: '井斜角', unit_label: '°' },
  azimuth: { name_cn: '方位角', unit_label: '°' },
  zCoordinate: { name_cn: 'Z坐标', unit_label: 'm' },
  xDeviation: { name_cn: 'X方向偏移量', unit_label: 'm' },
  yDeviation: { name_cn: 'Y方向偏移量', unit_label: 'm' },
  interpretationNumber: { name_cn: '解释序号', unit_label: '' },
  stratigraphy: { name_cn: '层位', unit_label: '' },
  topMeasuredDepth: { name_cn: '储层顶部深度', unit_label: 'm' },
  bottomMeasuredDepth: { name_cn: '储层底部深度', unit_label: 'm' },
  thickness: { name_cn: '储层厚度', unit_label: 'm' },
  porosity: { name_cn: '储层孔隙度', unit_label: '%' },
  permeability: { name_cn: '储层渗透率', unit_label: 'mD' },
  irreducibleWaterSaturation: { name_cn: '储层束缚水饱和度', unit_label: '%' },
  interpretationConclusion: { name_cn: '解释结论', unit_label: '' },
  topVerticalDepth: { name_cn: '储层顶部垂直深度', unit_label: 'm' },
  bottomVerticalDepth: { name_cn: '储层底部垂直深度', unit_label: 'm' },
  VvrticalThickness: { name_cn: '储层垂直厚度', unit_label: 'm' }
}

const loading = ref(false)
const rows = ref([])
const responseFields = ref([])
let loadSequence = 0

const config = computed(() => DATA_CONFIG[props.dataType] || DATA_CONFIG.wellhead)
const tabTitle = computed(() => `${props.wellName} ${config.value.title}`)

const fields = computed(() => {
  const fieldMap = new Map(
    responseFields.value
      .filter(field => field?.name)
      .map(field => [field.name, field])
  )

  return config.value.keys.map(key => ({
    key,
    ...(FALLBACK_FIELDS[key] || { name_cn: key, unit_label: '' }),
    ...(fieldMap.get(key) || {})
  }))
})

const getFieldLabel = (field) => {
  const name = field.name_cn || field.key
  const unit = field.unit_label
  return unit ? `${name}(${unit})` : name
}

const loadData = async () => {
  const sequence = ++loadSequence
  loading.value = true

  try {
    let response
    if (props.dataType === 'wellhead') {
      response = await dataManagementApi.getWellHead(props.projectId, props.gasReservoirId)
    } else if (props.dataType === 'deviation') {
      response = await dataManagementApi.getWellDeviation(
        props.projectId,
        props.gasReservoirId,
        props.wellName
      )
    } else {
      response = await dataManagementApi.getLogInterpretation(
        props.projectId,
        props.gasReservoirId,
        props.wellName
      )
    }

    if (sequence !== loadSequence) return

    const payload = response?.data?.data ?? response?.data ?? response ?? {}
    const items = Array.isArray(payload.items)
      ? payload.items
      : Array.isArray(payload.rows)
        ? payload.rows
        : Array.isArray(payload)
          ? payload
          : []

    rows.value = props.dataType === 'wellhead'
      ? items.filter(item => String(item?.wellName) === String(props.wellName))
      : items
    responseFields.value = Array.isArray(payload.fields) ? payload.fields : []
  } catch (error) {
    if (sequence !== loadSequence) return
    rows.value = []
    responseFields.value = []
    ElMessage.error(error.response?.data?.message || error.message || `${config.value.title}加载失败`)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

watch(
  () => [props.dataType, props.wellName, props.projectId, props.gasReservoirId],
  loadData,
  { immediate: true }
)
</script>

<template>
  <section class="well-data-content">
    <div class="data-tabs">
      <div class="data-tab active">{{ tabTitle }}</div>
    </div>

    <div class="data-toolbar">
      <el-button size="small">导入</el-button>
    </div>

    <div class="data-table-wrap">
      <el-table
        v-loading="loading"
        :data="rows"
        border
        height="100%"
        empty-text="暂无数据"
        row-key="id"
      >
        <el-table-column type="index" label="序号" width="64" fixed />
        <el-table-column
          v-for="field in fields"
          :key="field.key"
          :prop="field.key"
          :label="getFieldLabel(field)"
          min-width="140"
          show-overflow-tooltip
        />
      </el-table>
    </div>
  </section>
</template>

<style scoped>
.well-data-content {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-width: 0;
  background: #fff;
  color: #303133;
}

.data-tabs {
  display: flex;
  flex: 0 0 31px;
  align-items: stretch;
  border-bottom: 1px solid #dcdfe6;
  background: #fff;
}

.data-tab {
  display: flex;
  align-items: center;
  padding: 0 13px;
  border-right: 1px solid #dcdfe6;
  font-size: 14px;
  white-space: nowrap;
}

.data-tab.active {
  background: #ffd800;
  color: #303133;
}

.data-toolbar {
  display: flex;
  flex: 0 0 39px;
  align-items: center;
  padding: 0 10px;
  border-bottom: 1px solid #ebeef5;
}

.data-table-wrap {
  flex: 1;
  min-height: 0;
  padding: 0 1px 1px;
}

:deep(.el-table) {
  --el-table-header-bg-color: #fff;
  --el-table-row-hover-bg-color: #f5f7fa;
  font-size: 13px;
}

:deep(.el-table th.el-table__cell) {
  height: 39px;
  color: #303133;
  font-weight: 400;
}

:deep(.el-table td.el-table__cell) {
  height: 39px;
}

:deep(.el-table .cell) {
  padding: 0 10px;
}
</style>
