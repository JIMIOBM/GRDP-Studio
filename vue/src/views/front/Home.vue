<script setup>
import { useRouter } from 'vue-router'
import { projectName } from '../../../config/config.default'
const router = useRouter()
</script>

<template>
  <div class="front-home">
    <div class="hero">
      <h1>{{ projectName }}</h1>
      <p>解析融合 · 多周期优化 · 多目标决策 · 一体化油藏工作平台</p>
      <el-button type="primary" size="large" @click="router.push('/ipr')">进入解析融合工作台</el-button>
    </div>

    <div class="features">
      <el-card v-for="f in [
        { t: '数据管理', d: '动态数据、岩石及流体性质、相渗数据统一管理' },
        { t: '单井产能', d: '产能试井、产能系数、理论计算与动态产能分析' },
        { t: '井筒能力', d: '井身结构、PVT、温度模型与压力折算' },
        { t: '配产配注', d: '节点分析、注采拟合、多目标方案生成与比选' }
      ]" :key="f.t" class="feat-card">
        <div class="feat-title">{{ f.t }}</div>
        <div class="feat-desc">{{ f.d }}</div>
      </el-card>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.front-home {
  padding: 40px;
}
.hero {
  text-align: center;
  padding: 40px 0 50px;
  h1 { font-size: 34px; color: #2d2d2d; }
  p { margin: 12px 0 24px; color: #888; }
}
.features {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 18px;
  max-width: 1000px;
  margin: 0 auto;
  .feat-title { font-size: 16px; font-weight: 600; color: #333; }
  .feat-desc { margin-top: 8px; color: #888; font-size: 13px; line-height: 1.6; }
}
</style>
