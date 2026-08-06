<template>
  <aside :class="['workbench-document-panel', { mobile }]">
    <div v-if="mobile" class="workbench-panel-heading">
      <div class="workbench-document-actions">
        <el-button text @click="$emit('close')">关闭</el-button>
      </div>
    </div>

    <div class="workbench-document-browser">
      <div class="workbench-document-browser-toolbar">
        <el-select
          :model-value="selectedRepositoryKey"
          size="small"
          aria-label="选择 Workbench 仓库"
          placeholder="选择仓库"
          @change="$emit('select-repository', $event)"
        >
          <el-option
            v-for="repository in repositories"
            :key="repository.repositoryKey"
            :label="repository.repositoryKey"
            :value="repository.repositoryKey"
          />
        </el-select>
        <el-button
          size="small"
          plain
          :disabled="!currentDirectoryPath || treeLoading"
          @click="$emit('parent-directory')"
        >
          上一级
        </el-button>
      </div>
      <div class="workbench-document-directory-path">
        {{ selectedRepositoryKey || '未选择仓库' }} / {{ currentDirectoryPath || '.' }}
      </div>
      <div v-loading="treeLoading" class="workbench-document-tree">
        <button
          v-for="entry in treeEntries"
          :key="`${entry.kind}:${entry.relativePath}`"
          type="button"
          :class="[
            'workbench-document-tree-entry',
            { active: isCurrentEntry(entry) },
          ]"
          @click="openEntry(entry)"
        >
          <el-icon><folder v-if="entry.kind === 'DIRECTORY'" /><document v-else /></el-icon>
          <span>{{ entry.name }}</span>
          <small v-if="entry.kind === 'FILE' && entry.size != null">
            {{ formatBytes(entry.size) }}
          </small>
        </button>
        <div v-if="!treeLoading && treeEntries.length === 0" class="workbench-document-tree-empty">
          当前目录为空
        </div>
      </div>
      <small v-if="treeTruncated" class="workbench-document-truncated">
        目录条目已达到安全上限，仅展示部分内容。
      </small>
    </div>

    <!-- 文档查看弹框 -->
    <el-dialog
      v-model="documentDialogVisible"
      :title="currentDocument ? currentDocument.reference.repositoryKey + '/' + currentDocument.reference.relativePath : '文档查看'"
      :width="mobile ? '95%' : '70%'"
      :close-on-click-modal="true"
      append-to-body
      destroy-on-close
      class="workbench-document-dialog"
    >
    <el-alert
      v-if="documentError"
      class="workbench-document-alert"
      data-test="workbench-document-error"
      type="error"
      :closable="false"
      show-icon
      :title="documentError"
    />
    <el-alert
      v-else-if="currentDocument?.deleted"
      class="workbench-document-alert"
      data-test="workbench-document-deleted"
      type="warning"
      :closable="false"
      show-icon
      title="源文件已删除；保留已加载内容供当前阅读，下载已禁用。"
    />
    <el-alert
      v-else-if="currentDocument?.stale"
      class="workbench-document-alert"
      data-test="workbench-document-stale"
      type="warning"
      :closable="false"
      show-icon
      title="文件已有更新；点击刷新后才会替换当前内容。"
    />

    <section v-if="currentDocument" class="workbench-document-viewer">
      <header class="workbench-document-viewer-heading">
        <div
          v-if="renderMode === 'MARKDOWN'"
          class="workbench-document-markdown-modes"
          aria-label="Markdown 查看模式"
        >
          <el-button
            size="small"
            :type="markdownViewMode === 'PREVIEW' ? 'primary' : 'default'"
            :aria-pressed="markdownViewMode === 'PREVIEW'"
            data-test="workbench-markdown-preview"
            @click="markdownViewMode = 'PREVIEW'"
          >
            预览
          </el-button>
          <el-button
            size="small"
            :type="markdownViewMode === 'SOURCE' ? 'primary' : 'default'"
            :aria-pressed="markdownViewMode === 'SOURCE'"
            data-test="workbench-markdown-source"
            @click="markdownViewMode = 'SOURCE'"
          >
            源码
          </el-button>
        </div>
        <div class="workbench-document-viewer-actions">
          <el-button
            size="small"
            plain
            data-test="workbench-attach-document"
            :disabled="!canAttachCurrentDocument"
            @click="attachCurrentDocument"
          >
            {{ attachmentSelected ? '已附加' : '附加到对话' }}
          </el-button>
          <el-button
            size="small"
            :loading="contentLoading"
            @click="$emit('refresh-document')"
          >
            刷新
          </el-button>
          <el-button
            size="small"
            plain
            :loading="downloadLoading"
            :disabled="!loadedContent || !currentDocument.downloadEnabled"
            @click="$emit('download-document')"
          >
            下载
          </el-button>
        </div>
      </header>
      <div v-if="loadedContent" class="workbench-document-metadata">
        <span>{{ loadedContent.kind }}</span>
        <span>{{ loadedContent.mediaType }}</span>
        <span v-if="renderMode === 'MARKDOWN' || renderMode === 'TEXT'">
          {{ documentLanguageLabel }}
        </span>
        <span>{{ formatBytes(loadedContent.size) }}</span>
        <span v-if="loadedContent.truncated">预览已截断</span>
      </div>
      <div
        ref="viewerBody"
        v-loading="contentLoading"
        class="workbench-document-viewer-body"
        @scroll="rememberScroll"
      >
        <div
          v-if="contentLoading && !loadedContent"
          class="workbench-document-render-state"
          data-test="workbench-document-loading"
        >
          <el-icon class="is-loading" size="30"><loading /></el-icon>
          <strong>正在读取文档</strong>
          <span>正文只会通过当前 Workbench Repository Scope 加载。</span>
        </div>
        <template v-else-if="loadedContent">
          <div
            v-if="loadedContent.truncated"
            class="workbench-document-preview-notice"
            data-test="workbench-document-truncated"
          >
            当前是服务器返回的有界预览，未展示完整文件内容。
          </div>

          <div
            v-if="renderMode === 'MARKDOWN'
              && markdownViewMode === 'PREVIEW'
              && markdownRender.mode === 'SANITIZED_HTML'"
            class="workbench-markdown-preview"
            data-test="workbench-markdown-sanitized-preview"
            v-html="markdownRender.html"
          ></div>

          <div
            v-if="renderMode === 'MARKDOWN'
              && markdownViewMode === 'PREVIEW'
              && markdownRender.mode === 'PLAIN_TEXT'"
            class="workbench-document-preview-notice"
            data-test="workbench-markdown-plain-fallback"
          >
            Markdown 安全净化器当前不可用，已降级为纯文本展示。
          </div>

          <div
            v-if="showLinePresentation"
            class="workbench-document-lines"
            role="table"
            :aria-label="`${documentLanguageLabel} 只读源码`"
            :data-language="documentLanguageLabel"
          >
            <div
              v-for="line in textPresentation.lines"
              :key="line.number"
              class="workbench-document-line"
              role="row"
            >
              <span
                class="workbench-document-line-number"
                role="rowheader"
                aria-hidden="true"
                data-test="workbench-document-line-number"
              >{{ line.number }}</span>
              <code
                v-if="line.highlightedHtml !== null"
                role="cell"
                v-html="line.highlightedHtml"
              ></code>
              <code v-else role="cell">{{ line.text }}</code>
            </div>
            <div
              v-if="textPresentation.omittedLineCount > 0"
              class="workbench-document-lines-omitted"
              data-test="workbench-document-lines-omitted"
            >
              为保持页面稳定，后续 {{ textPresentation.omittedLineCount }} 行未创建 DOM 节点；
              请使用受控下载查看完整文件。
            </div>
          </div>

          <div
            v-if="renderMode === 'IMAGE'"
            class="workbench-document-image-preview workbench-document-render-state"
            data-test="workbench-document-image"
          >
            <img
              v-if="inlineImageSource"
              :src="inlineImageSource"
              alt="Workbench scoped 图片预览"
              draggable="false"
            >
            <template v-else>
              <el-icon size="30"><picture /></el-icon>
              <strong>图片预览不可用</strong>
              <span>图片只会从当前 Workbench Repository Scope 的受控响应加载。</span>
            </template>
          </div>

          <div
            v-else-if="renderMode === 'BINARY'"
            class="workbench-document-binary-metadata workbench-document-render-state"
            data-test="workbench-document-binary"
          >
            <el-icon size="30"><document /></el-icon>
            <strong>二进制文件</strong>
            <span>此文件不进入浏览器文本渲染，可核对类型与大小后通过受控接口下载。</span>
          </div>

          <div
            v-else-if="renderMode === 'UNSUPPORTED'"
            class="workbench-document-binary-metadata workbench-document-render-state"
            data-test="workbench-document-unsupported"
          >
            <el-icon size="30"><warning /></el-icon>
            <strong>暂不支持在线预览</strong>
            <span>不会尝试猜测文件格式；如下载已开放，可通过上方按钮获取原文件。</span>
          </div>

          <div
            v-else-if="renderMode === 'METADATA'"
            class="workbench-document-binary-metadata workbench-document-render-state"
            data-test="workbench-document-metadata-only"
          >
            <el-icon size="30"><document /></el-icon>
            <strong>正文不可在线预览</strong>
            <span>当前响应只包含安全元信息，不会将未知内容注入页面。</span>
          </div>
        </template>
        <div
          v-else-if="!contentLoading"
          class="workbench-disabled-document"
          data-test="workbench-document-empty-content"
        >
          <el-icon size="32"><document /></el-icon>
          <strong>{{ currentDocument.deleted ? '源文件已删除' : '无法显示文档内容' }}</strong>
          <span>未加载到正文；不会回退调用普通文件系统接口。</span>
        </div>
      </div>
    </section>

    <div v-else-if="!currentDocument" class="workbench-disabled-document">
      <el-icon size="32"><document /></el-icon>
      <strong>尚未选择文档</strong>
      <span>从上方仓库树选择文件；读取只会经过 Workbench Repository Scope 接口。</span>
    </div>
    </el-dialog>

    <div v-if="recentDocuments.length" class="workbench-recent-documents">
      <h4>本阶段最近查看</h4>
      <section
        v-for="group in recentDocumentGroups"
        :key="group.repositoryKey"
        class="workbench-recent-document-group"
      >
        <strong>{{ group.repositoryKey }}</strong>
        <button
          v-for="reference in group.documents"
          :key="`${reference.repositoryKey}:${reference.relativePath}`"
          type="button"
          @click="$emit('open-document', reference)"
        >
          <span>{{ reference.relativePath }}</span>
        </button>
      </section>
    </div>

    <div class="workbench-repository-scope">
      <h4>本次仓库范围</h4>
      <div
        v-for="repository in repositories"
        :key="repository.repositoryKey"
        class="workbench-scope-row"
      >
        <span>
          {{ repository.repositoryKey }}
          <small>{{ repository.relativePath || '.' }}</small>
        </span>
        <el-tag v-if="repository.primary" size="small" type="success">主仓</el-tag>
      </div>
    </div>
  </aside>
