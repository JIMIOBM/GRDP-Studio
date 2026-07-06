<script setup>
defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})
</script>

<template>
  <div class="material-placeholder">
    <div class="title">物质平衡</div>
    <div class="desc">当前页面保留物质平衡入口，结果展示未在本次解析法迁移中调整。</div>
  </div>
</template>

<style scoped>
.material-placeholder {
  height: 100%;
  padding: 18px;
  color: #333;
  background: #fff;
}

.title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 8px;
}

.desc {
  font-size: 13px;
  color: #666;
}
</style>
