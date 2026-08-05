/**
 * Workbench Owner Stage-only API client。
 *
 * @author alex
 * @since 2026-08-05
 */
import { fetchJson, postJson, query } from './client';
import type { WorkbenchRunMode } from '../lib/workbench-run-state';
import type { WorkbenchStageStatus } from '../lib/workbench-state';

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
  stageDefinitionIdentifiers: string[];
  expectedStageCatalogVersion: number;
}

export interface SelectableWorkbenchStageDefinition {
  definitionIdentifier: string;
  publishedRevision: number;
  displayName: string;
  description: string;
  sequenceNumber: number;
  definitionHash: string;
}

export interface SelectableWorkbenchStageCatalog {
  stageCatalogVersion: number;
  stages: SelectableWorkbenchStageDefinition[];
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
}

export interface WorkbenchStageConversationView {
  sessionId: string;
  generation: number;
  createdAt?: number;
  retiredAt?: number | null;
}

export interface WorkbenchStageView {
  stageInstanceIdentifier: string;
  definitionIdentifier: string;
  definitionRevision: number;
  definitionHash: string;
  snapshotHash: string;
  sequenceNumber: number;
  displayName: string;
  description: string;
  allowedRunModes: WorkbenchRunMode[];
  status: WorkbenchStageStatus;
  conversationGeneration: number;
  currentConversation: WorkbenchStageConversationView | null;
  conversationHistory: WorkbenchStageConversationView[];
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
    workspaceRoot: string;
    repositories: WorkbenchRepositoryView[];
  };
  creationSnapshot: {
    snapshotId: string;
    topologyHash: string;
    stateHash: string;
    repositoryCount: number;
  };
  stages: WorkbenchStageView[];
}

export interface WorkbenchStageLifecycleResponse {
  workbenchId: string;
  stageInstanceIdentifier: string;
  definitionIdentifier: string;
  stageStatus: WorkbenchStageStatus;
  conversationId: string | null;
  conversationGeneration: number;
  workbenchVersion: number;
  changed: boolean;
}

export function inspectWorkspace(workspaceRoot: string): Promise<WorkspaceInspection> {
  return postJson<WorkspaceInspection>('/api/workbench/workspaces/inspect', { workspaceRoot });
}

export function getSelectableWorkbenchStages(): Promise<SelectableWorkbenchStageCatalog> {
  return fetchJson<SelectableWorkbenchStageCatalog>('/api/workbench/stage-definitions');
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

function mutateStage(
  workbenchId: string,
  stageInstanceIdentifier: string,
  action: 'complete' | 'reopen',
  expectedVersion: number,
): Promise<WorkbenchStageLifecycleResponse> {
  const encodedWorkbenchId = encodeURIComponent(workbenchId);
  const encodedStageInstanceIdentifier = encodeURIComponent(stageInstanceIdentifier);
  return fetchJson<WorkbenchStageLifecycleResponse>(
    `/api/workbenches/${encodedWorkbenchId}/stages/`
      + `${encodedStageInstanceIdentifier}/${action}`,
    {
      method: 'POST',
      headers: { 'If-Match': String(expectedVersion) },
    },
  );
}

export function completeWorkbenchStage(
  workbenchId: string,
  stageInstanceIdentifier: string,
  expectedVersion: number,
): Promise<WorkbenchStageLifecycleResponse> {
  return mutateStage(
    workbenchId, stageInstanceIdentifier, 'complete', expectedVersion,
  );
}

export function reopenWorkbenchStage(
  workbenchId: string,
  stageInstanceIdentifier: string,
  expectedVersion: number,
): Promise<WorkbenchStageLifecycleResponse> {
  return mutateStage(
    workbenchId, stageInstanceIdentifier, 'reopen', expectedVersion,
  );
}
