import { describe, it, expect } from 'vitest';
import { createRequire } from 'node:module';

const requireCjs = createRequire(import.meta.url);
const formatters = requireCjs('../../src/main/resources/static/js/lib/formatters.js') as {
  IMAGE_PATH_RE: RegExp;
  formatSize: (bytes: number | null | undefined) => string;
  renderMarkdown: (text: string | null | undefined) => string;
  parseUserMessage: (text: string | null | undefined) => { text: string; images: string[] };
  imageUrl: (absPath: string) => string;
  formatTime: (isoStr: string | null | undefined) => string;
  formatBeijingDateTime: (isoStr: string | null | undefined) => string;
  escapeHtml: (text: string | null | undefined) => string;
  parseStreamJson: (raw: string | null | undefined) => Array<{ type: string; name?: string; content: string }>;
  isStreamJson: (content: string | null | undefined) => boolean;
};

describe('formatSize', () => {
  it('null returns empty string', () => {
    expect(formatters.formatSize(null)).toBe('');
  });

  it('undefined returns empty string', () => {
    expect(formatters.formatSize(undefined)).toBe('');
  });

  it('0 returns "0 B"', () => {
    expect(formatters.formatSize(0)).toBe('0 B');
  });

  it('512 returns "512 B"', () => {
    expect(formatters.formatSize(512)).toBe('512 B');
  });

  it('1023 still in B range', () => {
    expect(formatters.formatSize(1023)).toBe('1023 B');
  });

  it('2048 returns "2.0 KB"', () => {
    expect(formatters.formatSize(2048)).toBe('2.0 KB');
  });

  it('5 MB returns "5.0 MB"', () => {
    expect(formatters.formatSize(1024 * 1024 * 5)).toBe('5.0 MB');
  });

  it('1.5 MB returns "1.5 MB" with one decimal', () => {
    expect(formatters.formatSize(1024 * 1024 * 1.5)).toBe('1.5 MB');
  });
});

describe('renderMarkdown', () => {
  it('empty string returns empty', () => {
    expect(formatters.renderMarkdown('')).toBe('');
  });

  it('null returns empty', () => {
    expect(formatters.renderMarkdown(null)).toBe('');
  });

  it('undefined returns empty', () => {
    expect(formatters.renderMarkdown(undefined)).toBe('');
  });

  it('escapes html angle brackets via fallback (no marked global)', () => {
    const out = formatters.renderMarkdown('a<b>');
    expect(out).toContain('a&lt;b&gt;');
    expect(out).not.toContain('<b>');
  });

  it('escapes ampersand before angle brackets', () => {
    const out = formatters.renderMarkdown('x & y');
    expect(out).toContain('x &amp; y');
  });

  it('collapses 3+ consecutive newlines to two and renders as <br><br>', () => {
    const out = formatters.renderMarkdown('a\n\n\n\nb');
    expect(out).toBe('a<br><br>b');
  });

  it('two newlines pass through as <br><br>', () => {
    const out = formatters.renderMarkdown('a\n\nb');
    expect(out).toBe('a<br><br>b');
  });
});

describe('parseUserMessage', () => {
  it('empty string returns blank shape', () => {
    expect(formatters.parseUserMessage('')).toEqual({ text: '', images: [] });
  });

  it('null returns blank shape', () => {
    expect(formatters.parseUserMessage(null)).toEqual({ text: '', images: [] });
  });

  it('plain text passes through untouched', () => {
    expect(formatters.parseUserMessage('hello world')).toEqual({
      text: 'hello world',
      images: [],
    });
  });

  it('single windows png path extracted into images', () => {
    expect(formatters.parseUserMessage('C:\\foo\\bar.png')).toEqual({
      text: '',
      images: ['C:\\foo\\bar.png'],
    });
  });

  it('mixed text and image splits correctly', () => {
    expect(formatters.parseUserMessage('问题描述\nD:/upload_pic/x.jpg')).toEqual({
      text: '问题描述',
      images: ['D:/upload_pic/x.jpg'],
    });
  });

  it('extension is case insensitive (.PNG)', () => {
    const out = formatters.parseUserMessage('/home/u/a.PNG');
    expect(out.images).toContain('/home/u/a.PNG');
    expect(out.text).toBe('');
  });

  it('non image extension stays in text', () => {
    const out = formatters.parseUserMessage('/foo/bar.txt');
    expect(out.images).toEqual([]);
    expect(out.text).toBe('/foo/bar.txt');
  });

  it('multiple images all collected', () => {
    const input = 'check these\n/a/b.png\n/c/d.webp';
    const out = formatters.parseUserMessage(input);
    expect(out.text).toBe('check these');
    expect(out.images).toEqual(['/a/b.png', '/c/d.webp']);
  });

  it('webp / gif / bmp / jpeg all match', () => {
    const out = formatters.parseUserMessage('/a/1.webp\n/b/2.gif\n/c/3.bmp\n/d/4.jpeg');
    expect(out.images).toEqual(['/a/1.webp', '/b/2.gif', '/c/3.bmp', '/d/4.jpeg']);
    expect(out.text).toBe('');
  });
});

