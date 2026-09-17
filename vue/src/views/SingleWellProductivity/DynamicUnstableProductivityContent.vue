<!-- 不稳定流复用稳定流的布局，但计算、历史记录和数据库表均走独立后端接口。 -->
<script setup>
import DynamicProductivityContent from './DynamicProductivityContent.vue'

defineProps({
  wellName: { type: String, default: '' },
  projectId: { type: Number, required: true },
  gasReservoirId: { type: Number, required: true },
  pvtTableOptions: { type: Array, default: () => [] },
  pvtRecords: { type: Array, default: () => [] },
  unstableId: { type: Number, default: null },
  autoCalculate: { type: Boolean, default: false }
})

defineEmits(['saved', 'record-missing', 'initial-calculated'])
</script>

<template>
  <DynamicProductivityContent
    :well-name="wellName"
    :project-id="projectId"
    :gas-reservoir-id="gasReservoirId"
    :pvt-table-options="pvtTableOptions"
    :pvt-records="pvtRecords"
    :stable-id="unstableId"
    :auto-calculate="autoCalculate"
    flow-regime="unstable"
    @saved="$emit('saved', $event)"
    @record-missing="$emit('record-missing', $event)"
    @initial-calculated="$emit('initial-calculated', $event)"
  />
</template>
