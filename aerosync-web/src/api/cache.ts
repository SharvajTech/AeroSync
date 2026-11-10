/**
 * Small request cache: concurrent callers of the same key share one
 * request, successful results are reused until they expire, failures are
 * never cached (the next call retries).
 */
interface Entry<T> {
  promise: Promise<T>;
  settledAt: number | null;
  value?: T;
}

export class RequestCache {
  private readonly entries = new Map<string, Entry<unknown>>();

  constructor(
    private readonly ttlMs: number,
    private readonly now: () => number = () => Date.now(),
  ) {}

  get<T>(key: string, load: () => Promise<T>): Promise<T> {
    const existing = this.entries.get(key) as Entry<T> | undefined;
    if (existing && (existing.settledAt === null || this.now() - existing.settledAt < this.ttlMs)) {
      return existing.promise;
    }
    const entry: Entry<T> = { promise: Promise.resolve() as unknown as Promise<T>, settledAt: null };
    entry.promise = load().then(
      (value) => {
        entry.settledAt = this.now();
        entry.value = value;
        return value;
      },
      (error: unknown) => {
        if (this.entries.get(key) === entry) this.entries.delete(key);
        throw error;
      },
    );
    this.entries.set(key, entry as Entry<unknown>);
    return entry.promise;
  }

  /** Value already loaded and still fresh, if any (lets components render without a loading flash). */
  peek<T>(key: string): T | undefined {
    const entry = this.entries.get(key) as Entry<T> | undefined;
    if (!entry || entry.settledAt === null || this.now() - entry.settledAt >= this.ttlMs) return undefined;
    return entry.value;
  }

  /** Stores an already-fetched value under another key (e.g. the "today" network under its date). */
  put<T>(key: string, value: T): void {
    this.entries.set(key, { promise: Promise.resolve(value), settledAt: this.now(), value });
  }

  invalidate(prefix = ''): void {
    for (const key of [...this.entries.keys()]) {
      if (key.startsWith(prefix)) this.entries.delete(key);
    }
  }
}
