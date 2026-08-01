/**
 * TD-08 Workbench 高影响操作 Owner API 契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it, vi } from 'vitest';
import {
  WorkbenchOperationApiError,
  createWorkbenchOperationApiClient,
  type WorkbenchOperationFetch,
} from '../../frontend/js/api/workbench-operation.js';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(body == null ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function authorizedOperation(): Record<string, unknown> {
  return {
    operationId: 'operation-1',
    sourceRunId: 'run-1',
    phase: 'IMPLEMENT_TEST',
    type: 'GIT_COMMIT',
    target: {
      type: 'GIT_COMMIT',
      repositoryKeys: ['agent-web'],
      details: {
        branch: 'master',
        expectedHead: 'a'.repeat(40),
        expectedStateHash: 'b'.repeat(64),
        includedPaths: ['README.md', 'src/main/App.java'],
        messageHash: 'c'.repeat(64),
        safeMessagePreview: 'feat: workbench',
      },
    },
    requestedPayloadHash: 'd'.repeat(64),
    safeSummary: 'Commit selected files',
    status: 'AUTHORIZED',
    proposedAt: 1_722_528_000_000,
    decisionReason: '已核对目标',
    decidedAt: 1_722_528_010_000,
    authorizationExpiresAt: 1_722_528_910_000,
    preflightHash: null,
    executionReference: null,
    failureCode: null,
    updatedAt: 1_722_528_010_000,
    version: 3,
    executionAvailable: false,
    executionMode: 'MANUAL_OR_DEFERRED',
  };
}

describe('workbench high-impact operation API client', () => {
  it('lists and loads owner-scoped typed operation projections', async () => {
    const fetcher = vi.fn<WorkbenchOperationFetch>()
      .mockResolvedValueOnce(jsonResponse(200, [authorizedOperation()]))
      .mockResolvedValueOnce(jsonResponse(200, authorizedOperation()));
    const client = createWorkbenchOperationApiClient(fetcher);

    await expect(client.list('wb/一 二')).resolves.toEqual([
      expect.objectContaining({
        operationId: 'operation-1',
        status: 'AUTHORIZED',
        executionAvailable: false,
      }),
    ]);
    await expect(client.get('wb/一 二', 'operation-1')).resolves.toMatchObject({
      target: {
        type: 'GIT_COMMIT',
        repositoryKeys: ['agent-web'],
        details: expect.objectContaining({ branch: 'master' }),
      },
    });

    expect(fetcher.mock.calls[0]).toEqual([
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C/operations',
      expect.objectContaining({
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      }),
    ]);
    expect(fetcher.mock.calls[1]?.[0]).toBe(
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C/operations/operation-1',
    );
  });

  it('sends an exact If-Match decision without actor or execution fields', async () => {
    const fetcher = vi.fn<WorkbenchOperationFetch>()
      .mockResolvedValue(jsonResponse(200, authorizedOperation()));
    const client = createWorkbenchOperationApiClient(fetcher);

    await expect(client.decide('wb-1', 'operation-1', 0, {
      decision: 'APPROVE',
      reason: '已核对仓库、分支和状态',
    })).resolves.toMatchObject({
      status: 'AUTHORIZED',
      executionAvailable: false,
      executionMode: 'MANUAL_OR_DEFERRED',
    });

    const init = fetcher.mock.calls[0]?.[1] as RequestInit;
    expect(init).toEqual(expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'If-Match': '0',
      },
      body: JSON.stringify({
        decision: 'APPROVE',
        reason: '已核对仓库、分支和状态',
      }),
    }));
    expect(init.body).not.toContain('actor');
    expect(init.body).not.toContain('executionAvailable');
  });

  it('rejects malformed target paths, hashes, versions, and execution claims', async () => {
    const malformedPath = authorizedOperation();
    malformedPath.target = {
      ...(malformedPath.target as Record<string, unknown>),
      details: {
        ...((malformedPath.target as Record<string, unknown>).details as Record<string, unknown>),
        includedPaths: ['/home/user/.codex/auth.json'],
      },
    };
    const executable = { ...authorizedOperation(), executionAvailable: true };
    const fetcher = vi.fn<WorkbenchOperationFetch>()
      .mockResolvedValueOnce(jsonResponse(200, malformedPath))
      .mockResolvedValueOnce(jsonResponse(200, executable));
    const client = createWorkbenchOperationApiClient(fetcher);

    await expect(client.get('wb-1', 'operation-1')).rejects.toMatchObject({
      code: 'WORKBENCH_OPERATION_RESPONSE_INVALID',
    });
    await expect(client.get('wb-1', 'operation-1')).rejects.toMatchObject({
      code: 'WORKBENCH_OPERATION_RESPONSE_INVALID',
    });
    await expect(client.decide('wb-1', 'operation-1', -1, {
      decision: 'APPROVE',
      reason: 'x',
    })).rejects.toThrow();
  });

  it('preserves only a safe current projection on version conflict', async () => {
    const fetcher = vi.fn<WorkbenchOperationFetch>()
      .mockResolvedValue(jsonResponse(409, {
        code: 'WORKBENCH_OPERATION_VERSION_CONFLICT',
        message: 'secret backend detail',
        path: '/home/user/project',
        current: authorizedOperation(),
      }));
    const client = createWorkbenchOperationApiClient(fetcher);

    let thrown: unknown;
    try {
      await client.decide('wb-1', 'operation-1', 2, {
        decision: 'REJECT',
        reason: '目标变化',
      });
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(WorkbenchOperationApiError);
    expect(thrown).toMatchObject({
      status: 409,
      code: 'WORKBENCH_OPERATION_VERSION_CONFLICT',
      current: expect.objectContaining({ version: 3 }),
    });
    expect(JSON.stringify(thrown)).not.toContain('secret backend detail');
    expect(JSON.stringify(thrown)).not.toContain('/home/user/project');
  });

  it('fails closed on unknown error codes and malformed lists', async () => {
    const failed = vi.fn<WorkbenchOperationFetch>()
      .mockResolvedValueOnce(jsonResponse(422, {
        code: 'LEAK_/home/user/token',
        message: 'raw secret',
      }))
      .mockResolvedValueOnce(jsonResponse(200, [authorizedOperation(), { bad: true }]));
    const client = createWorkbenchOperationApiClient(failed);

    await expect(client.list('wb-1')).rejects.toMatchObject({
      status: 422,
      code: 'WORKBENCH_OPERATION_REQUEST_INVALID',
      message: 'Workbench high-impact operation request failed',
    });
    await expect(client.list('wb-1')).rejects.toMatchObject({
      code: 'WORKBENCH_OPERATION_RESPONSE_INVALID',
    });
  });
});
