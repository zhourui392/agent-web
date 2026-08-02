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

    <div class="workbench-review-candidate-heading">
      <div>
        <strong>Agent Review Candidate</strong>
        <small>候选只保存在当前浏览器；采用后仍需人工保存 Opinion，再单独确认。</small>
      </div>
      <el-button
        size="small"
        :loading="candidateLoading"
        :disabled="readOnly || !canGenerateCandidate"
        data-test="review-generate-candidate"
        @click="emit('generate-candidate')"
      >
        生成候选
      </el-button>
    </div>
    <el-alert
      v-if="candidateError"
      type="error"
      show-icon
      :closable="false"
      :title="candidateError"
    />
    <div
      v-if="candidate"
      class="workbench-review-candidate-list"
      data-test="review-candidate-list"
    >
      <small>
        会话代次 {{ candidate.conversationGeneration }} · Opinion 基线
        v{{ candidate.baseOpinionVersion }} · {{ candidate.sourceMessageCount }} 条公开消息
      </small>
      <el-empty
        v-if="candidateItems.length === 0"
        :image-size="44"
        description="未识别到明确的 Review Candidate"
      />
      <article
        v-for="item in candidateItems"
        :key="item.itemId"
        class="workbench-review-candidate-item"
        data-test="review-candidate-item"
      >
        <div class="workbench-review-candidate-status">
          <el-tag v-if="item.decision === 'ACCEPTED'" type="success" size="small">已采用到草稿</el-tag>
          <el-tag v-else-if="item.decision === 'IGNORED'" type="info" size="small">已忽略</el-tag>
          <el-tag v-else type="warning" effect="plain" size="small">待人工处理</el-tag>
        </div>
        <label>
          <span>Review 意见</span>
          <el-input
            :model-value="item.finding"
            type="textarea"
            :rows="2"
            :maxlength="2000"
            :disabled="readOnly || item.decision !== 'PENDING'"
            @update:model-value="updateCandidateItem(item.itemId, 'finding', $event)"
          />
        </label>
        <label>
          <span>影响</span>
          <el-input
            :model-value="item.impact"
            type="textarea"
            :rows="2"
            :maxlength="4000"
            :disabled="readOnly || item.decision !== 'PENDING'"
            @update:model-value="updateCandidateItem(item.itemId, 'impact', $event)"
          />
        </label>
        <label>
          <span>建议修改</span>
          <el-input
            :model-value="item.suggestedChange"
            type="textarea"
            :rows="2"
            :maxlength="4000"
            :disabled="readOnly || item.decision !== 'PENDING'"
            @update:model-value="updateCandidateItem(item.itemId, 'suggestedChange', $event)"
          />
        </label>
        <div v-if="item.affectedFiles.length" class="workbench-review-candidate-references">
          <span>影响文件</span>
          <code v-for="file in item.affectedFiles" :key="`${file.repositoryKey}:${file.relativePath}`">
            {{ file.repositoryKey }}::{{ file.relativePath }}
          </code>
        </div>
        <div v-if="item.suggestedTests.length" class="workbench-review-candidate-references">
          <span>建议测试</span>
          <code v-for="test in item.suggestedTests" :key="test">{{ test }}</code>
        </div>
        <div class="workbench-review-candidate-actions">
          <el-button
            size="small"
            :disabled="readOnly || item.decision !== 'PENDING' || !item.finding.trim()"
            @click="emit('accept-candidate-item', item.itemId)"
          >
            采用到人工草稿
          </el-button>
          <el-button
            size="small"
            :disabled="readOnly || item.decision !== 'PENDING'"
            @click="emit('ignore-candidate-item', item.itemId)"
          >
            忽略
          </el-button>
        </div>
      </article>
    </div>

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
  candidate: { type: Object, default: null },
  candidateItems: { type: Array, required: true },
  candidateLoading: { type: Boolean, required: true },
  candidateError: { type: String, default: null },
  canGenerateCandidate: { type: Boolean, required: true },
});

const emit = defineEmits([
  'update:modelValue',
  'save-opinion',
  'confirm-modification',
  'generate-candidate',
  'update-candidate-item',
  'accept-candidate-item',
  'ignore-candidate-item',
]);

function updateText(value) {
  if (typeof value === 'string') emit('update:modelValue', value);
}

function updateCandidateItem(itemId, field, value) {
  if (typeof value === 'string') {
    emit('update-candidate-item', itemId, field, value);
  }
}

function shortHash(value) {
  return `${value.slice(0, 10)}…`;
}

function shortId(value) {
  return value.length > 24 ? `${value.slice(0, 21)}…` : value;
}
</script>
