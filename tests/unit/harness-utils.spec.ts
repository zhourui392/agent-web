import { describe, expect, it } from 'vitest';
import { createRequire } from 'node:module';

const requireCjs = createRequire(import.meta.url);
const harness = requireCjs('../../src/main/resources/static/js/admin/harness-utils.js') as {
  selectionReasonLabel: (reason: string | null | undefined) => string;
  rejectionReasonLabel: (reason: string | null | undefined) => string;
  capabilityDecisionLabel: (authorized: boolean, reason?: string) => string;
  shortHash: (hash: string | null | undefined) => string;
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
});
