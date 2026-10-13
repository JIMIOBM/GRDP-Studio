<template>
  <div class="pressure-data-loading" role="status" aria-live="polite">
    <span class="loading-spinner" aria-hidden="true" />
    <span>正在读取测点数据…</span>
  </div>
</template>

<style scoped>
.pressure-data-loading {
  position: absolute;
  inset: 0;
  z-index: 6;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  background: rgba(255, 255, 255, .94);
  color: #607086;
  font-size: 13px;
}
.loading-spinner {
  width: 26px;
  height: 26px;
  border: 3px solid #dbeafe;
  border-top-color: #409eff;
  border-radius: 50%;
  animation: pressure-data-spin .8s linear infinite;
}
@keyframes pressure-data-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) {
  .loading-spinner { animation-duration: 2s; }
}
</style>
