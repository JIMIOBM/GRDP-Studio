cat > /mnt/user-data/outputs/IprInterface.vue << 'VEOF'
<script setup>
/**
 * IprInterface.vue
 * 解析融合工作台主界面
 *
 * 路径：src/views/IprInterface.vue
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import RibbonMenu from '@/components/RibbonMenu.vue'
import TreeNode from '@/views/TreeNode.vue'
import WaterInvasionDialog  from '@/views/WellControlInventory/WaterInvasionDialog.vue'
import WaterInvasionContent from '@/views/WellControlInventory/WaterInvasionContent.vue'
import { NODETYPE } from '@/constants/nodeType'
import { nodeApi } from '@/api/docker'

// ===== 左侧项目树 =====
const treeData = ref([
  {
    id:       'g-well',
    label:    '井',
    children: []   // 由后端数据驱动，onMounted / 分析完成后刷新
  },
  {
    id:       'g-reservoir',
    label:    '库',
    children: [{ id: 'res-1', label: '项目 1', type: 'reservoir' }]
  },
  {
    id:       'g-group',
    label:    '库群',
    children: [{ id: 'grp-1', label: '项目 1', type: 'group' }]
  }
])

const activeNodeId = ref('')
const activeNode   = ref(null)
const activeSide   = ref('input')

// ===== 右侧内容区 =====
const currentView     = ref(null)   // null | 'water-invasion'
const currentViewNode = ref(null)

const handleSelect = (node) => {
  activeNodeId.value = node.id
  activeNode.value   = node

  if (node.type === NODETYPE.NodeType_WaterInvasionAnalysis) {
    currentView.value     = 'water-invasion'
    currentViewNode.value = node
  } else {
    currentView.value     = null
    currentViewNode.value = null
    ElMessage.info(`已选择：${node.label}`)
  }
}

// ===== 对话框可见性 =====
const waterInvasionVisible = ref(false)

// ===== 功能区点击路由 =====
const handleCommand = ({ group, name }) => {
  switch (name) {
    case '水侵分析':
      waterInvasionVisible.value = true
      break
    default:
      ElMessage.success(`[${group}] ${name}`)
  }
}

// ===== 核心：把 subNodes 写入树（幂等：已存在不重复）=====
const applyWaterInvasionNodes = (node) => {
  if (!node?.subNodes?.length) return

  const wellGroup = treeData.value.find(n => n.id === 'g-well')
  if (!wellGroup) return

  node.subNodes.forEach((wellNode) => {
    const wellId   = wellNode.nodeId || `node-${wellNode.nodeType}-${wellNode.nodeTitle}`
    const wellName = wellNode.nodeTitle

    // ① 找或建井节点
    if (!wellGroup.children) wellGroup.children = []
    let wellItem = wellGroup.children.find(w => w.id === wellId || w.label === wellName)
    if (!wellItem) {
      wellItem = { id: wellId, label: wellName, type: NODETYPE.NodeType_Well, children: [] }
      wellGroup.children.push(wellItem)
    }

    // ② 找或建"井控库存"分类节点
    if (!wellItem.children) wellItem.children = []
    let wciItem = wellItem.children.find(n => n.label === '井控库存')
    if (!wciItem) {
      wciItem = { id: `wci-${wellId}`, label: '井控库存', type: 'well-control-inventory', children: [] }
      wellItem.children.push(wciItem)
    }

    // ③ 找或建"水侵分析"节点（重复计算时替换）
    if (!wciItem.children) wciItem.children = []
    const wiaIdx = wciItem.children.findIndex(n => n.type === NODETYPE.NodeType_WaterInvasionAnalysis)
    const wiaItem = {
      id:       `wia-${wellId}`,
      label:    '水侵分析',
      type:     NODETYPE.NodeType_WaterInvasionAnalysis,
      wellName: wellName        // WaterInvasionContent 用此字段调 API
    }
    if (wiaIdx >= 0) {
      wciItem.children[wiaIdx] = wiaItem
    } else {
      wciItem.children.push(wiaItem)
    }
  })
}

// ===== 从后端刷新"井"子树 =====
// 调用时机：① 页面初始化  ② 分析完成后  ③ WaterInvasionContent 请求刷新
const refreshWellTree = async () => {
  try {
    const res = await nodeApi.getNode(1, 1, NODETYPE.NodeType_WaterInvasionAnalysis)
    if (!res?.data?.node) return

    // 先清空再重建，保证与后端状态完全一致
    const wellGroup = treeData.value.find(n => n.id === 'g-well')
    if (wellGroup) wellGroup.children = []

    applyWaterInvasionNodes(res.data.node)
  } catch {
    // 后端无结果时静默忽略，树保持原样
  }
}

// ===== 水侵动态分析完成回调 =====
const handleWaterInvasionConfirm = async (data) => {
  // 1. 立即用本地数据更新树（毫秒级响应，用户马上看到）
  if (data?.node) applyWaterInvasionNodes(data.node)

  // 2. 再从后端刷新一次，保证树与服务器状态一致
  await refreshWellTree()
}

// ===== WaterInvasionContent 请求刷新树 =====
// WaterInvasionContent 在加载到新数据后 emit('refresh-tree')，触发此回调
const handleRefreshTree = () => {
  refreshWellTree()
}

// ===== 页面初始化：恢复已有计算结果 =====
onMounted(refreshWellTree)
</script>

<template>
  <div class="ipr-container">
    <!-- 顶部功能区菜单 -->
    <RibbonMenu @command="handleCommand" />

    <!-- 主体：左侧树 + 内容区 -->
    <div class="ipr-main">
      <!-- 左侧面板 -->
      <aside class="side-panel">
        <div class="side-tree">
          <TreeNode
              v-for="node in treeData"
              :key="node.id"
              :node="node"
              :active-id="activeNodeId"
              @select="handleSelect"
          />
        </div>

        <!-- 底部 输入 / 输出 页签 -->
        <div class="side-tabs">
          <div
              class="side-tab"
              :class="{ active: activeSide === 'input' }"
              @click="activeSide = 'input'"
          >
            输入
          </div>
          <div
              class="side-tab"
              :class="{ active: activeSide === 'output' }"
              @click="activeSide = 'output'"
          >
            输出
          </div>
        </div>
      </aside>

      <!-- 内容区 -->
      <main class="content-area">
        <WaterInvasionContent
            v-if="currentView === 'water-invasion'"
            :node="currentViewNode"
            :project-id="1"
            :gas-reservoir-id="1"
            @refresh-tree="handleRefreshTree"
        />
      </main>
    </div>

    <!-- 水侵动态分析对话框 -->
    <WaterInvasionDialog
        v-model:visible="waterInvasionVisible"
        :project-id="1"
        :gas-reservoir-id="1"
        :node-type="NODETYPE.NodeType_WaterInvasionAnalysis"
        @confirm="handleWaterInvasionConfirm"
    />
  </div>
</template>

<style lang="scss" scoped>
$accent-yellow: #f4d000;
$border: #e0e0e0;

.ipr-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: #fff;
  overflow: hidden;
}

.ipr-main {
  flex: 1;
  display: flex;
  min-height: 0;
}

/* ===== 左侧面板 ===== */
.side-panel {
  width: 210px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid $border;
  background-color: #fff;

  .side-tree {
    flex: 1;
    overflow-y: auto;
    padding: 6px 4px;
  }

  .side-tabs {
    display: flex;
    height: 30px;
    border-top: 1px solid $border;

    .side-tab {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 13px;
      color: #555;
      cursor: pointer;
      border-right: 1px solid $border;

      &:last-child { border-right: none; }

      &.active {
        background-color: $accent-yellow;
        color: #1a1a1a;
        font-weight: 600;
      }
    }
  }
}

/* ===== 内容区 ===== */
.content-area {
  flex: 1;
  background-color: #fff;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>
VEOF
echo "done"
