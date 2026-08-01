<template>
  <section v-if="operations.length || loading || error || notice" class="workbench-operation-section">
    <div class="workbench-operation-section-heading">
      <div>
        <span>类型化高影响操作</span>
        <small>commit、push、部署和生产写分别确认；聊天文字不构成授权。</small>
      </div>
      <el-button text :loading="loading" @click="emit('refresh')">刷新</el-button>
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
  </section>
</template>

<script setup>
/**
 * Typed high-impact Operation cards.
 *
 * @author alex
 * @since 2026-08-01
 */
import { reactive } from 'vue';

defineProps({
  operations: { type: Array, required: true },
  loading: { type: Boolean, required: true },
  decidingId: { type: String, default: null },
  error: { type: String, default: null },
  notice: { type: String, default: null },
  readOnly: { type: Boolean, required: true },
});

const emit = defineEmits(['refresh', 'decide']);
const reasons = reactive({});

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
