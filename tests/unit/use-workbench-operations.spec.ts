/**
 * High-impact Operation list and decision orchestration.
 *
 * @author alex
 * @since 2026-08-01
 */
// @ts-expect-error Frontend Vue ESM entry has no declaration for this relative test import.
import * as frontendVueRuntime from '../../frontend/node_modules/vue/index.mjs';
import { describe, expect, it, vi } from 'vitest';
import {
  WorkbenchOperationApiError,
  type WorkbenchHighImpactOperation,
  type WorkbenchOperationApiClient,
} from '../../frontend/js/api/workbench-operation.js';
import { useWorkbenchOperations } from '../../frontend/js/composables/useWorkbenchOperations.js';

const { ref } = frontendVueRuntime as typeof import('vue');

function operation(overrides: Partial<WorkbenchHighImpactOperation> = {}): WorkbenchHighImpactOperation {
  return {
    operationId: 'operation-1',
    sourceRunId: 'run-1',
    phase: 'IMPLEMENT_TEST',
    type: 'GIT_PUSH',
    target: {
      type: 'GIT_PUSH',
      repositoryKeys: ['agent-web'],
      details: {
        remoteName: 'origin',
        localBranch: 'master',
        remoteRef: 'refs/heads/master',
        expectedLocalHead: 'a'.repeat(40),
        forceAllowed: false,
      },
    },
    requestedPayloadHash: 'b'.repeat(64),
    safeSummary: 'Push master to origin',
    status: 'PROPOSED',
    proposedAt: 100,
    decisionReason: null,
    decidedAt: null,
    authorizationExpiresAt: null,
    preflightHash: null,
    executionReference: null,
    failureCode: null,
    updatedAt: 100,
    version: 0,
    executionAvailable: false,
    executionMode: 'MANUAL_OR_DEFERRED',
    ...overrides,
  };
}

function api(overrides: Partial<WorkbenchOperationApiClient> = {}): WorkbenchOperationApiClient {
  return {
    list: vi.fn().mockResolvedValue([operation()]),
    get: vi.fn().mockResolvedValue(operation()),
    decide: vi.fn().mockResolvedValue(operation({ status: 'AUTHORIZED', version: 1 })),
    ...overrides,
  };
}

describe('useWorkbenchOperations', () => {
  it('filters cards by phase and records approval as authorization without claiming execution', async () => {
    const client = api();
    const operations = useWorkbenchOperations({
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      apiClient: client,
    });
    await vi.waitFor(() => expect(operations.operationLoading.value).toBe(false));

    await operations.decideOperation(operation(), 'APPROVE', '已核对仓库、分支和 HEAD');

    expect(client.decide).toHaveBeenCalledWith('wb-1', 'operation-1', 0, {
      decision: 'APPROVE',
      reason: '已核对仓库、分支和 HEAD',
    });
    expect(operations.phaseOperations.value[0]).toMatchObject({
      status: 'AUTHORIZED',
      executionAvailable: false,
      executionMode: 'MANUAL_OR_DEFERRED',
    });
    expect(operations.operationNotice.value).toContain('不会自动执行');
  });

  it('adopts a safe current projection on version conflict and never retries silently', async () => {
    const current = operation({ version: 2, updatedAt: 200 });
    const decide = vi.fn().mockRejectedValue(new WorkbenchOperationApiError(
      409,
      'WORKBENCH_OPERATION_VERSION_CONFLICT',
      current,
    ));
    const client = api({ decide });
    const operations = useWorkbenchOperations({
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      apiClient: client,
    });
    await vi.waitFor(() => expect(operations.operationLoading.value).toBe(false));

    await operations.decideOperation(operation(), 'REJECT', '目标已变化');

    expect(decide).toHaveBeenCalledTimes(1);
    expect(operations.phaseOperations.value[0]?.version).toBe(2);
    expect(operations.operationError.value).toContain('已变化');
  });

  it('allows archived operation history to load but disables every decision', async () => {
    const client = api();
    const operations = useWorkbenchOperations({
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      archived: ref(true),
      apiClient: client,
    });
    await vi.waitFor(() => expect(operations.operationLoading.value).toBe(false));

    await operations.decideOperation(operation(), 'APPROVE', '不应写入');

    expect(client.list).toHaveBeenCalledWith('wb-1');
    expect(client.decide).not.toHaveBeenCalled();
    expect(operations.operationReadOnly.value).toBe(true);
  });
});
