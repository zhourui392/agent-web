/**
 * 共享对话附件展示合同。
 *
 * Chat 和 Workbench adapter 各自把领域附件映射为 ConversationAttachmentView，
 * 共享组件只消费此合同，不包含 storageKey、绝对路径或上传端点。
 *
 * @author alex
 * @since 2026-08-04
 */

/** 附件状态 */
export type ConversationAttachmentStatus =
  | 'UPLOADING'
  | 'AVAILABLE'
  | 'FAILED'
  | 'REMOVING';

/** 附件展示视图 — 共享组件的唯一数据合同 */
export interface ConversationAttachmentView {
  /** 稳定字符串标识 */
  attachmentKey: string;
  /** 显示名称 */
  displayName: string;
  /** MIME 类型 */
  mediaType: string;
  /** 文件大小（字节） */
  size: number;
  /** 预览 URL，null 表示无预览 */
  previewUrl: string | null;
  /** 附件状态 */
  status: ConversationAttachmentStatus;
  /** 错误信息，null 表示无错误 */
  errorMessage: string | null;
  /** 是否可移除 */
  removable: boolean;
  /** 是否可重试 */
  retryable: boolean;
}