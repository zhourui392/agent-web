/**
 * Chat 提交的 recall 取值：功能开关（ragEnabled，由 /api/refinery/chunks 探测决定，
 * 关闭时开关隐藏）与用户偏好（ragRecall，localStorage 持久化）共同决定。
 * 隐藏开关时不得沿用本地偏好，否则后端公共 Runtime 在未配置 Profile 时 fail-closed。
 */
export function resolveRecallFlag(ragEnabled: boolean, ragRecall: boolean): boolean {
  return !!(ragEnabled && ragRecall);
}
