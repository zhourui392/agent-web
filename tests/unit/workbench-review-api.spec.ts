/**
 * Workbench Review Opinion/Confirmation Owner API contract.
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it, vi } from 'vitest';
import {
  WorkbenchReviewApiError,
  createWorkbenchReviewApiClient,
  type WorkbenchReviewFetch,
} from '../../frontend/js/api/workbench-review.js';

const HASH = '47d8673e4e9c20347e1ef901382931ddec1f9a72672a8764a71020b803c200b2';
const REMOTE_HASH = '8c13e08fd7235c2b022d4f736146cdd25efacdc087b8f51569a3b2a15a0b26cd';

function response(status: number, body: unknown): Response {
  return new Response(body == null ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function opinion(overrides: Record<string, unknown> = {}) {
  return {
    phase: 'REVIEW_REFACTOR',
    version: 3,
    content: '请提取领域策略',
    contentHash: HASH,
    reviewedAt: 1_722_528_000_000,
    readOnly: false,
    ...overrides,
  };
}

function confirmation(overrides: Record<string, unknown> = {}) {
  return {
    confirmationId: 'confirmation-3',
    phase: 'REVIEW_REFACTOR',
    opinionVersion: 3,
    opinionHash: HASH,
    confirmedAt: 1_722_528_000_100,
    readOnly: false,
    ...overrides,
  };
}

describe('workbench review API client', () => {
  it('loads owner-scoped exact opinion and confirmation proofs', async () => {
    const fetcher = vi.fn<WorkbenchReviewFetch>()
      .mockResolvedValueOnce(response(200, opinion()))
      .mockResolvedValueOnce(response(200, confirmation()));
    const client = createWorkbenchReviewApiClient(fetcher);

    await expect(client.getOpinion('wb/一 二')).resolves.toEqual(opinion());
    await expect(client.getConfirmation('wb/一 二')).resolves.toEqual(confirmation());

    expect(fetcher.mock.calls[0]?.[0]).toBe(
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C/phases/REVIEW_REFACTOR/review-opinion',
    );
    expect(fetcher.mock.calls[1]?.[0]).toBe(
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C/phases/REVIEW_REFACTOR/review-confirmation',
    );
  });

  it('saves the bounded human text with exact If-Match and confirms the exact proof', async () => {
    const fetcher = vi.fn<WorkbenchReviewFetch>()
      .mockResolvedValueOnce(response(200, opinion()))
      .mockResolvedValueOnce(response(201, confirmation()));
    const client = createWorkbenchReviewApiClient(fetcher);

    await client.saveOpinion('wb-1', 2, '请提取领域策略');
    await client.confirmModification('wb-1', 3, HASH);

    expect(fetcher.mock.calls[0]?.[1]).toEqual(expect.objectContaining({
      method: 'PUT',
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'If-Match': '2',
      },
      body: JSON.stringify({ content: '请提取领域策略' }),
    }));
    expect(fetcher.mock.calls[1]?.[1]).toEqual(expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ opinionVersion: 3, opinionHash: HASH }),
    }));
  });

  it('treats only the two typed not-found responses as an empty proof', async () => {
    const fetcher = vi.fn<WorkbenchReviewFetch>()
      .mockResolvedValueOnce(response(404, { code: 'WORKBENCH_REVIEW_OPINION_NOT_FOUND' }))
      .mockResolvedValueOnce(response(404, { code: 'WORKBENCH_REVIEW_OPINION_NOT_FOUND' }))
      .mockResolvedValueOnce(response(404, { code: 'WORKBENCH_REVIEW_CONFIRMATION_NOT_FOUND' }))
      .mockResolvedValueOnce(response(404, { code: 'WORKBENCH_NOT_FOUND' }));
    const client = createWorkbenchReviewApiClient(fetcher);

    await expect(client.getOpinion('wb-1')).resolves.toBeNull();
    await expect(client.getConfirmation('wb-1')).resolves.toBeNull();
    await expect(client.getConfirmation('wb-1')).resolves.toBeNull();
    await expect(client.getOpinion('wb-1')).rejects.toMatchObject({
      status: 404,
      code: 'WORKBENCH_NOT_FOUND',
    });
  });

  it('retains only a validated current Opinion on conflict and fails closed on hostile responses', async () => {
    const fetcher = vi.fn<WorkbenchReviewFetch>()
      .mockResolvedValueOnce(response(409, {
        code: 'WORKBENCH_REVIEW_VERSION_CONFLICT',
        message: 'token=secret at /home/private',
        current: opinion({ version: 4, content: '远端意见', contentHash: REMOTE_HASH }),
      }))
      .mockResolvedValueOnce(response(200, confirmation({ opinionHash: 'NOT-A-HASH' })));
    const client = createWorkbenchReviewApiClient(fetcher);

    let conflict: unknown;
    try {
      await client.saveOpinion('wb-1', 3, HASH);
    } catch (error) {
      conflict = error;
    }
    expect(conflict).toBeInstanceOf(WorkbenchReviewApiError);
    expect(conflict).toMatchObject({
      status: 409,
      code: 'WORKBENCH_REVIEW_VERSION_CONFLICT',
      current: { version: 4, contentHash: REMOTE_HASH },
    });
    expect(JSON.stringify(conflict)).not.toMatch(/secret|home|token/i);

    await expect(client.getConfirmation('wb-1')).rejects.toMatchObject({
      code: 'WORKBENCH_REVIEW_RESPONSE_INVALID',
    });
  });
});
