/**
 * 人工 Review Opinion、exact Confirmation 与 Composer 文本绑定编排。
 *
 * @author alex
 * @since 2026-08-01
 */
import { computed, ref, shallowRef, watch, type Ref } from 'vue';
import {
  WorkbenchReviewApiError,
  createWorkbenchReviewApiClient,
  type WorkbenchReviewApiClient,
  type WorkbenchReviewConfirmation,
} from '../api/workbench-review.js';
import {
  applyWorkbenchReviewConfirmation,
  applyWorkbenchReviewOpinion,
  beginWorkbenchReviewConfirmation,
  beginWorkbenchReviewOpinion,
  createWorkbenchReviewState,
  failWorkbenchReviewRequest,
  invalidateWorkbenchReviewConfirmation,
  reviewModifyConfirmationId,
  type WorkbenchReviewState,
} from '../lib/workbench-review-state.js';
import type { WorkbenchPhase } from '../lib/workbench-state.js';

export interface UseWorkbenchReviewOptions {
  ownerId: Ref<string>;
  workbenchId: Ref<string | null>;
  phase: Ref<WorkbenchPhase>;
  archived?: Ref<boolean>;
  apiClient?: WorkbenchReviewApiClient;
}

interface ReviewIdentity {
  ownerId: string;
  workbenchId: string;
  phase: 'REVIEW_REFACTOR';
}

