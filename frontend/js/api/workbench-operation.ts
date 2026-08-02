/**
 * TD-08 Workbench 高影响操作 Owner API client。
 *
 * @author alex
 * @since 2026-08-01
 */
import { isWorkbenchPhase, type WorkbenchPhase } from '../lib/workbench-state.js';

const IDENTIFIER_MAX_CHARS = 128;
const SAFE_TEXT_MAX_CHARS = 2000;
const MAX_RESPONSE_CHARS = 1024 * 1024;
const MAX_OPERATIONS = 200;
const SHA_256 = /^[a-f0-9]{64}$/;
const GIT_OBJECT_ID = /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/;
const CONTROL_CHARACTER = /[\u0000-\u001F\u007F]/;
const MULTILINE_CONTROL_CHARACTER = /[\u0000-\u0009\u000B\u000C\u000E-\u001F\u007F]/;
const WINDOWS_ABSOLUTE_PATH = /^[A-Za-z]:[\\/]/;

const OPERATION_TYPES = new Set<WorkbenchOperationType>([
  'GIT_COMMIT',
  'GIT_PUSH',
  'LOCAL_DEPLOY',
  'PRODUCTION_WRITE',
]);
const OPERATION_STATUSES = new Set<WorkbenchOperationStatus>([
  'PROPOSED',
  'AUTHORIZED',
  'EXECUTING',
  'SUCCEEDED',
  'FAILED',
  'RECONCILIATION_REQUIRED',
  'REJECTED',
  'EXPIRED',
]);
const SAFE_ERROR_CODES = new Set([
  'AUTHENTICATION_REQUIRED',
  'ACCESS_DENIED',
  'WORKBENCH_NOT_FOUND',
  'WORKBENCH_ARCHIVED',
  'WORKBENCH_OPERATION_NOT_FOUND',
  'WORKBENCH_OPERATION_SOURCE_RUN_NOT_FOUND',
  'IDEMPOTENCY_CONFLICT',
  'WORKBENCH_OPERATION_VERSION_CONFLICT',
  'WORKBENCH_OPERATION_REQUEST_INVALID',
  'WORKBENCH_OPERATION_TRANSITION_INVALID',
  'WORKBENCH_OPERATION_TARGET_CHANGED',
  'WORKBENCH_OPERATION_EXECUTION_UNAVAILABLE',
]);

export type WorkbenchOperationFetch = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
export type WorkbenchOperationType = 'GIT_COMMIT' | 'GIT_PUSH' | 'LOCAL_DEPLOY' | 'PRODUCTION_WRITE';
export type WorkbenchOperationStatus =
  | 'PROPOSED'
  | 'AUTHORIZED'
  | 'EXECUTING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'RECONCILIATION_REQUIRED'
  | 'REJECTED'
  | 'EXPIRED';
export type WorkbenchOperationDecision = 'APPROVE' | 'REJECT';
export type WorkbenchOperationExecutionMode = 'MANUAL_OR_DEFERRED';

export interface WorkbenchOperationTarget {
  type: WorkbenchOperationType;
  repositoryKeys: ReadonlyArray<string>;
  details: Readonly<Record<string, unknown>>;
}

export interface WorkbenchHighImpactOperation {
  operationId: string;
  sourceRunId: string;
  phase: WorkbenchPhase;
  type: WorkbenchOperationType;
  target: WorkbenchOperationTarget;
  requestedPayloadHash: string;
  safeSummary: string;
  status: WorkbenchOperationStatus;
  proposedAt: number;
  decisionReason: string | null;
  decidedAt: number | null;
  authorizationExpiresAt: number | null;
  preflightHash: string | null;
  executionReference: string | null;
  failureCode: string | null;
  updatedAt: number;
  version: number;
  executionAvailable: false;
  executionMode: WorkbenchOperationExecutionMode;
}

export interface WorkbenchOperationDecisionInput {
  decision: WorkbenchOperationDecision;
  reason: string;
}

export interface GitCommitOperationProposalTarget {
  type: 'GIT_COMMIT';
  repositoryKey: string;
  branch: string;
  expectedHead: string;
  expectedStateHash: string;
  includedPaths: ReadonlyArray<string>;
  messageHash: string;
  safeMessagePreview: string;
}

export interface GitPushOperationProposalTarget {
  type: 'GIT_PUSH';
  repositoryKey: string;
  remoteName: string;
  localBranch: string;
  remoteRef: string;
  expectedLocalHead: string;
}

