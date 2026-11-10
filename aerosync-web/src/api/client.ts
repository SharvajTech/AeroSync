import type { ApiErrorBody } from './types';
import { REQUEST_TIMEOUT_MS, apiBaseUrl } from './config';

export type ApiErrorKind =
  | 'bad_request'
  | 'unauthorized'
  | 'forbidden'
  | 'not_found'
  | 'rate_limited'
  | 'server'
  | 'http'
  | 'network'
  | 'timeout'
  | 'parse';

/** Error raised for every failed backend call; `kind` drives the UI message. */
export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number | null;
  readonly url: string;
  /** Backend `code` of auth errors (token_expired, forbidden, ...), if any. */
  readonly code: string | null;

  constructor(kind: ApiErrorKind, message: string, url: string, status: number | null = null, code: string | null = null) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
    this.url = url;
    this.code = code;
  }
}

// ── Authentication wiring ─────────────────────────────────────────────────
// The auth store registers how to read the current token and what to do when
// the backend answers 401 (session expired or revoked). Kept here so every
// request goes through one place.

interface AuthHooks {
  token: () => string | null;
  onUnauthorized: (error: ApiError) => void;
}

let authHooks: AuthHooks = { token: () => null, onUnauthorized: () => undefined };

export function configureAuth(hooks: AuthHooks): void {
  authHooks = hooks;
}

export type QueryValue = string | number | boolean | null | undefined;

export function buildUrl(path: string, params: Record<string, QueryValue> = {}, baseUrl = apiBaseUrl()): string {
  const query = Object.entries(params)
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&');
  return `${baseUrl}${path}${query ? `?${query}` : ''}`;
}

function kindForStatus(status: number): ApiErrorKind {
  if (status === 400) return 'bad_request';
  if (status === 401) return 'unauthorized';
  if (status === 403) return 'forbidden';
  if (status === 404) return 'not_found';
  if (status === 429) return 'rate_limited';
  if (status >= 500) return 'server';
  return 'http';
}

async function errorBody(response: Response): Promise<{ message: string; code: string | null }> {
  const fallback = { message: `HTTP ${response.status}`, code: null };
  try {
    const text = await response.text();
    if (!text) return fallback;
    try {
      const body = JSON.parse(text) as ApiErrorBody;
      if (body && typeof body.error === 'string' && body.error.trim()) {
        return { message: body.error, code: typeof body.code === 'string' ? body.code : null };
      }
    } catch {
      // not JSON — fall through to the raw text
    }
    return text.length > 200 ? fallback : { message: text, code: null };
  } catch {
    return fallback;
  }
}

export interface RequestOptions {
  signal?: AbortSignal;
  timeoutMs?: number;
  fetchImpl?: typeof fetch;
  /**
   * Send the session token and treat 401 as "signed out" (default true).
   * False for the login request, whose 401 means "wrong credentials".
   */
  auth?: boolean;
}

/** GET a JSON resource. Rejects with {@link ApiError} for HTTP, network, timeout and parse failures. */
export function getJson<T>(
  path: string,
  params: Record<string, QueryValue> = {},
  options: RequestOptions = {},
): Promise<T> {
  return requestJson<T>('GET', path, params, undefined, options);
}

/** POST (optionally with a JSON body) and parse the JSON response. */
export function postJson<T>(
  path: string,
  body?: unknown,
  params: Record<string, QueryValue> = {},
  options: RequestOptions = {},
): Promise<T> {
  return requestJson<T>('POST', path, params, body, options);
}

async function requestJson<T>(
  method: 'GET' | 'POST',
  path: string,
  params: Record<string, QueryValue>,
  body: unknown,
  options: RequestOptions,
): Promise<T> {
  const url = buildUrl(path, params);
  const useAuth = options.auth !== false;
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const token = useAuth ? authHooks.token() : null;
  if (token) headers.Authorization = `Bearer ${token}`;
  const fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
  const controller = new AbortController();
  const timeoutMs = options.timeoutMs ?? REQUEST_TIMEOUT_MS;
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const forwardAbort = () => controller.abort();
  options.signal?.addEventListener('abort', forwardAbort);

  let response: Response;
  try {
    response = await fetchImpl(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
  } catch (error) {
    if (timedOut) {
      throw new ApiError('timeout', `The backend did not respond within ${Math.round(timeoutMs / 1000)} s`, url);
    }
    if (options.signal?.aborted) {
      throw new ApiError('network', 'Request cancelled', url);
    }
    throw new ApiError('network', `Cannot reach the AeroSync backend (${(error as Error).message || 'network error'})`, url);
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener('abort', forwardAbort);
  }

  if (!response.ok) {
    const { message, code } = await errorBody(response);
    const error = new ApiError(kindForStatus(response.status), message, url, response.status, code);
    if (error.kind === 'unauthorized' && useAuth) authHooks.onUnauthorized(error);
    throw error;
  }
  try {
    return (await response.json()) as T;
  } catch {
    throw new ApiError('parse', 'The backend returned a response that is not valid JSON', url, response.status);
  }
}

/** Short, user-facing description of a failed request. */
export function describeError(error: unknown): { title: string; detail: string } {
  if (error instanceof ApiError) {
    switch (error.kind) {
      case 'not_found':
        return { title: 'Not found', detail: error.message };
      case 'forbidden':
        return { title: 'Not authorized', detail: error.message };
      case 'unauthorized':
        return {
          title: error.code === 'token_expired' ? 'Session expired' : 'Sign-in required',
          detail: error.message,
        };
      case 'rate_limited':
        return { title: 'Too many attempts', detail: error.message };
      case 'bad_request':
        return { title: 'Invalid request', detail: error.message };
      case 'server':
        return { title: 'Backend error', detail: error.message };
      case 'timeout':
        return { title: 'Request timed out', detail: error.message };
      case 'network':
        return { title: 'Backend unreachable', detail: error.message };
      case 'parse':
        return { title: 'Unexpected response', detail: error.message };
      default:
        return { title: `Request failed (${error.status ?? 'error'})`, detail: error.message };
    }
  }
  return { title: 'Something went wrong', detail: error instanceof Error ? error.message : String(error) };
}
