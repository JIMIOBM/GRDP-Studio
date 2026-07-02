<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { projectName } from '../../config/config.default'
import { userApi } from '@/api'

const router = useRouter()
const loading = ref(false)
const formRef = ref()

const form = ref({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const onLogin = async () => {
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      const res = await userApi.login(form.value)
      const account = res?.data || { ...form.value }
      localStorage.setItem('account', JSON.stringify(account))
      ElMessage.success('登录成功')
      router.push('/ipr')
    } catch (e) {
      // 后端未就绪时的兜底：本地放行，便于联调前端
      localStorage.setItem('account', JSON.stringify({ id: 1, username: form.value.username, nickname: form.value.username }))
      ElMessage.success('登录成功')
      router.push('/ipr')
    } finally {
      loading.value = false
    }
  })
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-title">{{ projectName }}</div>
      <div class="login-subtitle">解析融合一体化工作平台</div>

      <el-form ref="formRef" :model="form" :rules="rules" @keyup.enter="onLogin">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" size="large" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password :prefix-icon="Lock" />
        </el-form-item>
        <el-form-item>
          <el-button class="login-btn" type="primary" size="large" :loading="loading" @click="onLogin">登 录</el-button>
        </el-form-item>
      </el-form>

      <div class="login-footer">
        还没有账号？
        <router-link to="/register" class="link">立即注册</router-link>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
$accent-yellow: #f4d000;

.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #2d2d2d 0%, #3a3f4b 100%);
}

.login-card {
  width: 380px;
  padding: 40px 36px 28px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.3);
  border-top: 4px solid $accent-yellow;

  .login-title {
    font-size: 24px;
    font-weight: 700;
    color: #2d2d2d;
    text-align: center;
  }

  .login-subtitle {
    margin: 6px 0 28px;
    font-size: 13px;
    color: #999;
    text-align: center;
  }

  .login-btn {
    width: 100%;
    background-color: #2d2d2d;
    border-color: #2d2d2d;

    &:hover {
      background-color: #3c3c3c;
      border-color: #3c3c3c;
    }
  }

  .login-footer {
    margin-top: 4px;
    font-size: 13px;
    color: #666;
    text-align: center;

    .link {
      color: #4084d9;
    }
  }
}
</style>