export interface LocalDeployOperationProposalTarget {
  type: 'LOCAL_DEPLOY';
  templateId: string;
  templateVersion: string;
  templateHash: string;
  repositoryTargets: ReadonlyArray<string>;
  environment: 'LOCAL';
  expectedWorkspaceStateHash: string;
  rollbackSummary: string;
}

export interface ProductionWriteOperationProposalTarget {
  type: 'PRODUCTION_WRITE';
  environment: string;
  resourceReference: string;
  expectedProductionStateHash: string;
}

export type WorkbenchOperationProposalTarget =
  | GitCommitOperationProposalTarget
  | GitPushOperationProposalTarget
  | LocalDeployOperationProposalTarget
  | ProductionWriteOperationProposalTarget;

export interface WorkbenchOperationProposalInput {
  sourceRunId: string;
  phase: WorkbenchPhase;
  safeSummary: string;
  target: WorkbenchOperationProposalTarget;
}

export interface WorkbenchOperationApiClient {
  list(workbenchId: string): Promise<ReadonlyArray<WorkbenchHighImpactOperation>>;
  get(workbenchId: string, operationId: string): Promise<WorkbenchHighImpactOperation>;
  propose(
    workbenchId: string,
    idempotencyKey: string,
    input: WorkbenchOperationProposalInput,
  ): Promise<WorkbenchHighImpactOperation>;
  decide(
    workbenchId: string,
    operationId: string,
    expectedVersion: number,
    input: WorkbenchOperationDecisionInput,
  ): Promise<WorkbenchHighImpactOperation>;
}

export class WorkbenchOperationApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly current: WorkbenchHighImpactOperation | null = null,
  ) {
    super('Workbench high-impact operation request failed');
    this.name = 'WorkbenchOperationApiError';
    const sanitized = this as Error & { cause?: unknown; body?: unknown; response?: unknown };
    delete sanitized.stack;
    delete sanitized.cause;
    delete sanitized.body;
    delete sanitized.response;
  }
}

export function createWorkbenchOperationApiClient(
  injectedFetch?: WorkbenchOperationFetch,
): WorkbenchOperationApiClient {
  const execute = injectedFetch ?? ((input, init) => globalThis.fetch(input, init));

  return {
    async list(workbenchId) {
      const response = await safeFetch(execute, operationsUrl(workbenchId), {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      });
      const body = await readBody(response);
      if (!response.ok) throw responseError(response.status, body);
      if (!Array.isArray(body) || body.length > MAX_OPERATIONS) throw invalidResponse(response.status);
      const operations: WorkbenchHighImpactOperation[] = [];
      for (const value of body) {
        const operation = operationProjection(value);
        if (!operation) throw invalidResponse(response.status);
        operations.push(operation);
      }
      return operations;
    },

    async get(workbenchId, operationId) {
      const response = await safeFetch(execute, operationUrl(workbenchId, operationId), {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      });
      const body = await readBody(response);
      if (!response.ok) throw responseError(response.status, body);
      const operation = operationProjection(body);
      if (!operation || operation.operationId !== requireIdentifier(operationId, 'operationId')) {
        throw invalidResponse(response.status);
      }
      return operation;
    },

    async propose(workbenchId, idempotencyKey, input) {
      const proposal = proposalProjection(input);
      const key = boundedString(idempotencyKey, IDENTIFIER_MAX_CHARS);
      if (!proposal || !key) throw invalidRequest();
      const response = await safeFetch(execute, operationsUrl(workbenchId), {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'Idempotency-Key': key,
        },
        body: JSON.stringify(proposal),
      });
      const body = await readBody(response);
      if (!response.ok) throw responseError(response.status, body);
      if (response.status !== 201) throw invalidResponse(response.status);
      const operation = operationProjection(body);
      if (
        !operation ||
        operation.sourceRunId !== proposal.sourceRunId ||
        operation.phase !== proposal.phase ||
        operation.type !== proposal.target.type ||
        operation.status !== 'PROPOSED' ||
        operation.version !== 0 ||
        operation.executionAvailable !== false
      ) {
        throw invalidResponse(response.status);
      }
      return operation;
    },

    async decide(workbenchId, operationId, expectedVersion, input) {
      const version = nonNegativeInteger(expectedVersion);
      const decision = decisionProjection(input);
      if (version == null || !decision) throw invalidRequest();
      const response = await safeFetch(execute, `${operationUrl(workbenchId, operationId)}/decision`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'If-Match': String(version),
        },
        body: JSON.stringify(decision),
      });
      const body = await readBody(response);
      if (!response.ok) throw responseError(response.status, body);
      const operation = operationProjection(body);
      if (!operation || operation.operationId !== requireIdentifier(operationId, 'operationId')) {
        throw invalidResponse(response.status);
      }
      return operation;
    },
  };
}

