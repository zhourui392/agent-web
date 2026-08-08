import { describe, it, expect } from 'vitest';
import {
  IMAGE_PATH_RE,
  formatSize,
  renderMarkdown,
  parseUserMessage,
  imageUrl,
  formatTime,
  formatBeijingDateTime,
  escapeHtml,
  parseStreamJson,
  isStreamJson,
  agentChunkToStreamJson,
  runtimeToolStartedToStreamJson,
  runtimeToolFinishedToStreamJson,
} from '../../frontend/js/lib/formatters.js';

// formatters.js 现在静态 import marked / dompurify (npm), 不再读全局。
// 本文件跑在 node 环境: DOMPurify.isSupported=false -> renderMarkdown 走 fail-closed 转义分支。
// 真正的「marked 渲染 + DOMPurify 净化」在 formatters-sanitize.spec.ts (jsdom 环境) 里验证。

describe('formatSize', () => {
  it('null returns empty string', () => {
    expect(formatSize(null)).toBe('');
  });

  it('undefined returns empty string', () => {
    expect(formatSize(undefined)).toBe('');
  });

  it('0 returns "0 B"', () => {
    expect(formatSize(0)).toBe('0 B');
  });

  it('512 returns "512 B"', () => {
    expect(formatSize(512)).toBe('512 B');
  });

  it('1023 still in B range', () => {
    expect(formatSize(1023)).toBe('1023 B');
  });

  it('2048 returns "2.0 KB"', () => {
    expect(formatSize(2048)).toBe('2.0 KB');
  });

  it('5 MB returns "5.0 MB"', () => {
    expect(formatSize(1024 * 1024 * 5)).toBe('5.0 MB');
  });

  it('1.5 MB returns "1.5 MB" with one decimal', () => {
    expect(formatSize(1024 * 1024 * 1.5)).toBe('1.5 MB');
  });
});

describe('renderMarkdown', () => {
  it('empty string returns empty', () => {
    expect(renderMarkdown('')).toBe('');
  });

  it('null returns empty', () => {
    expect(renderMarkdown(null)).toBe('');
  });

  it('undefined returns empty', () => {
    expect(renderMarkdown(undefined)).toBe('');
  });

  it('escapes html angle brackets via fallback (no DOM, sanitizer unsupported)', () => {
    const out = renderMarkdown('a<b>');
    expect(out).toContain('a&lt;b&gt;');
    expect(out).not.toContain('<b>');
  });

  it('escapes ampersand before angle brackets', () => {
    const out = renderMarkdown('x & y');
    expect(out).toContain('x &amp; y');
  });

  it('collapses 3+ consecutive newlines to two and renders as <br><br>', () => {
    const out = renderMarkdown('a\n\n\n\nb');
    expect(out).toBe('a<br><br>b');
  });

  it('two newlines pass through as <br><br>', () => {
    const out = renderMarkdown('a\n\nb');
    expect(out).toBe('a<br><br>b');
  });

  // 安全底线: 没有可用净化器时绝不吐出 marked 生成的 HTML, 一律转义。
  // node 环境下 DOMPurify.isSupported === false, 正好覆盖这条路径。
  it('fails closed by escaping markdown when the sanitizer is unsupported', () => {
    const out = renderMarkdown('<b>untrusted</b>');

    expect(out).toBe('&lt;b&gt;untrusted&lt;/b&gt;');
  });

  // 转义后 onerror= 只是纯文本残留 (已不是属性), 关键是不能留下任何可执行的活标签。
  it('fails closed on raw HTML payloads too', () => {
    const out = renderMarkdown('<img src="x" onerror="alert(1)">');

    expect(out).toContain('&lt;img');
    expect(out).not.toContain('<img');
    expect(out).not.toMatch(/<[a-zA-Z]/);
  });
});

