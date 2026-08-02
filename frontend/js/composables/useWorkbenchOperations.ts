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
  type WorkbenchOperationProposalInput,
} from '../api/workbench-operation.js';
import {
  createWorkbenchRunApiClient,
  type WorkbenchRunApiClient,
  type WorkbenchRunHistoryItem,
} from '../api/workbench-run.js';
import type { WorkbenchRepositoryView } from '../api/workbench.js';
import type { WorkbenchPhase } from '../lib/workbench-state.js';

interface GitCommitOperationProposalDraftTarget {
  type: 'GIT_COMMIT';
  repositoryKey: string;
  branch: string;
  expectedHead: string;
  expectedStateHash: string;
  includedPaths: ReadonlyArray<string>;
  safeMessagePreview: string;
}

interface GitPushOperationProposalDraftTarget {
  type: 'GIT_PUSH';
  repositoryKey: string;
  remoteName: string;
  localBranch: string;
  remoteRef: string;
  expectedLocalHead: string;
}

interface LocalDeployOperationProposalDraftTarget {
  type: 'LOCAL_DEPLOY';
  templateId: string;
  templateVersion: string;
  templateHash: string;
  repositoryTargets: ReadonlyArray<string>;
  environment: 'LOCAL';
  expectedWorkspaceStateHash: string;
  rollbackSummary: string;
}

interface ProductionWriteOperationProposalDraftTarget {
  type: 'PRODUCTION_WRITE';
  environment: string;
  resourceReference: string;
  expectedProductionStateHash: string;
}

export interface WorkbenchOperationProposalDraft {
  sourceRunId: string;
  safeSummary: string;
  target:
    | GitCommitOperationProposalDraftTarget
    | GitPushOperationProposalDraftTarget
    | LocalDeployOperationProposalDraftTarget
    | ProductionWriteOperationProposalDraftTarget;
}

export interface UseWorkbenchOperationsOptions {
  workbenchId: Ref<string | null>;
  phase: Ref<WorkbenchPhase>;
  archived?: Ref<boolean>;
  repositories?: Ref<ReadonlyArray<WorkbenchRepositoryView>>;
  apiClient?: WorkbenchOperationApiClient;
  runApiClient?: WorkbenchRunApiClient;
  idempotencyKeyFactory?: () => string;
}

