/**
 * 消息视图 lib (UMD-lite): 浏览器挂 window.AgentMessageView, Node/Vitest 走 module.exports。
 *
 * 抽出原因:
 * - enrichMessage 在 conversations.js / refinery.js 两处逐字节复制。
 * - mapMessages (带 recall) 在 app.js viewHistory / share.html 两处逐字节复制。
 * - ROLE_LABELS / roleLabel 在 conversations.js / refinery.js 各抄一份。
 *
 * 统一为 enrichMessage(msg, options) + mapMessages(rawMsgs, options):
 * - options.withRecall=false (默认): 匹配 conversations/refinery 的 enrichMessage (不处理 recall)。
 * - options.withRecall=true: 匹配 app.js viewHistory / share.html mapMessages (解析 recall + recallOpen)。
 *
 * 依赖: window.AgentFormatters (parseStreamJson/parseUserMessage/isStreamJson,须在 formatters.js 之后加载)。
 */
(function (root) {
  var formatters = root.AgentFormatters || {};
  var parseStreamJson = formatters.parseStreamJson || function () { return []; };
  var parseUserMessage = formatters.parseUserMessage || function () { return { text: '', images: [] }; };
  var isStreamJson = formatters.isStreamJson || function () { return false; };

  var ROLE_LABELS = { user: '用户', assistant: '助手', system: '系统' };

  function enrichMessage(msg, options) {
    if (!msg) return msg;
    var withRecall = !!(options && options.withRecall);
    var recall = null;
    if (withRecall && msg.role === 'assistant' && msg.recall) {
      try { recall = JSON.parse(msg.recall); } catch (e) { recall = null; }
    }
    if (msg.role === 'assistant' && isStreamJson(msg.content)) {
      return withRecall
        ? Object.assign({}, msg, { parsedSegments: parseStreamJson(msg.content), recall: recall, recallOpen: false })
        : Object.assign({}, msg, { parsedSegments: parseStreamJson(msg.content) });
    }
    if (msg.role === 'user') {
      var parsed = parseUserMessage(msg.content);
      return Object.assign({}, msg, { bodyText: parsed.text, images: parsed.images });
    }
    return withRecall
      ? Object.assign({}, msg, { recall: recall, recallOpen: false })
      : Object.assign({}, msg);
  }

  function mapMessages(rawMsgs, options) {
    return (rawMsgs || []).map(function (msg) { return enrichMessage(msg, options); });
  }

  function roleLabel(r) { return ROLE_LABELS[r] || r; }

  var api = {
    ROLE_LABELS: ROLE_LABELS,
    enrichMessage: enrichMessage,
    mapMessages: mapMessages,
    roleLabel: roleLabel
  };
  root.AgentMessageView = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
