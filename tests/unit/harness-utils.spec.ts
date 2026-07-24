import { describe, expect, it } from 'vitest';
import { createRequire } from 'node:module';

const requireCjs = createRequire(import.meta.url);
const harness = requireCjs('../../src/main/resources/static/js/admin/harness-utils.js') as {
  selectionReasonLabel: (reason: string | null | undefined) => string;
  rejectionReasonLabel: (reason: string | null | undefined) => string;
  capabilityDecisionLabel: (authorized: boolean, reason?: string) => string;
  shortHash: (hash: string | null | undefined) => string;
  stageStatusMeta: (status: string) => { label: string; type: string };
  currentAttempt: (stage: { attempts?: Array<{ number: number }> }) => { number: number } | null;
  gateFailureSummary: (gates: Array<Record<string, unknown>>, stage: string, attempt: number) => string[];
  validApproval: (approvals: Array<Record<string, unknown>>, stage: string, attempt: number,
    approvalType?: string) => Record<string, unknown> | null;
  canStartDeployment: (run: Record<string, unknown>) => boolean;
  reconciliationMessage: (status: string) => string;
  artifactDownloadUrl: (runId: string, artifactId: string) => string;
  harnessApiAvailable: (status: number, body?: { code?: string }) => boolean;
};

describe('Harness snapshot display mappings', () => {
  it('maps deterministic selection reasons without inventing policy', () => {
    expect(harness.selectionReasonLabel('STAGE_DEFAULT')).toBe('阶段默认');
    expect(harness.selectionReasonLabel('USER_EXPLICIT')).toBe('用户显式选择');
    expect(harness.selectionReasonLabel('TECH_TAG')).toBe('技术标签匹配');
    expect(harness.selectionReasonLabel('REQUIRED_DEPENDENCY')).toBe('必需依赖');
    expect(harness.selectionReasonLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('maps rejection and authorization reasons for preview', () => {
    expect(harness.rejectionReasonLabel('WORKSPACE_NOT_APPROVED')).toBe('工作区 Skill 未批准');
    expect(harness.rejectionReasonLabel('STAGE_INCOMPATIBLE')).toBe('阶段不兼容');
    expect(harness.capabilityDecisionLabel(true, 'EXPLICITLY_GRANTED')).toBe('已显式授权');
    expect(harness.capabilityDecisionLabel(false, 'NOT_GRANTED')).toBe('未授权');
  });

  it('shortens hashes only for display while preserving empty placeholder', () => {
    expect(harness.shortHash('a'.repeat(64))).toBe('aaaaaaaaaaaa…');
    expect(harness.shortHash(null)).toBe('-');
  });

  it('maps stage states and selects the immutable current attempt', () => {
    expect(harness.stageStatusMeta('WAITING_INPUT')).toEqual({ label: '等待输入', type: 'warning' });
    expect(harness.stageStatusMeta('PASSED')).toEqual({ label: '已通过', type: 'success' });
    expect(harness.currentAttempt({ attempts: [{ number: 1 }, { number: 2 }] }))
      .toEqual({ number: 2 });
    expect(harness.currentAttempt({ attempts: [] })).toBeNull();
  });

  it('summarizes current-attempt gate failures and finds only valid approvals', () => {
    const gates = [
      { stage: 'IMPLEMENTATION', attempt: 1, passed: false, rule: 'old', reason: 'old fail' },
      { stage: 'IMPLEMENTATION', attempt: 2, passed: false, rule: 'focused-tests-passed', reason: 'exit 1' },
      { stage: 'IMPLEMENTATION', attempt: 2, passed: true, rule: 'traceability-complete' }
    ];
    expect(harness.gateFailureSummary(gates, 'IMPLEMENTATION', 2))
      .toEqual(['focused-tests-passed：exit 1']);
    const approvals = [
      { stage: 'IMPLEMENTATION', attempt: 2, approvalType: 'DEPLOYABLE_BASELINE', valid: false },
      { stage: 'IMPLEMENTATION', attempt: 2, approvalType: 'DEPLOYABLE_BASELINE', valid: true }
    ];
    expect(harness.validApproval(approvals, 'IMPLEMENTATION', 2, 'DEPLOYABLE_BASELINE'))
      .toEqual(approvals[1]);
  });

  it('only exposes local deployment after an independent current-attempt approval', () => {
    const base = {
      environment: 'local',
      stages: [{ stage: 'DEPLOYMENT', status: 'RUNNING', attempts: [{ number: 1 }] }],
      approvals: []
    };
    expect(harness.canStartDeployment(base)).toBe(false);
    expect(harness.canStartDeployment({
      ...base,
      approvals: [{ stage: 'DEPLOYMENT', attempt: 1, approvalType: 'LOCAL_DEPLOY', valid: true }]
    })).toBe(true);
    expect(harness.canStartDeployment({
      ...base,
      environment: 'test',
      approvals: [{ stage: 'DEPLOYMENT', attempt: 1, approvalType: 'LOCAL_DEPLOY', valid: true }]
    })).toBe(false);
  });

  it('builds safe encoded URLs and explicit recovery/feature-flag messages', () => {
    expect(harness.reconciliationMessage('RECONCILIATION_REQUIRED'))
      .toContain('人工对账');
    expect(harness.artifactDownloadUrl('run / 1', 'artifact#1'))
      .toBe('/api/harness/runs/run%20%2F%201/artifacts/artifact%231');
    expect(harness.harnessApiAvailable(404, { code: 'HARNESS_RUN_NOT_FOUND' })).toBe(true);
    expect(harness.harnessApiAvailable(404, { code: 'NOT_FOUND' })).toBe(false);
  });
});
