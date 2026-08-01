/**
 * TD-05 Workbench Capability Profile / Override owner-scoped transport client。
 *
 * @author alex
 * @since 2026-08-01
 */
import {
  isWorkbenchPhase,
  type WorkbenchPhase,
} from '../lib/workbench-state.js';

const IDENTIFIER_MAX_LENGTH = 160;
const SOURCE_MAX_LENGTH = 160;
const SUMMARY_MAX_LENGTH = 1000;
const WARNING_MAX_LENGTH = 1000;
const MAXIMUM_CAPABILITIES = 200;
const MAXIMUM_WARNINGS = 100;
const MAXIMUM_ADDITIONAL_RULE_CHARS = 4000;
const SHA_256 = /^[a-f0-9]{64}$/;
const CONTROL_CHARACTER = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/;

const PROFILE_STATUSES = new Set<WorkbenchCapabilityProfileStatus>([
  'AVAILABLE',
  'DEGRADED',
  'UNAVAILABLE',
]);

const SAFE_ERROR_CODES = new Set([
  'AUTHENTICATION_REQUIRED',
  'ACCESS_DENIED',
  'WORKBENCH_NOT_FOUND',
  'WORKBENCH_ARCHIVED',
  'WORKBENCH_CAPABILITY_OVERRIDE_NOT_FOUND',
  'WORKBENCH_CAPABILITY_OVERRIDE_ALREADY_EXISTS',
  'WORKBENCH_CAPABILITY_VERSION_CONFLICT',
  'WORKBENCH_CAPABILITY_ESCALATION_DENIED',
  'WORKBENCH_CAPABILITY_REQUIRED_UNAVAILABLE',
  'WORKBENCH_RUNTIME_CAPABILITY_INCOMPATIBLE',
  'WORKBENCH_PROFILE_UNAVAILABLE',
  'WORKBENCH_CAPABILITY_REQUEST_INVALID',
]);

export type WorkbenchCapabilityFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export type WorkbenchCapabilityProfileStatus =
  | 'AVAILABLE'
  | 'DEGRADED'
  | 'UNAVAILABLE';

export interface WorkbenchCapabilityItem {
  id: string;
  required: boolean;
  selected: boolean;
  source: string;
  summary: string | null;
}

export interface WorkbenchCapabilityOverrideInput {
  optionalSkillIds: string[];
  optionalMcpServerIds: string[];
  additionalRule: string;
}

export interface WorkbenchEffectiveCapabilityProfile
  extends WorkbenchCapabilityOverrideInput {
  phase: WorkbenchPhase;
  status: WorkbenchCapabilityProfileStatus;
  profileId: string;
  profileVersion: string;
  profileHash: string;
  rules: WorkbenchCapabilityItem[];
  skills: WorkbenchCapabilityItem[];
  mcpServers: WorkbenchCapabilityItem[];
  overrideVersion: number;
  warnings: string[];
  effectiveFrom: 'NEXT_RUN';
  activeRunSnapshotHash: string | null;
}

export interface WorkbenchPhaseCapabilityOverride
  extends WorkbenchCapabilityOverrideInput {
  version: number;
  updatedAt: number;
}

export interface WorkbenchCapabilityMutationResult {
  version: number;
  effectiveFrom: 'NEXT_RUN';
  activeRunSnapshotHash: string | null;
}

export interface WorkbenchCapabilityApiClient {
  getEffectiveProfile(
    workbenchId: string,
    phase: WorkbenchPhase,
  ): Promise<WorkbenchEffectiveCapabilityProfile>;
  getOverride(
    workbenchId: string,
    phase: WorkbenchPhase,
  ): Promise<WorkbenchPhaseCapabilityOverride | null>;
  putOverride(
    workbenchId: string,
    phase: WorkbenchPhase,
    expectedVersion: number,
    override: WorkbenchCapabilityOverrideInput,
  ): Promise<WorkbenchCapabilityMutationResult>;
  deleteOverride(
    workbenchId: string,
    phase: WorkbenchPhase,
    expectedVersion: number,
  ): Promise<WorkbenchCapabilityMutationResult>;
}

export class WorkbenchCapabilityApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string) {
    super('Workbench capability request failed');
    this.name = 'WorkbenchCapabilityApiError';
    this.status = status;
    this.code = code;
    this.stack = undefined;
  }
}

