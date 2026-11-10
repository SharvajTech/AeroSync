import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import type { AirportSummary, UserProfile } from '../api/types';
import type { KeyValueStorage } from '../auth/session';

interface AirportState {
  /** The airport the dashboard currently operates (always one of the user's airports). */
  airport: AirportSummary;
  /** Every airport the signed-in user is authorized for. */
  airports: AirportSummary[];
  setAirport: (iata: string) => void;
  isAuthorized: (iata: string | null | undefined) => boolean;
  user: UserProfile;
}

const AirportContext = createContext<AirportState | null>(null);

export function airportStorageKey(userId: string): string {
  return `aerosync.airport.${userId}`;
}

/** The preferred airport when the user may operate it, otherwise their first airport. */
export function chooseAirport(airports: AirportSummary[], preferred: string | null | undefined): AirportSummary | null {
  if (airports.length === 0) return null;
  const wanted = preferred?.trim().toUpperCase();
  return airports.find((a) => a.iata_code === wanted) ?? airports[0];
}

/**
 * Selected airport of the signed-in user. Only the user's own airports can be
 * selected; the choice is remembered per user for this tab.
 */
export function AirportProvider({ user, storage, children }: { user: UserProfile; storage: KeyValueStorage; children: ReactNode }) {
  const key = airportStorageKey(user.user_id);
  const [preferred, setPreferred] = useState<string | null>(() => {
    try {
      return storage.getItem(key);
    } catch {
      return null;
    }
  });
  const airport = chooseAirport(user.airports, preferred);

  const isAuthorized = useCallback(
    (iata: string | null | undefined) => !!iata && user.airports.some((a) => a.iata_code === iata.trim().toUpperCase()),
    [user.airports],
  );

  const setAirport = useCallback(
    (iata: string) => {
      const next = chooseAirport(user.airports, iata);
      if (!next || next.iata_code !== iata.trim().toUpperCase()) return;
      setPreferred(next.iata_code);
      try {
        storage.setItem(key, next.iata_code);
      } catch {
        // not persisted; still selected for this page
      }
    },
    [user.airports, storage, key],
  );

  const value = useMemo<AirportState | null>(
    () => (airport ? { airport, airports: user.airports, setAirport, isAuthorized, user } : null),
    [airport, user, setAirport, isAuthorized],
  );
  if (!value) return null;
  return <AirportContext.Provider value={value}>{children}</AirportContext.Provider>;
}

export function useAirport(): AirportState {
  const value = useContext(AirportContext);
  if (!value) throw new Error('useAirport must be used inside <AirportProvider>');
  return value;
}
