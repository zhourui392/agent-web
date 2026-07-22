import { describe, expect, it } from 'vitest';
import { createRequire } from 'node:module';

const requireCjs = createRequire(import.meta.url);
const state = requireCjs('../../src/main/resources/static/js/lib/chat-run-state.js') as {
  createStore: (storage: MemoryStorage, userId: string) => RunStore;
  selectActiveRun: (activeRuns: Run[], localRuns: Record<string, Run>, workingDir: string) => Run | null;
};

type Run = {
  runId: string;
  sessionId: string;
  workingDir: string;
  createdAt?: number;
  startedAt?: number;
  lastAppliedEventSeq?: number;
};
type RunStore = {
  list: () => Record<string, Run>;
  put: (run: Run) => void;
  remove: (runId: string) => void;
};
type MemoryStorage = {
  getItem: (key: string) => string | null;
  setItem: (key: string, value: string) => void;
  removeItem: (key: string) => void;
};

function memoryStorage(): MemoryStorage {
  const values: Record<string, string> = {};
  return {
    getItem: key => values[key] ?? null,
    setItem: (key, value) => { values[key] = value; },
    removeItem: key => { delete values[key]; },
  };
}

describe('chat active run browser state', () => {
  it('isolates persisted run locators by authenticated user', () => {
    const storage = memoryStorage();
    const userOne = state.createStore(storage, 'user-1');
    const userTwo = state.createStore(storage, 'user-2');
    userOne.put({ runId: 'r1', sessionId: 's1', workingDir: '/a', lastAppliedEventSeq: 3 });
    userTwo.put({ runId: 'r2', sessionId: 's2', workingDir: '/b', lastAppliedEventSeq: 4 });

    expect(Object.keys(userOne.list())).toEqual(['r1']);
    expect(Object.keys(userTwo.list())).toEqual(['r2']);
    userOne.remove('r1');
    expect(userOne.list()).toEqual({});
    expect(Object.keys(userTwo.list())).toEqual(['r2']);
  });

  it('prefers a locally known visible run then the newest run in current workspace', () => {
    const active: Run[] = [
      { runId: 'r1', sessionId: 's1', workingDir: '/a', createdAt: 10 },
      { runId: 'r2', sessionId: 's2', workingDir: '/a', createdAt: 20 },
      { runId: 'r3', sessionId: 's3', workingDir: '/b', createdAt: 30 },
    ];

    expect(state.selectActiveRun(active, { r1: active[0] }, '/a')?.runId).toBe('r1');
    expect(state.selectActiveRun(active, {}, '/a')?.runId).toBe('r2');
    expect(state.selectActiveRun(active, {}, '/missing')).toBeNull();
    expect(state.selectActiveRun([active[2]], {}, '/missing')?.runId).toBe('r3');
  });
});
