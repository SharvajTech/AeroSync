import { describe, expect, it } from 'vitest';
import { RequestCache } from './cache';

function counter<T>(value: T) {
  const calls = { count: 0 };
  const load = async () => {
    calls.count += 1;
    return value;
  };
  return { calls, load };
}

describe('RequestCache', () => {
  it('shares one request between concurrent callers of the same key', async () => {
    const cache = new RequestCache(60_000);
    const { calls, load } = counter('network');
    const [a, b] = await Promise.all([cache.get('network:2026-11-09', load), cache.get('network:2026-11-09', load)]);
    expect(a).toBe('network');
    expect(b).toBe('network');
    expect(calls.count).toBe(1);
  });

  it('reuses a value until it expires', async () => {
    let now = 1_000;
    const cache = new RequestCache(60_000, () => now);
    const { calls, load } = counter(42);
    await cache.get('k', load);
    now += 59_999;
    await cache.get('k', load);
    expect(calls.count).toBe(1);
    expect(cache.peek('k')).toBe(42);
    now += 1;
    expect(cache.peek('k')).toBeUndefined();
    await cache.get('k', load);
    expect(calls.count).toBe(2);
  });

  it('does not cache failures', async () => {
    const cache = new RequestCache(60_000);
    let attempts = 0;
    const load = async () => {
      attempts += 1;
      if (attempts === 1) throw new Error('offline');
      return 'ok';
    };
    let failed = false;
    try {
      await cache.get('k', load);
    } catch {
      failed = true;
    }
    expect(failed).toBe(true);
    expect(await cache.get('k', load)).toBe('ok');
    expect(attempts).toBe(2);
  });

  it('peek returns nothing while a request is in flight', () => {
    const cache = new RequestCache(60_000);
    void cache.get('k', () => new Promise(() => undefined));
    expect(cache.peek('k')).toBeUndefined();
  });

  it('put stores a value under another key and invalidate removes by prefix', async () => {
    const cache = new RequestCache(60_000);
    cache.put('network:2026-11-09', 'today');
    cache.put('airports', 'list');
    const { calls, load } = counter('fresh');
    expect(await cache.get('network:2026-11-09', load)).toBe('today');
    cache.invalidate('network:');
    expect(cache.peek('network:2026-11-09')).toBeUndefined();
    expect(cache.peek('airports')).toBe('list');
    expect(await cache.get('network:2026-11-09', load)).toBe('fresh');
    expect(calls.count).toBe(1);
  });
});
