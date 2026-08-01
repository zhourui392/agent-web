/**
 * TD-07 Phase Handoff 加载、人工保存与上游 Reception 编排。
 *
 * @author alex
 * @since 2026-08-01
 */
import { computed, ref, shallowRef, watch, type Ref } from 'vue';
import {
  WorkbenchHandoffApiError,
  createWorkbenchHandoffApiClient,
  type HandoffEditableContent,
  type WorkbenchHandoffApiClient,
} from '../api/workbench-handoff.js';
import {
  acceptWorkbenchHandoffSource,
  adoptWorkbenchHandoffConflict,
  applyWorkbenchHandoffDraft,
  applyWorkbenchHandoffSave,
  copyHandoffContent,
  createWorkbenchHandoffState,
  keepCurrentWorkbenchHandoffSource,
  recordWorkbenchHandoffConflict,
  replaceWorkbenchHandoffSource,
} from '../lib/workbench-handoff-state.js';
import type { WorkbenchPhase } from '../lib/workbench-state.js';

export interface UseWorkbenchHandoffOptions {
  workbenchId: Ref<string | null>;
  phase: Ref<WorkbenchPhase>;
  archived?: Ref<boolean>;
  apiClient?: WorkbenchHandoffApiClient;
}

interface HandoffIdentity {
  workbenchId: string;
  phase: WorkbenchPhase;
}

export function useWorkbenchHandoff(options: UseWorkbenchHandoffOptions) {
  const apiClient = options.apiClient ?? createWorkbenchHandoffApiClient();
  const state = shallowRef(createWorkbenchHandoffState());
  const handoffDrawerVisible = ref(false);
  const handoffLoading = ref(false);
  const handoffSaving = ref(false);
  const handoffAccepting = ref(false);
  const handoffError = ref<string | null>(null);
  const handoffNotice = ref<string | null>(null);
  let scopeGeneration = 0;
  let mutationGeneration = 0;

  const handoffCurrent = computed(() => state.value.current);
  const handoffDraft = computed(() => state.value.draft);
  const handoffSource = computed(() => state.value.source);
  const handoffConflict = computed(() => state.value.conflict);
  const handoffDirty = computed(() => state.value.dirty);
  const handoffReadOnly = computed(() => state.value.readOnly);
  const keepCurrentDismissed = computed(() => state.value.keepCurrentDismissed);
  const handoffCanSave = computed(
    () => state.value.dirty && !state.value.readOnly && !handoffSaving.value && !handoffAccepting.value,
  );
  const handoffCanAccept = computed(
    () =>
      Boolean(state.value.source?.latestSource) &&
      !state.value.readOnly &&
      !handoffAccepting.value &&
      !handoffSaving.value,
  );

  function openHandoffDrawer(): void {
    handoffDrawerVisible.value = true;
  }

  function closeHandoffDrawer(): void {
    handoffDrawerVisible.value = false;
  }

  async function loadHandoff(): Promise<void> {
    const identity = currentIdentity();
    if (!identity) {
      resetScope();
      return;
    }
    const generation = ++scopeGeneration;
    mutationGeneration++;
    handoffLoading.value = true;
    handoffSaving.value = false;
    handoffAccepting.value = false;
    handoffError.value = null;
    handoffNotice.value = null;
    state.value = createWorkbenchHandoffState(null, null, isArchived());
    try {
      const [current, source] = await Promise.all([
        apiClient.getHandoff(identity.workbenchId, identity.phase),
        apiClient.getHandoffSource(identity.workbenchId, identity.phase),
      ]);
      if (generation !== scopeGeneration || !sameIdentity(identity)) return;
      state.value = createWorkbenchHandoffState(current, source, isArchived());
    } catch (error) {
      if (generation !== scopeGeneration || !sameIdentity(identity)) return;
      state.value = createWorkbenchHandoffState(null, null, isArchived());
      handoffError.value = handoffErrorMessage(error);
    } finally {
      if (generation === scopeGeneration) handoffLoading.value = false;
    }
  }

  async function refreshHandoffSource(): Promise<void> {
    const identity = currentIdentity();
    if (!identity) return;
    const generation = scopeGeneration;
    handoffError.value = null;
    try {
      const source = await apiClient.getHandoffSource(identity.workbenchId, identity.phase);
      if (generation !== scopeGeneration || !sameIdentity(identity)) return;
      state.value = replaceWorkbenchHandoffSource(state.value, source);
    } catch (error) {
      if (generation === scopeGeneration && sameIdentity(identity)) {
        handoffError.value = handoffErrorMessage(error);
      }
    }
  }

  function updateHandoffDraft(draft: HandoffEditableContent): void {
    if (state.value.readOnly) return;
    state.value = applyWorkbenchHandoffDraft(state.value, draft);
    handoffError.value = null;
    handoffNotice.value = null;
  }

  async function saveHandoff(): Promise<void> {
    const identity = currentIdentity();
    if (!identity || state.value.readOnly || handoffSaving.value || handoffAccepting.value || !state.value.dirty)
      return;
    const generation = ++mutationGeneration;
    const scope = scopeGeneration;
    const expectedVersion = state.value.current?.version ?? 0;
    const submitted = copyHandoffContent(state.value.draft);
    handoffSaving.value = true;
    handoffError.value = null;
    handoffNotice.value = null;
    try {
      const saved = await apiClient.putHandoff(identity.workbenchId, identity.phase, expectedVersion, submitted);
      if (!isCurrentMutation(identity, scope, generation)) return;
      state.value = applyWorkbenchHandoffSave(state.value, saved);
      handoffNotice.value = `交接内容已保存为版本 ${saved.version}。`;
    } catch (error) {
      if (!isCurrentMutation(identity, scope, generation)) return;
      if (isVersionConflict(error)) {
        state.value = recordWorkbenchHandoffConflict(state.value, error.current);
        handoffError.value = '交接内容已被其他页面更新，本地草稿仍保留，请对比后处理。';
      } else {
        handoffError.value = handoffErrorMessage(error);
      }
    } finally {
      if (isCurrentMutation(identity, scope, generation)) handoffSaving.value = false;
    }
  }

  async function acceptLatestSource(): Promise<void> {
    const identity = currentIdentity();
    const latest = state.value.source?.latestSource;
    if (!identity || !latest || state.value.readOnly || handoffAccepting.value || handoffSaving.value) return;
    const generation = ++mutationGeneration;
    const scope = scopeGeneration;
    handoffAccepting.value = true;
    handoffError.value = null;
    handoffNotice.value = null;
    try {
      const reception = await apiClient.acceptHandoffReception(identity.workbenchId, identity.phase, {
        sourcePhase: latest.sourcePhase,
        sourceVersion: latest.version,
        sourceHash: latest.contentHash,
      });
      if (!isCurrentMutation(identity, scope, generation)) return;
      state.value = acceptWorkbenchHandoffSource(state.value, reception);
      handoffNotice.value = `已接受上游版本 ${reception.sourceVersion}。`;
    } catch (error) {
      if (isCurrentMutation(identity, scope, generation)) {
        handoffError.value = handoffErrorMessage(error);
      }
    } finally {
      if (isCurrentMutation(identity, scope, generation)) handoffAccepting.value = false;
    }
  }

  function keepCurrentSource(): void {
    state.value = keepCurrentWorkbenchHandoffSource(state.value);
    if (state.value.keepCurrentDismissed) {
      handoffNotice.value = '已在本页面保留当前接收版本；上游更新提示稍后仍可重新查看。';
    }
  }

  function adoptRemoteCurrent(): void {
    if (state.value.readOnly || !state.value.conflict) return;
    state.value = adoptWorkbenchHandoffConflict(state.value);
    handoffError.value = null;
    handoffNotice.value = '已用服务端当前版本替换本地草稿。';
  }

  function currentIdentity(): HandoffIdentity | null {
    const workbenchId = options.workbenchId.value;
    return workbenchId ? { workbenchId, phase: options.phase.value } : null;
  }

  function sameIdentity(identity: HandoffIdentity): boolean {
    return options.workbenchId.value === identity.workbenchId && options.phase.value === identity.phase;
  }

  function isCurrentMutation(identity: HandoffIdentity, scope: number, generation: number): boolean {
    return scope === scopeGeneration && generation === mutationGeneration && sameIdentity(identity);
  }

  function isArchived(): boolean {
    return options.archived?.value ?? false;
  }

  function resetScope(): void {
    scopeGeneration++;
    mutationGeneration++;
    state.value = createWorkbenchHandoffState(null, null, isArchived());
    handoffLoading.value = false;
    handoffSaving.value = false;
    handoffAccepting.value = false;
    handoffError.value = null;
    handoffNotice.value = null;
    handoffDrawerVisible.value = false;
  }

  watch(
    () =>
      `${options.workbenchId.value ?? ''}\u0000${options.phase.value}` +
      `\u0000${isArchived() ? 'archived' : 'active'}`,
    () => {
      if (options.workbenchId.value) void loadHandoff();
      else resetScope();
    },
    { immediate: true, flush: 'sync' },
  );

  return {
    handoffDrawerVisible,
    handoffCurrent,
    handoffDraft,
    handoffSource,
    handoffConflict,
    handoffDirty,
    handoffReadOnly,
    keepCurrentDismissed,
    handoffLoading,
    handoffSaving,
    handoffAccepting,
    handoffError,
    handoffNotice,
    handoffCanSave,
    handoffCanAccept,
    openHandoffDrawer,
    closeHandoffDrawer,
    loadHandoff,
    refreshHandoffSource,
    updateHandoffDraft,
    saveHandoff,
    acceptLatestSource,
    keepCurrentSource,
    adoptRemoteCurrent,
  };
}

