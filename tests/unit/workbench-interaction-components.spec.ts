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
    expect(panel).toContain('data-test="review-generate-candidate"');
    expect(panel).toContain('data-test="review-candidate-item"');
    expect(panel).toContain("emit('accept-candidate-item'");
    expect(panel).toContain("emit('ignore-candidate-item'");
    expect(page).toContain('@generate-candidate="generateReviewCandidate"');
    expect(page).toContain('@accept-candidate-item="acceptReviewCandidateItem"');
    expect(page).toContain('@ignore-candidate-item="ignoreReviewCandidateItem"');
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

  it('keeps safe command output summaries bounded and collapsed by default', async () => {
    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');

    expect(panel).toContain('block.commandSummary || block.outputSummary');
    expect(panel).toContain('v-if="block.outputSummary"');
    expect(panel).toContain('{{ block.outputSummary }}');
    expect(panel).not.toContain('<details open');
  });

  it('renders persisted Phase messages before current Run events with safe Markdown and no live duplication', async () => {
    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');
    const page = await source('frontend/js/pages/Workbench.vue');
    const messages = panel.indexOf('v-for="message in visibleMessages"');
    const currentRun = panel.indexOf('v-for="block in visibleRunBlocks"');

    expect(messages).toBeGreaterThan(0);
    expect(currentRun).toBeGreaterThan(messages);
    expect(panel).toContain('{{ message.content }}');
    expect(panel).toContain('v-html="renderMarkdown(message.content)"');
    expect(panel).toContain("import { renderMarkdown");
    expect(panel).toContain('persistedAssistantRunIds');
    expect(panel).toContain("block.kind === 'agent_chunk'");
    expect(panel).toContain('data-test="workbench-load-older-messages"');
    expect(panel).toContain("emit('load-older-messages')");
    expect(page).toContain(':has-older-messages="hasOlderConversationMessages"');
    expect(page).toContain('@load-older-messages="loadOlderConversationMessages"');
  });

  it('reminds the user again when a Run finishes while the open document is stale', async () => {
    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(page).toContain(':terminal-document-stale="Boolean(runState?.terminal && currentDocument?.stale)"');
    expect(panel).toContain('data-test="workbench-terminal-document-stale"');
    expect(panel).toContain('本轮运行已结束，当前打开文件已有更新；请在文档区手动刷新。');
  });

  it('exposes the frozen Repository Scope without rendering any absolute repository path', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');
    const scopeStart = page.indexOf('<el-popover');
    const scopeEnd = page.indexOf('</el-popover>', scopeStart);
    const scope = page.slice(scopeStart, scopeEnd);

    expect(page).toContain('data-test="repository-scope-popover"');
    expect(scopeStart).toBeGreaterThan(0);
    expect(scopeEnd).toBeGreaterThan(scopeStart);
    expect(scope).toContain('v-for="repository in detail.repositoryScope.repositories"');
    expect(scope).toContain('{{ repository.repositoryKey }}');
    expect(scope).toContain('{{ repositoryRelativePathLabel(repository.relativePath) }}');
    expect(page).toContain("return '路径已隐藏'");
    expect(scope).not.toContain("{{ repository.relativePath || '.' }}");
    expect(scope).toContain('v-if="repository.primary"');
    expect(scope).not.toContain('repository.absolutePath');
  });

  it('shows automatic Phase capability status and keeps advanced actions out of the primary toolbar', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(page).toContain('data-test="phase-capability-status"');
    expect(page).toContain('capabilitySummaryStatus');
    expect(page).toContain("AVAILABLE: '可用'");
    expect(page).toContain("DEGRADED: '降级可用'");
    expect(page).toContain("UNAVAILABLE: '不可用'");
    expect(page).toContain("LOAD_FAILED: '加载失败'");
    expect(page).toContain('data-test="phase-advanced-menu"');
    expect(page).toContain('@command="handlePhaseAdvancedCommand"');
    expect(page).toContain('command="open-capability"');
    expect(page).not.toContain('data-test="open-capability-drawer"');
  });

  it('states repository write authority by Run mode using repository keys only', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');
    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');
    const modifyScopeStart = panel.indexOf('data-test="run-modify-scope"');
    const modifyScopeEnd = panel.indexOf('</small>', modifyScopeStart);
    const modifyScope = panel.slice(modifyScopeStart, modifyScopeEnd);

    expect(page).toContain(':repository-keys="repositoryKeys"');
    expect(panel).toContain('repositoryKeys: { type: Array, required: true }');
    expect(panel).toContain('只读模式不授予仓库写入');
    expect(panel).toContain('本轮允许写入仓库');
    expect(panel).toContain('v-for="repositoryKey in repositoryKeys"');
    expect(modifyScope).not.toContain('relativePath');
    expect(modifyScope).not.toContain('workspaceRoot');
  });

  it('uses the Workbench-wide active write id to block only a second modify Run', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');
    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');

    expect(page).toContain('const activeWriteRunId = computed(');
    expect(page).toContain('() => shell.detail.value?.activeWriteRunId || null');
    expect(page).toContain('activeWriteRunId,');
    expect(page).toContain(':write-run-blocked="writeRunBlocked"');
    expect(panel).toContain('writeRunBlocked: { type: Boolean, required: true }');
    expect(panel).toContain("props.runMode === 'MODIFY_WORKSPACE' && props.writeRunBlocked");
    expect(panel).toContain('可切换为只读讨论');
  });

  it('allows an idle not-started Phase to be manually completed but blocks active or archived Phases', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(page).toContain('const canCompleteSelectedPhase = computed(');
    expect(page).toContain("['NOT_STARTED', 'IN_PROGRESS'].includes");
    expect(page).toContain('shell.selectedPhaseView.value?.activeRun == null');
    expect(page).toContain(':disabled="!canCompleteSelectedPhase"');
  });

  it('groups recent documents by repository and opens only scoped structured file-event references', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');
    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');
    const documentPane = await source('frontend/js/components/WorkbenchDocumentPane.vue');

    expect(documentPane).toContain('v-for="group in recentDocumentGroups"');
    expect(documentPane).toContain('v-for="reference in group.documents"');
    expect(documentPane).toContain('{{ group.repositoryKey }}');
    expect(panel).toContain('authorizedDocumentReference');
    expect(panel).toContain('visibleDocumentEvents');
    expect(panel).toContain("emit('open-document', document.reference)");
    expect(panel).not.toContain('normalizeDocumentReference(message.content');
    expect(panel).not.toMatch(/emit\('open-document',\s*message\.content/);
    expect(page).toContain('async function openRunDocument(reference)');
  });

  it('linkifies only scoped backtick references in persisted assistant and streaming Agent text', async () => {
    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');

    expect(panel).toContain('extractAuthorizedAgentDocumentReferences');
    expect(panel).toContain("message.role === 'assistant'");
    expect(panel).toContain("block.kind === 'agent_chunk'");
    expect(panel).toContain('message.documentReferences');
    expect(panel).toContain('block.documentReferences');
    expect(panel).toContain('data-test="workbench-agent-document-reference"');
    expect(panel).toContain("emit('open-document', reference)");
    expect(panel).not.toContain('normalizeDocumentReference(message.content');
    expect(panel).not.toContain('repositoryKeys[0]');
  });

  it('attaches only the loaded non-stale scoped document and renders removable safe attachment chips', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');
    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');
    const documentPane = await source('frontend/js/components/WorkbenchDocumentPane.vue');
    const pendingAttachmentsStart = panel.indexOf('data-test="workbench-pending-attachments"');
    const pendingAttachmentsEnd = panel.indexOf('</div>', pendingAttachmentsStart);
    const pendingAttachments = panel.slice(pendingAttachmentsStart, pendingAttachmentsEnd);

    expect(documentPane).toContain('data-test="workbench-attach-document"');
    expect(documentPane).toContain("attachmentSelected ? '已附加' : '附加到对话'");
    expect(documentPane).toContain(':disabled="!canAttachCurrentDocument"');
    expect(documentPane).toContain("emit('attach-document', {");
    expect(documentPane).toContain('props.repositories.some');
    expect(documentPane).toContain('props.currentDocument?.stale');
    expect(documentPane).toContain('props.currentDocument?.deleted');
    expect(documentPane).toContain('props.loadedContent?.deleted');
    expect(documentPane).toContain('props.contentLoading');
    expect(documentPane).toContain('props.currentDocument?.contentVersion === contentHash');
    expect(documentPane).toContain('/^[a-f0-9]{64}$/');
    expect(documentPane).not.toContain('type="file"');
    expect(documentPane).not.toContain('/api/fs');

    expect(pendingAttachmentsStart).toBeGreaterThan(0);
    expect(pendingAttachments).toContain('attachment.repositoryKey');
    expect(pendingAttachments).toContain('attachment.relativePath');
    expect(pendingAttachments).toContain("emit('remove-attachment'");
    expect(pendingAttachments).not.toContain('contentHash');

    expect(page.match(/:attachment-selected=/g)).toHaveLength(2);
    expect(page.match(/@attach-document="addAttachment"/g)).toHaveLength(2);
    expect(page).toContain(':attachments="pendingAttachments"');
    expect(page).toContain('@remove-attachment="removeAttachment"');
  });

  it('supports pasted, selected and dropped browser files without reusing global filesystem upload', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');
    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');
    const api = await source('frontend/js/api/workbench-uploaded-attachment.ts');
    const composable = await source('frontend/js/composables/useWorkbenchUploadedAttachments.ts');

    expect(panel).toContain('data-test="workbench-upload-image-input"');
    expect(panel).toContain('data-test="workbench-upload-file-input"');
    expect(panel).toContain('data-test="workbench-upload-image-button"');
    expect(panel).toContain('data-test="workbench-upload-file-button"');
    expect(panel).toContain('@paste="handleAttachmentPaste"');
    expect(panel).toContain('@dragover.prevent');
    expect(panel).toContain('@drop.prevent="handleAttachmentDrop"');
    expect(panel).toContain("emit('upload-files'");
    expect(panel).toContain("emit('retry-upload'");
    expect(panel).toContain("emit('remove-upload'");
    expect(panel).toContain('data-test="workbench-upload-item"');
    expect(panel).toContain('data-test="workbench-upload-preview"');
    expect(panel).toContain('浏览器上传');
    expect(panel).toContain('仓内文档');
    expect(panel).not.toContain('/api/fs');
    expect(api).not.toContain('/api/fs');
    expect(api).not.toMatch(/workingDir|absolutePath|storageKey/);
    expect(composable).toContain("type: 'UPLOADED_CONVERSATION'");
    expect(composable).toContain('attachmentId: uploaded.attachmentId');
    expect(composable).toContain('contentHash: uploaded.sha256');
    expect(page).toContain('useWorkbenchUploadedAttachments');
    expect(page).toContain(':upload-items="workbenchUploadItems"');
    expect(page).toContain('@upload-files="uploadWorkbenchFiles"');
    expect(page).toContain('@retry-upload="retryWorkbenchUpload"');
    expect(page).toContain('@remove-upload="removeWorkbenchUpload"');
  });

  it('wires an explicit destructive Phase conversation restart confirmation and state update', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(page).toContain('data-test="restart-phase-conversation"');
    expect(page).toContain('旧会话历史将只读保留，新会话不会复制任何消息');
    expect(page).toContain(':disabled="!canRestartConversation"');
    expect(page).toContain('command="restart-conversation"');
    expect(page).toContain('ElMessageBox.confirm');
    expect(page).toContain('handlePhaseAdvancedCommand');
    expect(page).toContain(':messages="conversationMessages"');
    expect(page).toContain(':messages-loading="messagesLoading"');
    expect(page).toContain('onConversationRestarted: applyConversationRestart');
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
