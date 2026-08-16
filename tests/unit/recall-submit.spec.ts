import { describe, it, expect } from 'vitest';
import { resolveRecallFlag } from '../../frontend/js/lib/recall-submit';

/**
 * 回归: refinery 总开关关闭时 /api/refinery/chunks 404, App.vue 隐藏 RAG召回 开关,
 * 但 chat-panel 的 ragRecall 偏好默认 true(localStorage 无记录视为开),
 * 导致提交仍带 recall=true, 被公共 Runtime fail-closed 拒绝("does not yet support recall")。
 * 提交值必须由「功能可见(ragEnabled)」与「用户偏好(ragRecall)」共同决定。
 */
describe('resolveRecallFlag', () => {
  it('开关隐藏(功能未启用)时无论本地偏好如何都不提交 recall', () => {
    expect(resolveRecallFlag(false, true)).toBe(false);
    expect(resolveRecallFlag(false, false)).toBe(false);
  });

  it('开关可见时按用户偏好提交', () => {
    expect(resolveRecallFlag(true, true)).toBe(true);
    expect(resolveRecallFlag(true, false)).toBe(false);
  });
});
