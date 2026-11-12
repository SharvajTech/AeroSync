import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, apiCache, keys } from '../api/endpoints';
import type { DataWindow, GraphResponse } from '../api/types';
import { useResource, type Resource } from '../hooks/useResource';
import { useAirport } from './AirportContext';

interface OperatingDateState {
  /** Selected service date (yyyy-MM-dd); null until the backend reports its "today". */
  date: string | null;
  setDate: (date: string) => void;
  /** First and last service dates that have flights. */
  dataWindow: DataWindow | null;
  /** Network of the selected airport for the selected date (shared by every page, fetched once per airport and date). */
  network: Resource<GraphResponse>;
  /** When the network was last loaded or refreshed successfully (Stage 4). */
  networkRefreshedAt: Date | null;
  /** Why the last background refresh failed; undefined when it succeeded. The graph keeps the last good data. */
  networkRefreshError: unknown;
}

/** How often the network of the selected date is refreshed in the background. */
export const NETWORK_REFRESH_MS = 120_000;

const OperatingDateContext = createContext<OperatingDateState | null>(null);

export function OperatingDateProvider({ children }: { children: ReactNode }) {
  const [selected, setSelected] = useState<string | null>(null);
  const { airport } = useAirport();
  const iata = airport.iata_code;
  // Keyed by airport: switching airports loads that airport's network and never shows the previous one.
  const network = useResource(keys.network(iata, selected), () => api.network(selected, iata));

  // Without an explicit date the backend answers for "today" in its
  // operations time zone; adopt that date (the response is cached under it).
  const date = selected ?? network.data?.service_date ?? null;
  const dataWindow = network.data?.data_window ?? null;

  // Stage 4: keep the graph current. The refresh never clears what is shown:
  // a newer answer replaces it, a failure is remembered and reported instead.
  const [refreshed, setRefreshed] = useState<{ at: Date | null; error: unknown }>({ at: null, error: undefined });
  const loaded = network.data;
  useEffect(() => {
    if (loaded) setRefreshed({ at: new Date(), error: undefined });
  }, [loaded]);
  const sync = network.sync;
  useEffect(() => {
    if (!date) return undefined;
    let current = true;
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      api.refreshNetwork(date, iata).then(
        (response) => {
          if (!current) return;
          // The first load of "today" is cached under the default key as well.
          if (selected === null) apiCache.put(keys.network(iata, null), response);
          sync();
        },
        (error: unknown) => {
          if (current) setRefreshed((previous) => ({ ...previous, error }));
        },
      );
    }, NETWORK_REFRESH_MS);
    return () => {
      current = false;
      window.clearInterval(timer);
    };
  }, [date, iata, selected, sync]);

  const setDate = useCallback((next: string) => setSelected(next), []);
  const value = useMemo<OperatingDateState>(
    () => ({
      date,
      setDate,
      dataWindow,
      network,
      networkRefreshedAt: refreshed.at,
      networkRefreshError: refreshed.error,
    }),
    [date, setDate, dataWindow, network, refreshed],
  );
  return <OperatingDateContext.Provider value={value}>{children}</OperatingDateContext.Provider>;
}

export function useOperatingDate(): OperatingDateState {
  const value = useContext(OperatingDateContext);
  if (!value) throw new Error('useOperatingDate must be used inside <OperatingDateProvider>');
  return value;
}
