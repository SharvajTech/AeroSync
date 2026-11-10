import { describe, expect, it } from 'vitest';
import { ApiError, buildUrl, describeError, getJson } from './client';

function respond(status: number, body: string, contentType = 'application/json'): typeof fetch {
  return (async () => new Response(body, { status, headers: { 'Content-Type': contentType } })) as typeof fetch;
}

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('expected the request to fail');
}

describe('buildUrl', () => {
  it('joins the base URL, path and encoded query parameters', () => {
    expect(buildUrl('/api/graph/network', { date: '2026-11-09', include_dependencies: true }, 'http://backend:8080'))
      .toBe('http://backend:8080/api/graph/network?date=2026-11-09&include_dependencies=true');
  });

  it('skips null, undefined and empty parameters', () => {
    expect(buildUrl('/api/graph/flights/x/affected', { types: '', max_depth: 6, horizon_hours: null, min_impact_weight: undefined }, ''))
      .toBe('/api/graph/flights/x/affected?max_depth=6');
  });

  it('encodes reserved characters', () => {
    expect(buildUrl('/api/x', { types: 'AIRCRAFT_ROTATION,CREW_ROTATION' }, '')).toBe('/api/x?types=AIRCRAFT_ROTATION%2CCREW_ROTATION');
  });
});

describe('getJson', () => {
  it('returns the parsed body of a successful response', async () => {
    const body = await getJson<{ view: string }>('/api/graph/network', {}, { fetchImpl: respond(200, '{"view":"NATIONAL_NETWORK"}') });
    expect(body).toEqual({ view: 'NATIONAL_NETWORK' });
  });

  it('maps 400 to bad_request with the backend error message', async () => {
    const error = await failure(getJson('/api/graph/flights/AI101', {}, {
      fetchImpl: respond(400, '{"error":"Flight number AI101 is not unique across dates","status":400}'),
    }));
    expect(error.kind).toBe('bad_request');
    expect(error.status).toBe(400);
    expect(error.message).toBe('Flight number AI101 is not unique across dates');
  });

  it('maps 404 to not_found', async () => {
    const error = await failure(getJson('/api/graph/airports/XYZ', {}, { fetchImpl: respond(404, '{"error":"Airport XYZ not found","status":404}') }));
    expect(error.kind).toBe('not_found');
    expect(describeError(error)).toEqual({ title: 'Not found', detail: 'Airport XYZ not found' });
  });

  it('maps 5xx to server and falls back to the status when the body is empty', async () => {
    const error = await failure(getJson('/api/airports', {}, { fetchImpl: respond(503, '') }));
    expect(error.kind).toBe('server');
    expect(error.message).toBe('HTTP 503');
  });

  it('reports a network failure as unreachable', async () => {
    const fetchImpl = (async () => {
      throw new TypeError('Failed to fetch');
    }) as typeof fetch;
    const error = await failure(getJson('/api/airports', {}, { fetchImpl }));
    expect(error.kind).toBe('network');
    expect(describeError(error).title).toBe('Backend unreachable');
  });

  it('aborts and reports a timeout when the backend does not answer', async () => {
    const fetchImpl = ((_url: string, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
    })) as typeof fetch;
    const error = await failure(getJson('/api/airports', {}, { fetchImpl, timeoutMs: 20 }));
    expect(error.kind).toBe('timeout');
  });

  it('reports invalid JSON as a parse error', async () => {
    const error = await failure(getJson('/api/airports', {}, { fetchImpl: respond(200, '<html>proxy page</html>', 'text/html') }));
    expect(error.kind).toBe('parse');
  });
});

describe('describeError', () => {
  it('describes unknown errors generically', () => {
    expect(describeError(new Error('boom'))).toEqual({ title: 'Something went wrong', detail: 'boom' });
  });
});
