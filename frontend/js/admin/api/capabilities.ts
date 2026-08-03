/**
 * 管理后台阶段能力配置 API 客户端。
 *
 * @author alex
 * @since 2026-08-02
 */

export interface CapabilityReferenceInput {
  id: string;
  type: 'RULE' | 'SKILL' | 'MCP_SERVER';
  required: boolean;
}

export interface CapabilityReferenceView {
  id: string;
  type: string;
  required: boolean;
}

export interface AdminPhaseCapabilityProfile {
  phase: string;
  profileId: string;
  profileVersion: string;
  profileHash: string;
  capabilities: CapabilityReferenceView[];
  updatedById: string;
  updatedByName: string;
  updatedAt: number;
  version: number;
}

export interface CatalogEntry {
  id: string;
  version: string;
  description: string;
  compatibleRuntimes: string[];
}

export interface AdminCapabilityCatalog {
  skills: CatalogEntry[];
  mcpServers: CatalogEntry[];
}

export class AdminCapabilityApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'AdminCapabilityApiError';
    this.status = status;
    this.code = code;
  }
}

export async function fetchProfiles(): Promise<AdminPhaseCapabilityProfile[]> {
  const res = await fetch('/api/admin/workbench-capabilities/profiles', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });
  if (!res.ok) throw await apiError(res);
  return (await res.json()) as AdminPhaseCapabilityProfile[];
}

export async function fetchProfile(
  phase: string,
): Promise<AdminPhaseCapabilityProfile> {
  const res = await fetch(
    `/api/admin/workbench-capabilities/profiles/${encodeURIComponent(phase)}`,
    { credentials: 'same-origin', headers: { Accept: 'application/json' } },
  );
  if (!res.ok) throw await apiError(res);
  return (await res.json()) as AdminPhaseCapabilityProfile;
}

export async function updateProfile(
  phase: string,
  capabilities: CapabilityReferenceInput[],
  expectedVersion: number,
): Promise<AdminPhaseCapabilityProfile> {
  const res = await fetch(
    `/api/admin/workbench-capabilities/profiles/${encodeURIComponent(phase)}`,
    {
      method: 'PUT',
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'If-Match': String(expectedVersion),
      },
      body: JSON.stringify({ capabilities }),
    },
  );
  if (!res.ok) throw await apiError(res);
  return (await res.json()) as AdminPhaseCapabilityProfile;
}

export async function fetchCatalog(): Promise<AdminCapabilityCatalog> {
  const res = await fetch('/api/admin/workbench-capabilities/catalog', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });
  if (!res.ok) throw await apiError(res);
  return (await res.json()) as AdminCapabilityCatalog;
}

async function apiError(res: Response): Promise<AdminCapabilityApiError> {
  let code = 'UNKNOWN';
  let message = `请求失败 (${res.status})`;
  try {
    const body = await res.json();
    if (body.code) code = body.code;
    if (body.message) message = body.message;
  } catch {
    // 响应体非 JSON
  }
  return new AdminCapabilityApiError(res.status, code, message);
}
