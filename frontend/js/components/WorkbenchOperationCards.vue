<template>
  <section class="workbench-operation-section">
    <div class="workbench-operation-section-heading">
      <div>
        <span>类型化高影响操作</span>
        <small>commit、push、部署和生产写分别确认；聊天文字不构成授权。</small>
      </div>
      <div class="workbench-operation-heading-actions">
        <el-button text :loading="loading" @click="emit('refresh')">刷新</el-button>
        <el-button
          type="danger"
          plain
          data-test="open-operation-proposal"
          :disabled="readOnly"
          @click="openProposal"
        >
          新建高影响操作
        </el-button>
      </div>
    </div>
    <el-alert v-if="error" type="error" show-icon :closable="false" :title="error" />
    <el-alert v-if="notice" type="success" show-icon :closable="false" :title="notice" />

    <article
      v-for="operation in operations"
      :key="operation.operationId"
      class="workbench-operation-card"
      data-test="high-impact-operation"
    >
      <header>
        <div>
          <el-tag type="danger" effect="plain">{{ typeLabel(operation.type) }}</el-tag>
          <strong>{{ operation.safeSummary }}</strong>
        </div>
        <el-tag :type="statusType(operation.status)">{{ statusLabel(operation.status) }}</el-tag>
      </header>

      <dl>
        <template v-if="operation.target.repositoryKeys.length">
          <dt>仓库</dt>
          <dd>{{ operation.target.repositoryKeys.join('、') }}</dd>
        </template>
        <template v-for="entry in targetEntries(operation)" :key="entry.label">
          <dt>{{ entry.label }}</dt>
          <dd>{{ entry.value }}</dd>
        </template>
        <dt>目标证明</dt>
        <dd><code>{{ shortHash(operation.requestedPayloadHash) }}</code></dd>
      </dl>

      <el-alert
        v-if="operation.executionAvailable === false"
        type="warning"
        show-icon
        :closable="false"
        title="执行器未开放：批准只记录授权，不会自动执行。"
      />

      <div v-if="operation.status === 'PROPOSED'" class="workbench-operation-decision">
        <el-input
          :model-value="reasons[operation.operationId] || ''"
          :maxlength="2000"
          placeholder="说明已核对的目标、风险或拒绝原因（必填）"
          :disabled="readOnly || decidingId != null"
          @update:model-value="value => updateReason(operation.operationId, value)"
        />
        <el-button
          type="danger"
          plain
          :loading="decidingId === operation.operationId"
          :disabled="readOnly || decidingId != null || !validReason(operation.operationId)"
          @click="emitDecision(operation, 'REJECT')"
        >
          拒绝
        </el-button>
        <el-button
          type="primary"
          :loading="decidingId === operation.operationId"
          :disabled="readOnly || decidingId != null || !validReason(operation.operationId)"
          @click="emitDecision(operation, 'APPROVE')"
        >
          批准授权
        </el-button>
      </div>
      <p v-else-if="operation.decisionReason" class="workbench-operation-reason">
        决策理由：{{ operation.decisionReason }}
      </p>
    </article>

    <el-dialog
      v-model="proposalVisible"
      title="新建高影响操作提案"
      width="min(760px, 94vw)"
      append-to-body
      destroy-on-close
      @closed="resetProposal"
    >
      <div class="workbench-operation-proposal" data-test="operation-proposal-form">
        <el-alert
          type="warning"
          show-icon
          :closable="false"
          title="提交只会创建 PROPOSED 待决策记录，不代表授权，也不会执行操作。"
        />
        <el-alert v-if="error" type="error" show-icon :closable="false" :title="error" />
        <p class="workbench-operation-proposal-phase">当前阶段：{{ phase }}</p>

        <label class="workbench-operation-proposal-field">
          <span>操作类型</span>
          <el-select v-model="form.type" data-test="operation-proposal-type" style="width: 100%">
            <el-option label="Git Commit" value="GIT_COMMIT" />
            <el-option label="Git Push" value="GIT_PUSH" />
            <el-option label="本地部署" value="LOCAL_DEPLOY" />
            <el-option label="生产写入" value="PRODUCTION_WRITE" />
          </el-select>
        </label>

        <label class="workbench-operation-proposal-field">
          <span>Source Run</span>
          <el-select
            v-model="form.sourceRunId"
            data-test="operation-source-run"
            :loading="proposalSourceLoading"
            placeholder="选择当前阶段的真实 Run"
            style="width: 100%"
          >
            <el-option
              v-for="run in sourceRuns"
              :key="run.runId"
              :label="runLabel(run)"
              :value="run.runId"
            />
          </el-select>
          <small>仅列出当前 Workbench、当前阶段已记录的 Run，不推测工作目录。</small>
          <small data-test="operation-selected-source-run">
            已选择 Source Run：{{ form.sourceRunId || '未选择' }}
          </small>
        </label>

        <label class="workbench-operation-proposal-field workbench-operation-proposal-wide">
          <span>安全摘要与风险说明</span>
          <el-input
            v-model="form.safeSummary"
            :maxlength="2000"
            data-test="operation-safe-summary"
          />
        </label>

        <template v-if="form.type === 'GIT_COMMIT'">
          <label class="workbench-operation-proposal-field">
            <span>目标仓库</span>
            <el-select v-model="form.repositoryKey" data-test="operation-repository" style="width: 100%">
              <el-option
                v-for="repository in repositories"
                :key="repository.repositoryKey"
                :label="repositoryLabel(repository)"
                :value="repository.repositoryKey"
              />
            </el-select>
          </label>
          <label class="workbench-operation-proposal-field">
            <span>目标分支</span>
            <el-input v-model="form.branch" :maxlength="512" data-test="operation-branch" />
          </label>
          <label class="workbench-operation-proposal-field">
            <span>预期 HEAD</span>
            <el-input v-model="form.expectedHead" :maxlength="64" data-test="operation-expected-head" />
          </label>
          <label class="workbench-operation-proposal-field">
            <span>预期工作区状态 Hash</span>
            <el-input v-model="form.expectedStateHash" :maxlength="64" data-test="operation-state-hash" />
          </label>
          <label class="workbench-operation-proposal-field workbench-operation-proposal-wide">
            <span>包含的相对路径（每行一条）</span>
            <el-input
              v-model="form.includedPathsText"
              type="textarea"
              :rows="3"
              data-test="operation-included-paths"
              placeholder="src/main/java/example/App.java"
            />
          </label>
          <label class="workbench-operation-proposal-field workbench-operation-proposal-wide">
            <span>安全 Commit Message 预览</span>
            <el-input
              v-model="form.safeMessagePreview"
              type="textarea"
              :rows="3"
              :maxlength="500"
              data-test="operation-message-preview"
            />
            <small>浏览器会对规范化预览计算 SHA-256；Hash 不由用户填写。</small>
          </label>
        </template>

        <template v-else-if="form.type === 'GIT_PUSH'">
          <label class="workbench-operation-proposal-field">
            <span>目标仓库</span>
            <el-select v-model="form.repositoryKey" data-test="operation-repository" style="width: 100%">
              <el-option
                v-for="repository in repositories"
                :key="repository.repositoryKey"
                :label="repositoryLabel(repository)"
                :value="repository.repositoryKey"
              />
            </el-select>
          </label>
          <label class="workbench-operation-proposal-field">
            <span>远端名称</span>
            <el-input v-model="form.remoteName" :maxlength="128" data-test="operation-remote-name" />
          </label>
          <label class="workbench-operation-proposal-field">
            <span>本地分支</span>
            <el-input v-model="form.localBranch" :maxlength="512" data-test="operation-local-branch" />
          </label>
          <label class="workbench-operation-proposal-field">
            <span>远端分支引用</span>
            <el-input v-model="form.remoteRef" :maxlength="1024" data-test="operation-remote-ref" placeholder="refs/heads/master" />
          </label>
          <label class="workbench-operation-proposal-field">
            <span>预期本地 HEAD</span>
            <el-input v-model="form.expectedLocalHead" :maxlength="64" data-test="operation-expected-local-head" />
          </label>
        </template>

        <template v-else-if="form.type === 'LOCAL_DEPLOY'">
          <label class="workbench-operation-proposal-field">
            <span>部署模板 ID</span>
            <el-input v-model="form.templateId" :maxlength="128" data-test="operation-template-id" />
          </label>
          <label class="workbench-operation-proposal-field">
            <span>部署模板版本</span>
            <el-input v-model="form.templateVersion" :maxlength="128" data-test="operation-template-version" />
          </label>
          <label class="workbench-operation-proposal-field">
            <span>部署模板 Hash</span>
            <el-input v-model="form.templateHash" :maxlength="64" data-test="operation-template-hash" />
          </label>
          <label class="workbench-operation-proposal-field">
            <span>目标仓库</span>
            <el-select
              v-model="form.repositoryTargets"
              multiple
              data-test="operation-repository"
              style="width: 100%"
            >
              <el-option
                v-for="repository in repositories"
                :key="repository.repositoryKey"
                :label="repositoryLabel(repository)"
                :value="repository.repositoryKey"
              />
            </el-select>
          </label>
          <label class="workbench-operation-proposal-field">
            <span>环境</span>
            <el-input model-value="LOCAL" disabled data-test="operation-local-environment" />
          </label>
          <label class="workbench-operation-proposal-field">
            <span>预期工作区状态 Hash</span>
            <el-input v-model="form.expectedWorkspaceStateHash" :maxlength="64" data-test="operation-workspace-state-hash" />
          </label>
          <label class="workbench-operation-proposal-field workbench-operation-proposal-wide">
            <span>回滚说明</span>
            <el-input v-model="form.rollbackSummary" :maxlength="2000" data-test="operation-rollback-summary" />
          </label>
        </template>

        <template v-else>
          <label class="workbench-operation-proposal-field">
            <span>生产环境</span>
            <el-input v-model="form.environment" :maxlength="128" data-test="operation-production-environment" />
          </label>
          <label class="workbench-operation-proposal-field">
            <span>资源引用</span>
            <el-input v-model="form.resourceReference" :maxlength="1024" data-test="operation-resource-reference" />
          </label>
          <label class="workbench-operation-proposal-field">
            <span>预期生产状态 Hash</span>
            <el-input v-model="form.expectedProductionStateHash" :maxlength="64" data-test="operation-production-state-hash" />
          </label>
        </template>

        <el-alert
          v-if="proposalDisabledReason"
          type="info"
          show-icon
          :closable="false"
          :title="proposalDisabledReason"
          data-test="operation-proposal-disabled-reason"
        />
      </div>
      <template #footer>
        <el-button @click="proposalVisible = false">取消</el-button>
        <el-button text :loading="proposalSourceLoading" @click="emit('prepare-proposal')">
          重新加载 Run
        </el-button>
        <el-button
          type="danger"
          data-test="submit-operation-proposal"
          :loading="proposing"
          :disabled="!proposalValid"
          @click="submitProposal"
        >
          仅创建待决策提案
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
/**
 * Typed high-impact Operation cards and fixed Proposal entry.
 *
 * @author alex
 * @since 2026-08-01
 */
