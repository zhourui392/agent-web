import { describe, expect, it, vi, afterEach } from 'vitest';
import { useHistory } from '../../frontend/js/composables/useHistory.js';
// @ts-expect-error Frontend Vue ESM entry has no declaration for this relative test import.
import { ref } from '../../frontend/node_modules/vue/index.mjs';

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), success: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}));

describe('useHistory', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('keeps current rows visible while a refresh is pending and replaces them after success', async () => {
    let resolveSessions: (items: unknown[]) => void = () => undefined;
    const sessions = new Promise<unknown[]>(resolve => { resolveSessions = resolve; });
    const fetchMock = vi.fn((url: string) => {
      if (url.startsWith('/api/chat/sessions')) {
        return Promise.resolve({ json: () => sessions });
      }
      return Promise.resolve({ ok: true, json: () => Promise.resolve([]) });
    });
    vi.stubGlobal('fetch', fetchMock);

    const history = useHistory({
      currentUserId: ref('user-1'),
      agentType: ref('CODEX'),
      activeResumeId: ref(''),
      activeSessionId: ref(''),
      activeEnvironment: ref(''),
    });
    const oldItem = { sessionId: 'old', createdAt: '2026-08-08T00:00:00Z' };
    history.historyList.value = [oldItem];

    const pendingRefresh = history.loadHistory(true);
    expect(history.historyList.value).toEqual([oldItem]);
    expect(history.historyLoading.value).toBe(true);

    await history.loadHistory(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);

    resolveSessions([{ sessionId: 'new', createdAt: '2026-08-08T00:00:00Z' }]);
    await pendingRefresh;

    expect(history.historyList.value.map(item => item.sessionId)).toEqual(['new']);
    expect(history.historyLoading.value).toBe(false);
  });
});
