/**
 * 动态 Workbench Stage Definition 管理 API 客户端。
 *
 * @author alex
 * @since 2026-08-05
 */

export type WorkbenchRunMode = 'DISCUSS_READ_ONLY' | 'MODIFY_WORKSPACE';

export interface VersionSelectionInput {
  identifier: string;
  version: string;
}

export interface RequiredVersionSelectionInput extends VersionSelectionInput {
  required: boolean;
}

export interface StageDefinitionDraftInput {
  sequenceNumber: number;
  displayName: string;
  description: string;
  stageRules: string;
  allowedRunModes: readonly WorkbenchRunMode[];
  commandReferences: readonly VersionSelectionInput[];
  skillReferences: readonly RequiredVersionSelectionInput[];
  mcpServerReferences: readonly RequiredVersionSelectionInput[];
}

export interface StageDefinitionView {
  definitionIdentifier: string;
  definitionVersion: number;
  lifecycleStatus: 'DRAFT' | 'PUBLISHED' | 'DISABLED';
  hasDraft: boolean;
  draft: (StageDefinitionDraftInput & { draftHash: string }) | null;
  published: (StageDefinitionDraftInput & {
    revisionNumber: number;
    definitionHash: string;
  }) | null;
}

export interface StageCatalogView {
  stageCatalogVersion: number;
  definitions: StageDefinitionView[];
  updatedAt: string | null;
}

export type StageDefinitionFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export class StageDefinitionApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'StageDefinitionApiError';
    this.status = status;
    this.code = code;
  }
}

export interface StageDefinitionApiClient {
  findAll(): Promise<StageCatalogView>;
  create(
    definitionIdentifier: string,
    draft: StageDefinitionDraftInput,
    expectedCatalogVersion: number,
  ): Promise<StageDefinitionView>;
  saveDraft(
    definitionIdentifier: string,
    draft: StageDefinitionDraftInput,
    expectedDefinitionVersion: number,
  ): Promise<StageDefinitionView>;
  publish(
    definitionIdentifier: string,
    expectedDefinitionVersion: number,
    expectedCatalogVersion: number,
  ): Promise<unknown>;
  disable(
    definitionIdentifier: string,
    expectedDefinitionVersion: number,
    expectedCatalogVersion: number,
  ): Promise<StageDefinitionView>;
}

export function createStageDefinitionApiClient(
  fetchImplementation: StageDefinitionFetch = globalThis.fetch.bind(globalThis),
): StageDefinitionApiClient {
  const baseUrl = '/api/admin-settings/workbench/stage-definitions';
  const definitionUrl = (identifier: string): string =>
    `${baseUrl}/${encodeURIComponent(identifier)}`;

  return {
    async findAll() {
      return request<StageCatalogView>(fetchImplementation, baseUrl);
    },

    async create(definitionIdentifier, draft, expectedCatalogVersion) {
      return request<StageDefinitionView>(
        fetchImplementation,
        baseUrl,
        jsonRequest('POST', { definitionIdentifier, ...draft },
          expectedCatalogVersion),
      );
    },

    async saveDraft(definitionIdentifier, draft, expectedDefinitionVersion) {
      return request<StageDefinitionView>(
        fetchImplementation,
        `${definitionUrl(definitionIdentifier)}/draft`,
        jsonRequest('PUT', draft, expectedDefinitionVersion),
      );
    },

    async publish(
      definitionIdentifier,
      expectedDefinitionVersion,
      expectedCatalogVersion,
    ) {
      return request<unknown>(
        fetchImplementation,
        `${definitionUrl(definitionIdentifier)}/publish`,
        versionRequest(expectedDefinitionVersion, expectedCatalogVersion),
      );
    },

    async disable(
      definitionIdentifier,
      expectedDefinitionVersion,
      expectedCatalogVersion,
    ) {
      return request<StageDefinitionView>(
        fetchImplementation,
        `${definitionUrl(definitionIdentifier)}/disable`,
        versionRequest(expectedDefinitionVersion, expectedCatalogVersion),
      );
    },
  };
}

function versionRequest(
  expectedDefinitionVersion: number,
  expectedCatalogVersion: number,
): RequestInit {
  return jsonRequest(
    'POST',
    { expectedStageCatalogVersion: expectedCatalogVersion },
    expectedDefinitionVersion,
  );
}

function jsonRequest(
  method: string,
  body: unknown,
  expectedVersion: number,
): RequestInit {
  return {
    method,
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'If-Match': String(expectedVersion),
    },
    body: JSON.stringify(body),
  };
}

async function request<T>(
  fetchImplementation: StageDefinitionFetch,
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

async function apiError(response: Response): Promise<StageDefinitionApiError> {
  let code = 'WORKBENCH_STAGE_DEFINITION_REQUEST_FAILED';
  let message = `请求失败 (${response.status})`;
  try {
    const body = await response.json() as { code?: string; message?: string };
    code = body.code ?? code;
    message = body.message ?? message;
  } catch {
    // 非 JSON 错误响应保持稳定的客户端错误码。
  }
  return new StageDefinitionApiError(response.status, code, message);
}