describe('parseUserMessage', () => {
  it('empty string returns blank shape', () => {
    expect(parseUserMessage('')).toEqual({ text: '', images: [] });
  });

  it('null returns blank shape', () => {
    expect(parseUserMessage(null)).toEqual({ text: '', images: [] });
  });

  it('plain text passes through untouched', () => {
    expect(parseUserMessage('hello world')).toEqual({
      text: 'hello world',
      images: [],
    });
  });

  it('single windows png path extracted into images', () => {
    expect(parseUserMessage('C:\\foo\\bar.png')).toEqual({
      text: '',
      images: ['C:\\foo\\bar.png'],
    });
  });

  it('mixed text and image splits correctly', () => {
    expect(parseUserMessage('问题描述\nD:/upload_pic/x.jpg')).toEqual({
      text: '问题描述',
      images: ['D:/upload_pic/x.jpg'],
    });
  });

  it('extension is case insensitive (.PNG)', () => {
    const out = parseUserMessage('/home/u/a.PNG');
    expect(out.images).toContain('/home/u/a.PNG');
    expect(out.text).toBe('');
  });

  it('non image extension stays in text', () => {
    const out = parseUserMessage('/foo/bar.txt');
    expect(out.images).toEqual([]);
    expect(out.text).toBe('/foo/bar.txt');
  });

  it('multiple images all collected', () => {
    const input = 'check these\n/a/b.png\n/c/d.webp';
    const out = parseUserMessage(input);
    expect(out.text).toBe('check these');
    expect(out.images).toEqual(['/a/b.png', '/c/d.webp']);
  });

  it('webp / gif / bmp / jpeg all match', () => {
    const out = parseUserMessage('/a/1.webp\n/b/2.gif\n/c/3.bmp\n/d/4.jpeg');
    expect(out.images).toEqual(['/a/1.webp', '/b/2.gif', '/c/3.bmp', '/d/4.jpeg']);
    expect(out.text).toBe('');
  });
});

describe('agentChunkToStreamJson', () => {
  it('converts a common Runtime payload into an incremental text event', () => {
    const line = agentChunkToStreamJson(
      JSON.stringify({ runtimeSequence: 8, content: '分段输出' }),
    );

    expect(line).not.toBeNull();
    expect(parseStreamJson(line)).toEqual([{ type: 'text', content: '分段输出' }]);
  });

  it('rejects malformed or incomplete Runtime payloads', () => {
    expect(agentChunkToStreamJson('{broken')).toBeNull();
    expect(agentChunkToStreamJson(JSON.stringify({ runtimeSequence: 1 }))).toBeNull();
  });
});

describe('Runtime tool event stream projection', () => {
  it('shows a tool block before its result and following text', () => {
    const started = runtimeToolStartedToStreamJson(JSON.stringify({
      runtimeSequence: 2,
      tool: 'shell',
      callId: 'call-1',
      status: 'RUNNING',
      commandContent: 'ls',
    }));
    const finished = runtimeToolFinishedToStreamJson(JSON.stringify({
      runtimeSequence: 3,
      tool: 'shell',
      callId: 'call-1',
      status: 'SUCCEEDED',
      outputContent: 'README.md',
    }));
    const text = agentChunkToStreamJson(JSON.stringify({
      runtimeSequence: 4,
      content: '完成',
    }));

    const segments = parseStreamJson([started, finished, text].join('\n'));
    expect(segments).toHaveLength(2);
    expect(segments[0]).toMatchObject({ type: 'tool', name: 'shell' });
    expect(segments[0].content).toContain('README.md');
    expect(segments[1]).toEqual({ type: 'text', content: '完成' });
  });

  it('ignores malformed tool payloads', () => {
    expect(runtimeToolStartedToStreamJson('{broken')).toBeNull();
    expect(runtimeToolFinishedToStreamJson(JSON.stringify({ status: 'FAILED' }))).toBeNull();
  });
});

