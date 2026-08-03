import { spawnSync } from 'child_process';
import { fileURLToPath } from 'url';
import { describe, expect, it } from 'vitest';

const serverPath = fileURLToPath(new URL(
  '../../src/main/resources/capability/mcp-servers/local-readonly-fixture/1.0.0/server.mjs',
  import.meta.url,
));

interface JsonRpcResponse {
  id?: number;
  result?: {
    protocolVersion?: string;
    tools?: unknown;
    content?: unknown;
  };
  error?: {
    code?: number;
  };
}

function request(messages: object[]): JsonRpcResponse[] {
  const input = messages.map(message => JSON.stringify(message)).join('\n') + '\n';
  const completed = spawnSync(process.execPath, [serverPath], {
    encoding: 'utf8',
    input,
    timeout: 3000,
  });
  if (completed.error) {
    throw completed.error;
  }
  if (completed.status !== 0) {
    throw new Error(`MCP exited with code ${completed.status}: ${completed.stderr}`);
  }
  return completed.stdout.trim().split('\n').filter(Boolean)
    .map(line => JSON.parse(line) as JsonRpcResponse);
}

describe('Read-only MCP fixture', () => {
  it('exposes one deterministic read tool and rejects undeclared tools', () => {
    const responses = request([
      {
        jsonrpc: '2.0', id: 1, method: 'initialize',
        params: {
          protocolVersion: '2024-11-05', capabilities: {},
          clientInfo: { name: 'agent-web-fixture-test', version: '1.0.0' },
        },
      },
      { jsonrpc: '2.0', method: 'notifications/initialized', params: {} },
      { jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} },
      { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'read_fixture', arguments: {} } },
      { jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'write_fixture', arguments: {} } },
    ]);

    expect(responses).toHaveLength(4);
    expect(responses[0].result?.protocolVersion).toBe('2024-11-05');
    expect(responses[1].result?.tools).toEqual([
      expect.objectContaining({
        name: 'read_fixture',
        annotations: expect.objectContaining({ readOnlyHint: true, destructiveHint: false }),
      }),
    ]);
    expect(responses[2].result?.content).toEqual([
      { type: 'text', text: 'local-readonly-fixture:v1' },
    ]);
    expect(responses[3]).toEqual(expect.objectContaining({
      id: 4,
      error: expect.objectContaining({ code: -32602 }),
    }));
  });
});
