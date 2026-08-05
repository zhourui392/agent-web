/**
 * Workbench Document Viewer 的布局、用户隔离恢复状态和文档一致性规则。
 *
 * <p>正文只存在内存中；浏览器持久化仅投影布局、文档引用、滚动位置和最近引用。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
import {
  isWorkbenchStageInstanceIdentifier,
} from './workbench-state.js';

const LAYOUT_SCHEMA_VERSION = 'workbench-stage-document-layout@1';
const DOCUMENTS_SCHEMA_VERSION = 'workbench-stage-documents@1';
const CONTROL_CHARACTER = /[\u0000-\u001f\u007f]/;
const WINDOWS_ABSOLUTE_PATH = /^[A-Za-z]:\//;
const MAX_PERSISTED_STATE_CHARS = 256 * 1024;

export const WORKBENCH_DOCUMENT_LIMITS = {
  minimumWidthPercent: 25,
  maximumWidthPercent: 70,
  defaultWidthPercent: 35,
  recentDocuments: 20,
  agentTextDocumentReferences: 20,
  agentTextChars: 32 * 1024,
  relativePathChars: 4096,
} as const;

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface WorkbenchDocumentStorageIdentity {
  userId: string;
  workbenchId: string;
  stageInstanceIdentifier: string;
}

export interface DocumentReference {
  repositoryKey: string;
  relativePath: string;
}

export interface DocumentReferenceGroup {
  repositoryKey: string;
  documents: ReadonlyArray<DocumentReference>;
}

export type WorkbenchDesktopDocumentLayoutMode =
  | 'NORMAL'
  | 'COLLAPSED'
  | 'MAXIMIZED';

export interface WorkbenchDesktopDocumentLayout {
  mode: WorkbenchDesktopDocumentLayoutMode;
  widthPercent: number;
  restoreWidthPercent: number;
}

export interface WorkbenchMobileDrawerLayout {
  mode: 'MOBILE_DRAWER';
  widthPercent: number;
  restoreWidthPercent: number;
  desktopLayout: WorkbenchDesktopDocumentLayout;
}

export type WorkbenchDocumentLayoutState =
  | WorkbenchDesktopDocumentLayout
  | WorkbenchMobileDrawerLayout;

export interface WorkbenchDocumentViewState {
  reference: DocumentReference;
  content: string | null;
  contentVersion: string | null;
  scrollTop: number;
  stale: boolean;
  deleted: boolean;
  downloadEnabled: boolean;
}

export interface LoadedWorkbenchDocumentInput {
  reference: DocumentReference;
  content: string | null;
  contentVersion: string;
  scrollTop?: number;
}

export interface WorkbenchDocumentRefresh {
  reference: DocumentReference;
  content: string | null;
  contentVersion: string;
}

export interface WorkbenchDocumentFileChange {
  reference: DocumentReference;
  contentVersion: string;
}

export interface WorkbenchDocumentFileEvent extends WorkbenchDocumentFileChange {
  changeType: string;
}

export interface WorkbenchDocumentSessionState {
  layout: WorkbenchDocumentLayoutState;
  currentDocument: WorkbenchDocumentViewState | null;
  recentDocuments: ReadonlyArray<DocumentReference>;
}

export interface WorkbenchDocumentStateStore {
  load(): WorkbenchDocumentSessionState;
  save(state: WorkbenchDocumentSessionState): void;
  clear(): void;
}

interface PersistedLayoutState extends WorkbenchDocumentStorageIdentity {
  schemaVersion: typeof LAYOUT_SCHEMA_VERSION;
  layout: WorkbenchDocumentLayoutState;
}

interface PersistedDocumentsState extends WorkbenchDocumentStorageIdentity {
  schemaVersion: typeof DOCUMENTS_SCHEMA_VERSION;
  current: {
    reference: DocumentReference;
    scrollTop: number;
  } | null;
  recentDocuments: ReadonlyArray<DocumentReference>;
}

export function createWorkbenchDocumentLayout(): WorkbenchDesktopDocumentLayout {
  return normalLayout(WORKBENCH_DOCUMENT_LIMITS.defaultWidthPercent);
}

export function resizeWorkbenchDocumentLayout(
  state: WorkbenchDocumentLayoutState,
  widthPercent: number,
): WorkbenchDocumentLayoutState {
  if (!state || state.mode !== 'NORMAL' || !Number.isFinite(widthPercent)) return state;
  const width = clampWidth(widthPercent);
  if (width === state.widthPercent && width === state.restoreWidthPercent) return state;
  return normalLayout(width);
}

export function collapseWorkbenchDocumentLayout(
  state: WorkbenchDocumentLayoutState,
): WorkbenchDesktopDocumentLayout {
  const desktop = desktopLayoutOf(state);
  const width = restorableWidth(desktop);
  return {
    mode: 'COLLAPSED',
    widthPercent: width,
    restoreWidthPercent: width,
  };
}

export function maximizeWorkbenchDocumentLayout(
  state: WorkbenchDocumentLayoutState,
): WorkbenchDesktopDocumentLayout {
  const desktop = desktopLayoutOf(state);
  const width = restorableWidth(desktop);
  return {
    mode: 'MAXIMIZED',
    widthPercent: width,
    restoreWidthPercent: width,
  };
}

export function restoreWorkbenchDocumentLayout(
  state: WorkbenchDocumentLayoutState,
): WorkbenchDesktopDocumentLayout {
  const desktop = desktopLayoutOf(state);
  if (desktop.mode === 'NORMAL') return desktop;
  return normalLayout(restorableWidth(desktop));
}

/**
 * Resize handle 双击或“恢复默认”动作统一回到 35%。
 */
