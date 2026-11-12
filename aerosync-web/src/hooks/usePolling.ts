import { useCallback, useEffect, useRef, useState } from 'react';
import { Poller, type PollState } from './poller';

export interface Polled<T> extends PollState<T> {
  refresh: () => void;
}

const EMPTY: PollState<never> = { data: undefined, error: undefined, loading: true, lastUpdated: null };

/**
 * Polls `load` every `intervalMs` for as long as the component is mounted
 * and `key` stays the same (null = do not load). Changing the key, for
 * example the selected airport, drops the previous data immediately, so one
 * airport's data is never shown under another. Polling pauses while the tab
 * is hidden and refreshes when it becomes visible again.
 */
export function usePolling<T>(key: string | null, load: () => Promise<T>, intervalMs: number): Polled<T> {
  const loadRef = useRef(load);
  loadRef.current = load;
  const pollerRef = useRef<Poller<T> | null>(null);
  const [state, setState] = useState<{ key: string | null; value: PollState<T> }>({ key, value: EMPTY });

  useEffect(() => {
    if (key === null) {
      setState({ key, value: { ...EMPTY, loading: false } });
      return;
    }
    setState({ key, value: EMPTY });
    const poller = new Poller<T>({
      load: () => loadRef.current(),
      intervalMs,
      onChange: (value) => setState({ key, value }),
      paused: () => typeof document !== 'undefined' && document.hidden,
    });
    pollerRef.current = poller;
    poller.start();
    const onVisible = () => {
      if (!document.hidden) void poller.refresh();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      poller.stop();
      pollerRef.current = null;
    };
  }, [key, intervalMs]);

  const refresh = useCallback(() => {
    void pollerRef.current?.refresh();
  }, []);

  // Until the effect for a new key has run, do not expose the previous key's data.
  const value = state.key === key ? state.value : EMPTY;
  return { ...value, refresh };
}
