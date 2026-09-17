<script setup>
import DynamicProductivityContent from './DynamicProductivityContent.vue'

/**
 * 理论稳定流与动态稳定流共用界面和旧平台计算适配层；storageMode 决定它只访问
 * theoretical-productivity 接口，从而让两套编号、默认参数和计算快照保持独立。
 */
defineProps({
  wellName: { type: String, default: '' },
  defaultWellType: { type: String, default: '' },
  projectId: { type: Number, required: true },
  gasReservoirId: { type: Number, required: true },
  pvtTableOptions: { type: Array, default: () => [] },
  pvtRecords: { type: Array, default: () => [] },
  stableId: { type: Number, default: null },
  resultData: { type: Object, default: null },
  autoCalculate: { type: Boolean, default: false }
})

const emit = defineEmits(['saved', 'record-missing', 'initial-calculated'])
</script>

<template>
  <DynamicProductivityContent
    storage-mode="theoretical"
    :well-name="wellName"
    :default-well-type="defaultWellType"
    :project-id="projectId"
    :gas-reservoir-id="gasReservoirId"
    :pvt-table-options="pvtTableOptions"
    :pvt-records="pvtRecords"
    :stable-id="stableId"
    :result-data="resultData"
    :auto-calculate="autoCalculate"
    @saved="emit('saved', $event)"
    @record-missing="emit('record-missing', $event)"
    @initial-calculated="emit('initial-calculated', $event)"
  />
</template>
