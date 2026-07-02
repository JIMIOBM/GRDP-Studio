<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'

const fileList = ref([])

const onChange = (file) => {
  ElMessage.success(`已选择文件：${file.name}`)
}
const submit = () => {
  if (!fileList.value.length) return ElMessage.warning('请先选择文件')
  ElMessage.success('文件已提交，等待后端解析')
}
</script>

<template>
  <div class="input-file">
    <h2>导入文件</h2>
    <el-upload
      class="uploader"
      drag
      :auto-upload="false"
      :file-list="fileList"
      :on-change="onChange"
      multiple
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">将文件拖到此处，或<em>点击上传</em></div>
      <template #tip>
        <div class="el-upload__tip">支持 .xlsx / .csv / .txt 等数据文件</div>
      </template>
    </el-upload>
    <el-button type="primary" style="margin-top: 16px" @click="submit">提交解析</el-button>
  </div>
</template>

<style lang="scss" scoped>
.input-file {
  padding: 40px;
  max-width: 720px;
  margin: 0 auto;
  h2 { margin-bottom: 20px; color: #333; }
}
</style>