export function createWorkbenchCapabilityApiClient(
  injectedFetch?: WorkbenchCapabilityFetch,
): WorkbenchCapabilityApiClient {
  const execute = injectedFetch ?? ((input, init) => globalThis.fetch(input, init));

  return {
    async getEffectiveProfile(workbenchId, phase) {
      const response = await safeFetch(execute, capabilityUrl(workbenchId, phase, 'profile'), {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      });
      const body = await readBody(response);
      if (!response.ok) throw responseError(response.status, body);
      const profile = effectiveProfile(body);
      if (!profile) throw invalidResponse(response.status);
      return profile;
    },

    async getOverride(workbenchId, phase) {
      const response = await safeFetch(execute, capabilityUrl(workbenchId, phase, 'override'), {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      });
      const body = await readBody(response);
      if (response.status === 404
        && safeErrorCode(body) === 'WORKBENCH_CAPABILITY_OVERRIDE_NOT_FOUND') {
        return null;
      }
      if (!response.ok) throw responseError(response.status, body);
      const current = overrideProjection(body);
      if (!current) throw invalidResponse(response.status);
      return current;
    },

    async putOverride(workbenchId, phase, expectedVersion, value) {
      const version = requireVersion(expectedVersion);
      const request = overrideInput(value);
      const response = await safeFetch(execute, capabilityUrl(workbenchId, phase, 'override'), {
        method: 'PUT',
        credentials: 'same-origin',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'If-Match': String(version),
        },
        body: JSON.stringify(request),
      });
      const body = await readBody(response);
      if (!response.ok) throw responseError(response.status, body);
      const result = mutationProjection(body);
      if (!result) throw invalidResponse(response.status);
      return result;
    },

    async deleteOverride(workbenchId, phase, expectedVersion) {
      const version = requireVersion(expectedVersion);
      const response = await safeFetch(execute, capabilityUrl(workbenchId, phase, 'override'), {
        method: 'DELETE',
        credentials: 'same-origin',
        headers: { Accept: 'application/json', 'If-Match': String(version) },
      });
      const body = await readBody(response);
      if (!response.ok) throw responseError(response.status, body);
      const result = mutationProjection(body);
      if (!result) throw invalidResponse(response.status);
      return result;
    },
  };
}

function capabilityUrl(
  workbenchId: string,
  phase: WorkbenchPhase,
  resource: 'profile' | 'override',
): string {
  const id = pathSegment(workbenchId);
  if (!isWorkbenchPhase(phase)) throw invalidRequest();
  return `/api/workbenches/${id}/phases/${encodeURIComponent(phase)}/capability-${resource}`;
}

function pathSegment(value: unknown): string {
  const id = boundedString(value, IDENTIFIER_MAX_LENGTH);
  if (!id || id === '.' || id === '..') throw invalidRequest();
  return encodeURIComponent(id);
}

function effectiveProfile(value: unknown): WorkbenchEffectiveCapabilityProfile | null {
  const body = record(value);
  if (!body || !isWorkbenchPhase(body.phase)
    || typeof body.status !== 'string'
    || !PROFILE_STATUSES.has(body.status as WorkbenchCapabilityProfileStatus)) {
    return null;
  }
  const profileId = boundedString(body.profileId, IDENTIFIER_MAX_LENGTH);
  const profileVersion = boundedString(body.profileVersion, IDENTIFIER_MAX_LENGTH);
  const profileHash = sha256(body.profileHash);
  const rules = capabilityItems(body.rules);
  const skills = capabilityItems(body.skills);
  const mcpServers = capabilityItems(body.mcpServers);
  const selection = overrideInputOrNull(body);
  const overrideVersion = nonNegativeInteger(body.overrideVersion);
  const warnings = boundedStringList(body.warnings, WARNING_MAX_LENGTH, MAXIMUM_WARNINGS);
  const activeRunSnapshotHash = nullableSha256(body.activeRunSnapshotHash);
  if (!profileId || !profileVersion || !profileHash || !rules || !skills || !mcpServers
    || !selection || overrideVersion == null || !warnings
    || activeRunSnapshotHash === undefined || body.effectiveFrom !== 'NEXT_RUN') {
    return null;
  }
  return {
    phase: body.phase,
    status: body.status as WorkbenchCapabilityProfileStatus,
    profileId,
    profileVersion,
    profileHash,
    rules,
    skills,
    mcpServers,
    ...selection,
    overrideVersion,
    warnings,
    effectiveFrom: 'NEXT_RUN',
    activeRunSnapshotHash,
  };
}

function capabilityItems(value: unknown): WorkbenchCapabilityItem[] | null {
  if (!Array.isArray(value) || value.length > MAXIMUM_CAPABILITIES) return null;
  const result: WorkbenchCapabilityItem[] = [];
  const identifiers = new Set<string>();
  for (const raw of value) {
    const item = record(raw);
    if (!item) return null;
    const id = boundedString(item.id, IDENTIFIER_MAX_LENGTH);
    const source = boundedString(item.source, SOURCE_MAX_LENGTH);
    const summary = nullableBoundedString(item.summary, SUMMARY_MAX_LENGTH);
    if (!id || !source || summary === undefined
      || typeof item.required !== 'boolean' || typeof item.selected !== 'boolean'
      || item.required && !item.selected || !identifiers.add(id)) {
      return null;
    }
    result.push({
      id,
      required: item.required,
      selected: item.selected,
      source,
      summary,
    });
  }
  return result;
}

