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

  const stageStatuses = {
    PENDING: { label: '未开始', type: 'info' },
    RUNNING: { label: '运行中', type: '' },
    WAITING_INPUT: { label: '等待输入', type: 'warning' },
    WAITING_APPROVAL: { label: '等待批准', type: 'warning' },
    CANCELLING: { label: '取消中', type: 'warning' },
    PASSED: { label: '已通过', type: 'success' },
    FAILED: { label: '失败', type: 'danger' },
    INVALIDATED: { label: '已失效', type: 'info' },
    CANCELLED: { label: '已取消', type: 'info' }
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

  function stageStatusMeta(status) {
    return stageStatuses[status] || { label: status || '未知', type: 'info' };
  }

  function currentAttempt(stage) {
    const attempts = stage && Array.isArray(stage.attempts) ? stage.attempts : [];
    return attempts.reduce(function (current, attempt) {
      if (!current || Number(attempt.number) > Number(current.number)) {
        return attempt;
      }
      return current;
    }, null);
  }

  function gateFailureSummary(gates, stage, attempt) {
    return (Array.isArray(gates) ? gates : [])
      .filter(function (gate) {
        return gate.stage === stage && Number(gate.attempt) === Number(attempt) && gate.passed === false;
      })
      .map(function (gate) {
        return (gate.rule || '未知门禁') + '：' + (gate.reason || '未提供失败原因');
      });
  }

  function validApproval(approvals, stage, attempt, approvalType) {
    const values = Array.isArray(approvals) ? approvals : [];
    for (let index = values.length - 1; index >= 0; index -= 1) {
      const approval = values[index];
      const approvedDecision = !approval.decision || approval.decision === 'APPROVED';
      if (approval.stage === stage
          && Number(approval.attempt) === Number(attempt)
          && approval.valid === true
          && approvedDecision
          && (!approvalType || approval.approvalType === approvalType)) {
        return approval;
      }
    }
    return null;
  }

  function canStartDeployment(run) {
    if (!run || String(run.environment || '').toLowerCase() !== 'local') {
      return false;
    }
    const stages = Array.isArray(run.stages) ? run.stages : [];
    const deployment = stages.find(function (stage) {
      return stage.stage === 'DEPLOYMENT';
    });
    const attempt = currentAttempt(deployment);
    return Boolean(deployment
      && deployment.status === 'RUNNING'
      && attempt
      && validApproval(run.approvals, 'DEPLOYMENT', attempt.number, 'LOCAL_DEPLOY'));
  }

  function reconciliationMessage(status) {
    if (status === 'RECONCILIATION_REQUIRED') {
      return '部署结果不确定，必须由管理员人工对账；系统不会自动重放部署。';
    }
    if (status === 'LOST') {
      return 'Runtime 在服务重启后失去跟踪，请创建新 Attempt 重试；系统不会自动重放。';
    }
    return '';
  }

  function artifactDownloadUrl(runId, artifactId) {
    return '/api/harness/runs/' + encodeURIComponent(runId)
      + '/artifacts/' + encodeURIComponent(artifactId);
  }

  function harnessApiAvailable(status, body) {
    if (Number(status) !== 404) {
      return true;
    }
    const code = body && body.code ? String(body.code) : '';
    return code.indexOf('HARNESS_') === 0 && code !== 'HARNESS_DISABLED';
  }

  return {
    selectionReasonLabel,
    rejectionReasonLabel,
    capabilityDecisionLabel,
    shortHash,
    stageStatusMeta,
    currentAttempt,
    gateFailureSummary,
    validApproval,
    canStartDeployment,
    reconciliationMessage,
    artifactDownloadUrl,
    harnessApiAvailable
  };
});