import { computed, reactive, ref, watch } from 'vue';

const props = defineProps({
  operations: { type: Array, required: true },
  loading: { type: Boolean, required: true },
  decidingId: { type: String, default: null },
  error: { type: String, default: null },
  notice: { type: String, default: null },
  readOnly: { type: Boolean, required: true },
  repositories: { type: Array, required: true },
  sourceRuns: { type: Array, required: true },
  workbenchId: { type: String, default: null },
  phase: { type: String, required: true },
  proposalSourceLoading: { type: Boolean, required: true },
  proposing: { type: Boolean, required: true },
  proposalCreatedToken: { type: Number, required: true },
  proposalDisabledReason: { type: String, default: null },
});

const emit = defineEmits(['refresh', 'decide', 'prepare-proposal', 'propose']);
const reasons = reactive({});
const proposalVisible = ref(false);
const form = reactive(emptyProposal());
const SHA_256 = /^[a-f0-9]{64}$/;
const GIT_OBJECT_ID = /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/;

const proposalValid = computed(() => {
  if (props.readOnly || props.proposing || props.proposalSourceLoading ||
    props.proposalDisabledReason || !form.sourceRunId || !form.safeSummary.trim()) return false;
  switch (form.type) {
    case 'GIT_COMMIT':
      return scopedRepository(form.repositoryKey) && Boolean(form.branch.trim()) &&
        GIT_OBJECT_ID.test(form.expectedHead.trim()) && SHA_256.test(form.expectedStateHash.trim()) &&
        relativePaths().length > 0 && Boolean(form.safeMessagePreview.trim());
    case 'GIT_PUSH':
      return scopedRepository(form.repositoryKey) && Boolean(form.remoteName.trim()) &&
        Boolean(form.localBranch.trim()) && validRemoteRef(form.remoteRef.trim()) &&
        GIT_OBJECT_ID.test(form.expectedLocalHead.trim());
    case 'LOCAL_DEPLOY':
      return Boolean(form.templateId.trim()) && Boolean(form.templateVersion.trim()) &&
        SHA_256.test(form.templateHash.trim()) && form.repositoryTargets.length > 0 &&
        form.repositoryTargets.every(scopedRepository) &&
        SHA_256.test(form.expectedWorkspaceStateHash.trim()) && Boolean(form.rollbackSummary.trim());
    case 'PRODUCTION_WRITE':
      return Boolean(form.environment.trim()) && form.environment.trim().toLowerCase() !== 'local' &&
        Boolean(form.resourceReference.trim()) && SHA_256.test(form.expectedProductionStateHash.trim());
    default:
      return false;
  }
});