export function resetWorkbenchDocumentLayout(
  _state?: WorkbenchDocumentLayoutState,
): WorkbenchDesktopDocumentLayout {
  return createWorkbenchDocumentLayout();
}

export function enterWorkbenchMobileDrawer(
  state: WorkbenchDocumentLayoutState,
): WorkbenchMobileDrawerLayout {
  if (state.mode === 'MOBILE_DRAWER') return state;
  return {
    mode: 'MOBILE_DRAWER',
    widthPercent: state.widthPercent,
    restoreWidthPercent: state.restoreWidthPercent,
    desktopLayout: cloneDesktopLayout(state),
  };
}

export function exitWorkbenchMobileDrawer(
  state: WorkbenchDocumentLayoutState,
): WorkbenchDesktopDocumentLayout {
  return state.mode === 'MOBILE_DRAWER'
    ? cloneDesktopLayout(state.desktopLayout)
    : state;
}

export function conversationUsesFullWidth(
  state: WorkbenchDocumentLayoutState,
): boolean {
  return state.mode === 'COLLAPSED';
}

export function normalizeDocumentReference(value: unknown): DocumentReference | null {
  if (!isRecord(value)) return null;
  const repositoryKey = value.repositoryKey;
  const relativePath = value.relativePath;
  if (!isPosixRelativePath(repositoryKey, WORKBENCH_DOCUMENT_LIMITS.relativePathChars)
    || !isPosixRelativePath(relativePath, WORKBENCH_DOCUMENT_LIMITS.relativePathChars)) return null;
  return { repositoryKey, relativePath };
}

/**
 * 只把已结构化且属于冻结 Repository Scope 的引用提升为可打开入口。
 */
export function authorizedDocumentReference(
  value: unknown,
  repositoryKeys: ReadonlyArray<string>,
): DocumentReference | null {
  const normalized = normalizeDocumentReference(value);
  if (!normalized || !Array.isArray(repositoryKeys)) return null;
  return repositoryKeys.includes(normalized.repositoryKey) ? normalized : null;
}

/**
 * 从 Agent 文本的反引号代码片段中提取 best-effort 文档入口。
 *
 * <p>候选必须完整写出一个已选 repositoryKey 和 POSIX relativePath；仓库前缀存在歧义时
 * fail closed，不猜最短或最长仓库。返回结果有界且按首次出现去重。</p>
 */
