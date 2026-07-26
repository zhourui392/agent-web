/** API client 集中入口 */
export { fetchJson, postJson, deleteJson, putJson, query, ApiError } from './client';
export * as chatApi from './chat';
export * as adminApi from './admin';
// harness 域暂不在此收敛：管理台 Harness 页的请求逻辑仍在 admin/composables/useHarness.ts 内，
// 曾经的 ./harness 模块零消费者且路径已与真实路由脱节（/runtime vs /executions 等），故移除。
// 后续拆分 useHarness 时再按真实路由重建，并在同一次改动里接上消费者。