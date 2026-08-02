/**
 * Admin Workbench 安全浏览、显式 Stop 与单 Run 对账编排。
 *
 * @author alex
 * @since 2026-08-01
 */
import { computed, ref, type ComputedRef, type Ref } from 'vue';
import {
  createAdminWorkbenchApiClient,
  type AdminWorkbenchApiClient,
  type AdminWorkbenchDetail,
  type AdminWorkbenchListCursor,
  type AdminWorkbenchListItem,
  type AdminWorkbenchRunActionResult,
  type AdminWorkbenchRunDetail,
  type AdminWorkbenchRunListCursor,
  type AdminWorkbenchRunListItem,
  type AdminWorkbenchRunStatus,
  type AdminWorkbenchStatus,
} from '../api/workbench.js';

const PAGE_LIMIT = 20;
const STOPPABLE_RUN_STATUSES = new Set<AdminWorkbenchRunStatus>([
  'PENDING',
  'RUNNING',
  'CANCEL_REQUESTED',
]);

export interface UseAdminWorkbenchesOptions {
  apiClient?: AdminWorkbenchApiClient;
}

export interface UseAdminWorkbenches {
  workbenches: Ref<AdminWorkbenchListItem[]>;
  selectedWorkbenchId: Ref<string | null>;
  selectedWorkbench: Ref<AdminWorkbenchDetail | null>;
  runs: Ref<AdminWorkbenchRunListItem[]>;
  selectedRunId: Ref<string | null>;
  selectedRun: Ref<AdminWorkbenchRunDetail | null>;
  workbenchStatusFilter: Ref<AdminWorkbenchStatus | ''>;
  runStatusFilter: Ref<AdminWorkbenchRunStatus | ''>;
  loadingWorkbenches: Ref<boolean>;
  loadingSelection: Ref<boolean>;
  loadingRuns: Ref<boolean>;
  loadingRunDetail: Ref<boolean>;
  actionBusy: Ref<boolean>;
  actionKind: Ref<'STOP' | 'RECONCILE' | null>;
  workbenchError: Ref<string | null>;
  selectionError: Ref<string | null>;
  actionError: Ref<string | null>;
  lastAction: Ref<AdminWorkbenchRunActionResult | null>;
  hasMoreWorkbenches: ComputedRef<boolean>;
  hasMoreRuns: ComputedRef<boolean>;
  canStopSelectedRun: ComputedRef<boolean>;
  canReconcileSelectedRun: ComputedRef<boolean>;
  loadInitial(): Promise<void>;
  refresh(): Promise<void>;
  applyWorkbenchFilter(): Promise<void>;
  loadMoreWorkbenches(): Promise<void>;
  selectWorkbench(workbenchId: string): Promise<void>;
  applyRunFilter(): Promise<void>;
  loadMoreRuns(): Promise<void>;
  selectRun(runId: string): Promise<void>;
  stopSelectedRun(): Promise<void>;
  reconcileSelectedRun(): Promise<void>;
}

