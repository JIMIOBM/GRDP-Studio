<script setup>
defineProps({ modelValue: { type: Number, default: null }, label: String, min: Number, max: Number, step: { type: [String, Number], default: 'any' }, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
</script>
<template><label class="pipeline-field"><span>{{ label }}</span><input type="number" :value="modelValue" :min="min" :max="max" :step="step" :disabled="disabled" @input="emit('update:modelValue', $event.target.value === '' ? null : Number($event.target.value))" /></label></template>
<style scoped>
.pipeline-field { display: block; margin: 0 0 11px; font-size: 12px; }.pipeline-field span { display: block; margin-bottom: 5px; }.pipeline-field input { width: 100%; height: 28px; box-sizing: border-box; padding: 0 8px; border: 1px solid #aaa; border-radius: 3px; background: white; color: #333; font: inherit; }.pipeline-field input:disabled { background: #f0f0f0; color: #888; }.pipeline-field input:focus { outline: 1px solid #409eff; border-color: #409eff; }
</style>
