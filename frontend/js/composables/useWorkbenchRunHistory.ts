/**
 * Workbench 完成态 Run 历史、分页事件恢复与实际能力绑定查询编排。
 *
 * @author alex
 * @since 2026-08-01
 */
import { ref, shallowRef, watch, type Ref, type ShallowRef } from 'vue';
import {
  createWorkbenchRunApiClient,
  type WorkbenchRunApiClient,
  type WorkbenchRunCapability,
  type WorkbenchRunHistoryCursor,
  type WorkbenchRunHistoryItem,
} from '../api/workbench-run.js';
import {
  applyWorkbenchRunEvent,
  createWorkbenchRunState,
  type WorkbenchRunContext,
  type WorkbenchRunState,
} from '../lib/workbench-run-state.js';

const RUN_PAGE_LIMIT = 20;
const EVENT_PAGE_LIMIT = 200;

export interface UseWorkbenchRunHistoryOptions {
  workbenchId: Ref<string | null>;
  stageInstanceIdentifier: Ref<string | null>;
  apiClient?: WorkbenchRunApiClient;
}

export interface UseWorkbenchRunHistory {
  visible: Ref<boolean>;
  loadingRuns: Ref<boolean>;
  loadingSelection: Ref<boolean>;
  loadingEvents: Ref<boolean>;
  runs: Ref<WorkbenchRunHistoryItem[]>;
  selectedRunId: Ref<string | null>;
  selectedRun: Ref<WorkbenchRunHistoryItem | null>;
  runState: ShallowRef<WorkbenchRunState | null>;
  capability: Ref<WorkbenchRunCapability | null>;
  historyError: Ref<string | null>;
  capabilityError: Ref<string | null>;
  hasMoreRuns: Ref<boolean>;
  hasMoreEvents: Ref<boolean>;
  open(): Promise<void>;
  close(): void;
  refresh(): Promise<void>;
  selectRun(runId: string): Promise<void>;
  loadMoreRuns(): Promise<void>;
  loadMoreEvents(): Promise<void>;
}

