/**
 * Admin Workbench 独立安全投影与运维动作 API 契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it, vi } from 'vitest';
import {
  createAdminWorkbenchApiClient,
  type AdminWorkbenchFetch,
} from '../../frontend/js/admin/api/workbench.js';

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: vi.fn().mockResolvedValue(JSON.stringify(body)),
  } as unknown as Response;
}

const hash = (value: string): string => value.repeat(64);

function detail(): Record<string, unknown> {
  return {
    workbenchId: 'workbench-1',
    ownerId: 'owner-1',
    ownerName: 'Owner One',
    title: 'Safe Workbench',
    status: 'ACTIVE',
    agentType: 'CODEX',
    environment: 'local',
    primaryRepositoryKey: 'agent-web',
    repositoryScopeHash: hash('a'),
    activeWriteRunId: 'run-1',
    createdAt: 10,
    updatedAt: 20,
    version: 3,
    repositories: [{
      repositoryKey: 'agent-web',
      relativePath: 'agent-web',
      primary: true,
      repositoryRoot: '/home/private/agent-web',
    }],
    phases: [
      { phase: 'REQUIREMENT_ANALYSIS', phaseOrder: 0, status: 'HUMAN_COMPLETED', activeRunId: null, activeRunMode: null, lastActivityAt: 11, completedAt: 12 },
      { phase: 'SOLUTION_DESIGN', phaseOrder: 1, status: 'HUMAN_COMPLETED', activeRunId: null, activeRunMode: null, lastActivityAt: 13, completedAt: 14 },
      { phase: 'IMPLEMENT_TEST', phaseOrder: 2, status: 'IN_PROGRESS', activeRunId: 'run-1', activeRunMode: 'MODIFY_WORKSPACE', lastActivityAt: 15, completedAt: null },
      { phase: 'REVIEW_REFACTOR', phaseOrder: 3, status: 'NOT_STARTED', activeRunId: null, activeRunMode: null, lastActivityAt: null, completedAt: null },
    ],
    workspaceRoot: '/home/private',
    originalGoal: 'private requirement body',
    rootFingerprint: hash('f'),
  };
}

function runDetail(): Record<string, unknown> {
  return {
    runId: 'run-1',
    workbenchId: 'workbench-1',
    phase: 'IMPLEMENT_TEST',
    status: 'RUNNING',
    runMode: 'MODIFY_WORKSPACE',
    lastEventSeq: 7,
    createdAt: 10,
    startedAt: 11,
    cancelRequestedAt: null,
    finishedAt: null,
    exitCode: null,
    failureCode: null,
    repositoryScopeHash: hash('a'),
    capabilitySnapshotHash: hash('b'),
    promptHash: hash('c'),
    runtimeHandlePresent: true,
    sessionId: 'private-session',
    prompt: 'private prompt',
    errorMessage: '/home/private stderr',
    toolOutput: 'secret tool output',
  };
}

describe('Admin Workbench API', () => {
  it('lists and loads only the independent safe Workbench projection', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, {
        items: [{
          ...detail(),
          repositoryCount: 1,
          repositories: undefined,
          phases: undefined,
        }],
        nextCursor: { updatedAt: 20, workbenchId: 'workbench-1', secret: 'drop' },
      }))
      .mockResolvedValueOnce(jsonResponse(200, detail()));
    const client = createAdminWorkbenchApiClient(fetchMock as AdminWorkbenchFetch);

    const page = await client.listWorkbenches({
      status: 'ACTIVE',
      cursorUpdatedAt: 30,
      cursorWorkbenchId: 'workbench/一',
      limit: 20,
    });
    const loaded = await client.getWorkbench('workbench-1');

    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/admin/workbenches?status=ACTIVE&cursorUpdatedAt=30&cursorWorkbenchId=workbench%2F%E4%B8%80&limit=20',
    );
    expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/workbenches/workbench-1');
    expect(loaded.repositories).toEqual([{ repositoryKey: 'agent-web', primary: true }]);
    expect(loaded.phases).toHaveLength(4);
    expect(JSON.stringify({ page, loaded })).not.toMatch(
      /workspaceRoot|repositoryRoot|rootFingerprint|originalGoal|private requirement|home\/private|secret/i,
    );
  });

  it('loads only exact safe Run fields and drops session, prompt, raw output and stderr', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, {
        items: [{ ...runDetail(), workbenchId: 'workbench/一' }],
        nextCursor: null,
      }))
      .mockResolvedValueOnce(jsonResponse(200, runDetail()));
    const client = createAdminWorkbenchApiClient(fetchMock as AdminWorkbenchFetch);

    const page = await client.listRuns('workbench/一', { status: 'RUNNING', limit: 20 });
    const run = await client.getRun('workbench-1', 'run-1');

    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/admin/workbenches/workbench%2F%E4%B8%80/runs?status=RUNNING&limit=20',
    );
    expect(run.runtimeHandlePresent).toBe(true);
    expect(JSON.stringify({ page, run })).not.toMatch(
      /sessionId|prompt"|errorMessage|toolOutput|stderr|private-session|home\/private|secret/i,
    );
  });

  it('posts an empty body for Stop and Reconcile without any Owner identity', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(202, {
        workbenchId: 'workbench-1', runId: 'run-1', action: 'STOP',
        outcome: 'REQUESTED', runStatus: 'CANCEL_REQUESTED', acceptedAt: 30,
      }))
      .mockResolvedValueOnce(jsonResponse(200, {
        workbenchId: 'workbench-1', runId: 'run-1', action: 'RECONCILE',
        outcome: 'INTERRUPT', runStatus: null, acceptedAt: 31,
      }));
    const client = createAdminWorkbenchApiClient(fetchMock as AdminWorkbenchFetch);

    await client.stopRun('workbench-1', 'run-1');
    await client.reconcileRun('workbench-1', 'run-1');

    for (const call of fetchMock.mock.calls) {
      expect(call[1]).toEqual({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      });
      expect(String(call[1]?.body)).not.toMatch(/owner|actor|administrator/i);
    }
  });

  it('rejects a mismatched or unsafe projection instead of displaying it', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, { ...runDetail(), workbenchId: 'other' }))
      .mockResolvedValueOnce(jsonResponse(200, {
        ...detail(),
        repositories: [{ repositoryKey: '/home/private', relativePath: '/home/private', primary: true }],
        primaryRepositoryKey: '/home/private',
      }));
    const client = createAdminWorkbenchApiClient(fetchMock as AdminWorkbenchFetch);

    await expect(client.getRun('workbench-1', 'run-1'))
      .rejects.toThrow('Admin Workbench response is invalid');
    await expect(client.getWorkbench('workbench-1'))
      .rejects.toThrow('Admin Workbench response is invalid');
  });
});
