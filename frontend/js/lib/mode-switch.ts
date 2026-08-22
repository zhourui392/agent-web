/**
 * 模式切换前端纯逻辑：下拉分组、幂等键、会话启动入参投影。
 *
 * @author alex
 * @since 2026-08-20
 */

/** 下拉分组项：个人使用不区分来源，单一平铺组。 */
export interface ModeOptionGroup {
  key: 'all';
  label: string;
  options: ModeOption[];
}

export interface ModeOption {
  value: string;
  label: string;
  description: string;
  /** 内置模板项专用：fork 后再切换。 */
  revisionId?: number;
  source: 'default' | 'mine' | 'template';
}

/** fork 副本 identifier 形如 fork-{definitionIdentifier}-{8位hex}。 */
function isForkOf(identifier: string, definitionIdentifier: string): boolean {
  return identifier.startsWith(`fork-${definitionIdentifier}-`);
}

export function buildModeOptionGroups(
  mine: Array<{
    id: string;
    identifier?: string;
    displayName: string;
    description: string | null;
  }>,
  templates: Array<{
    revisionId: number;
    definitionIdentifier: string;
    displayName: string;
    description: string;
  }>,
): ModeOptionGroup[] {
  const mineOptions = mine.map((mode) => ({
    value: mode.id,
    label: mode.displayName,
    description: mode.description || '',
    source: 'mine' as const,
  }));
  const unforkedTemplates = templates
    .filter((template) =>
      !mine.some((mode) =>
        mode.identifier ? isForkOf(mode.identifier, template.definitionIdentifier) : false))
    .map((template) => ({
      // 复合身份编码（identifier 不含冒号，可安全用 ':' 分隔）
      value: templateOptionValue(template.definitionIdentifier, template.revisionId),
      label: template.displayName,
      description: template.description || '',
      revisionId: template.revisionId,
      source: 'template' as const,
    }));
  return [
    {
      key: 'all',
      label: '',
      options: [
        {
          value: '',
          label: '默认模式',
          description: '原纯 Chat 行为，无模式下发',
          source: 'default',
        },
        ...mineOptions,
        ...unforkedTemplates,
      ],
    },
  ];
}

/** 当前选中模式的展示名。 */
export function modeLabel(groups: ModeOptionGroup[], value: string): string {
  for (const group of groups) {
    for (const option of group.options) {
      if (option.value === value) {
        return option.label;
      }
    }
  }
  return '默认模式';
}

/** 每次切换生成全新幂等键（crypto 不可用时退化为随机数）。 */
export function newIdempotencyKey(): string {
  const cryptoObj = globalThis.crypto;
  if (cryptoObj && typeof cryptoObj.randomUUID === 'function') {
    return cryptoObj.randomUUID();
  }
  return `ms-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

/** 会话创建入参：默认模式不带 modeId，保持后端"无模式 = 零行为变化"契约。 */
export function toStartSessionModeParam(modeId: string): { modeId?: string } {
  return modeId ? { modeId } : {};
}

/** 模板复合身份编码：revision_number 非全局唯一，必须与 definitionIdentifier 联合定位。 */
export function templateOptionValue(definitionIdentifier: string, revisionId: number): string {
  return `template:${definitionIdentifier}:${revisionId}`;
}

/** 模板选中 → fork 后实际绑定的模式 id。 */
export function isTemplateOption(value: string): boolean {
  return value.startsWith('template:');
}

export function templateDefinitionIdentifier(value: string): string {
  return value.slice('template:'.length).split(':', 2)[0];
}

export function templateRevisionId(value: string): number {
  const parts = value.slice('template:'.length).split(':');
  return Number(parts[1]);
}