</template>

<script setup>
/**
 * Workbench Owner Scope 内的只读文档 Pane；桌面 Split Pane 与移动 Drawer 复用同一状态。
 *
 * @author alex
 * @since 2026-08-01
 */
import { computed, nextTick, ref, watch } from 'vue';
import {
  createWorkbenchHighlightedPresentation,
  renderWorkbenchMarkdown,
  workbenchDocumentDisplayMode,
  workbenchDocumentLanguageLabel,
  workbenchInlineImagePreviewSource,
} from '../lib/workbench-document-renderer.js';
import { groupDocumentReferencesByRepository } from '../lib/workbench-document-state.js';

const props = defineProps({
  repositories: { type: Array, required: true },
  selectedRepositoryKey: { type: String, default: '' },
  currentDirectoryPath: { type: String, default: '' },
  treeEntries: { type: Array, default: () => [] },
  treeTruncated: { type: Boolean, default: false },
  currentDocument: { type: Object, default: null },
  loadedContent: { type: Object, default: null },
  recentDocuments: { type: Array, default: () => [] },
  treeLoading: { type: Boolean, default: false },
  contentLoading: { type: Boolean, default: false },
  downloadLoading: { type: Boolean, default: false },
  documentError: { type: String, default: '' },
  mobile: { type: Boolean, default: false },
  attachmentSelected: { type: Boolean, default: false },
});

