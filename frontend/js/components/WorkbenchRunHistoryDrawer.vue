<template>
  <el-drawer
    :model-value="visible"
    title="运行记录与能力追溯"
    size="min(1120px, 92vw)"
    destroy-on-close
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="workbench-run-history-shell">
      <aside class="workbench-run-history-sidebar">
        <header>
          <div>
            <strong>本阶段 Run</strong>
            <small>包含已完成、失败、取消和中断记录</small>
          </div>
          <el-button text :loading="loadingRuns" @click="emit('refresh')">刷新</el-button>
        </header>
        <el-alert
          v-if="historyError"
          type="error"
          :closable="false"
          show-icon
          :title="historyError"
        />
        <div
          v-loading="loadingRuns"
          class="workbench-run-history-list"
          data-test="workbench-run-history-list"
        >
          <button
            v-for="run in runs"
            :key="run.runId"
            type="button"
            :class="['workbench-run-history-item', { active: selectedRunId === run.runId }]"
            @click="emit('select-run', run.runId)"
          >
            <span>
              <el-tag size="small" :type="statusType(run.status)">{{ statusLabel(run.status) }}</el-tag>
              <small>{{ modeLabel(run.runMode) }}</small>
            </span>
            <strong>{{ run.runId }}</strong>
            <small>{{ formatTime(run.createdAt) }} · {{ durationLabel(run) }}</small>
          </button>
          <div v-if="!loadingRuns && runs.length === 0" class="workbench-run-history-empty">
            本阶段暂无 Run 记录
          </div>
        </div>
        <el-button
          v-if="hasMoreRuns"
          plain
          :loading="loadingRuns"
          @click="emit('load-more-runs')"
        >
          加载更多 Run
        </el-button>
      </aside>

      <main v-loading="loadingSelection" class="workbench-run-history-detail">
        <div v-if="!selectedRun" class="workbench-run-history-empty">
          选择一条 Run 查看可恢复事件与实际能力。
        </div>
        <template v-else>
          <section class="workbench-run-history-summary">
            <div>
              <span>Run</span>
              <strong>{{ selectedRun.runId }}</strong>
            </div>
            <div><span>状态</span><strong>{{ statusLabel(selectedRun.status) }}</strong></div>
            <div><span>事件</span><strong>{{ selectedRun.lastEventSeq }}</strong></div>
            <div><span>耗时</span><strong>{{ durationLabel(selectedRun) }}</strong></div>
          </section>

          <section class="workbench-run-history-section">
            <header><h3>恢复的运行时间线</h3><small>按持久化序号分页回放</small></header>
            <div
              class="workbench-run-history-timeline"
              data-test="workbench-run-history-timeline"
            >
              <div v-if="!runState" class="workbench-run-history-empty">暂无可恢复事件</div>
              <template v-else>
                <article
                  v-for="block in runState.blocks"
                  :key="block.eventId"
                  :class="['workbench-timeline-block', `kind-${block.kind}`]"
                >
                  <header><span>{{ blockLabel(block.kind) }}</span><small>#{{ block.eventId }}</small></header>
                  <p v-if="block.content">{{ block.content }}</p>
                  <details v-else>
                    <summary>{{ block.commandSummary || block.outputSummary || block.summary || block.tool || block.commandClass || block.eventType }}</summary>
                    <p v-if="block.outputSummary" data-test="workbench-history-command-output-summary">
                      {{ block.outputSummary }}
                    </p>
                    <dl>
                      <template v-if="block.repositoryKey"><dt>仓库</dt><dd>{{ block.repositoryKey }}</dd></template>
                      <template v-if="block.status"><dt>状态</dt><dd>{{ block.status }}</dd></template>
                      <template v-if="block.durationMs != null"><dt>耗时</dt><dd>{{ block.durationMs }} ms</dd></template>
                      <template v-if="block.exitCode != null"><dt>退出码</dt><dd>{{ block.exitCode }}</dd></template>
                    </dl>
                  </details>
                </article>
                <article
                  v-for="document in runState.staleDocuments"
                  :key="`${document.repositoryKey}:${document.path}:${document.eventId}`"
                  class="workbench-file-event"
                >
                  <span>文件 {{ document.changeType }}</span>
                  <strong>{{ document.repositoryKey }}/{{ document.path }}</strong>
                </article>
                <article
                  v-for="test in runState.testProgress"
                  :key="`${test.eventId}:${test.repositoryKey}:${test.suite}`"
                  class="workbench-test-event"
                >
                  <header><span>测试 · {{ test.repositoryKey }}</span><el-tag size="small">{{ test.status }}</el-tag></header>
                  <strong>{{ test.suite }}</strong>
                  <p>{{ test.summary }}</p>
                </article>
                <el-result
                  v-if="runState.terminal"
                  :icon="runState.terminal.status === 'SUCCEEDED' ? 'success' : 'warning'"
                  :title="statusLabel(runState.terminal.status)"
                  :sub-title="runState.terminal.publicMessage || undefined"
                />
              </template>
            </div>
            <el-button
              v-if="hasMoreEvents"
              plain
              :loading="loadingEvents"
              @click="loadMoreEvents"
            >
              继续加载事件
            </el-button>
          </section>

          <section
            class="workbench-run-history-section"
            data-test="workbench-run-history-capability"
          >
            <header><h3>本轮实际冻结的能力</h3><small>不是当前阶段的下一轮配置</small></header>
            <el-alert
              v-if="capabilityError"
              type="error"
              :closable="false"
              show-icon
              :title="capabilityError"
            />
            <template v-else-if="capability">
              <dl class="workbench-run-capability-meta">
                <dt>Binding Hash</dt><dd><code :title="capability.bindingHash">{{ shortHash(capability.bindingHash) }}</code></dd>
                <dt>Profile</dt><dd>{{ capability.profileId }} @ {{ capability.profileVersion }}</dd>
                <dt>Policy</dt><dd>{{ capability.policyVersion }}</dd>
                <dt>Runtime</dt><dd>{{ capability.runtimeCompatibility }}</dd>
                <dt>Override</dt><dd>v{{ capability.overrideVersion }}</dd>
                <dt>Scope Hash</dt><dd><code :title="capability.repositoryScopeHash">{{ shortHash(capability.repositoryScopeHash) }}</code></dd>
              </dl>
              <section
                class="workbench-run-repository-scope"
                data-test="workbench-run-repository-scope"
              >
                <header>
                  <h4>Repository Scope · {{ capability.repositories.length }}</h4>
                  <small>本轮冻结的仓库读写边界</small>
                </header>
                <article
                  v-for="repository in capability.repositories"
                  :key="repository.repositoryKey"
                >
                  <div>
                    <strong>{{ repository.repositoryKey }}</strong>
                    <el-tag size="small" :type="repository.primary ? 'primary' : 'info'">
                      {{ repository.primary ? '主仓' : '参与仓' }}
                    </el-tag>
                  </div>
                  <small>相对路径 · <code>{{ repository.relativePath }}</code></small>
                  <el-tag
                    size="small"
                    effect="plain"
                    :type="repository.access === 'WRITE' ? 'warning' : 'info'"
                  >
                    {{ repository.access }}
                  </el-tag>
                </article>
              </section>
              <div class="workbench-run-capability-grid">
                <section>
                  <h4>Rules · {{ capability.rules.length }}</h4>
                  <article v-for="rule in capability.rules" :key="`${rule.id}:${rule.version}`">
                    <strong>{{ rule.id }} @ {{ rule.version }}</strong>
                    <p>{{ rule.safeSummary }}</p>
                    <small>{{ rule.source }} · {{ rule.mandatory ? '强制' : '可选' }} · {{ shortHash(rule.contentHash) }}</small>
                  </article>
                </section>
                <section>
                  <h4>Skills · {{ capability.skills.length }}</h4>
                  <article v-for="skill in capability.skills" :key="`${skill.id}:${skill.version}`">
                    <strong>{{ skill.id }} @ {{ skill.version }}</strong>
                    <small>{{ skill.source }} · {{ skill.trustTier }} · {{ shortHash(skill.packageHash) }}</small>
                  </article>
                </section>
                <section>
                  <h4>MCP · {{ capability.mcpServers.length }}</h4>
                  <article v-for="server in capability.mcpServers" :key="`${server.id}:${server.version}`">
                    <strong>{{ server.id }} @ {{ server.version }}</strong>
                    <small>{{ server.access }} · {{ server.transport }} · {{ shortHash(server.definitionHash) }}</small>
                  </article>
                </section>
              </div>
              <div v-if="capability.rejected.length" class="workbench-run-capability-rejected">
                <strong>未进入本轮的能力</strong>
                <span v-for="item in capability.rejected" :key="`${item.id}:${item.reasonCode}`">
                  {{ item.id }} · {{ item.reasonCode }}
                </span>
              </div>
            </template>
          </section>
        </template>
      </main>
    </div>
  </el-drawer>