describe('imageUrl', () => {
  it('basic absolute path is url-encoded', () => {
    expect(formatters.imageUrl('/foo/bar.png')).toBe('/api/fs/image?path=%2Ffoo%2Fbar.png');
  });

  it('encodes spaces as %20', () => {
    expect(formatters.imageUrl('/a b/c.png')).toBe('/api/fs/image?path=%2Fa%20b%2Fc.png');
  });

  it('encodes windows backslash and colon', () => {
    const out = formatters.imageUrl('C:\\x\\y.png');
    expect(out).toContain('C%3A');
    expect(out).toContain('%5C');
  });

  it('encodes chinese chars', () => {
    const out = formatters.imageUrl('/图片/1.png');
    expect(out.startsWith('/api/fs/image?path=')).toBe(true);
    expect(out).not.toContain('图片');
  });
});

describe('formatTime', () => {
  it('empty string returns empty', () => {
    expect(formatters.formatTime('')).toBe('');
  });

  it('null returns empty', () => {
    expect(formatters.formatTime(null)).toBe('');
  });

  it('valid ISO string returns a non-empty string', () => {
    const out = formatters.formatTime('2026-05-25T14:30:00Z');
    expect(typeof out).toBe('string');
    expect(out.length).toBeGreaterThan(0);
  });

  it('invalid date string does not throw', () => {
    expect(() => formatters.formatTime('not-a-date')).not.toThrow();
    const out = formatters.formatTime('not-a-date');
    expect(out).not.toBeUndefined();
  });
});

describe('formatBeijingDateTime', () => {
  it('empty string returns empty', () => {
    expect(formatters.formatBeijingDateTime('')).toBe('');
  });

  it('null returns empty', () => {
    expect(formatters.formatBeijingDateTime(null)).toBe('');
  });

  it('formats UTC ISO string as Asia/Shanghai datetime', () => {
    expect(formatters.formatBeijingDateTime('2026-01-31T16:00:00Z')).toBe('2026-02-01 00:00:00');
  });

  it('invalid date string falls back to original input', () => {
    expect(formatters.formatBeijingDateTime('not-a-date')).toBe('not-a-date');
  });
});

describe('escapeHtml', () => {
  it('empty string returns empty', () => {
    expect(formatters.escapeHtml('')).toBe('');
  });

  it('null returns empty', () => {
    expect(formatters.escapeHtml(null)).toBe('');
  });

  it('escapes script tag', () => {
    const out = formatters.escapeHtml('<script>alert(1)</script>');
    expect(out).toContain('&lt;script&gt;');
    expect(out).not.toContain('<script>');
  });

  it('escapes ampersand', () => {
    expect(formatters.escapeHtml('a & b')).toContain('a &amp; b');
  });

  it('newline becomes <br>', () => {
    expect(formatters.escapeHtml('line1\nline2')).toContain('line1<br>line2');
  });

  it('ampersand encoded before angle brackets (no double-encoding of &lt;)', () => {
    const out = formatters.escapeHtml('<a>');
    expect(out).toBe('&lt;a&gt;');
  });
});

describe('IMAGE_PATH_RE', () => {
  it('is a RegExp instance', () => {
    expect(formatters.IMAGE_PATH_RE).toBeInstanceOf(RegExp);
  });

  it('matches unix png path', () => {
    expect(formatters.IMAGE_PATH_RE.test('/foo/bar.png')).toBe(true);
  });

  it('matches windows jpg path', () => {
    expect(formatters.IMAGE_PATH_RE.test('C:\\a\\b.jpg')).toBe(true);
  });

  it('matches uppercase WEBP extension', () => {
    // 注意: 正则要求分隔符前至少一个字符 (^.+[\/\\]), 单 '/a.WEBP' 不够
    expect(formatters.IMAGE_PATH_RE.test('/x/a.WEBP')).toBe(true);
  });

  it('does not match plain text', () => {
    expect(formatters.IMAGE_PATH_RE.test('just text')).toBe(false);
  });

  it('does not match non-image extension', () => {
    expect(formatters.IMAGE_PATH_RE.test('/foo/bar.txt')).toBe(false);
  });

  it('does not match path without separator', () => {
    expect(formatters.IMAGE_PATH_RE.test('bar.png')).toBe(false);
  });

  it('matches jpeg extension', () => {
    expect(formatters.IMAGE_PATH_RE.test('/x/y.jpeg')).toBe(true);
  });
});

