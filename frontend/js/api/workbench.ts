/**
 * Workbench Owner API client。只暴露已落地的 Shell 端点。
 *
 * @author alex
 * @since 2026-08-01
 */
import { fetchJson, postJson, query } from './client';
import type { WorkbenchRunMode } from '../lib/workbench-run-state';
import type { WorkbenchPhase, WorkbenchPhaseStatus } from '../lib/workbench-state';

export interface WorkspaceRepositoryCandidate {
  repositoryKey: string;
  relativePath: string;
  branch: string | null;
  headShort: string | null;
  clean: boolean;
  selectedByDefault: boolean;
  primarySuggested: boolean;
  warnings: string[];
}

export interface WorkspaceInspection {
  workspaceRootDisplay: string;
  inspectionToken: string;
  source: string;
  repositories: WorkspaceRepositoryCandidate[];
  warnings: string[];
}

export interface CreateWorkbenchRequest {
  title: string;
  originalGoal: string;
  agentType: string;
  environment: string;
  workspaceRoot: string;
  primaryRepository: string;
  repositories: string[];
}

export interface WorkbenchCreationResponse {
  workbenchId: string;
  status: string;
  version: number;
  replayed: boolean;
}

export interface WorkbenchListQuery {
  status?: string;
  cursorUpdatedAt?: number;
  cursorWorkbenchId?: string;
  limit?: number;
}

export interface WorkbenchListCursor {
  updatedAt: number;
  workbenchId: string;
}

export interface WorkbenchListItem {
  id: string;
  title: string;
  status: string;
  agentType: string;
  environment: string | null;
  primaryRepositoryKey: string;
  repositoryCount: number;
  activeWriteRunId: string | null;
  createdAt: number;
  updatedAt: number;
  version: number;
}

export interface WorkbenchListPage {
  items: WorkbenchListItem[];
  nextCursor: WorkbenchListCursor | null;
}

export interface WorkbenchRepositoryView {
  repositoryKey: string;
  relativePath: string;
  primary: boolean;
}

export interface WorkbenchActiveRunView {
  runId: string;
  runMode: WorkbenchRunMode;
  preparedAt: number;
  reviewConfirmationId: string | null;
  reviewOpinionVersion: number | null;
  reviewOpinionHash: string | null;
}

export interface WorkbenchPhaseView {
  phase: WorkbenchPhase;
  phaseOrder: number;
  status: WorkbenchPhaseStatus;
  conversationGeneration: number;
  currentConversation: { sessionId: string; generation: number } | null;
  conversationHistory: Array<{ sessionId: string; generation: number }>;
  activeRun: WorkbenchActiveRunView | null;
  lastActivityAt: number | null;
  completedAt: number | null;
}

export interface WorkbenchDetail {
  id: string;
  title: string;
  originalGoal: string;
  agentType: string;
  environment: string | null;
  activeWriteRunId: string | null;
  status: string;
  createdAt: number;
  updatedAt: number;
  version: number;
  repositoryScope: {
    scopeHash: string;
    primaryRepositoryKey: string;
    repositories: WorkbenchRepositoryView[];
  };
  creationSnapshot: {
    snapshotId: string;
    topologyHash: string;
    stateHash: string;
    repositoryCount: number;
  };
  phases: WorkbenchPhaseView[];
}

export interface WorkbenchPhaseLifecycleResponse {
  workbenchId: string;
  phase: WorkbenchPhase;
  phaseStatus: WorkbenchPhaseStatus;
  conversationId: string | null;
  conversationGeneration: number;
  workbenchVersion: number;
  changed: boolean;
}

export function inspectWorkspace(workspaceRoot: string): Promise<WorkspaceInspection> {
  return postJson<WorkspaceInspection>('/api/workbench/workspaces/inspect', { workspaceRoot });
}

export function createWorkbench(
  request: CreateWorkbenchRequest,
  idempotencyKey: string,
): Promise<WorkbenchCreationResponse> {
  if (!idempotencyKey || !idempotencyKey.trim()) {
    return Promise.reject(new Error('Idempotency-Key is required'));
  }
  return fetchJson<WorkbenchCreationResponse>('/api/workbenches', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify(request),
  });
}

export function listWorkbenches(filters: WorkbenchListQuery = {}): Promise<WorkbenchListPage> {
  return fetchJson<WorkbenchListPage>(
    `/api/workbenches${query({
      status: filters.status,
      cursorUpdatedAt: filters.cursorUpdatedAt,
      cursorWorkbenchId: filters.cursorWorkbenchId,
      limit: filters.limit,
    })}`,
  );
}

export function getWorkbench(workbenchId: string): Promise<WorkbenchDetail> {
  return fetchJson<WorkbenchDetail>(`/api/workbenches/${encodeURIComponent(workbenchId)}`);
}

function mutatePhase(
  workbenchId: string,
  phase: WorkbenchPhase,
  action: 'complete' | 'reopen',
  expectedVersion: number,
): Promise<WorkbenchPhaseLifecycleResponse> {
  const encodedWorkbenchId = encodeURIComponent(workbenchId);
  const encodedPhase = encodeURIComponent(phase);
  return fetchJson<WorkbenchPhaseLifecycleResponse>(
    `/api/workbenches/${encodedWorkbenchId}/phases/${encodedPhase}/${action}`,
    {
      method: 'POST',
      headers: { 'If-Match': String(expectedVersion) },
    },
  );
}

export function completeWorkbenchPhase(
  workbenchId: string,
  phase: WorkbenchPhase,
  expectedVersion: number,
): Promise<WorkbenchPhaseLifecycleResponse> {
  return mutatePhase(workbenchId, phase, 'complete', expectedVersion);
}

export function reopenWorkbenchPhase(
  workbenchId: string,
  phase: WorkbenchPhase,
  expectedVersion: number,
): Promise<WorkbenchPhaseLifecycleResponse> {
  return mutatePhase(workbenchId, phase, 'reopen', expectedVersion);
}