function isVersionConflict(error: unknown): error is WorkbenchHandoffApiError {
  return (
    error instanceof WorkbenchHandoffApiError &&
    error.status === 409 &&
    ['WORKBENCH_HANDOFF_VERSION_CONFLICT', 'WORKBENCH_VERSION_CONFLICT'].includes(error.code)
  );
}

function handoffErrorMessage(error: unknown): string {
  if (!(error instanceof WorkbenchHandoffApiError)) {
    return '交接请求失败，请稍后重试。';
  }
  switch (error.code) {
    case 'WORKBENCH_ARCHIVED':
      return 'Workbench 已归档，交接内容仅可查看。';
    case 'WORKBENCH_HANDOFF_SOURCE_CHANGED':
    case 'WORKBENCH_VERSION_CONFLICT':
      return '上游交接版本已变化，请刷新预览后重新接受。';
    case 'WORKBENCH_HANDOFF_SECRET_DETECTED':
      return '交接内容疑似包含敏感信息，请移除后重试。';
    case 'WORKBENCH_REPOSITORY_SCOPE_INVALID':
      return 'Pinned File 不在当前 Workbench 仓库范围内。';
    case 'WORKBENCH_RUN_REFERENCE_INVALID':
      return 'Referenced Run 不属于当前 Workbench 或已不可用。';
    case 'WORKBENCH_NOT_FOUND':
      return 'Workbench 不存在或无权访问。';
    case 'WORKBENCH_HANDOFF_REQUEST_INVALID':
    case 'WORKBENCH_REQUEST_INVALID':
      return '交接内容不符合字段或长度约束。';
    default:
      return '交接请求失败，请稍后重试。';
  }
}