describe('parseStreamJson', () => {
  it('empty / null returns empty array', () => {
    expect(formatters.parseStreamJson('')).toEqual([]);
    expect(formatters.parseStreamJson(null)).toEqual([]);
  });

  it('non-JSON lines are skipped', () => {
    expect(formatters.parseStreamJson('plain text\nnot json')).toEqual([]);
  });

  it('text_delta 累加为单个 text segment', () => {
    const raw = [
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'Hello ' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'world' } } }),
    ].join('\n');
    const segs = formatters.parseStreamJson(raw);
    expect(segs).toHaveLength(1);
    expect(segs[0]).toEqual({ type: 'text', content: 'Hello world' });
  });

  it('tool_use 起段 + input_json_delta 累加到 tool', () => {
    const raw = [
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_start', content_block: { type: 'tool_use', name: 'Read' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'input_json_delta', partial_json: '{"path":' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'input_json_delta', partial_json: '"/a"}' } } }),
    ].join('\n');
    const segs = formatters.parseStreamJson(raw);
    expect(segs).toHaveLength(1);
    expect(segs[0].type).toBe('tool');
    expect(segs[0].name).toBe('Read');
    expect(segs[0].content).toBe('{"path":"/a"}');
  });

  it('tool_result 合并进最近的 tool 段', () => {
    const raw = [
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_start', content_block: { type: 'tool_use', name: 'Bash' } } }),
      JSON.stringify({ type: 'user', message: { content: [{ type: 'tool_result', content: 'exit 0' }] } }),
    ].join('\n');
    const segs = formatters.parseStreamJson(raw);
    expect(segs).toHaveLength(1);
    expect(segs[0].type).toBe('tool');
    expect(segs[0].content).toContain('exit 0');
  });

  it('无前置 tool 的 tool_result 自成一段 Tool Result', () => {
    const raw = JSON.stringify({ type: 'user', message: { content: [{ type: 'tool_result', content: 'orphan' }] } });
    const segs = formatters.parseStreamJson(raw);
    expect(segs).toHaveLength(1);
    expect(segs[0].name).toBe('Tool Result');
    expect(segs[0].content).toContain('orphan');
  });

  it('result 仅在没有正文文本时兜底成 text', () => {
    const withText = [
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'answer' } } }),
      JSON.stringify({ type: 'result', result: 'final' }),
    ].join('\n');
    expect(formatters.parseStreamJson(withText)).toEqual([{ type: 'text', content: 'answer' }]);

    const onlyResult = JSON.stringify({ type: 'result', result: 'final' });
    expect(formatters.parseStreamJson(onlyResult)).toEqual([{ type: 'text', content: 'final' }]);
  });
});

describe('isStreamJson', () => {
  const initLine = JSON.stringify({ type: 'system', subtype: 'init', session_id: 'abc' });
  const deltaLine = JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'hi' } } });

  it('empty / null / undefined returns false', () => {
    expect(formatters.isStreamJson('')).toBe(false);
    expect(formatters.isStreamJson(null)).toBe(false);
    expect(formatters.isStreamJson(undefined)).toBe(false);
  });

  it('standard stream-json (first line is JSON) returns true', () => {
    expect(formatters.isStreamJson(initLine + '\n' + deltaLine)).toBe(true);
  });

  it('stderr warning lines before first JSON line still returns true', () => {
    const polluted = 'Ignoring 11 permissions.allow entries from .claude/settings.local.json: '
      + 'this workspace has not been trusted.\n' + initLine + '\n' + deltaLine;
    expect(formatters.isStreamJson(polluted)).toBe(true);
  });

  it('plain text message returns false', () => {
    expect(formatters.isStreamJson('Echo hello world\n第二行纯文本')).toBe(false);
  });

  it('line starting with { but invalid JSON returns false', () => {
    expect(formatters.isStreamJson('{not valid json\nplain text')).toBe(false);
  });

  it('valid JSON object without string type field returns false', () => {
    expect(formatters.isStreamJson('{"foo": 1}\nplain text')).toBe(false);
  });

  it('JSON line beyond head scan window is not treated as stream-json', () => {
    const noise = Array.from({ length: 15 }, (_, i) => 'noise line ' + i).join('\n');
    expect(formatters.isStreamJson(noise + '\n' + initLine)).toBe(false);
  });
});
