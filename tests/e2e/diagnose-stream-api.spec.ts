import { test, expect } from '@playwright/test';
import { readSse, SseEvent } from './_sse';
import { cleanupDir, makeTempDir } from './_tmp';

const API_KEY = 'ak-ops-team-change-me';

test.describe('诊断 SSE 与幂等 API', () => {
  let tmpDir: string;

  test.beforeAll(async () => {
    tmpDir = await makeTempDir('e2e-diagnose-stream');
  });

  test.afterAll(async () => {
    await cleanupDir(tmpDir);
  });

  async function submit(request: any, marker: string, clientToken?: string) {
    const headers: Record<string, string> = { 'X-API-Key': API_KEY, 'Content-Type': 'application/json' };
    if (clientToken) {
      headers['Idempotency-Key'] = clientToken;
    }
    const res = await request.post('/api/diagnose', {
      headers,
      data: {
        agentType: 'CODEX',
        workingDir: tmpDir,
        query: marker + ' ServiceA timeout',
        env: 'test',
      },
    });
    expect(res.status()).toBe(201);
    return res.json();
  }

  test('相同 Idempotency-Key 返回同一 taskId 且 streamUrl 为根路径', async ({ request }) => {
    const marker = 'E2E-DIAG-IDEM-' + Date.now();
    const clientToken = marker + '-token';

    const first = await submit(request, marker, clientToken);
    const second = await submit(request, marker, clientToken);

    expect(second.taskId).toBe(first.taskId);
    expect(first.streamUrl).toBe(`/api/diagnose/${first.taskId}/stream`);
  });

  test('SSE 收到递增 id 的 status 与终态事件,Last-Event-ID 只回放后续事件', async ({ request, baseURL }) => {
    test.setTimeout(120_000);
    const marker = 'E2E-DIAG-SSE-' + Date.now();
    const submitted = await submit(request, marker);
    const streamUrl = new URL(submitted.streamUrl, baseURL).toString();

    // 全套跑时 diagnose 执行器可能被先行 spec 的任务占满, 盯活流会在超时窗内收不到事件;
    // 终态后 subscribe 会确定性回放全部持久化事件, 所以先轮询到终态再读 SSE
    await waitForTerminal(request, submitted.taskId);

    const events = await readSse(streamUrl, {
      headers: { 'X-API-Key': API_KEY },
      terminalEvents: ['result', 'error'],
      timeoutMs: 20_000,
    });
    expect(events.some((e) => e.event === 'status')).toBeTruthy();
    expect(events.some((e) => e.event === 'result' || e.event === 'error')).toBeTruthy();
    assertIncreasingIds(events);

    const firstId = events.find((e) => e.id != null)!.id!;
    const replay = await readSse(streamUrl, {
      headers: { 'X-API-Key': API_KEY, 'Last-Event-ID': String(firstId) },
      terminalEvents: ['result', 'error'],
      timeoutMs: 10_000,
    });
    expect(replay.length).toBeGreaterThan(0);
    expect(replay.every((e) => e.id == null || e.id > firstId)).toBeTruthy();
    expect(replay.some((e) => e.event === 'result' || e.event === 'error')).toBeTruthy();
  });
});

async function waitForTerminal(request: any, taskId: string): Promise<void> {
  await expect
    .poll(async () => {
      const res = await request.get(`/api/diagnose/${taskId}`, { headers: { 'X-API-Key': API_KEY } });
      return res.ok() ? (await res.json()).status : 'HTTP_' + res.status();
    }, { timeout: 60_000 })
    .toMatch(/^(SUCCESS|FAILED|CANCELLED|TIMEOUT)$/);
}

function assertIncreasingIds(events: SseEvent[]): void {
  const ids = events.map((e) => e.id).filter((id): id is number => id != null);
  expect(ids.length).toBeGreaterThan(0);
  for (let i = 1; i < ids.length; i++) {
    expect(ids[i]).toBeGreaterThan(ids[i - 1]);
  }
}
