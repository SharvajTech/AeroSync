import { useEffect, useMemo, useState } from 'react';
import { LuGitFork, LuSearch } from 'react-icons/lu';
import type { FlightStatus } from '../api/types';
import { Card } from '../components/common/Card';
import { StatusBadge } from '../components/common/StatusBadge';
import { EmptyState, ErrorState, LoadingState } from '../components/common/States';
import { DetailsPanel } from '../components/details/DetailsPanel';
import { PageHeader } from '../components/layout/PageHeader';
import { flightNodes } from '../graph/model';
import type { RouteName } from '../hooks/useHashRoute';
import { useOperatingDate } from '../state/OperatingDate';
import type { Selection } from '../state/selection';
import {
  EMPTY_FLIGHT_FILTERS,
  filterFlights,
  sortFlightsByDeparture,
  type DelayFilter,
  type FlightFilters,
} from '../utils/filters';
import { delayLabel, flightStatusTone, formatDateLong, isDelayed, localClock, titleCase } from '../utils/format';

type Navigate = (route: RouteName, params?: Record<string, string | null | undefined>) => void;

const PAGE_SIZE = 50;
const STATUSES: FlightStatus[] = ['SCHEDULED', 'DELAYED', 'BOARDING', 'DEPARTED', 'ARRIVED', 'CANCELLED', 'DIVERTED'];

export function FlightsPage({ params, navigate }: { params: Record<string, string>; navigate: Navigate }) {
  const { date, network } = useOperatingDate();
  const [filters, setFilters] = useState<FlightFilters>({ ...EMPTY_FLIGHT_FILTERS, airport: params.airport ?? '' });
  const [page, setPage] = useState(0);
  const [selection, setSelection] = useState<Selection>(null);

  const flights = useMemo(() => sortFlightsByDeparture(flightNodes(network.data)), [network.data]);
  const dependencyCount = useMemo(() => {
    const counts = new Map<string, { up: number; down: number }>();
    const get = (id: string) => {
      let entry = counts.get(id);
      if (!entry) counts.set(id, (entry = { up: 0, down: 0 }));
      return entry;
    };
    for (const edge of network.data?.edges ?? []) {
      if (edge.category !== 'DEPENDENCY') continue;
      get(edge.source).down += 1;
      get(edge.target).up += 1;
    }
    return counts;
  }, [network.data]);
  const airports = useMemo(() => [...new Set(flights.flatMap((f) => [f.origin_airport, f.destination_airport]))].sort(), [flights]);
  const airlines = useMemo(() => [...new Set(flights.map((f) => f.operating_airline))].sort(), [flights]);
  const rows = useMemo(() => filterFlights(flights, filters), [flights, filters]);

  useEffect(() => setPage(0), [filters, date]);
  const pageCount = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  const pageRows = rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);
  const update = (patch: Partial<FlightFilters>) => setFilters((current) => ({ ...current, ...patch }));

  let table;
  if (network.loading && !network.data) table = <LoadingState label="Loading flights…" />;
  else if (network.error) table = <ErrorState error={network.error} onRetry={network.reload} />;
  else if (flights.length === 0) table = <EmptyState title={`No flights scheduled on ${formatDateLong(date)}`} />;
  else if (rows.length === 0) table = <EmptyState title="No flights match the filters" />;
  else {
    table = (
      <>
        <div className="table-wrap">
          <table className="table" data-testid="flights-table">
            <thead>
              <tr>
                <th>Flight</th>
                <th>Route</th>
                <th>Departure</th>
                <th>Arrival</th>
                <th>Status</th>
                <th>Delay</th>
                <th>Aircraft</th>
                <th className="num" title="Upstream / downstream dependencies on this date">Deps ↑/↓</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {pageRows.map((flight) => {
                const deps = dependencyCount.get(flight.id);
                const selected = selection?.kind === 'flight' && selection.flightId === flight.flight_instance_id;
                return (
                  <tr
                    key={flight.id}
                    className={selected ? 'is-selected' : undefined}
                    onClick={() => setSelection({ kind: 'flight', flightId: flight.flight_instance_id })}
                  >
                    <td>
                      <button type="button" className="row-link" onClick={() => setSelection({ kind: 'flight', flightId: flight.flight_instance_id })}>
                        <span className="code">{flight.flight_number}</span>
                      </button>
                    </td>
                    <td>{flight.origin_airport} → {flight.destination_airport}</td>
                    <td className="mono">{localClock(flight.scheduled_departure_local)}</td>
                    <td className="mono">{localClock(flight.scheduled_arrival_local)}</td>
                    <td><StatusBadge tone={flightStatusTone(flight.status, flight.delay_minutes)}>{titleCase(flight.status)}</StatusBadge></td>
                    <td className={isDelayed(flight.status, flight.delay_minutes) ? 'text-warning' : 'text-muted'}>{delayLabel(flight.delay_minutes)}</td>
                    <td>{flight.aircraft?.registration ?? '—'} <span className="text-muted">{flight.aircraft?.aircraft_type ?? ''}</span></td>
                    <td className="num">{deps?.up ?? 0} / {deps?.down ?? 0}</td>
                    <td className="actions">
                      <button
                        type="button"
                        className="icon-button icon-button--plain"
                        title="Show dependency graph"
                        aria-label={`Show the dependency graph of ${flight.flight_number}`}
                        onClick={(event) => {
                          event.stopPropagation();
                          navigate('network', { flight: flight.flight_instance_id, depth: '2' });
                        }}
                      >
                        <LuGitFork aria-hidden />
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <span className="text-muted">
            {page * PAGE_SIZE + 1}–{Math.min(rows.length, (page + 1) * PAGE_SIZE)} of {rows.length}
          </span>
          <button type="button" className="button button--ghost button--small" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </button>
          <button type="button" className="button button--ghost button--small" disabled={page >= pageCount - 1} onClick={() => setPage(page + 1)}>
            Next
          </button>
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeader title="Flights" subtitle={`Flight instances operating on ${formatDateLong(date)}`} />
      <div className="split">
        <Card title={`${rows.length} of ${flights.length} flights`}>
          <div className="filters filters--bar" role="group" aria-label="Flight filters">
            <label className="search">
              <LuSearch aria-hidden />
              <input
                type="search"
                placeholder="Flight, registration or BOM-DEL"
                value={filters.search}
                onChange={(event) => update({ search: event.target.value })}
                aria-label="Search flights"
              />
            </label>
            <select className="select" value={filters.airport} onChange={(event) => update({ airport: event.target.value })} aria-label="Airport">
              <option value="">All airports</option>
              {airports.map((code) => <option key={code} value={code}>{code}</option>)}
            </select>
            <select
              className="select"
              value={filters.status}
              onChange={(event) => update({ status: event.target.value as FlightStatus | 'all' })}
              aria-label="Flight status"
            >
              <option value="all">All statuses</option>
              {STATUSES.map((status) => <option key={status} value={status}>{titleCase(status)}</option>)}
            </select>
            <select className="select" value={filters.delay} onChange={(event) => update({ delay: event.target.value as DelayFilter })} aria-label="Delay status">
              <option value="all">Any delay status</option>
              <option value="on_time">On time</option>
              <option value="delayed">Delayed (15 min or more)</option>
            </select>
            <select className="select" value={filters.airline} onChange={(event) => update({ airline: event.target.value })} aria-label="Airline">
              <option value="">All airlines</option>
              {airlines.map((code) => <option key={code} value={code}>{code}</option>)}
            </select>
          </div>
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