function proposalProjection(value: unknown): WorkbenchOperationProposalInput | null {
  if (!isRecord(value) || !hasExactKeys(value, ['sourceRunId', 'phase', 'safeSummary', 'target'])) {
    return null;
  }
  const sourceRunId = boundedString(value.sourceRunId, IDENTIFIER_MAX_CHARS);
  const safeSummary = boundedString(value.safeSummary, SAFE_TEXT_MAX_CHARS);
  const target = proposalTargetProjection(value.target);
  return sourceRunId && isWorkbenchPhase(value.phase) && safeSummary && target
    ? { sourceRunId, phase: value.phase, safeSummary, target }
    : null;
}

function proposalTargetProjection(value: unknown): WorkbenchOperationProposalTarget | null {
  if (!isRecord(value)) return null;
  switch (value.type) {
    case 'GIT_COMMIT':
      return commitProposalTarget(value);
    case 'GIT_PUSH':
      return pushProposalTarget(value);
    case 'LOCAL_DEPLOY':
      return deployProposalTarget(value);
    case 'PRODUCTION_WRITE':
      return productionProposalTarget(value);
    default:
      return null;
  }
}

function commitProposalTarget(value: Record<string, unknown>): GitCommitOperationProposalTarget | null {
  if (!hasExactKeys(value, [
    'type', 'repositoryKey', 'branch', 'expectedHead', 'expectedStateHash',
    'includedPaths', 'messageHash', 'safeMessagePreview',
  ])) return null;
  const repositoryKey = boundedString(value.repositoryKey, 128);
  const branch = boundedString(value.branch, 512);
  const expectedHead = gitObjectId(value.expectedHead);
  const expectedStateHash = sha256(value.expectedStateHash);
  const includedPaths = stringArray(value.includedPaths, 100, 4096, requireRelativePath);
  const messageHash = sha256(value.messageHash);
  const safeMessagePreview = boundedMultilineString(value.safeMessagePreview, 500);
  if (!repositoryKey || !requireRepositoryKey(repositoryKey) || !branch || !expectedHead ||
    !expectedStateHash || !includedPaths?.length || !messageHash || !safeMessagePreview) return null;
  return {
    type: 'GIT_COMMIT', repositoryKey, branch, expectedHead, expectedStateHash,
    includedPaths, messageHash, safeMessagePreview,
  };
}

function pushProposalTarget(value: Record<string, unknown>): GitPushOperationProposalTarget | null {
  if (!hasExactKeys(value, [
    'type', 'repositoryKey', 'remoteName', 'localBranch', 'remoteRef', 'expectedLocalHead',
  ])) return null;
  const repositoryKey = boundedString(value.repositoryKey, 128);
  const remoteName = boundedString(value.remoteName, 128);
  const localBranch = boundedString(value.localBranch, 512);
  const remoteRef = boundedString(value.remoteRef, 1024);
  const expectedLocalHead = gitObjectId(value.expectedLocalHead);
  if (!repositoryKey || !requireRepositoryKey(repositoryKey) || !remoteName || !localBranch ||
    !remoteRef || !validRemoteBranchRef(remoteRef) || !expectedLocalHead) return null;
  return {
    type: 'GIT_PUSH', repositoryKey, remoteName, localBranch, remoteRef, expectedLocalHead,
  };
}

function deployProposalTarget(value: Record<string, unknown>): LocalDeployOperationProposalTarget | null {
  if (!hasExactKeys(value, [
    'type', 'templateId', 'templateVersion', 'templateHash', 'repositoryTargets',
    'environment', 'expectedWorkspaceStateHash', 'rollbackSummary',
  ])) return null;
  const templateId = boundedString(value.templateId, 128);
  const templateVersion = boundedString(value.templateVersion, 128);
  const templateHash = sha256(value.templateHash);
  const repositoryTargets = stringArray(value.repositoryTargets, 50, 128, requireRepositoryKey);
  const expectedWorkspaceStateHash = sha256(value.expectedWorkspaceStateHash);
  const rollbackSummary = boundedString(value.rollbackSummary, SAFE_TEXT_MAX_CHARS);
  if (!templateId || !templateVersion || !templateHash || !repositoryTargets?.length ||
    value.environment !== 'LOCAL' || !expectedWorkspaceStateHash || !rollbackSummary) return null;
  return {
    type: 'LOCAL_DEPLOY', templateId, templateVersion, templateHash, repositoryTargets,
    environment: 'LOCAL', expectedWorkspaceStateHash, rollbackSummary,
  };
}