export function useWorkbenchReview(options: UseWorkbenchReviewOptions) {
  const apiClient = options.apiClient ?? createWorkbenchReviewApiClient();
  const state = shallowRef<WorkbenchReviewState | null>(null);
  const reviewText = ref('');
  const reviewDraftHash = ref<string | null>(null);
  const reviewLoading = ref(false);
  const reviewSaving = ref(false);
  const reviewConfirming = ref(false);
  const reviewError = ref<string | null>(null);
  const reviewNotice = ref<string | null>(null);
  const serverReadOnly = ref(false);
  let loadedConfirmation: WorkbenchReviewConfirmation | null = null;
  let scopeGeneration = 0;
  let mutationGeneration = 0;
  let hashGeneration = 0;

  const reviewEnabled = computed(() => options.phase.value === 'REVIEW_REFACTOR');
  const reviewOpinion = computed(() => state.value?.opinion ?? null);
  const reviewConfirmation = computed(() => state.value?.confirmation ?? null);
  const reviewReadOnly = computed(() => isArchived() || serverReadOnly.value);
  const reviewDraftMatchesOpinion = computed(
    () => Boolean(
      reviewDraftHash.value &&
      reviewOpinion.value &&
      reviewDraftHash.value === reviewOpinion.value.contentHash,
    ),
  );
  const reviewModifyConfirmationId = computed(() => state.value
    ? reviewModifyConfirmationIdFromState(state.value)
    : null);
  const reviewConfirmed = computed(() => reviewModifyConfirmationId.value != null);
  const reviewCanSave = computed(() =>
    reviewEnabled.value &&
    !reviewReadOnly.value &&
    Boolean(reviewText.value.trim()) &&
    !reviewLoading.value &&
    !reviewSaving.value &&
    !reviewConfirming.value,
  );
  const reviewCanConfirm = computed(() =>
    reviewCanSave.value &&
    reviewDraftMatchesOpinion.value &&
    !reviewConfirmed.value,
  );

  function currentIdentity(): ReviewIdentity | null {
    const ownerId = options.ownerId.value?.trim();
    const workbenchId = options.workbenchId.value?.trim();
    return ownerId && workbenchId && options.phase.value === 'REVIEW_REFACTOR'
      ? { ownerId, workbenchId, phase: 'REVIEW_REFACTOR' }
      : null;
  }

  function sameIdentity(identity: ReviewIdentity): boolean {
    const current = currentIdentity();
    return Boolean(
      current &&
      current.ownerId === identity.ownerId &&
      current.workbenchId === identity.workbenchId,
    );
  }

  function resetScope(): void {
    scopeGeneration++;
    mutationGeneration++;
    hashGeneration++;
    state.value = null;
    reviewText.value = '';
    reviewDraftHash.value = null;
    reviewLoading.value = false;
    reviewSaving.value = false;
    reviewConfirming.value = false;
    reviewError.value = null;
    reviewNotice.value = null;
    serverReadOnly.value = isArchived();
    loadedConfirmation = null;
  }

  async function loadReview(): Promise<void> {
    const identity = currentIdentity();
    if (!identity) {
      resetScope();
      return;
    }
    const generation = ++scopeGeneration;
    mutationGeneration++;
    hashGeneration++;
    state.value = createWorkbenchReviewState(identity);
    reviewText.value = '';
    reviewDraftHash.value = null;
    reviewLoading.value = true;
    reviewSaving.value = false;
    reviewConfirming.value = false;
    reviewError.value = null;
    reviewNotice.value = null;
    serverReadOnly.value = isArchived();
    loadedConfirmation = null;
    try {
      const [opinion, confirmation] = await Promise.all([
        apiClient.getOpinion(identity.workbenchId),
        apiClient.getConfirmation(identity.workbenchId),
      ]);
      if (generation !== scopeGeneration || !sameIdentity(identity)) return;
      const restoredHash = opinion ? await hashReviewText(opinion.content) : null;
      if (opinion && restoredHash !== opinion.contentHash) {
        throw new WorkbenchReviewApiError(200, 'WORKBENCH_REVIEW_RESPONSE_INVALID');
      }
      if (generation !== scopeGeneration || !sameIdentity(identity)) return;
      let next = createWorkbenchReviewState(identity);
      if (opinion) {
        const started = beginWorkbenchReviewOpinion(next);
        next = applyWorkbenchReviewOpinion(started.state, started.token, opinion);
        serverReadOnly.value = serverReadOnly.value || opinion.readOnly;
        reviewText.value = opinion.content;
        reviewDraftHash.value = restoredHash;
      }
      loadedConfirmation = confirmation;
      if (confirmation) serverReadOnly.value = serverReadOnly.value || confirmation.readOnly;
      state.value = next;
      restoreExactLoadedConfirmation();
    } catch (error) {
      if (generation !== scopeGeneration || !sameIdentity(identity)) return;
      state.value = createWorkbenchReviewState(identity);
      reviewError.value = reviewErrorMessage(error);
    } finally {
      if (generation === scopeGeneration) reviewLoading.value = false;
    }
  }

  function updateReviewText(value: string): void {
    if (typeof value !== 'string' || reviewReadOnly.value || !reviewEnabled.value) return;
    reviewText.value = value;
    reviewDraftHash.value = null;
    reviewError.value = null;
    reviewNotice.value = null;
    if (state.value) state.value = invalidateWorkbenchReviewConfirmation(state.value);
    const generation = ++hashGeneration;
    const scope = scopeGeneration;
    const identity = currentIdentity();
    if (!identity || !value.trim()) return;
    void hashReviewText(normalizeReviewText(value)).then(hash => {
      if (
        generation !== hashGeneration ||
        scope !== scopeGeneration ||
        !sameIdentity(identity) ||
        reviewText.value !== value
      ) return;
      reviewDraftHash.value = hash;
      restoreExactLoadedConfirmation();
    }).catch(() => {
      if (generation === hashGeneration && scope === scopeGeneration && sameIdentity(identity)) {
        reviewError.value = '无法生成 Review Opinion 的完整性证明，请刷新页面后重试。';
      }
    });
  }

  async function saveReviewOpinion(): Promise<void> {
    const identity = currentIdentity();
    if (!identity || !state.value || !reviewCanSave.value) return;
    const text = reviewText.value;
    const generation = ++mutationGeneration;
    const scope = scopeGeneration;
    reviewSaving.value = true;
    reviewError.value = null;
    reviewNotice.value = null;
    const started = beginWorkbenchReviewOpinion(state.value);
    state.value = started.state;
    try {
      const normalizedText = normalizeReviewText(text);
      const hash = await hashReviewText(normalizedText);
      if (!isCurrentMutation(identity, scope, generation) || reviewText.value !== text) return;
      const expectedVersion = reviewOpinion.value?.version ?? 0;
      const saved = await apiClient.saveOpinion(identity.workbenchId, expectedVersion, normalizedText);
      if (!isCurrentMutation(identity, scope, generation) || reviewText.value !== text) return;
      if (saved.content !== normalizedText || saved.contentHash !== hash) {
        throw new WorkbenchReviewApiError(
          200,
          'WORKBENCH_REVIEW_RESPONSE_INVALID',
        );
      }
      state.value = applyWorkbenchReviewOpinion(state.value, started.token, saved);
      reviewText.value = normalizedText;
      reviewDraftHash.value = hash;
      loadedConfirmation = null;
      serverReadOnly.value = serverReadOnly.value || saved.readOnly;
      reviewNotice.value = `Review Opinion 已保存为 v${saved.version}；确认后才可启动重构写入。`;
    } catch (error) {
      if (!isCurrentMutation(identity, scope, generation)) return;
      if (isConflictWithCurrent(error)) {
        const currentHash = await hashReviewText(error.current.content);
        if (!isCurrentMutation(identity, scope, generation)) return;
        if (currentHash !== error.current.contentHash) {
          state.value = failWorkbenchReviewRequest(
            state.value,
            started.token,
            new WorkbenchReviewApiError(409, 'WORKBENCH_REVIEW_RESPONSE_INVALID'),
          );
          reviewError.value = 'Review 请求返回了无法验证的当前版本，请刷新后重试。';
          return;
        }
        state.value = applyWorkbenchReviewOpinion(state.value, started.token, error.current);
        loadedConfirmation = null;
        reviewError.value = 'Review Opinion 已变化，已载入当前证明；本地文字仍保留，请核对后重试。';
      } else {
        state.value = failWorkbenchReviewRequest(state.value, started.token, error);
        reviewError.value = reviewErrorMessage(error);
      }
    } finally {
      if (isCurrentMutation(identity, scope, generation)) reviewSaving.value = false;
    }
  }

  async function confirmReviewModification(): Promise<void> {
    const identity = currentIdentity();
    if (
      !identity ||
      !state.value ||
      !reviewCanConfirm.value ||
      !reviewOpinion.value ||
      !reviewDraftMatchesOpinion.value
    ) return;
    const started = beginWorkbenchReviewConfirmation(state.value);
    if (!started) return;
    const generation = ++mutationGeneration;
    const scope = scopeGeneration;
    const opinion = reviewOpinion.value;
    state.value = started.state;
    reviewConfirming.value = true;
    reviewError.value = null;
    reviewNotice.value = null;
    try {
      const confirmed = await apiClient.confirmModification(
        identity.workbenchId,
        opinion.version,
        opinion.contentHash,
      );
      if (!isCurrentMutation(identity, scope, generation)) return;
      loadedConfirmation = confirmed;
      state.value = applyWorkbenchReviewConfirmation(state.value, started.token, confirmed);
      serverReadOnly.value = serverReadOnly.value || confirmed.readOnly;
      reviewNotice.value = '已确认当前 Review Opinion；仅本次 exact 版本可用于重构写入。';
    } catch (error) {
      if (!isCurrentMutation(identity, scope, generation)) return;
      loadedConfirmation = null;
      if (isConflictWithCurrent(error)) {
        const currentHash = await hashReviewText(error.current.content);
        if (!isCurrentMutation(identity, scope, generation)) return;
        if (currentHash === error.current.contentHash) {
          const currentRequest = beginWorkbenchReviewOpinion(state.value);
          state.value = applyWorkbenchReviewOpinion(
            currentRequest.state,
            currentRequest.token,
            error.current,
          );
          serverReadOnly.value = serverReadOnly.value || error.current.readOnly;
          reviewError.value = 'Review Opinion 已变化，旧确认已撤销；请核对当前版本后重试。';
        } else {
          state.value = failWorkbenchReviewRequest(state.value, started.token, error);
          reviewError.value = 'Review 请求返回了无法验证的当前版本，请刷新后重试。';
        }
      } else {
        state.value = failWorkbenchReviewRequest(state.value, started.token, error);
        reviewError.value = reviewErrorMessage(error);
      }
    } finally {
      if (isCurrentMutation(identity, scope, generation)) reviewConfirming.value = false;
    }
  }

  function restoreExactLoadedConfirmation(): void {
    if (
      !state.value ||
      !loadedConfirmation ||
      !reviewDraftMatchesOpinion.value ||
      reviewReadOnly.value && !isArchived()
    ) return;
    const started = beginWorkbenchReviewConfirmation(state.value);
    if (!started) return;
    state.value = applyWorkbenchReviewConfirmation(
      started.state,
      started.token,
      loadedConfirmation,
    );
  }

  function isCurrentMutation(identity: ReviewIdentity, scope: number, generation: number): boolean {
    return scope === scopeGeneration && generation === mutationGeneration && sameIdentity(identity);
  }

  function isArchived(): boolean {
    return options.archived?.value ?? false;
  }

  watch(
    () => [
      options.ownerId.value,
      options.workbenchId.value ?? '',
      options.phase.value,
      isArchived() ? 'archived' : 'active',
    ].join('\u0000'),
    () => {
      if (currentIdentity()) void loadReview();
      else resetScope();
    },
    { immediate: true, flush: 'sync' },
  );

  return {
    reviewEnabled,
    reviewText,
    reviewDraftHash,
    reviewOpinion,
    reviewConfirmation,
    reviewLoading,
    reviewSaving,
    reviewConfirming,
    reviewError,
    reviewNotice,
    reviewReadOnly,
    reviewDraftMatchesOpinion,
    reviewModifyConfirmationId,
    reviewConfirmed,
    reviewCanSave,
    reviewCanConfirm,
    loadReview,
    updateReviewText,
    saveReviewOpinion,
    confirmReviewModification,
  };
}

