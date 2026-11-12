import { describe, expect, it } from 'vitest';
import { Poller, type PollState } from './poller';

/** A poller with a manual timer: `tick()` fires the pending timer. */
function harness<T>(load: () => Promise<T>, paused: () => boolean = () => false) {
  const states: PollState<T>[] = [];
  let pending: (() => void) | null = null;
  let clock = new Date('2026-11-10T10:00:00Z');
  const poller = new Poller<T>({
    load,
    intervalMs: 30_000,
    onChange: (state) => states.push(state),
    paused,
    now: () => clock,
    setTimer: (callback) => {
      pending = callback;
      return 1;
    },
    clearTimer: () => {
      pending = null;
    },
  });
  return {
    poller,
    states,
    hasTimer: () => pending !== null,
    advance: (ms: number) => {
      clock = new Date(clock.getTime() + ms);
    },
    tick: async () => {
      const callback = pending;
      pending = null;
      callback?.();
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
    },
    settle: async () => {
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
    },
  };
}

describe('Poller', () => {
  it('loads immediately and again on every interval, replacing the data', async () => {
    let n = 0;
    const h = harness(async () => ({ version: ++n }));
    expect(h.poller.getState().loading).toBe(true);

    h.poller.start();
    await h.settle();
    expect(h.poller.getState().data).toEqual({ version: 1 });
    expect(h.poller.getState().loading).toBe(false);
    expect(h.poller.getState().lastUpdated?.toISOString()).toBe('2026-11-10T10:00:00.000Z');

    h.advance(30_000);
    await h.tick();
    expect(h.poller.getState().data).toEqual({ version: 2 });
    expect(h.poller.getState().lastUpdated?.toISOString()).toBe('2026-11-10T10:00:30.000Z');
    expect(h.hasTimer()).toBe(true);
  });

  it('keeps the last good data when a refresh fails, and reports the error', async () => {
    let fail = false;
    const h = harness(async () => {
      if (fail) throw new Error('backend unreachable');
      return 'fresh';
    });
    h.poller.start();
    await h.settle();

    fail = true;
    h.advance(30_000);
    await h.tick();
    const failed = h.poller.getState();
    expect(failed.data).toBe('fresh');
    expect((failed.error as Error).message).toBe('backend unreachable');
    // The time of the last success does not move, so the UI can say how old the data is.
    expect(failed.lastUpdated?.toISOString()).toBe('2026-11-10T10:00:00.000Z');
    expect(h.hasTimer()).toBe(true);

    fail = false;
    h.advance(30_000);
    await h.tick();
    expect(h.poller.getState().error).toBeUndefined();
    expect(h.poller.getState().lastUpdated?.toISOString()).toBe('2026-11-10T10:01:00.000Z');
  });

  it('reports a failed first load without data', async () => {
    const h = harness<string>(async () => {
      throw new Error('403');
    });
    h.poller.start();
    await h.settle();
    expect(h.poller.getState().data).toBeUndefined();
    expect(h.poller.getState().loading).toBe(false);
    expect(h.poller.getState().lastUpdated).toBeNull();
  });

  it('ignores an answer that arrives after it was stopped', async () => {
    let release: (value: string) => void = () => undefined;
    const h = harness(() => new Promise<string>((resolve) => {
      release = resolve;
    }));
    h.poller.start();
    h.poller.stop();
    release('data of the previous airport');
    await h.settle();

    expect(h.states).toHaveLength(0);
    expect(h.poller.getState().data).toBeUndefined();
    expect(h.hasTimer()).toBe(false);
  });

  it('skips refreshes while paused and resumes afterwards', async () => {
    let calls = 0;
    let hidden = false;
    const h = harness(async () => ++calls, () => hidden);
    h.poller.start();
    await h.settle();
    expect(calls).toBe(1);

    hidden = true;
    await h.tick();
    await h.tick();
    expect(calls).toBe(1);

    hidden = false;
    await h.tick();
    expect(calls).toBe(2);
  });

  it('merges overlapping refresh requests into one load', async () => {
    let calls = 0;
    let release: () => void = () => undefined;
    const h = harness(() => new Promise<number>((resolve) => {
      calls++;
      release = () => resolve(calls);
    }));
    h.poller.start();
    void h.poller.refresh();
    void h.poller.refresh();
    release();
    await h.settle();
    expect(calls).toBe(1);
    expect(h.poller.getState().data).toBe(1);
  });
});