const emit = defineEmits([
  'close',
  'restore',
  'select-repository',
  'open-directory',
  'parent-directory',
  'open-document',
  'refresh-document',
  'download-document',
  'update-scroll',
  'attach-document',
  'close-document',
]);

const viewerBody = ref(null);
const documentDialogVisible = computed({
  get: () => props.currentDocument != null,
  set: (val) => { if (!val) emit('close-document'); },
});
const markdownViewMode = ref('PREVIEW');
const recentDocumentGroups = computed(() => groupDocumentReferencesByRepository(
  props.recentDocuments,
));

const renderMode = computed(() => props.loadedContent
  ? workbenchDocumentDisplayMode(props.loadedContent.kind, props.loadedContent.content)
  : 'METADATA');

const markdownRender = computed(() => renderWorkbenchMarkdown(
  props.loadedContent?.kind === 'MARKDOWN' && props.loadedContent.content != null
    ? props.loadedContent.content
    : '',
));

const textPresentation = computed(() => createWorkbenchHighlightedPresentation(
  props.loadedContent?.content ?? '',
  props.currentDocument?.reference?.relativePath ?? '',
  props.loadedContent?.mediaType ?? '',
));

const showLinePresentation = computed(() => renderMode.value === 'TEXT'
  || renderMode.value === 'MARKDOWN' && (
    markdownViewMode.value === 'SOURCE' || markdownRender.value.mode === 'PLAIN_TEXT'
  ));