describe('imageUrl', () => {
  it('basic absolute path is url-encoded', () => {
    expect(imageUrl('/foo/bar.png')).toBe('/api/fs/image?path=%2Ffoo%2Fbar.png');
  });

  it('encodes spaces as %20', () => {
    expect(imageUrl('/a b/c.png')).toBe('/api/fs/image?path=%2Fa%20b%2Fc.png');
  });

  it('encodes windows backslash and colon', () => {
    const out = imageUrl('C:\\x\\y.png');
    expect(out).toContain('C%3A');
    expect(out).toContain('%5C');
  });

  it('encodes chinese chars', () => {
    const out = imageUrl('/图片/1.png');
    expect(out.startsWith('/api/fs/image?path=')).toBe(true);
    expect(out).not.toContain('图片');
  });
});

describe('formatTime', () => {
  it('empty string returns empty', () => {
    expect(formatTime('')).toBe('');
  });

  it('null returns empty', () => {
    expect(formatTime(null)).toBe('');
  });

  it('valid ISO string returns a non-empty string', () => {
    const out = formatTime('2026-05-25T14:30:00Z');
    expect(typeof out).toBe('string');
    expect(out.length).toBeGreaterThan(0);
  });

  it('invalid date string does not throw', () => {
    expect(() => formatTime('not-a-date')).not.toThrow();
    const out = formatTime('not-a-date');
    expect(out).not.toBeUndefined();
  });
});

describe('formatBeijingDateTime', () => {
  it('empty string returns empty', () => {
    expect(formatBeijingDateTime('')).toBe('');
  });

  it('null returns empty', () => {
    expect(formatBeijingDateTime(null)).toBe('');
  });

  it('formats UTC ISO string as Asia/Shanghai datetime', () => {
    expect(formatBeijingDateTime('2026-01-31T16:00:00Z')).toBe('2026-02-01 00:00:00');
  });

  it('invalid date string falls back to original input', () => {
    expect(formatBeijingDateTime('not-a-date')).toBe('not-a-date');
  });
});

describe('escapeHtml', () => {
  it('empty string returns empty', () => {
    expect(escapeHtml('')).toBe('');
  });

  it('null returns empty', () => {
    expect(escapeHtml(null)).toBe('');
  });

  it('escapes script tag', () => {
    const out = escapeHtml('<script>alert(1)</script>');
    expect(out).toContain('&lt;script&gt;');
    expect(out).not.toContain('<script>');
  });

  it('escapes ampersand', () => {
    expect(escapeHtml('a & b')).toContain('a &amp; b');
  });

  it('newline becomes <br>', () => {
    expect(escapeHtml('line1\nline2')).toContain('line1<br>line2');
  });

  it('ampersand encoded before angle brackets (no double-encoding of &lt;)', () => {
    const out = escapeHtml('<a>');
    expect(out).toBe('&lt;a&gt;');
  });
});

describe('IMAGE_PATH_RE', () => {
  it('is a RegExp instance', () => {
    expect(IMAGE_PATH_RE).toBeInstanceOf(RegExp);
  });

  it('matches unix png path', () => {
    expect(IMAGE_PATH_RE.test('/foo/bar.png')).toBe(true);
  });

  it('matches windows jpg path', () => {
    expect(IMAGE_PATH_RE.test('C:\\a\\b.jpg')).toBe(true);
  });

  it('matches uppercase WEBP extension', () => {
    // 注意: 正则要求分隔符前至少一个字符 (^.+[\/\\]), 单 '/a.WEBP' 不够
    expect(IMAGE_PATH_RE.test('/x/a.WEBP')).toBe(true);
  });

  it('does not match plain text', () => {
    expect(IMAGE_PATH_RE.test('just text')).toBe(false);
  });

  it('does not match non-image extension', () => {
    expect(IMAGE_PATH_RE.test('/foo/bar.txt')).toBe(false);
  });

  it('does not match path without separator', () => {
    expect(IMAGE_PATH_RE.test('bar.png')).toBe(false);
  });

  it('matches jpeg extension', () => {
    expect(IMAGE_PATH_RE.test('/x/y.jpeg')).toBe(true);
  });
});

