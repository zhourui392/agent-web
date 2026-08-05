/**
 * Workbench Stage Run Owner API 契约。
 *
 * @author alex
 * @since 2026-08-05
 */
import { describe, expect, it, vi } from 'vitest';
import {
  createWorkbenchRunApiClient,
  normalizeWorkbenchRunAttachments,
  type WorkbenchRunFetch,
} from '../../frontend/js/api/workbench-run.js';

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: vi.fn().mockResolvedValue(body == null ? '' : JSON.stringify(body)),
  } as unknown as Response;
}

function submission(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    runId: 'run-1',
    sessionId: 'stage-session-1',
    status: 'PENDING',
    stageStatus: 'IN_PROGRESS',
    workbenchVersion: 8,
    capabilitySnapshotHash: 'a'.repeat(64),
    repositoryScopeHash: 'b'.repeat(64),
    replayed: false,
    ...overrides,
  };
}

function runDetail(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    runId: 'run-1',
    workbenchId: 'workbench-1',
    stageInstanceIdentifier: 'stage-implementation',
    sessionId: 'stage-session-1',
    status: 'RUNNING',
    runMode: 'MODIFY_WORKSPACE',
    lastEventSeq: 3,
    earliestRetainedSeq: 1,
    createdAt: 10,
    startedAt: 11,
    finishedAt: null,
    failureCode: null,
    ...overrides,
  };
}

