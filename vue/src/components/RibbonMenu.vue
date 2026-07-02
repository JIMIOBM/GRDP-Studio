<script setup>
import { ref, computed } from 'vue'

/**
 * 顶部功能区菜单（Ribbon）组件
 * 完全数据驱动：tabs -> groups -> columns -> items
 * dropdown: true 的列需同时提供 dropdownItems 数组，点击后弹出复选下拉列表。
 */
const props = defineProps({
  tabs: {
    type: Array,
    default: () => null
  }
})

const emit = defineEmits(['command'])

const defaultTabs = [
  {
    name: '解析融合',
    groups: [
      {
        title: '数据管理',
        columns: [
          { type: 'checks', items: ['动态数据', '岩石及流体性质', '相渗数据'] }
        ]
      },
      {
        title: '井控库存',
        columns: [
          { type: 'large', label: '诊断曲线', dropdown: true, dropdownItems: ['Blasingame', 'Transient', 'AG', 'Wattenbarger', 'NPI'] },
          { type: 'checks', items: ['物质平衡', '流动平衡', '动态平衡'] },
          { type: 'checks', items: ['水侵分析', '解析法'], squares: 4 }
        ]
      },
      {
        title: '单井产能',
        columns: [
          { type: 'large', label: '产能试井', dropdown: true, dropdownItems: ['二项式', '指数式'] },
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
        title: '外部软件',
        columns: [
          { type: 'large', label: 'Eclipse' },
          { type: 'large', label: 'CMG' },
          { type: 'large', label: 'PIPESIM' }
        ]
      },
      {
        title: '数据接口',
        columns: [
          { type: 'checks', items: ['数据映射', '单位转换', '接口配置'] }
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

const tabList = computed(() => props.tabs || defaultTabs)
const activeTab = ref(0)
const activeTabGroups = computed(() => tabList.value[activeTab.value]?.groups || [])

const switchTab = (idx) => {
  activeTab.value = idx
}

const onItemClick = (groupTitle, label) => {
  if (!label) return
  emit('command', {group: groupTitle, name: label})
}
</script>

<template>
  <div class="ribbon">
    <!-- 顶部页签条 -->
    <div class="ribbon-tabs">
      <div
          v-for="(tab, idx) in tabList"
          :key="tab.name"
          class="ribbon-tab"
          :class="{ active: idx === activeTab }"
          @click="switchTab(idx)"
      >
        {{ tab.name }}
      </div>
    </div>

    <!-- 功能区主体 -->
    <div class="ribbon-body">
      <div class="ribbon-group" v-for="group in activeTabGroups" :key="group.title">
        <div class="group-content">
          <template v-for="(col, ci) in group.columns" :key="ci">

            <!-- 复选项列 -->
            <div v-if="col.type === 'checks'" class="col-checks">
              <label
                  class="check-item"
                  v-for="item in col.items"
                  :key="item"
                  @click="onItemClick(group.title, item)"
              >
                <span class="checkbox"></span>
                <span class="check-label">{{ item }}</span>
              </label>
              <div v-if="col.squares" class="square-row">
                <span class="mini-square" v-for="n in col.squares" :key="n"></span>
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
                  <span class="big-icon"></span>
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
                    @click="onItemClick(group.title, item)"
                >
                  <span class="d-checkbox"></span>
                  <span class="d-label">{{ item }}</span>
                </div>
              </div>
            </el-popover>

            <!-- 大图标按钮列 - 无下拉 -->
            <div
                v-else-if="col.type === 'large'"
                class="col-large"
                @click="onItemClick(group.title, col.label)"
            >
              <span class="big-icon"></span>
              <span class="big-label">{{ col.label }}</span>
            </div>

            <!-- 小方块占位列 -->
            <div v-else-if="col.type === 'squares'" class="col-squares">
              <span class="pad-square" v-for="n in col.count" :key="n"></span>
            </div>

          </template>
        </div>
        <div class="group-title">{{ group.title }}</div>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
$tab-bg: #2d2d2d;
$accent-yellow: #f4d000;
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
      background-color: $accent-yellow;
      color: #1a1a1a;
    }
  }
}

/* ===== 功能区主体 ===== */
.ribbon-body {
  display: flex;
  align-items: stretch;
  background-color: $ribbon-bg;
  min-height: 112px;
}

.ribbon-group {
  display: flex;
  flex-direction: column;
  border-right: 1px solid $divider;

  .group-content {
    flex: 1;
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 8px 12px;
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
      color: #4084d9;
    }

    .checkbox {
      width: 16px;
      height: 16px;
      background-color: $square-bg;
      border: 1px solid $square-border;
      border-radius: 1px;
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
      background-color: $square-bg;
      border: 1px solid $square-border;
    }
  }
}

/* ===== 大图标按钮列 ===== */
.col-large {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-width: 52px;
  padding: 4px 6px;
  border-radius: 3px;
  cursor: pointer;

  &:hover {
    background-color: #f0f6ff;
    outline: 1px solid #cfe0fb;
  }

  .big-icon {
    width: 36px;
    height: 36px;
    background-color: $square-bg;
    border: 1px solid $square-border;
    border-radius: 2px;
  }

  .big-label {
    margin-top: 5px;
    font-size: 13px;
    color: #333;
    white-space: nowrap;
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
    background-color: $square-bg;
    border: 1px solid $square-border;
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
      background-color: #f0f6ff;
      color: #4084d9;
    }

    .d-checkbox {
      width: 14px;
      height: 14px;
      background-color: #d7d7d7;
      border: 1px solid #c2c2c2;
      border-radius: 1px;
      flex-shrink: 0;
    }

    .d-label {
      line-height: 1;
    }
  }
}
</style>

