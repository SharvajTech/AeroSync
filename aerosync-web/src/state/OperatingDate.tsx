import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { api, keys } from '../api/endpoints';
import type { DataWindow, GraphResponse } from '../api/types';
import { useResource, type Resource } from '../hooks/useResource';

interface OperatingDateState {
  /** Selected service date (yyyy-MM-dd); null until the backend reports its "today". */
  date: string | null;
  setDate: (date: string) => void;
  /** First and last service dates that have flights. */
  dataWindow: DataWindow | null;
  /** National network for the selected date (shared by every page, fetched once per date). */
  network: Resource<GraphResponse>;
}

const OperatingDateContext = createContext<OperatingDateState | null>(null);

export function OperatingDateProvider({ children }: { children: ReactNode }) {
  const [selected, setSelected] = useState<string | null>(null);
  const network = useResource(keys.network(selected), () => api.network(selected));

  // Without an explicit date the backend answers for "today" in its
  // operations time zone; adopt that date (the response is cached under it).
  const date = selected ?? network.data?.service_date ?? null;
  const dataWindow = network.data?.data_window ?? null;

  const setDate = useCallback((next: string) => setSelected(next), []);
  const value = useMemo<OperatingDateState>(
    () => ({ date, setDate, dataWindow, network }),
    [date, setDate, dataWindow, network],
  );
  return <OperatingDateContext.Provider value={value}>{children}</OperatingDateContext.Provider>;
}

export function useOperatingDate(): OperatingDateState {
  const value = useContext(OperatingDateContext);
  if (!value) throw new Error('useOperatingDate must be used inside <OperatingDateProvider>');
  return value;
}