export async function hashReviewText(text: string): Promise<string> {
  if (typeof text !== 'string' || !text.trim()) throw new Error('Review opinion text is required');
  const cryptoApi = globalThis.crypto;
  if (!cryptoApi?.subtle) throw new Error('Web Crypto is unavailable');
  const digest = await cryptoApi.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), value => value.toString(16).padStart(2, '0')).join('');
}

function normalizeReviewText(text: string): string {
  return text.trim();
}

function reviewModifyConfirmationIdFromState(state: WorkbenchReviewState): string | null {
  return reviewModifyConfirmationId(state, 'REVIEW_REFACTOR', 'MODIFY_WORKSPACE');
}

function isConflictWithCurrent(error: unknown): error is WorkbenchReviewApiError & {
  current: NonNullable<WorkbenchReviewApiError['current']>;
} {
  return error instanceof WorkbenchReviewApiError &&
    error.status === 409 &&
    error.code === 'WORKBENCH_REVIEW_VERSION_CONFLICT' &&
    error.current != null;
}

function reviewErrorMessage(error: unknown): string {
  if (!(error instanceof WorkbenchReviewApiError)) {
    return 'Review 请求失败，请稍后重试。';
  }
  switch (error.code) {
    case 'WORKBENCH_ARCHIVED':
      return 'Workbench 已归档，Review 证明仅可查看。';
    case 'WORKBENCH_REVIEW_VERSION_CONFLICT':
      return 'Review Opinion 已变化，请重新核对后确认。';
    case 'WORKBENCH_REVIEW_REQUEST_INVALID':
      return 'Review Opinion 或确认参数不符合约束。';
    case 'WORKBENCH_NOT_FOUND':
      return 'Workbench 不存在或无权访问。';
    default:
      return 'Review 请求失败，请稍后重试。';
  }
}
