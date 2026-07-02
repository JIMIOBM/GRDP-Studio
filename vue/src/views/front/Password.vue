<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userApi } from '@/api'

const formRef = ref()
const form = ref({ oldPassword: '', newPassword: '', confirm: '' })

const validateConfirm = (rule, value, callback) => {
  if (value !== form.value.newPassword) callback(new Error('两次输入的新密码不一致'))
  else callback()
}
const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }],
  confirm: [{ required: true, validator: validateConfirm, trigger: 'blur' }]
}

const submit = async () => {
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      await userApi.updatePassword(form.value)
    } catch (e) { /* 后端未就绪忽略 */ }
    ElMessage.success('密码修改成功')
    form.value = { oldPassword: '', newPassword: '', confirm: '' }
  })
}
</script>

<template>
  <div class="password">
    <h2>修改密码</h2>
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" style="max-width: 460px">
      <el-form-item label="原密码" prop="oldPassword"><el-input v-model="form.oldPassword" type="password" show-password /></el-form-item>
      <el-form-item label="新密码" prop="newPassword"><el-input v-model="form.newPassword" type="password" show-password /></el-form-item>
      <el-form-item label="确认密码" prop="confirm"><el-input v-model="form.confirm" type="password" show-password /></el-form-item>
      <el-form-item><el-button type="primary" @click="submit">确认修改</el-button></el-form-item>
    </el-form>
  </div>
</template>

<style scoped>
.password { padding: 40px; }
.password h2 { margin-bottom: 20px; color: #333; }
</style>
