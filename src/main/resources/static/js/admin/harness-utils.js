/**
 * Harness Capability Snapshot 管理页纯展示函数。
 *
 * @author zhourui(V33215020)
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  }
  root.HarnessAdminUtils = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  const selectionReasons = {
    STAGE_DEFAULT: '阶段默认',
    USER_EXPLICIT: '用户显式选择',
    TECH_TAG: '技术标签匹配',
    REQUIRED_DEPENDENCY: '必需依赖'
  };

  const rejectionReasons = {
    WORKSPACE_NOT_APPROVED: '工作区 Skill 未批准',
    STAGE_INCOMPATIBLE: '阶段不兼容',
    RUNTIME_INCOMPATIBLE: 'Runtime 不兼容',
    TECH_TAG_NOT_MATCHED: '技术标签未匹配'
  };

  function selectionReasonLabel(reason) {
    return selectionReasons[reason] || reason || '-';
  }

  function rejectionReasonLabel(reason) {
    return rejectionReasons[reason] || reason || '-';
  }

  function capabilityDecisionLabel(authorized, reason) {
    if (authorized && reason === 'EXPLICITLY_GRANTED') {
      return '已显式授权';
    }
    if (!authorized && reason === 'NOT_GRANTED') {
      return '未授权';
    }
    return reason || (authorized ? '已授权' : '已拒绝');
  }

  function shortHash(hash) {
    if (!hash) {
      return '-';
    }
    const text = String(hash);
    return text.length > 12 ? text.slice(0, 12) + '…' : text;
  }

  return {
    selectionReasonLabel,
    rejectionReasonLabel,
    capabilityDecisionLabel,
    shortHash
  };
});
