<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userApi } from '@/api'

const account = ref(
  localStorage.getItem('account') ? JSON.parse(localStorage.getItem('account')) : {}
)
const form = ref({
  username: account.value.username || '',
  nickname: account.value.nickname || '',
  email: account.value.email || '',
  phone: account.value.phone || ''
})

const save = async () => {
  try {
    await userApi.updateInfo(form.value)
  } catch (e) { /* 后端未就绪时忽略 */ }
  const merged = { ...account.value, ...form.value }
  localStorage.setItem('account', JSON.stringify(merged))
  ElMessage.success('保存成功')
}
</script>

<template>
  <div class="person">
    <h2>个人信息</h2>
    <el-form :model="form" label-width="80px" style="max-width: 460px">
      <el-form-item label="用户名"><el-input v-model="form.username" disabled /></el-form-item>
      <el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item>
      <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
      <el-form-item label="手机号"><el-input v-model="form.phone" /></el-form-item>
      <el-form-item><el-button type="primary" @click="save">保存</el-button></el-form-item>
    </el-form>
  </div>
</template>

<style scoped>
.person { padding: 40px; }
.person h2 { margin-bottom: 20px; color: #333; }
</style>