const documentLanguageLabel = computed(() => workbenchDocumentLanguageLabel(
  props.currentDocument?.reference?.relativePath ?? '',
  props.loadedContent?.mediaType ?? '',
));

const inlineImageSource = computed(() => workbenchInlineImagePreviewSource(
  props.loadedContent?.inlineImageUrl,
  props.loadedContent?.kind,
  props.loadedContent?.mediaType,
));

const canAttachCurrentDocument = computed(() => {
  const currentReference = props.currentDocument?.reference;
  const loadedReference = props.loadedContent?.reference;
  const contentHash = props.loadedContent?.contentVersion;
  return !props.attachmentSelected
    && !props.contentLoading
    && Boolean(currentReference)
    && Boolean(loadedReference)
    && props.repositories.some(repository => (
      repository.repositoryKey === currentReference?.repositoryKey
    ))
    && currentReference?.repositoryKey === loadedReference?.repositoryKey
    && currentReference?.relativePath === loadedReference?.relativePath
    && props.currentDocument?.contentVersion === contentHash
    && !props.currentDocument?.stale
    && !props.currentDocument?.deleted
    && !props.loadedContent?.deleted
    && typeof contentHash === 'string'
    && /^[a-f0-9]{64}$/.test(contentHash);
});

function attachCurrentDocument() {
  if (!canAttachCurrentDocument.value || !props.loadedContent) return;
  emit('attach-document', {
    repositoryKey: props.loadedContent.reference.repositoryKey,
    relativePath: props.loadedContent.reference.relativePath,
    contentHash: props.loadedContent.contentVersion,
  });
}

function openEntry(entry) {
  if (entry.kind === 'DIRECTORY') {
    emit('open-directory', entry.relativePath);
    return;
  }
  emit('open-document', {
    repositoryKey: props.selectedRepositoryKey,
    relativePath: entry.relativePath,
  });
}

function isCurrentEntry(entry) {
  return entry.kind === 'FILE'
    && props.currentDocument?.reference?.repositoryKey === props.selectedRepositoryKey
    && props.currentDocument?.reference?.relativePath === entry.relativePath;
}

