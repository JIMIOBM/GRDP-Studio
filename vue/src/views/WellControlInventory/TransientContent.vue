<script setup>
import NpiContent from '@/views/WellControlInventory/NpiContent.vue'

defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String],
  recalculating: Boolean
})

const emit = defineEmits(['recalculate'])

const handleRecalculate = options => emit('recalculate', options)
</script>

<template>
  <NpiContent
      method="transient"
      :node="node"
      :project-id="projectId"
      :gas-reservoir-id="gasReservoirId"
      :recalculating="recalculating"
      @recalculate="handleRecalculate"
  />
</template>
