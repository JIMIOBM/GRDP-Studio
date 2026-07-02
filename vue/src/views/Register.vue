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
  password: '',
  confirm: ''
})

const validateConfirm = (rule, value, callback) => {
  if (value !== form.value.password) callback(new Error('两次输入的密码不一致'))
  else callback()
}

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  confirm: [{ required: true, validator: validateConfirm, trigger: 'blur' }]
}

const onRegister = async () => {
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await userApi.register({ username: form.value.username, password: form.value.password })
      ElMessage.success('注册成功，请登录')
      router.push('/login')
    } catch (e) {
      ElMessage.error('注册失败，请稍后重试')
    } finally {
      loading.value = false
    }
  })
}
</script>

<template>
  <div class="register-page">
    <div class="register-card">
      <div class="register-title">注册 · {{ projectName }}</div>

      <el-form ref="formRef" :model="form" :rules="rules">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" size="large" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password :prefix-icon="Lock" />
        </el-form-item>
        <el-form-item prop="confirm">
          <el-input v-model="form.confirm" type="password" placeholder="确认密码" size="large" show-password :prefix-icon="Lock" />
        </el-form-item>
        <el-form-item>
          <el-button class="register-btn" type="primary" size="large" :loading="loading" @click="onRegister">注 册</el-button>
        </el-form-item>
      </el-form>

      <div class="register-footer">
        已有账号？
        <router-link to="/login" class="link">去登录</router-link>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
$accent-yellow: #f4d000;

.register-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #2d2d2d 0%, #3a3f4b 100%);
}

.register-card {
  width: 380px;
  padding: 40px 36px 28px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.3);
  border-top: 4px solid $accent-yellow;

  .register-title {
    font-size: 22px;
    font-weight: 700;
    color: #2d2d2d;
    text-align: center;
    margin-bottom: 26px;
  }

  .register-btn {
    width: 100%;
    background-color: #2d2d2d;
    border-color: #2d2d2d;
  }

  .register-footer {
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
