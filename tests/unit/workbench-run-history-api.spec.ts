/**
 * Workbench 完成态 Run 历史、事件分页与实际能力绑定 API 契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it, vi } from 'vitest';
import {
  createWorkbenchRunApiClient,
  type WorkbenchRunFetch,
} from '../../frontend/js/api/workbench-run.js';

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: vi.fn().mockResolvedValue(JSON.stringify(body)),
  } as unknown as Response;
}

const run = {
  runId: 'run-2',
  workbenchId: 'wb-1',
  phase: 'IMPLEMENT_TEST',
  sessionId: 'session-1',
  status: 'SUCCEEDED',
  runMode: 'MODIFY_WORKSPACE',
  lastEventSeq: 7,
  earliestRetainedSeq: 1,
  createdAt: 1_786_000_000_000,
  startedAt: 1_786_000_000_010,
  finishedAt: 1_786_000_000_200,
  failureCode: null,
};

describe('Workbench Run history API', () => {
  it('lists terminal runs with an owner-scoped phase cursor and drops unknown fields', async () => {
    const { earliestRetainedSeq: _earliestRetainedSeq, ...listedRun } = run;
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, {
      items: [{ ...listedRun, command: 'rm -rf /', token: 'secret' }],
      nextCursor: { createdAt: run.createdAt, runId: run.runId, secret: 'drop-me' },
      workingDir: '/home/private/project',
    }));
    const client = createWorkbenchRunApiClient(fetchMock as WorkbenchRunFetch);

    const page = await client.listRuns('wb/一', {
      phase: 'IMPLEMENT_TEST',
      cursorCreatedAt: 1_785_000_000_000,
      cursorRunId: 'run/一',
      limit: 20,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/workbenches/wb%2F%E4%B8%80/runs?phase=IMPLEMENT_TEST&cursorCreatedAt=1785000000000&cursorRunId=run%2F%E4%B8%80&limit=20',
      { method: 'GET' },
    );
    expect(page).toEqual({
      items: [listedRun],
      nextCursor: { createdAt: run.createdAt, runId: run.runId },
    });
    expect(JSON.stringify(page)).not.toMatch(/workingDir|command|token|secret|home\/private/i);
  });

  it('loads a bounded ascending event page without trusting unknown response fields', async () => {
    const payload = JSON.stringify({
      schemaVersion: 'workbench-run-event@1',
      runId: 'run-2',
      workbenchId: 'wb-1',
      phase: 'IMPLEMENT_TEST',
      occurredAt: 1_786_000_000_100,
      data: { content: 'done' },
    });
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, {
      runId: 'run-2',
      after: 0,
      through: 2,
      lastEventSeq: 7,
      earliestRetainedSeq: 1,
      hasMore: true,
      events: [
        { sequence: 1, eventType: 'run_status', payload },
        { sequence: 2, eventType: 'agent_chunk', payload },
      ],
      rawStderr: 'never expose',
    }));
    const client = createWorkbenchRunApiClient(fetchMock as WorkbenchRunFetch);

    const page = await client.getRunEvents('wb-1', 'run-2', { after: 0, limit: 2 });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/workbenches/wb-1/runs/run-2/events-page?after=0&limit=2',
      { method: 'GET' },
    );
    expect(page).toEqual({
      runId: 'run-2',
      after: 0,
      through: 2,
      lastEventSeq: 7,
      earliestRetainedSeq: 1,
      hasMore: true,
      events: [
        { sequence: 1, eventType: 'run_status', payload },
        { sequence: 2, eventType: 'agent_chunk', payload },
      ],
    });
    expect(JSON.stringify(page)).not.toContain('rawStderr');
  });

  it('projects the frozen capability binding without executable or secret MCP fields', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, {
      runId: 'run-2',
      workbenchId: 'wb-1',
      phase: 'IMPLEMENT_TEST',
      runMode: 'MODIFY_WORKSPACE',
      createdAt: run.createdAt,
      overrideVersion: 3,
      policyVersion: 'workbench-policy@1',
      profileId: 'workbench-implement-test',
      profileVersion: '1.0.0',
      profileHash: 'a'.repeat(64),
      bindingHash: 'b'.repeat(64),
      runtimeCompatibility: 'm0-2026-07-22',
      rules: [{
        id: 'workbench/tdd-minimal-change',
        version: '1.0.0',
        source: 'PLATFORM',
        contentHash: 'c'.repeat(64),
        mandatory: true,
        safeSummary: 'TDD 最小修改',
        content: 'private rule body',
      }],
      skills: [{
        id: 'java-tdd',
        version: '1.0.0',
        source: 'PLATFORM',
        packageHash: 'd'.repeat(64),
        trustTier: 'PLATFORM',
        entryContent: 'private skill body',
      }],
      mcpServers: [{
        id: 'local-test-runner',
        version: '1.0.0',
        definitionHash: 'e'.repeat(64),
        access: 'WRITE',
        transport: 'STDIO',
        command: '/private/runner',
        env: { TOKEN: 'secret' },
      }],
      rejected: [{ id: 'optional-missing', reasonCode: 'UNAVAILABLE', details: 'private' }],
    }));
    const client = createWorkbenchRunApiClient(fetchMock as WorkbenchRunFetch);

    const capability = await client.getRunCapability('wb-1', 'run-2');

    expect(capability).toEqual({
      runId: 'run-2',
      workbenchId: 'wb-1',
      phase: 'IMPLEMENT_TEST',
      runMode: 'MODIFY_WORKSPACE',
      createdAt: run.createdAt,
      overrideVersion: 3,
      policyVersion: 'workbench-policy@1',
      profileId: 'workbench-implement-test',
      profileVersion: '1.0.0',
      profileHash: 'a'.repeat(64),
      bindingHash: 'b'.repeat(64),
      runtimeCompatibility: 'm0-2026-07-22',
      rules: [{
        id: 'workbench/tdd-minimal-change',
        version: '1.0.0',
        source: 'PLATFORM',
        contentHash: 'c'.repeat(64),
        mandatory: true,
        safeSummary: 'TDD 最小修改',
      }],
      skills: [{
        id: 'java-tdd',
        version: '1.0.0',
        source: 'PLATFORM',
        packageHash: 'd'.repeat(64),
        trustTier: 'PLATFORM',
      }],
      mcpServers: [{
        id: 'local-test-runner',
        version: '1.0.0',
        definitionHash: 'e'.repeat(64),
        access: 'WRITE',
        transport: 'STDIO',
      }],
      rejected: [{ id: 'optional-missing', reasonCode: 'UNAVAILABLE' }],
    });
    expect(JSON.stringify(capability)).not.toMatch(/private|command|env|token|secret|content\"/i);
  });

  it('rejects incomplete cursors, invalid limits, and malformed historical projections before use', async () => {
    const { earliestRetainedSeq: _earliestRetainedSeq, ...listedRun } = run;
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, {
      items: [{ ...listedRun, createdAt: -1 }],
      nextCursor: null,
    }));
    const client = createWorkbenchRunApiClient(fetchMock as WorkbenchRunFetch);

    await expect(client.listRuns('wb-1', { cursorRunId: 'run-1' }))
      .rejects.toThrow('cursor');
    await expect(client.listRuns('wb-1', { limit: 0 })).rejects.toThrow('limit');
    await expect(client.getRunEvents('wb-1', 'run-1', { after: -1, limit: 200 }))
      .rejects.toThrow('after');
    await expect(client.getRunEvents('wb-1', 'run-1', { after: 0, limit: 501 }))
      .rejects.toThrow('limit');
    await expect(client.listRuns('wb-1')).rejects.toMatchObject({
      code: 'WORKBENCH_RUN_UNEXPECTED_RESPONSE',
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