export function extractAuthorizedAgentDocumentReferences(
  content: unknown,
  repositoryKeys: ReadonlyArray<string>,
): ReadonlyArray<DocumentReference> {
  if (typeof content !== 'string' || !Array.isArray(repositoryKeys)) return [];
  const selectedRepositoryKeys = Array.from(new Set(repositoryKeys.filter(repositoryKey =>
    normalizeDocumentReference({ repositoryKey, relativePath: 'placeholder' }) != null,
  )));
  if (!selectedRepositoryKeys.length) return [];

  const references: DocumentReference[] = [];
  const seen = new Set<string>();
  const inlineCode = /`([^`\r\n]+)`/g;
  const boundedContent = content.slice(0, WORKBENCH_DOCUMENT_LIMITS.agentTextChars);
  let match: RegExpExecArray | null;
  while ((match = inlineCode.exec(boundedContent)) != null) {
    const candidate = match[1].trim();
    const matchedRepositoryKeys = selectedRepositoryKeys.filter(repositoryKey =>
      candidate.startsWith(`${repositoryKey}/`),
    );
    if (matchedRepositoryKeys.length !== 1) continue;
    const repositoryKey = matchedRepositoryKeys[0];
    const reference = authorizedDocumentReference({
      repositoryKey,
      relativePath: candidate.slice(repositoryKey.length + 1),
    }, selectedRepositoryKeys);
    if (!reference) continue;
    const identity = `${reference.repositoryKey}\u0000${reference.relativePath}`;
    if (seen.has(identity)) continue;
    seen.add(identity);
    references.push(reference);
    if (references.length >= WORKBENCH_DOCUMENT_LIMITS.agentTextDocumentReferences) break;
  }
  return references;
}

/**
 * 保留最近访问顺序，同时显式按 repositoryKey 分组，避免同名路径混淆。
 */
export function groupDocumentReferencesByRepository(
  references: ReadonlyArray<DocumentReference>,
): ReadonlyArray<DocumentReferenceGroup> {
  if (!Array.isArray(references)) return [];
  const groups = new Map<string, DocumentReference[]>();
  const seen = new Set<string>();
  for (const candidate of references) {
    const reference = normalizeDocumentReference(candidate);
    if (!reference) continue;
    const key = `${reference.repositoryKey}\u0000${reference.relativePath}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const documents = groups.get(reference.repositoryKey);
    if (documents) documents.push(reference);
    else groups.set(reference.repositoryKey, [reference]);
  }
  return Array.from(groups, ([repositoryKey, documents]) => ({
    repositoryKey,
    documents,
  }));
}

export function sameDocumentReference(
  left: DocumentReference,
  right: DocumentReference,
): boolean {
  const normalizedLeft = normalizeDocumentReference(left);
  const normalizedRight = normalizeDocumentReference(right);
  return normalizedLeft != null
    && normalizedRight != null
    && normalizedLeft.repositoryKey === normalizedRight.repositoryKey
    && normalizedLeft.relativePath === normalizedRight.relativePath;
}

export function rememberRecentDocument(
  recentDocuments: ReadonlyArray<DocumentReference>,
  reference: DocumentReference,
): ReadonlyArray<DocumentReference> {
  const normalized = requireDocumentReference(reference);
  const current = Array.isArray(recentDocuments) ? recentDocuments : [];
  const retained: DocumentReference[] = [];
  for (const candidate of current) {
    const safeCandidate = normalizeDocumentReference(candidate);
    if (!safeCandidate || sameDocumentReference(safeCandidate, normalized)) continue;
    retained.push(safeCandidate);
    if (retained.length >= WORKBENCH_DOCUMENT_LIMITS.recentDocuments - 1) break;
  }
  return [normalized, ...retained];
}

export function createLoadedWorkbenchDocument(
  input: LoadedWorkbenchDocumentInput,
): WorkbenchDocumentViewState {
  if (!input || input.content !== null && typeof input.content !== 'string') {
    throw new IllegalStateError('loaded document content is invalid');
  }
  return {
    reference: requireDocumentReference(input.reference),
    content: input.content,
    contentVersion: requireContentVersion(input.contentVersion),
    scrollTop: requireScrollTop(input.scrollTop ?? 0),
    stale: false,
    deleted: false,
    downloadEnabled: true,
  };
}

