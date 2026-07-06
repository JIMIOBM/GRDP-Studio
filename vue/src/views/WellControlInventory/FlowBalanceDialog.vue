<script setup>
import { ref } from "vue";
import { flowBalanceApi } from "@/api/docker";

const props = defineProps({
  visible: { type: Boolean, default: false },
  selectedNode: { type: Object, default: null },
});
const emit = defineEmits(["update:visible"]);

const close = () => emit("update:visible", false);

const formRef = ref();
const form = ref({
  gasReservoirId: 169,
  projectId: 235,
  analysisType: 0,
  wellNames: "P101-2H",
  waterGasRatioLimit: -1,
});

const rules = {
  gasReservoirId: [
    { required: true, message: "请输入气藏ID", trigger: "blur" },
  ],
  projectId: [{ required: true, message: "请输入项目ID", trigger: "blur" }],
  analysisType: [
    { required: true, message: "请选择分析类型", trigger: "change" },
  ],
  wellNames: [
    { required: true, message: "请输入至少一口井名", trigger: "blur" },
  ],
};

const loading = ref(false);
const resultText = ref("");

function parseWells(str) {
  return String(str || "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

const submit = async () => {
  await formRef.value.validate(async (valid) => {
    if (!valid) return;

    loading.value = true;
    resultText.value = "";

    const payload = {
      gasReservoirId: Number(form.value.gasReservoirId),
      projectId: Number(form.value.projectId),
      analysisType: Number(form.value.analysisType),
      wellNames: parseWells(form.value.wellNames),
      waterGasRatioLimit: Number(form.value.waterGasRatioLimit),
    };

    const r = await flowBalanceApi.analyze(payload);
    if (r && r.ok) {
      resultText.value = JSON.stringify(r.data, null, 2);
    } else {
      resultText.value = r?.message || "未连接后端服务";
    }

    loading.value = false;
  });
};

const reset = () => {
  resultText.value = "";
  formRef.value?.resetFields();
};
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="流动平衡分析"
    width="720px"
    draggable
    destroy-on-close
    @close="close"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="110px"
      label-position="right"
    >
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="气藏 ID" prop="gasReservoirId">
            <el-input-number
              v-model="form.gasReservoirId"
              :min="0"
              :controls="false"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="项目 ID" prop="projectId">
            <el-input-number
              v-model="form.projectId"
              :min="0"
              :controls="false"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="分析类型" prop="analysisType">
            <el-input-number
              v-model="form.analysisType"
              :min="0"
              :controls="false"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="气水比限值" prop="waterGasRatioLimit">
            <el-input-number
              v-model="form.waterGasRatioLimit"
              :controls="false"
              style="width: 100%"
              placeholder="-1 表示不限"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="井名" prop="wellNames">
        <el-input
          v-model="form.wellNames"
          placeholder="多口井用英文逗号分隔，如：P101-2H, P102-3H"
          clearable
        />
      </el-form-item>
    </el-form>

    <div v-if="resultText" class="result-box">
      <div class="result-title">
        <span>分析结果</span>
        <el-button size="small" text @click="resultText = ''">清除</el-button>
      </div>
      <pre class="result-text">{{ resultText }}</pre>
    </div>

    <template #footer>
      <el-button @click="reset">重置</el-button>
      <el-button @click="close">关闭</el-button>
      <el-button type="primary" :loading="loading" @click="submit">
        执行分析
      </el-button>
    </template>
  </el-dialog>
</template>

<style lang="scss" scoped>
.result-box {
  margin-top: 16px;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  overflow: hidden;

  .result-title {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 6px 12px;
    background-color: #f5f5f5;
    border-bottom: 1px solid #e0e0e0;
    font-size: 13px;
    font-weight: 600;
    color: #333;
  }

  .result-text {
    margin: 0;
    padding: 12px;
    font-size: 13px;
    line-height: 1.8;
    color: #333;
    background-color: #fafafa;
    max-height: 320px;
    overflow-y: auto;
    white-space: pre-wrap;
    word-break: break-all;
  }
}
</style>