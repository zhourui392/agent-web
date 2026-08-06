/**
 * Workbench Document Pane 编排：身份隔离恢复、响应式 Drawer 与 Pointer Events 拖动。
 *
 * @author alex
 * @since 2026-08-01
 */
import {
  computed,
  getCurrentInstance,
  getCurrentScope,
  onMounted,
  onScopeDispose,
  ref,
  shallowRef,
  watch,
  type ComputedRef,
  type Ref,
} from 'vue';
import {
  WorkbenchDocumentApiError,
  createWorkbenchDocumentApiClient,
  type WorkbenchDocumentApiClient,
  type WorkbenchDocumentContentView,
  type WorkbenchDocumentDownload,
  type WorkbenchDocumentTreeEntry,
} from '../api/workbench-document';
import {
  restoreWorkbenchDocumentPaneSession,
  workbenchDocumentWidthFromPointer,
} from '../lib/workbench-document-pane';
import {
  applyWorkbenchDocumentNotModified,
  applyWorkbenchDocumentFileEvent,
  collapseWorkbenchDocumentLayout,
  createLoadedWorkbenchDocument,
  createWorkbenchDocumentLayout,
  enterWorkbenchMobileDrawer,
  exitWorkbenchMobileDrawer,
  markWorkbenchDocumentDeleted,
  maximizeWorkbenchDocumentLayout,
  normalizeDocumentReference,
  refreshWorkbenchDocument,
  rememberRecentDocument,
  resetWorkbenchDocumentLayout,
  resizeWorkbenchDocumentLayout,
  restoreWorkbenchDocumentLayout,
  sameDocumentReference,
  switchWorkbenchDocument,
  type DocumentReference,
  type StorageLike,
  type WorkbenchDesktopDocumentLayoutMode,
  type WorkbenchDocumentLayoutState,
  type WorkbenchDocumentStorageIdentity,
  type WorkbenchDocumentSessionState,
  type WorkbenchDocumentStateStore,
  type WorkbenchDocumentViewState,
} from '../lib/workbench-document-state';
import { workbenchInlineImagePreviewSource } from '../lib/workbench-document-renderer';

const MOBILE_VIEWPORT_QUERY = '(max-width: 820px)';

export interface WorkbenchDocumentRepositoryScopeItem {
  repositoryKey: string;
  primary?: boolean;
}

export type WorkbenchDocumentDownloadSaver = (
  download: WorkbenchDocumentDownload,
) => void;

export interface WorkbenchInlineImageUrlLifecycle {
  create(blob: Blob): string;
  revoke(url: string): void;
}

export interface WorkbenchDocumentPaneContentView extends WorkbenchDocumentContentView {
  inlineImageUrl?: string;
}

/**
 * Run 订阅建立时捕获的 Document Scope。对象本身是一次性 token，身份切换后旧 token 失效。
 */
export interface WorkbenchDocumentEventScope extends WorkbenchDocumentStorageIdentity {
  generation: number;
}

export interface WorkbenchDocumentFileChangedEvent {
  repositoryKey: string;
  relativePath: string;
  changeType: string;
  contentVersion: string;
}

interface UseWorkbenchDocumentPaneOptions {
  userId: Readonly<Ref<string>>;
  workbenchId: Readonly<Ref<string | null>>;
  stageInstanceIdentifier: Readonly<Ref<string | null>>;
  repositories?: Readonly<Ref<ReadonlyArray<WorkbenchDocumentRepositoryScopeItem>>>;
  apiClient?: WorkbenchDocumentApiClient;
  saveDownload?: WorkbenchDocumentDownloadSaver;
  inlineImageUrls?: WorkbenchInlineImageUrlLifecycle;
  storage?: StorageLike;
}