function productionProposalTarget(value: Record<string, unknown>): ProductionWriteOperationProposalTarget | null {
  if (!hasExactKeys(value, [
    'type', 'environment', 'resourceReference', 'expectedProductionStateHash',
  ])) return null;
  const environment = boundedString(value.environment, 128);
  const resourceReference = boundedString(value.resourceReference, 1024);
  const expectedProductionStateHash = sha256(value.expectedProductionStateHash);
  if (!environment || environment.toLowerCase() === 'local' || !resourceReference ||
    !expectedProductionStateHash) return null;
  return { type: 'PRODUCTION_WRITE', environment, resourceReference, expectedProductionStateHash };
}

function operationProjection(value: unknown): WorkbenchHighImpactOperation | null {
  if (!isRecord(value)) return null;
  const operationId = boundedString(value.operationId, IDENTIFIER_MAX_CHARS);
  const sourceRunId = boundedString(value.sourceRunId, IDENTIFIER_MAX_CHARS);
  const type = knownValue(value.type, OPERATION_TYPES);
  const target = targetProjection(value.target);
  const requestedPayloadHash = sha256(value.requestedPayloadHash);
  const safeSummary = boundedString(value.safeSummary, SAFE_TEXT_MAX_CHARS);
  const status = knownValue(value.status, OPERATION_STATUSES);
  const proposedAt = nonNegativeInteger(value.proposedAt);
  const decisionReason = nullableBoundedString(value.decisionReason, SAFE_TEXT_MAX_CHARS);
  const decidedAt = nullableNonNegativeInteger(value.decidedAt);
  const authorizationExpiresAt = nullableNonNegativeInteger(value.authorizationExpiresAt);
  const preflightHash = nullableSha256(value.preflightHash);
  const executionReference = nullableBoundedString(value.executionReference, 256);
  const failureCode = nullableBoundedString(value.failureCode, 256);
  const updatedAt = nonNegativeInteger(value.updatedAt);
  const version = nonNegativeInteger(value.version);
  if (
    !operationId ||
    !sourceRunId ||
    !isWorkbenchPhase(value.phase) ||
    !type ||
    !target ||
    target.type !== type ||
    !requestedPayloadHash ||
    !safeSummary ||
    !status ||
    proposedAt == null ||
    decisionReason === undefined ||
    decidedAt === undefined ||
    authorizationExpiresAt === undefined ||
    preflightHash === undefined ||
    executionReference === undefined ||
    failureCode === undefined ||
    updatedAt == null ||
    version == null ||
    value.executionAvailable !== false ||
    value.executionMode !== 'MANUAL_OR_DEFERRED'
  ) {
    return null;
  }
  return {
    operationId,
    sourceRunId,
    phase: value.phase,
    type,
    target,
    requestedPayloadHash,
    safeSummary,
    status,
    proposedAt,
    decisionReason,
    decidedAt,
    authorizationExpiresAt,
    preflightHash,
    executionReference,
    failureCode,
    updatedAt,
    version,
    executionAvailable: false,
    executionMode: 'MANUAL_OR_DEFERRED',
  };
}

function targetProjection(value: unknown): WorkbenchOperationTarget | null {
  if (!isRecord(value)) return null;
  const type = knownValue(value.type, OPERATION_TYPES);
  const repositoryKeys = stringArray(value.repositoryKeys, 50, 160, requireRepositoryKey);
  if (!type || !repositoryKeys || !isRecord(value.details)) return null;
  let details: Record<string, unknown> | null;
  switch (type) {
    case 'GIT_COMMIT':
      details = commitDetails(value.details);
      break;
    case 'GIT_PUSH':
      details = pushDetails(value.details);
      break;
    case 'LOCAL_DEPLOY':
      details = deployDetails(value.details);
      break;
    case 'PRODUCTION_WRITE':
      details = productionDetails(value.details);
      break;
    default:
      return null;
  }
  if (!details) return null;
  if (type === 'PRODUCTION_WRITE' ? repositoryKeys.length !== 0 : repositoryKeys.length === 0) return null;
  return { type, repositoryKeys, details };
}