watch(
  () => props.sourceRuns,
  runs => {
    if (proposalVisible.value && !runs.some(run => run.runId === form.sourceRunId)) {
      form.sourceRunId = runs[0]?.runId ?? '';
    }
  },
);

watch(
  () => props.proposalCreatedToken,
  (token, previous) => {
    if (token > previous) {
      proposalVisible.value = false;
      resetProposal();
    }
  },
);

watch(
  () => [props.workbenchId, props.phase],
  (scope, previous) => {
    if (scope[0] !== previous[0] || scope[1] !== previous[1]) {
      proposalVisible.value = false;
      resetProposal();
    }
  },
);

function openProposal() {
  if (props.readOnly) return;
  proposalVisible.value = true;
  form.repositoryKey = scopedRepository(form.repositoryKey)
    ? form.repositoryKey
    : props.repositories[0]?.repositoryKey ?? '';
  emit('prepare-proposal');
}

function submitProposal() {
  if (!proposalValid.value) return;
  const common = { sourceRunId: form.sourceRunId, safeSummary: form.safeSummary };
  switch (form.type) {
    case 'GIT_COMMIT':
      emit('propose', { ...common, target: {
        type: 'GIT_COMMIT', repositoryKey: form.repositoryKey, branch: form.branch,
        expectedHead: form.expectedHead, expectedStateHash: form.expectedStateHash,
        includedPaths: relativePaths(), safeMessagePreview: form.safeMessagePreview,
      } });
      break;
    case 'GIT_PUSH':
      emit('propose', { ...common, target: {
        type: 'GIT_PUSH', repositoryKey: form.repositoryKey, remoteName: form.remoteName,
        localBranch: form.localBranch, remoteRef: form.remoteRef,
        expectedLocalHead: form.expectedLocalHead,
      } });
      break;
    case 'LOCAL_DEPLOY':
      emit('propose', { ...common, target: {
        type: 'LOCAL_DEPLOY', templateId: form.templateId, templateVersion: form.templateVersion,
        templateHash: form.templateHash, repositoryTargets: form.repositoryTargets.slice(),
        environment: 'LOCAL', expectedWorkspaceStateHash: form.expectedWorkspaceStateHash,
        rollbackSummary: form.rollbackSummary,
      } });
      break;
    case 'PRODUCTION_WRITE':
      emit('propose', { ...common, target: {
        type: 'PRODUCTION_WRITE', environment: form.environment,
        resourceReference: form.resourceReference,
        expectedProductionStateHash: form.expectedProductionStateHash,
      } });
      break;
  }
}