export function useWorkbenchRunHistory(
  options: UseWorkbenchRunHistoryOptions,
): UseWorkbenchRunHistory {
  const apiClient = options.apiClient ?? createWorkbenchRunApiClient();
  const visible = ref(false);
  const loadingRuns = ref(false);
  const loadingSelection = ref(false);
  const loadingEvents = ref(false);
  const runs = ref<WorkbenchRunHistoryItem[]>([]);
  const selectedRunId = ref<string | null>(null);
  const selectedRun = ref<WorkbenchRunHistoryItem | null>(null);
  const runState = shallowRef<WorkbenchRunState | null>(null);
  const capability = ref<WorkbenchRunCapability | null>(null);
  const historyError = ref<string | null>(null);
  const capabilityError = ref<string | null>(null);
  const hasMoreRuns = ref(false);
  const hasMoreEvents = ref(false);
  let nextCursor: WorkbenchRunHistoryCursor | null = null;
  let selectionAfter = 0;
  let generation = 0;
  let selectionGeneration = 0;

  function identity(): {
    workbenchId: string;
    stageInstanceIdentifier: string;
  } | null {
    const workbenchId = options.workbenchId.value?.trim();
    const stageInstanceIdentifier =
      options.stageInstanceIdentifier.value?.trim();
    return workbenchId && stageInstanceIdentifier
      ? { workbenchId, stageInstanceIdentifier }
      : null;
  }

  function clearSelection(): void {
    selectionGeneration++;
    selectedRunId.value = null;
    selectedRun.value = null;
    runState.value = null;
    capability.value = null;
    capabilityError.value = null;
    hasMoreEvents.value = false;
    selectionAfter = 0;
  }

  function reset(closeDrawer: boolean): void {
    generation++;
    if (closeDrawer) visible.value = false;
    loadingRuns.value = false;
    loadingSelection.value = false;
    loadingEvents.value = false;
    runs.value = [];
    nextCursor = null;
    hasMoreRuns.value = false;
    historyError.value = null;
    clearSelection();
  }

  function context(run: WorkbenchRunHistoryItem): WorkbenchRunContext {
    return {
      workbenchId: run.workbenchId,
      stageInstanceIdentifier: run.stageInstanceIdentifier,
      runId: run.runId,
    };
  }

  async function loadInitialEvents(
    run: WorkbenchRunHistoryItem,
    earliestRetainedSeq: number,
    token: number,
  ): Promise<void> {
    const after = Math.max(0, earliestRetainedSeq - 1);
    const page = await apiClient.getRunEvents(run.workbenchId, run.runId, {
      after,
      limit: EVENT_PAGE_LIMIT,
    });
    if (token !== selectionGeneration || selectedRunId.value !== run.runId) return;
    if (page.runId !== run.runId || page.after !== after) {
      throw new Error('historical event page does not match selected Run');
    }
    let state = createWorkbenchRunState(context(run));
    if (after > 0) state = { ...state, lastAppliedEventSeq: after };
    for (const event of page.events) {
      state = applyWorkbenchRunEvent(state, {
        id: String(event.sequence),
        type: event.eventType,
        data: event.payload,
      }, context(run));
    }
    runState.value = state;
    selectionAfter = page.through;
    hasMoreEvents.value = page.hasMore;
  }

  async function loadExactRunEvents(
    run: WorkbenchRunHistoryItem,
    token: number,
  ): Promise<void> {
    const detail = await apiClient.getRun(run.workbenchId, run.runId);
    if (token !== selectionGeneration || selectedRunId.value !== run.runId) return;
    if (detail.runId !== run.runId || detail.workbenchId !== run.workbenchId
      || detail.stageInstanceIdentifier !== run.stageInstanceIdentifier
      || detail.runMode !== run.runMode
      || detail.sessionId !== run.sessionId || detail.earliestRetainedSeq == null) {
      throw new Error('historical Run detail does not match selected Run');
    }
    await loadInitialEvents(run, detail.earliestRetainedSeq, token);
  }

  async function loadCapability(
    run: WorkbenchRunHistoryItem,
    token: number,
  ): Promise<void> {
    try {
      const binding = await apiClient.getRunCapability(run.workbenchId, run.runId);
      if (token !== selectionGeneration || selectedRunId.value !== run.runId) return;
      if (binding.runId !== run.runId || binding.workbenchId !== run.workbenchId
        || binding.stageInstanceIdentifier !== run.stageInstanceIdentifier
        || binding.runMode !== run.runMode) {
        throw new Error('historical capability does not match selected Run');
      }
      capability.value = binding;
      capabilityError.value = null;
    } catch {
      if (token === selectionGeneration && selectedRunId.value === run.runId) {
        capability.value = null;
        capabilityError.value = '无法加载该 Run 实际冻结的能力绑定。';
      }
    }
  }

  async function selectRun(runId: string): Promise<void> {
    const run = runs.value.find(candidate => candidate.runId === runId) ?? null;
    if (!run) return;
    const token = ++selectionGeneration;
    selectedRunId.value = run.runId;
    selectedRun.value = run;
    runState.value = null;
    capability.value = null;
    historyError.value = null;
    capabilityError.value = null;
    hasMoreEvents.value = false;
    selectionAfter = 0;
    loadingSelection.value = true;
    try {
      await Promise.all([
        loadExactRunEvents(run, token),
        loadCapability(run, token),
      ]);
    } catch {
      if (token === selectionGeneration && selectedRunId.value === run.runId) {
        runState.value = null;
        hasMoreEvents.value = false;
        historyError.value = '无法恢复该 Run 的历史事件，请稍后重试。';
      }
    } finally {
      if (token === selectionGeneration) loadingSelection.value = false;
    }
  }

  async function loadRuns(replace: boolean): Promise<void> {
    const current = identity();
    if (!current || loadingRuns.value) return;
    const identityToken = generation;
    loadingRuns.value = true;
    historyError.value = null;
    try {
      const page = await apiClient.listRuns(current.workbenchId, {
        stageInstanceIdentifier: current.stageInstanceIdentifier,
        ...(replace || !nextCursor ? {} : {
          cursorCreatedAt: nextCursor.createdAt,
          cursorRunId: nextCursor.runId,
        }),
        limit: RUN_PAGE_LIMIT,
      });
      if (identityToken !== generation) return;
      runs.value = replace ? page.items : [...runs.value, ...page.items];
      nextCursor = page.nextCursor;
      hasMoreRuns.value = page.nextCursor != null;
      if (replace) {
        clearSelection();
        if (runs.value.length > 0) await selectRun(runs.value[0].runId);
      }
    } catch {
      if (identityToken === generation) {
        historyError.value = '无法加载本阶段 Run 历史，请稍后重试。';
      }
    } finally {
      if (identityToken === generation) loadingRuns.value = false;
    }
  }

  async function open(): Promise<void> {
    reset(false);
    if (!identity()) return;
    visible.value = true;
    await loadRuns(true);
  }

  function close(): void {
    visible.value = false;
  }

  async function refresh(): Promise<void> {
    reset(false);
    if (!identity()) return;
    visible.value = true;
    await loadRuns(true);
  }

  async function loadMoreRuns(): Promise<void> {
    if (!nextCursor) return;
    await loadRuns(false);
  }

  async function loadMoreEvents(): Promise<void> {
    const run = selectedRun.value;
    if (!run || !hasMoreEvents.value || loadingEvents.value) return;
    const identityToken = generation;
    const selectionToken = selectionGeneration;
    const after = selectionAfter;
    loadingEvents.value = true;
    historyError.value = null;
    try {
      const page = await apiClient.getRunEvents(run.workbenchId, run.runId, {
        after,
        limit: EVENT_PAGE_LIMIT,
      });
      if (identityToken !== generation || selectionToken !== selectionGeneration
        || selectedRunId.value !== run.runId) return;
      if (page.runId !== run.runId || page.after !== after) {
        throw new Error('historical event page does not continue selected Run');
      }
      let state = runState.value ?? createWorkbenchRunState(context(run));
      for (const event of page.events) {
        state = applyWorkbenchRunEvent(state, {
          id: String(event.sequence),
          type: event.eventType,
          data: event.payload,
        }, context(run));
      }
      runState.value = state;
      selectionAfter = page.through;
      hasMoreEvents.value = page.hasMore;
    } catch {
      if (identityToken === generation && selectionToken === selectionGeneration) {
        historyError.value = '无法继续加载该 Run 的历史事件。';
      }
    } finally {
      if (identityToken === generation && selectionToken === selectionGeneration) {
        loadingEvents.value = false;
      }
    }
  }

  watch(
    () => `${options.workbenchId.value ?? ''}\u0000${
      options.stageInstanceIdentifier.value ?? ''
    }`,
    () => reset(true),
    { flush: 'sync' },
  );

  return {
    visible,
    loadingRuns,
    loadingSelection,
    loadingEvents,
    runs,
    selectedRunId,
    selectedRun,
    runState,
    capability,
    historyError,
    capabilityError,
    hasMoreRuns,
    hasMoreEvents,
    open,
    close,
    refresh,
    selectRun,
    loadMoreRuns,
    loadMoreEvents,
  };
}
