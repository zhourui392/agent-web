import { describe, it, expect } from 'vitest';
import {
  ROLE_LABELS,
  enrichMessage,
  mapMessages,
  roleLabel,
} from '../../src/main/frontend/js/lib/message-view.js';

const STREAM_JSON = JSON.stringify({
  type: 'stream_event',
  event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'hello' } }
});

describe('AgentMessageView', () => {
  describe('enrichMessage (without recall, 默认)', () => {
    it('assistant stream-json -> parsedSegments, 无 recall 字段', () => {
      const result = enrichMessage({ role: 'assistant', content: STREAM_JSON });
      expect(result.parsedSegments).toHaveLength(1);
      expect((result.parsedSegments as Array<{ content: string }>)[0].content).toBe('hello');
      expect(result.recall).toBeUndefined();
      expect(result.recallOpen).toBeUndefined();
    });

    it('user -> bodyText + images', () => {
      const result = enrichMessage({ role: 'user', content: 'hello\n/path/to/img.png' });
      expect(result.bodyText).toBe('hello');
      expect(result.images).toEqual(['/path/to/img.png']);
    });

    it('其他 role -> 浅拷贝, 无 recall/parsedSegments', () => {
      const msg = { role: 'system', content: 'sys' };
      const result = enrichMessage(msg);
      expect(result).not.toBe(msg);
      expect(result.content).toBe('sys');
      expect(result.recall).toBeUndefined();
      expect(result.parsedSegments).toBeUndefined();
    });

    it('null msg -> null', () => {
      expect(enrichMessage(null as unknown as Record<string, unknown>)).toBeNull();
    });
  });

  describe('enrichMessage (withRecall: true)', () => {
    it('assistant stream-json + recall -> parsedSegments + recall + recallOpen', () => {
      const result = enrichMessage(
        { role: 'assistant', content: STREAM_JSON, recall: JSON.stringify({ title: 't' }) },
        { withRecall: true }
      );
      expect(result.parsedSegments).toHaveLength(1);
      expect(result.recall).toEqual({ title: 't' });
      expect(result.recallOpen).toBe(false);
    });

    it('assistant 非 stream + recall -> recall + recallOpen, 无 parsedSegments', () => {
      const result = enrichMessage(
        { role: 'assistant', content: 'plain text', recall: '{"title":"x"}' },
        { withRecall: true }
      );
      expect(result.parsedSegments).toBeUndefined();
      expect(result.recall).toEqual({ title: 'x' });
      expect(result.recallOpen).toBe(false);
    });

    it('非法 recall JSON -> recall=null', () => {
      const result = enrichMessage(
        { role: 'assistant', content: 'text', recall: 'invalid{json' },
        { withRecall: true }
      );
      expect(result.recall).toBeNull();
      expect(result.recallOpen).toBe(false);
    });

    it('user + withRecall -> bodyText + images, recall 保持原始字符串(user 不解析 recall)', () => {
      const result = enrichMessage(
        { role: 'user', content: 'hi', recall: '{"x":1}' },
        { withRecall: true }
      );
      expect(result.bodyText).toBe('hi');
      expect(result.recallOpen).toBeUndefined();
    });
  });

  describe('mapMessages', () => {
    it('数组映射 + withRecall', () => {
      const result = mapMessages(
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
      const result = mapMessages([
        { role: 'assistant', content: 'plain', recall: '{"title":"t"}' },
      ]);
      // withRecall=false: Object.assign 复制原始 recall 字符串, 但不加 recallOpen/parsedSegments
      expect(result[0].recallOpen).toBeUndefined();
      expect(result[0].parsedSegments).toBeUndefined();
    });

    it('null/空输入 -> []', () => {
      expect(mapMessages(null)).toEqual([]);
      expect(mapMessages([])).toEqual([]);
    });
  });

  describe('roleLabel', () => {
    it('已知 role', () => {
      expect(roleLabel('user')).toBe('用户');
      expect(roleLabel('assistant')).toBe('助手');
      expect(roleLabel('system')).toBe('系统');
    });
    it('未知 role -> 原值', () => {
      expect(roleLabel('other')).toBe('other');
    });
  });

  describe('ROLE_LABELS', () => {
    it('包含 user/assistant/system', () => {
      expect(ROLE_LABELS.user).toBe('用户');
      expect(ROLE_LABELS.assistant).toBe('助手');
      expect(ROLE_LABELS.system).toBe('系统');
    });
  });
});
