/**
 * 集中 API client：统一 fetch + JSON 解析 + 错误处理。
 * 各域模块（chat.ts / admin.ts 等）基于此构建。
 */

export class ApiError extends Error {
  constructor(
    public status: number,
    public body: unknown,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** 发起 fetch 并解析 JSON；非 2xx 抛 ApiError */
export async function fetchJson<T = any>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(url, options);
  const text = await res.text();
  let body: unknown;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  if (!res.ok) {
    const message =
      body && typeof body === 'object' && 'message' in body
        ? String((body as any).message)
        : `HTTP ${res.status}`;
    throw new ApiError(res.status, body, message);
  }
  return body as T;
}

/** POST JSON */
export async function postJson<T = any>(url: string, payload?: unknown, options?: RequestInit): Promise<T> {
  return fetchJson<T>(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: payload != null ? JSON.stringify(payload) : undefined,
    ...options,
  });
}

/** DELETE */
export async function deleteJson<T = any>(url: string, options?: RequestInit): Promise<T> {
  return fetchJson<T>(url, { method: 'DELETE', ...options });
}

/** PUT JSON */
export async function putJson<T = any>(url: string, payload?: unknown, options?: RequestInit): Promise<T> {
  return postJson<T>(url, payload, { method: 'PUT', ...options });
}

/** 构造 query string */
export function query(params: Record<string, string | number | boolean | undefined>): string {
  const sp = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value != null) sp.set(key, String(value));
  }
  const qs = sp.toString();
  return qs ? `?${qs}` : '';
}