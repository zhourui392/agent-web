/**
 * Workbench 完成态 Run 历史恢复与能力追溯编排。
 *
 * @author alex
 * @since 2026-08-01
 */
// @ts-expect-error Frontend Vue ESM entry has no declaration for this relative test import.
import * as frontendVueRuntime from '../../frontend/node_modules/vue/index.mjs';
import { describe, expect, it, vi } from 'vitest';
import type { WorkbenchRunApiClient } from '../../frontend/js/api/workbench-run.js';
import { useWorkbenchRunHistory } from '../../frontend/js/composables/useWorkbenchRunHistory.js';

const { ref } = frontendVueRuntime as typeof import('vue');

function envelope(runId: string, type: string, data: Record<string, unknown>): string {
  return JSON.stringify({
    schemaVersion: 'workbench-run-event@1',
    runId,
    workbenchId: 'wb-1',
    stageInstanceIdentifier: 'stage-delivery',
    occurredAt: 1_786_000_000_000,
    data: { ...data, projectedType: type },
  });
}

function api(): WorkbenchRunApiClient {
  return {
    getStageConversationMessages: vi.fn(),
    ensureStageConversation: vi.fn(),
    restartStageConversation: vi.fn(),
    submitRun: vi.fn(),
    getRun: vi.fn().mockResolvedValue({
      runId: 'run-history',
      workbenchId: 'wb-1',
      stageInstanceIdentifier: 'stage-delivery',
      sessionId: 'session-1',
      status: 'SUCCEEDED',
      runMode: 'MODIFY_WORKSPACE',
      lastEventSeq: 3,
      earliestRetainedSeq: 1,
      createdAt: 1_786_000_000_000,
      startedAt: 1_786_000_000_010,
      finishedAt: 1_786_000_000_200,
      failureCode: null,
    }),
    stopRun: vi.fn(),
    eventsUrl: vi.fn(),
    listRuns: vi.fn().mockResolvedValue({
      items: [{
        runId: 'run-history',
        workbenchId: 'wb-1',
        stageInstanceIdentifier: 'stage-delivery',
        sessionId: 'session-1',
        status: 'SUCCEEDED',
        runMode: 'MODIFY_WORKSPACE',
        lastEventSeq: 3,
        createdAt: 1_786_000_000_000,
        startedAt: 1_786_000_000_010,
        finishedAt: 1_786_000_000_200,
        failureCode: null,
      }],
      nextCursor: null,
    }),
    getRunEvents: vi.fn()
      .mockResolvedValueOnce({
        runId: 'run-history',
        after: 0,
        through: 2,
        lastEventSeq: 3,
        earliestRetainedSeq: 1,
        hasMore: true,
        events: [
          { sequence: 1, eventType: 'run_status', payload: envelope('run-history', 'run_status', { status: 'RUNNING' }) },
          { sequence: 2, eventType: 'agent_chunk', payload: envelope('run-history', 'agent_chunk', { content: '历史输出' }) },
        ],
      })
      .mockResolvedValueOnce({
        runId: 'run-history',
        after: 2,
        through: 3,
        lastEventSeq: 3,
        earliestRetainedSeq: 1,
        hasMore: false,
        events: [{
          sequence: 3,
          eventType: 'terminal',
          payload: envelope('run-history', 'terminal', {
            status: 'SUCCEEDED',
            failureCode: null,
            publicMessage: '完成',
          }),
        }],
      }),
    getRunCapability: vi.fn().mockResolvedValue({
      runId: 'run-history',
      workbenchId: 'wb-1',
      stageInstanceIdentifier: 'stage-delivery',
      runMode: 'MODIFY_WORKSPACE',
      createdAt: 1_786_000_000_000,
      policyVersion: 'workbench-policy@1',
      profileId: 'implement',
      profileVersion: '1.0.0',
      profileHash: 'a'.repeat(64),
      bindingHash: 'b'.repeat(64),
      runtimeCompatibility: 'm0',
      rules: [],
      skills: [],
      mcpServers: [],
      rejected: [],
    }),
  };
}

describe('useWorkbenchRunHistory', () => {
  it('lists terminal runs, restores paged events in sequence, and loads the exact frozen binding', async () => {
    const client = api();
    const history = useWorkbenchRunHistory({
      workbenchId: ref('wb-1'),
      stageInstanceIdentifier: ref('stage-delivery'),
      apiClient: client,
    });

    await history.open();

    expect(client.listRuns).toHaveBeenCalledWith('wb-1', {
      stageInstanceIdentifier: 'stage-delivery',
      limit: 20,
    });
    expect(history.runs.value).toHaveLength(1);
    expect(history.selectedRunId.value).toBe('run-history');
    expect(history.runState.value?.blocks).toEqual([
      expect.objectContaining({ kind: 'agent_chunk', content: '历史输出' }),
    ]);
    expect(history.runState.value?.terminal).toBeNull();
    expect(history.hasMoreEvents.value).toBe(true);
    expect(history.capability.value?.bindingHash).toBe('b'.repeat(64));

    await history.loadMoreEvents();

    expect(client.getRunEvents).toHaveBeenLastCalledWith('wb-1', 'run-history', {
      after: 2,
      limit: 200,
    });
    expect(history.runState.value?.terminal).toEqual(expect.objectContaining({
      status: 'SUCCEEDED',
      publicMessage: '完成',
    }));
    expect(history.hasMoreEvents.value).toBe(false);
  });

  it('discards stale async results when the Workbench or Stage changes', async () => {
    let resolveList: (value: unknown) => void = () => undefined;
    const client = api();
    client.listRuns = vi.fn().mockReturnValue(new Promise(resolve => { resolveList = resolve; }));
    const workbenchId = ref<string | null>('wb-1');
    const stageInstanceIdentifier = ref<'stage-delivery' | 'stage-design'>('stage-delivery');
    const history = useWorkbenchRunHistory({ workbenchId, stageInstanceIdentifier, apiClient: client });

    const opening = history.open();
    stageInstanceIdentifier.value = 'stage-design';
    resolveList({ items: [], nextCursor: null });
    await opening;

    expect(history.visible.value).toBe(false);
    expect(history.runs.value).toEqual([]);
    expect(history.selectedRunId.value).toBeNull();
  });
});
