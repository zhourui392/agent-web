/**
 * User-isolated browser locator state for active ChatRun restoration.
 *
 * @author zhourui(V33215020)
 */
(function (root) {
  function storageKey(userId) {
    return 'agent_web_active_runs:' + String(userId || 'anonymous');
  }

  function parseStored(storage, key) {
    try {
      var parsed = JSON.parse(storage.getItem(key) || '{}');
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
    } catch (error) {
      return {};
    }
  }

  function createStore(storage, userId) {
    var key = storageKey(userId);
    function list() {
      return parseStored(storage, key);
    }
    function save(entries) {
      if (Object.keys(entries).length === 0) storage.removeItem(key);
      else storage.setItem(key, JSON.stringify(entries));
    }
    return {
      list: list,
      put: function (run) {
        if (!run || !run.runId) return;
        var entries = list();
        entries[run.runId] = run;
        save(entries);
      },
      remove: function (runId) {
        var entries = list();
        delete entries[runId];
        save(entries);
      }
    };
  }

  function runTime(run) {
    return Number(run.startedAt || run.createdAt || 0);
  }

  function newest(runs) {
    if (!runs.length) return null;
    return runs.slice().sort(function (left, right) {
      return runTime(right) - runTime(left);
    })[0];
  }

  function selectActiveRun(activeRuns, localRuns, workingDir) {
    var active = Array.isArray(activeRuns) ? activeRuns : [];
    var local = localRuns || {};
    var locallyKnown = active.filter(function (run) { return !!local[run.runId]; });
    if (locallyKnown.length) return newest(locallyKnown);
    if (active.length === 1) return active[0];
    var inWorkspace = active.filter(function (run) { return run.workingDir === workingDir; });
    return newest(inWorkspace);
  }

  var api = {
    createStore: createStore,
    selectActiveRun: selectActiveRun,
    storageKey: storageKey
  };
  root.AgentChatRunState = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