export function useAdminWorkbenches(
  options: UseAdminWorkbenchesOptions = {},
): UseAdminWorkbenches {
  const apiClient = options.apiClient ?? createAdminWorkbenchApiClient();
  const workbenches = ref<AdminWorkbenchListItem[]>([]);
  const selectedWorkbenchId = ref<string | null>(null);
  const selectedWorkbench = ref<AdminWorkbenchDetail | null>(null);
  const runs = ref<AdminWorkbenchRunListItem[]>([]);
  const selectedRunId = ref<string | null>(null);
  const selectedRun = ref<AdminWorkbenchRunDetail | null>(null);
  const workbenchStatusFilter = ref<AdminWorkbenchStatus | ''>('');
  const runStatusFilter = ref<AdminWorkbenchRunStatus | ''>('');
  const loadingWorkbenches = ref(false);
  const loadingSelection = ref(false);
  const loadingRuns = ref(false);
  const loadingRunDetail = ref(false);
  const actionBusy = ref(false);
  const actionKind = ref<'STOP' | 'RECONCILE' | null>(null);
  const workbenchError = ref<string | null>(null);
  const selectionError = ref<string | null>(null);
  const actionError = ref<string | null>(null);
  const lastAction = ref<AdminWorkbenchRunActionResult | null>(null);
  let nextWorkbenchCursor: AdminWorkbenchListCursor | null = null;
  let nextRunCursor: AdminWorkbenchRunListCursor | null = null;
  let listGeneration = 0;
  let selectionGeneration = 0;
  let runGeneration = 0;

  const hasMoreWorkbenches = computed(() => nextWorkbenchCursor != null);
  const hasMoreRuns = computed(() => nextRunCursor != null);
  const canStopSelectedRun = computed(() => (
    selectedRun.value != null && STOPPABLE_RUN_STATUSES.has(selectedRun.value.status)
  ));
  const canReconcileSelectedRun = computed(() => selectedRun.value != null);

  function clearRunSelection(): void {
    runGeneration++;
    selectedRunId.value = null;
    selectedRun.value = null;
    loadingRunDetail.value = false;
  }

  function clearWorkbenchSelection(): void {
    selectionGeneration++;
    selectedWorkbenchId.value = null;
    selectedWorkbench.value = null;
    runs.value = [];
    nextRunCursor = null;
    loadingSelection.value = false;
    loadingRuns.value = false;
    selectionError.value = null;
    clearRunSelection();
  }

  async function loadWorkbenchPage(replace: boolean): Promise<void> {
    const token = listGeneration;
    loadingWorkbenches.value = true;
    workbenchError.value = null;
    try {
      const page = await apiClient.listWorkbenches({
        ...(workbenchStatusFilter.value ? { status: workbenchStatusFilter.value } : {}),
        ...(!replace && nextWorkbenchCursor ? {
          cursorUpdatedAt: nextWorkbenchCursor.updatedAt,
          cursorWorkbenchId: nextWorkbenchCursor.workbenchId,
        } : {}),
        limit: PAGE_LIMIT,
      });
      if (token !== listGeneration) return;
      workbenches.value = replace ? page.items : [...workbenches.value, ...page.items];
      nextWorkbenchCursor = page.nextCursor;
    } catch {
      if (token === listGeneration) {
        workbenchError.value = '无法加载 Workbench 运维列表，请稍后重试。';
      }
    } finally {
      if (token === listGeneration) loadingWorkbenches.value = false;
    }
  }

  async function loadInitial(): Promise<void> {
    listGeneration++;
    workbenches.value = [];
    nextWorkbenchCursor = null;
    clearWorkbenchSelection();
    await loadWorkbenchPage(true);
    if (selectedWorkbenchId.value == null && workbenches.value.length > 0) {
      await selectWorkbench(workbenches.value[0].workbenchId);
    }
  }

  async function applyWorkbenchFilter(): Promise<void> {
    await loadInitial();
  }

  async function loadMoreWorkbenches(): Promise<void> {
    if (!nextWorkbenchCursor) return;
    await loadWorkbenchPage(false);
  }

  async function loadRunPage(workbenchId: string, replace: boolean, token: number): Promise<void> {
    loadingRuns.value = true;
    selectionError.value = null;
    try {
      const page = await apiClient.listRuns(workbenchId, {
        ...(runStatusFilter.value ? { status: runStatusFilter.value } : {}),
        ...(!replace && nextRunCursor ? {
          cursorCreatedAt: nextRunCursor.createdAt,
          cursorRunId: nextRunCursor.runId,
        } : {}),
        limit: PAGE_LIMIT,
      });
      if (token !== selectionGeneration || selectedWorkbenchId.value !== workbenchId) return;
      runs.value = replace ? page.items : [...runs.value, ...page.items];
      nextRunCursor = page.nextCursor;
    } catch {
      if (token === selectionGeneration && selectedWorkbenchId.value === workbenchId) {
        selectionError.value = '无法加载该 Workbench 的 Run 列表，请稍后重试。';
      }
    } finally {
      if (token === selectionGeneration) loadingRuns.value = false;
    }
  }

  async function selectWorkbench(workbenchId: string): Promise<void> {
    const token = ++selectionGeneration;
    selectedWorkbenchId.value = workbenchId;
    selectedWorkbench.value = null;
    runs.value = [];
    nextRunCursor = null;
    selectionError.value = null;
    lastAction.value = null;
    actionError.value = null;
    clearRunSelection();
    loadingSelection.value = true;
    const detailRequest = apiClient.getWorkbench(workbenchId);
    const runsRequest = loadRunPage(workbenchId, true, token);
    try {
      const detail = await detailRequest;
      if (token !== selectionGeneration || selectedWorkbenchId.value !== workbenchId) return;
      selectedWorkbench.value = detail;
    } catch {
      if (token === selectionGeneration && selectedWorkbenchId.value === workbenchId) {
        selectionError.value = '无法加载该 Workbench 的安全详情，请稍后重试。';
      }
    } finally {
      await runsRequest;
      if (token === selectionGeneration) loadingSelection.value = false;
    }
  }

  async function applyRunFilter(): Promise<void> {
    const workbenchId = selectedWorkbenchId.value;
    if (!workbenchId) return;
    const token = ++selectionGeneration;
    runs.value = [];
    nextRunCursor = null;
    clearRunSelection();
    await loadRunPage(workbenchId, true, token);
  }

  async function loadMoreRuns(): Promise<void> {
    const workbenchId = selectedWorkbenchId.value;
    if (!workbenchId || !nextRunCursor) return;
    await loadRunPage(workbenchId, false, selectionGeneration);
  }

  async function selectRun(runId: string): Promise<void> {
    const workbenchId = selectedWorkbenchId.value;
    if (!workbenchId) return;
    const token = ++runGeneration;
    selectedRunId.value = runId;
    selectedRun.value = null;
    actionError.value = null;
    loadingRunDetail.value = true;
    try {
      const run = await apiClient.getRun(workbenchId, runId);
      if (token !== runGeneration || selectedWorkbenchId.value !== workbenchId
        || selectedRunId.value !== runId) return;
      selectedRun.value = run;
    } catch {
      if (token === runGeneration && selectedRunId.value === runId) {
        actionError.value = '无法加载该 Run 的安全详情，请稍后重试。';
      }
    } finally {
      if (token === runGeneration) loadingRunDetail.value = false;
    }
  }

  async function refreshSelection(preserveRunId: string | null): Promise<void> {
    const workbenchId = selectedWorkbenchId.value;
    if (!workbenchId) return;
    const token = ++selectionGeneration;
    loadingSelection.value = true;
    nextRunCursor = null;
    const detailRequest = apiClient.getWorkbench(workbenchId);
    const runsRequest = loadRunPage(workbenchId, true, token);
    try {
      const detail = await detailRequest;
      if (token === selectionGeneration && selectedWorkbenchId.value === workbenchId) {
        selectedWorkbench.value = detail;
      }
    } catch {
      if (token === selectionGeneration) {
        selectionError.value = '运维动作已受理，但刷新 Workbench 状态失败。';
      }
    } finally {
      await runsRequest;
      if (token === selectionGeneration) loadingSelection.value = false;
    }
    if (preserveRunId && selectedWorkbenchId.value === workbenchId) {
      await selectRun(preserveRunId);
    }
  }

  async function runAction(kind: 'STOP' | 'RECONCILE'): Promise<void> {
    const workbenchId = selectedWorkbenchId.value;
    const runId = selectedRunId.value;
    if (!workbenchId || !runId || actionBusy.value) return;
    actionBusy.value = true;
    actionKind.value = kind;
    actionError.value = null;
    try {
      const result = kind === 'STOP'
        ? await apiClient.stopRun(workbenchId, runId)
        : await apiClient.reconcileRun(workbenchId, runId);
      lastAction.value = result;
      await refreshSelection(runId);
    } catch {
      actionError.value = kind === 'STOP'
        ? '停止请求失败；系统未确认已记录取消意图，请刷新后核对。'
        : '单 Run 对账失败；系统不会自动重放 Provider，请稍后重试。';
    } finally {
      actionBusy.value = false;
      actionKind.value = null;
    }
  }

  async function stopSelectedRun(): Promise<void> {
    if (!canStopSelectedRun.value) return;
    await runAction('STOP');
  }

  async function reconcileSelectedRun(): Promise<void> {
    if (!canReconcileSelectedRun.value) return;
    await runAction('RECONCILE');
  }

  async function refresh(): Promise<void> {
    if (selectedWorkbenchId.value) {
      await refreshSelection(selectedRunId.value);
      return;
    }
    await loadInitial();
  }

  return {
    workbenches,
    selectedWorkbenchId,
    selectedWorkbench,
    runs,
    selectedRunId,
    selectedRun,
    workbenchStatusFilter,
    runStatusFilter,
    loadingWorkbenches,
    loadingSelection,
    loadingRuns,
    loadingRunDetail,
    actionBusy,
    actionKind,
    workbenchError,
    selectionError,
    actionError,
    lastAction,
    hasMoreWorkbenches,
    hasMoreRuns,
    canStopSelectedRun,
    canReconcileSelectedRun,
    loadInitial,
    refresh,
    applyWorkbenchFilter,
    loadMoreWorkbenches,
    selectWorkbench,
    applyRunFilter,
    loadMoreRuns,
    selectRun,
    stopSelectedRun,
    reconcileSelectedRun,
  };
}
