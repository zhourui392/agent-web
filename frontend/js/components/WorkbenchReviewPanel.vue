<template>
  <section class="workbench-review-panel" data-test="review-opinion">
    <div class="workbench-review-heading">
      <div>
        <span>人工 Review</span>
        <strong>记录意见并显式确认重构</strong>
      </div>
      <div class="workbench-review-proof">
        <el-tag v-if="opinion" effect="plain">
          Opinion v{{ opinion.version }} · {{ shortHash(opinion.contentHash) }}
        </el-tag>
        <el-tag v-if="confirmed" type="success">已精确确认</el-tag>
        <el-tag v-else type="warning" effect="plain">未授权修改</el-tag>
      </div>
    </div>

    <el-alert
      v-if="error"
      type="error"
      show-icon
      :closable="false"
      :title="error"
    />
    <el-alert
      v-if="notice"
      type="success"
      show-icon
      :closable="false"
      :title="notice"
    />

    <p class="workbench-review-guidance">
      先让 Agent 只读解释影响；需要修改时，保存当前意见并单独确认。确认只绑定当前版本与 Hash，文字变化后立即失效。
    </p>
    <el-input
      :model-value="modelValue"
      type="textarea"
      :rows="4"
      :maxlength="16000"
      show-word-limit
      resize="vertical"
      placeholder="写下需要 Agent 解释或执行的 Review 意见、重构目标及回归测试要求"
      :disabled="readOnly || loading || saving || confirming"
      @update:model-value="updateText"
    />

    <div class="workbench-review-actions">
      <small v-if="opinion && !draftMatchesOpinion">当前文字尚未绑定到已保存 Opinion。</small>
      <small v-else-if="confirmation">Confirmation {{ shortId(confirmation.confirmationId) }}</small>
      <span></span>
      <el-button
        :loading="saving"
        :disabled="readOnly || !canSave"
        data-test="review-save-opinion"
        @click="emit('save-opinion')"
      >
        保存 Opinion
      </el-button>
      <el-button
        type="primary"
        :loading="confirming"
        :disabled="readOnly || !canConfirm"
        data-test="review-confirm-modification"
        @click="emit('confirm-modification')"
      >
        确认按此意见修改
      </el-button>
    </div>
  </section>
</template>

<script setup>
/**
 * Review Opinion/Confirmation presentational panel.
 *
 * @author alex
 * @since 2026-08-01
 */
defineProps({
  modelValue: { type: String, required: true },
  opinion: { type: Object, default: null },
  confirmation: { type: Object, default: null },
  loading: { type: Boolean, required: true },
  saving: { type: Boolean, required: true },
  confirming: { type: Boolean, required: true },
  error: { type: String, default: null },
  notice: { type: String, default: null },
  readOnly: { type: Boolean, required: true },
  draftMatchesOpinion: { type: Boolean, required: true },
  confirmed: { type: Boolean, required: true },
  canSave: { type: Boolean, required: true },
  canConfirm: { type: Boolean, required: true },
});

const emit = defineEmits([
  'update:modelValue',
  'save-opinion',
  'confirm-modification',
]);

function updateText(value) {
  if (typeof value === 'string') emit('update:modelValue', value);
}

function shortHash(value) {
  return `${value.slice(0, 10)}…`;
}

function shortId(value) {
  return value.length > 24 ? `${value.slice(0, 21)}…` : value;
}
</script>
