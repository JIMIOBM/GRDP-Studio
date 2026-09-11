<script setup>
import { ref } from 'vue'
const collapsed = ref(false)
</script>

<template>
  <aside class="parameter-panel constraint-parameters" :class="{ collapsed }">
    <button v-if="collapsed" class="collapsed-tab" type="button" aria-label="展开参数设置" :aria-expanded="false" @click="collapsed = false">参数设置</button>
    <template v-else>
      <div class="panel-heading"><span>参数设置</span><button class="collapse-button" type="button" title="收起参数设置" aria-label="收起参数设置" :aria-expanded="true" @click="collapsed = true"><svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg></button></div>
      <div class="parameter-form"><slot /></div>
    </template>
  </aside>
</template>

<style scoped>
.constraint-parameters{width:260px;min-width:260px}.constraint-parameters.collapsed{width:38px;min-width:38px;padding:0}.panel-heading{justify-content:space-between}.collapse-button{display:flex;align-items:center;justify-content:center;width:24px;height:26px;padding:0;border:0;background:transparent}.collapsed-tab{width:100%;padding:15px 8px;border:0;background:transparent;writing-mode:vertical-rl;letter-spacing:4px;color:#555;font:13px/1.8 "Microsoft YaHei",sans-serif}
</style>
