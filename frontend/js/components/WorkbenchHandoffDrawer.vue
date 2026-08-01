<template>
  <el-drawer :model-value="visible" size="min(760px, 94vw)" destroy-on-close @close="emit('close')">
    <template #header>
      <div class="handoff-drawer-heading">
        <div>
          <small>PHASE HANDOFF</small>
          <h2>阶段交接</h2>
        </div>
        <el-tag v-if="current" effect="plain">v{{ current.version }}</el-tag>
        <el-tag v-if="readOnly" type="info">只读</el-tag>
      </div>
    </template>

    <div v-loading="loading" class="handoff-drawer-body">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-alert v-if="notice" :title="notice" type="success" :closable="false" show-icon />

      <section v-if="conflict" class="handoff-conflict" data-test="handoff-conflict">
        <div>
          <h3>检测到并发更新</h3>
          <p>本地草稿已保留。服务端当前版本为 v{{ conflict.version }}：</p>
          <blockquote>{{ conflict.summary }}</blockquote>
        </div>
        <el-button :disabled="readOnly || saving" @click="emit('reload-current')"> 使用服务端版本 </el-button>
      </section>

      <section v-if="source" class="handoff-source">
        <div class="handoff-section-heading">
          <h3>上游 Source View</h3>
          <el-tag v-if="source.stale" type="warning">有新版本</el-tag>
          <el-tag v-else-if="source.reception" type="success">已接收</el-tag>
          <el-tag v-else type="info">尚未接收</el-tag>
        </div>
        <template v-if="source.latestSource">
          <p>
            {{ source.latestSource.sourcePhase }} · v{{ source.latestSource.version }} ·
            {{ shortHash(source.latestSource.contentHash) }}
          </p>
          <blockquote>{{ source.latestSource.summary }}</blockquote>
          <div v-if="source.reception" class="handoff-reception">
            当前接收：{{ source.reception.sourcePhase }} v{{ source.reception.sourceVersion }}
          </div>
          <div
            v-if="source.stale && !keepCurrentDismissed"
            class="handoff-source-stale"
            data-test="handoff-source-stale"
          >
            <p>{{ diffText(source.diff) }}</p>
            <el-button
              type="primary"
              :loading="accepting"
              :disabled="readOnly || accepting"
              @click="emit('accept-latest')"
            >
              接受新版本
            </el-button>
            <el-button :disabled="readOnly || accepting" @click="emit('keep-current')"> 保留当前版本 </el-button>
          </div>
          <el-button
            v-else-if="!source.reception"
            type="primary"
            :loading="accepting"
            :disabled="readOnly || accepting"
            @click="emit('accept-latest')"
          >
            接受此版本
          </el-button>
        </template>
        <el-empty v-else description="当前阶段没有默认上游 Handoff" :image-size="56" />
      </section>

      <section class="handoff-section" data-test="handoff-summary">
        <div class="handoff-section-heading">
          <h3>Summary</h3>
          <small>阶段结论摘要，最多 8000 字符</small>
        </div>
        <el-input
          type="textarea"
          :rows="6"
          :maxlength="8000"
          show-word-limit
          :disabled="readOnly || saving"
          :model-value="draft.summary"
          @update:model-value="updateSummary"
        />
      </section>

      <section class="handoff-section" data-test="handoff-decisions">
        <div class="handoff-section-heading">
          <h3>Decisions</h3>
          <el-button :disabled="readOnly || saving" @click="addDecision">新增决定</el-button>
        </div>
        <div v-for="(decision, index) in draft.decisions" :key="`decision-${index}`" class="handoff-row">
          <el-input
            :model-value="decision.text"
            :maxlength="2000"
            :disabled="readOnly || saving"
            placeholder="已确认的决定"
            @update:model-value="(value) => updateDecision(index, 'text', value)"
          />
          <el-input
            :model-value="decision.rationale || ''"
            :maxlength="2000"
            :disabled="readOnly || saving"
            placeholder="理由（可选）"
            @update:model-value="(value) => updateDecision(index, 'rationale', value)"
          />
          <el-button :disabled="readOnly || saving" @click="removeDecision(index)">删除</el-button>
        </div>
        <el-empty v-if="!draft.decisions.length" description="暂无决定" :image-size="48" />
      </section>

      <section class="handoff-section" data-test="handoff-open-questions">
        <div class="handoff-section-heading">
          <h3>Open Questions</h3>
          <el-button :disabled="readOnly || saving" @click="addQuestion">新增问题</el-button>
        </div>
        <div v-for="(question, index) in draft.openQuestions" :key="`question-${index}`" class="handoff-row">
          <el-input
            :model-value="question.text"
            :maxlength="2000"
            :disabled="readOnly || saving"
            placeholder="尚待澄清的问题"
            @update:model-value="(value) => updateQuestion(index, 'text', value)"
          />
          <el-input
            :model-value="question.ownerHint || ''"
            :maxlength="2000"
            :disabled="readOnly || saving"
            placeholder="负责人提示（可选）"
            @update:model-value="(value) => updateQuestion(index, 'ownerHint', value)"
          />
          <el-button :disabled="readOnly || saving" @click="removeQuestion(index)">删除</el-button>
        </div>
        <el-empty v-if="!draft.openQuestions.length" description="暂无开放问题" :image-size="48" />
      </section>

      <section class="handoff-section" data-test="handoff-pinned-files">
        <div class="handoff-section-heading">
          <h3>Pinned Files</h3>
          <el-button :disabled="readOnly || saving" @click="addPinnedFile">新增文件</el-button>
        </div>
        <div v-for="(file, index) in draft.pinnedFiles" :key="fileKey(file, index)" class="handoff-row">
          <el-input
            :model-value="file.repositoryKey"
            :disabled="readOnly || saving"
            placeholder="repositoryKey"
            @update:model-value="(value) => updatePinnedFile(index, 'repositoryKey', value)"
          />
          <el-input
            :model-value="file.relativePath"
            :disabled="readOnly || saving"
            placeholder="relative/path.md"
            @update:model-value="(value) => updatePinnedFile(index, 'relativePath', value)"
          />
          <el-button link type="primary" @click="emit('open-document', { ...file })">
            {{ file.repositoryKey }}/{{ file.relativePath }}
          </el-button>
          <el-button :disabled="readOnly || saving" @click="removePinnedFile(index)">删除</el-button>
        </div>
        <el-empty v-if="!draft.pinnedFiles.length" description="暂无 Pinned File" :image-size="48" />
      </section>

      <section class="handoff-section" data-test="handoff-referenced-runs">
        <div class="handoff-section-heading">
          <h3>Referenced Runs</h3>
          <el-button :disabled="readOnly || saving" @click="addRun">新增 Run</el-button>
        </div>
        <div v-for="(run, index) in draft.referencedRuns" :key="run.runId || `run-${index}`" class="handoff-row">
          <el-input
            :model-value="run.runId"
            :disabled="readOnly || saving"
            placeholder="runId"
            @update:model-value="(value) => updateRunId(index, value)"
          />
          <span>{{ run.phase }}</span>
          <small v-if="run.safeSummary">{{ run.safeSummary }}</small>
          <el-button :disabled="readOnly || saving" @click="removeRun(index)">删除</el-button>
        </div>
        <el-empty v-if="!draft.referencedRuns.length" description="暂无 Referenced Run" :image-size="48" />
      </section>
    </div>

    <template #footer>
      <div class="handoff-drawer-footer">
        <el-button @click="emit('close')">关闭</el-button>
        <span></span>
        <el-button @click="emit('refresh-source')">刷新上游</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="readOnly || loading || saving || !dirty"
          data-test="handoff-save"
          @click="emit('save')"
        >
          保存 Handoff
        </el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