function resetProposal() {
  Object.assign(form, emptyProposal());
}

function emptyProposal() {
  return {
    type: 'GIT_COMMIT', sourceRunId: '', safeSummary: '', repositoryKey: '', branch: '',
    expectedHead: '', expectedStateHash: '', includedPathsText: '', safeMessagePreview: '',
    remoteName: 'origin', localBranch: '', remoteRef: '', expectedLocalHead: '',
    templateId: '', templateVersion: '', templateHash: '', repositoryTargets: [],
    expectedWorkspaceStateHash: '', rollbackSummary: '', environment: '',
    resourceReference: '', expectedProductionStateHash: '',
  };
}

function relativePaths() {
  return form.includedPathsText.split(/\r?\n/).map(value => value.trim()).filter(Boolean);
}

function scopedRepository(repositoryKey) {
  return props.repositories.some(repository => repository.repositoryKey === repositoryKey);
}

function validRemoteRef(value) {
  return value.startsWith('refs/heads/') && value.length > 'refs/heads/'.length &&
    !/[\s~^:?*[\]\\]/.test(value.slice('refs/heads/'.length));
}

function updateReason(operationId, value) {
  if (typeof value === 'string') reasons[operationId] = value;
}

function validReason(operationId) {
  return Boolean(reasons[operationId]?.trim());
}

