<template>
  <admin-shell active="workbenches" @ready="state.loadInitial">
    <template #header-actions>
      <el-button text :loading="pageLoading" @click="state.refresh">刷新</el-button>
    </template>

    <div class="view-wrap admin-workbench-view">
      <el-alert
        class="admin-workbench-boundary"
        type="info"
        :closable="false"
        show-icon
        title="独立管理员运维边界"
        description="本页只查看安全投影、停止异常 Run 和执行单 Run 对账；不会以创建者身份提交消息或批准高影响操作。"
      />

      <el-alert
        v-if="state.workbenchError.value"
        class="admin-workbench-alert"
        type="error"
        :closable="false"
        :title="state.workbenchError.value"
        show-icon
      />

      <div class="admin-workbench-layout">
        <el-card shadow="never" class="admin-workbench-list-card">
          <template #header>
            <div class="admin-workbench-card-header">
              <div>
                <div class="admin-workbench-card-title">Workbench</div>
                <div class="admin-workbench-card-subtitle">跨 Owner 安全摘要</div>
              </div>
              <el-select
                v-model="state.workbenchStatusFilter.value"
                size="small"
                class="admin-workbench-status-filter"
                aria-label="Workbench 状态筛选"
                @change="state.applyWorkbenchFilter"
              >
                <el-option label="全部" value="" />
                <el-option label="活动" value="ACTIVE" />
                <el-option label="已归档" value="ARCHIVED" />
              </el-select>
            </div>
          </template>

          <div
            v-loading="state.loadingWorkbenches.value"
            data-test="admin-workbench-list"
            class="admin-workbench-list"
          >
            <el-empty
              v-if="!state.loadingWorkbenches.value && state.workbenches.value.length === 0"
              description="暂无 Workbench"
              :image-size="72"
            />
            <button
              v-for="workbench in state.workbenches.value"
              :key="workbench.workbenchId"
              type="button"
              class="admin-workbench-list-item"
              :class="{ selected: state.selectedWorkbenchId.value === workbench.workbenchId }"
              :data-workbench-id="workbench.workbenchId"
              @click="state.selectWorkbench(workbench.workbenchId)"
            >
              <span class="admin-workbench-list-title">{{ workbench.title }}</span>
              <span class="admin-workbench-list-meta">
                {{ workbench.ownerName }} · {{ workbench.agentType }} · {{ workbench.environment || '默认环境' }}
              </span>
              <span class="admin-workbench-list-meta mono-text">{{ workbench.workbenchId }}</span>
              <span class="admin-workbench-list-footer">
                <el-tag size="small" :type="workbenchStatusType(workbench.status)">
                  {{ workbenchStatusLabel(workbench.status) }}
                </el-tag>
                <span>{{ fmtTime(workbench.updatedAt) }}</span>
              </span>
            </button>
            <el-button
              v-if="state.hasMoreWorkbenches.value"
              class="admin-workbench-load-more"
              text
              type="primary"
              :loading="state.loadingWorkbenches.value"
              @click="state.loadMoreWorkbenches"
            >
              加载更多
            </el-button>
          </div>
        </el-card>

        <section class="admin-workbench-detail-column">
          <el-empty
            v-if="!state.selectedWorkbenchId.value"
            description="选择一个 Workbench 查看运维信息"
          />

          <template v-else>
            <el-alert
              v-if="state.selectionError.value"
              class="admin-workbench-alert"
              type="error"
              :closable="false"
              :title="state.selectionError.value"
              show-icon
            />

            <el-card
              v-loading="state.loadingSelection.value"
              shadow="never"
              data-test="admin-workbench-detail"
              class="admin-workbench-summary-card"
            >
              <template v-if="state.selectedWorkbench.value">
                <div class="admin-workbench-summary-heading">
                  <div>
                    <h2>{{ state.selectedWorkbench.value.title }}</h2>
                    <div class="mono-text">{{ state.selectedWorkbench.value.workbenchId }}</div>
                  </div>
                  <el-tag :type="workbenchStatusType(state.selectedWorkbench.value.status)">
                    {{ workbenchStatusLabel(state.selectedWorkbench.value.status) }}
                  </el-tag>
                </div>

                <el-descriptions :column="3" border size="small" class="admin-workbench-descriptions">
                  <el-descriptions-item label="创建者">
                    {{ state.selectedWorkbench.value.ownerName }}
                    <span class="admin-workbench-inline-id">{{ state.selectedWorkbench.value.ownerId }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="Agent">{{ state.selectedWorkbench.value.agentType }}</el-descriptions-item>
                  <el-descriptions-item label="环境">{{ state.selectedWorkbench.value.environment || '默认' }}</el-descriptions-item>
                  <el-descriptions-item label="主仓库">{{ state.selectedWorkbench.value.primaryRepositoryKey }}</el-descriptions-item>
                  <el-descriptions-item label="活动写 Run">
                    <span class="mono-text">{{ state.selectedWorkbench.value.activeWriteRunId || '—' }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="版本">v{{ state.selectedWorkbench.value.version }}</el-descriptions-item>
                  <el-descriptions-item label="仓库范围 Hash" :span="3">
                    <span class="mono-text">{{ shortHash(state.selectedWorkbench.value.repositoryScopeHash) }}</span>
                  </el-descriptions-item>
                </el-descriptions>

                <div class="admin-workbench-safe-section">
                  <h3>逻辑仓库范围</h3>
                  <div class="admin-workbench-repository-list">
                    <el-tag
                      v-for="repository in state.selectedWorkbench.value.repositories"
                      :key="repository.repositoryKey"
                      :type="repository.primary ? 'success' : 'info'"
                      effect="plain"
                    >
                      {{ repository.repositoryKey }}{{ repository.primary ? ' · 主仓' : '' }}
                    </el-tag>
                  </div>
                </div>

                <div class="admin-workbench-safe-section">
                  <h3>阶段状态</h3>
                  <div class="admin-workbench-stage-grid">
                    <div
                      v-for="stage in state.selectedWorkbench.value.stages"
                      :key="stage.stageInstanceIdentifier"
                      class="admin-workbench-stage"
                    >
                      <span class="admin-workbench-stage-name">
                        {{ stage.definitionIdentifier }} · r{{ stage.definitionRevision }}
                      </span>
                      <span class="admin-workbench-stage-identifier mono-text">
                        {{ stage.stageInstanceIdentifier }}
                      </span>
                      <el-tag size="small" :type="stageStatusType(stage.status)">
                        {{ stageStatusLabel(stage.status) }}
                      </el-tag>
                      <span class="admin-workbench-stage-run mono-text">
                        {{ stage.activeRunId || '无活动 Run' }}
                      </span>
                    </div>
                  </div>
                </div>
              </template>
            </el-card>

            <el-card shadow="never" class="admin-workbench-runs-card">
              <template #header>
                <div class="admin-workbench-card-header">
                  <div>
                    <div class="admin-workbench-card-title">Run 运维</div>
                    <div class="admin-workbench-card-subtitle">只显示生命周期、稳定代码和不可逆 Hash</div>
                  </div>
                  <el-select
                    v-model="state.runStatusFilter.value"
                    size="small"
                    class="admin-workbench-status-filter"
                    aria-label="Run 状态筛选"
                    @change="state.applyRunFilter"
                  >
                    <el-option label="全部" value="" />
                    <el-option
                      v-for="status in runStatusOptions"
                      :key="status"
                      :label="runStatusLabel(status)"
                      :value="status"
                    />
                  </el-select>
                </div>
              </template>

              <el-table
                v-loading="state.loadingRuns.value"
                :data="state.runs.value"
                border
                size="small"
                empty-text="暂无精确绑定的 Run"
                data-test="admin-workbench-run-list"
                @row-click="openRun"
              >
                <el-table-column label="Run" min-width="180">
                  <template #default="{ row }"><span class="mono-text">{{ row.runId }}</span></template>
                </el-table-column>
                <el-table-column label="阶段" min-width="130">
                  <template #default="{ row }">
                    <span class="mono-text">{{ row.stageInstanceIdentifier }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="模式" min-width="120">
                  <template #default="{ row }">{{ runModeLabel(row.runMode) }}</template>
                </el-table-column>
                <el-table-column label="状态" width="110">
                  <template #default="{ row }">
                    <el-tag size="small" :type="runStatusType(row.status)">
                      {{ runStatusLabel(row.status) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="创建时间" width="170">
                  <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
                </el-table-column>
                <el-table-column label="操作" width="80" fixed="right">
                  <template #default="{ row }">
                    <el-button text type="primary" size="small" @click.stop="openRun(row)">查看</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <div v-if="state.hasMoreRuns.value" class="admin-workbench-run-more">
                <el-button text type="primary" :loading="state.loadingRuns.value" @click="state.loadMoreRuns">
                  加载更多 Run
                </el-button>
              </div>
            </el-card>
          </template>
        </section>
      </div>

      <el-drawer
        v-model="runDrawerOpen"
        title="Run 安全详情与运维"
        size="520px"
        direction="rtl"
      >
        <div v-loading="state.loadingRunDetail.value" class="admin-workbench-run-detail">
          <el-alert
            v-if="state.actionError.value"
            class="admin-workbench-alert"
            type="error"
            :closable="false"
            :title="state.actionError.value"
            show-icon
          />

          <template v-if="state.selectedRun.value">
            <div class="admin-workbench-run-heading">
              <span class="mono-text">{{ state.selectedRun.value.runId }}</span>
              <el-tag :type="runStatusType(state.selectedRun.value.status)">
                {{ runStatusLabel(state.selectedRun.value.status) }}
              </el-tag>
            </div>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="Workbench">
                <span class="mono-text">{{ state.selectedRun.value.workbenchId }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="阶段">
                <span class="mono-text">{{ state.selectedRun.value.stageInstanceIdentifier }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="模式">{{ runModeLabel(state.selectedRun.value.runMode) }}</el-descriptions-item>
              <el-descriptions-item label="事件序号">{{ state.selectedRun.value.lastEventSeq }}</el-descriptions-item>
              <el-descriptions-item label="创建">{{ fmtTime(state.selectedRun.value.createdAt) }}</el-descriptions-item>
              <el-descriptions-item label="启动">{{ fmtTime(state.selectedRun.value.startedAt) }}</el-descriptions-item>
              <el-descriptions-item label="取消请求">{{ fmtTime(state.selectedRun.value.cancelRequestedAt) }}</el-descriptions-item>
              <el-descriptions-item label="结束">{{ fmtTime(state.selectedRun.value.finishedAt) }}</el-descriptions-item>
              <el-descriptions-item label="退出码">{{ state.selectedRun.value.exitCode ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="失败码">{{ state.selectedRun.value.failureCode || '—' }}</el-descriptions-item>
              <el-descriptions-item label="Runtime Handle">
                {{ state.selectedRun.value.runtimeHandlePresent ? '存在持久化引用' : '无持久化引用' }}
              </el-descriptions-item>
              <el-descriptions-item label="仓库范围 Hash">
                <span class="mono-text">{{ shortHash(state.selectedRun.value.repositoryScopeHash) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="能力快照 Hash">
                <span class="mono-text">{{ shortHash(state.selectedRun.value.capabilitySnapshotHash) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="Prompt Hash">
                <span class="mono-text">{{ shortHash(state.selectedRun.value.promptHash) }}</span>
              </el-descriptions-item>
            </el-descriptions>

            <el-alert
              class="admin-workbench-action-note"
              type="warning"
              :closable="false"
              show-icon
              title="运维动作不扩展业务权限"
              description="停止只记录取消意图；对账只观察并收敛当前 Run，不会重放 Provider。"
            />

            <div class="admin-workbench-actions">
              <el-button
                data-test="admin-workbench-stop"
                type="danger"
                plain
                :disabled="!state.canStopSelectedRun.value || state.actionBusy.value"
                :loading="state.actionBusy.value && state.actionKind.value === 'STOP'"
                @click="confirmStop"
              >
                停止异常 Run
              </el-button>
              <el-button
                data-test="admin-workbench-reconcile"
                type="warning"
                plain
                :disabled="!state.canReconcileSelectedRun.value || state.actionBusy.value"
                :loading="state.actionBusy.value && state.actionKind.value === 'RECONCILE'"
                @click="confirmReconcile"
              >
                单 Run 对账
              </el-button>
            </div>

            <el-alert
              v-if="state.lastAction.value"
              class="admin-workbench-action-result"
              type="success"
              :closable="false"
              show-icon
              :title="actionResultTitle"
              :description="actionResultDescription"
            />
          </template>
        </div>
      </el-drawer>
    </div>
  </admin-shell>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessageBox } from 'element-plus';
import { useAdminWorkbenches } from '../composables/useAdminWorkbenches.js';
import type {
  AdminWorkbenchRunListItem,
  AdminWorkbenchRunMode,
  AdminWorkbenchRunStatus,
  AdminWorkbenchStageStatus,
  AdminWorkbenchStatus,
} from '../api/workbench.js';

const state = useAdminWorkbenches();
const runDrawerOpen = ref(false);

const runStatusOptions: AdminWorkbenchRunStatus[] = [
  'PENDING',
  'RUNNING',
  'CANCEL_REQUESTED',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
];

const pageLoading = computed(() => (
  state.loadingWorkbenches.value
  || state.loadingSelection.value
  || state.loadingRuns.value
  || state.loadingRunDetail.value
  || state.actionBusy.value
));

const actionResultTitle = computed(() => {
  const result = state.lastAction.value;
  if (!result) return '';
  return result.action === 'STOP' ? '停止请求已受理' : '单 Run 对账已完成';
});

const actionResultDescription = computed(() => {
  const result = state.lastAction.value;
  if (!result) return '';
  const status = result.runStatus ? ` · Run 状态 ${runStatusLabel(result.runStatus)}` : '';
  return `结果 ${result.outcome}${status} · ${fmtTime(result.acceptedAt)}`;
});

function fmtTime(value: number | null | undefined): string {
  return value == null ? '—' : new Date(value).toLocaleString('zh-CN');
}

function shortHash(value: string): string {
  return `${value.slice(0, 12)}…${value.slice(-8)}`;
}

function workbenchStatusLabel(status: AdminWorkbenchStatus): string {
  return status === 'ACTIVE' ? '活动' : '已归档';
}

function workbenchStatusType(status: AdminWorkbenchStatus): 'success' | 'info' {
  return status === 'ACTIVE' ? 'success' : 'info';
}

function stageStatusLabel(status: AdminWorkbenchStageStatus): string {
  const labels: Record<AdminWorkbenchStageStatus, string> = {
    NOT_STARTED: '未开始',
    IN_PROGRESS: '进行中',
    HUMAN_COMPLETED: '人工完成',
  };
  return labels[status];
}

function stageStatusType(status: AdminWorkbenchStageStatus): 'info' | 'primary' | 'success' {
  if (status === 'HUMAN_COMPLETED') return 'success';
  if (status === 'IN_PROGRESS') return 'primary';
  return 'info';
}

function runModeLabel(mode: AdminWorkbenchRunMode): string {
  return mode === 'MODIFY_WORKSPACE' ? '修改工作区' : '只读讨论';
}

function runStatusLabel(status: AdminWorkbenchRunStatus): string {
  const labels: Record<AdminWorkbenchRunStatus, string> = {
    PENDING: '等待启动',
    RUNNING: '运行中',
    CANCEL_REQUESTED: '停止中',
    SUCCEEDED: '成功',
    FAILED: '失败',
    CANCELLED: '已取消',
    INTERRUPTED: '已中断',
  };
  return labels[status];
}

function runStatusType(
  status: AdminWorkbenchRunStatus,
): 'info' | 'primary' | 'warning' | 'success' | 'danger' {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'FAILED' || status === 'INTERRUPTED') return 'danger';
  if (status === 'CANCEL_REQUESTED') return 'warning';
  if (status === 'RUNNING') return 'primary';
  return 'info';
}

async function openRun(row: AdminWorkbenchRunListItem): Promise<void> {
  runDrawerOpen.value = true;
  await state.selectRun(row.runId);
}

async function confirmStop(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将为当前精确绑定的 Run 记录取消意图。该动作不会以创建者身份启动其他任务，是否继续？',
      '确认停止异常 Run',
      {
        confirmButtonText: '确认停止',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
  } catch {
    return;
  }
  await state.stopSelectedRun();
}

async function confirmReconcile(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '系统只对当前 Run 的持久化 Runtime Handle 做观察和终态收敛，不会重放 Provider。是否继续？',
      '确认单 Run 对账',
      {
        confirmButtonText: '确认对账',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
  } catch {
    return;
  }
  await state.reconcileSelectedRun();
}
</script>
