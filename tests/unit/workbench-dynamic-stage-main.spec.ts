/**
 * Workbench 动态 Stage 主页面接线契约。
 *
 * @author alex
 * @since 2026-08-05
 */
import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';

async function source(relativePath: string): Promise<string> {
  return readFile(new URL(`../../${relativePath}`, import.meta.url), 'utf8');
}

describe('workbench dynamic stage main page', () => {
  it('restores and mutates a dynamic Stage by its frozen instance identity', async () => {
    const shell = await source(
      'frontend/js/composables/useWorkbenchShell.ts',
    );

    expect(shell).toContain('selectedStageInstanceIdentifier');
    expect(shell).toContain('selectedStageView');
    expect(shell).toContain('parseWorkbenchStageShellState');
    expect(shell).toContain('resolveStageNavigation');
    expect(shell).toContain('completeWorkbenchStage');
    expect(shell).toContain('reopenWorkbenchStage');
    expect(shell).toContain('stageInstanceIdentifier');
  });

  it('renders frozen Stage snapshots in server order without gates or connectors', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(page).toContain('v-for="(stage, index) in detail.stages"');
    expect(page).toContain(':key="stage.stageInstanceIdentifier"');
    expect(page).toContain('@click="selectStage(stage.stageInstanceIdentifier)"');
    expect(page).toContain('{{ stage.displayName }}');
    expect(page).toContain('selectedStageView');
    expect(page).not.toContain('stagePrerequisite');
    expect(page).not.toContain('stageGate');
  });

  it('binds Conversation state directly to the frozen Stage instance', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(page).toContain(
      'stageInstanceIdentifier: shell.selectedStageInstanceIdentifier',
    );
    expect(page).toContain('stages: shell.detail.value.stages.map');
    expect(page).toContain('const allowedRunModes = computed(');
  });
});