export interface UseWorkbenchDocumentPane {
  splitRoot: Ref<HTMLElement | null>;
  layout: Ref<WorkbenchDocumentLayoutState>;
  desktopMode: ComputedRef<WorkbenchDesktopDocumentLayoutMode>;
  splitStyle: ComputedRef<Record<string, string>>;
  isMobile: Ref<boolean>;
  mobileDrawerVisible: Ref<boolean>;
  dragging: Ref<boolean>;
  selectedRepositoryKey: Ref<string>;
  currentDirectoryPath: Ref<string>;
  treeEntries: Ref<WorkbenchDocumentTreeEntry[]>;
  treeTruncated: Ref<boolean>;
  currentDocument: Ref<WorkbenchDocumentViewState | null>;
  loadedContent: Ref<WorkbenchDocumentPaneContentView | null>;
  recentDocuments: Ref<ReadonlyArray<DocumentReference>>;
  treeLoading: Ref<boolean>;
  contentLoading: Ref<boolean>;
  downloadLoading: Ref<boolean>;
  documentError: Ref<string>;
  documentEventScope: Readonly<Ref<WorkbenchDocumentEventScope | null>>;
  collapse: () => void;
  maximize: () => void;
  restore: () => void;
  reset: () => void;
  openMobileDrawer: () => void;
  cancelResize: () => void;
  beginResize: (event: PointerEvent) => void;
  moveResize: (event: PointerEvent) => void;
  endResize: (event: PointerEvent) => void;
  selectRepository: (repositoryKey: string) => Promise<void>;
  openDirectory: (relativePath: string) => Promise<void>;
  navigateToParentDirectory: () => Promise<void>;
  openDocument: (reference: DocumentReference) => Promise<void>;
  closeDocument: () => void;
  refreshDocument: () => Promise<void>;
  downloadCurrent: () => Promise<void>;
  updateDocumentScrollTop: (scrollTop: number) => void;
  receiveDocumentFileChanged: (
    scope: WorkbenchDocumentEventScope | null,
    event: WorkbenchDocumentFileChangedEvent,
  ) => boolean;
}

function initialSessionState(): WorkbenchDocumentSessionState {
  return {
    layout: collapseWorkbenchDocumentLayout(createWorkbenchDocumentLayout()),
    currentDocument: null,
    recentDocuments: [],
  };
}

function currentDocumentStorageIdentity(
  options: UseWorkbenchDocumentPaneOptions,
): Pick<WorkbenchDocumentStorageIdentity, 'stageInstanceIdentifier'> | null {
  const stageInstanceIdentifier =
    options.stageInstanceIdentifier.value?.trim();
  return stageInstanceIdentifier ? { stageInstanceIdentifier } : null;
}

function sameDocumentStorageIdentity(
  left: Pick<WorkbenchDocumentStorageIdentity, 'stageInstanceIdentifier'>,
  right: Pick<WorkbenchDocumentStorageIdentity, 'stageInstanceIdentifier'>,
): boolean {
  return left.stageInstanceIdentifier === right.stageInstanceIdentifier;
}

function browserStorage(): StorageLike | undefined {
  if (typeof window === 'undefined') return undefined;
  try {
    return window.localStorage;
  } catch {
    return undefined;
  }
}

function browserMatchesMobile(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia(MOBILE_VIEWPORT_QUERY).matches;
}