export function useWorkbenchOperations(options: UseWorkbenchOperationsOptions) {
  const apiClient = options.apiClient ?? createWorkbenchOperationApiClient();
  const runApiClient = options.runApiClient ?? createWorkbenchRunApiClient();
  const operations = shallowRef<ReadonlyArray<WorkbenchHighImpactOperation>>([]);
  const operationLoading = ref(false);
  const operationDecidingId = ref<string | null>(null);
  const operationError = ref<string | null>(null);
  const operationNotice = ref<string | null>(null);
  const operationSourceRuns = shallowRef<ReadonlyArray<WorkbenchRunHistoryItem>>([]);
  const operationSourceRunsLoading = ref(false);
  const operationProposing = ref(false);
  const operationProposalCreatedToken = ref(0);
  const operationProposalDisabledReason = ref<string | null>(
    options.archived?.value ? 'Workbench 已归档，不能新建高影响操作。' : '请先加载当前阶段的真实 Run。',
  );
  let scopeGeneration = 0;
  let mutationGeneration = 0;
  let proposalScopeGeneration = 0;
  let proposalMutationGeneration = 0;
  let lastProposalFingerprint: string | null = null;
  let lastProposalIdempotencyKey: string | null = null;

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

  async function prepareOperationProposal(): Promise<void> {
    const workbenchId = currentWorkbenchId();
    const phase = options.phase.value;
    if (!workbenchId) {
      resetProposalScope();
      operationProposalDisabledReason.value = '请先选择 Workbench。';
      return;
    }
    if (operationReadOnly.value) {
      resetProposalScope();
      operationProposalDisabledReason.value = 'Workbench 已归档，不能新建高影响操作。';
      return;
    }
    const generation = ++proposalScopeGeneration;
    operationSourceRunsLoading.value = true;
    operationProposalDisabledReason.value = null;
    try {
      const page = await runApiClient.listRuns(workbenchId, { phase, limit: 100 });
      if (!isProposalScopeCurrent(workbenchId, phase, generation)) return;
      operationSourceRuns.value = page.items.filter(
        run => run.workbenchId === workbenchId && run.phase === phase,
      );
      operationProposalDisabledReason.value = operationSourceRuns.value.length
        ? null
        : '当前阶段没有可选择的真实 Run，不能创建提案。';
    } catch {
      if (!isProposalScopeCurrent(workbenchId, phase, generation)) return;
      operationSourceRuns.value = [];
      operationProposalDisabledReason.value = '真实 Run 加载失败，请重试后再创建提案。';
    } finally {
      if (isProposalScopeCurrent(workbenchId, phase, generation)) {
        operationSourceRunsLoading.value = false;
      }
    }
  }

  async function proposeOperation(draft: WorkbenchOperationProposalDraft): Promise<void> {
    const workbenchId = currentWorkbenchId();
    const phase = options.phase.value;
    if (!workbenchId || operationReadOnly.value || operationProposing.value) return;
    if (!operationSourceRuns.value.some(run =>
      run.runId === draft.sourceRunId && run.workbenchId === workbenchId && run.phase === phase)) {
      operationProposalDisabledReason.value = '请选择当前阶段真实存在的 Source Run。';
      return;
    }
    const generation = ++proposalMutationGeneration;
    const scope = proposalScopeGeneration;
    operationProposing.value = true;
    operationError.value = null;
    operationNotice.value = null;
    try {
      const input = await proposalInput(draft, phase, scopedRepositoryKeys());
      if (!isProposalMutationCurrent(workbenchId, phase, scope, generation)) return;
      const fingerprint = JSON.stringify(input);
      if (fingerprint !== lastProposalFingerprint || !lastProposalIdempotencyKey) {
        lastProposalFingerprint = fingerprint;
        lastProposalIdempotencyKey = (options.idempotencyKeyFactory ?? defaultIdempotencyKey)();
      }
      const idempotencyKey = lastProposalIdempotencyKey;
      const proposed = await apiClient.propose(workbenchId, idempotencyKey, input);
      if (!isProposalMutationCurrent(workbenchId, phase, scope, generation)) return;
      replaceOperation(proposed);
      operationProposalCreatedToken.value++;
      operationNotice.value = '仅创建待决策提案；尚未授权，也不会自动执行。';
    } catch (error) {
      if (!isProposalMutationCurrent(workbenchId, phase, scope, generation)) return;
      if (error instanceof WorkbenchOperationApiError && error.code === 'IDEMPOTENCY_CONFLICT') {
        lastProposalFingerprint = null;
        lastProposalIdempotencyKey = null;
      }
      operationError.value = error instanceof WorkbenchOperationApiError
        ? operationErrorMessage(error)
        : '提案字段不符合类型约束，请核对后重试。';
    } finally {
      if (isProposalMutationCurrent(workbenchId, phase, scope, generation)) {
        operationProposing.value = false;
      }
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

  function scopedRepositoryKeys(): ReadonlySet<string> {
    return new Set((options.repositories?.value ?? []).map(repository => repository.repositoryKey));
  }

  function isCurrent(workbenchId: string, scope: number, generation: number): boolean {
    return scope === scopeGeneration &&
      generation === mutationGeneration &&
      currentWorkbenchId() === workbenchId;
  }

  function isProposalScopeCurrent(
    workbenchId: string,
    phase: WorkbenchPhase,
    generation: number,
  ): boolean {
    return generation === proposalScopeGeneration &&
      currentWorkbenchId() === workbenchId &&
      options.phase.value === phase;
  }

  function isProposalMutationCurrent(
    workbenchId: string,
    phase: WorkbenchPhase,
    scope: number,
    generation: number,
  ): boolean {
    return scope === proposalScopeGeneration &&
      generation === proposalMutationGeneration &&
      currentWorkbenchId() === workbenchId &&
      options.phase.value === phase;
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

  function resetProposalScope(): void {
    proposalScopeGeneration++;
    proposalMutationGeneration++;
    operationSourceRuns.value = [];
    operationSourceRunsLoading.value = false;
    operationProposing.value = false;
    operationProposalCreatedToken.value = 0;
    operationProposalDisabledReason.value = operationReadOnly.value
      ? 'Workbench 已归档，不能新建高影响操作。'
      : '请先加载当前阶段的真实 Run。';
    lastProposalFingerprint = null;
    lastProposalIdempotencyKey = null;
  }

  watch(
    () => currentWorkbenchId() ?? '',
    () => {
      if (currentWorkbenchId()) void loadOperations();
      else resetScope();
    },
    { immediate: true, flush: 'sync' },
  );

  watch(
    () => [currentWorkbenchId() ?? '', options.phase.value, operationReadOnly.value] as const,
    () => resetProposalScope(),
    { flush: 'sync' },
  );

  return {
    operations,
    phaseOperations,
    operationLoading,
    operationDecidingId,
    operationError,
    operationNotice,
    operationReadOnly,
    operationSourceRuns,
    operationSourceRunsLoading,
    operationProposing,
    operationProposalCreatedToken,
    operationProposalDisabledReason,
    loadOperations,
    decideOperation,
    prepareOperationProposal,
    proposeOperation,
  };
}

async function proposalInput(
  draft: WorkbenchOperationProposalDraft,
  phase: WorkbenchPhase,
  repositoryKeys: ReadonlySet<string>,
): Promise<WorkbenchOperationProposalInput> {
  const sourceRunId = draft.sourceRunId.trim();
  const safeSummary = draft.safeSummary.trim();
  switch (draft.target.type) {
    case 'GIT_COMMIT': {
      requireScopedRepositories([draft.target.repositoryKey], repositoryKeys);
      const safeMessagePreview = normalizeCommitPreview(draft.target.safeMessagePreview);
      return {
        sourceRunId,
        phase,
        safeSummary,
        target: {
          type: 'GIT_COMMIT',
          repositoryKey: draft.target.repositoryKey.trim(),
          branch: draft.target.branch.trim(),
          expectedHead: draft.target.expectedHead.trim(),
          expectedStateHash: draft.target.expectedStateHash.trim(),
          includedPaths: draft.target.includedPaths.map(path => path.trim()),
          messageHash: await sha256Hex(safeMessagePreview),
          safeMessagePreview,
        },
      };
    }
    case 'GIT_PUSH':
      requireScopedRepositories([draft.target.repositoryKey], repositoryKeys);
      return {
        sourceRunId,
        phase,
        safeSummary,
        target: {
          type: 'GIT_PUSH',
          repositoryKey: draft.target.repositoryKey.trim(),
          remoteName: draft.target.remoteName.trim(),
          localBranch: draft.target.localBranch.trim(),
          remoteRef: draft.target.remoteRef.trim(),
          expectedLocalHead: draft.target.expectedLocalHead.trim(),
        },
      };
    case 'LOCAL_DEPLOY':
      requireScopedRepositories(draft.target.repositoryTargets, repositoryKeys);
      return {
        sourceRunId,
        phase,
        safeSummary,
        target: {
          type: 'LOCAL_DEPLOY',
          templateId: draft.target.templateId.trim(),
          templateVersion: draft.target.templateVersion.trim(),
          templateHash: draft.target.templateHash.trim(),
          repositoryTargets: draft.target.repositoryTargets.map(key => key.trim()),
          environment: 'LOCAL',
          expectedWorkspaceStateHash: draft.target.expectedWorkspaceStateHash.trim(),
          rollbackSummary: draft.target.rollbackSummary.trim(),
        },
      };
    case 'PRODUCTION_WRITE': {
      const environment = draft.target.environment.trim();
      if (!environment || environment.toLowerCase() === 'local') {
        throw new Error('production write environment must not be LOCAL');
      }
      return {
        sourceRunId,
        phase,
        safeSummary,
        target: {
          type: 'PRODUCTION_WRITE',
          environment,
          resourceReference: draft.target.resourceReference.trim(),
          expectedProductionStateHash: draft.target.expectedProductionStateHash.trim(),
        },
      };
    }
  }
}

function normalizeCommitPreview(value: string): string {
  return value.normalize('NFC').replace(/\r\n?/g, '\n').trim();
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
}

function requireScopedRepositories(
  requested: ReadonlyArray<string>,
  scoped: ReadonlySet<string>,
): void {
  if (!requested.length || requested.some(key => !scoped.has(key.trim()))) {
    throw new Error('repository is outside frozen scope');
  }
}

function defaultIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
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
    case 'WORKBENCH_OPERATION_SOURCE_RUN_NOT_FOUND':
      return 'Source Run 已不存在或不属于当前 Workbench，请重新加载后核对。';
    case 'IDEMPOTENCY_CONFLICT':
      return '幂等键对应的提案规范已冲突；草稿已保留，请重新提交。';
    case 'WORKBENCH_OPERATION_TRANSITION_INVALID':
      return '操作已不处于可决策状态，请刷新后核对。';
    case 'WORKBENCH_OPERATION_TARGET_CHANGED':
      return '操作目标已变化，旧授权不可继续使用。';
    case 'WORKBENCH_OPERATION_REQUEST_INVALID':
      return '操作提案、决策或理由不符合约束。';
    default:
      return '高影响操作请求失败，请稍后重试。';
  }
}
