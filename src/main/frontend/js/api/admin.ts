/** Admin 域 API */
import { fetchJson, postJson, deleteJson, putJson, query } from './client';

// Users
export function listUsers() {
  return fetchJson<any[]>('/api/admin-users');
}
export function createUser(username: string, password: string, role: string) {
  return postJson('/api/admin-users', { username, password, role });
}
export function updateUserPassword(userId: string, newPassword: string) {
  return putJson(`/api/admin-users/${encodeURIComponent(userId)}/password`, { newPassword });
}
export function updateUserRole(userId: string, role: string) {
  return putJson(`/api/admin-users/${encodeURIComponent(userId)}/role`, { role });
}
export function deleteUser(userId: string) {
  return deleteJson(`/api/admin-users/${encodeURIComponent(userId)}`);
}

// Metrics
export function getMetricsOverview() {
  return fetchJson<any>('/api/metrics/overview');
}
export function getMetricsTrend(days: number) {
  return fetchJson<any[]>(`/api/metrics/trend${query({ days })}`);
}
export function getMetricsConversations(params: Record<string, string | number>) {
  return fetchJson<any>(`/api/metrics/conversations${query(params)}`);
}

// Settings
export function getSettings() {
  return fetchJson<any>('/api/admin-settings');
}
export function updateSettings(settings: Record<string, unknown>) {
  return postJson('/api/admin-settings', settings);
}

// Workflows
export function listWorkflows() {
  return fetchJson<any[]>('/api/admin-workflows');
}
export function createWorkflow(workflow: any) {
  return postJson('/api/admin-workflows', workflow);
}
export function updateWorkflow(id: string, workflow: any) {
  return putJson(`/api/admin-workflows/${encodeURIComponent(id)}`, workflow);
}
export function deleteWorkflow(id: string) {
  return deleteJson(`/api/admin-workflows/${encodeURIComponent(id)}`);
}
export function runWorkflow(id: string) {
  return postJson(`/api/admin-workflows/${encodeURIComponent(id)}/run`);
}
export function listWorkflowExecutions(params?: Record<string, string | number>) {
  return fetchJson<any>(`/api/admin-workflow-executions${query(params || {})}`);
}

// Recall / Refinery
export function listRecallChunks(params: Record<string, string | number>) {
  return fetchJson<any>(`/api/refinery/chunks${query({ page: 1, size: 20, ...params })}`);
}
export function deleteRecallChunk(chunkId: string) {
  return deleteJson(`/api/refinery/chunks/${encodeURIComponent(chunkId)}`);
}