export function switchWorkbenchDocument(
  state: WorkbenchDocumentViewState,
  reference: DocumentReference,
): WorkbenchDocumentViewState {
  const normalized = requireDocumentReference(reference);
  if (state && sameDocumentReference(state.reference, normalized)) return state;
  return emptyDocument(normalized, 0);
}

export function applyWorkbenchDocumentFileChanged(
  state: WorkbenchDocumentViewState,
  change: WorkbenchDocumentFileChange,
): WorkbenchDocumentViewState {
  if (!state || !change) return state;
  const reference = normalizeDocumentReference(change.reference);
  const changedVersion = optionalContentVersion(change.contentVersion);
  if (!reference
    || !changedVersion
    || !sameDocumentReference(state.reference, reference)
    || state.contentVersion == null
    || state.contentVersion === changedVersion
    || state.stale) {
    return state;
  }
  return { ...state, stale: true };
}

/**
 * 把结构化 FILE_CHANGED 投影到当前文档。删除只改变可用性标记，其他变更只标 stale；
 * 两种情况都不得替换已加载正文、版本或滚动位置。
 */
export function applyWorkbenchDocumentFileEvent(
  state: WorkbenchDocumentViewState,
  event: WorkbenchDocumentFileEvent,
): WorkbenchDocumentViewState {
  if (!state || !event) return state;
  const reference = normalizeDocumentReference(event.reference);
  const contentVersion = optionalContentVersion(event.contentVersion);
  const changeType = optionalChangeType(event.changeType);
  if (!reference
    || !contentVersion
    || !changeType
    || !sameDocumentReference(state.reference, reference)) {
    return state;
  }
  if (changeType === 'DELETED') return markWorkbenchDocumentDeleted(state);
  const changed = applyWorkbenchDocumentFileChanged(state, { reference, contentVersion });
  return changed !== state && state.deleted
    ? { ...changed, deleted: false }
    : changed;
}

/**
 * 304 说明浏览器已加载版本仍有效，只清除提示，不触碰正文和滚动位置。
 */
export function applyWorkbenchDocumentNotModified(
  state: WorkbenchDocumentViewState,
): WorkbenchDocumentViewState {
  if (!state || !state.stale && !state.deleted && state.downloadEnabled) return state;
  return {
    ...state,
    stale: false,
    deleted: false,
    downloadEnabled: true,
  };
}

export function refreshWorkbenchDocument(
  state: WorkbenchDocumentViewState,
  refreshed: WorkbenchDocumentRefresh,
  scrollTop?: number,
): WorkbenchDocumentViewState {
  if (!state
    || !refreshed
    || refreshed.content !== null && typeof refreshed.content !== 'string') return state;
  const reference = normalizeDocumentReference(refreshed.reference);
  const contentVersion = optionalContentVersion(refreshed.contentVersion);
  const nextScrollTop = scrollTop == null ? state.scrollTop : optionalScrollTop(scrollTop);
  if (!reference
    || !sameDocumentReference(state.reference, reference)
    || !contentVersion
    || nextScrollTop == null) {
    return state;
  }
  return {
    ...state,
    reference,
    content: refreshed.content,
    contentVersion,
    scrollTop: nextScrollTop,
    stale: false,
    deleted: false,
    downloadEnabled: true,
  };
}

export function markWorkbenchDocumentDeleted(
  state: WorkbenchDocumentViewState,
): WorkbenchDocumentViewState {
  if (!state || state.deleted && !state.stale && !state.downloadEnabled) return state;
  return {
    ...state,
    stale: false,
    deleted: true,
    downloadEnabled: false,
  };
}

export function workbenchDocumentLayoutStorageKey(
  identity: WorkbenchDocumentStorageIdentity,
): string {
  const normalized = normalizeStorageIdentity(identity);
  return storageKey('agent-web:workbench-layout', normalized);
}

export function workbenchDocumentsStorageKey(
  identity: WorkbenchDocumentStorageIdentity,
): string {
  const normalized = normalizeStorageIdentity(identity);
  return storageKey('agent-web:workbench-documents', normalized);
}

