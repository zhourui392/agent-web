/**
 * 模式（Chat Mode）API client。
 *
 * @author alex
 * @since 2026-08-20
 */
import { fetchJson, postJson, putJson, deleteJson } from './client';

export interface ModeCapabilityRef {
  identifier: string;
  version: string;
  contentHash: string;
  sortOrder?: number;
}

export interface ModeMcpServerRef extends ModeCapabilityRef {
  maximumAccess: 'READ' | 'WRITE';
  transport: 'STDIO' | 'STREAMABLE_HTTP';
}

export interface ChatMode {
  id: string;
  identifier: string;
  displayName: string;
  description: string | null;
  defaultPrompt: string | null;
  permissionMode: string | null;
  model: string | null;
  effort: string | null;
  sourceRevisionId: number | null;
  commands: ModeCapabilityRef[];
  skills: ModeCapabilityRef[];
  mcpServers: ModeMcpServerRef[];
}

export interface ModeTemplate {
  revisionId: number;
  definitionIdentifier: string;
  displayName: string;
  description: string;
  commandCount: number;
  skillCount: number;
  mcpServerCount: number;
}

export interface ModeCapabilityCatalogItem {
  kind: 'COMMAND' | 'SKILL' | 'MCP_SERVER';
  identifier: string;
  version: string;
  displayName: string;
  description: string;
  hash: string;
  maximumAccess: string | null;
  transport: string | null;
}

export interface SaveModeRequest {
  identifier?: string;
  displayName: string;
  description?: string | null;
  defaultPrompt?: string | null;
  permissionMode?: string | null;
  model?: string | null;
  effort?: string | null;
  commands?: ModeCapabilityRef[];
  skills?: ModeCapabilityRef[];
  mcpServers?: ModeMcpServerRef[];
}

export interface ModeSwitchResult {
  newSessionId: string;
  handoffDocumentId: string;
}

/** 管理后台全量模式读模型：ChatMode 字段 + 归属者。 */
export interface AdminChatMode extends ChatMode {
  ownerUserId: string;
  ownerUsername: string;
}

export interface HandoffDocumentView {
  id: string;
  fromSessionId: string;
  toSessionId: string;
  fromModeId: string;
  toModeId: string;
  filePath: string;
  createdAt: string;
  content: string;
}

export function listMyModes(): Promise<ChatMode[]> {
  return fetchJson<ChatMode[]>('/api/modes?scope=mine');
}

/** 管理后台全量模式列表（跨用户，含归属者；仅 ADMIN，401/403 由拦截器统一处理）。 */
export function listAllModes(): Promise<AdminChatMode[]> {
  return fetchJson<AdminChatMode[]>('/api/admin-settings/modes');
}

export function listModeTemplates(): Promise<ModeTemplate[]> {
  return fetchJson<ModeTemplate[]>('/api/modes/templates');
}

export function listCapabilityCatalog(): Promise<ModeCapabilityCatalogItem[]> {
  return fetchJson<ModeCapabilityCatalogItem[]>('/api/modes/capabilities');
}

export function createMode(request: SaveModeRequest): Promise<ChatMode> {
  return postJson<ChatMode>('/api/modes', request);
}

export function updateMode(id: string, request: SaveModeRequest): Promise<ChatMode> {
  return putJson<ChatMode>(`/api/modes/${encodeURIComponent(id)}`, request);
}

export function deleteMode(id: string): Promise<void> {
  return deleteJson<void>(`/api/modes/${encodeURIComponent(id)}`);
}

export function forkMode(
  definitionIdentifier: string,
  revisionId: number,
  displayName?: string,
): Promise<ChatMode> {
  return postJson<ChatMode>('/api/modes/fork', {
    definitionIdentifier,
    revisionId,
    displayName,
  });
}

/** 单步同步模式切换；有活跃 run 时后端返回 409。 */
export function switchSessionMode(
  sessionId: string,
  targetModeId: string | null,
  idempotencyKey: string,
): Promise<ModeSwitchResult> {
  return postJson<ModeSwitchResult>(
    `/api/chat/session/${encodeURIComponent(sessionId)}/mode-switch`,
    { targetModeId },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  );
}

export function getHandoffDocument(id: string): Promise<HandoffDocumentView> {
  return fetchJson<HandoffDocumentView>(`/api/handoff-documents/${encodeURIComponent(id)}`);
}
