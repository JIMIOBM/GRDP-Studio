<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { projectName } from '../../config/config.default'
import { disconnectNotifySocket } from '@/utils/notifySocket'

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

const loginDockerPlatform = async (credentials) => {
  const response = await fetch('/docker-auth/login', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(credentials)
  })
  const result = await response.json().catch(() => ({}))

  // 不能把代理返回的 HTML 或不完整响应当作登录成功。
  if (!response.ok || result.success !== true || !result.account?.id) {
    throw new Error(result.message || '登录认证失败，请确认认证服务已启动')
  }
  return result.account
}

const enterIpr = async (account) => {
  localStorage.setItem('account', JSON.stringify(account))
  ElMessage.success('登录成功')
  // 整页进入工作台，清除上一账号留在内存中的目录/结果/通知连接。
  // 新页面中的 HTTP 和 WebSocket 会自动使用刚写入的 HttpOnly 会话 Cookie。
  window.location.replace('/ipr')
}

const onLogin = async () => {
  if (loading.value) return
  try {
    // 自动填充可能没有触发 input 事件；提交时以输入框当前值为准，再同步表单校验。
    const values = new FormData(formRef.value.$el)
    const credentials = {
      username: String(values.get('username') ?? '').trim(),
      // 密码中的空格可能是有效字符，不能做 trim 或其它隐式转换。
      password: String(values.get('password') ?? '')
    }
    Object.assign(form.value, credentials)
    loading.value = true
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return
    disconnectNotifySocket()
    localStorage.removeItem('account')
    const account = await loginDockerPlatform(credentials)
    form.value.password = ''
    await enterIpr(account)
  } catch (error) {
    ElMessage.error(error.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-title">{{ projectName }}</div>
      <div class="login-subtitle">解析融合一体化工作平台</div>

      <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent @keyup.enter="onLogin">
        <el-form-item prop="username">
          <el-input v-model="form.username" name="username" autocomplete="username" placeholder="用户名" size="large" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" name="password" autocomplete="current-password" type="password" placeholder="密码" size="large" show-password :prefix-icon="Lock" />
        </el-form-item>
        <el-form-item>
          <el-button class="login-btn" type="primary" size="large" native-type="button" :loading="loading" @click="onLogin">登 录</el-button>
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
