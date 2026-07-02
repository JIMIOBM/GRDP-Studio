<script setup>
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <div class="notfound">
    <div class="code">404</div>
    <div class="desc">页面走丢了</div>
    <el-button type="primary" @click="router.push('/ipr')">返回工作台</el-button>
  </div>
</template>

<style lang="scss" scoped>
.notfound {
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;

  .code {
    font-size: 96px;
    font-weight: 800;
    color: #2d2d2d;
  }

  .desc {
    font-size: 16px;
    color: #888;
    margin-bottom: 8px;
  }
}
</style>
