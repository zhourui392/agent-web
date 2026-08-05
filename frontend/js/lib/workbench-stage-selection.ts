/**
 * Workbench 创建时动态 Stage 选择的集合与顺序规则。
 *
 * @author alex
 * @since 2026-08-05
 */

export interface WorkbenchStageSelectionOption {
  definitionIdentifier: string;
}

export function defaultSelectedStageIdentifiers(
  stages: readonly WorkbenchStageSelectionOption[],
): string[] {
  return orderedSelectedStageIdentifiers(
    stages,
    stages.map(stage => stage.definitionIdentifier),
  );
}

export function orderedSelectedStageIdentifiers(
  stages: readonly WorkbenchStageSelectionOption[],
  selectedDefinitionIdentifiers: readonly string[],
): string[] {
  const selected = new Set(selectedDefinitionIdentifiers);
  const included = new Set<string>();
  const ordered: string[] = [];
  for (const stage of stages) {
    const identifier = stage.definitionIdentifier;
    if (selected.has(identifier) && !included.has(identifier)) {
      included.add(identifier);
      ordered.push(identifier);
    }
  }
  return ordered;
}
