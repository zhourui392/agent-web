/**
 * 人工 Review Opinion 与 MODIFY Confirmation 的前端 fail-closed 状态模型。
 *
 * 本模块只消费已经过边界投影的版本/Hash 证明，不定义或猜测 HTTP 端点与 DTO。
 * 异步 Token 绑定 owner、Workbench、Phase 和 Opinion，旧作用域响应不能恢复写权限。
 *
 * @author alex
 * @since 2026-08-01
 */
import type { WorkbenchRunMode } from './workbench-run-state.js';
import { isWorkbenchPhase, type WorkbenchPhase } from './workbench-state.js';

const IDENTIFIER_MAX_LENGTH = 128;
const SHA_256 = /^[a-f0-9]{64}$/;

export interface WorkbenchReviewScope {
  ownerId: string;
  workbenchId: string;
  phase: WorkbenchPhase;
}

export interface WorkbenchReviewOpinionProof {
  version: number;
  contentHash: string;
}

export interface WorkbenchReviewConfirmationProof {
  confirmationId: string;
  opinionVersion: number;
  opinionHash: string;
}

export interface WorkbenchReviewSafeError {
  code: string;
  message: string;
}

type WorkbenchReviewRequestKind = 'OPINION' | 'CONFIRMATION';

export interface WorkbenchReviewRequestToken {
  readonly kind: WorkbenchReviewRequestKind;
  readonly requestId: number;
  readonly scopeRevision: number;
  readonly scope: WorkbenchReviewScope;
  readonly opinionVersion: number | null;
  readonly opinionHash: string | null;
}

export interface WorkbenchReviewState {
  readonly scope: WorkbenchReviewScope;
  readonly opinion: WorkbenchReviewOpinionProof | null;
  readonly confirmation: WorkbenchReviewConfirmationProof | null;
  readonly error: WorkbenchReviewSafeError | null;
  readonly scopeRevision: number;
  readonly nextRequestId: number;
  readonly activeOpinionRequestId: number | null;
  readonly activeConfirmationRequestId: number | null;
}

export interface WorkbenchReviewRequestStart {
  readonly state: WorkbenchReviewState;
  readonly token: WorkbenchReviewRequestToken;
}

export function createWorkbenchReviewState(scope: WorkbenchReviewScope): WorkbenchReviewState {
  return {
    scope: normalizeScope(scope),
    opinion: null,
    confirmation: null,
    error: null,
    scopeRevision: 0,
    nextRequestId: 1,
    activeOpinionRequestId: null,
    activeConfirmationRequestId: null,
  };
}

export function switchWorkbenchReviewScope(
  state: WorkbenchReviewState,
  scope: WorkbenchReviewScope,
): WorkbenchReviewState {
  const normalized = normalizeScope(scope);
  if (sameScope(state.scope, normalized)) return state;
  return {
    ...state,
    scope: normalized,
    opinion: null,
    confirmation: null,
    error: null,
    scopeRevision: state.scopeRevision + 1,
    activeOpinionRequestId: null,
    activeConfirmationRequestId: null,
  };
}

export function beginWorkbenchReviewOpinion(state: WorkbenchReviewState): WorkbenchReviewRequestStart {
  const requestId = state.nextRequestId;
  return {
    state: {
      ...state,
      confirmation: null,
      error: null,
      nextRequestId: requestId + 1,
      activeOpinionRequestId: requestId,
      activeConfirmationRequestId: null,
    },
    token: requestToken(state, 'OPINION', requestId, null),
  };
}

export function beginWorkbenchReviewConfirmation(state: WorkbenchReviewState): WorkbenchReviewRequestStart | null {
  if (state.scope.phase !== 'REVIEW_REFACTOR' || !validOpinion(state.opinion)) {
    return null;
  }
  const requestId = state.nextRequestId;
  return {
    state: {
      ...state,
      confirmation: null,
      error: null,
      nextRequestId: requestId + 1,
      activeConfirmationRequestId: requestId,
    },
    token: requestToken(state, 'CONFIRMATION', requestId, state.opinion),
  };
}