export function createWorkbenchDocumentStateStore(
  storage: StorageLike,
  identity: WorkbenchDocumentStorageIdentity,
): WorkbenchDocumentStateStore {
  if (!storage) throw new IllegalStateError('document state storage is required');
  const normalizedIdentity = normalizeStorageIdentity(identity);
  const layoutKey = workbenchDocumentLayoutStorageKey(normalizedIdentity);
  const documentsKey = workbenchDocumentsStorageKey(normalizedIdentity);

  return {
    load(): WorkbenchDocumentSessionState {
      const persistedLayout = readPersisted(
        storage,
        layoutKey,
        raw => parsePersistedLayout(raw, normalizedIdentity),
      );
      const persistedDocuments = readPersisted(
        storage,
        documentsKey,
        raw => parsePersistedDocuments(raw, normalizedIdentity),
      );
      return {
        layout: persistedLayout?.layout ?? createWorkbenchDocumentLayout(),
        currentDocument: persistedDocuments?.current
          ? emptyDocument(
            persistedDocuments.current.reference,
            persistedDocuments.current.scrollTop,
          )
          : null,
        recentDocuments: persistedDocuments?.recentDocuments ?? [],
      };
    },

    save(state: WorkbenchDocumentSessionState): void {
      if (!state) throw new IllegalStateError('document session state is required');
      const layout = normalizeLayout(state.layout);
      if (!layout) throw new IllegalStateError('document layout is invalid');
      const current = state.currentDocument
        ? {
          reference: requireDocumentReference(state.currentDocument.reference),
          scrollTop: requireScrollTop(state.currentDocument.scrollTop),
        }
        : null;
      const recentDocuments = normalizeRecentDocuments(state.recentDocuments, true);
      if (!recentDocuments) throw new IllegalStateError('recent documents are invalid');

      const persistedLayout: PersistedLayoutState = {
        schemaVersion: LAYOUT_SCHEMA_VERSION,
        ...normalizedIdentity,
        layout,
      };
      const persistedDocuments: PersistedDocumentsState = {
        schemaVersion: DOCUMENTS_SCHEMA_VERSION,
        ...normalizedIdentity,
        current,
        recentDocuments,
      };
      storage.setItem(layoutKey, JSON.stringify(persistedLayout));
      storage.setItem(documentsKey, JSON.stringify(persistedDocuments));
    },

    clear(): void {
      storage.removeItem(layoutKey);
      storage.removeItem(documentsKey);
    },
  };
}

function normalLayout(widthPercent: number): WorkbenchDesktopDocumentLayout {
  return {
    mode: 'NORMAL',
    widthPercent,
    restoreWidthPercent: widthPercent,
  };
}

function restorableWidth(layout: WorkbenchDesktopDocumentLayout): number {
  return layout.mode === 'NORMAL'
    ? layout.widthPercent
    : layout.restoreWidthPercent;
}

function clampWidth(widthPercent: number): number {
  return Math.min(
    WORKBENCH_DOCUMENT_LIMITS.maximumWidthPercent,
    Math.max(WORKBENCH_DOCUMENT_LIMITS.minimumWidthPercent, widthPercent),
  );
}

function cloneDesktopLayout(
  layout: WorkbenchDesktopDocumentLayout,
): WorkbenchDesktopDocumentLayout {
  return {
    mode: layout.mode,
    widthPercent: layout.widthPercent,
    restoreWidthPercent: layout.restoreWidthPercent,
  };
}

function desktopLayoutOf(
  state: WorkbenchDocumentLayoutState,
): WorkbenchDesktopDocumentLayout {
  if (!state) return createWorkbenchDocumentLayout();
  return state.mode === 'MOBILE_DRAWER'
    ? cloneDesktopLayout(state.desktopLayout)
    : cloneDesktopLayout(state);
}