function commitDetails(value: Record<string, unknown>): Record<string, unknown> | null {
  const branch = boundedString(value.branch, 512);
  const expectedHead = gitObjectId(value.expectedHead);
  const expectedStateHash = sha256(value.expectedStateHash);
  const includedPaths = stringArray(value.includedPaths, 100, 4096, requireRelativePath);
  const messageHash = sha256(value.messageHash);
  const safeMessagePreview = boundedMultilineString(value.safeMessagePreview, 500);
  return branch && expectedHead && expectedStateHash && includedPaths?.length && messageHash && safeMessagePreview
    ? { branch, expectedHead, expectedStateHash, includedPaths, messageHash, safeMessagePreview }
    : null;
}

function pushDetails(value: Record<string, unknown>): Record<string, unknown> | null {
  const remoteName = boundedString(value.remoteName, 128);
  const localBranch = boundedString(value.localBranch, 512);
  const remoteRef = boundedString(value.remoteRef, 1024);
  const expectedLocalHead = gitObjectId(value.expectedLocalHead);
  if (!remoteName || !localBranch || !remoteRef?.startsWith('refs/heads/') || !expectedLocalHead) return null;
  if (value.forceAllowed !== false) return null;
  return { remoteName, localBranch, remoteRef, expectedLocalHead, forceAllowed: false };
}

function deployDetails(value: Record<string, unknown>): Record<string, unknown> | null {
  const templateId = boundedString(value.templateId, 128);
  const templateVersion = boundedString(value.templateVersion, 128);
  const templateHash = sha256(value.templateHash);
  const expectedWorkspaceStateHash = sha256(value.expectedWorkspaceStateHash);
  const rollbackSummary = boundedString(value.rollbackSummary, SAFE_TEXT_MAX_CHARS);
  if (
    !templateId ||
    !templateVersion ||
    !templateHash ||
    value.environment !== 'LOCAL' ||
    !expectedWorkspaceStateHash ||
    !rollbackSummary
  ) {
    return null;
  }
  return {
    templateId,
    templateVersion,
    templateHash,
    environment: 'LOCAL',
    expectedWorkspaceStateHash,
    rollbackSummary,
  };
}

function productionDetails(value: Record<string, unknown>): Record<string, unknown> | null {
  const environment = boundedString(value.environment, 128);
  const resourceReference = boundedString(value.resourceReference, 1024);
  const expectedProductionStateHash = sha256(value.expectedProductionStateHash);
  return environment && environment.toLowerCase() !== 'local' && resourceReference && expectedProductionStateHash
    ? { environment, resourceReference, expectedProductionStateHash }
    : null;
}

function decisionProjection(value: WorkbenchOperationDecisionInput): WorkbenchOperationDecisionInput | null {
  if (!isRecord(value) || (value.decision !== 'APPROVE' && value.decision !== 'REJECT')) return null;
  const reason = boundedString(value.reason, SAFE_TEXT_MAX_CHARS);
  return reason ? { decision: value.decision, reason } : null;
}

function operationsUrl(workbenchId: string): string {
  return `/api/workbenches/${encodedIdentifier(workbenchId, 'workbenchId')}/operations`;
}

function operationUrl(workbenchId: string, operationId: string): string {
  return `${operationsUrl(workbenchId)}/${encodedIdentifier(operationId, 'operationId')}`;
}

function encodedIdentifier(value: string, name: string): string {
  return encodeURIComponent(requireIdentifier(value, name));
}

function requireIdentifier(value: string, name: string): string {
  const normalized = boundedString(value, IDENTIFIER_MAX_CHARS);
  if (!normalized || normalized === '.' || normalized === '..') throw new Error(`${name} is invalid`);
  return normalized;
}

async function safeFetch(
  fetcher: WorkbenchOperationFetch,
  input: RequestInfo | URL,
  init: RequestInit,
): Promise<Response> {
  try {
    return await fetcher(input, init);
  } catch {
    throw new WorkbenchOperationApiError(0, 'WORKBENCH_OPERATION_NETWORK_ERROR');
  }
}