/**
 * TD-07 独立的 Phase Handoff 展示与人工编辑 Drawer。
 *
 * @author alex
 * @since 2026-08-01
 */
const props = defineProps({
  visible: { type: Boolean, required: true },
  current: { type: Object, default: null },
  draft: { type: Object, required: true },
  source: { type: Object, default: null },
  conflict: { type: Object, default: null },
  loading: { type: Boolean, required: true },
  saving: { type: Boolean, required: true },
  accepting: { type: Boolean, required: true },
  error: { type: String, default: null },
  notice: { type: String, default: null },
  dirty: { type: Boolean, required: true },
  readOnly: { type: Boolean, required: true },
  keepCurrentDismissed: { type: Boolean, required: true },
  phase: { type: String, required: true },
});

const emit = defineEmits([
  'close',
  'update:draft',
  'save',
  'reload-current',
  'refresh-source',
  'accept-latest',
  'keep-current',
  'open-document',
]);

function updateSummary(value) {
  if (typeof value === 'string') updateDraft({ summary: value });
}

function addDecision() {
  updateDraft({ decisions: [...props.draft.decisions, { text: '', rationale: null }] });
}

function updateDecision(index, field, value) {
  if (typeof value !== 'string') return;
  const decisions = props.draft.decisions.map((item, itemIndex) =>
    itemIndex === index ? { ...item, [field]: field === 'rationale' && !value ? null : value } : { ...item },
  );
  updateDraft({ decisions });
}

function removeDecision(index) {
  updateDraft({ decisions: props.draft.decisions.filter((_, itemIndex) => itemIndex !== index) });
}

function addQuestion() {
  updateDraft({ openQuestions: [...props.draft.openQuestions, { text: '', ownerHint: null }] });
}