describe('Workbench Stage Run API', () => {
  it('loads the current Stage conversation with an optional bounded cursor', async () => {
    const fetchMock = vi.fn<WorkbenchRunFetch>().mockResolvedValue(jsonResponse(200, {
      sessionId: 'stage-session-1',
      generation: 2,
      workbenchVersion: 7,
      messages: [{
        messageId: 4,
        role: 'assistant',
        content: 'Stage output',
        timestamp: '2026-08-05T00:00:00Z',
        runId: 'run-1',
        privateOutput: 'drop',
      }],
      nextCursor: 4,
    }));
    const client = createWorkbenchRunApiClient(fetchMock);

    const messages = await client.getStageConversationMessages(
      'workbench/一', 'stage-implementation', 9,
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/workbenches/workbench%2F%E4%B8%80/stages/stage-implementation/conversation/messages?beforeMessageId=9',
      { method: 'GET' },
    );
    expect(messages.messages[0]).not.toHaveProperty('privateOutput');
  });

  it('ensures and restarts only the exact Stage conversation with version and idempotency headers', async () => {
    const fetchMock = vi.fn<WorkbenchRunFetch>()
      .mockResolvedValueOnce(jsonResponse(200, {
        sessionId: 'stage-session-1', generation: 0, workbenchVersion: 7, created: true,
      }))
      .mockResolvedValueOnce(jsonResponse(200, {
        sessionId: 'stage-session-2', previousSessionId: 'stage-session-1',
        generation: 1, workbenchVersion: 8, replayed: false,
      }));
    const client = createWorkbenchRunApiClient(fetchMock);

    await client.ensureStageConversation('workbench-1', 'stage-analysis', 6);
    await client.restartStageConversation(
      'workbench-1', 'stage-analysis', 7, 'restart-key-1',
    );

    expect(fetchMock.mock.calls[0]).toEqual([
      '/api/workbenches/workbench-1/stages/stage-analysis/conversation',
      { method: 'POST', headers: { 'If-Match': '6' } },
    ]);
    expect(fetchMock.mock.calls[1]).toEqual([
      '/api/workbenches/workbench-1/stages/stage-analysis/conversation/restart',
      {
        method: 'POST',
        headers: { 'If-Match': '7', 'Idempotency-Key': 'restart-key-1' },
      },
    ]);
  });

  it('submits only to the Stage Run endpoint with the selected frozen mode', async () => {
    const fetchMock = vi.fn<WorkbenchRunFetch>()
      .mockResolvedValue(jsonResponse(202, submission()));
    const client = createWorkbenchRunApiClient(fetchMock);

    const result = await client.submitRun({
      workbenchId: 'workbench/一',
      stageInstanceIdentifier: 'stage-implementation',
      expectedVersion: 7,
      idempotencyKey: 'submission-key-1',
      request: {
        message: 'implement safely',
        runMode: 'MODIFY_WORKSPACE',
        attachments: [{
          repositoryKey: 'agent-web',
          relativePath: 'src/Main.java',
          contentHash: 'c'.repeat(64),
        }],
      },
    });

    expect(result.stageStatus).toBe('IN_PROGRESS');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/workbenches/workbench%2F%E4%B8%80/stages/stage-implementation/runs',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'If-Match': '7',
          'Idempotency-Key': 'submission-key-1',
        },
        body: JSON.stringify({
          message: 'implement safely',
          runMode: 'MODIFY_WORKSPACE',
          attachments: [{
            repositoryKey: 'agent-web',
            relativePath: 'src/Main.java',
            contentHash: 'c'.repeat(64),
          }],
        }),
      },
    );
  });

  it('fails before fetch for an invalid Stage identity, version or idempotency key', async () => {
    const fetchMock = vi.fn<WorkbenchRunFetch>();
    const client = createWorkbenchRunApiClient(fetchMock);
    const command = {
      workbenchId: 'workbench-1',
      stageInstanceIdentifier: 'stage-analysis',
      expectedVersion: 7,
      idempotencyKey: 'key-1',
      request: { message: 'question', runMode: 'DISCUSS_READ_ONLY' as const },
    };

    await expect(client.submitRun({ ...command, stageInstanceIdentifier: '../stage' }))
      .rejects.toThrow(/stageInstanceIdentifier/);
    await expect(client.submitRun({ ...command, expectedVersion: -1 }))
      .rejects.toThrow(/If-Match/);
    await expect(client.submitRun({ ...command, idempotencyKey: ' ' }))
      .rejects.toThrow(/Idempotency-Key/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('requires exact Stage identity in Run detail and drops unknown private fields', async () => {
    const fetchMock = vi.fn<WorkbenchRunFetch>()
      .mockResolvedValueOnce(jsonResponse(200, runDetail({ privatePrompt: 'drop' })))
      .mockResolvedValueOnce(jsonResponse(200, runDetail({
        stageInstanceIdentifier: '../wrong-stage',
      })));
    const client = createWorkbenchRunApiClient(fetchMock);

    const detail = await client.getRun('workbench-1', 'run-1');
    expect(detail.stageInstanceIdentifier).toBe('stage-implementation');
    expect(detail).not.toHaveProperty('privatePrompt');
    await expect(client.getRun('workbench-1', 'run-1'))
      .rejects.toMatchObject({ code: 'WORKBENCH_RUN_UNEXPECTED_RESPONSE' });
  });

  it('maps server failures to fixed safe messages without exposing response text', async () => {
    const fetchMock = vi.fn<WorkbenchRunFetch>().mockResolvedValue(jsonResponse(404, {
      code: 'WORKBENCH_RUN_NOT_FOUND',
      message: '/private/path and raw stderr',
    }));
    const client = createWorkbenchRunApiClient(fetchMock);

    const error = await client.getRun('workbench-1', 'run-1').catch(value => value);
    expect(error).toMatchObject({
      status: 404,
      code: 'WORKBENCH_RUN_NOT_FOUND',
      message: 'Workbench Run was not found',
    });
    expect(String(error)).not.toMatch(/private|stderr/i);
  });

  it('normalizes both attachment kinds and rejects paths, duplicates and unknown fields', () => {
    expect(normalizeWorkbenchRunAttachments([
      {
        repositoryKey: 'agent-web', relativePath: 'docs/design.md',
        contentHash: 'a'.repeat(64),
      },
      {
        type: 'UPLOADED_CONVERSATION', attachmentId: 'upload-1',
        contentHash: 'b'.repeat(64),
      },
    ])).toHaveLength(2);
    expect(() => normalizeWorkbenchRunAttachments([{
      repositoryKey: 'agent-web', relativePath: '../secret',
      contentHash: 'a'.repeat(64),
    }])).toThrow(/relative path/);
    expect(() => normalizeWorkbenchRunAttachments([
      {
        type: 'UPLOADED_CONVERSATION', attachmentId: 'upload-1',
        contentHash: 'b'.repeat(64),
      },
      {
        type: 'UPLOADED_CONVERSATION', attachmentId: 'upload-1',
        contentHash: 'b'.repeat(64),
      },
    ])).toThrow(/unique/);
  });

  it('builds owner-scoped event URLs with encoded logical identifiers', () => {
    const client = createWorkbenchRunApiClient(vi.fn<WorkbenchRunFetch>());
    expect(client.eventsUrl('workbench/一', 'run/一')).toBe(
      '/api/workbenches/workbench%2F%E4%B8%80/runs/run%2F%E4%B8%80/events',
    );
  });
});
