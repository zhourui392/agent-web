/**
 * Admin Workbench 只读浏览与显式 Stop/Reconcile 编排契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it, vi } from 'vitest';
import type { AdminWorkbenchApiClient } from '../../frontend/js/admin/api/workbench.js';
import { useAdminWorkbenches } from '../../frontend/js/admin/composables/useAdminWorkbenches.js';

const hash = (value: string): string => value.repeat(64);

function api(): AdminWorkbenchApiClient {
  return {
    listWorkbenches: vi.fn().mockResolvedValue({
      items: [{
        workbenchId: 'workbench-1', ownerId: 'owner-1', ownerName: 'Owner One',
        title: 'Workbench', status: 'ACTIVE', agentType: 'CODEX', environment: 'local',
        primaryRepositoryKey: 'agent-web', repositoryCount: 1, activeWriteRunId: 'run-1',
        createdAt: 1, updatedAt: 2, version: 3,
      }],
      nextCursor: null,
    }),
    getWorkbench: vi.fn().mockResolvedValue({
      workbenchId: 'workbench-1', ownerId: 'owner-1', ownerName: 'Owner One',
      title: 'Workbench', status: 'ACTIVE', agentType: 'CODEX', environment: 'local',
      primaryRepositoryKey: 'agent-web', repositoryScopeHash: hash('a'),
      activeWriteRunId: 'run-1', createdAt: 1, updatedAt: 2, version: 3,
      repositories: [{ repositoryKey: 'agent-web', primary: true }],
      stages: [],
    }),
    listRuns: vi.fn().mockResolvedValue({
      items: [{
        runId: 'run-1', workbenchId: 'workbench-1',
        stageInstanceIdentifier: 'stage-implementation',
        status: 'RUNNING', runMode: 'MODIFY_WORKSPACE', lastEventSeq: 7,
        createdAt: 1, startedAt: 2, cancelRequestedAt: null, finishedAt: null,
        failureCode: null,
      }],
      nextCursor: null,
    }),
    getRun: vi.fn().mockResolvedValue({
      runId: 'run-1', workbenchId: 'workbench-1',
      stageInstanceIdentifier: 'stage-implementation',
      status: 'RUNNING', runMode: 'MODIFY_WORKSPACE', lastEventSeq: 7,
      createdAt: 1, startedAt: 2, cancelRequestedAt: null, finishedAt: null,
      exitCode: null, failureCode: null, repositoryScopeHash: hash('a'),
      capabilitySnapshotHash: hash('b'), promptHash: hash('c'), runtimeHandlePresent: true,
    }),
    stopRun: vi.fn().mockResolvedValue({
      workbenchId: 'workbench-1', runId: 'run-1', action: 'STOP',
      outcome: 'REQUESTED', runStatus: 'CANCEL_REQUESTED', acceptedAt: 4,
    }),
    reconcileRun: vi.fn().mockResolvedValue({
      workbenchId: 'workbench-1', runId: 'run-1', action: 'RECONCILE',
      outcome: 'INTERRUPT', runStatus: null, acceptedAt: 5,
    }),
  };
}

describe('useAdminWorkbenches', () => {
  it('loads Workbenches, exact detail and exact Runs without Owner-scoped commands', async () => {
    const client = api();
    const state = useAdminWorkbenches({ apiClient: client });

    await state.loadInitial();

    expect(client.listWorkbenches).toHaveBeenCalledWith({ limit: 20 });
    expect(client.getWorkbench).toHaveBeenCalledWith('workbench-1');
    expect(client.listRuns).toHaveBeenCalledWith('workbench-1', { limit: 20 });
    expect(state.selectedWorkbench.value?.workbenchId).toBe('workbench-1');
    expect(state.runs.value).toHaveLength(1);
  });

  it('uses an explicit selected Run for Stop/Reconcile and exposes the bounded result', async () => {
    const client = api();
    const state = useAdminWorkbenches({ apiClient: client });
    await state.loadInitial();
    await state.selectRun('run-1');

    await state.stopSelectedRun();
    expect(client.stopRun).toHaveBeenCalledWith('workbench-1', 'run-1');
    expect(state.lastAction.value?.outcome).toBe('REQUESTED');

    await state.reconcileSelectedRun();
    expect(client.reconcileRun).toHaveBeenCalledWith('workbench-1', 'run-1');
    expect(state.lastAction.value?.outcome).toBe('INTERRUPT');
  });

  it('discards stale detail results after selecting another Workbench', async () => {
    let resolveFirst: (value: unknown) => void = () => undefined;
    const client = api();
    client.listWorkbenches = vi.fn().mockResolvedValue({
      items: [
        { workbenchId: 'workbench-1', ownerId: 'owner-1', ownerName: 'Owner One', title: 'One', status: 'ACTIVE', agentType: 'CODEX', environment: 'local', primaryRepositoryKey: 'one', repositoryCount: 1, activeWriteRunId: null, createdAt: 1, updatedAt: 2, version: 1 },
        { workbenchId: 'workbench-2', ownerId: 'owner-2', ownerName: 'Owner Two', title: 'Two', status: 'ACTIVE', agentType: 'CODEX', environment: 'local', primaryRepositoryKey: 'two', repositoryCount: 1, activeWriteRunId: null, createdAt: 1, updatedAt: 2, version: 1 },
      ],
      nextCursor: null,
    });
    client.getWorkbench = vi.fn()
      .mockReturnValueOnce(new Promise(resolve => { resolveFirst = resolve; }))
      .mockResolvedValueOnce({
        workbenchId: 'workbench-2', ownerId: 'owner-2', ownerName: 'Owner Two',
        title: 'Two', status: 'ACTIVE', agentType: 'CODEX', environment: 'local',
        primaryRepositoryKey: 'two', repositoryScopeHash: hash('a'), activeWriteRunId: null,
        createdAt: 1, updatedAt: 2, version: 1, repositories: [], stages: [],
      });
    client.listRuns = vi.fn().mockResolvedValue({ items: [], nextCursor: null });
    const state = useAdminWorkbenches({ apiClient: client });

    const initial = state.loadInitial();
    await vi.waitFor(() => {
      expect(client.getWorkbench).toHaveBeenCalledWith('workbench-1');
    });
    const second = state.selectWorkbench('workbench-2');
    resolveFirst({
      workbenchId: 'workbench-1', ownerId: 'owner-1', ownerName: 'Owner One',
      title: 'One', status: 'ACTIVE', agentType: 'CODEX', environment: 'local',
      primaryRepositoryKey: 'one', repositoryScopeHash: hash('a'), activeWriteRunId: null,
      createdAt: 1, updatedAt: 2, version: 1, repositories: [], stages: [],
    });
    await Promise.all([initial, second]);

    expect(state.selectedWorkbenchId.value).toBe('workbench-2');
    expect(state.selectedWorkbench.value?.title).toBe('Two');
  });
});
