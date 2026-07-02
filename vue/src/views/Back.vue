<script setup>
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { projectName } from '../../config/config.default'
import { HomeFilled, User, Avatar, Lock, SwitchButton } from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()
const account = ref(
  localStorage.getItem('account') ? JSON.parse(localStorage.getItem('account')) : {}
)
const activeMenu = computed(() => route.path)

const logout = () => {
  localStorage.removeItem('account')
  ElMessage.success('退出成功')
  router.push('/login')
}
</script>

<template>
  <el-container class="back-container">
    <el-aside width="210px" class="back-aside">
      <div class="aside-logo">{{ projectName }}</div>
      <el-menu router :default-active="activeMenu" class="aside-menu" background-color="#2d2d2d" text-color="#cfcfcf" active-text-color="#f4d000">
        <el-menu-item index="/back/home"><el-icon><HomeFilled /></el-icon><span>首页</span></el-menu-item>
        <el-menu-item index="/back/user"><el-icon><User /></el-icon><span>用户管理</span></el-menu-item>
        <el-menu-item index="/back/admin"><el-icon><Avatar /></el-icon><span>管理员管理</span></el-menu-item>
        <el-menu-item index="/back/person"><el-icon><Avatar /></el-icon><span>个人信息</span></el-menu-item>
        <el-menu-item index="/back/password"><el-icon><Lock /></el-icon><span>修改密码</span></el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="back-header">
        <span class="header-title">后台管理系统</span>
        <el-dropdown @command="(c) => c === 'logout' && logout()">
          <span class="header-user">{{ account.nickname || account.username || '管理员' }}</span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">
                <el-icon><SwitchButton /></el-icon> 退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <el-main class="back-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style lang="scss" scoped>
.back-container {
  height: 100vh;
}

.back-aside {
  background-color: #2d2d2d;

  .aside-logo {
    height: 60px;
    line-height: 60px;
    text-align: center;
    color: #f4d000;
    font-size: 18px;
    font-weight: 700;
    border-bottom: 1px solid #3a3a3a;
  }

  .aside-menu {
    border-right: none;
  }
}

.back-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #fff;
  border-bottom: 1px solid #eee;

  .header-title {
    font-size: 16px;
    font-weight: 600;
    color: #333;
  }

  .header-user {
    cursor: pointer;
    color: #4084d9;
  }
}

.back-main {
  background-color: #f5f5f5;
}
</style>
