/**
 * 共享对话消息视图合同。
 *
 * Chat 和 Workbench adapter 各自把领域消息映射为 ConversationMessageView，
 * 共享组件只消费此合同，不解释 Chat 或 Workbench 业务状态。
 *
 * @author alex
 * @since 2026-08-04
 */

/** 消息角色 */
export type ConversationMessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM' | 'ERROR';

/** Segment 类型联合 */
export type ConversationSegmentType =
  | 'text'
  | 'tool'
  | 'file_change'
  | 'mcp_tool_call'
  | 'test_progress';

/** Segment 展示视图 */
export interface ConversationSegmentView {
  type: ConversationSegmentType;
  content: string;
  // tool / mcp_tool_call
  toolName?: string;
  status?: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  // tool
  commandClass?: 'SHELL' | 'TEST' | 'BUILD' | 'OTHER';
  repositoryKey?: string;
  exitCode?: number;
  durationMs?: number;
  commandSummary?: string;
  outputSummary?: string;
  // file_change
  relativePath?: string;
  changeType?: 'CREATE' | 'MODIFY' | 'DELETE';
  // test_progress
  suiteName?: string;
  testStatus?: string;
  summary?: string;
}

/** 召回视图 */
export interface ConversationRecallView {
  hits: ReadonlyArray<{ title: string; conclusion?: string }>;
  query?: string;
  recallOpen: boolean;
}

/** 授权文档引用 */
export interface AuthorizedDocumentReference {
  repositoryKey: string;
  relativePath: string;
}

/** 消息视图 — 共享组件的唯一数据合同 */
export interface ConversationMessageView {
  /** 稳定字符串标识，不依赖数组下标 */
  messageKey: string;
  /** 持久化消息 ID，null 表示尚未持久化 */
  persistedMessageId: number | null;
  role: ConversationMessageRole;
  /** 用户消息正文（已从原始 content 解析出纯文本） */
  bodyText: string;
  /** 用户消息图片路径列表 */
  images: ReadonlyArray<string>;
  /** Agent 消息 segment 列表 */
  segments: ReadonlyArray<ConversationSegmentView>;
  /** ISO 时间戳，null 表示无时间戳 */
  createdAt: string | null;
  /** RAG 召回信息，null 表示无召回 */
  recall: ConversationRecallView | null;
  /** 授权文档引用列表 */
  documentReferences: ReadonlyArray<AuthorizedDocumentReference>;
  /** 是否为流式消息（当前活动 Run） */
  streaming: boolean;
}

// ---- messageKey 生成辅助 ----

/** 持久化消息的 messageKey */
export function persistedMessageKey(persistedMessageId: number): string {
  return 'persisted-message-' + persistedMessageId;
}

/** 流式运行中的 messageKey */
export function streamingMessageKey(runId: string | number): string {
  return 'run-' + runId + '-streaming';
}

// ---- adapter 辅助 ----

/** 角色映射：Chat/Workbench 领域角色 → 共享角色 */
export function toMessageRole(role: string): ConversationMessageRole {
  switch (role) {
    case 'user': return 'USER';
    case 'assistant':
    case 'agent': return 'ASSISTANT';
    case 'system': return 'SYSTEM';
    case 'error': return 'ERROR';
    default: return 'SYSTEM';
  }
}