</template>

<script setup>
/**
 * Workbench 历史 Run 与不可变能力绑定只读抽屉。
 *
 * @author alex
 * @since 2026-08-01
 */
const props = defineProps({
  visible: { type: Boolean, required: true },
  loadingRuns: { type: Boolean, required: true },
  loadingSelection: { type: Boolean, required: true },
  loadingEvents: { type: Boolean, required: true },
  runs: { type: Array, required: true },
  selectedRunId: { type: String, default: null },
  selectedRun: { type: Object, default: null },
  runState: { type: Object, default: null },
  capability: { type: Object, default: null },
  historyError: { type: String, default: null },
  capabilityError: { type: String, default: null },
  hasMoreRuns: { type: Boolean, required: true },
  hasMoreEvents: { type: Boolean, required: true },
});

const emit = defineEmits([
  'update:visible',
  'refresh',
  'select-run',
  'load-more-runs',
  'load-more-events',
]);

function loadMoreEvents() {
  emit('load-more-events');
}

function statusLabel(status) {
  return {
    PENDING: '等待启动',
    RUNNING: '运行中',
    CANCEL_REQUESTED: '正在停止',
    SUCCEEDED: '成功',
    FAILED: '失败',
    CANCELLED: '已取消',
    INTERRUPTED: '已中断',
  }[status] || status;
}

