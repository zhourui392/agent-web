/**
 * useFeedback composable: ChatPanel 分析评价切片(FE-R3.5)。
 *
 * 从 chat-panel.js setup 抽出: feedback 状态(feedback/feedbackDialogVisible/
 * feedbackCommentDraft/feedbackSaving) + loadFeedback/persistFeedback/setRating/
 * openFeedbackDialog/submitFeedbackComment。
 *
 * 与外部耦合: persistFeedback 读 sessionId(组件状态),故以参数注入。
 *
 * 行为照搬 chat-panel.js 原内联实现,零逻辑变更。依赖 ElMessage。
 */
import { ref, type Ref } from 'vue';
import { ElMessage } from 'element-plus';

type Rating = 'CORRECT' | 'PARTIALLY_CORRECT' | 'INCORRECT' | null;

interface FeedbackState {
  rating: Rating;
  comment: string | null;
}

interface SessionIntegration {
  sessionId: Ref<string>;
}

export function useFeedback(session: SessionIntegration): {
  feedback: Ref<FeedbackState>;
  feedbackDialogVisible: Ref<boolean>;
  feedbackCommentDraft: Ref<string>;
  feedbackSaving: Ref<boolean>;
  loadFeedback: (sid: string) => Promise<void>;
  persistFeedback: () => Promise<boolean>;
  setRating: (rating: Rating) => Promise<void>;
  openFeedbackDialog: () => void;
  submitFeedbackComment: () => Promise<void>;
} {
  const feedback = ref<FeedbackState>({ rating: null, comment: null });
  const feedbackDialogVisible = ref(false);
  const feedbackCommentDraft = ref('');
  const feedbackSaving = ref(false);

  const loadFeedback = async (sid: string) => {
    feedback.value = { rating: null, comment: null };
    if (!sid) return;
    try {
      const data: { rating?: Rating; comment?: string | null } = await fetch('/api/chat/session/' + encodeURIComponent(sid) + '/feedback').then((r) => r.json());
      feedback.value = { rating: data.rating || null, comment: data.comment || null };
    } catch (e) { /* best effort */ }
  };

  const persistFeedback = async () => {
    const sid = session.sessionId.value;
    if (!sid) return false;
    try {
      const res = await fetch('/api/chat/session/' + encodeURIComponent(sid) + '/feedback', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ rating: feedback.value.rating, comment: feedback.value.comment }),
      });
      if (!res.ok) throw new Error(await res.text());
      const data: { rating?: Rating; comment?: string | null } = await res.json();
      feedback.value = { rating: data.rating || null, comment: data.comment || null };
      return true;
    } catch (e) {
      ElMessage.error('保存反馈失败: ' + (e as Error).message);
      return false;
    }
  };

  const setRating = async (rating: Rating) => {
    const prev = feedback.value.rating;
    const next: Rating = prev === rating ? null : rating;
    feedback.value = { rating: next, comment: feedback.value.comment };
    const ok = await persistFeedback();
    if (ok) {
      ElMessage.success(next ? '已记录评价' : '已取消评价');
    } else {
      feedback.value = { rating: prev, comment: feedback.value.comment };
    }
  };

  const openFeedbackDialog = () => {
    feedbackCommentDraft.value = feedback.value.comment || '';
    feedbackDialogVisible.value = true;
  };

  const submitFeedbackComment = async () => {
    feedbackSaving.value = true;
    const prev = feedback.value.comment;
    feedback.value = { rating: feedback.value.rating, comment: feedbackCommentDraft.value.trim() || null };
    const ok = await persistFeedback();
    feedbackSaving.value = false;
    if (ok) {
      feedbackDialogVisible.value = false;
      ElMessage.success('反馈已提交');
    } else {
      feedback.value = { rating: feedback.value.rating, comment: prev };
    }
  };

  return {
    feedback, feedbackDialogVisible, feedbackCommentDraft, feedbackSaving,
    loadFeedback, persistFeedback, setRating, openFeedbackDialog, submitFeedbackComment,
  };
}