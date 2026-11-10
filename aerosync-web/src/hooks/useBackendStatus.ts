import { useEffect, useState } from 'react';
import { getJson } from '../api/client';

export type BackendStatus = 'checking' | 'online' | 'offline';

/**
 * Checks that the backend answers (GET /api/airports, small and public) on
 * mount and every `intervalMs`. Drives the "System online" indicator.
 */
export function useBackendStatus(intervalMs = 30_000): BackendStatus {
  const [status, setStatus] = useState<BackendStatus>('checking');

  useEffect(() => {
    let active = true;
    const check = () =>
      getJson<unknown>('/api/airports', {}, { timeoutMs: 5_000 }).then(
        () => active && setStatus('online'),
        () => active && setStatus('offline'),
      );
    check();
    const timer = window.setInterval(check, intervalMs);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [intervalMs]);

  return status;
}
