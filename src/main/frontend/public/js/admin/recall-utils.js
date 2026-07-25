/**
 * Recall observability admin page pure helpers (ES module).
 *
 * 浏览器挂 window.AgentRecallUtils (兼容未改的消费者); Node/Vitest 走 ES import。
 */
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

export function buildRecallQuery(filters, page, size) {
  var params = new URLSearchParams();
  appendIfPresent(params, 'page', page);
  appendIfPresent(params, 'size', size);
  var source = filters || {};
  for (var i = 0; i < FILTER_KEYS.length; i++) {
    appendIfPresent(params, FILTER_KEYS[i], source[FILTER_KEYS[i]]);
  }
  return params.toString();
}

export function pct(value) {
  return typeof value === 'number' ? (value * 100).toFixed(1) + '%' : '-';
}

export function score(value) {
  return typeof value === 'number' ? value.toFixed(3) : '-';
}

export function millis(value) {
  return typeof value === 'number' ? value + 'ms' : '-';
}

export function epochTime(value) {
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

export function statusLabel(status) {
  return STATUS_LABELS[status] || status || '-';
}

export function statusTagType(status) {
  return STATUS_TYPES[status] || 'info';
}

export function compactText(value, max) {
  if (!value) {
    return '';
  }
  var text = String(value);
  var limit = typeof max === 'number' && max > 0 ? max : 80;
  return text.length <= limit ? text : text.substring(0, limit) + '...';
}

export function bucketDisplayKey(group, key) {
  return (group || '-') + ' · ' + (key || '-');
}

// 浏览器: 挂全局 window.AgentRecallUtils (兼容未改的消费者)
if (typeof window !== 'undefined') {
  window.AgentRecallUtils = {
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
}