function overrideProjection(value: unknown): WorkbenchPhaseCapabilityOverride | null {
  const body = record(value);
  if (!body) return null;
  const version = nonNegativeInteger(body.version);
  const updatedAt = nonNegativeInteger(body.updatedAt);
  const selection = overrideInputOrNull(body);
  return version == null || updatedAt == null || !selection
    ? null
    : { version, updatedAt, ...selection };
}

function mutationProjection(value: unknown): WorkbenchCapabilityMutationResult | null {
  const body = record(value);
  if (!body || body.effectiveFrom !== 'NEXT_RUN') return null;
  const version = nonNegativeInteger(body.version);
  const activeRunSnapshotHash = nullableSha256(body.activeRunSnapshotHash);
  return version == null || activeRunSnapshotHash === undefined
    ? null
    : { version, effectiveFrom: 'NEXT_RUN', activeRunSnapshotHash };
}

function overrideInput(value: unknown): WorkbenchCapabilityOverrideInput {
  const projected = overrideInputOrNull(value);
  if (!projected) throw invalidRequest();
  return projected;
}

function overrideInputOrNull(value: unknown): WorkbenchCapabilityOverrideInput | null {
  const body = record(value);
  if (!body || typeof body.additionalRule !== 'string'
    || body.additionalRule.length > MAXIMUM_ADDITIONAL_RULE_CHARS
    || CONTROL_CHARACTER.test(body.additionalRule)) {
    return null;
  }
  const optionalSkillIds = identifierList(body.optionalSkillIds);
  const optionalMcpServerIds = identifierList(body.optionalMcpServerIds);
  if (!optionalSkillIds || !optionalMcpServerIds) return null;
  return {
    optionalSkillIds,
    optionalMcpServerIds,
    additionalRule: body.additionalRule,
  };
}

function identifierList(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length > MAXIMUM_CAPABILITIES) return null;
  const result: string[] = [];
  const unique = new Set<string>();
  for (const raw of value) {
    const id = boundedString(raw, IDENTIFIER_MAX_LENGTH);
    if (!id || !unique.add(id)) return null;
    result.push(id);
  }
  return result;
}

function boundedStringList(
  value: unknown,
  maximumChars: number,
  maximumItems: number,
): string[] | null {
  if (!Array.isArray(value) || value.length > maximumItems) return null;
  const result: string[] = [];
  for (const raw of value) {
    const item = boundedString(raw, maximumChars);
    if (!item) return null;
    result.push(item);
  }
  return result;
}

function requireVersion(value: unknown): number {
  const version = nonNegativeInteger(value);
  if (version == null) throw invalidRequest();
  return version;
}

function nonNegativeInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
    ? value
    : null;
}

function boundedString(value: unknown, maximum: number): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim();
  return normalized && normalized.length <= maximum && !CONTROL_CHARACTER.test(normalized)
    ? normalized
    : null;
}

function nullableBoundedString(value: unknown, maximum: number): string | null | undefined {
  return value === null ? null : boundedString(value, maximum) ?? undefined;
}

function sha256(value: unknown): string | null {
  return typeof value === 'string' && SHA_256.test(value) ? value : null;
}

function nullableSha256(value: unknown): string | null | undefined {
  return value === null ? null : sha256(value) ?? undefined;
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

async function safeFetch(
  execute: WorkbenchCapabilityFetch,
  url: string,
  init: RequestInit,
): Promise<Response> {
  try {
    return await execute(url, init);
  } catch {
    throw new WorkbenchCapabilityApiError(0, 'WORKBENCH_CAPABILITY_NETWORK_ERROR');
  }
}

async function readBody(response: Response): Promise<unknown> {
  let text: string;
  try {
    text = await response.text();
  } catch {
    throw invalidResponse(response.status);
  }
  if (!text) return null;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

function safeErrorCode(value: unknown): string | null {
  const body = record(value);
  return body && typeof body.code === 'string' && SAFE_ERROR_CODES.has(body.code)
    ? body.code
    : null;
}

function responseError(status: number, body: unknown): WorkbenchCapabilityApiError {
  const fallback = status === 401
    ? 'AUTHENTICATION_REQUIRED'
    : status === 403
      ? 'ACCESS_DENIED'
      : status === 404
        ? 'WORKBENCH_NOT_FOUND'
        : status === 409
          ? 'WORKBENCH_CAPABILITY_VERSION_CONFLICT'
          : status === 503
            ? 'WORKBENCH_PROFILE_UNAVAILABLE'
            : 'WORKBENCH_CAPABILITY_REQUEST_FAILED';
  return new WorkbenchCapabilityApiError(status, safeErrorCode(body) ?? fallback);
}

function invalidRequest(): WorkbenchCapabilityApiError {
  return new WorkbenchCapabilityApiError(400, 'WORKBENCH_CAPABILITY_REQUEST_INVALID');
}

function invalidResponse(status: number): WorkbenchCapabilityApiError {
  return new WorkbenchCapabilityApiError(status, 'WORKBENCH_CAPABILITY_RESPONSE_INVALID');
}