function normalizeLayout(value: unknown): WorkbenchDocumentLayoutState | null {
  if (!isRecord(value) || typeof value.mode !== 'string') return null;
  if (value.mode === 'MOBILE_DRAWER') {
    const widthPercent = validWidth(value.widthPercent);
    const restoreWidthPercent = validWidth(value.restoreWidthPercent);
    const desktopLayout = normalizeDesktopLayout(value.desktopLayout);
    if (widthPercent == null || restoreWidthPercent == null || !desktopLayout) return null;
    return {
      mode: 'MOBILE_DRAWER',
      widthPercent,
      restoreWidthPercent,
      desktopLayout,
    };
  }
  return normalizeDesktopLayout(value);
}

function normalizeDesktopLayout(value: unknown): WorkbenchDesktopDocumentLayout | null {
  if (!isRecord(value)
    || value.mode !== 'NORMAL' && value.mode !== 'COLLAPSED' && value.mode !== 'MAXIMIZED') {
    return null;
  }
  const widthPercent = validWidth(value.widthPercent);
  const restoreWidthPercent = validWidth(value.restoreWidthPercent);
  if (widthPercent == null || restoreWidthPercent == null) return null;
  return { mode: value.mode, widthPercent, restoreWidthPercent };
}

function validWidth(value: unknown): number | null {
  return typeof value === 'number'
    && Number.isFinite(value)
    && value >= WORKBENCH_DOCUMENT_LIMITS.minimumWidthPercent
    && value <= WORKBENCH_DOCUMENT_LIMITS.maximumWidthPercent
    ? value
    : null;
}

function isPosixRelativePath(value: unknown, maximumChars: number): value is string {
  if (typeof value !== 'string'
    || value.length === 0
    || value.length > maximumChars
    || value.startsWith('/')
    || WINDOWS_ABSOLUTE_PATH.test(value)
    || value.includes('\\')
    || CONTROL_CHARACTER.test(value)) {
    return false;
  }
  const segments = value.split('/');
  return segments.every(segment => segment.length > 0 && segment !== '.' && segment !== '..');
}

function requireDocumentReference(value: unknown): DocumentReference {
  const normalized = normalizeDocumentReference(value);
  if (!normalized) throw new IllegalStateError('document reference is invalid');
  return normalized;
}

function optionalContentVersion(value: unknown): string | null {
  return typeof value === 'string'
    && value.length > 0
    && value.length <= 512
    && !CONTROL_CHARACTER.test(value)
    ? value
    : null;
}

function optionalChangeType(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim().toUpperCase();
  return normalized
    && normalized.length <= 80
    && !CONTROL_CHARACTER.test(normalized)
    ? normalized
    : null;
}

function requireContentVersion(value: unknown): string {
  const normalized = optionalContentVersion(value);
  if (!normalized) throw new IllegalStateError('document content version is invalid');
  return normalized;
}

function optionalScrollTop(value: unknown): number | null {
  return typeof value === 'number'
    && Number.isFinite(value)
    && value >= 0
    && value <= Number.MAX_SAFE_INTEGER
    ? value
    : null;
}

function requireScrollTop(value: unknown): number {
  const normalized = optionalScrollTop(value);
  if (normalized == null) throw new IllegalStateError('document scroll position is invalid');
  return normalized;
}

function emptyDocument(
  reference: DocumentReference,
  scrollTop: number,
): WorkbenchDocumentViewState {
  return {
    reference: requireDocumentReference(reference),
    content: null,
    contentVersion: null,
    scrollTop: requireScrollTop(scrollTop),
    stale: false,
    deleted: false,
    downloadEnabled: true,
  };
}

function normalizeStorageIdentity(
  identity: WorkbenchDocumentStorageIdentity,
): WorkbenchDocumentStorageIdentity {
  if (!identity) throw new IllegalStateError('document storage identity is required');
  const userId = boundedIdentity(identity.userId, 'authenticated user id');
  const workbenchId = boundedIdentity(identity.workbenchId, 'workbench id');
  if (!isWorkbenchStageInstanceIdentifier(
    identity.stageInstanceIdentifier,
  )) {
    throw new IllegalStateError('workbench stage instance identifier is invalid');
  }
  return {
    userId,
    workbenchId,
    stageInstanceIdentifier: identity.stageInstanceIdentifier,
  };
}

