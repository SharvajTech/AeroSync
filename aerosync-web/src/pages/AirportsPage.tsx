import { useMemo, useState } from 'react';
import { LuNetwork, LuSearch } from 'react-icons/lu';
import { api, keys } from '../api/endpoints';
import type { AirportStatus } from '../api/types';
import { Card } from '../components/common/Card';
import { StatusBadge } from '../components/common/StatusBadge';
import { EmptyState, ErrorState, LoadingState } from '../components/common/States';
import { DetailsPanel } from '../components/details/DetailsPanel';
import { PageHeader } from '../components/layout/PageHeader';
import { flightNodes } from '../graph/model';
import type { RouteName } from '../hooks/useHashRoute';
import { useResource } from '../hooks/useResource';
import { useOperatingDate } from '../state/OperatingDate';
import type { Selection } from '../state/selection';
import { filterAirports, type AirportFilters } from '../utils/filters';
import { airportStatusTone, formatDateShort, formatNumber, titleCase } from '../utils/format';

type Navigate = (route: RouteName, params?: Record<string, string | null | undefined>) => void;

interface AirportCounts {
  departures: number;
  arrivals: number;
  dependencies: number;
}

export function AirportsPage({ navigate }: { navigate: Navigate }) {
  const { date, network } = useOperatingDate();
  const airports = useResource(keys.airports(), () => api.airports());
  const [filters, setFilters] = useState<AirportFilters>({ search: '', status: 'all' });
  const [selection, setSelection] = useState<Selection>(null);

  const counts = useMemo(() => {
    const result = new Map<string, AirportCounts>();
    const get = (iata: string) => {
      let entry = result.get(iata);
      if (!entry) result.set(iata, (entry = { departures: 0, arrivals: 0, dependencies: 0 }));
      return entry;
    };
    const flights = flightNodes(network.data);
    const flightAirports = new Map(flights.map((f) => [f.id, [f.origin_airport, f.destination_airport]]));
    flights.forEach((f) => {
      get(f.origin_airport).departures += 1;
      get(f.destination_airport).arrivals += 1;
    });
    for (const edge of network.data?.edges ?? []) {
      if (edge.category !== 'DEPENDENCY') continue;
      const touched = new Set([...(flightAirports.get(edge.source) ?? []), ...(flightAirports.get(edge.target) ?? [])]);
      touched.forEach((iata) => (get(iata).dependencies += 1));
    }
    return result;
  }, [network.data]);

  const rows = useMemo(() => filterAirports(airports.data ?? [], filters), [airports.data, filters]);

  let table;
  if (airports.loading && !airports.data) table = <LoadingState label="Loading airports…" />;
  else if (airports.error) table = <ErrorState error={airports.error} onRetry={airports.reload} />;
  else if (rows.length === 0) table = <EmptyState title="No airports match the filters" />;
  else {
    table = (
      <div className="table-wrap">
        <table className="table" data-testid="airports-table">
          <thead>
            <tr>
              <th>Airport</th>
              <th>City</th>
              <th>Status</th>
              <th className="num">Departures</th>
              <th className="num">Arrivals</th>
              <th className="num" title="Dependency edges touching flights at this airport">Dependencies</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {rows.map((airport) => {
              const c = counts.get(airport.iata_code);
              const selected = selection?.kind === 'airport' && selection.iata === airport.iata_code;
              return (
                <tr
                  key={airport.airport_id}
                  className={selected ? 'is-selected' : undefined}
                  onClick={() => setSelection({ kind: 'airport', iata: airport.iata_code })}
                >
                  <td>
                    <button type="button" className="row-link" onClick={() => setSelection({ kind: 'airport', iata: airport.iata_code })}>
                      <span className="code">{airport.iata_code}</span> {airport.airport_name}
                    </button>
                  </td>
                  <td>{airport.city}</td>
                  <td><StatusBadge tone={airportStatusTone(airport.status)}>{titleCase(airport.status)}</StatusBadge></td>
                  <td className="num">{network.data ? formatNumber(c?.departures ?? 0) : '—'}</td>
                  <td className="num">{network.data ? formatNumber(c?.arrivals ?? 0) : '—'}</td>
                  <td className="num">{network.data ? formatNumber(c?.dependencies ?? 0) : '—'}</td>
                  <td className="actions">
                    <button
                      type="button"
                      className="icon-button icon-button--plain"
                      title={`Open the ${airport.iata_code} airport graph`}
                      aria-label={`Open the ${airport.iata_code} airport graph`}
                      onClick={(event) => {
                        event.stopPropagation();
                        navigate('network', { airport: airport.iata_code });
                      }}
                    >
                      <LuNetwork aria-hidden />
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    );
  }

  return (
    <>
      <PageHeader title="Airports" subtitle={`Operational network airports · counts for ${formatDateShort(date)}`} />
      <div className="split">
        <Card
          title={`${rows.length} of ${airports.data?.length ?? 0} airports`}
          actions={
            <div className="filters">
              <label className="search">
                <LuSearch aria-hidden />
                <input
                  type="search"
                  placeholder="Search code, name or city"
                  value={filters.search}
                  onChange={(event) => setFilters({ ...filters, search: event.target.value })}
                  aria-label="Search airports"
                />
              </label>
              <select
                className="select"
                value={filters.status}
                onChange={(event) => setFilters({ ...filters, status: event.target.value as AirportStatus | 'all' })}
                aria-label="Airport status"
              >
                <option value="all">All statuses</option>
                <option value="OPERATIONAL">Operational</option>
                <option value="RESTRICTED">Restricted</option>
                <option value="CLOSED">Closed</option>
              </select>
            </div>
          }
        >
          {table}
        </Card>
        <DetailsPanel
          selection={selection}
          date={date}
          context={network.data}
          onSelect={setSelection}
          onViewAirportGraph={(iata) => navigate('network', { airport: iata })}
          onOpenOperations={(iata) => navigate('operations', { airport: iata })}
          onShowDependencies={(flightId) => navigate('network', { flight: flightId, depth: '2' })}
          onShowAffected={(flightId) => navigate('network', { flight: flightId, view: 'affected' })}
        />
      </div>
    </>
  );
}
