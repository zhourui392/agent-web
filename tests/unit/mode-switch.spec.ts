/**
 * 模式切换前端纯逻辑测试。
 *
 * @author alex
 * @since 2026-08-20
 */
import { describe, expect, it } from 'vitest';
import {
  buildModeOptionGroups,
  modeLabel,
  newIdempotencyKey,
  toStartSessionModeParam,
  isTemplateOption,
  templateDefinitionIdentifier,
  templateRevisionId,
} from '../../frontend/js/lib/mode-switch';

describe('buildModeOptionGroups', () => {
  it('平铺单组：默认模式 + 我的模式 + 未 fork 的内置模板', () => {
    const groups = buildModeOptionGroups(
      [
        { id: 'm1', identifier: 'fork-tpl-a-12345678', displayName: '模式A', description: '已 fork' },
        { id: 'm2', identifier: 'self-made', displayName: '自建', description: null },
      ],
      [
        { revisionId: 7, definitionIdentifier: 'tpl-a', displayName: '模板A', description: '已被副本顶替' },
        { revisionId: 9, definitionIdentifier: 'tpl-b', displayName: '模板B', description: '' },
      ],
    );
    expect(groups).toHaveLength(1);
    expect(groups[0].key).toBe('all');
    expect(groups[0].options[0].value).toBe('');
    expect(groups[0].options.map((o) => o.value)).toEqual([
      '',
      'm1',
      'm2',
      'template:tpl-b:9',
    ]);
    const templateOption = groups[0].options[3];
    expect(templateOption.revisionId).toBe(9);
    expect(templateOption.source).toBe('template');
  });

  it('mine 缺少 identifier 时不做去重（兼容旧数据）', () => {
    const groups = buildModeOptionGroups(
      [{ id: 'm1', displayName: '无标识', description: null }],
      [{ revisionId: 1, definitionIdentifier: 'tpl-x', displayName: '模板X', description: '' }],
    );
    expect(groups[0].options.map((o) => o.value)).toEqual(['', 'm1', 'template:tpl-x:1']);
  });
});

describe('modeLabel', () => {
  it('命中返回显示名，未命中回退默认模式', () => {
    const groups = buildModeOptionGroups(
      [{ id: 'm1', displayName: '评审', description: null }], []);
    expect(modeLabel(groups, 'm1')).toBe('评审');
    expect(modeLabel(groups, 'unknown')).toBe('默认模式');
    expect(modeLabel(groups, '')).toBe('默认模式');
  });
});

describe('newIdempotencyKey', () => {
  it('每次生成不重复的非空键', () => {
    const first = newIdempotencyKey();
    const second = newIdempotencyKey();
    expect(first).toBeTruthy();
    expect(second).toBeTruthy();
    expect(first).not.toEqual(second);
    expect(first.length).toBeLessThanOrEqual(128);
  });
});

describe('toStartSessionModeParam', () => {
  it('默认模式不带 modeId（零行为变化契约），模式会话携带', () => {
    expect(toStartSessionModeParam('')).toEqual({});
    expect(toStartSessionModeParam('m1')).toEqual({ modeId: 'm1' });
  });
});

describe('template option helpers', () => {
  it('识别模板项并解析复合身份（definitionIdentifier + revisionId）', () => {
    const value = 'template:builtin-1-requirement-analysis:2';
    expect(isTemplateOption(value)).toBe(true);
    expect(isTemplateOption('m1')).toBe(false);
    expect(templateDefinitionIdentifier(value)).toBe('builtin-1-requirement-analysis');
    expect(templateRevisionId(value)).toBe(2);
  });
});
