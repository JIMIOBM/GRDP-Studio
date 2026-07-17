<script setup>
import NpiContent from '@/views/WellControlInventory/NpiContent.vue'

defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})
</script>

<template>
  <NpiContent
      method="transient"
      :node="node"
      :project-id="projectId"
      :gas-reservoir-id="gasReservoirId"
  />
</template>