function emitDecision(operation, decision) {
  const reason = reasons[operation.operationId]?.trim();
  if (!reason) return;
  emit('decide', operation, decision, reason);
}

function runLabel(run) {
  return `${run.runId} · ${run.status} · ${new Date(run.createdAt).toLocaleString()}`;
}

function repositoryLabel(repository) {
  return `${repository.repositoryKey}${repository.primary ? '（主仓）' : ''}`;
}

function targetEntries(operation) {
  const details = operation.target.details;
  switch (operation.type) {
    case 'GIT_COMMIT':
      return compactEntries([
        ['分支', details.branch],
        ['预期 HEAD', shortHash(details.expectedHead)],
        ['包含路径', arrayText(details.includedPaths)],
        ['Commit 摘要', details.safeMessagePreview],
      ]);
    case 'GIT_PUSH':
      return compactEntries([
        ['远端', details.remoteName],
        ['本地分支', details.localBranch],
        ['远端引用', details.remoteRef],
        ['预期 HEAD', shortHash(details.expectedLocalHead)],
        ['Force', details.forceAllowed === false ? '禁止' : null],
      ]);
    case 'LOCAL_DEPLOY':
      return compactEntries([
        ['环境', details.environment],
        ['模板', `${stringValue(details.templateId)}@${stringValue(details.templateVersion)}`],
        ['回滚说明', details.rollbackSummary],
      ]);
    case 'PRODUCTION_WRITE':
      return compactEntries([
        ['环境', details.environment],
        ['资源', details.resourceReference],
      ]);
    default:
      return [];
  }
}

function compactEntries(entries) {
  return entries.flatMap(([label, value]) => {
    const text = stringValue(value);
    return text ? [{ label, value: text }] : [];
  });
}

function arrayText(value) {
  return Array.isArray(value) && value.every(item => typeof item === 'string')
    ? value.join('、')
    : '';
}

function stringValue(value) {
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
    ? String(value)
    : '';
}

function shortHash(value) {
  const text = stringValue(value);
  return text.length > 14 ? `${text.slice(0, 12)}…` : text;
}

function typeLabel(type) {
  return {
    GIT_COMMIT: 'Git Commit',
    GIT_PUSH: 'Git Push',
    LOCAL_DEPLOY: '本地部署',
    PRODUCTION_WRITE: '生产写入',
  }[type];
}

function statusLabel(status) {
  return {
    PROPOSED: '待决策',
    AUTHORIZED: '已授权待处理',
    EXECUTING: '执行中',
    SUCCEEDED: '已完成',
    FAILED: '失败',
    RECONCILIATION_REQUIRED: '需要对账',
    REJECTED: '已拒绝',
    EXPIRED: '已过期',
  }[status];
}

function statusType(status) {
  if (status === 'AUTHORIZED' || status === 'SUCCEEDED') return 'success';
  if (status === 'PROPOSED' || status === 'EXECUTING' || status === 'RECONCILIATION_REQUIRED') return 'warning';
  if (status === 'FAILED' || status === 'REJECTED') return 'danger';
  return 'info';
}
</script>
