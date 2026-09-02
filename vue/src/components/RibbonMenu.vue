<script setup>
import { ref, computed, nextTick, onBeforeUnmount, onMounted, watch } from 'vue'

/**
 * 顶部功能区菜单（Ribbon）组件
 * 完全数据驱动：tabs -> groups -> columns -> items
 * dropdown: true 的列需同时提供 dropdownItems 数组，点击后弹出复选下拉列表。
 */
const props = defineProps({ //允许外部传菜单配置
  tabs: {
    type: Array,
    default: () => null
  },
  activeTabName: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['command', 'tab-change']) //向父组件发送命令

const defaultTabs = [
  {
    name: '解析融合',
    groups: [
      {
        title: '数据管理',
        columns: [
          { type: 'checks', items: ['井头数据', '井斜数据', '测井数据'] },
          { type: 'checks', items: ['完井数据', '其他数据', '注采数据'] },
          { type: 'checks', items: ['产能测试', '静压数据', 'PVT性质'] },
          { type: 'checks', items: ['相渗数据'] }
        ]
      },
      {
        title: '井控库存',
        columns: [
          { type: 'large', label: '诊断曲线', dropdown: true, dropdownItems: ['Blasingame', 'Transient', 'AG', 'Wattenbarger', 'NPI'] },
          { type: 'checks', items: ['物质平衡', '流动平衡', '动态平衡'] },
          { type: 'checks', items: ['水侵分析', '解析法'], squares: 5 }
        ]
      },
      {
        title: '单井产能',
        columns: [
          { type: 'large', label: '产能试井', dropdown: true, dropdownItems: ['回压试井', '等时试井', '修正等时', '一点法'] },
          { type: 'large', label: '产能系数', dropdown: true, dropdownItems: ['二项式', '指数式'] },
          { type: 'large', label: '理论计算', dropdown: true, dropdownItems: ['稳定流', '不稳定流'] },
          { type: 'large', label: '动态产能', dropdown: true, dropdownItems: ['稳定流', '不稳定流'] },
          { type: 'large', label: '产能对比', dropdown: true, dropdownItems: ['多周期'] }
        ]
      },
      {
        title: '井筒能力',
        columns: [
          { type: 'checks', items: ['井身结构', 'PVT模型', '温度模型'] },
          { type: 'checks', items: ['边界条件', '井筒积液', '水合物'] },
          { type: 'checks', items: ['冲蚀', '出砂'] },
          { type: 'large', label: '压力折算', dropdown: true, dropdownItems: ['折算方法', '结果对比'] }
        ]
      },
      {
        title: '管束能力',
        columns: [
          { type: 'large', label: '管流计算', dropdown: true, dropdownItems: ['折算方法', '结果对比'] },
          { type: 'large', label: '约束条件', dropdown: true, dropdownItems: ['关键设备', '水合物', '冲蚀', '冻堵'] },
          { type: 'squares', count: 3 },
          { type: 'squares', count: 1 }
        ]
      },
      {
        title: '配产配注',
        columns: [
          { type: 'checks', items: ['节点分析', '注采拟合', '图版法'] },
          { type: 'large', label: '一体化耦合优化', dropdown: true, dropdownItems: ['单目标', '多目标'] },
          { type: 'large', label: '多周期预测', dropdown: true, dropdownItems: ['目标函数', '约束条件', '方案生成', '方案必选'] }
        ]
      }
    ]
  },
  {
    name: '软件集成',
    groups: [
      {
        title: '项目管理',
        columns: [
          { type: 'large', label: '新建项目', icon: '方案生成', commandId: 'software-integration.project.create' },
          {
            type: 'large',
            label: '导入模型',
            icon: '数据映射',
            dropdown: true,
            dropdownItems: ['PIPESIM 井筒模型'],
            dropdownCommandIds: { 'PIPESIM 井筒模型': 'software-integration.model.import-pipesim' }
          },
          { type: 'large', label: '保存', icon: '生成报告', commandId: 'software-integration.project.save' }
        ]
      },
      {
        title: '数值模拟',
        columns: [
          { type: 'large', label: 'ECLIPSE', icon: 'Eclipse' },
          { type: 'large', label: 'INTERSECT', icon: 'CMG' },
          { type: 'large', label: '扩展接口', icon: '接口配置' }
        ]
      },
      {
        title: '井筒模拟',
        columns: [
          { type: 'large', label: 'PIPESIM 井筒', icon: 'PIPESIM' },
          { type: 'large', label: 'wellcat', icon: '井身结构' },
          { type: 'large', label: '扩展接口', icon: '接口配置' }
        ]
      },
      {
        title: '管网模拟',
        columns: [
          { type: 'large', label: 'PIPESIM Network', icon: '管流计算' },
          { type: 'large', label: 'wellcat', icon: '约束条件' },
          { type: 'large', label: '扩展接口', icon: '接口配置' }
        ]
      },
      {
        title: '一体化模拟',
        columns: [
          { type: 'large', label: '加载模拟', icon: '初始方案' },
          {
            type: 'large',
            label: '一体化建模',
            icon: '一体化耦合优化',
            dropdown: true,
            dropdownItems: ['模型组合', '方案构建']
          },
          { type: 'checks', items: ['PVT设置', '井信息匹配', '耦合参数传递'] },
          { type: 'checks', items: ['模拟控制', '耦合计算', '运行状态监控'] }
        ]
      },
      {
        title: '模拟器设置',
        columns: [
          { type: 'large', label: '模拟器设置', icon: '接口配置' }
        ]
      },
      {
        title: '可视化',
        columns: [
          { type: 'checks', items: ['2D可视化', '3D可视化', '曲线'] }
        ]
      }
    ]
  },
  {
    name: '多周期优化',
    groups: [
      {
        title: '周期设置',
        columns: [{type: 'checks', items: ['周期划分', '约束设置', '初始方案']}]
      },
      {
        title: '优化算法',
        columns: [
          {
            type: 'large',
            label: '遗传算法',
            dropdown: true,
            dropdownItems: ['标准遗传算法', '自适应遗传算法', '差分进化']
          },
          {type: 'large', label: '梯度法', dropdown: true, dropdownItems: ['最速下降法', '共轭梯度法', 'Newton法']}
        ]
      }
    ]
  },
  {
    name: '多目标决策',
    groups: [
      {
        title: '目标定义',
        columns: [{type: 'checks', items: ['产量目标', '成本目标', '采收率目标']}]
      },
      {
        title: '决策方法',
        columns: [
          {type: 'large', label: 'Pareto', dropdown: true, dropdownItems: ['NSGA-II', 'MOEA/D', 'SPEA2']},
          {type: 'large', label: '权衡分析'}
        ]
      }
    ]
  },
  {
    name: '可视化',
    groups: [
      {
        title: '图表',
        columns: [
          {type: 'large', label: '曲线图'},
          {type: 'large', label: '散点图'},
          {type: 'large', label: '云图'}
        ]
      },
      {
        title: '报告',
        columns: [{type: 'checks', items: ['生成报告', '导出 PDF', '打印']}]
      }
    ]
  }
]

//控制当先显示哪一个页签
const diagnosticCurveItems = ['Blasingame', 'Transient', 'AG', 'Wattenbarger', 'NPI']
const constraintConditionItems = ['关键设备', '水合物', '冲蚀', '冻堵']

const isDiagnosticDropdown = (col) =>
  Array.isArray(col?.dropdownItems) &&
  diagnosticCurveItems.every(item => col.dropdownItems.includes(item))

const isConstraintDropdown = (col) =>
  Array.isArray(col?.dropdownItems) &&
  constraintConditionItems.every(item => col.dropdownItems.includes(item))

const normalizeRibbonTabs = (tabs) => tabs.map(tab => ({
  ...tab,
  groups: (tab.groups || []).map(group => {
    let shouldMoveDiagnosticItems = false
    let pendingConstraintItems = []
    return {
      ...group,
      columns: (group.columns || []).map(col => {
        if (isDiagnosticDropdown(col)) {
          shouldMoveDiagnosticItems = true
          return {
            ...col,
            dropdown: false,
            dropdownItems: [],
            passive: true
          }
        }

        if (isConstraintDropdown(col)) {
          pendingConstraintItems = [...constraintConditionItems]
          return {
            ...col,
            dropdown: false,
            dropdownItems: [],
            passive: true
          }
        }

        if (shouldMoveDiagnosticItems && col?.squares === 5) {
          shouldMoveDiagnosticItems = false
          return {
            ...col,
            squares: diagnosticCurveItems
          }
        }

        if (pendingConstraintItems.length && col?.type === 'squares' && Number(col.count) > 0) {
          const items = pendingConstraintItems.slice(0, col.count)
          pendingConstraintItems = pendingConstraintItems.slice(col.count)
          return {
            ...col,
            squares: items
          }
        }

        return col
      })
    }
  })
}))

const tabList = computed(() => normalizeRibbonTabs(props.tabs || defaultTabs))
const activeTab = ref(0)
const activeTabGroups = computed(() => tabList.value[activeTab.value]?.groups || [])
const ribbonBody = ref(null)
const canScrollLeft = ref(false)
const canScrollRight = ref(false)
let ribbonResizeObserver = null

watch(
  () => props.activeTabName,
  name => {
    if (!name) return
    const index = tabList.value.findIndex(tab => tab.name === name)
    if (index >= 0) activeTab.value = index
  },
  { immediate: true }
)

const updateRibbonOverflow = () => {
  const body = ribbonBody.value
  if (!body) return

  canScrollLeft.value = body.scrollLeft > 1
  canScrollRight.value = body.scrollLeft + body.clientWidth < body.scrollWidth - 1
}

const scrollRibbon = (direction) => {
  const body = ribbonBody.value
  if (!body) return

  body.scrollBy({
    left: direction * Math.max(280, Math.round(body.clientWidth * 0.72)),
    behavior: 'smooth'
  })
}

onMounted(() => {
  nextTick(updateRibbonOverflow)

  if (typeof ResizeObserver !== 'undefined') {
    ribbonResizeObserver = new ResizeObserver(updateRibbonOverflow)
    if (ribbonBody.value) ribbonResizeObserver.observe(ribbonBody.value)
  }
})

onBeforeUnmount(() => ribbonResizeObserver?.disconnect())

watch(activeTab, () => {
  nextTick(() => {
    if (ribbonBody.value) ribbonBody.value.scrollLeft = 0
    updateRibbonOverflow()
  })
})


//把 ../assets/ribbon-icons/ 下面所有 svg 图标都加载进来。
const iconModules = import.meta.glob('../assets/ribbon-icons/*.svg', {
  eager: true,
  query: '?url',
  import: 'default'
})
//把图表名标准化
const normalizeIconKey = (value) => String(value || '').replace(/[\s/\\-]+/g, '').toLowerCase()

//把所有图表文件变成一个映射表
const iconMap = Object.fromEntries(
  Object.entries(iconModules).map(([path, url]) => [
    normalizeIconKey(decodeURIComponent(path.split('/').pop().replace(/\.svg$/, ''))),
    url
  ])
)

// 数据管理沿用现有 Ribbon 图标资产，保持线宽、配色和视觉密度一致。
const iconAliases = {
  井头数据: '井身结构',
  完井数据: '边界条件',
  产能测试: '产能试井',
  井斜数据: '温度模型',
  其他数据: '数据映射',
  静压数据: '压力折算',
  测井数据: '动态数据',
  注采数据: '注采拟合',
  PVT性质: 'PVT模型',
  PVT设置: 'PVT模型',
  井信息匹配: '注采拟合',
  耦合参数传递: '数据映射',
  模拟控制: '约束设置',
  耦合计算: '一体化耦合优化',
  运行状态监控: '动态数据',
  '2D可视化': '曲线图',
  '3D可视化': '云图',
  曲线: '曲线图',
  'PIPESIM 井筒模型': 'PIPESIM',
  模型组合: '一体化耦合优化',
  方案构建: '方案生成'
}

const switchTab = (idx) => { //切换页签
  activeTab.value = idx
  emit('tab-change', tabList.value[idx]?.name || '')
}

const onItemClick = (groupTitle, label, parent = '', commandId = '') => { //点击菜单项
  if (!label) return
  emit('command', {group: groupTitle, name: label, parent, commandId})
}


//渲染图表
const getIcon = (label) => iconMap[normalizeIconKey(iconAliases[label] || label)] || ''
</script>

<template>
  <div
      class="ribbon"
      :class="{ 'software-integration-ribbon': tabList[activeTab]?.name === '软件集成' }"
  >
    <!-- 顶部页签条 -->
    <div class="ribbon-tabs">
      <div
          v-for="(tab, idx) in tabList"
          :key="tab.name"
          class="ribbon-tab"
          :class="{ active: idx === activeTab }"
          role="button"
          tabindex="0"
          @click="switchTab(idx)"
          @keydown.enter.prevent="switchTab(idx)"
      >
        {{ tab.name }}
      </div>
    </div>

    <!-- 功能区主体 -->
    <div class="ribbon-body-wrap" :class="{ 'has-left-overflow': canScrollLeft, 'has-right-overflow': canScrollRight }">
      <button
          v-if="canScrollLeft"
          class="ribbon-scroll-button ribbon-scroll-left"
          type="button"
          aria-label="向左查看更多功能"
          title="向左查看更多功能"
          @click="scrollRibbon(-1)"
      >
        &#8249;
      </button>

      <div ref="ribbonBody" class="ribbon-body" @scroll.passive="updateRibbonOverflow">
        <div class="ribbon-group" v-for="group in activeTabGroups" :key="group.title">
        <div class="group-content" :class="{ 'production-group': group.title === '配产配注' }">
          <template v-for="(col, ci) in group.columns" :key="ci">

            <!-- 复选项列 -->
            <div v-if="col.type === 'checks'" class="col-checks">
              <label
                  class="check-item"
                  v-for="item in col.items"
                  :key="item"
                  @click="onItemClick(group.title, item, '', col.commandIds?.[item])"
              >
                <img
                    v-if="getIcon(item)"
                    class="small-icon"
                    :src="getIcon(item)"
                    :alt="item"
                >
                <span v-else class="checkbox"></span>
                <span class="check-label">{{ item }}</span>
              </label>
              <div v-if="col.squares" class="square-row">
                <template v-if="Array.isArray(col.squares)">
                  <el-tooltip
                      v-for="item in col.squares"
                      :key="item"
                      :content="item"
                      placement="bottom"
                  >
                    <span
                        class="mini-square command-square"
                        tabindex="0"
                        role="button"
                        :aria-label="item"
                        @click="onItemClick(group.title, item, '', col.commandIds?.[item])"
                        @keydown.enter.prevent="onItemClick(group.title, item, '', col.commandIds?.[item])"
                    >
                      <img
                          v-if="getIcon(item)"
                          class="square-icon"
                          :src="getIcon(item)"
                          :alt="item"
                      >
                    </span>
                  </el-tooltip>
                </template>
                <span
                    v-else
                    class="mini-square"
                    v-for="n in col.squares"
                    :key="n"
                ></span>
              </div>
            </div>

            <!-- 大图标按钮列 - 有下拉菜单（dropdown: true + dropdownItems 存在） -->
            <el-popover
                v-else-if="col.type === 'large' && col.dropdown && col.dropdownItems?.length"
                placement="bottom-start"
                trigger="click"
                popper-class="ribbon-popover"
                :show-arrow="false"
            >
              <template #reference>
                <div class="col-large">
                  <img
                      v-if="getIcon(col.icon || col.label)"
                      class="big-icon"
                      :src="getIcon(col.icon || col.label)"
                      :alt="col.label"
                  >
                  <span v-else class="big-icon icon-placeholder"></span>
                  <span class="big-label">{{ col.label }}</span>
                  <span class="dropdown-arrow">▾</span>
                </div>
              </template>

              <!-- 下拉列表内容 -->
              <div class="ribbon-dropdown-list">
                <div
                    class="ribbon-dropdown-item"
                    v-for="item in col.dropdownItems"
                    :key="item"
                    @click="onItemClick(group.title, item, col.label, col.dropdownCommandIds?.[item])"
                >
                  <img
                      v-if="getIcon(item)"
                      class="dropdown-icon"
                      :src="getIcon(item)"
                      :alt="item"
                  >
                  <span v-else class="d-checkbox"></span>
                  <span class="d-label">{{ item }}</span>
                </div>
              </div>
            </el-popover>

            <!-- 大图标按钮列 - 无下拉 -->
            <div
                v-else-if="col.type === 'large'"
                class="col-large"
                :class="{ passive: col.passive }"
                @click="!col.passive && onItemClick(group.title, col.label, '', col.commandId)"
            >
              <img
                  v-if="getIcon(col.icon || col.label)"
                  class="big-icon"
                  :src="getIcon(col.icon || col.label)"
                  :alt="col.label"
              >
              <span v-else class="big-icon icon-placeholder"></span>
              <span class="big-label">{{ col.label }}</span>
            </div>

            <!-- 小方块占位列 -->
            <div v-else-if="col.type === 'squares'" class="col-squares">
              <template v-if="Array.isArray(col.squares)">
                <el-tooltip
                    v-for="item in col.squares"
                    :key="item"
                    :content="item"
                    placement="bottom"
                >
                  <span
                      class="pad-square command-square"
                      tabindex="0"
                      role="button"
                      :aria-label="item"
                      @click="onItemClick(group.title, item)"
                      @keydown.enter.prevent="onItemClick(group.title, item)"
                  >
                    <img
                        v-if="getIcon(item)"
                        class="square-icon"
                        :src="getIcon(item)"
                        :alt="item"
                    >
                  </span>
                </el-tooltip>
              </template>
              <span v-else class="pad-square" v-for="n in col.count" :key="n"></span>
            </div>

          </template>
        </div>
          <div class="group-title">{{ group.title }}</div>
        </div>
      </div>

      <button
          v-if="canScrollRight"
          class="ribbon-scroll-button ribbon-scroll-right"
          type="button"
          aria-label="向右查看更多功能"
          title="向右查看更多功能"
          @click="scrollRibbon(1)"
      >
        &#8250;
      </button>
    </div>
  </div>
</template>

<style lang="scss" scoped>
$tab-bg: #2d2d2d;
$accent-yellow: #f4d000;
$accent-soft: #fff8d8;
$accent-border: #d6b20d;
$ribbon-bg: #ffffff;
$group-label-bg: #ececec;
$divider: #d9d9d9;
$square-bg: #d7d7d7;
$square-border: #c2c2c2;

.ribbon {
  user-select: none;
  background-color: $ribbon-bg;
  border-bottom: 1px solid $divider;
}

/* ===== 页签条 ===== */
.ribbon-tabs {
  display: flex;
  height: 32px;
  background-color: $tab-bg;
  padding-left: 4px;

  .ribbon-tab {
    height: 100%;
    display: flex;
    align-items: center;
    padding: 0 16px;
    font-size: 13px;
    font-weight: 600;
    color: #e6e6e6;
    cursor: pointer;
    transition: background-color 0.15s;

    &:hover {
      background-color: #3c3c3c;
    }

    &.active {
      background: $accent-yellow;
      color: #202020;
    }
  }
}

/* ===== 功能区主体 ===== */
.ribbon-body-wrap {
  position: relative;
  background-color: $ribbon-bg;

  &::before,
  &::after {
    content: '';
    position: absolute;
    top: 0;
    bottom: 0;
    z-index: 4;
    width: 42px;
    pointer-events: none;
    opacity: 0;
    transition: opacity 0.15s ease;
  }

  &::before {
    left: 0;
    background: linear-gradient(to right, rgba(255, 255, 255, 0.98), rgba(255, 255, 255, 0));
  }

  &::after {
    right: 0;
    background: linear-gradient(to left, rgba(255, 255, 255, 0.98), rgba(255, 255, 255, 0));
  }

  &.has-left-overflow::before,
  &.has-right-overflow::after {
    opacity: 1;
  }
}

.ribbon-body {
  display: flex;
  align-items: stretch;
  background-color: $ribbon-bg;
  min-height: 112px;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: none;
  -ms-overflow-style: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.ribbon-scroll-button {
  position: absolute;
  top: 50%;
  z-index: 5;
  width: 28px;
  height: 48px;
  padding: 0;
  transform: translateY(-50%);
  border: 1px solid #d3d3d3;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.96);
  color: #333;
  box-shadow: 0 1px 5px rgba(0, 0, 0, 0.14);
  font-size: 26px;
  line-height: 42px;
  cursor: pointer;

  &:hover,
  &:focus-visible {
    border-color: $accent-border;
    background: $accent-soft;
    color: #202020;
    outline: none;
  }
}

.ribbon-scroll-left {
  left: 6px;
}

.ribbon-scroll-right {
  right: 6px;
}

.ribbon-group {
  display: flex;
  flex-direction: column;
  border-right: 1px solid $divider;

  .group-content {
    flex: 1;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 10px;
  }

  .group-title {
    height: 20px;
    line-height: 20px;
    text-align: center;
    font-size: 12px;
    color: #666;
    background-color: $group-label-bg;
    border-top: 1px solid $divider;
  }
}

/* ===== 复选项列 ===== */
.col-checks {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
  align-self: flex-start;
  padding-top: 8px;

  .check-item {
    display: flex;
    align-items: center;
    gap: 6px;
    cursor: pointer;
    line-height: 16px;

    &:hover .check-label {
      color: #202020;
    }

    .checkbox {
      width: 16px;
      height: 16px;
      background-color: $square-bg;
      border: 1px solid $square-border;
      border-radius: 1px;
      flex-shrink: 0;
    }

    .small-icon {
      width: 18px;
      height: 18px;
      object-fit: contain;
      flex-shrink: 0;
    }

    .check-label {
      font-size: 13px;
      color: #333;
      white-space: nowrap;
    }
  }

  .square-row {
    display: flex;
    gap: 3px;
    margin-top: 2px;

    .mini-square {
      width: 16px;
      height: 16px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      background-color: $square-bg;
      border: 1px solid $square-border;
    }

    .command-square {
      cursor: pointer;

      &:hover,
      &:focus-visible {
        border-color: $accent-border;
        background-color: $accent-soft;
        outline: none;
      }
    }

    .square-icon {
      width: 13px;
      height: 13px;
      object-fit: contain;
      pointer-events: none;
    }
  }
}

/* ===== 大图标按钮列 ===== */
.col-large {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 48px;
  min-width: 48px;
  padding: 4px 6px;
  border-radius: 3px;
  cursor: pointer;

  &:hover {
    background-color: $accent-soft;
    outline: 1px solid #ead36c;
  }

  /* 下拉栏目打开后保持白底，不把“已打开”误表现为选中背景。 */
  &[aria-describedby] {
    background-color: transparent;
    outline: none;
  }

  &.el-tooltip__trigger:hover {
    background-color: transparent;
    outline: none;
  }

  &.passive {
    cursor: default;
  }

  &.passive:hover {
    background-color: transparent;
    outline: none;
  }

  .big-icon {
    width: 36px;
    height: 36px;
    object-fit: contain;
    flex-shrink: 0;
  }

  .icon-placeholder {
    background-color: $square-bg;
    border: 1px solid $square-border;
    border-radius: 2px;
  }

  .big-label {
    margin-top: 5px;
    font-size: 13px;
    white-space: nowrap;
    color: #333;
  }

  .dropdown-arrow {
    font-size: 10px;
    line-height: 8px;
    color: #666;
  }
}

/* ===== 小方块占位列 ===== */
.col-squares {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
  align-self: flex-start;
  padding-top: 8px;

  .pad-square {
    width: 16px;
    height: 16px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background-color: $square-bg;
    border: 1px solid $square-border;
  }

  .command-square {
    cursor: pointer;

    &:hover,
    &:focus-visible {
      border-color: $accent-border;
      background-color: $accent-soft;
      outline: none;
    }
  }

  .square-icon {
    width: 13px;
    height: 13px;
    object-fit: contain;
    pointer-events: none;
  }
}

.production-group {
  gap: 8px;

  .col-large {
    width: auto;
    min-width: 70px;
    flex: 0 0 auto;
  }

  .big-label {
    width: auto;
    max-width: none;
    white-space: nowrap;
    overflow: visible;
  }
}

/* 中小屏收紧功能区，但保留足够的文字和图标尺寸。 */
@media (max-width: 1440px) {
  .ribbon-group .group-content {
    gap: 6px;
    padding-right: 6px;
    padding-left: 6px;
  }

  .col-checks .check-item {
    gap: 4px;
  }

  .col-large {
    width: 44px;
    min-width: 44px;
    padding-right: 3px;
    padding-left: 3px;

    .big-icon {
      width: 32px;
      height: 32px;
    }
  }

  .production-group {
    gap: 5px;

    .col-large {
      min-width: 64px;
    }
  }
}

/* 软件集成命令名称更长，只在该页签保留稳定尺寸并使用横向滚动。 */
.ribbon.software-integration-ribbon {
  .ribbon-group {
    flex: 0 0 auto;

    .group-content {
      gap: 6px;
      padding-right: 8px;
      padding-left: 8px;
      box-sizing: border-box;
    }
  }

  .col-checks {
    min-width: 104px;

    .check-item {
      min-height: 20px;
    }

    .small-icon {
      display: block;
    }
  }

  .col-large {
    position: relative;
    justify-content: flex-start;
    width: 60px;
    min-width: 60px;
    height: 84px;
    padding-right: 4px;
    padding-left: 4px;
    box-sizing: border-box;

    .big-icon {
      display: block;
      margin: 1px auto 0;
    }

    .big-label {
      width: 100%;
      min-height: 32px;
      margin-top: 4px;
      line-height: 16px;
      text-align: center;
      white-space: normal;
      overflow-wrap: anywhere;
    }

    .dropdown-arrow {
      min-height: 8px;
    }
  }
}
</style>

<!--
  el-popover 的弹出层挂载到 <body>，scoped 样式无法穿透，
  必须用非 scoped 的全局 style 块来定义下拉列表样式。
-->
<style lang="scss">
.ribbon-popover.el-popover {
  padding: 4px 0 !important;
  min-width: 0 !important;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.18) !important;
  border: 1px solid #d4d4d4 !important;
  border-radius: 3px !important;
}

.ribbon-dropdown-list {
  .ribbon-dropdown-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 5px 14px;
    cursor: pointer;
    font-size: 13px;
    color: #333;
    white-space: nowrap;

    &:hover {
      background-color: #fff8d8;
      color: #202020;
    }

    .d-checkbox {
      width: 14px;
      height: 14px;
      background-color: #d7d7d7;
      border: 1px solid #c2c2c2;
      border-radius: 1px;
      flex-shrink: 0;
    }

    .dropdown-icon {
      width: 16px;
      height: 16px;
      object-fit: contain;
      flex-shrink: 0;
    }

    .d-label {
      line-height: 1;
    }
  }
}
</style>
