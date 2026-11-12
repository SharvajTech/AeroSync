/**
 * Repeats an async load on an interval and keeps the last good answer.
 * Framework-free so it can be tested without a browser; `usePolling` wraps
 * it for React.
 *
 * - A failed refresh keeps the previous data and records the error, so the
 *   UI can say "showing data from 10:42" instead of going blank or, worse,
 *   pretending to be current.
 * - A response that arrives after `stop()` is ignored.
 */

export interface PollState<T> {
  data: T | undefined;
  /** Error of the most recent attempt; undefined after a success. */
  error: unknown;
  /** True until the first attempt has finished. */
  loading: boolean;
  /** When data was last loaded successfully. */
  lastUpdated: Date | null;
}

export interface PollerOptions<T> {
  load: () => Promise<T>;
  intervalMs: number;
  onChange: (state: PollState<T>) => void;
  /** Skip a scheduled refresh while this returns true (hidden tab). */
  paused?: () => boolean;
  now?: () => Date;
  setTimer?: (callback: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

export class Poller<T> {
  private state: PollState<T> = { data: undefined, error: undefined, loading: true, lastUpdated: null };
  private timer: unknown = null;
  private stopped = true;
  private inFlight = false;

  constructor(private readonly options: PollerOptions<T>) {}

  getState(): PollState<T> {
    return this.state;
  }

  /** Loads now and then every interval. */
  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    void this.refresh();
  }

  stop(): void {
    this.stopped = true;
    if (this.timer !== null) {
      (this.options.clearTimer ?? ((handle) => clearTimeout(handle as ReturnType<typeof setTimeout>)))(this.timer);
      this.timer = null;
    }
  }

  /** Loads now (also used by "Refresh" buttons). Overlapping calls are merged. */
  async refresh(): Promise<void> {
    if (this.stopped || this.inFlight) return;
    this.inFlight = true;
    try {
      const data = await this.options.load();
      if (this.stopped) return;
      this.update({ data, error: undefined, loading: false, lastUpdated: (this.options.now ?? (() => new Date()))() });
    } catch (error) {
      if (this.stopped) return;
      this.update({ ...this.state, error, loading: false });
    } finally {
      this.inFlight = false;
      this.schedule();
    }
  }

  private schedule(): void {
    if (this.stopped) return;
    const setTimer = this.options.setTimer ?? ((callback, ms) => setTimeout(callback, ms));
    this.timer = setTimer(() => {
      this.timer = null;
      if (this.options.paused?.()) this.schedule();
      else void this.refresh();
    }, this.options.intervalMs);
  }

  private update(state: PollState<T>): void {
    this.state = state;
    this.options.onChange(state);
  }
}