export function applyWorkbenchReviewOpinion(
  state: WorkbenchReviewState,
  token: WorkbenchReviewRequestToken,
  proof: WorkbenchReviewOpinionProof,
): WorkbenchReviewState {
  if (!currentRequest(state, token, 'OPINION')) return state;
  const safeProof = projectOpinion(proof);
  if (!safeProof) {
    return {
      ...state,
      opinion: null,
      confirmation: null,
      error: invalidOpinionResponse(),
      activeOpinionRequestId: null,
      activeConfirmationRequestId: null,
    };
  }
  return {
    ...state,
    opinion: safeProof,
    confirmation: null,
    error: null,
    activeOpinionRequestId: null,
    activeConfirmationRequestId: null,
  };
}

export function applyWorkbenchReviewConfirmation(
  state: WorkbenchReviewState,
  token: WorkbenchReviewRequestToken,
  proof: WorkbenchReviewConfirmationProof,
): WorkbenchReviewState {
  if (!currentRequest(state, token, 'CONFIRMATION')) return state;
  const safeProof = projectConfirmation(proof);
  if (!safeProof || !confirmationMatches(state.opinion, token, safeProof)) {
    return {
      ...state,
      confirmation: null,
      error: invalidConfirmationResponse(),
      activeConfirmationRequestId: null,
    };
  }
  return {
    ...state,
    confirmation: safeProof,
    error: null,
    activeConfirmationRequestId: null,
  };
}

export function failWorkbenchReviewRequest(
  state: WorkbenchReviewState,
  token: WorkbenchReviewRequestToken,
  failure: unknown,
): WorkbenchReviewState {
  if (!currentRequest(state, token, token.kind)) return state;
  return {
    ...state,
    confirmation: null,
    error: safeFailure(failure),
    activeOpinionRequestId: token.kind === 'OPINION' ? null : state.activeOpinionRequestId,
    activeConfirmationRequestId: token.kind === 'CONFIRMATION' ? null : state.activeConfirmationRequestId,
  };
}

/**
 * Composer 内容一旦变化，旧 Confirmation 必须同步失效；Opinion 证明仍保留用于 If-Match。
 */
export function invalidateWorkbenchReviewConfirmation(
  state: WorkbenchReviewState,
): WorkbenchReviewState {
  if (!state.confirmation && state.activeConfirmationRequestId == null) return state;
  return {
    ...state,
    confirmation: null,
    activeConfirmationRequestId: null,
  };
}

export function reviewModifyConfirmationId(
  state: WorkbenchReviewState,
  phase: WorkbenchPhase,
  runMode: WorkbenchRunMode,
): string | null {
  if (
    state.scope.phase !== 'REVIEW_REFACTOR' ||
    phase !== state.scope.phase ||
    runMode !== 'MODIFY_WORKSPACE' ||
    !validOpinion(state.opinion) ||
    !validConfirmation(state.confirmation)
  ) {
    return null;
  }
  return state.confirmation.opinionVersion === state.opinion.version &&
    state.confirmation.opinionHash === state.opinion.contentHash
    ? state.confirmation.confirmationId
    : null;
}

function requestToken(
  state: WorkbenchReviewState,
  kind: WorkbenchReviewRequestKind,
  requestId: number,
  opinion: WorkbenchReviewOpinionProof | null,
): WorkbenchReviewRequestToken {
  return {
    kind,
    requestId,
    scopeRevision: state.scopeRevision,
    scope: { ...state.scope },
    opinionVersion: opinion?.version ?? null,
    opinionHash: opinion?.contentHash ?? null,
  };
}

function currentRequest(
  state: WorkbenchReviewState,
  token: WorkbenchReviewRequestToken,
  kind: WorkbenchReviewRequestKind,
): boolean {
  if (token.kind !== kind || token.scopeRevision !== state.scopeRevision || !sameScope(token.scope, state.scope)) {
    return false;
  }
  return kind === 'OPINION'
    ? state.activeOpinionRequestId === token.requestId
    : state.activeConfirmationRequestId === token.requestId;
}

