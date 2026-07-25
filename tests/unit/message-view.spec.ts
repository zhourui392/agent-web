import { describe, it, expect } from 'vitest';
import { createRequire } from 'node:module';

const requireCjs = createRequire(import.meta.url);
// message-view 依赖 formatters, 先加载 formatters 设置 globalThis.AgentFormatters
const formatters = requireCjs('../../src/main/resources/static/js/lib/formatters.js');
(globalThis as Record<string, unknown>).AgentFormatters = formatters;
// 再加载 message-view (它读 globalThis.AgentFormatters)
const messageView = requireCjs('../../src/main/resources/static/js/lib/message-view.js') as {
  ROLE_LABELS: Record<string, string>;
  enrichMessage: (msg: Record<string, unknown>, options?: { withRecall?: boolean }) => Record<string, unknown>;
  mapMessages: (rawMsgs: Record<string, unknown>[] | null, options?: { withRecall?: boolean }) => Record<string, unknown>[];
  roleLabel: (r: string) => string;
};

const STREAM_JSON = JSON.stringify({
  type: 'stream_event',
  event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'hello' } }
});

describe('AgentMessageView', () => {
  describe('enrichMessage (without recall, 默认)', () => {
    it('assistant stream-json -> parsedSegments, 无 recall 字段', () => {
      const result = messageView.enrichMessage({ role: 'assistant', content: STREAM_JSON });
      expect(result.parsedSegments).toHaveLength(1);
      expect((result.parsedSegments as Array<{ content: string }>)[0].content).toBe('hello');
      expect(result.recall).toBeUndefined();
      expect(result.recallOpen).toBeUndefined();
    });

    it('user -> bodyText + images', () => {
      const result = messageView.enrichMessage({ role: 'user', content: 'hello\n/path/to/img.png' });
      expect(result.bodyText).toBe('hello');
      expect(result.images).toEqual(['/path/to/img.png']);
    });

    it('其他 role -> 浅拷贝, 无 recall/parsedSegments', () => {
      const msg = { role: 'system', content: 'sys' };
      const result = messageView.enrichMessage(msg);
      expect(result).not.toBe(msg);
      expect(result.content).toBe('sys');
      expect(result.recall).toBeUndefined();
      expect(result.parsedSegments).toBeUndefined();
    });

    it('null msg -> null', () => {
      expect(messageView.enrichMessage(null as unknown as Record<string, unknown>)).toBeNull();
    });
  });

  describe('enrichMessage (withRecall: true)', () => {
    it('assistant stream-json + recall -> parsedSegments + recall + recallOpen', () => {
      const result = messageView.enrichMessage(
        { role: 'assistant', content: STREAM_JSON, recall: JSON.stringify({ title: 't' }) },
        { withRecall: true }
      );
      expect(result.parsedSegments).toHaveLength(1);
      expect(result.recall).toEqual({ title: 't' });
      expect(result.recallOpen).toBe(false);
    });

    it('assistant 非 stream + recall -> recall + recallOpen, 无 parsedSegments', () => {
      const result = messageView.enrichMessage(
        { role: 'assistant', content: 'plain text', recall: '{"title":"x"}' },
        { withRecall: true }
      );
      expect(result.parsedSegments).toBeUndefined();
      expect(result.recall).toEqual({ title: 'x' });
      expect(result.recallOpen).toBe(false);
    });

    it('非法 recall JSON -> recall=null', () => {
      const result = messageView.enrichMessage(
        { role: 'assistant', content: 'text', recall: 'invalid{json' },
        { withRecall: true }
      );
      expect(result.recall).toBeNull();
      expect(result.recallOpen).toBe(false);
    });

    it('user + withRecall -> bodyText + images, recall 保持原始字符串(user 不解析 recall)', () => {
      const result = messageView.enrichMessage(
        { role: 'user', content: 'hi', recall: '{"x":1}' },
        { withRecall: true }
      );
      expect(result.bodyText).toBe('hi');
      expect(result.recallOpen).toBeUndefined();
    });
  });

  describe('mapMessages', () => {
    it('数组映射 + withRecall', () => {
      const result = messageView.mapMessages(
        [
          { role: 'user', content: 'hi' },
          { role: 'assistant', content: 'plain', recall: '{"title":"t"}' },
        ],
        { withRecall: true }
      );
      expect(result).toHaveLength(2);
      expect(result[0].bodyText).toBe('hi');
      expect(result[1].recall).toEqual({ title: 't' });
    });

    it('默认不带 recall (匹配 conversations/refinery enrichMessage 行为)', () => {
      const result = messageView.mapMessages([
        { role: 'assistant', content: 'plain', recall: '{"title":"t"}' },
      ]);
      // withRecall=false: Object.assign 复制原始 recall 字符串, 但不加 recallOpen/parsedSegments
      expect(result[0].recallOpen).toBeUndefined();
      expect(result[0].parsedSegments).toBeUndefined();
    });

    it('null/空输入 -> []', () => {
      expect(messageView.mapMessages(null)).toEqual([]);
      expect(messageView.mapMessages([])).toEqual([]);
    });
  });

  describe('roleLabel', () => {
    it('已知 role', () => {
      expect(messageView.roleLabel('user')).toBe('用户');
      expect(messageView.roleLabel('assistant')).toBe('助手');
      expect(messageView.roleLabel('system')).toBe('系统');
    });
    it('未知 role -> 原值', () => {
      expect(messageView.roleLabel('other')).toBe('other');
    });
  });

  describe('ROLE_LABELS', () => {
    it('包含 user/assistant/system', () => {
      expect(messageView.ROLE_LABELS.user).toBe('用户');
      expect(messageView.ROLE_LABELS.assistant).toBe('助手');
      expect(messageView.ROLE_LABELS.system).toBe('系统');
    });
  });
});
