/** Chat 域 API */
import { fetchJson, postJson, deleteJson, query } from './client';

export interface ChatSession {
  sessionId: string;
  workingDir: string;
  agentType: string;
}

export interface ChatMessage {
  id: number;
  role: 'user' | 'assistant' | 'system';
  content: string;
  recall?: string;
}

export interface ChatRun {
  runId: string;
  sessionId: string;
  status: string;
}

export interface ChatSessionListItem {
  sessionId: string;
  agentType: string;
  workingDir: string;
  lastMessageAt: string;
  messageCount: number;
}

export interface ChatSessionListResponse {
  sessions: ChatSessionListItem[];
  total: number;
  page: number;
  size: number;
}

export function createSession(workingDir: string, agentType: string) {
  return postJson<ChatSession>('/api/chat/session', { workingDir, agentType });
}

export function getMessages(sessionId: string) {
  return fetchJson<ChatMessage[]>(`/api/chat/session/${encodeURIComponent(sessionId)}/messages`);
}

export function deleteMessages(sessionId: string, fromId: number) {
  return deleteJson<{ deletedCount: number; prefillContent: string }>(
    `/api/chat/session/${encodeURIComponent(sessionId)}/messages${query({ fromId })}`,
  );
}

export function getCommands(workingDir: string) {
  return fetchJson<string[]>(`/api/chat/commands${query({ workingDir })}`);
}

export function stopRun(runId: string) {
  return postJson(`/api/chat/runs/${encodeURIComponent(runId)}/stop`);
}

export function getActiveRun() {
  return fetchJson<any>('/api/chat/runs/active');
}

export function listSessions(page = 1, size = 20) {
  return fetchJson<any>(`/api/chat/sessions${query({ page, size })}`);
}

export function getAgentDefault() {
  return fetchJson<any>('/api/chat/agent-default');
}