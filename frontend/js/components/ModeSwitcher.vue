<template>
  <el-dropdown trigger="click" @command="onCommand">
    <span class="mode-switcher" :title="currentLabel">
      <el-icon><magic-stick /></el-icon>
      <span class="mode-label">{{ currentLabel }}</span>
      <el-icon><arrow-down /></el-icon>
    </span>
    <template #dropdown>
      <el-dropdown-menu>
        <template v-for="group in groups" :key="group.key">
          <div v-if="group.label" class="group-label">{{ group.label }}</div>
          <el-dropdown-item
            v-for="option in group.options"
            :key="option.value"
            :command="option.value"
            :disabled="disabled"
            :class="{ 'is-active': option.value === modelValue }">
            <div class="option-content">
              <div class="option-label">{{ option.label }}</div>
              <div v-if="option.description" class="option-desc">{{ option.description }}</div>
            </div>
          </el-dropdown-item>
        </template>
        <div class="group-label manage-entry" @click="$emit('manage')">模式管理…</div>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script>
import { ArrowDown, MagicStick } from '@element-plus/icons-vue';

/**
 * 顶部模式切换器：默认 / 我的模式 / 模板库 三组下拉。
 * 模板项由父层负责 fork 后切换（command 透传原始 value）。
 *
 * @author alex
 * @since 2026-08-20
 */
export default {
  name: 'ModeSwitcher',
  components: { ArrowDown, MagicStick },
  props: {
    groups: { type: Array, required: true },
    modelValue: { type: String, default: '' },
    currentLabel: { type: String, default: '默认模式' },
    disabled: { type: Boolean, default: false },
  },
  emits: ['update:modelValue', 'manage'],
  setup(props, { emit }) {
    const onCommand = (value) => {
      emit('update:modelValue', value);
    };
    return { onCommand };
  },
};
</script>

<style scoped>
.mode-switcher {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 13px;
  color: #303133;
  flex-shrink: 0;
}
.mode-switcher:hover {
  background: #f5f7fa;
}
.mode-label {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.group-label {
  padding: 4px 12px;
  font-size: 12px;
  color: #909399;
}
.manage-entry {
  cursor: pointer;
  color: #409eff;
  border-top: 1px solid #ebeef5;
  margin-top: 4px;
  padding-top: 8px;
}
.option-content {
  max-width: 320px;
}
.option-label {
  font-size: 13px;
}
.option-desc {
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.is-active {
  color: #409eff;
  font-weight: 600;
}
</style>
