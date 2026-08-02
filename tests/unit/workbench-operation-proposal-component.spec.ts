/**
 * TD-08 fixed typed Operation Proposal UI contract.
 *
 * @author alex
 * @since 2026-08-01
 */
import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';

async function source(relativePath: string): Promise<string> {
  return readFile(new URL(`../../${relativePath}`, import.meta.url), 'utf8');
}

describe('Workbench Operation Proposal component contract', () => {
  it('offers one explicit typed entry with factual Run and frozen Repository selectors', async () => {
    const cards = await source('frontend/js/components/WorkbenchOperationCards.vue');

    expect(cards).toContain('data-test="open-operation-proposal"');
    expect(cards).toContain('新建高影响操作');
    expect(cards).toContain('data-test="operation-source-run"');
    expect(cards).toContain('v-for="run in sourceRuns"');
    expect(cards).toContain('data-test="operation-repository"');
    expect(cards).toContain('v-for="repository in repositories"');
    expect(cards).toContain("value=\"GIT_COMMIT\"");
    expect(cards).toContain("value=\"GIT_PUSH\"");
    expect(cards).toContain("value=\"LOCAL_DEPLOY\"");
    expect(cards).toContain("value=\"PRODUCTION_WRITE\"");
    expect(cards).toContain('data-test="submit-operation-proposal"');
    expect(cards).toContain("emit('prepare-proposal')");
    expect(cards).toContain("emit('propose'");
  });

  it('never exposes arbitrary execution text, upload controls, absolute paths or editable message hashes', async () => {
    const cards = await source('frontend/js/components/WorkbenchOperationCards.vue');
    const template = cards.slice(0, cards.indexOf('<script setup>'));

    expect(template).not.toMatch(/command|shell|args/i);
    expect(template).not.toContain('type="file"');
    expect(template).not.toContain('messageHash');
    expect(template).not.toContain('absolutePath');
    expect(template).toContain('model-value="LOCAL"');
    expect(template).toContain('safeMessagePreview');
  });

  it('wires proposal state without removing Review Candidate events', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(page).toContain(':repositories="repositories"');
    expect(page).toContain(':source-runs="operationSourceRuns"');
    expect(page).toContain(':workbench-id="workbenchId"');
    expect(page).toContain(':phase="selectedPhase"');
    expect(page).toContain(':proposal-source-loading="operationSourceRunsLoading"');
    expect(page).toContain(':proposing="operationProposing"');
    expect(page).toContain(':proposal-created-token="operationProposalCreatedToken"');
    expect(page).toContain(':proposal-disabled-reason="operationProposalDisabledReason"');
    expect(page).toContain('@prepare-proposal="prepareOperationProposal"');
    expect(page).toContain('@propose="proposeOperation"');
    expect(page).toContain('@generate-candidate="generateReviewCandidate"');
    expect(page).toContain('@accept-candidate-item="acceptReviewCandidateItem"');
    expect(page).toContain('@ignore-candidate-item="ignoreReviewCandidateItem"');
  });
});
