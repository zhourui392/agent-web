/**
 * 消息视图 lib (ES module): 浏览器挂 window.AgentMessageView, Node/Vitest 走 ES import。
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
 * 依赖: parseStreamJson / parseUserMessage / isStreamJson (ES import from formatters.js)。
 */
import { parseStreamJson, parseUserMessage, isStreamJson, type StreamSegment } from './formatters.js';

export var ROLE_LABELS: Record<string, string> = { user: '用户', assistant: '助手', system: '系统' };

interface RawMessage {
  role?: string;
  content?: string;
  recall?: string;
  [key: string]: any;
}

interface EnrichedMessage extends RawMessage {
  parsedSegments?: StreamSegment[];
  bodyText?: string;
  images?: string[];
  recall?: any;
  recallOpen?: boolean;
}

export function enrichMessage(msg: null, options?: { withRecall?: boolean }): null;
export function enrichMessage(msg: RawMessage, options?: { withRecall?: boolean }): EnrichedMessage;
export function enrichMessage(msg: RawMessage | null, options?: { withRecall?: boolean }): EnrichedMessage | null {
  if (!msg) return msg;
  var withRecall = !!(options && options.withRecall);
  var recall: any = null;
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

export function mapMessages(rawMsgs: RawMessage[] | null, options?: { withRecall?: boolean }): EnrichedMessage[] {
  return (rawMsgs || []).map(function (msg) { return enrichMessage(msg, options) as EnrichedMessage; });
}

export function roleLabel(r: string): string { return ROLE_LABELS[r] || r; }