function statusType(status) {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'FAILED' || status === 'INTERRUPTED') return 'danger';
  if (status === 'CANCELLED' || status === 'CANCEL_REQUESTED') return 'warning';
  return 'info';
}

function modeLabel(mode) {
  return mode === 'MODIFY_WORKSPACE' ? '修改工作区' : '只读讨论';
}

function formatTime(timestamp) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(timestamp));
}

function durationLabel(run) {
  if (run.startedAt == null) return '未启动';
  if (run.finishedAt == null) return '进行中';
  const milliseconds = Math.max(0, run.finishedAt - run.startedAt);
  return milliseconds < 1000 ? `${milliseconds} ms` : `${(milliseconds / 1000).toFixed(1)} s`;
}

function shortHash(hash) {
  return hash && hash.length > 16 ? `${hash.slice(0, 12)}…` : hash;
}

function blockLabel(kind) {
  return {
    agent_chunk: 'Agent',
    tool_started: '工具开始',
    tool_finished: '工具完成',
    command_started: '命令开始',
    command_finished: '命令完成',
    generic: '运行事件',
  }[kind] || kind;
}
</script>

<style scoped>
.workbench-run-history-shell {
  display: grid;
  grid-template-columns: minmax(220px, 30%) minmax(0, 1fr);
  gap: 16px;
  height: calc(100vh - 110px);
  min-height: 480px;
}
.workbench-run-history-sidebar,
.workbench-run-history-detail,
.workbench-run-history-section {
  min-width: 0;
}
.workbench-run-history-sidebar {
  display: flex;
  flex-direction: column;
  gap: 12px;
  border-right: 1px solid var(--el-border-color-lighter);
  padding-right: 14px;
}
.workbench-run-history-sidebar > header,
.workbench-run-history-section > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.workbench-run-history-sidebar > header div {
  display: grid;
  gap: 4px;
}
.workbench-run-history-sidebar small,
.workbench-run-history-section header small,
.workbench-run-history-item small {
  color: var(--el-text-color-secondary);
}
.workbench-run-history-list {
  display: grid;
  gap: 8px;
  overflow: auto;
}
.workbench-run-history-item {
  display: grid;
  gap: 7px;
  width: 100%;
  padding: 10px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
  text-align: left;
  cursor: pointer;
}
.workbench-run-history-item.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.workbench-run-history-item > span {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.workbench-run-history-item > strong {
  overflow: hidden;
  text-overflow: ellipsis;
}
.workbench-run-history-detail {
  overflow: auto;
  padding-right: 4px;
}
.workbench-run-history-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 16px;
}
.workbench-run-history-summary > div {
  display: grid;
  gap: 4px;
  padding: 10px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.workbench-run-history-summary span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.workbench-run-history-summary strong {
  overflow: hidden;
  text-overflow: ellipsis;
}
.workbench-run-history-section {
  margin-bottom: 18px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}
.workbench-run-history-section h3,
.workbench-run-history-section h4 {
  margin: 0;
}
.workbench-run-history-timeline {
  display: grid;
  gap: 10px;
  margin: 12px 0;
  max-height: 360px;
  overflow: auto;
}
.workbench-run-history-empty {
  padding: 28px 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.workbench-run-capability-meta {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  gap: 7px 12px;
}
.workbench-run-capability-meta dt {
  color: var(--el-text-color-secondary);
}
.workbench-run-capability-meta dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.workbench-run-repository-scope {
  display: grid;
  gap: 8px;
  margin-top: 14px;
}
.workbench-run-repository-scope > header,
.workbench-run-repository-scope article > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.workbench-run-repository-scope article {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 5px 10px;
  padding: 9px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  overflow-wrap: anywhere;
}
.workbench-run-repository-scope article > div {
  grid-column: 1 / -1;
}
.workbench-run-repository-scope small {
  color: var(--el-text-color-secondary);
}
.workbench-run-capability-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 14px;
}
.workbench-run-capability-grid > section {
  display: grid;
  align-content: start;
  gap: 8px;
}
.workbench-run-capability-grid article,
.workbench-run-capability-rejected {
  display: grid;
  gap: 4px;
  padding: 9px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  overflow-wrap: anywhere;
}
.workbench-run-capability-grid p {
  margin: 0;
}
.workbench-run-capability-grid small {
  color: var(--el-text-color-secondary);
}
.workbench-run-capability-rejected {
  margin-top: 12px;
}
@media (max-width: 760px) {
  .workbench-run-history-shell {
    grid-template-columns: 1fr;
    height: auto;
  }
  .workbench-run-history-sidebar {
    max-height: 42vh;
    border-right: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
    padding: 0 0 12px;
  }
  .workbench-run-history-summary,
  .workbench-run-capability-grid {
    grid-template-columns: 1fr;
  }
}
</style>
