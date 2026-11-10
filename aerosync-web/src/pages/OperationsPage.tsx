import { useMemo, useState } from 'react';
import { LuClock, LuPlaneLanding, LuPlaneTakeoff, LuTriangleAlert } from 'react-icons/lu';
import { api, keys } from '../api/endpoints';
import type { FlightMovement } from '../api/types';
import { Card } from '../components/common/Card';
import { MetricCards, type Metric } from '../components/common/MetricCards';
import { StatusBadge } from '../components/common/StatusBadge';
import { EmptyState, ErrorState, LoadingState } from '../components/common/States';
import { Tabs } from '../components/common/Tabs';
import { DetailsPanel } from '../components/details/DetailsPanel';
import { PageHeader } from '../components/layout/PageHeader';
import { flightNodes } from '../graph/model';
import type { RouteName } from '../hooks/useHashRoute';
import { useResource } from '../hooks/useResource';
import { useOperatingDate } from '../state/OperatingDate';
import type { Selection } from '../state/selection';
import { delayLabel, flightStatusTone, formatDateLong, isDelayed, localClock, titleCase } from '../utils/format';

type Navigate = (route: RouteName, params?: Record<string, string | null | undefined>) => void;
type Board = 'departures' | 'arrivals';

export function OperationsPage({ params, navigate }: { params: Record<string, string>; navigate: Navigate }) {
  const { date, network } = useOperatingDate();
  const airports = useResource(keys.airports(), () => api.airports());
  const [board, setBoard] = useState<Board>('departures');
  const [selection, setSelection] = useState<Selection>(null);

  // Default to the busiest airport of the day when none is in the URL.
  const busiest = useMemo(() => {
    const counts = new Map<string, number>();
    flightNodes(network.data).forEach((f) => counts.set(f.origin_airport, (counts.get(f.origin_airport) ?? 0) + 1));
    return [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))[0]?.[0];
  }, [network.data]);
  const iata = (params.airport ?? busiest ?? airports.data?.[0]?.iata_code ?? null)?.toUpperCase() ?? null;

  const operations = useResource(iata ? keys.airportOperations(iata, date) : null, () => api.airportOperations(iata as string, date));
  const ops = operations.data;

  const metrics: Metric[] = [
    { id: 'departures', label: 'Departures', value: ops?.summary.departure_count, icon: <LuPlaneTakeoff /> },
    { id: 'arrivals', label: 'Arrivals', value: ops?.summary.arrival_count, icon: <LuPlaneLanding /> },
    { id: 'delayed', label: 'Delayed departures', value: ops?.summary.delayed_departure_count, icon: <LuTriangleAlert /> },
    {
      id: 'avg-delay',
      label: 'Avg. departure delay (min)',
      value: ops ? Math.round(ops.summary.average_departure_delay_minutes) : undefined,
      icon: <LuClock />,
    },
  ];

  let content;
  if (!iata || (operations.loading && !ops)) content = <LoadingState label="Loading operations…" />;
  else if (operations.error) content = <ErrorState error={operations.error} onRetry={operations.reload} />;
  else if (ops) {
    const rows = board === 'departures' ? ops.departures : ops.arrivals;
    content = rows.length === 0 ? (
      <EmptyState title={`No ${board} at ${iata} on ${formatDateLong(date)}`} />
    ) : (
      <MovementTable
        rows={rows}
        board={board}
        selectedId={selection?.kind === 'flight' ? selection.flightId : null}
        onSelect={(flightId) => setSelection({ kind: 'flight', flightId })}
      />
    );
  }

  return (
    <>
      <PageHeader
        title="Operations"
        subtitle={ops ? `${ops.airport.airport_name} · ${formatDateLong(ops.service_date)} (${ops.timezone})` : 'Departures and arrivals by airport'}
        actions={
          <label className="field field--inline">
            <span className="field__label">Airport</span>
            <select
              className="select"
              value={iata ?? ''}
              onChange={(event) => navigate('operations', { airport: event.target.value })}
              aria-label="Airport"
              disabled={!airports.data}
            >
              {(airports.data ?? []).map((a) => (
                <option key={a.iata_code} value={a.iata_code}>{a.iata_code} · {a.city}</option>
              ))}
            </select>
          </label>
        }
      />
      <MetricCards metrics={metrics} loading={operations.loading && !ops} />
      <div className="split">
        <Card
          title={
            <Tabs<Board>
              label="Movement board"
              active={board}
              onChange={setBoard}
              items={[
                { id: 'departures', label: 'Departures', count: ops?.summary.departure_count },
                { id: 'arrivals', label: 'Arrivals', count: ops?.summary.arrival_count },
              ]}
            />
          }
          actions={ops?.synthetic_data ? <span className="badge badge--neutral" title={ops.data_notice}>Synthetic data</span> : undefined}
        >
          {content}
          {ops && ops.gates.length > 0 && (
            <div className="gates">
              <h3 className="details__label">Gates</h3>
              <div className="chip-list">
                {ops.gates.map((gate) => (
                  <span key={gate.gate_id} className={`chip${gate.status === 'OPEN' ? '' : ' chip--muted'}`} title={gate.supported_aircraft_types.join(', ')}>
                    {gate.terminal ? `${gate.terminal} · ` : ''}{gate.gate_number}
                  </span>
                ))}
              </div>
            </div>
          )}
        </Card>
        <DetailsPanel
          selection={selection}
          date={date}
          context={undefined}
          onSelect={setSelection}
          onViewAirportGraph={(code) => navigate('network', { airport: code })}
          onShowDependencies={(flightId) => navigate('network', { flight: flightId, depth: '2' })}
          onShowAffected={(flightId) => navigate('network', { flight: flightId, view: 'affected' })}
        />
      </div>
    </>
  );
}

function MovementTable({ rows, board, selectedId, onSelect }: {
  rows: FlightMovement[];
  board: Board;
  selectedId: string | null;
  onSelect: (flightId: string) => void;
}) {
  return (
    <div className="table-wrap">
      <table className="table" data-testid={`${board}-table`}>
        <thead>
          <tr>
            <th>Time</th>
            <th>Flight</th>
            <th>{board === 'departures' ? 'To' : 'From'}</th>
            <th>Aircraft</th>
            <th>Gate</th>
            <th>Status</th>
            <th>Delay</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((m) => (
            <tr
              key={m.flight_instance_id}
              className={selectedId === m.flight_instance_id ? 'is-selected' : undefined}
              onClick={() => onSelect(m.flight_instance_id)}
            >
              <td className="mono">{localClock(m.scheduled_time_local)}</td>
              <td>
                <button type="button" className="row-link" onClick={() => onSelect(m.flight_instance_id)}>
                  <span className="code">{m.flight_number}</span>
                </button>
              </td>
              <td>{m.counterpart_airport}</td>
              <td>{m.aircraft_registration ?? '—'} <span className="text-muted">{m.aircraft_type ?? ''}</span></td>
              <td>{m.assigned_gate ?? '—'}</td>
              <td><StatusBadge tone={flightStatusTone(m.status, m.delay_minutes)}>{titleCase(m.status)}</StatusBadge></td>
              <td className={isDelayed(m.status, m.delay_minutes) ? 'text-warning' : 'text-muted'}>{delayLabel(m.delay_minutes)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
