/**
 * Recall observability admin page pure helpers.
 */
(function (root) {
  var STATUS_LABELS = {
    PENDING: '处理中',
    SKIPPED: '跳过',
    NO_HIT: '0 命中',
    HIT: '命中',
    ERROR: '异常'
  };

  var STATUS_TYPES = {
    PENDING: 'info',
    SKIPPED: 'info',
    NO_HIT: 'warning',
    HIT: 'success',
    ERROR: 'danger'
  };

  var FILTER_KEYS = ['status', 'sessionId', 'embeddingModel', 'env', 'sourceType', 'tier', 'from', 'to'];

  function appendIfPresent(params, key, value) {
    if (value === null || value === undefined) {
      return;
    }
    if (typeof value === 'string' && value.trim() === '') {
      return;
    }
    params.set(key, value);
  }

  function buildRecallQuery(filters, page, size) {
    var params = new URLSearchParams();
    appendIfPresent(params, 'page', page);
    appendIfPresent(params, 'size', size);
    var source = filters || {};
    for (var i = 0; i < FILTER_KEYS.length; i++) {
      appendIfPresent(params, FILTER_KEYS[i], source[FILTER_KEYS[i]]);
    }
    return params.toString();
  }

  function pct(value) {
    return typeof value === 'number' ? (value * 100).toFixed(1) + '%' : '-';
  }

  function score(value) {
    return typeof value === 'number' ? value.toFixed(3) : '-';
  }

  function millis(value) {
    return typeof value === 'number' ? value + 'ms' : '-';
  }

  function epochTime(value) {
    if (typeof value !== 'number') {
      return '-';
    }
    try {
      return new Date(value).toLocaleString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hourCycle: 'h23'
      });
    } catch (e) {
      return String(value);
    }
  }

  function statusLabel(status) {
    return STATUS_LABELS[status] || status || '-';
  }

  function statusTagType(status) {
    return STATUS_TYPES[status] || 'info';
  }

  function compactText(value, max) {
    if (!value) {
      return '';
    }
    var text = String(value);
    var limit = typeof max === 'number' && max > 0 ? max : 80;
    return text.length <= limit ? text : text.substring(0, limit) + '...';
  }

  function bucketDisplayKey(group, key) {
    return (group || '-') + ' · ' + (key || '-');
  }

  var api = {
    buildRecallQuery: buildRecallQuery,
    pct: pct,
    score: score,
    millis: millis,
    epochTime: epochTime,
    statusLabel: statusLabel,
    statusTagType: statusTagType,
    compactText: compactText,
    bucketDisplayKey: bucketDisplayKey
  };

  root.AgentRecallUtils = api;
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
})(typeof window !== 'undefined' ? window : globalThis);
