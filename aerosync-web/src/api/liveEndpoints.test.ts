import { describe, expect, it } from 'vitest';
import { ApiError, configureAuth, describeError } from './client';
import { api, apiCache, keys } from './endpoints';
import { liveTraffic, networkResponse, systemStatus, weather } from '../test/fixtures';

interface Seen {
  url: string;
  authorization: string | undefined;
}

/** Replaces the global fetch for one test; `reply` decides the answer per URL. */
async function withFetch<T>(reply: (url: string) => { status: number; body: unknown }, run: (seen: Seen[]) => Promise<T>): Promise<T> {
  const original = globalThis.fetch;
  const seen: Seen[] = [];
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    seen.push({ url, authorization: (init?.headers as Record<string, string> | undefined)?.Authorization });
    const { status, body } = reply(url);
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  }) as typeof fetch;
  configureAuth({ token: () => 'session-token', onUnauthorized: () => undefined });
  try {
    return await run(seen);
  } finally {
    globalThis.fetch = original;
    configureAuth({ token: () => null, onUnauthorized: () => undefined });
    apiCache.invalidate();
  }
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

describe('live data endpoints', () => {
  it('asks for live aircraft and weather of exactly one airport, with the session token', async () => {
    await withFetch(
      (url) => ({ status: 200, body: url.includes('weather') ? weather('BOM', 'LIVE') : liveTraffic('BOM', 'LIVE') }),
      async (seen) => {
        const traffic = await api.liveAircraft('BOM');
        const report = await api.airportWeather('BOM');
        expect(traffic.airport).toBe('BOM');
        expect(report.airport).toBe('BOM');
        expect(seen[0].url.endsWith('/api/airports/BOM/live-aircraft')).toBe(true);
        expect(seen[1].url.endsWith('/api/airports/BOM/weather')).toBe(true);
        expect(seen[0].authorization).toBe('Bearer session-token');
        expect(seen[1].authorization).toBe('Bearer session-token');
      },
    );
  });

  it('never reuses the answer of one airport for another', async () => {
    await withFetch(
      (url) => ({ status: 200, body: liveTraffic(url.includes('/DEL/') ? 'DEL' : 'BOM', 'LIVE') }),
      async (seen) => {
        expect((await api.liveAircraft('BOM')).airport).toBe('BOM');
        expect((await api.liveAircraft('DEL')).airport).toBe('DEL');
        // Live data is not cached: asking again goes back to the backend.
        expect((await api.liveAircraft('BOM')).airport).toBe('BOM');
        expect(seen).toHaveLength(3);
        expect(seen.map((s) => s.url.split('/api/airports/')[1])).toEqual(['BOM/live-aircraft', 'DEL/live-aircraft', 'BOM/live-aircraft']);
      },
    );
  });

  it('surfaces the backend refusal for an airport outside the user scope', async () => {
    await withFetch(
      () => ({ status: 403, body: { error: 'You are not authorized for airport DEL', status: 403, code: 'forbidden' } }),
      async () => {
        const traffic = await failure(api.liveAircraft('DEL'));
        expect(traffic.kind).toBe('forbidden');
        expect(describeError(traffic).title).toBe('Not authorized');
        const report = await failure(api.airportWeather('DEL'));
        expect(report.status).toBe(403);
      },
    );
  });

  it('encodes the airport code instead of letting it change the path', async () => {
    await withFetch(
      () => ({ status: 404, body: { error: 'Unknown airport', status: 404 } }),
      async (seen) => {
        await failure(api.liveAircraft('BOM/../DEL'));
        expect(seen[0].url.endsWith('/api/airports/BOM%2F..%2FDEL/live-aircraft')).toBe(true);
      },
    );
  });

  it('reads the system status fresh on every call', async () => {
    let mode: 'SYNTHETIC' | 'HYBRID' = 'SYNTHETIC';
    await withFetch(
      () => ({ status: 200, body: systemStatus(mode, []) }),
      async (seen) => {
        expect((await api.systemStatus()).data_mode).toBe('SYNTHETIC');
        mode = 'HYBRID';
        expect((await api.systemStatus()).data_mode).toBe('HYBRID');
        expect(seen).toHaveLength(2);
        expect(seen[0].url.endsWith('/api/system/status')).toBe(true);
      },
    );
  });
});

describe('network refresh', () => {
  it('fetches the network of the active airport again and replaces the cached copy', async () => {
    let delay = 0;
    await withFetch(
      () => {
        const body = networkResponse();
        body.nodes = body.nodes.map((n) => (n.type === 'FLIGHT' && n.flight_number === 'AI101' ? { ...n, delay_minutes: delay } : n));
        return { status: 200, body };
      },
      async (seen) => {
        const delayOf = (r: Awaited<ReturnType<typeof api.network>>) =>
          r.nodes.find((n) => n.type === 'FLIGHT' && n.flight_number === 'AI101' && n.type === 'FLIGHT')!;
        const first = await api.network('2026-11-09', 'BOM');
        delay = 45;
        // The cached call still answers from the cache ...
        await api.network('2026-11-09', 'BOM');
        expect(seen).toHaveLength(1);
        // ... a refresh goes to the backend, scoped to the same airport ...
        const refreshed = await api.refreshNetwork('2026-11-09', 'BOM');
        expect(seen).toHaveLength(2);
        expect(seen[1].url).toContain('airports=BOM');
        expect(seen[1].url).toContain('date=2026-11-09');
        expect((delayOf(first) as { delay_minutes: number }).delay_minutes).toBe(0);
        expect((delayOf(refreshed) as { delay_minutes: number }).delay_minutes).toBe(45);
        // ... and later cached calls see the refreshed data.
        const again = await api.network('2026-11-09', 'BOM');
        expect((delayOf(again) as { delay_minutes: number }).delay_minutes).toBe(45);
        expect(seen).toHaveLength(2);
      },
    );
  });

  it('keeps the cache entries of different airports apart', () => {
    expect(keys.network('BOM', '2026-11-09') === keys.network('DEL', '2026-11-09')).toBe(false);
  });
});