function formatBytes(size) {
  if (!Number.isFinite(size) || size < 0) return '-';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KiB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MiB`;
}

function rememberScroll(event) {
  emit('update-scroll', event.currentTarget.scrollTop);
}

watch(
  () => [
    props.currentDocument?.reference?.repositoryKey,
    props.currentDocument?.reference?.relativePath,
    props.loadedContent?.contentVersion,
  ],
  () => {
    markdownViewMode.value = 'PREVIEW';
    nextTick(() => {
      if (viewerBody.value && props.currentDocument) {
        viewerBody.value.scrollTop = props.currentDocument.scrollTop || 0;
      }
    });
  },
  { immediate: true },
);
</script>

<style scoped>
.workbench-document-viewer-actions,
.workbench-document-markdown-modes {
  display: flex;
  align-items: center;
  gap: 4px;
}

.workbench-document-viewer-actions {
  min-width: 0;
  margin-left: auto;
  justify-content: flex-end;
  flex-wrap: wrap;
}

.workbench-document-viewer-actions .el-button + .el-button,
.workbench-document-markdown-modes .el-button + .el-button {
  margin-left: 0;
}

.workbench-document-render-state {
  display: flex;
  min-height: 180px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 20px;
  gap: 7px;
  color: #8a93a2;
  text-align: center;
}

.workbench-document-render-state strong {
  color: #606b7d;
  font-size: 12px;
}

.workbench-document-render-state span {
  max-width: 420px;
  font-size: 10px;
  line-height: 1.6;
}

.workbench-document-image-preview img {
  display: block;
  max-width: 100%;
  max-height: min(70vh, 960px);
  object-fit: contain;
  user-select: none;
}

.workbench-document-preview-notice,
.workbench-document-lines-omitted {
  padding: 8px 12px;
  color: #8a651f;
  font-size: 10px;
  line-height: 1.5;
  background: #fff8e8;
  border-bottom: 1px solid #f4dfb3;
}

.workbench-document-lines {
  width: max-content;
  min-width: 100%;
  color: #2f3a4c;
  background: #fff;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  line-height: 1.55;
  tab-size: 4;
}

.workbench-document-line {
  display: grid;
  min-height: 1.55em;
  grid-template-columns: minmax(44px, auto) minmax(0, 1fr);
}

.workbench-document-line:hover {
  background: #f5f8ff;
}

.workbench-document-line-number {
  position: sticky;
  left: 0;
  padding: 0 10px;
  color: #a0a8b5;
  text-align: right;
  user-select: none;
  background: #f7f8fa;
  border-right: 1px solid #e8ebf0;
}

.workbench-document-line code {
  display: block;
  min-width: max-content;
  padding: 0 12px;
  color: inherit;
  font: inherit;
  white-space: pre;
  background: transparent;
}

.workbench-document-line :deep(.hljs-comment),
.workbench-document-line :deep(.hljs-quote) {
  color: #708090;
  font-style: italic;
}

.workbench-document-line :deep(.hljs-keyword),
.workbench-document-line :deep(.hljs-selector-tag),
.workbench-document-line :deep(.hljs-type) {
  color: #8f3f71;
  font-weight: 600;
}

.workbench-document-line :deep(.hljs-string),
.workbench-document-line :deep(.hljs-attr),
.workbench-document-line :deep(.hljs-template-variable) {
  color: #2f6f44;
}

.workbench-document-line :deep(.hljs-number),
.workbench-document-line :deep(.hljs-literal),
.workbench-document-line :deep(.hljs-symbol) {
  color: #845ec2;
}

.workbench-document-line :deep(.hljs-title),
.workbench-document-line :deep(.hljs-section),
.workbench-document-line :deep(.hljs-name) {
  color: #2458a6;
}

.workbench-document-lines-omitted {
  position: sticky;
  left: 0;
  width: 100%;
  border-top: 1px solid #f4dfb3;
}

.workbench-markdown-preview {
  padding: 14px 18px 24px;
  color: #2f3a4c;
  overflow-wrap: anywhere;
}

.workbench-markdown-preview :deep(h1),
.workbench-markdown-preview :deep(h2),
.workbench-markdown-preview :deep(h3),
.workbench-markdown-preview :deep(h4),
.workbench-markdown-preview :deep(h5),
.workbench-markdown-preview :deep(h6) {
  margin: 1em 0 0.45em;
  line-height: 1.35;
}

.workbench-markdown-preview :deep(h1) {
  padding-bottom: 0.3em;
  font-size: 21px;
  border-bottom: 1px solid #e7eaf0;
}

.workbench-markdown-preview :deep(h2) {
  font-size: 18px;
}

.workbench-markdown-preview :deep(h3) {
  font-size: 15px;
}

.workbench-markdown-preview :deep(p),
.workbench-markdown-preview :deep(li) {
  font-size: 12px;
  line-height: 1.7;
}

.workbench-markdown-preview :deep(ul),
.workbench-markdown-preview :deep(ol) {
  padding-left: 1.8em;
}

.workbench-markdown-preview :deep(a) {
  color: #4169bd;
}

.workbench-markdown-preview :deep(blockquote) {
  margin: 12px 0;
  padding: 4px 12px;
  color: #697386;
  border-left: 3px solid #bdc9df;
}

.workbench-markdown-preview :deep(code) {
  padding: 1px 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  background: #f0f2f5;
  border-radius: 4px;
}

.workbench-markdown-preview :deep(pre) {
  min-height: auto;
  margin: 10px 0;
  padding: 12px;
  overflow: auto;
  background: #f6f8fa;
  border-radius: 6px;
}

.workbench-markdown-preview :deep(pre code) {
  padding: 0;
  background: transparent;
}

.workbench-markdown-preview :deep(table) {
  display: block;
  max-width: 100%;
  overflow: auto;
  border-collapse: collapse;
}

.workbench-markdown-preview :deep(th),
.workbench-markdown-preview :deep(td) {
  padding: 6px 8px;
  font-size: 11px;
  border: 1px solid #dfe4ec;
}
</style>
