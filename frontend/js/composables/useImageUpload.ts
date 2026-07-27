/**
 * useImageUpload composable: ChatPanel 图片/附件上传切片(FE-R3.5)。
 *
 * 从 chat-panel.js setup 抽出: pendingImages/pendingFile 状态 + 上限常量 +
 * readAsDataURL/uploadImageFile/beforeChatImageUpload/uploadChatImage/
 * removePendingImage/formatChatFileSize/extOfName/beforeChatFileUpload/
 * uploadChatFileBytes/uploadChatFile/removePendingFile/handlePaste。
 *
 * 与外部耦合: 上传需先 ensureSession + 读 sessionId(组件状态) + 读 workingDir(props),
 * 故这三项以参数注入。workingDir 传 Ref<string>(组件用 computed(() => props.workingDir))。
 *
 * 行为照搬 chat-panel.js 原内联实现,零逻辑变更。依赖 ElMessage。
 */
import { ref, type Ref } from 'vue';
import { ElMessage } from 'element-plus';

interface PendingImage {
  path: string;
  previewUrl: string;
  name: string;
}

interface PendingFile {
  path: string;
  name: string;
  size: number;
}

interface ChatIntegration {
  ensureSession: () => Promise<void>;
  sessionId: Ref<string>;
  workingDir: Ref<string>;
}