async function readBody(response: Response): Promise<unknown> {
  let text: string;
  try {
    text = await response.text();
  } catch {
    throw invalidResponse(response.status);
  }
  if (text.length > MAX_RESPONSE_CHARS) throw invalidResponse(response.status);
  if (!text) return null;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

function responseError(status: number, body: unknown): WorkbenchOperationApiError {
  const fallback = fallbackErrorCode(status);
  const code = isRecord(body) && typeof body.code === 'string' && SAFE_ERROR_CODES.has(body.code)
    ? body.code
    : fallback;
  const current = isRecord(body) ? operationProjection(body.current) : null;
  return new WorkbenchOperationApiError(status, code, current);
}

function fallbackErrorCode(status: number): string {
  if (status === 401) return 'AUTHENTICATION_REQUIRED';
  if (status === 403) return 'ACCESS_DENIED';
  if (status === 404) return 'WORKBENCH_OPERATION_NOT_FOUND';
  if (status === 409) return 'WORKBENCH_OPERATION_VERSION_CONFLICT';
  if (status === 422) return 'WORKBENCH_OPERATION_REQUEST_INVALID';
  if (status === 503) return 'WORKBENCH_OPERATION_EXECUTION_UNAVAILABLE';
  return 'WORKBENCH_OPERATION_REQUEST_FAILED';
}

function invalidRequest(): WorkbenchOperationApiError {
  return new WorkbenchOperationApiError(0, 'WORKBENCH_OPERATION_REQUEST_INVALID');
}

function invalidResponse(status: number): WorkbenchOperationApiError {
  return new WorkbenchOperationApiError(status, 'WORKBENCH_OPERATION_RESPONSE_INVALID');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, expected: ReadonlyArray<string>): boolean {
  const actual = Object.keys(value);
  return actual.length === expected.length && expected.every(
    key => Object.prototype.hasOwnProperty.call(value, key),
  );
}

function knownValue<T extends string>(value: unknown, values: Set<T>): T | null {
  return typeof value === 'string' && values.has(value as T) ? (value as T) : null;
}

function boundedString(value: unknown, maximum: number): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim();
  return normalized && normalized.length <= maximum && !CONTROL_CHARACTER.test(normalized) ? normalized : null;
}

function boundedMultilineString(value: unknown, maximum: number): string | null {
  if (typeof value !== 'string' || value.includes('\r')) return null;
  const normalized = value.trim();
  return normalized && normalized.length <= maximum && !MULTILINE_CONTROL_CHARACTER.test(normalized)
    ? normalized
    : null;
}

function nullableBoundedString(value: unknown, maximum: number): string | null | undefined {
  return value === null ? null : boundedString(value, maximum) ?? undefined;
}

function nonNegativeInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function nullableNonNegativeInteger(value: unknown): number | null | undefined {
  return value === null ? null : nonNegativeInteger(value) ?? undefined;
}

function sha256(value: unknown): string | null {
  return typeof value === 'string' && SHA_256.test(value) ? value : null;
}

function nullableSha256(value: unknown): string | null | undefined {
  return value === null ? null : sha256(value) ?? undefined;
}

function gitObjectId(value: unknown): string | null {
  return typeof value === 'string' && GIT_OBJECT_ID.test(value) ? value : null;
}

function stringArray(
  value: unknown,
  maximumItems: number,
  maximumChars: number,
  validator: (value: string) => string | null,
): string[] | null {
  if (!Array.isArray(value) || value.length > maximumItems) return null;
  const result: string[] = [];
  const unique = new Set<string>();
  for (const item of value) {
    const text = boundedString(item, maximumChars);
    const validated = text ? validator(text) : null;
    if (!validated || unique.has(validated)) return null;
    unique.add(validated);
    result.push(validated);
  }
  return result;
}

function requireRepositoryKey(value: string): string | null {
  return !value.includes('/') && !value.includes('\\') && value !== '.' && value !== '..' ? value : null;
}

function requireRelativePath(value: string): string | null {
  if (value.startsWith('/') || value.startsWith('~/') ||
    WINDOWS_ABSOLUTE_PATH.test(value) || value.includes('\\')) return null;
  const segments = value.split('/');
  return segments.some(segment => !segment || segment === '.' || segment === '..') ? null : value;
}

function validRemoteBranchRef(value: string): boolean {
  if (!value.startsWith('refs/heads/')) return false;
  const branch = value.slice('refs/heads/'.length);
  if (!branch || branch.startsWith('.') || branch.endsWith('/') || branch.endsWith('.') ||
    branch.endsWith('.lock') || branch.includes('..') || branch.includes('@{') ||
    branch.includes('//')) return false;
  return !/[\s~^:?*[\]\\]/.test(branch);
}
