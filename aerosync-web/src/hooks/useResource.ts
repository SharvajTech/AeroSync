import { useCallback, useEffect, useRef, useState } from 'react';
import { apiCache } from '../api/endpoints';

export interface Resource<T> {
  data: T | undefined;
  error: unknown;
  loading: boolean;
  /** Drops the cached value for this key and loads it again. */
  reload: () => void;
}

interface State<T> {
  key: string | null;
  data: T | undefined;
  error: unknown;
  loading: boolean;
}

/**
 * Loads a cached API resource identified by `key` (null = nothing to load).
 * Results for a key that is no longer current are ignored, so fast date or
 * selection changes never show stale data.
 */
export function useResource<T>(key: string | null, load: () => Promise<T>): Resource<T> {
  const loadRef = useRef(load);
  loadRef.current = load;
  const [nonce, setNonce] = useState(0);
  const [state, setState] = useState<State<T>>(() => ({
    key,
    data: key ? apiCache.peek<T>(key) : undefined,
    error: undefined,
    loading: key !== null && apiCache.peek<T>(key) === undefined,
  }));

  useEffect(() => {
    if (key === null) {
      setState({ key, data: undefined, error: undefined, loading: false });
      return;
    }
    const cached = apiCache.peek<T>(key);
    if (cached !== undefined) {
      setState({ key, data: cached, error: undefined, loading: false });
      return;
    }
    let current = true;
    setState((previous) => ({ key, data: previous.key === key ? previous.data : undefined, error: undefined, loading: true }));
    loadRef.current().then(
      (data) => {
        if (current) setState({ key, data, error: undefined, loading: false });
      },
      (error: unknown) => {
        if (current) setState({ key, data: undefined, error, loading: false });
      },
    );
    return () => {
      current = false;
    };
  }, [key, nonce]);

  const reload = useCallback(() => {
    if (key) apiCache.invalidate(key);
    setNonce((n) => n + 1);
  }, [key]);

  // While the effect for a new key has not run yet, do not expose the previous key's data.
  const fresh = state.key === key;
  return {
    data: fresh ? state.data : undefined,
    error: fresh ? state.error : undefined,
    loading: fresh ? state.loading : key !== null,
    reload,
  };
}
