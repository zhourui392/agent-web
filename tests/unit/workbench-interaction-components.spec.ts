/**
 * Workbench Review, Operation and Conversation visible interaction contracts.
 *
 * @author alex
 * @since 2026-08-01
 */
import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';

async function source(relativePath: string): Promise<string> {
  return readFile(new URL(`../../${relativePath}`, import.meta.url), 'utf8');
}

describe('Workbench interactive panels', () => {
  it('requires an explicit exact Review confirmation before exposing modify intent', async () => {
    const panel = await source('frontend/js/components/WorkbenchReviewPanel.vue');
    const conversation = await source('frontend/js/components/WorkbenchConversationPanel.vue');
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(panel).toContain('data-test="review-opinion"');
    expect(panel).toContain('data-test="review-save-opinion"');
    expect(panel).toContain('data-test="review-confirm-modification"');
    expect(panel).toContain('Opinion v{{ opinion.version }}');
    expect(panel).toContain('确认只绑定当前版本与 Hash');
    expect(panel).toContain(':disabled="readOnly');
    expect(panel).not.toContain('v-html');
    expect(page).toContain(':modify-ready="selectedPhase !== \'REVIEW_REFACTOR\' || reviewConfirmed"');
    expect(conversation).toContain('props.modifyReady');
  });

  it('shows typed operation targets and states that authorization never means execution', async () => {
    const cards = await source('frontend/js/components/WorkbenchOperationCards.vue');

    expect(cards).toContain('data-test="high-impact-operation"');
    expect(cards).toContain('executionAvailable === false');
    expect(cards).toContain('批准只记录授权，不会自动执行');
    expect(cards).toContain("emit('decide'");
    expect(cards).toContain(':disabled="readOnly');
    expect(cards).not.toContain('v-html');
  });

  it('replaces the disabled phase placeholder with timeline, mode, stop and composer controls', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(page).toContain("import WorkbenchConversationPanel from '../components/WorkbenchConversationPanel.vue'");
    expect(page).toContain('<workbench-conversation-panel');
    expect(page).toContain('@submit="submitConversation"');
    expect(page).toContain('@stop="stopConversation"');
    expect(page).toContain('<workbench-review-panel');
    expect(page).toContain('<workbench-operation-cards');
    expect(page).not.toContain('阶段对话尚未接入');
    expect(page).not.toContain('阶段对话能力尚未开放');
    expect(page).not.toContain("{ name: 'Review 确认'");
    expect(page).not.toContain("{ name: '高影响操作'");
    expect(page).not.toContain('useWorkbenchDocumentRunIntegration');
    expect(page).toContain('conversation.runState.value?.staleDocuments');

    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');
    expect(panel).toContain('data-test="workbench-new-output"');
    expect(panel).toContain('newOutputAvailable');
    expect(panel).toContain('scrollToLatest');
  });

  it('keeps an archived Workbench readable while disabling Run, Review, Handoff and Operation mutations', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');
    const conversation = await source('frontend/js/components/WorkbenchConversationPanel.vue');

    expect(page).toContain(':read-only="archived"');
    expect(page).toContain(':read-only="reviewReadOnly"');
    expect(page).toContain(':read-only="operationReadOnly"');
    expect(page).toContain(':read-only="handoffReadOnly"');
    expect(conversation).toContain(':disabled="readOnly"');
  });
});
