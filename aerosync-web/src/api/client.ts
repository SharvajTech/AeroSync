import type { ApiErrorBody } from './types';
import { REQUEST_TIMEOUT_MS, apiBaseUrl } from './config';

export type ApiErrorKind = 'bad_request' | 'not_found' | 'server' | 'http' | 'network' | 'timeout' | 'parse';

/** Error raised for every failed backend call; `kind` drives the UI message. */
export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number | null;
  readonly url: string;

  constructor(kind: ApiErrorKind, message: string, url: string, status: number | null = null) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
    this.url = url;
  }
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
  if (status === 404) return 'not_found';
  if (status >= 500) return 'server';
  return 'http';
}

async function errorMessage(response: Response): Promise<string> {
  try {
    const text = await response.text();
    if (!text) return `HTTP ${response.status}`;
    try {
      const body = JSON.parse(text) as ApiErrorBody;
      if (body && typeof body.error === 'string' && body.error.trim()) return body.error;
    } catch {
      // not JSON — fall through to the raw text
    }
    return text.length > 200 ? `HTTP ${response.status}` : text;
  } catch {
    return `HTTP ${response.status}`;
  }
}

export interface RequestOptions {
  signal?: AbortSignal;
  timeoutMs?: number;
  fetchImpl?: typeof fetch;
}

/** GET a JSON resource. Rejects with {@link ApiError} for HTTP, network, timeout and parse failures. */
export async function getJson<T>(
  path: string,
  params: Record<string, QueryValue> = {},
  options: RequestOptions = {},
): Promise<T> {
  const url = buildUrl(path, params);
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
    response = await fetchImpl(url, { headers: { Accept: 'application/json' }, signal: controller.signal });
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
    throw new ApiError(kindForStatus(response.status), await errorMessage(response), url, response.status);
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
