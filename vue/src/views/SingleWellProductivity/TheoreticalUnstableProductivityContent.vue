<!-- 理论计算不稳定流复用成熟界面和计算模型，但使用理论计算专属接口与数据表。 -->
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
    storage-mode="theoretical"
    flow-regime="unstable"
    :well-name="wellName"
    :project-id="projectId"
    :gas-reservoir-id="gasReservoirId"
    :pvt-table-options="pvtTableOptions"
    :pvt-records="pvtRecords"
    :stable-id="unstableId"
    :auto-calculate="autoCalculate"
    @saved="$emit('saved', $event)"
    @record-missing="$emit('record-missing', $event)"
    @initial-calculated="$emit('initial-calculated', $event)"
  />
</template>
