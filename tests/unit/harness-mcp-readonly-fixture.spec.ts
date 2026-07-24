import { spawn, type ChildProcessWithoutNullStreams } from 'child_process';
import { fileURLToPath } from 'url';
import { afterEach, describe, expect, it } from 'vitest';

const serverPath = fileURLToPath(new URL(
  '../../src/main/resources/harness/mcp-servers/local-readonly-fixture/1.0.0/server.mjs',
  import.meta.url,
));

let server: ChildProcessWithoutNullStreams | undefined;

afterEach(() => {
  server?.kill('SIGKILL');
  server = undefined;
});

function request(messages: object[], expectedResponses: number): Promise<any[]> {
  server = spawn(process.execPath, [serverPath], { stdio: ['pipe', 'pipe', 'pipe'] });
  return new Promise((resolve, reject) => {
    const responses: any[] = [];
    let stdout = '';
    let stderr = '';
    const timeout = setTimeout(() => reject(new Error(`MCP timeout: ${stderr}`)), 3000);
    server!.stderr.on('data', chunk => {
      stderr += chunk.toString();
    });
    server!.stdout.on('data', chunk => {
      stdout += chunk.toString();
      const lines = stdout.split('\n');
      stdout = lines.pop() ?? '';
      for (const line of lines.filter(Boolean)) {
        responses.push(JSON.parse(line));
      }
      if (responses.length === expectedResponses) {
        clearTimeout(timeout);
        resolve(responses);
      }
    });
    server!.once('error', error => {
      clearTimeout(timeout);
      reject(error);
    });
    for (const message of messages) {
      server!.stdin.write(`${JSON.stringify(message)}\n`);
    }
  });
}

describe('Harness read-only MCP live fixture', () => {
  it('exposes one deterministic read tool and rejects undeclared tools', async () => {
    const responses = await request([
      {
        jsonrpc: '2.0', id: 1, method: 'initialize',
        params: {
          protocolVersion: '2024-11-05', capabilities: {},
          clientInfo: { name: 'harness-fixture-test', version: '1.0.0' },
        },
      },
      { jsonrpc: '2.0', method: 'notifications/initialized', params: {} },
      { jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} },
      { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'read_fixture', arguments: {} } },
      { jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'write_fixture', arguments: {} } },
    ], 4);

    expect(responses[0].result.protocolVersion).toBe('2024-11-05');
    expect(responses[1].result.tools).toEqual([
      expect.objectContaining({
        name: 'read_fixture',
        annotations: expect.objectContaining({ readOnlyHint: true, destructiveHint: false }),
      }),
    ]);
    expect(responses[2].result.content).toEqual([
      { type: 'text', text: 'harness-readonly-fixture:v1' },
    ]);
    expect(responses[3]).toEqual(expect.objectContaining({
      id: 4,
      error: expect.objectContaining({ code: -32602 }),
    }));
  });
});
