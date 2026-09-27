<script setup>
import { Connection } from '@element-plus/icons-vue'
</script>

<template>
  <section class="software-integration-overview" aria-labelledby="software-integration-title">
    <div class="software-integration-icon" aria-hidden="true">
      <el-icon><Connection /></el-icon>
    </div>
    <h1 id="software-integration-title">软件集成</h1>
    <p>请从上方选择需要使用的功能</p>
  </section>
</template>

<style lang="scss" scoped>
.software-integration-overview {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px;
  background: #fff;
  color: #2d2d2d;
  text-align: center;
}

.software-integration-icon {
  width: 64px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #e2e2e2;
  border-top: 3px solid #f4d000;
  border-radius: 8px;
  background: #fafafa;
  color: #4084d9;

  .el-icon {
    font-size: 30px;
  }
}

h1 {
  margin: 18px 0 8px;
  font-size: 20px;
  line-height: 1.4;
}

p {
  margin: 0;
  color: #909399;
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .software-integration-overview {
    padding: 24px 16px;
  }
}
</style>
