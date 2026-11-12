import { createContext, useContext, type ReactNode } from 'react';
import { api } from '../api/endpoints';
import type { SystemStatus } from '../api/types';
import { usePolling, type Polled } from '../hooks/usePolling';

/** How often the pipeline status is refreshed. */
export const STATUS_POLL_MS = 30_000;

const SystemStatusContext = createContext<Polled<SystemStatus> | null>(null);

/**
 * Polls GET /api/system/status once for the whole signed-in app: data mode,
 * feed freshness, dependency health and the disruption monitor.
 */
export function SystemStatusProvider({ children }: { children: ReactNode }) {
  const status = usePolling<SystemStatus>('system-status', () => api.systemStatus(), STATUS_POLL_MS);
  return <SystemStatusContext.Provider value={status}>{children}</SystemStatusContext.Provider>;
}

export function useSystemStatus(): Polled<SystemStatus> {
  const value = useContext(SystemStatusContext);
  if (!value) throw new Error('useSystemStatus must be used inside SystemStatusProvider');
  return value;
}
