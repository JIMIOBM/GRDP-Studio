<script setup>
import DynamicBalanceContent from '@/views/WellControlInventory/DynamicBalanceContent.vue'

defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})
</script>

<template>
  <DynamicBalanceContent
      :node="node"
      :project-id="projectId"
      :gas-reservoir-id="gasReservoirId"
  />
</template>