function confirmationMatches(
  currentOpinion: WorkbenchReviewOpinionProof | null,
  token: WorkbenchReviewRequestToken,
  confirmation: WorkbenchReviewConfirmationProof,
): boolean {
  return (
    validOpinion(currentOpinion) &&
    token.opinionVersion === currentOpinion.version &&
    token.opinionHash === currentOpinion.contentHash &&
    confirmation.opinionVersion === currentOpinion.version &&
    confirmation.opinionHash === currentOpinion.contentHash
  );
}

function projectOpinion(value: unknown): WorkbenchReviewOpinionProof | null {
  if (!isRecord(value) || !positiveInteger(value.version) || !sha256(value.contentHash)) {
    return null;
  }
  return { version: value.version, contentHash: value.contentHash };
}

function projectConfirmation(value: unknown): WorkbenchReviewConfirmationProof | null {
  if (
    !isRecord(value) ||
    !boundedIdentifier(value.confirmationId) ||
    !positiveInteger(value.opinionVersion) ||
    !sha256(value.opinionHash)
  ) {
    return null;
  }
  return {
    confirmationId: value.confirmationId.trim(),
    opinionVersion: value.opinionVersion,
    opinionHash: value.opinionHash,
  };
}

function validOpinion(value: WorkbenchReviewOpinionProof | null): value is WorkbenchReviewOpinionProof {
  return projectOpinion(value) !== null;
}

function validConfirmation(value: WorkbenchReviewConfirmationProof | null): value is WorkbenchReviewConfirmationProof {
  return projectConfirmation(value) !== null;
}

function invalidOpinionResponse(): WorkbenchReviewSafeError {
  return {
    code: 'REVIEW_OPINION_INVALID_RESPONSE',
    message: 'Review opinion could not be verified',
  };
}

function invalidConfirmationResponse(): WorkbenchReviewSafeError {
  return {
    code: 'REVIEW_CONFIRMATION_INVALID_RESPONSE',
    message: 'Review confirmation could not be verified',
  };
}

function safeFailure(failure: unknown): WorkbenchReviewSafeError {
  const status = isRecord(failure) && typeof failure.status === 'number' ? failure.status : null;
  switch (status) {
    case 401:
      return { code: 'AUTHENTICATION_REQUIRED', message: 'Authentication is required' };
    case 403:
      return { code: 'ACCESS_DENIED', message: 'Access is denied' };
    case 404:
      return { code: 'REVIEW_NOT_FOUND', message: 'Review state was not found' };
    case 409:
      return {
        code: 'REVIEW_CONFLICT',
        message: 'Review state changed; reload it before retrying',
      };
    case 422:
      return { code: 'REVIEW_REQUEST_INVALID', message: 'Review request is invalid' };
    case 503:
      return { code: 'REVIEW_UNAVAILABLE', message: 'Review service is unavailable' };
    default:
      return { code: 'REVIEW_REQUEST_FAILED', message: 'Review request failed' };
  }
}

function normalizeScope(scope: WorkbenchReviewScope): WorkbenchReviewScope {
  if (
    !scope ||
    !boundedIdentifier(scope.ownerId) ||
    !boundedIdentifier(scope.workbenchId) ||
    !isWorkbenchPhase(scope.phase)
  ) {
    throw new Error('Review scope is invalid');
  }
  return {
    ownerId: scope.ownerId.trim(),
    workbenchId: scope.workbenchId.trim(),
    phase: scope.phase,
  };
}

function sameScope(left: WorkbenchReviewScope, right: WorkbenchReviewScope): boolean {
  return left.ownerId === right.ownerId && left.workbenchId === right.workbenchId && left.phase === right.phase;
}

function boundedIdentifier(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    Boolean(value.trim()) &&
    value.trim().length <= IDENTIFIER_MAX_LENGTH &&
    !/[\u0000-\u001F\u007F]/.test(value.trim())
  );
}

function positiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}

function sha256(value: unknown): value is string {
  return typeof value === 'string' && SHA_256.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
