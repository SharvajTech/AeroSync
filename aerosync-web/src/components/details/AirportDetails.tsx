import { useMemo, useState } from 'react';
import { LuChartColumn, LuNetwork, LuTowerControl } from 'react-icons/lu';
import { api, keys } from '../../api/endpoints';
import type { AirportStatus, FlightMovement, GraphResponse } from '../../api/types';
import { airportMovements, dependencyCounts } from '../../graph/model';
import { useResource } from '../../hooks/useResource';
import type { Selection } from '../../state/selection';
import {
  airportStatusTone,
  dependencyShortLabel,
  flightStatusTone,
  formatDateLong,
  isDelayed,
  localClock,
  pluralize,
  titleCase,
} from '../../utils/format';
import { DEPENDENCY_TYPES } from '../../api/types';
import { StatusBadge } from '../common/StatusBadge';
import { ErrorState, LoadingState } from '../common/States';
import { Tabs } from '../common/Tabs';

type Tab = 'overview' | 'departures' | 'arrivals';

const MAX_CHIPS = 8;

export interface AirportDetailsProps {
  iata: string;
  date: string | null;
  /** Graph the airport was selected in; used for connected flights and dependency counts. */
  context: GraphResponse | undefined;
  onSelect: (selection: Selection) => void;
  onViewAirportGraph?: (iata: string) => void;
  onOpenOperations?: (iata: string) => void;
}

export function AirportDetails({ iata, date, context, onSelect, onViewAirportGraph, onOpenOperations }: AirportDetailsProps) {
  const [tab, setTab] = useState<Tab>('overview');
  const operations = useResource(keys.airportOperations(iata, date), () => api.airportOperations(iata, date));
  const ops = operations.data;

  const contextFlights = useMemo(() => {
    const { departures, arrivals } = airportMovements(context, iata);
    return [...departures, ...arrivals].sort((a, b) => a.scheduled_departure.localeCompare(b.scheduled_departure));
  }, [context, iata]);
  const dependencies = useMemo(
    () => dependencyCounts(context, new Set(contextFlights.map((f) => f.id))),
    [context, contextFlights],
  );
  const dependencyText = DEPENDENCY_TYPES.filter((t) => dependencies[t] > 0)
    .map((t) => `${dependencies[t]} ${dependencyShortLabel(t).toLowerCase()}${dependencies[t] === 1 ? '' : 's'}`)
    .join(' · ');

  const airport = ops?.airport;
  const status: AirportStatus | undefined = airport?.status;

  return (
    <div className="details" data-testid="airport-details">
      <div className="details__head">
        <div className="details__icon" aria-hidden><LuTowerControl /></div>
        <div className="details__identity">
          <div className="details__code">{iata}</div>
          <div className="details__name">{airport ? airport.city : ' '}</div>
        </div>
        {status && <StatusBadge tone={airportStatusTone(status)}>{titleCase(status)}</StatusBadge>}
      </div>
      {airport && <div className="details__subtitle">{airport.airport_name}</div>}

      <Tabs<Tab>
        label="Airport details"
        active={tab}
        onChange={setTab}
        items={[
          { id: 'overview', label: 'Overview' },
          { id: 'departures', label: 'Departures', count: ops?.summary.departure_count },
          { id: 'arrivals', label: 'Arrivals', count: ops?.summary.arrival_count },
        ]}
      />

      {operations.loading && !ops && <LoadingState label="Loading airport operations…" />}
      {operations.error ? <ErrorState error={operations.error} onRetry={operations.reload} /> : null}

      {ops && tab === 'overview' && (
        <div className="details__sections">
          <section className="details__section">
            <h3 className="details__label">Flights on {formatDateLong(ops.service_date)}</h3>
            <p className="details__value">
              {pluralize(ops.summary.departure_count, 'departure')} · {pluralize(ops.summary.arrival_count, 'arrival')}
            </p>
            {ops.summary.delayed_departure_count + ops.summary.cancelled_departure_count > 0 && (
              <p className="details__value details__value--warning">
                {pluralize(ops.summary.delayed_departure_count, 'delayed departure')}
                {ops.summary.cancelled_departure_count > 0 && ` · ${ops.summary.cancelled_departure_count} cancelled`}
              </p>
            )}
          </section>

          <section className="details__section">
            <h3 className="details__label">Connected flights {context ? `(${contextFlights.length})` : ''}</h3>
            {contextFlights.length === 0 ? (
              <p className="details__muted">No flights at {iata} in the current graph view.</p>
            ) : (
              <div className="chip-list">
                {contextFlights.slice(0, MAX_CHIPS).map((flight) => (
                  <button
                    key={flight.id}
                    type="button"
                    className="chip"
                    title={`${flight.origin_airport} → ${flight.destination_airport} · ${localClock(flight.scheduled_departure_local)}`}
                    onClick={() => onSelect({ kind: 'flight', flightId: flight.flight_instance_id })}
                  >
                    {flight.flight_number}
                  </button>
                ))}
                {contextFlights.length > MAX_CHIPS && (
                  <span className="chip chip--muted">+{contextFlights.length - MAX_CHIPS} more</span>
                )}
              </div>
            )}
          </section>

          <section className="details__section">
            <h3 className="details__label">Dependencies</h3>
            <p className="details__value">{dependencyText || 'None among these flights'}</p>
          </section>

          {ops.gates.length > 0 && (
            <section className="details__section">
              <h3 className="details__label">Gates</h3>
              <p className="details__value">
                {pluralize(ops.gates.length, 'gate')} · {ops.gates.filter((g) => g.status === 'OPEN').length} open
              </p>
            </section>
          )}

          <section className="details__section details__actions">
            <h3 className="details__label">Quick actions</h3>
            {onViewAirportGraph && (
              <button type="button" className="button button--outline button--block" onClick={() => onViewAirportGraph(iata)}>
                <LuNetwork aria-hidden /> View airport graph
              </button>
            )}
            {onOpenOperations && (
              <button type="button" className="button button--ghost button--block" onClick={() => onOpenOperations(iata)}>
                <LuChartColumn aria-hidden /> Open operations board
              </button>
            )}
          </section>
        </div>
      )}

      {ops && tab !== 'overview' && (
        <MovementList
          movements={tab === 'departures' ? ops.departures : ops.arrivals}
          direction={tab}
          onSelect={(flightId) => onSelect({ kind: 'flight', flightId })}
        />
      )}
    </div>
  );
}

function MovementList({ movements, direction, onSelect }: {
  movements: FlightMovement[];
  direction: 'departures' | 'arrivals';
  onSelect: (flightId: string) => void;
}) {
  if (movements.length === 0) {
    return <p className="details__muted details__empty">No {direction} on this date.</p>;
  }
  return (
    <ul className="movement-list" aria-label={direction}>
      {movements.map((m) => (
        <li key={m.flight_instance_id}>
          <button type="button" className="movement" onClick={() => onSelect(m.flight_instance_id)}>
            <span className="movement__time">{localClock(m.scheduled_time_local)}</span>
            <span className="movement__flight">{m.flight_number}</span>
            <span className="movement__place">{direction === 'departures' ? `to ${m.counterpart_airport}` : `from ${m.counterpart_airport}`}</span>
            <StatusBadge tone={flightStatusTone(m.status, m.delay_minutes)}>
              {isDelayed(m.status, m.delay_minutes) && m.delay_minutes > 0 ? `+${m.delay_minutes}m` : titleCase(m.status)}
            </StatusBadge>
          </button>
        </li>
      ))}
    </ul>
  );
}