describe('parseStreamJson', () => {
  it('empty / null returns empty array', () => {
    expect(parseStreamJson('')).toEqual([]);
    expect(parseStreamJson(null)).toEqual([]);
  });

  it('non-JSON lines are skipped', () => {
    expect(parseStreamJson('plain text\nnot json')).toEqual([]);
  });

  it('text_delta 累加为单个 text segment', () => {
    const raw = [
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'Hello ' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'world' } } }),
    ].join('\n');
    const segs = parseStreamJson(raw);
    expect(segs).toHaveLength(1);
    expect(segs[0]).toEqual({ type: 'text', content: 'Hello world' });
  });

  it('tool_use 起段 + input_json_delta 累加到 tool', () => {
    const raw = [
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_start', content_block: { type: 'tool_use', name: 'Read' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'input_json_delta', partial_json: '{"path":' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'input_json_delta', partial_json: '"/a"}' } } }),
    ].join('\n');
    const segs = parseStreamJson(raw);
    expect(segs).toHaveLength(1);
    expect(segs[0].type).toBe('tool');
    expect((segs[0] as any).name).toBe('Read');
    expect(segs[0].content).toBe('{"path":"/a"}');
  });

  it('tool_result 合并进最近的 tool 段', () => {
    const raw = [
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_start', content_block: { type: 'tool_use', name: 'Bash' } } }),
      JSON.stringify({ type: 'user', message: { content: [{ type: 'tool_result', content: 'exit 0' }] } }),
    ].join('\n');
    const segs = parseStreamJson(raw);
    expect(segs).toHaveLength(1);
    expect(segs[0].type).toBe('tool');
    expect(segs[0].content).toContain('exit 0');
  });

  it('无前置 tool 的 tool_result 自成一段 Tool Result', () => {
    const raw = JSON.stringify({ type: 'user', message: { content: [{ type: 'tool_result', content: 'orphan' }] } });
    const segs = parseStreamJson(raw);
    expect(segs).toHaveLength(1);
    expect((segs[0] as any).name).toBe('Tool Result');
    expect(segs[0].content).toContain('orphan');
  });

  it('result 仅在没有正文文本时兜底成 text', () => {
    const withText = [
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'answer' } } }),
      JSON.stringify({ type: 'result', result: 'final' }),
    ].join('\n');
    expect(parseStreamJson(withText)).toEqual([{ type: 'text', content: 'answer' }]);

    const onlyResult = JSON.stringify({ type: 'result', result: 'final' });
    expect(parseStreamJson(onlyResult)).toEqual([{ type: 'text', content: 'final' }]);
  });
});

describe('isStreamJson', () => {
  const initLine = JSON.stringify({ type: 'system', subtype: 'init', session_id: 'abc' });
  const deltaLine = JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'hi' } } });

  it('empty / null / undefined returns false', () => {
    expect(isStreamJson('')).toBe(false);
    expect(isStreamJson(null)).toBe(false);
    expect(isStreamJson(undefined)).toBe(false);
  });

  it('standard stream-json (first line is JSON) returns true', () => {
    expect(isStreamJson(initLine + '\n' + deltaLine)).toBe(true);
  });

  it('stderr warning lines before first JSON line still returns true', () => {
    const polluted = 'Ignoring 11 permissions.allow entries from .claude/settings.local.json: '
      + 'this workspace has not been trusted.\n' + initLine + '\n' + deltaLine;
    expect(isStreamJson(polluted)).toBe(true);
  });

  it('plain text message returns false', () => {
    expect(isStreamJson('Echo hello world\n第二行纯文本')).toBe(false);
  });

  it('line starting with { but invalid JSON returns false', () => {
    expect(isStreamJson('{not valid json\nplain text')).toBe(false);
  });

  it('valid JSON object without string type field returns false', () => {
    expect(isStreamJson('{"foo": 1}\nplain text')).toBe(false);
  });

  it('JSON line beyond head scan window is not treated as stream-json', () => {
    const noise = Array.from({ length: 15 }, (_, i) => 'noise line ' + i).join('\n');
    expect(isStreamJson(noise + '\n' + initLine)).toBe(false);
  });
});
