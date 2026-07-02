<script setup>
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { projectName } from '../../config/config.default'
import { User, Lock, SwitchButton } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

// 路由实例
const router = useRouter()
const route = useRoute()

// 用户信息
const account = ref(
    localStorage.getItem('account') ? JSON.parse(localStorage.getItem('account')) : {}
)

// 当前激活的菜单项
const activeMenu = computed(() => route.path)

// 退出登录
const logout = () => {
  localStorage.removeItem('account')
  ElMessage.success('退出成功')
  router.push('/login')
}

const handleUpdateAccount = (updatedAccount) => {
  // 更新父组件中的用户信息
  account.value = updatedAccount
}
</script>

<template>
  <div class="front-container">
    <!-- 顶部导航栏 -->
    <header class="header-nav">
      <div class="header-left-warp">
        <div class="logo-warp">
          <div class="logo">
            <img src="../../config/logo.svg" alt="Logo" />
          </div>
          <div class="logo-text">{{ projectName }}</div>
        </div>

        <div class="header-navs">
          <el-menu
              router
              :default-active="activeMenu"
              mode="horizontal"
              :ellipsis="false"
          >
            <!--前台路由-->
            <el-menu-item index="/front/home">前台首页</el-menu-item>
            <el-menu-item index="/front/input_file">导入文件</el-menu-item>
            <el-menu-item index="/ipr">解析融合工作台</el-menu-item>
            <!--前台路由-->
          </el-menu>
        </div>
      </div>

      <div class="user-warp">
        <!-- 未登录状态显示登录注册按钮 -->
        <template v-if="!account.id">
          <div class="btn-login">
            <el-button @click="router.push('/login')">登录</el-button>
          </div>
          <div class="btn-login" style="margin-left: 10px">
            <el-button @click="router.push('/register')">注册</el-button>
          </div>
        </template>

        <!-- 已登录状态显示用户头像和下拉菜单 -->
        <el-dropdown v-else class="custom-dropdown">
          <div class="user-avatar">
            <img :src="account.avatarUrl || '../../config/logo.svg'" />
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item>{{ account.nickname || account.username }}</el-dropdown-item>
              <el-dropdown-item>
                <router-link to="/front/person" class="dropdown-link">
                  <el-icon><User /></el-icon>
                  <span>个人信息</span>
                </router-link>
              </el-dropdown-item>
              <el-dropdown-item>
                <router-link to="/front/password" class="dropdown-link">
                  <el-icon><Lock /></el-icon>
                  <span>修改密码</span>
                </router-link>
              </el-dropdown-item>
              <el-dropdown-item>
                <div @click="logout" class="dropdown-link">
                  <el-icon><SwitchButton /></el-icon>
                  <span>退出登录</span>
                </div>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <!-- 主内容区域 -->
    <div class="main-content">
      <router-view @update-account="handleUpdateAccount"></router-view>
    </div>

    <!-- 页脚 -->
    <footer class="front-footer">
      <p>© {{ new Date().getFullYear() }} {{ projectName }}. 保留所有权利</p>
    </footer>
  </div>
</template>

<style lang="scss" scoped>

/*定义前台头部 背景 主题色*/
$front-back-color: #fff;

/*定义前台头部 字体 主题色*/
$front-font-color: #4084d9;

.front-container {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.header-nav {
  z-index: 1800;
  position: sticky;
  top: 0;
  height: 70px;
  background-color: $front-back-color;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 40px;
  box-shadow: 0 2px 10px 0 rgba(0, 0, 0, 0.1);
  overflow: visible;

  .header-left-warp {
    display: flex;
    align-items: center;
    height: 100%;

    .logo-warp {
      display: flex;
      align-items: center;
      margin-left: 20px;

      .logo {
        width: 30px;
        height: 30px;
        margin-right: 10px;

        img {
          width: 100%;
          height: 100%;
          object-fit: cover;
        }
      }

      .logo-text {
        font-size: 22px;
        font-weight: 500;
        color: $front-font-color;
      }

    }

    .header-navs{
      margin-left: 80px;
      height: 100%;

      .el-menu {
        background-color: $front-back-color !important;
        border: none !important;
        height: 70px !important;
      }

      .el-menu-item {
        height: 70px !important;
        line-height: 70px !important;
        border: none !important;
      }

      .el-menu-item:hover {
        color: $front-font-color !important;
        background-color: transparent !important;
      }

      .el-menu-item.is-active {
        color: $front-font-color !important;
        background-color: transparent !important;
        border: none !important;
      }

    }

  }

  .user-warp {
    display: flex;
    align-items: center;
    margin-right: 20px;
    height: 100%; /* 确保高度与父元素一致 */

    .btn-login {
      margin-top: 0;
    }

    .user-avatar {
      width: 40px;
      height: 40px;
      border-radius: 50%;
      overflow: hidden;
      border: 1px solid $front-font-color;
      padding: 2px;
      cursor: pointer;
      outline: none !important;

      img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        border-radius: 50%;
      }

    }

    .dropdown-link {
      display: flex;
      align-items: center;
      color: inherit;
      text-decoration: none;

      .el-icon {
        margin-right: 8px;
      }
    }

  }
}

.main-content {
  flex: 1;
  background-color: #fff;
}

.front-footer {
  padding: 16px 24px;
  text-align: center;
  background-color: #fff;
  color: #666;
  font-size: 12px;
  border-top: 1px solid #eee;
}

</style>
