/**
 * Workbench Capability Source 管理 API 客户端。
 *
 * @author alex
 * @since 2026-08-05
 */

export interface CommandCatalogDirectoryInput {
  directoryIdentifier: string;
  absoluteDirectory: string;
  enabled: boolean;
}

export type SkillTrustSource = 'PLATFORM' | 'APPROVED_USER' | 'WORKSPACE';

export interface SkillCatalogDirectoryInput {
  directoryIdentifier: string;
  absoluteDirectory: string;
  trustSource: SkillTrustSource;
  enabled: boolean;
}

export interface McpCatalogConfiguration {
  schema: string;
  servers: unknown[];
  [key: string]: unknown;
}

export interface CapabilitySourceCandidate {
  commandCatalogDirectories: readonly CommandCatalogDirectoryInput[];
  skillCatalogDirectories: readonly SkillCatalogDirectoryInput[];
  mcpConfiguration: McpCatalogConfiguration;
}

export interface CapabilitySourceConfiguration extends CapabilitySourceCandidate {
  version: number;
  configurationHash: string | null;
  updatedBy: unknown;
  updatedAt: string | null;
}

export interface CapabilityDiscoveryItem {
  identifier: string;
  version: string;
  contentHash: string;
  displayName: string;
}

export interface CapabilitySourceValidationResult {
  commandCatalogDirectories?: unknown[];
  skillCatalogDirectories?: unknown[];
  canonicalMcpConfiguration?: McpCatalogConfiguration;
  commands?: CapabilityDiscoveryItem[];
  skills?: CapabilityDiscoveryItem[];
  mcpServers?: CapabilityDiscoveryItem[];
  warnings: unknown[];
}

export type CapabilitySourceFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export class CapabilitySourceApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'CapabilitySourceApiError';
    this.status = status;
    this.code = code;
  }
}

export interface CapabilitySourceApiClient {
  find(): Promise<CapabilitySourceConfiguration>;
  validate(candidate: CapabilitySourceCandidate):
    Promise<CapabilitySourceValidationResult>;
  update(candidate: CapabilitySourceCandidate, expectedVersion: number):
    Promise<CapabilitySourceConfiguration>;
}

export function createCapabilitySourceApiClient(
  fetchImplementation: CapabilitySourceFetch = globalThis.fetch.bind(globalThis),
): CapabilitySourceApiClient {
  const baseUrl = '/api/admin-settings/workbench/capability-sources';

  return {
    async find() {
      return request<CapabilitySourceConfiguration>(fetchImplementation, baseUrl);
    },

    async validate(candidate) {
      return request<CapabilitySourceValidationResult>(
        fetchImplementation,
        `${baseUrl}/validation`,
        jsonRequest('POST', candidate),
      );
    },

    async update(candidate, expectedVersion) {
      return request<CapabilitySourceConfiguration>(
        fetchImplementation,
        baseUrl,
        jsonRequest('PUT', candidate, expectedVersion),
      );
    },
  };
}

function jsonRequest(
  method: string,
  body: unknown,
  expectedVersion?: number,
): RequestInit {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'Content-Type': 'application/json',
  };
  if (expectedVersion !== undefined) {
    headers['If-Match'] = String(expectedVersion);
  }
  return {
    method,
    credentials: 'same-origin',
    headers,
    body: JSON.stringify(body),
  };
}

async function request<T>(
  fetchImplementation: CapabilitySourceFetch,
  url: string,
  init: RequestInit = {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  },
): Promise<T> {
  const response = await fetchImplementation(url, init);
  if (!response.ok) {
    throw await apiError(response);
  }
  return await response.json() as T;
}

async function apiError(response: Response): Promise<CapabilitySourceApiError> {
  let code = 'WORKBENCH_CAPABILITY_SOURCE_REQUEST_FAILED';
  let message = `请求失败 (${response.status})`;
  try {
    const body = await response.json() as { code?: string; message?: string };
    code = body.code ?? code;
    message = body.message ?? message;
  } catch {
    // 非 JSON 错误响应保持稳定的客户端错误码。
  }
  return new CapabilitySourceApiError(response.status, code, message);
}