export function useImageUpload(chat: ChatIntegration): {
  pendingImages: Ref<PendingImage[]>;
  pendingFile: Ref<PendingFile | null>;
  maxImagesPerMessage: number;
  maxImageBytes: number;
  maxChatFileBytes: number;
  allowedChatFileExts: string[];
  readAsDataURL: (file: File) => Promise<string>;
  uploadImageFile: (file: File) => Promise<void>;
  beforeChatImageUpload: (file: File) => boolean;
  uploadChatImage: (options: { file: File; onSuccess?: (r: unknown) => void; onError?: (e: Error) => void }) => Promise<void>;
  removePendingImage: (idx: number) => void;
  formatChatFileSize: (bytes: number | null) => string;
  beforeChatFileUpload: (file: File) => boolean;
  uploadChatFile: (options: { file: File; onSuccess?: (r: unknown) => void; onError?: (e: Error) => void }) => Promise<void>;
  removePendingFile: () => void;
  handlePaste: (event: ClipboardEvent) => Promise<void>;
} {
  const pendingImages = ref<PendingImage[]>([]);
  const maxImagesPerMessage = 4;
  const maxImageBytes = 1024 * 1024;
  const pendingFile = ref<PendingFile | null>(null);
  const maxChatFileBytes = 5 * 1024 * 1024;
  const allowedChatFileExts = ['log', 'txt', 'json', 'csv', 'md', 'yaml', 'yml', 'xml', 'properties', 'stacktrace', 'out', 'conf', 'ini'];

  const readAsDataURL = (file: File) => new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });

  const uploadImageFile = async (file: File) => {
    await chat.ensureSession();
    const form = new FormData();
    form.append('file', file);
    const url = '/api/fs/upload-image?path=' + encodeURIComponent(chat.workingDir.value)
      + '&sessionId=' + encodeURIComponent(chat.sessionId.value);
    const res = await fetch(url, { method: 'POST', body: form });
    if (!res.ok) throw new Error(await res.text());
    const data: { path: string } = await res.json();
    const previewUrl = await readAsDataURL(file);
    pendingImages.value.push({ path: data.path, previewUrl, name: file.name || 'clipboard.png' });
  };

  const beforeChatImageUpload = (file: File) => {
    if (!chat.workingDir.value) { ElMessage.warning('请先选择工作目录'); return false; }
    if (file.type && file.type.indexOf('image/') !== 0) { ElMessage.warning('只能上传图片'); return false; }
    if (file.size > maxImageBytes) { ElMessage.warning('图片大小不能超过 1MB'); return false; }
    if (pendingImages.value.length >= maxImagesPerMessage) { ElMessage.warning('每条消息最多 ' + maxImagesPerMessage + ' 张图片'); return false; }
    return true;
  };

  const uploadChatImage = async (options: { file: File; onSuccess?: (r: unknown) => void; onError?: (e: Error) => void }) => {
    try {
      await uploadImageFile(options.file);
      ElMessage.success('图片已上传');
      if (options.onSuccess) options.onSuccess({});
    } catch (e) {
      ElMessage.error('图片上传失败: ' + (e as Error).message);
      if (options.onError) options.onError(e as Error);
    }
  };

  const removePendingImage = (idx: number) => { pendingImages.value.splice(idx, 1); };

  const formatChatFileSize = (bytes: number | null) => {
    if (bytes == null) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(2) + ' MB';
  };

  const extOfName = (name: string) => {
    if (!name) return '';
    const dot = name.lastIndexOf('.');
    if (dot < 0 || dot === name.length - 1) return '';
    return name.substring(dot + 1).toLowerCase();
  };

  const beforeChatFileUpload = (file: File) => {
    if (!chat.workingDir.value) { ElMessage.warning('请先选择工作目录'); return false; }
    if (pendingFile.value) { ElMessage.warning('每条消息只能附一个文件,请先移除当前附件'); return false; }
    if (file.size > maxChatFileBytes) { ElMessage.warning('文件大小不能超过 5MB'); return false; }
    const ext = extOfName(file.name);
    if (!ext || allowedChatFileExts.indexOf(ext) < 0) {
      ElMessage.warning('仅支持文本类附件:' + allowedChatFileExts.join('/'));
      return false;
    }
    return true;
  };

  const uploadChatFileBytes = async (file: File) => {
    await chat.ensureSession();
    const form = new FormData();
    form.append('file', file);
    const url = '/api/fs/upload-file?path=' + encodeURIComponent(chat.workingDir.value)
      + '&sessionId=' + encodeURIComponent(chat.sessionId.value);
    const res = await fetch(url, { method: 'POST', body: form });
    if (!res.ok) throw new Error(await res.text());
    const data: { path: string } = await res.json();
    pendingFile.value = { path: data.path, name: file.name, size: file.size };
  };

  const uploadChatFile = async (options: { file: File; onSuccess?: (r: unknown) => void; onError?: (e: Error) => void }) => {
    try {
      await uploadChatFileBytes(options.file);
      ElMessage.success('附件已上传');
      if (options.onSuccess) options.onSuccess({});
    } catch (e) {
      ElMessage.error('附件上传失败: ' + (e as Error).message);
      if (options.onError) options.onError(e as Error);
    }
  };

  const removePendingFile = () => { pendingFile.value = null; };

  const handlePaste = async (event: ClipboardEvent) => {
    const items = (event.clipboardData && event.clipboardData.items) || [];
    let imageFile: File | null = null;
    for (let i = 0; i < items.length; i++) {
      if (items[i].kind === 'file' && items[i].type.indexOf('image/') === 0) {
        imageFile = items[i].getAsFile();
        break;
      }
    }
    if (!imageFile) return;
    event.preventDefault();
    if (!chat.workingDir.value) { ElMessage.warning('请先选择工作目录'); return; }
    if (pendingImages.value.length >= maxImagesPerMessage) { ElMessage.warning('每条消息最多 ' + maxImagesPerMessage + ' 张图片'); return; }
    if (imageFile.size > maxImageBytes) { ElMessage.warning('图片大小不能超过 1MB'); return; }
    try {
      await uploadImageFile(imageFile);
      ElMessage.success('图片已上传');
    } catch (e) {
      ElMessage.error('图片上传失败: ' + (e as Error).message);
    }
  };

  return {
    pendingImages, pendingFile, maxImagesPerMessage, maxImageBytes, maxChatFileBytes,
    allowedChatFileExts, readAsDataURL, uploadImageFile, beforeChatImageUpload,
    uploadChatImage, removePendingImage, formatChatFileSize, beforeChatFileUpload,
    uploadChatFile, removePendingFile, handlePaste,
  };
}