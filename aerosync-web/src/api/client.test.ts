import { describe, expect, it } from 'vitest';
import { ApiError, buildUrl, configureAuth, describeError, getJson, postJson } from './client';

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

describe('authenticated requests', () => {
  function capture(status = 200, body = '{}') {
    const seen: { url: string; init: RequestInit | undefined }[] = [];
    const fetchImpl = (async (url: string, init?: RequestInit) => {
      seen.push({ url, init });
      return new Response(body, { status, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    return { seen, fetchImpl };
  }

  function headerOf(init: RequestInit | undefined, name: string): string | undefined {
    return (init?.headers as Record<string, string> | undefined)?.[name];
  }

  it('sends the session token as a bearer header', async () => {
    configureAuth({ token: () => 'session-token', onUnauthorized: () => undefined });
    try {
      const { seen, fetchImpl } = capture();
      await getJson('/api/graph/network', { airports: 'BOM' }, { fetchImpl });
      expect(headerOf(seen[0].init, 'Authorization')).toBe('Bearer session-token');
      expect(seen[0].url).toContain('airports=BOM');
    } finally {
      configureAuth({ token: () => null, onUnauthorized: () => undefined });
    }
  });

  it('sends no token for public calls and when signed out', async () => {
    configureAuth({ token: () => 'session-token', onUnauthorized: () => undefined });
    try {
      const { seen, fetchImpl } = capture();
      await postJson('/api/auth/login', { username: 'bom.ops', password: 'x' }, {}, { fetchImpl, auth: false });
      expect(headerOf(seen[0].init, 'Authorization')).toBeUndefined();
      expect(seen[0].init?.method).toBe('POST');
      expect(seen[0].init?.body).toBe('{"username":"bom.ops","password":"x"}');
      expect(headerOf(seen[0].init, 'Content-Type')).toBe('application/json');
    } finally {
      configureAuth({ token: () => null, onUnauthorized: () => undefined });
    }
    const { seen, fetchImpl } = capture();
    await getJson('/api/flights', {}, { fetchImpl });
    expect(headerOf(seen[0].init, 'Authorization')).toBeUndefined();
  });

  it('reports a 401 on a protected request to the session handler', async () => {
    const reported: ApiError[] = [];
    configureAuth({ token: () => 'expired-token', onUnauthorized: (error) => reported.push(error) });
    try {
      const { fetchImpl } = capture(401, '{"error":"Your session has expired; please sign in again","status":401,"code":"token_expired"}');
      const error = await failure(getJson('/api/graph/network', {}, { fetchImpl }));
      expect(error.kind).toBe('unauthorized');
      expect(error.code).toBe('token_expired');
      expect(reported).toHaveLength(1);
      expect(describeError(error).title).toBe('Session expired');
    } finally {
      configureAuth({ token: () => null, onUnauthorized: () => undefined });
    }
  });

  it('does not treat a failed login (401) as an expired session', async () => {
    const reported: ApiError[] = [];
    configureAuth({ token: () => null, onUnauthorized: (error) => reported.push(error) });
    try {
      const { fetchImpl } = capture(401, '{"error":"Invalid username or password","status":401,"code":"invalid_credentials"}');
      const error = await failure(postJson('/api/auth/login', {}, {}, { fetchImpl, auth: false }));
      expect(error.code).toBe('invalid_credentials');
      expect(reported).toHaveLength(0);
    } finally {
      configureAuth({ token: () => null, onUnauthorized: () => undefined });
    }
  });

  it('maps 403 to a "Not authorized" error without ending the session', async () => {
    const reported: ApiError[] = [];
    configureAuth({ token: () => 'token', onUnauthorized: (error) => reported.push(error) });
    try {
      const { fetchImpl } = capture(403, '{"error":"You are not authorized for airport DEL","status":403,"code":"forbidden"}');
      const error = await failure(getJson('/api/graph/airports/DEL', {}, { fetchImpl }));
      expect(error.kind).toBe('forbidden');
      expect(describeError(error)).toEqual({ title: 'Not authorized', detail: 'You are not authorized for airport DEL' });
      expect(reported).toHaveLength(0);
    } finally {
      configureAuth({ token: () => null, onUnauthorized: () => undefined });
    }
  });

  it('maps 429 to rate_limited', async () => {
    const { fetchImpl } = capture(429, '{"error":"Too many failed login attempts. Try again in 15 minute(s).","status":429}');
    const error = await failure(postJson('/api/auth/login', {}, {}, { fetchImpl, auth: false }));
    expect(error.kind).toBe('rate_limited');
  });
});
