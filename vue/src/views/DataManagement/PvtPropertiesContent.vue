<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import NaturalGasProperties from './NaturalGasProperties.vue'
import FormationWaterProperties from './FormationWaterProperties.vue'
import RockProperties from './RockProperties.vue'
import NaturalGasImportDialog from './NaturalGasImportDialog.vue'

defineProps({
  wellName: {type: String, required: true},
  projectId: {type: [Number, String], required: true},
  gasReservoirId: {type: [Number, String], required: true}
})

const propertyTabs = [
  { name: '天然气性质', component: NaturalGasProperties },
  { name: '地层水性质', component: FormationWaterProperties },
  { name: '岩石性质', component: RockProperties }
]

const activePropertyTab = ref('天然气性质')
const importDialogVisible = ref(false)

const handleSave = () => {
  ElMessage.success(`${activePropertyTab.value}参数已保存`)
}

const handleImport = () => {
  if (activePropertyTab.value === '天然气性质') {
    importDialogVisible.value = true
    return
  }
  ElMessage.info(`${activePropertyTab.value}导入功能暂未接入`)
}
</script>

<template>
  <section class="pvt-properties">
    <header class="pvt-toolbar">
      <nav class="property-tabs" aria-label="PVT 性质分类">
        <button
          v-for="tab in propertyTabs"
          :key="tab.name"
          type="button"
          class="property-tab"
          :class="{ active: activePropertyTab === tab.name }"
          @click="activePropertyTab = tab.name"
        >
          {{ tab.name }}
        </button>
      </nav>

      <div class="toolbar-actions">
        <button type="button" class="toolbar-button" @click="handleSave">保存</button>
        <button type="button" class="toolbar-button" @click="handleImport">导入</button>
      </div>
    </header>

    <NaturalGasProperties v-if="activePropertyTab === '天然气性质'" />
    <FormationWaterProperties v-else-if="activePropertyTab === '地层水性质'" :well-name="wellName" :project-id="projectId"/>
    <RockProperties v-else />

    <NaturalGasImportDialog v-model="importDialogVisible" />
  </section>
</template>

<style lang="scss" scoped>
.pvt-properties {
  width: 100%;
  height: 100%;
  min-width: 720px;
  min-height: 420px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
  color: #252525;
  font-family: "Microsoft YaHei", "Segoe UI", sans-serif;
  font-size: 14px;
}

.pvt-toolbar {
  height: 48px;
  flex: 0 0 48px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 7px 11px 0;
  box-sizing: border-box;
}

.property-tabs {
  display: flex;
  align-items: stretch;
}

.property-tab {
  min-width: 94px;
  height: 30px;
  padding: 0 12px;
  border: 1px solid #222;
  border-right: 0;
  background: #fff;
  color: #222;
  font: inherit;
  cursor: pointer;

  &:last-child {
    border-right: 1px solid #222;
  }

  &.active {
    background: #050505;
    color: #fff;
    font-weight: 700;
  }

  &:focus-visible {
    outline: 2px solid #2f74c0;
    outline-offset: 2px;
  }
}

.toolbar-actions {
  display: flex;
  gap: 9px;
}

.toolbar-button {
  min-width: 70px;
  height: 30px;
  padding: 0 17px;
  border: 1px solid #111;
  border-radius: 6px;
  background: #fff;
  color: #111;
  font: inherit;
  cursor: pointer;

  &:hover {
    background: #f5f5f5;
  }

  &:active {
    background: #ebebeb;
  }
}
</style>
