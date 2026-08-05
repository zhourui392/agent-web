/**
 * Workbench 创建页动态 Stage 选择纯逻辑测试。
 *
 * @author alex
 * @since 2026-08-05
 */
import { describe, expect, it } from 'vitest';
import {
  defaultSelectedStageIdentifiers,
  orderedSelectedStageIdentifiers,
} from '../../frontend/js/lib/workbench-stage-selection.js';

const stages = [
  {
    definitionIdentifier: 'requirement-analysis',
    publishedRevision: 3,
    displayName: '需求分析',
    description: '明确需求',
    sequenceNumber: 10,
    definitionHash: 'a'.repeat(64),
  },
  {
    definitionIdentifier: 'implementation',
    publishedRevision: 2,
    displayName: '开发测试',
    description: '实现并验证',
    sequenceNumber: 30,
    definitionHash: 'b'.repeat(64),
  },
];

describe('workbench stage selection', () => {
  it('selects all published stages by default in server order', () => {
    expect(defaultSelectedStageIdentifiers(stages)).toEqual([
      'requirement-analysis',
      'implementation',
    ]);
  });

  it('projects checkbox selection in catalog order instead of click order', () => {
    expect(orderedSelectedStageIdentifiers(
      stages,
      ['implementation', 'requirement-analysis'],
    )).toEqual(['requirement-analysis', 'implementation']);
  });

  it('ignores duplicate and unknown identifiers instead of inventing stages', () => {
    expect(orderedSelectedStageIdentifiers(
      stages,
      ['implementation', 'unknown', 'implementation'],
    )).toEqual(['implementation']);
  });
});
