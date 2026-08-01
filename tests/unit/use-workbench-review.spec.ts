/**
 * Review Opinion/Confirmation browser orchestration.
 *
 * @author alex
 * @since 2026-08-01
 */
// @ts-expect-error Frontend Vue ESM entry has no declaration for this relative test import.
import * as frontendVueRuntime from '../../frontend/node_modules/vue/index.mjs';
import { describe, expect, it, vi } from 'vitest';
import {
  WorkbenchReviewApiError,
  type WorkbenchReviewApiClient,
  type WorkbenchReviewConfirmation,
  type WorkbenchReviewOpinion,
} from '../../frontend/js/api/workbench-review.js';
import { useWorkbenchReview } from '../../frontend/js/composables/useWorkbenchReview.js';

const { nextTick, ref } = frontendVueRuntime as typeof import('vue');
const HASH = '47d8673e4e9c20347e1ef901382931ddec1f9a72672a8764a71020b803c200b2';

function opinion(overrides: Partial<WorkbenchReviewOpinion> = {}): WorkbenchReviewOpinion {
  return {
    phase: 'REVIEW_REFACTOR',
    version: 3,
    content: '请提取领域策略',
    contentHash: HASH,
    reviewedAt: 100,
    readOnly: false,
    ...overrides,
  };
}

function confirmation(
  overrides: Partial<WorkbenchReviewConfirmation> = {},
): WorkbenchReviewConfirmation {
  return {
    confirmationId: 'confirmation-3',
    phase: 'REVIEW_REFACTOR',
    opinionVersion: 3,
    opinionHash: HASH,
    confirmedAt: 101,
    readOnly: false,
    ...overrides,
  };
}

function api(overrides: Partial<WorkbenchReviewApiClient> = {}): WorkbenchReviewApiClient {
  return {
    getOpinion: vi.fn().mockResolvedValue(null),
    saveOpinion: vi.fn().mockResolvedValue(opinion({ version: 1 })),
    getConfirmation: vi.fn().mockResolvedValue(null),
    confirmModification: vi.fn().mockResolvedValue(confirmation()),
    ...overrides,
  };
}