function browserSaveDownload(download: WorkbenchDocumentDownload): void {
  if (typeof document === 'undefined'
    || typeof URL === 'undefined'
    || typeof URL.createObjectURL !== 'function') return;
  const objectUrl = URL.createObjectURL(download.blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = download.fileName;
    anchor.rel = 'noopener';
    anchor.style.display = 'none';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

const BROWSER_INLINE_IMAGE_URLS: WorkbenchInlineImageUrlLifecycle = {
  create(blob: Blob): string {
    if (typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') {
      throw new WorkbenchDocumentApiError(0, 'WORKBENCH_DOCUMENT_RESPONSE_INVALID');
    }
    return URL.createObjectURL(blob);
  },
  revoke(url: string): void {
    if (typeof URL !== 'undefined' && typeof URL.revokeObjectURL === 'function') {
      URL.revokeObjectURL(url);
    }
  },
};

function documentErrorMessage(error: unknown): string {
  if (!(error instanceof WorkbenchDocumentApiError)) {
    return '文档请求失败，请稍后重试';
  }
  if (error.code === 'WORKBENCH_DOCUMENT_RESPONSE_INVALID') {
    return '文档响应与当前 Workbench 仓库范围不一致';
  }
  if (error.code === 'WORKBENCH_NOT_FOUND') return 'Workbench 不存在或无权访问';
  if (error.code === 'WORKBENCH_REPOSITORY_NOT_FOUND'
    || error.code === 'WORKBENCH_REPOSITORY_SCOPE_INVALID'
    || error.code === 'WORKSPACE_TOPOLOGY_CHANGED') {
    return '仓库目录已移动或不存在；请恢复原目录，或创建新的 Workbench。';
  }
  if (error.code === 'WORKBENCH_PATH_FORBIDDEN'
    || error.code === 'WORKSPACE_PATH_FORBIDDEN') {
    return '该路径不在 Workbench 授权范围内';
  }
  if (error.code === 'WORKBENCH_DOCUMENT_TOO_LARGE') return '文件过大，无法在线预览';
  if (error.code === 'WORKBENCH_DOCUMENT_UNSUPPORTED') return '该文件不支持在线预览';
  if (error.code === 'WORKBENCH_DOCUMENT_CHANGED_DURING_READ') {
    return '文件读取期间发生变化，请重新刷新';
  }
  if (error.code === 'WORKBENCH_DOCUMENT_NOT_FOUND'
    || error.code === 'WORKBENCH_DOCUMENT_DELETED') return '文件已不存在';
  return '文档请求失败，请稍后重试';
}

export function useWorkbenchDocumentPane(
  options: UseWorkbenchDocumentPaneOptions,
): UseWorkbenchDocumentPane {
  const storage = options.storage ?? browserStorage();
  const apiClient = options.apiClient ?? createWorkbenchDocumentApiClient();
  const saveDownload = options.saveDownload ?? browserSaveDownload;
  const inlineImageUrls = options.inlineImageUrls ?? BROWSER_INLINE_IMAGE_URLS;
  const splitRoot = ref<HTMLElement | null>(null);
  const layout = shallowRef<WorkbenchDocumentLayoutState>(
    collapseWorkbenchDocumentLayout(createWorkbenchDocumentLayout()));
  const isMobile = ref(browserMatchesMobile());
  const mobileDrawerVisible = ref(false);
  const dragging = ref(false);
  const selectedRepositoryKey = ref('');
  const currentDirectoryPath = ref('');
  const treeEntries = ref<WorkbenchDocumentTreeEntry[]>([]);
  const treeTruncated = ref(false);
  const currentDocument = shallowRef<WorkbenchDocumentViewState | null>(null);
  const loadedContent = shallowRef<WorkbenchDocumentPaneContentView | null>(null);
  const recentDocuments = ref<ReadonlyArray<DocumentReference>>([]);
  const treeLoading = ref(false);
  const contentLoading = ref(false);
  const downloadLoading = ref(false);
  const documentError = ref('');
  const documentEventScope = shallowRef<WorkbenchDocumentEventScope | null>(null);
  const currentEtag = ref<string | null>(null);
  let activeInlineImageUrl: string | null = null;
  let inlineImageEtag: string | null = null;
  let activeStore: WorkbenchDocumentStateStore | null = null;
  let retainedState = initialSessionState();
  let mediaQuery: MediaQueryList | null = null;
  let dragBounds: { left: number; width: number } | null = null;
  let activePointerId: number | null = null;
  let captureTarget: Element | null = null;
  let pendingClientX: number | null = null;
  let animationFrame: number | null = null;
  let scopeEpoch = 0;
  let treeRequestSequence = 0;
  let contentRequestSequence = 0;
  let downloadRequestSequence = 0;

  const desktopMode = computed<WorkbenchDesktopDocumentLayoutMode>(() =>
    layout.value.mode === 'MOBILE_DRAWER'
      ? layout.value.desktopLayout.mode
      : layout.value.mode);

  const splitStyle = computed<Record<string, string>>(() => {
    if (isMobile.value || desktopMode.value !== 'NORMAL') {
      return {} as Record<string, string>;
    }
    return {
      '--workbench-document-pane-width': `${layout.value.widthPercent}%`,
    };
  });

  function persist(): void {
    if (!activeStore) return;
    retainedState = { ...retainedState, layout: layout.value };
    try {
      activeStore.save(retainedState);
    } catch {
      // 浏览器禁用 localStorage 时仍允许继续使用当前内存布局。
    }
  }

  function applyLayout(next: WorkbenchDocumentLayoutState, shouldPersist = true): void {
    layout.value = next;
    retainedState = { ...retainedState, layout: next };
    if (shouldPersist) persist();
  }

  function layoutForViewport(
    source: WorkbenchDocumentLayoutState,
  ): WorkbenchDocumentLayoutState {
    return isMobile.value
      ? enterWorkbenchMobileDrawer(source)
      : exitWorkbenchMobileDrawer(source);
  }

  function scopedRepositories(): WorkbenchDocumentRepositoryScopeItem[] {
    const repositories = options.repositories?.value ?? [];
    const accepted: WorkbenchDocumentRepositoryScopeItem[] = [];
    const keys = new Set<string>();
    for (const repository of repositories) {
      const reference = normalizeDocumentReference({
        repositoryKey: repository?.repositoryKey,
        relativePath: 'scope-check',
      });
      if (!reference || keys.has(reference.repositoryKey)) continue;
      keys.add(reference.repositoryKey);
      accepted.push({
        repositoryKey: reference.repositoryKey,
        primary: repository.primary === true,
      });
    }
    return accepted;
  }

  function scopeContains(repositoryKey: string): boolean {
    return scopedRepositories().some(repository => repository.repositoryKey === repositoryKey);
  }

  function applyCurrentDocument(next: WorkbenchDocumentViewState | null): void {
    currentDocument.value = next;
    retainedState = { ...retainedState, currentDocument: next };
    persist();
  }

  function applyRecentDocuments(next: ReadonlyArray<DocumentReference>): void {
    recentDocuments.value = next;
    retainedState = { ...retainedState, recentDocuments: next };
    persist();
  }

  function safelyRevokeInlineImageUrl(url: string): void {
    try {
      inlineImageUrls.revoke(url);
    } catch {
      // Object URL 已失效或浏览器拒绝回收时只丢弃本地引用。
    }
  }

  function clearInlineImagePreview(): void {
    const previous = activeInlineImageUrl;
    activeInlineImageUrl = null;
    inlineImageEtag = null;
    if (previous) safelyRevokeInlineImageUrl(previous);
  }

  function replaceInlineImagePreview(url: string, etag: string): void {
    const previous = activeInlineImageUrl;
    activeInlineImageUrl = url;
    inlineImageEtag = etag;
    if (previous && previous !== url) safelyRevokeInlineImageUrl(previous);
  }

  function closeDocument(): void {
    currentDocument.value = null;
  }

  function clearDocumentRequestState(): void {
    treeRequestSequence++;
    contentRequestSequence++;
    downloadRequestSequence++;
    clearInlineImagePreview();
    treeLoading.value = false;
    contentLoading.value = false;
    downloadLoading.value = false;
    selectedRepositoryKey.value = '';
    currentDirectoryPath.value = '';
    treeEntries.value = [];
    treeTruncated.value = false;
    currentDocument.value = null;
    loadedContent.value = null;
    recentDocuments.value = [];
    currentEtag.value = null;
    documentError.value = '';
  }

  function reportDocumentError(error: unknown): void {
    documentError.value = documentErrorMessage(error);
  }

  function reportScopeMismatch(): void {
    reportDocumentError(new WorkbenchDocumentApiError(
      200,
      'WORKBENCH_DOCUMENT_RESPONSE_INVALID',
    ));
  }

  async function loadTreeForScope(
    repositoryKey: string,
    relativePath: string,
    epoch: number,
  ): Promise<void> {
    const workbenchId = options.workbenchId.value;
    if (!workbenchId || !scopeContains(repositoryKey)) {
      reportScopeMismatch();
      return;
    }
    const requestSequence = ++treeRequestSequence;
    treeLoading.value = true;
    documentError.value = '';
    try {
      const tree = await apiClient.listTree({
        workbenchId,
        repositoryKey,
        relativePath,
        limit: 1000,
      });
      if (epoch !== scopeEpoch || requestSequence !== treeRequestSequence) return;
      if (options.workbenchId.value !== workbenchId
        || selectedRepositoryKey.value !== repositoryKey
        || tree.repositoryKey !== repositoryKey
        || tree.path !== relativePath) {
        reportScopeMismatch();
        treeEntries.value = [];
        treeTruncated.value = false;
        return;
      }
      currentDirectoryPath.value = relativePath;
      treeEntries.value = tree.entries.slice();
      treeTruncated.value = tree.truncated;
    } catch (error) {
      if (epoch !== scopeEpoch || requestSequence !== treeRequestSequence) return;
      treeEntries.value = [];
      treeTruncated.value = false;
      reportDocumentError(error);
    } finally {
      if (epoch === scopeEpoch && requestSequence === treeRequestSequence) {
        treeLoading.value = false;
      }
    }
  }

  async function readDocument(
    reference: DocumentReference,
    refresh: boolean,
    epoch: number,
  ): Promise<void> {
    const normalized = normalizeDocumentReference(reference);
    const workbenchId = options.workbenchId.value;
    if (!normalized || !workbenchId || !scopeContains(normalized.repositoryKey)) {
      reportScopeMismatch();
      return;
    }
    const requestSequence = ++contentRequestSequence;
    contentLoading.value = true;
    documentError.value = '';
    const sameCurrent = currentDocument.value != null
      && sameDocumentReference(currentDocument.value.reference, normalized);
    if (!sameCurrent) {
      clearInlineImagePreview();
      const switched = currentDocument.value
        ? switchWorkbenchDocument(currentDocument.value, normalized)
        : switchWorkbenchDocument(createLoadedWorkbenchDocument({
          reference: normalized,
          content: null,
          contentVersion: 'pending',
        }), normalized);
      applyCurrentDocument({ ...switched, contentVersion: null });
      loadedContent.value = null;
      currentEtag.value = null;
    }
    try {
      const locator = {
        workbenchId,
        ...normalized,
      };
      const result = refresh
        ? await apiClient.readContent(locator, currentEtag.value ?? undefined)
        : await apiClient.readContent(locator);
      if (epoch !== scopeEpoch || requestSequence !== contentRequestSequence) return;
      if (options.workbenchId.value !== workbenchId
        || !currentDocument.value
        || !sameDocumentReference(currentDocument.value.reference, normalized)) return;
      if (result.status === 'DELETED') {
        applyCurrentDocument(markWorkbenchDocumentDeleted(currentDocument.value));
        return;
      }
      if (result.status === 'NOT_MODIFIED') {
        if (loadedContent.value == null) {
          reportScopeMismatch();
          return;
        }
        currentEtag.value = result.etag ?? currentEtag.value;
        applyCurrentDocument(applyWorkbenchDocumentNotModified(currentDocument.value));
        return;
      }
      if (!sameDocumentReference(result.document.reference, normalized)) {
        reportScopeMismatch();
        return;
      }
      if (result.document.deleted) {
        applyCurrentDocument(markWorkbenchDocumentDeleted(currentDocument.value));
        return;
      }
      const next = refresh
        ? refreshWorkbenchDocument(currentDocument.value, {
          reference: normalized,
          content: result.document.content,
          contentVersion: result.document.contentVersion,
        })
        : createLoadedWorkbenchDocument({
          reference: normalized,
          content: result.document.content,
          contentVersion: result.document.contentVersion,
          scrollTop: currentDocument.value.scrollTop,
        });
      if (result.document.kind === 'IMAGE') {
        const retainedImageUrl = loadedContent.value?.kind === 'IMAGE'
          && sameDocumentReference(loadedContent.value.reference, normalized)
          && loadedContent.value.inlineImageUrl === activeInlineImageUrl
          ? activeInlineImageUrl
          : null;
        const imageResult = refresh && retainedImageUrl && inlineImageEtag
          ? await apiClient.readInlineImage(locator, inlineImageEtag)
          : await apiClient.readInlineImage(locator);
        if (epoch !== scopeEpoch
          || requestSequence !== contentRequestSequence
          || options.workbenchId.value !== workbenchId
          || !currentDocument.value
          || !sameDocumentReference(currentDocument.value.reference, normalized)) return;
        if (imageResult.status === 'DELETED') {
          applyCurrentDocument(markWorkbenchDocumentDeleted(currentDocument.value));
          return;
        }
        let inlineImageUrl: string;
        if (imageResult.status === 'NOT_MODIFIED') {
          if (!retainedImageUrl) {
            throw new WorkbenchDocumentApiError(
              304,
              'WORKBENCH_DOCUMENT_RESPONSE_INVALID',
            );
          }
          inlineImageUrl = retainedImageUrl;
          inlineImageEtag = imageResult.etag ?? inlineImageEtag;
        } else {
          if (result.document.mediaType.toLowerCase() !== imageResult.mediaType) {
            throw new WorkbenchDocumentApiError(
              200,
              'WORKBENCH_DOCUMENT_RESPONSE_INVALID',
            );
          }
          const created = inlineImageUrls.create(imageResult.blob);
          const safeCreated = workbenchInlineImagePreviewSource(
            created,
            result.document.kind,
            imageResult.mediaType,
          );
          if (!safeCreated) {
            if (typeof created === 'string' && created.startsWith('blob:')) {
              safelyRevokeInlineImageUrl(created);
            }
            throw new WorkbenchDocumentApiError(
              200,
              'WORKBENCH_DOCUMENT_RESPONSE_INVALID',
            );
          }
          inlineImageUrl = safeCreated;
          replaceInlineImagePreview(safeCreated, imageResult.etag);
        }
        loadedContent.value = { ...result.document, inlineImageUrl };
        currentEtag.value = result.etag;
        applyCurrentDocument(next);
        applyRecentDocuments(rememberRecentDocument(recentDocuments.value, normalized));
        return;
      }
      clearInlineImagePreview();
      loadedContent.value = result.document;
      currentEtag.value = result.etag;
      applyCurrentDocument(next);
      applyRecentDocuments(rememberRecentDocument(recentDocuments.value, normalized));
    } catch (error) {
      if (epoch !== scopeEpoch || requestSequence !== contentRequestSequence) return;
      reportDocumentError(error);
    } finally {
      if (epoch === scopeEpoch && requestSequence === contentRequestSequence) {
        contentLoading.value = false;
      }
    }
  }

  async function initializeDocumentScope(epoch: number): Promise<void> {
    const repositories = scopedRepositories();
    const restoredReference = retainedState.currentDocument?.reference ?? null;
    const restoredRepository = restoredReference
      && repositories.some(item => item.repositoryKey === restoredReference.repositoryKey)
      ? restoredReference.repositoryKey
      : null;
    const selected = restoredRepository
      ?? repositories.find(repository => repository.primary)?.repositoryKey
      ?? repositories[0]?.repositoryKey
      ?? '';
    if (!selected || epoch !== scopeEpoch) return;
    selectedRepositoryKey.value = selected;
    await loadTreeForScope(selected, '', epoch);
    if (epoch === scopeEpoch && restoredReference && restoredRepository) {
      await readDocument(restoredReference, false, epoch);
    }
  }

  function restoreIdentity(): void {
    const epoch = ++scopeEpoch;
    documentEventScope.value = createDocumentEventScope(epoch);
    cancelResize();
    mobileDrawerVisible.value = false;
    activeStore = null;
    clearDocumentRequestState();
    const userId = options.userId.value;
    const workbenchId = options.workbenchId.value;
    const storageIdentity = currentDocumentStorageIdentity(options);
    if (!storage || !userId || !workbenchId || !storageIdentity) {
      retainedState = initialSessionState();
      applyLayout(layoutForViewport(retainedState.layout), false);
      void initializeDocumentScope(epoch);
      return;
    }
    const restored = restoreWorkbenchDocumentPaneSession(storage, {
      userId,
      workbenchId,
      ...storageIdentity,
    }, isMobile.value);
    activeStore = restored.store;
    retainedState = restored.state;
    currentDocument.value = retainedState.currentDocument;
    recentDocuments.value = retainedState.recentDocuments;
    applyLayout(retainedState.layout);
    // 文档区最大化已移除；存量持久化状态为 MAXIMIZED 时自动恢复到 NORMAL
    if (desktopMode.value === 'MAXIMIZED') {
      restore();
    }
    void initializeDocumentScope(epoch);
  }

  function synchronizeViewport(mobile: boolean): void {
    cancelResize();
    isMobile.value = mobile;
    if (!mobile) mobileDrawerVisible.value = false;
    applyLayout(layoutForViewport(layout.value));
  }

  function collapse(): void {
    applyLayout(collapseWorkbenchDocumentLayout(layout.value));
  }

  function maximize(): void {
    applyLayout(maximizeWorkbenchDocumentLayout(layout.value));
  }

  function restore(): void {
    applyLayout(restoreWorkbenchDocumentLayout(layout.value));
  }

  function reset(): void {
    applyLayout(resetWorkbenchDocumentLayout(layout.value));
  }

  function openMobileDrawer(): void {
    if (isMobile.value) mobileDrawerVisible.value = true;
  }

  function parentDirectory(relativePath: string): string {
    const separator = relativePath.lastIndexOf('/');
    return separator < 0 ? '' : relativePath.slice(0, separator);
  }

  async function selectRepository(repositoryKey: string): Promise<void> {
    if (!scopeContains(repositoryKey)) {
      reportScopeMismatch();
      return;
    }
    selectedRepositoryKey.value = repositoryKey;
    currentDirectoryPath.value = '';
    treeEntries.value = [];
    treeTruncated.value = false;
    await loadTreeForScope(repositoryKey, '', scopeEpoch);
  }

  async function openDirectory(relativePath: string): Promise<void> {
    const repositoryKey = selectedRepositoryKey.value;
    if (!repositoryKey) {
      reportScopeMismatch();
      return;
    }
    await loadTreeForScope(repositoryKey, relativePath, scopeEpoch);
  }

  function navigateToParentDirectory(): Promise<void> {
    return openDirectory(parentDirectory(currentDirectoryPath.value));
  }

  async function openDocument(reference: DocumentReference): Promise<void> {
    const normalized = normalizeDocumentReference(reference);
    if (!normalized || !scopeContains(normalized.repositoryKey)) {
      reportScopeMismatch();
      return;
    }
    if (selectedRepositoryKey.value !== normalized.repositoryKey) {
      selectedRepositoryKey.value = normalized.repositoryKey;
      await loadTreeForScope(
        normalized.repositoryKey,
        parentDirectory(normalized.relativePath),
        scopeEpoch,
      );
    }
    await readDocument(normalized, false, scopeEpoch);
  }

  async function refreshDocument(): Promise<void> {
    if (!currentDocument.value) return;
    await readDocument(currentDocument.value.reference, true, scopeEpoch);
  }

  async function downloadCurrent(): Promise<void> {
    const documentState = currentDocument.value;
    const workbenchId = options.workbenchId.value;
    if (!documentState
      || !documentState.downloadEnabled
      || !workbenchId
      || !scopeContains(documentState.reference.repositoryKey)) return;
    const epoch = scopeEpoch;
    const requestSequence = ++downloadRequestSequence;
    downloadLoading.value = true;
    documentError.value = '';
    try {
      const download = await apiClient.download({
        workbenchId,
        ...documentState.reference,
      });
      if (epoch !== scopeEpoch
        || requestSequence !== downloadRequestSequence
        || !currentDocument.value
        || !sameDocumentReference(currentDocument.value.reference, documentState.reference)
        || !currentDocument.value.downloadEnabled) return;
      saveDownload(download);
    } catch (error) {
      if (epoch === scopeEpoch && requestSequence === downloadRequestSequence) {
        reportDocumentError(error);
      }
    } finally {
      if (epoch === scopeEpoch && requestSequence === downloadRequestSequence) {
        downloadLoading.value = false;
      }
    }
  }

  function updateDocumentScrollTop(scrollTop: number): void {
    if (!currentDocument.value
      || !Number.isFinite(scrollTop)
      || scrollTop < 0) return;
    applyCurrentDocument({ ...currentDocument.value, scrollTop });
  }

  function createDocumentEventScope(
    generation: number,
  ): WorkbenchDocumentEventScope | null {
    const userId = options.userId.value;
    const workbenchId = options.workbenchId.value;
    const storageIdentity = currentDocumentStorageIdentity(options);
    if (!userId || !workbenchId || !storageIdentity) return null;
    return Object.freeze({
      userId,
      workbenchId,
      ...storageIdentity,
      generation,
    });
  }

  function receiveDocumentFileChanged(
    scope: WorkbenchDocumentEventScope | null,
    event: WorkbenchDocumentFileChangedEvent,
  ): boolean {
    const storageIdentity = currentDocumentStorageIdentity(options);
    if (!scope
      || scope !== documentEventScope.value
      || scope.generation !== scopeEpoch
      || scope.userId !== options.userId.value
      || scope.workbenchId !== options.workbenchId.value
      || !storageIdentity
      || !sameDocumentStorageIdentity(scope, storageIdentity)
      || !event
      || !currentDocument.value) {
      return false;
    }
    const reference = normalizeDocumentReference({
      repositoryKey: event.repositoryKey,
      relativePath: event.relativePath,
    });
    if (!reference
      || !scopeContains(reference.repositoryKey)
      || !sameDocumentReference(currentDocument.value.reference, reference)) {
      return false;
    }
    const current = currentDocument.value;
    const next = applyWorkbenchDocumentFileEvent(current, {
      reference,
      changeType: event.changeType,
      contentVersion: event.contentVersion,
    });
    if (next === current) return false;
    applyCurrentDocument(next);
    return true;
  }

  function applyPendingPointer(): void {
    if (!dragBounds || pendingClientX == null) return;
    const widthPercent = workbenchDocumentWidthFromPointer(dragBounds, pendingClientX);
    pendingClientX = null;
    if (widthPercent == null) return;
    applyLayout(resizeWorkbenchDocumentLayout(layout.value, widthPercent), false);
  }

  function requestPointerUpdate(): void {
    if (animationFrame != null) return;
    animationFrame = window.requestAnimationFrame(() => {
      animationFrame = null;
      applyPendingPointer();
    });
  }

  function releasePointerCapture(): void {
    if (!captureTarget || activePointerId == null) return;
    try {
      if (captureTarget.hasPointerCapture(activePointerId)) {
        captureTarget.releasePointerCapture(activePointerId);
      }
    } catch {
      // DOM 已卸载或 pointer 已被浏览器释放时无需继续处理。
    }
    captureTarget = null;
  }

  function cancelResize(): void {
    if (animationFrame != null && typeof window !== 'undefined') {
      window.cancelAnimationFrame(animationFrame);
    }
    animationFrame = null;
    pendingClientX = null;
    dragBounds = null;
    dragging.value = false;
    releasePointerCapture();
    activePointerId = null;
  }

  function beginResize(event: PointerEvent): void {
    if (isMobile.value || desktopMode.value !== 'NORMAL' || !splitRoot.value) return;
    const bounds = splitRoot.value.getBoundingClientRect();
    if (!Number.isFinite(bounds.width) || bounds.width <= 0) return;
    dragBounds = { left: bounds.left, width: bounds.width };
    activePointerId = event.pointerId;
    pendingClientX = event.clientX;
    dragging.value = true;
    event.preventDefault();
    const target = event.currentTarget;
    if (target instanceof Element) {
      captureTarget = target;
      try {
        if (!target.hasPointerCapture(event.pointerId)) {
          target.setPointerCapture(event.pointerId);
        }
      } catch {
        captureTarget = null;
      }
    }
    requestPointerUpdate();
  }

  function moveResize(event: PointerEvent): void {
    if (!dragging.value || activePointerId !== event.pointerId) return;
    pendingClientX = event.clientX;
    event.preventDefault();
    requestPointerUpdate();
  }

  function endResize(event: PointerEvent): void {
    if (!dragging.value || activePointerId !== event.pointerId) return;
    if (event.type !== 'pointercancel') pendingClientX = event.clientX;
    if (animationFrame != null) {
      window.cancelAnimationFrame(animationFrame);
      animationFrame = null;
    }
    applyPendingPointer();
    persist();
    releasePointerCapture();
    activePointerId = null;
    dragging.value = false;
    dragBounds = null;
    event.preventDefault();
  }

  function onViewportChange(event: MediaQueryListEvent): void {
    synchronizeViewport(event.matches);
  }

  watch(options.userId, restoreIdentity, { flush: 'sync' });
  watch(options.workbenchId, restoreIdentity, { flush: 'sync' });
  watch(options.stageInstanceIdentifier, restoreIdentity, { flush: 'sync' });
  if (options.repositories) {
    watch(options.repositories, restoreIdentity, { flush: 'sync', deep: true });
  }
  restoreIdentity();

  if (typeof window !== 'undefined' && getCurrentInstance()) {
    onMounted(() => {
      if (typeof window.matchMedia !== 'function') return;
      mediaQuery = window.matchMedia(MOBILE_VIEWPORT_QUERY);
      synchronizeViewport(mediaQuery.matches);
      mediaQuery.addEventListener('change', onViewportChange);
    });
  }

  if (getCurrentScope()) {
    onScopeDispose(() => {
      scopeEpoch++;
      documentEventScope.value = null;
      clearInlineImagePreview();
      cancelResize();
      mediaQuery?.removeEventListener('change', onViewportChange);
    });
  }

  return {
    splitRoot,
    layout,
    desktopMode,
    splitStyle,
    isMobile,
    mobileDrawerVisible,
    dragging,
    selectedRepositoryKey,
    currentDirectoryPath,
    treeEntries,
    treeTruncated,
    currentDocument,
    loadedContent,
    recentDocuments,
    treeLoading,
    contentLoading,
    downloadLoading,
    documentError,
    documentEventScope,
    collapse,
    maximize,
    restore,
    reset,
    openMobileDrawer,
    cancelResize,
    beginResize,
    moveResize,
    endResize,
    selectRepository,
    openDirectory,
    navigateToParentDirectory,
    openDocument,
    refreshDocument,
    downloadCurrent,
    closeDocument,
    updateDocumentScrollTop,
    receiveDocumentFileChanged,
  };
}
