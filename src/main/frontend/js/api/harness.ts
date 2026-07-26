/** Harness 域 API */
import { fetchJson, postJson, deleteJson, query } from './client';

const BASE = '/api/harness';

export interface HarnessRunSummary {
  runId: string;
  status: string;
  title: string;
  workingDir: string;
  requirement: string;
  createdAt: string;
}

export interface HarnessStageView {
  stage: string;
  status: string;
  attempts: Array<{ number: number }>;
  deterministicGates: string[];
}

export interface HarnessRunView {
  runId: string;
  status: string;
  title: string;
  workingDir: string;
  requirement: string;
  stages: HarnessStageView[];
  artifacts: Array<{ artifactType: string; stage: string; attempt: number }>;
  events: Array<{ type: string; stage: string; detail: string; occurredAt: string }>;
}

export interface DeploymentExecutionView {
  executionId: string;
  runId: string;
  attemptNumber: number;
  status: string;
  templateId: string;
  templateVersion: string;
  templateHash: string;
  failureReason: string | null;
  approvedInputBaselineHash: string;
  preparedAt: number;
  startedAt: number;
  finishedAt: number;
}

export interface TraceabilityRow {
  requirementId: string;
  acceptanceCriteriaId: string;
  acceptanceDescription: string;
  verification: string;
  designRef: string;
  testRef: string;
  implementationRef: string;
  deploymentPassed: boolean;
}

export interface HarnessReport {
  traceabilityComplete: boolean;
  traceability: TraceabilityRow[];
}

export function listRuns() {
  return fetchJson<any[]>(`${BASE}/runs`);
}
export function getRun(runId: string) {
  return fetchJson<any>(`${BASE}/runs/${encodeURIComponent(runId)}`);
}
export function getRunEvents(runId: string) {
  return fetchJson<any[]>(`${BASE}/runs/${encodeURIComponent(runId)}/events`);
}
export function createRun(payload: any) {
  return postJson<any>(`${BASE}/runs`, payload);
}
export function cancelRun(runId: string, reason: string) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/cancel`, { reason });
}
export function getReport(runId: string) {
  return fetchJson<any>(`${BASE}/runs/${encodeURIComponent(runId)}/report`);
}

// Stages
export function startStage(runId: string, stage: string, idempotencyKey: string) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/stages/${stage}/start`, { idempotencyKey });
}
export function retryStage(runId: string, stage: string, idempotencyKey: string) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/stages/${stage}/retry`, { idempotencyKey });
}
export function resolveSnapshot(runId: string, stage: string, payload: any) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/stages/${stage}/capability-snapshot`, payload);
}
export function launchRuntime(runId: string, stage: string, payload: any) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/stages/${stage}/runtime`, payload);
}
export function evaluateGate(runId: string, stage: string, rule: string) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/stages/${stage}/gates`, { rule });
}
export function requestApproval(runId: string, stage: string) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/stages/${stage}/request-approval`);
}
export function approveStage(runId: string, stage: string, payload: any) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/stages/${stage}/approve`, payload);
}
export function rejectStage(runId: string, stage: string, payload: any) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/stages/${stage}/reject`, payload);
}

// Conversation
export function getConversation(runId: string) {
  return fetchJson<any[]>(`${BASE}/runs/${encodeURIComponent(runId)}/conversation`);
}
export function sendConversation(runId: string, stage: string, payload: any) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/stages/${stage}/conversation`, payload);
}

// Questions
export function askQuestion(runId: string, stage: string, payload: any) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/stages/${stage}/questions`, payload);
}
export function answerQuestion(runId: string, questionId: string, answer: string) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/questions/${encodeURIComponent(questionId)}/answer`, { answer });
}

// Deployment
export function approveDeploymentReadiness(runId: string, reason: string) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/stages/DEPLOYMENT/approval`, { reason });
}
export function getDeploymentReadiness(runId: string) {
  return fetchJson<any>(`${BASE}/runs/${encodeURIComponent(runId)}/stages/DEPLOYMENT/deployment-readiness`);
}
export function startDeployment(runId: string, payload: any) {
  return postJson(`${BASE}/runs/${encodeURIComponent(runId)}/deployments`, payload);
}
export function listDeployments(runId: string) {
  return fetchJson<any[]>(`${BASE}/runs/${encodeURIComponent(runId)}/deployments`);
}

// Artifacts
export function getArtifactContent(runId: string, artifactId: string) {
  return fetchJson<any>(`${BASE}/runs/${encodeURIComponent(runId)}/artifacts/${encodeURIComponent(artifactId)}`);
}