describe('useWorkbenchReview', () => {
  it('hashes and saves the exact composer text, then issues an exact confirmation', async () => {
    const client = api({
      saveOpinion: vi.fn().mockResolvedValue(opinion()),
      confirmModification: vi.fn().mockResolvedValue(confirmation()),
    });
    const review = useWorkbenchReview({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('REVIEW_REFACTOR'),
      archived: ref(false),
      apiClient: client,
    });
    await vi.waitFor(() => expect(review.reviewLoading.value).toBe(false));

    review.updateReviewText('  请提取领域策略 \n');
    await review.saveReviewOpinion();
    await review.confirmReviewModification();

    expect(client.saveOpinion).toHaveBeenCalledWith('wb-1', 0, '请提取领域策略');
    expect(review.reviewText.value).toBe('请提取领域策略');
    expect(client.confirmModification).toHaveBeenCalledWith('wb-1', 3, HASH);
    expect(review.reviewModifyConfirmationId.value).toBe('confirmation-3');
  });

  it('revokes the exact confirmation immediately when composer text changes', async () => {
    const client = api({
      getOpinion: vi.fn().mockResolvedValue(opinion()),
      getConfirmation: vi.fn().mockResolvedValue(confirmation()),
    });
    const review = useWorkbenchReview({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('REVIEW_REFACTOR'),
      apiClient: client,
    });
    await vi.waitFor(() => expect(review.reviewLoading.value).toBe(false));

    expect(review.reviewText.value).toBe('请提取领域策略');
    expect(review.reviewDraftMatchesOpinion.value).toBe(true);
    expect(review.reviewModifyConfirmationId.value).toBe('confirmation-3');

    review.updateReviewText('扩大到所有模块');
    expect(review.reviewModifyConfirmationId.value).toBeNull();
    expect(review.reviewConfirmed.value).toBe(false);
  });

  it('keeps the draft but adopts only safe current proof after an If-Match conflict', async () => {
    const current = opinion({
      version: 4,
      content: '远端意见',
      contentHash: '8c13e08fd7235c2b022d4f736146cdd25efacdc087b8f51569a3b2a15a0b26cd',
    });
    const client = api({
      getOpinion: vi.fn().mockResolvedValue(opinion()),
      saveOpinion: vi.fn().mockRejectedValue(new WorkbenchReviewApiError(
        409,
        'WORKBENCH_REVIEW_VERSION_CONFLICT',
        current,
      )),
    });
    const review = useWorkbenchReview({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('REVIEW_REFACTOR'),
      apiClient: client,
    });
    await vi.waitFor(() => expect(review.reviewLoading.value).toBe(false));
    review.updateReviewText('本地不能丢的意见');

    await review.saveReviewOpinion();

    expect(review.reviewText.value).toBe('本地不能丢的意见');
    expect(review.reviewOpinion.value).toEqual({
      version: 4,
      contentHash: '8c13e08fd7235c2b022d4f736146cdd25efacdc087b8f51569a3b2a15a0b26cd',
    });
    expect(review.reviewModifyConfirmationId.value).toBeNull();
    expect(review.reviewError.value).toContain('已变化');
  });

  it('revokes confirmation intent and adopts the verified current Opinion on confirmation conflict', async () => {
    const remote = opinion({
      version: 4,
      content: '远端意见',
      contentHash: '8c13e08fd7235c2b022d4f736146cdd25efacdc087b8f51569a3b2a15a0b26cd',
    });
    const client = api({
      getOpinion: vi.fn().mockResolvedValue(opinion()),
      confirmModification: vi.fn().mockRejectedValue(new WorkbenchReviewApiError(
        409,
        'WORKBENCH_REVIEW_VERSION_CONFLICT',
        remote,
      )),
    });
    const review = useWorkbenchReview({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('REVIEW_REFACTOR'),
      apiClient: client,
    });
    await vi.waitFor(() => expect(review.reviewLoading.value).toBe(false));
    expect(review.reviewCanConfirm.value).toBe(true);

    await review.confirmReviewModification();

    expect(review.reviewOpinion.value).toEqual({
      version: 4,
      contentHash: remote.contentHash,
    });
    expect(review.reviewText.value).toBe('请提取领域策略');
    expect(review.reviewDraftMatchesOpinion.value).toBe(false);
    expect(review.reviewModifyConfirmationId.value).toBeNull();
    expect(review.reviewError.value).toContain('已变化');
  });

  it('loads archived proof for reading but never saves or confirms', async () => {
    const client = api({
      getOpinion: vi.fn().mockResolvedValue(opinion({ readOnly: true })),
      getConfirmation: vi.fn().mockResolvedValue(confirmation({ readOnly: true })),
    });
    const review = useWorkbenchReview({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('REVIEW_REFACTOR'),
      archived: ref(true),
      apiClient: client,
    });
    await vi.waitFor(() => expect(review.reviewLoading.value).toBe(false));
    review.updateReviewText('禁止写入');
    await review.saveReviewOpinion();
    await review.confirmReviewModification();

    expect(client.getOpinion).toHaveBeenCalled();
    expect(client.getConfirmation).toHaveBeenCalled();
    expect(client.saveOpinion).not.toHaveBeenCalled();
    expect(client.confirmModification).not.toHaveBeenCalled();
    expect(review.reviewReadOnly.value).toBe(true);
  });

  it('clears proof and ignores delayed responses after leaving Review or changing owner', async () => {
    let resolveOld: ((value: WorkbenchReviewOpinion | null) => void) | null = null;
    const client = api({
      getOpinion: vi.fn().mockImplementation(() => new Promise(resolve => { resolveOld = resolve; })),
    });
    const phase = ref<'REVIEW_REFACTOR' | 'IMPLEMENT_TEST'>('REVIEW_REFACTOR');
    const ownerId = ref('owner-1');
    const review = useWorkbenchReview({
      ownerId,
      workbenchId: ref('wb-1'),
      phase,
      apiClient: client,
    });

    phase.value = 'IMPLEMENT_TEST';
    ownerId.value = 'owner-2';
    await nextTick();
    const oldResolver = resolveOld as ((value: WorkbenchReviewOpinion | null) => void) | null;
    oldResolver?.(opinion());
    await nextTick();

    expect(review.reviewOpinion.value).toBeNull();
    expect(review.reviewModifyConfirmationId.value).toBeNull();
    expect(review.reviewEnabled.value).toBe(false);
  });
});
