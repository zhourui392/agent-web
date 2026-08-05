/**
 * Workbench Owner API client 契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { readFile } from 'node:fs/promises';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  completeWorkbenchStage,
  createWorkbench,
  getSelectableWorkbenchStages,
  getWorkbench,
  inspectWorkspace,
  listWorkbenches,
  reopenWorkbenchStage,
} from '../../frontend/js/api/workbench.js';

type FetchMock = ReturnType<typeof vi.fn>;

const createRequest = {
  title: '修复登录流程',
  originalGoal: '修复超时后无法重试的问题',
  agentType: 'CODEX',
  environment: 'test',
  workspaceRoot: '/workspace/主项目',
  primaryRepository: 'service/a',
  repositories: ['service/a', 'web 客户端'],
  stageDefinitionIdentifiers: ['requirement-analysis', 'implementation'],
  expectedStageCatalogVersion: 7,
};

function jsonResponse(status = 200, body: unknown = {}): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: vi.fn().mockResolvedValue(JSON.stringify(body)),
  } as unknown as Response;
}

function requestOptions(fetchMock: FetchMock, callIndex = 0): RequestInit {
  return fetchMock.mock.calls[callIndex][1] as RequestInit;
}

describe('workbench API client', () => {
  let fetchMock: FetchMock;

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue(jsonResponse());
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('inspects a workspace through the scoped endpoint', async () => {
    await inspectWorkspace('/workspace/主项目');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith('/api/workbench/workspaces/inspect',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ workspaceRoot: '/workspace/主项目' }),
      }));
  });

  it('creates a workbench with an explicit Idempotency-Key', async () => {
    await createWorkbench(createRequest, 'create-key-1');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith('/api/workbenches', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({
        'Content-Type': 'application/json',
        'Idempotency-Key': 'create-key-1',
      }),
      body: JSON.stringify(createRequest),
    }));
  });

  it('loads the selectable published stage catalog', async () => {
    await getSelectableWorkbenchStages();

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/workbench/stage-definitions',
      undefined,
    );
  });

  it('rejects a missing Idempotency-Key before sending create', async () => {
    await expect(createWorkbench(createRequest, '   '))
      .rejects.toThrow('Idempotency-Key');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('encodes list cursors and detail path segments', async () => {
    await listWorkbenches({
      status: 'ACTIVE',
      cursorUpdatedAt: 1720000000123,
      cursorWorkbenchId: 'wb/一 二?',
      limit: 10,
    });
    await getWorkbench('wb/一 二?');

    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/workbenches?status=ACTIVE&cursorUpdatedAt=1720000000123' +
      '&cursorWorkbenchId=wb%2F%E4%B8%80+%E4%BA%8C%3F&limit=10',
    );
    expect(fetchMock.mock.calls[1][0]).toBe(
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C%3F',
    );
  });

  it('lists without a dangling query delimiter when filters are absent', async () => {
    await listWorkbenches();
    expect(fetchMock.mock.calls[0][0]).toBe('/api/workbenches');
  });

  it('sends If-Match and stable Stage Instance identifiers for lifecycle mutations', async () => {
    await completeWorkbenchStage('wb/一 二?', 'stage/implementation', 9);
    await reopenWorkbenchStage('wb/一 二?', 'stage/review', 10);

    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C%3F/stages/'
      + 'stage%2Fimplementation/complete',
    );
    expect(requestOptions(fetchMock, 0)).toEqual(expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'If-Match': '9' }),
    }));
    expect(fetchMock.mock.calls[1][0]).toBe(
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C%3F/stages/'
      + 'stage%2Freview/reopen',
    );
    expect(requestOptions(fetchMock, 1)).toEqual(expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'If-Match': '10' }),
    }));
  });

  it('surfaces a 409 version conflict without retrying the mutation', async () => {
    fetchMock.mockReset().mockResolvedValueOnce(jsonResponse(409, {
      code: 'WORKBENCH_VERSION_CONFLICT',
      message: 'stale workbench version',
    }));

    await expect(reopenWorkbenchStage('wb-1', 'stage-design', 3))
      .rejects.toMatchObject({
        status: 409,
        body: {
          code: 'WORKBENCH_VERSION_CONFLICT',
          message: 'stale workbench version',
        },
      });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('never falls back to unscoped chat or fs endpoints', async () => {
    const source = await readFile(
      new URL('../../frontend/js/api/workbench.ts', import.meta.url),
      'utf8',
    );

    expect(source).not.toMatch(/\/api\/(chat|fs)(?:\/|['"`])/);
  });
});
