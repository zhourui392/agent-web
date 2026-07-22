import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const repositoryRoot = resolve(__dirname, '../..');

function productionSource(relativePath: string): string {
  return readFileSync(resolve(repositoryRoot, relativePath), 'utf8');
}

describe('single ChatRun page path', () => {
  it('does not retain the legacy POST SSE fallback', () => {
    const panel = productionSource('src/main/resources/static/js/components/chat-panel.js');

    expect(panel).not.toContain('AgentPostSse');
    expect(panel).not.toContain('/message/stream');
    expect(panel).not.toContain('sendMessageLegacy');
    expect(panel).not.toContain('ensureResumableCapability');
  });

  it('does not load the legacy POST SSE client', () => {
    const index = productionSource('src/main/resources/static/index.html');
    const adminChat = productionSource('src/main/resources/static/admin/chat.html');

    expect(index).not.toContain('post-sse-client.js');
    expect(adminChat).not.toContain('post-sse-client.js');
  });
});
