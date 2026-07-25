/**
 * User-isolated browser locator state for active ChatRun restoration.
 *
 * @author zhourui(V33215020)
 */

interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

interface Run {
  runId: string;
  sessionId?: string;
  startedAt?: number;
  createdAt?: number;
  workingDir?: string;
  lastAppliedEventSeq?: number;
}

interface ChatRunStore {
  list(): Record<string, Run>;
  put(run: Run): void;
  remove(runId: string): void;
}

export function storageKey(userId: string | null | undefined): string {
  return 'agent_web_active_runs:' + String(userId || 'anonymous');
}

function parseStored(storage: StorageLike, key: string): Record<string, Run> {
  try {
    var parsed: any = JSON.parse(storage.getItem(key) || '{}');
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as Record<string, Run> : {};
  } catch (error) {
    return {};
  }
}

export function createStore(storage: StorageLike, userId: string): ChatRunStore {
  var key = storageKey(userId);
  function list(): Record<string, Run> {
    return parseStored(storage, key);
  }
  function save(entries: Record<string, Run>): void {
    if (Object.keys(entries).length === 0) storage.removeItem(key);
    else storage.setItem(key, JSON.stringify(entries));
  }
  return {
    list: list,
    put: function (run: Run): void {
      if (!run || !run.runId) return;
      var entries = list();
      entries[run.runId] = run;
      save(entries);
    },
    remove: function (runId: string): void {
      var entries = list();
      delete entries[runId];
      save(entries);
    }
  };
}

function runTime(run: Run): number {
  return Number(run.startedAt || run.createdAt || 0);
}

function newest(runs: Run[]): Run | null {
  if (!runs.length) return null;
  return runs.slice().sort(function (left: Run, right: Run): number {
    return runTime(right) - runTime(left);
  })[0];
}

export function selectActiveRun(activeRuns: Run[] | null, localRuns: Record<string, Run> | null, workingDir: string): Run | null {
  var active = Array.isArray(activeRuns) ? activeRuns : [];
  var local = localRuns || {};
  var locallyKnown = active.filter(function (run: Run) { return !!local[run.runId]; });
  if (locallyKnown.length) return newest(locallyKnown);
  if (active.length === 1) return active[0];
  var inWorkspace = active.filter(function (run: Run) { return run.workingDir === workingDir; });
  return newest(inWorkspace);
}