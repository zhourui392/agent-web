/**
 * useHarnessApi: Harness API 调用基础设施 composable。
 *
 * 从 useHarness 拆出的独立关注点：HTTP 调用、幂等键缓存、错误提示。
 * 不依赖任何 harness 业务状态（selectedRun 等），可独立单测。
 *
 * 依赖: element-plus(ElMessage)。
 */
import { ElMessage } from 'element-plus';

export function useHarnessApi() {
  const idempotencyKeyCache: Map<string, string> = new Map();

  function runUrl(runId: string): string {
    return '/api/harness/runs/' + encodeURIComponent(runId);
  }

  function randomToken(): string {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID();
    }
    return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
  }

  function idempotencyKey(identity: string): string {
    let value = idempotencyKeyCache.get(identity);
    if (!value) {
      value = 'harness-ui-' + randomToken();
      idempotencyKeyCache.set(identity, value);
    }
    return value;
  }

  async function api(path: string, options?: RequestInit): Promise<any> {
    const response = await fetch(path, options || {});
    const text = await response.text();
    let body: any = {};
    if (text) {
      try {
        body = JSON.parse(text);
      } catch (ignored) {
        body = text;
      }
    }
    if (!response.ok) {
      const message = body && typeof body === 'object'
        ? (body.message || body.error || body.code) : body;
      const error = new Error(message || ('HTTP ' + response.status));
      (error as any).status = response.status;
      (error as any).body = body;
      throw error;
    }
    return body;
  }

  async function optionalApi(path: string): Promise<any> {
    try {
      return await api(path);
    } catch (error: any) {
      if (error.status === 404) {
        return null;
      }
      throw error;
    }
  }

  async function post(path: string, payload?: any, identity?: string): Promise<any> {
    const headers: Record<string, string> = {};
    if (payload !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    if (identity) {
      headers['Idempotency-Key'] = idempotencyKey(identity);
    }
    return api(path, {
      method: 'POST',
      headers,
      body: payload === undefined ? undefined : JSON.stringify(payload)
    });
  }

  function showError(prefix: string, error: any): void {
    ElMessage.error(prefix + '：' + (error.message || error));
  }

  return { idempotencyKeyCache, runUrl, randomToken, idempotencyKey, api, optionalApi, post, showError };
}