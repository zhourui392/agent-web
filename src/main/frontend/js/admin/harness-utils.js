/**
 * Harness Capability Snapshot 管理页纯展示函数 (ES module)。
 *
 * 浏览器挂 window.HarnessAdminUtils (兼容未改的消费者); Node/Vitest 走 ES import。
 *
 * @author zhourui(V33215020)
 */
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

/**
 * Run 级状态（HarnessRunStatus）到列表展示的映射。
 *
 * 注意与 stageStatuses 是两套枚举：Run 没有 RUNNING，进行中叫 ACTIVE；
 * 列表接口 (GET /api/harness/runs) 只返回 Run 级 summary，不带 stages。
 */
const runStatuses = {
  DRAFT: { label: '草稿', type: 'info', bucket: 'running', dot: 'is-running' },
  ACTIVE: { label: '进行中', type: '', bucket: 'running', dot: 'is-running' },
  WAITING_INPUT: { label: '等待输入', type: 'warning', bucket: 'waiting', dot: 'is-waiting' },
  WAITING_APPROVAL: { label: '等待批准', type: 'warning', bucket: 'waiting', dot: 'is-waiting' },
  CANCELLING: { label: '取消中', type: 'warning', bucket: 'running', dot: 'is-running' },
  ROLLING_BACK: { label: '回滚中', type: 'warning', bucket: 'running', dot: 'is-running' },
  ROLLED_BACK: { label: '已回滚', type: 'info', bucket: 'done', dot: 'is-invalidated' },
  COMPLETED: { label: '已完成', type: 'success', bucket: 'done', dot: 'is-passed' },
  FAILED: { label: '失败', type: 'danger', bucket: 'failed', dot: 'is-failed' },
  CANCELLED: { label: '已取消', type: 'info', bucket: 'failed', dot: 'is-invalidated' }
};

export function selectionReasonLabel(reason) {
  return selectionReasons[reason] || reason || '-';
}

export function rejectionReasonLabel(reason) {
  return rejectionReasons[reason] || reason || '-';
}

export function capabilityDecisionLabel(authorized, reason) {
  if (authorized && reason === 'EXPLICITLY_GRANTED') {
    return '已显式授权';
  }
  if (!authorized && reason === 'NOT_GRANTED') {
    return '未授权';
  }
  return reason || (authorized ? '已授权' : '已拒绝');
}

export function shortHash(hash) {
  if (!hash) {
    return '-';
  }
  const text = String(hash);
  return text.length > 12 ? text.slice(0, 12) + '…' : text;
}

export function stageStatusMeta(status) {
  return stageStatuses[status] || { label: status || '未知', type: 'info' };
}

export function runStatusMeta(status) {
  return runStatuses[status] || { label: status || '未知', type: 'info', bucket: 'running', dot: '' };
}

/** Run 归到列表筛选桶：all / running / waiting / done / failed。 */
export function runBucket(run) {
  return runStatusMeta(String((run && run.status) || '').toUpperCase()).bucket;
}

/** Run 列表条目左侧状态点的颜色类。 */
export function runStageDotClass(run) {
  return runStatusMeta(String((run && run.status) || '').toUpperCase()).dot;
}

/** 从历史 Run 的工作目录去重收集，供新建 Run 的目录联想使用。 */
export function workingDirSuggestions(runs) {
  const values = Array.isArray(runs) ? runs : [];
  const unique = new Set();
  values.forEach(function (run) {
    if (run && run.workingDir) {
      unique.add(run.workingDir);
    }
  });
  return Array.from(unique);
}

export function currentAttempt(stage) {
  const attempts = stage && Array.isArray(stage.attempts) ? stage.attempts : [];
  return attempts.reduce(function (current, attempt) {
    if (!current || Number(attempt.number) > Number(current.number)) {
      return attempt;
    }
    return current;
  }, null);
}

export function gateFailureSummary(gates, stage, attempt) {
  return (Array.isArray(gates) ? gates : [])
    .filter(function (gate) {
      return gate.stage === stage && Number(gate.attempt) === Number(attempt) && gate.passed === false;
    })
    .map(function (gate) {
      return (gate.rule || '未知门禁') + '：' + (gate.reason || '未提供失败原因');
    });
}

export function validApproval(approvals, stage, attempt, approvalType) {
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

export function canStartDeployment(run) {
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

export function runtimeBusy(runtime) {
  return Boolean(runtime && ['PREPARED', 'STARTING', 'RUNNING', 'CANCEL_REQUESTED']
    .includes(runtime.status));
}

export function canSendConversation(stage, runtime) {
  if (!stage || runtimeBusy(runtime)) {
    return false;
  }
  return ['PENDING', 'RUNNING', 'WAITING_APPROVAL', 'PASSED', 'FAILED', 'INVALIDATED']
    .includes(stage.status);
}

export function reconciliationMessage(status) {
  if (status === 'RECONCILIATION_REQUIRED') {
    return '部署结果不确定，必须由管理员人工对账；系统不会自动重放部署。';
  }
  if (status === 'LOST') {
    return 'Runtime 在服务重启后失去跟踪，请创建新 Attempt 重试；系统不会自动重放。';
  }
  return '';
}

export function artifactDownloadUrl(runId, artifactId) {
  return '/api/harness/runs/' + encodeURIComponent(runId)
    + '/artifacts/' + encodeURIComponent(artifactId);
}

export function harnessApiAvailable(status, body) {
  if (Number(status) !== 404) {
    return true;
  }
  const code = body && body.code ? String(body.code) : '';
  return code.indexOf('HARNESS_') === 0 && code !== 'HARNESS_DISABLED';
}

// 浏览器: 挂全局 window.HarnessAdminUtils (兼容未改的消费者)
if (typeof window !== 'undefined') {
  window.HarnessAdminUtils = {
    selectionReasonLabel,
    rejectionReasonLabel,
    capabilityDecisionLabel,
    shortHash,
    stageStatusMeta,
    runStatusMeta,
    runBucket,
    runStageDotClass,
    workingDirSuggestions,
    currentAttempt,
    gateFailureSummary,
    validApproval,
    canStartDeployment,
    runtimeBusy,
    canSendConversation,
    reconciliationMessage,
    artifactDownloadUrl,
    harnessApiAvailable
  };
}