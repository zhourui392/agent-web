/**
 * Workbench Stage Shell 状态契约。
 *
 * @author alex
 * @since 2026-08-05
 */
import { describe, expect, it } from 'vitest';
import {
  isWorkbenchStageInstanceIdentifier,
  parseWorkbenchStageShellState,
  resolveStageNavigation,
  stageStatusLabel,
  workbenchErrorMessage,
  workbenchShellStorageKey,
  workbenchStageStorageKey,
} from '../../frontend/js/lib/workbench-state.js';

describe('workbench Stage shell state', () => {
  it('uses human-state labels without treating completion as a quality gate', () => {
    expect(stageStatusLabel('NOT_STARTED')).toBe('未开始');
    expect(stageStatusLabel('IN_PROGRESS')).toBe('进行中');
    expect(stageStatusLabel('HUMAN_COMPLETED')).toBe('人工已完成');
  });

  it('isolates shell and Stage browser state by user, Workbench, instance and generation', () => {
    expect(workbenchShellStorageKey('user/a', 'workbench:1'))
      .not.toBe(workbenchShellStorageKey('user/b', 'workbench:1'));
    expect(workbenchShellStorageKey('user/a', 'workbench:1'))
      .not.toBe(workbenchShellStorageKey('user/a', 'workbench:2'));

    const current = workbenchStageStorageKey(
      'user/a', 'workbench:1', 'stage-implementation', 0,
    );
    expect(current).not.toBe(workbenchStageStorageKey(
      'user/b', 'workbench:1', 'stage-implementation', 0,
    ));
    expect(current).not.toBe(workbenchStageStorageKey(
      'user/a', 'workbench:1', 'stage-review', 0,
    ));
    expect(current).not.toBe(workbenchStageStorageKey(
      'user/a', 'workbench:1', 'stage-implementation', 1,
    ));
  });

  it('restores only a syntactically valid Stage Instance identifier', () => {
    expect(isWorkbenchStageInstanceIdentifier('stage-implementation')).toBe(true);
    expect(isWorkbenchStageInstanceIdentifier('../escape')).toBe(false);
    expect(parseWorkbenchStageShellState(JSON.stringify({
      selectedStageInstanceIdentifier: 'stage-implementation',
    }))).toEqual({ selectedStageInstanceIdentifier: 'stage-implementation' });
    expect(parseWorkbenchStageShellState('{broken')).toEqual({
      selectedStageInstanceIdentifier: null,
    });
    expect(parseWorkbenchStageShellState(JSON.stringify({
      selectedStageInstanceIdentifier: '../escape',
    }))).toEqual({ selectedStageInstanceIdentifier: null });
  });

  it('resolves navigation only against Stage instances frozen in the Workbench', () => {
    const stages = [
      { stageInstanceIdentifier: 'stage-analysis' },
      { stageInstanceIdentifier: 'stage-implementation' },
    ];

    expect(resolveStageNavigation(stages, null, 'stage-implementation'))
      .toBe('stage-implementation');
    expect(resolveStageNavigation(stages, 'stage-analysis', 'unknown'))
      .toBe('stage-analysis');
    expect(resolveStageNavigation(stages, 'unknown', 'unknown'))
      .toBe('stage-analysis');
    expect(resolveStageNavigation([], 'stage-analysis', 'stage-analysis'))
      .toBeNull();
  });

  it('maps stable backend error codes to actionable shell messages', () => {
    expect(workbenchErrorMessage('WORKBENCH_VERSION_CONFLICT'))
      .toBe('工作台已被更新，请刷新后重试');
    expect(workbenchErrorMessage('WORKBENCH_NOT_FOUND'))
      .toBe('工作台不存在或你无权访问');
    expect(workbenchErrorMessage('WORKBENCH_STAGE_CATALOG_CHANGED'))
      .toBe('可选阶段配置已更新，请重新确认阶段后创建');
    expect(workbenchErrorMessage('WORKBENCH_STAGE_SELECTION_EMPTY'))
      .toBe('请至少选择一个阶段');
    expect(workbenchErrorMessage('UNKNOWN_ERROR'))
      .toBe('操作失败，请稍后重试');
  });
});
