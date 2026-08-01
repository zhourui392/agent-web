/**
 * Workbench Shell 状态契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it } from 'vitest';
import {
  WORKBENCH_PHASES,
  isWorkbenchShellFeatureAvailable,
  parseWorkbenchShellState,
  phaseStatusLabel,
  resolvePhaseNavigation,
  workbenchErrorMessage,
  workbenchPhaseStorageKey,
  workbenchShellStorageKey,
} from '../../frontend/js/lib/workbench-state.js';

describe('workbench shell state', () => {
  it('keeps the four human-guided phases in their fixed product order', () => {
    expect(WORKBENCH_PHASES).toEqual([
      { phase: 'REQUIREMENT_ANALYSIS', label: '需求分析' },
      { phase: 'SOLUTION_DESIGN', label: '技术方案设计' },
      { phase: 'IMPLEMENT_TEST', label: '开发部署测试' },
      { phase: 'REVIEW_REFACTOR', label: '人工 Review、重构与测试' },
    ]);
  });

  it('allows direct navigation in either direction without treating phases as gates', () => {
    expect(resolvePhaseNavigation('REQUIREMENT_ANALYSIS', 'REVIEW_REFACTOR'))
      .toBe('REVIEW_REFACTOR');
    expect(resolvePhaseNavigation('REVIEW_REFACTOR', 'REQUIREMENT_ANALYSIS'))
      .toBe('REQUIREMENT_ANALYSIS');
    expect(resolvePhaseNavigation('IMPLEMENT_TEST', 'SOLUTION_DESIGN'))
      .toBe('SOLUTION_DESIGN');
  });

  it('uses human-state labels and does not present completion as a quality gate', () => {
    expect(phaseStatusLabel('NOT_STARTED')).toBe('未开始');
    expect(phaseStatusLabel('IN_PROGRESS')).toBe('进行中');
    expect(phaseStatusLabel('HUMAN_COMPLETED')).toBe('人工已完成');
  });

  it('isolates shell and phase browser state by user workbench phase and generation', () => {
    expect(workbenchShellStorageKey('user/a', 'workbench:1'))
      .not.toBe(workbenchShellStorageKey('user/b', 'workbench:1'));
    expect(workbenchShellStorageKey('user/a', 'workbench:1'))
      .not.toBe(workbenchShellStorageKey('user/a', 'workbench:2'));

    const requirement = workbenchPhaseStorageKey(
      'user/a', 'workbench:1', 'REQUIREMENT_ANALYSIS', 0,
    );
    expect(requirement).not.toBe(workbenchPhaseStorageKey(
      'user/a', 'workbench:1', 'SOLUTION_DESIGN', 0,
    ));
    expect(requirement).not.toBe(workbenchPhaseStorageKey(
      'user/a', 'workbench:1', 'REQUIREMENT_ANALYSIS', 1,
    ));
    expect(requirement).not.toBe(workbenchPhaseStorageKey(
      'user/b', 'workbench:1', 'REQUIREMENT_ANALYSIS', 0,
    ));
  });

  it('falls back to the first phase when persisted shell state is missing or damaged', () => {
    expect(parseWorkbenchShellState(null)).toEqual({
      selectedPhase: 'REQUIREMENT_ANALYSIS',
    });
    expect(parseWorkbenchShellState('{broken-json')).toEqual({
      selectedPhase: 'REQUIREMENT_ANALYSIS',
    });
    expect(parseWorkbenchShellState(JSON.stringify({ selectedPhase: 'UNKNOWN' })))
      .toEqual({ selectedPhase: 'REQUIREMENT_ANALYSIS' });
    expect(parseWorkbenchShellState(JSON.stringify({ selectedPhase: 'IMPLEMENT_TEST' })))
      .toEqual({ selectedPhase: 'IMPLEMENT_TEST' });
  });

  it('maps stable backend error codes to actionable shell messages', () => {
    expect(workbenchErrorMessage('WORKBENCH_VERSION_CONFLICT'))
      .toBe('工作台已被更新，请刷新后重试');
    expect(workbenchErrorMessage('WORKBENCH_NOT_FOUND'))
      .toBe('工作台不存在或你无权访问');
    expect(workbenchErrorMessage('WORKBENCH_ARCHIVED'))
      .toBe('工作台已归档，不能继续修改');
    expect(workbenchErrorMessage('WORKSPACE_TOPOLOGY_CHANGED'))
      .toBe('仓库状态已变化，请重新检查工作空间');
    expect(workbenchErrorMessage('UNKNOWN_ERROR'))
      .toBe('操作失败，请稍后重试');
  });

  it('keeps backend capabilities that are not in the first shell slice disabled', () => {
    expect(isWorkbenchShellFeatureAvailable('conversation')).toBe(false);
    expect(isWorkbenchShellFeatureAvailable('documents')).toBe(false);
    expect(isWorkbenchShellFeatureAvailable('capabilities')).toBe(false);
    expect(isWorkbenchShellFeatureAvailable('handoff')).toBe(false);
    expect(isWorkbenchShellFeatureAvailable('operations')).toBe(false);
  });
});
