/**
 * Workbench Stage Conversation 与 Document 可见交互契约。
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
  it('renders the Stage timeline, stop and composer controls', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(page).toContain("import WorkbenchConversationPanel from '../components/WorkbenchConversationPanel.vue'");
    expect(page).toContain('<workbench-conversation-panel');
    expect(page).toContain('@submit="submitConversation"');
    expect(page).toContain('@stop="stopConversation"');
    expect(page).toContain(':allowed-run-modes="allowedRunModes"');
    expect(page).not.toContain('@select-run-mode="selectRunMode"');
    expect(page).not.toContain('阶段对话尚未接入');
    expect(page).not.toContain('阶段对话能力尚未开放');
    expect(page).not.toContain('useWorkbenchDocumentRunIntegration');
    expect(page).toContain('conversation.runState.value?.staleDocuments');

    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');
    expect(panel).toContain('data-test="workbench-new-output"');
    expect(panel).toContain('newOutputAvailable');
    expect(panel).toContain('scrollToLatest');
  });

  it('shows a loading indicator instead of streaming blocks and renders persisted messages with Markdown', async () => {
    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');
    const page = await source('frontend/js/pages/Workbench.vue');

    // Persisted messages and streaming run mapped to ConversationMessage via conversationMessages
    expect(panel).toContain('v-for="msg in conversationMessages"');
    expect(panel).toContain('ConversationMessage');
    expect(panel).toContain('streamingRunMessage');
    // Agent messages are parsed into segments via parseStreamJson
    expect(panel).toContain("from '../lib/formatters.js'");
    expect(panel).toContain('persistedAssistantRunIds');
    expect(panel).toContain("block.kind === 'agent_chunk'");
    expect(panel).toContain('data-test="workbench-load-older-messages"');
    expect(panel).toContain("emit('load-older-messages')");
    expect(page).toContain(':has-older-messages="hasOlderConversationMessages"');
    expect(page).toContain('@load-older-messages="loadOlderConversationMessages"');
  });

  it('renders live shell command content and bounded output in the shared tool block', async () => {
    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');
    const toolBlock = await source('frontend/js/components/ToolBlock.vue');

    expect(panel).toContain('block.outputContent');
    expect(panel).toContain('commandExecutionContent(block)');
    expect(toolBlock).toContain('repositoryKey');
    expect(toolBlock).toContain('commandClass');
    expect(toolBlock).toContain('退出码');
  });

  it('uses one slash-command interaction adapter across Chat and Workbench', async () => {
    const chat = await source('frontend/js/components/chat-panel.vue');
    const workbench = await source('frontend/js/components/WorkbenchConversationPanel.vue');

    expect(chat).toContain("useSlashCommandInteraction");
    expect(workbench).toContain("useSlashCommandInteraction");
    expect(chat).not.toContain("useSlashCommand.js");
  });

  it('projects live Stage test progress with a stable selector and terminal status', async () => {
    const panel = await source('frontend/js/components/WorkbenchConversationPanel.vue');
    const message = await source('frontend/js/components/conversation/ConversationMessage.vue');

    expect(panel).toContain('testStatus: test.status');
    expect(panel).toContain('repositoryKey: test.repositoryKey');
    expect(message).toContain('data-test="workbench-live-test-event"');
    expect(message).toContain('{{ seg.testStatus }}');
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

  it('allows an idle Stage to be manually completed but blocks active or archived Stages', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(page).toContain('const canCompleteSelectedWorkUnit = computed(');
    expect(page).toContain("['NOT_STARTED', 'IN_PROGRESS'].includes");
    expect(page).toContain('selectedWorkUnitView.value?.activeRun == null');
    expect(page).toContain(':disabled="!canCompleteSelectedWorkUnit"');
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
    expect(panel).toContain("emit('open-document', ref)");
    expect(panel).toContain('file-change-data-test');
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
    expect(panel).toContain("emit('open-document', ref)");
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
    expect(panel).toContain('@paste-files="handlePasteFiles"');
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

  it('wires an explicit destructive Stage conversation restart confirmation and state update', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(page).toContain('旧会话历史将只读保留，新会话不会复制任何消息');
    expect(page).toContain('ElMessageBox.confirm');
    expect(page).toContain('handleStartNewContext');
    expect(page).toContain(':messages="conversationMessages"');
    expect(page).toContain(':messages-loading="messagesLoading"');
    expect(page).toContain('onConversationRestarted: applyConversationRestart');
  });

  it('keeps an archived Workbench readable while disabling Run mutations', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');
    const conversation = await source('frontend/js/components/WorkbenchConversationPanel.vue');

    expect(page).toContain(':read-only="archived"');
    expect(conversation).toContain(':stop-disabled="readOnly"');
  });
});
