/**
 * Workbench 高影响操作列表与 Owner 决策编排。
 *
 * @author alex
 * @since 2026-08-01
 */
import { computed, ref, shallowRef, watch, type Ref } from 'vue';
import {
  WorkbenchOperationApiError,
  createWorkbenchOperationApiClient,
  type WorkbenchHighImpactOperation,
  type WorkbenchOperationApiClient,
  type WorkbenchOperationDecision,
} from '../api/workbench-operation.js';
import type { WorkbenchPhase } from '../lib/workbench-state.js';

export interface UseWorkbenchOperationsOptions {
  workbenchId: Ref<string | null>;
  phase: Ref<WorkbenchPhase>;
  archived?: Ref<boolean>;
  apiClient?: WorkbenchOperationApiClient;
}

export function useWorkbenchOperations(options: UseWorkbenchOperationsOptions) {
  const apiClient = options.apiClient ?? createWorkbenchOperationApiClient();
  const operations = shallowRef<ReadonlyArray<WorkbenchHighImpactOperation>>([]);
  const operationLoading = ref(false);
  const operationDecidingId = ref<string | null>(null);
  const operationError = ref<string | null>(null);
  const operationNotice = ref<string | null>(null);
  let scopeGeneration = 0;
  let mutationGeneration = 0;

  const operationReadOnly = computed(() => options.archived?.value ?? false);
  const phaseOperations = computed(() => operations.value
    .filter(operation => operation.phase === options.phase.value));

  async function loadOperations(): Promise<void> {
    const workbenchId = currentWorkbenchId();
    if (!workbenchId) {
      resetScope();
      return;
    }
    const generation = ++scopeGeneration;
    mutationGeneration++;
    operationLoading.value = true;
    operationDecidingId.value = null;
    operationError.value = null;
    operationNotice.value = null;
    try {
      const loaded = await apiClient.list(workbenchId);
      if (generation !== scopeGeneration || currentWorkbenchId() !== workbenchId) return;
      operations.value = loaded.slice();
    } catch (error) {
      if (generation !== scopeGeneration || currentWorkbenchId() !== workbenchId) return;
      operations.value = [];
      operationError.value = operationErrorMessage(error);
    } finally {
      if (generation === scopeGeneration) operationLoading.value = false;
    }
  }

  async function decideOperation(
    requested: WorkbenchHighImpactOperation,
    decision: WorkbenchOperationDecision,
    reason: string,
  ): Promise<void> {
    const workbenchId = currentWorkbenchId();
    if (
      !workbenchId ||
      operationReadOnly.value ||
      operationDecidingId.value ||
      (decision !== 'APPROVE' && decision !== 'REJECT') ||
      typeof reason !== 'string' ||
      !reason.trim()
    ) return;
    const current = operations.value.find(item => item.operationId === requested.operationId);
    if (!current || current.status !== 'PROPOSED') return;
    const generation = ++mutationGeneration;
    const scope = scopeGeneration;
    operationDecidingId.value = current.operationId;
    operationError.value = null;
    operationNotice.value = null;
    try {
      const updated = await apiClient.decide(
        workbenchId,
        current.operationId,
        current.version,
        { decision, reason: reason.trim() },
      );
      if (!isCurrent(workbenchId, scope, generation)) return;
      replaceOperation(updated);
      operationNotice.value = decision === 'APPROVE'
        ? '已记录独立授权；当前执行器未开放，不会自动执行。'
        : '已拒绝该高影响操作；不会执行任何外部副作用。';
    } catch (error) {
      if (!isCurrent(workbenchId, scope, generation)) return;
      if (isVersionConflict(error)) {
        replaceOperation(error.current);
        operationError.value = '操作状态或目标已变化，已显示服务端当前版本，请重新核对。';
      } else {
        operationError.value = operationErrorMessage(error);
      }
    } finally {
      if (isCurrent(workbenchId, scope, generation)) operationDecidingId.value = null;
    }
  }

  function replaceOperation(updated: WorkbenchHighImpactOperation): void {
    const exists = operations.value.some(item => item.operationId === updated.operationId);
    operations.value = exists
      ? operations.value.map(item => item.operationId === updated.operationId ? updated : item)
      : [updated, ...operations.value];
  }

  function currentWorkbenchId(): string | null {
    return options.workbenchId.value?.trim() || null;
  }

  function isCurrent(workbenchId: string, scope: number, generation: number): boolean {
    return scope === scopeGeneration &&
      generation === mutationGeneration &&
      currentWorkbenchId() === workbenchId;
  }

  function resetScope(): void {
    scopeGeneration++;
    mutationGeneration++;
    operations.value = [];
    operationLoading.value = false;
    operationDecidingId.value = null;
    operationError.value = null;
    operationNotice.value = null;
  }

  watch(
    () => currentWorkbenchId() ?? '',
    () => {
      if (currentWorkbenchId()) void loadOperations();
      else resetScope();
    },
    { immediate: true, flush: 'sync' },
  );

  return {
    operations,
    phaseOperations,
    operationLoading,
    operationDecidingId,
    operationError,
    operationNotice,
    operationReadOnly,
    loadOperations,
    decideOperation,
  };
}

function isVersionConflict(error: unknown): error is WorkbenchOperationApiError & {
  current: WorkbenchHighImpactOperation;
} {
  return error instanceof WorkbenchOperationApiError &&
    error.status === 409 &&
    error.code === 'WORKBENCH_OPERATION_VERSION_CONFLICT' &&
    error.current != null;
}

function operationErrorMessage(error: unknown): string {
  if (!(error instanceof WorkbenchOperationApiError)) {
    return '高影响操作请求失败，请稍后重试。';
  }
  switch (error.code) {
    case 'WORKBENCH_ARCHIVED':
      return 'Workbench 已归档，高影响操作仅可查看。';
    case 'WORKBENCH_OPERATION_NOT_FOUND':
    case 'WORKBENCH_NOT_FOUND':
      return '操作不存在或无权访问。';
    case 'WORKBENCH_OPERATION_TRANSITION_INVALID':
      return '操作已不处于可决策状态，请刷新后核对。';
    case 'WORKBENCH_OPERATION_TARGET_CHANGED':
      return '操作目标已变化，旧授权不可继续使用。';
    case 'WORKBENCH_OPERATION_REQUEST_INVALID':
      return '操作决策或理由不符合约束。';
    default:
      return '高影响操作请求失败，请稍后重试。';
  }
}