function updateQuestion(index, field, value) {
  if (typeof value !== 'string') return;
  const openQuestions = props.draft.openQuestions.map((item, itemIndex) =>
    itemIndex === index ? { ...item, [field]: field === 'ownerHint' && !value ? null : value } : { ...item },
  );
  updateDraft({ openQuestions });
}

function removeQuestion(index) {
  updateDraft({
    openQuestions: props.draft.openQuestions.filter((_, itemIndex) => itemIndex !== index),
  });
}

function addPinnedFile() {
  updateDraft({
    pinnedFiles: [...props.draft.pinnedFiles, { repositoryKey: '', relativePath: '' }],
  });
}

function updatePinnedFile(index, field, value) {
  if (typeof value !== 'string') return;
  const pinnedFiles = props.draft.pinnedFiles.map((item, itemIndex) =>
    itemIndex === index ? { ...item, [field]: value } : { ...item },
  );
  updateDraft({ pinnedFiles });
}

function removePinnedFile(index) {
  updateDraft({
    pinnedFiles: props.draft.pinnedFiles.filter((_, itemIndex) => itemIndex !== index),
  });
}

function addRun() {
  updateDraft({
    referencedRuns: [
      ...props.draft.referencedRuns,
      {
        runId: '',
        phase: props.phase,
        safeSummary: null,
      },
    ],
  });
}

function updateRunId(index, value) {
  if (typeof value !== 'string') return;
  const referencedRuns = props.draft.referencedRuns.map((item, itemIndex) =>
    itemIndex === index ? { ...item, runId: value } : { ...item },
  );
  updateDraft({ referencedRuns });
}

function removeRun(index) {
  updateDraft({
    referencedRuns: props.draft.referencedRuns.filter((_, itemIndex) => itemIndex !== index),
  });
}

function updateDraft(change) {
  if (props.readOnly) return;
  emit('update:draft', {
    summary: change.summary ?? props.draft.summary,
    decisions: (change.decisions ?? props.draft.decisions).map((item) => ({ ...item })),
    openQuestions: (change.openQuestions ?? props.draft.openQuestions).map((item) => ({ ...item })),
    pinnedFiles: (change.pinnedFiles ?? props.draft.pinnedFiles).map((item) => ({ ...item })),
    referencedRuns: (change.referencedRuns ?? props.draft.referencedRuns).map((item) => ({ ...item })),
  });
}

function shortHash(value) {
  return value.length > 16 ? `${value.slice(0, 12)}…${value.slice(-4)}` : value;
}

function fileKey(file, index) {
  return `${file.repositoryKey}\u0000${file.relativePath}\u0000${index}`;
}

function diffText(diff) {
  if (!diff) return '上游 Handoff 已更新，请明确选择是否接受。';
  const changes = [
    diff.summaryChanged ? 'Summary 已变化' : null,
    countDiff('Decisions', diff.decisions.added, diff.decisions.removed),
    countDiff('Open Questions', diff.openQuestions.added, diff.openQuestions.removed),
    countDiff('Pinned Files', diff.pinnedFiles.added, diff.pinnedFiles.removed),
    countDiff('Referenced Runs', diff.referencedRuns.added, diff.referencedRuns.removed),
  ].filter(Boolean);
  return changes.length ? changes.join('；') : '上游版本标识已变化。';
}

function countDiff(label, added, removed) {
  return added || removed ? `${label} +${added}/-${removed}` : null;
}
</script>

<style scoped>
.handoff-drawer-heading,
.handoff-section-heading,
.handoff-drawer-footer,
.handoff-conflict {
  display: flex;
  align-items: center;
  gap: 12px;
}

.handoff-drawer-heading,
.handoff-section-heading,
.handoff-conflict {
  justify-content: space-between;
}

.handoff-drawer-heading h2,
.handoff-section h3,
.handoff-source h3,
.handoff-conflict h3 {
  margin: 0;
}

.handoff-drawer-body {
  display: grid;
  gap: 16px;
}

.handoff-section,
.handoff-source,
.handoff-conflict {
  display: grid;
  gap: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 14px;
}

.handoff-conflict {
  grid-template-columns: minmax(0, 1fr) auto;
  border-color: var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9);
}

.handoff-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
}

.handoff-row > .el-button--link {
  grid-column: 1 / -1;
  justify-self: start;
  overflow-wrap: anywhere;
}

blockquote {
  margin: 0;
  padding: 10px 12px;
  border-left: 3px solid var(--el-border-color);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.handoff-source-stale {
  padding: 10px;
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
}

.handoff-drawer-footer > span {
  flex: 1;
}

@media (max-width: 720px) {
  .handoff-row,
  .handoff-conflict {
    grid-template-columns: 1fr;
  }
}
</style>
