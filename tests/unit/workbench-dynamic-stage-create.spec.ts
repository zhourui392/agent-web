/**
 * Workbench 创建页动态 Stage 接线契约。
 *
 * @author alex
 * @since 2026-08-05
 */
import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';

describe('workbench dynamic stage creation', () => {
  it('loads selectable stages and submits identifiers with the observed catalog version', async () => {
    const shell = await readFile(
      new URL('../../frontend/js/composables/useWorkbenchShell.ts', import.meta.url),
      'utf8',
    );

    expect(shell).toContain('getSelectableWorkbenchStages');
    expect(shell).toContain('defaultSelectedStageIdentifiers');
    expect(shell).toContain('orderedSelectedStageIdentifiers');
    expect(shell).toContain('stageDefinitionIdentifiers: orderedStageIdentifiers');
    expect(shell).toContain('expectedStageCatalogVersion: selectableStageCatalog.value.stageCatalogVersion');
  });

  it('renders checkboxes in server order without any stage sorting control', async () => {
    const page = await readFile(
      new URL('../../frontend/js/pages/Workbench.vue', import.meta.url),
      'utf8',
    );

    expect(page).toContain('v-for="stage in selectableStageCatalog.stages"');
    expect(page).toContain('v-model="selectedStageDefinitionIdentifiers"');
    expect(page).toContain('{{ stage.sequenceNumber }} · {{ stage.displayName }}');
    expect(page).not.toMatch(/stage[^\n]*(?:draggable|上移|下移|拖拽)/i);
  });
});