function boundedIdentity(value: unknown, name: string): string {
  if (typeof value !== 'string'
    || !value.trim()
    || value.length > 128
    || CONTROL_CHARACTER.test(value)) {
    throw new IllegalStateError(`${name} is invalid`);
  }
  return value;
}

function storageKey(
  prefix: string,
  identity: WorkbenchDocumentStorageIdentity,
): string {
  const base = [
    prefix,
    encodeURIComponent(identity.userId),
    encodeURIComponent(identity.workbenchId),
  ];
  return [...base, encodeURIComponent(identity.stageInstanceIdentifier)]
    .join(':');
}

function identityMatches(
  value: Record<string, unknown>,
  identity: WorkbenchDocumentStorageIdentity,
  schemaVersion: string,
): boolean {
  return value.schemaVersion === schemaVersion
    && value.userId === identity.userId
    && value.workbenchId === identity.workbenchId
    && value.stageInstanceIdentifier === identity.stageInstanceIdentifier;
}

function parsePersistedLayout(
  raw: string,
  identity: WorkbenchDocumentStorageIdentity,
): PersistedLayoutState | null {
  const parsed = parseJsonRecord(raw);
  if (!parsed || !identityMatches(parsed, identity, LAYOUT_SCHEMA_VERSION)) return null;
  const layout = normalizeLayout(parsed.layout);
  if (!layout) return null;
  return {
    schemaVersion: LAYOUT_SCHEMA_VERSION,
    ...identity,
    layout,
  };
}

function parsePersistedDocuments(
  raw: string,
  identity: WorkbenchDocumentStorageIdentity,
): PersistedDocumentsState | null {
  const parsed = parseJsonRecord(raw);
  if (!parsed || !identityMatches(parsed, identity, DOCUMENTS_SCHEMA_VERSION)) return null;
  let current: PersistedDocumentsState['current'] = null;
  if (parsed.current != null) {
    if (!isRecord(parsed.current)) return null;
    const reference = normalizeDocumentReference(parsed.current.reference);
    const scrollTop = optionalScrollTop(parsed.current.scrollTop);
    if (!reference || scrollTop == null) return null;
    current = { reference, scrollTop };
  }
  const recentDocuments = normalizeRecentDocuments(parsed.recentDocuments, false);
  if (!recentDocuments) return null;
  return {
    schemaVersion: DOCUMENTS_SCHEMA_VERSION,
    ...identity,
    current,
    recentDocuments,
  };
}

function normalizeRecentDocuments(
  value: unknown,
  acceptOversized: boolean,
): ReadonlyArray<DocumentReference> | null {
  if (!Array.isArray(value)
    || !acceptOversized && value.length > WORKBENCH_DOCUMENT_LIMITS.recentDocuments
    || value.length > 1000) {
    return null;
  }
  const normalized: DocumentReference[] = [];
  const identities = new Set<string>();
  for (const candidate of value) {
    const reference = normalizeDocumentReference(candidate);
    if (!reference) return null;
    const identity = `${reference.repositoryKey}\n${reference.relativePath}`;
    if (identities.has(identity)) continue;
    identities.add(identity);
    normalized.push(reference);
    if (normalized.length >= WORKBENCH_DOCUMENT_LIMITS.recentDocuments) break;
  }
  return normalized;
}

function parseJsonRecord(raw: string): Record<string, unknown> | null {
  if (!raw || raw.length > MAX_PERSISTED_STATE_CHARS) return null;
  try {
    const parsed = JSON.parse(raw) as unknown;
    return isRecord(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function readPersisted<T>(
  storage: StorageLike,
  key: string,
  parser: (raw: string) => T | null,
): T | null {
  let raw: string | null;
  try {
    raw = storage.getItem(key);
  } catch {
    return null;
  }
  if (raw == null) return null;
  const parsed = parser(raw);
  if (!parsed) {
    try {
      storage.removeItem(key);
    } catch {
      // 清理失败仍按没有可恢复状态处理。
    }
  }
  return parsed;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value);
}

class IllegalStateError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'IllegalStateError';
  }
}
