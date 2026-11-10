import { LuGitFork, LuNetwork, LuPlane, LuShare2 } from 'react-icons/lu';
import { api, keys } from '../../api/endpoints';
import type { FlightNode, FlightReach, GraphResponse } from '../../api/types';
import { flightNodes } from '../../graph/model';
import { useResource } from '../../hooks/useResource';
import type { Selection } from '../../state/selection';
import {
  clockInZone,
  delayLabel,
  dependencyShortLabel,
  durationLabel,
  flightStatusTone,
  formatDateLong,
  isDelayed,
  localClock,
  titleCase,
} from '../../utils/format';
import { StatusBadge } from '../common/StatusBadge';
import { ErrorState, LoadingState } from '../common/States';

const OPERATIONS_TIME_ZONE = 'Asia/Kolkata';

export interface FlightDetailsProps {
  flightId: string;
  /** The flight as already known from the current graph (shown while its dependency view loads). */
  initial?: FlightNode;
  onSelect: (selection: Selection) => void;
  onShowDependencies?: (flightId: string) => void;
  onShowAffected?: (flightId: string) => void;
  onFocusAirport?: (iata: string) => void;
}

export function FlightDetails({ flightId, initial, onSelect, onShowDependencies, onShowAffected, onFocusAirport }: FlightDetailsProps) {
  const view = useResource(keys.flightGraph(flightId, 1), () => api.flightGraph(flightId, 1));
  const flight = flightNodes(view.data).find((f) => f.flight_instance_id === flightId) ?? initial;
  const upstream = view.data?.traversal?.upstream ?? [];
  const downstream = view.data?.traversal?.downstream ?? [];

  if (!flight) {
    if (view.error) return <ErrorState error={view.error} onRetry={view.reload} />;
    return <LoadingState label="Loading flight…" />;
  }

  const delayed = isDelayed(flight.status, flight.delay_minutes);
  return (
    <div className="details" data-testid="flight-details">
      <div className="details__head">
        <div className="details__icon details__icon--flight" aria-hidden><LuPlane /></div>
        <div className="details__identity">
          <div className="details__code">{flight.flight_number}</div>
          <div className="details__name">
            <button type="button" className="link-button" onClick={() => onFocusAirport?.(flight.origin_airport)} disabled={!onFocusAirport}>
              {flight.origin_airport}
            </button>
            {' → '}
            <button type="button" className="link-button" onClick={() => onFocusAirport?.(flight.destination_airport)} disabled={!onFocusAirport}>
              {flight.destination_airport}
            </button>
          </div>
        </div>
        <StatusBadge tone={flightStatusTone(flight.status, flight.delay_minutes)}>{titleCase(flight.status)}</StatusBadge>
      </div>
      <div className="details__subtitle">
        {flight.operating_airline} · Operating date {formatDateLong(flight.operating_date)}
      </div>

      <div className="details__sections">
        <section className="details__section">
          <h3 className="details__label">Schedule</h3>
          <dl className="kv">
            <dt>Departure</dt>
            <dd>
              {localClock(flight.scheduled_departure_local)} <span className="kv__muted">{flight.origin_airport} local</span>
            </dd>
            <dt>Arrival</dt>
            <dd>
              {localClock(flight.scheduled_arrival_local)} <span className="kv__muted">{flight.destination_airport} local</span>
            </dd>
            <dt>Block time</dt>
            <dd>{durationLabel(flight.scheduled_departure, flight.scheduled_arrival)}</dd>
            {(flight.estimated_departure || flight.actual_departure) && (
              <>
                <dt>{flight.actual_departure ? 'Actual dep.' : 'Estimated dep.'}</dt>
                <dd>{clockInZone(flight.actual_departure ?? flight.estimated_departure, OPERATIONS_TIME_ZONE)}</dd>
              </>
            )}
            {(flight.estimated_arrival || flight.actual_arrival) && (
              <>
                <dt>{flight.actual_arrival ? 'Actual arr.' : 'Estimated arr.'}</dt>
                <dd>{clockInZone(flight.actual_arrival ?? flight.estimated_arrival, OPERATIONS_TIME_ZONE)}</dd>
              </>
            )}
            <dt>Delay</dt>
            <dd className={delayed ? 'kv__warning' : undefined}>{delayLabel(flight.delay_minutes)}</dd>
          </dl>
        </section>

        <section className="details__section">
          <h3 className="details__label">Aircraft</h3>
          <dl className="kv">
            <dt>Registration</dt>
            <dd>{flight.aircraft?.registration ?? 'Not assigned'}</dd>
            <dt>Type</dt>
            <dd>{flight.aircraft?.aircraft_type ?? '—'}</dd>
            <dt>Gate</dt>
            <dd>{flight.assigned_gate ?? 'Not assigned'}</dd>
          </dl>
        </section>

        {view.error ? (
          <ErrorState error={view.error} onRetry={view.reload} />
        ) : view.loading && !view.data ? (
          <LoadingState label="Loading dependencies…" />
        ) : (
          <>
            <ReachList
              title="Upstream dependencies"
              empty="Does not depend on other flights."
              items={upstream}
              nodes={view.data}
              onSelect={(id) => onSelect({ kind: 'flight', flightId: id })}
            />
            <ReachList
              title="Downstream affected flights"
              empty="No flights depend on this one."
              items={downstream}
              nodes={view.data}
              onSelect={(id) => onSelect({ kind: 'flight', flightId: id })}
            />
            {view.data?.traversal?.depth_limit_reached && (
              <p className="details__muted">Direct dependencies only; use “Show affected flights” for the full chain.</p>
            )}
          </>
        )}

        <section className="details__section details__actions">
          <h3 className="details__label">Quick actions</h3>
          {onShowDependencies && (
            <button type="button" className="button button--outline button--block" onClick={() => onShowDependencies(flightId)}>
              <LuGitFork aria-hidden /> Show dependency graph
            </button>
          )}
          {onShowAffected && (
            <button type="button" className="button button--ghost button--block" onClick={() => onShowAffected(flightId)}>
              <LuShare2 aria-hidden /> Show affected flights
            </button>
          )}
          {onFocusAirport && (
            <button type="button" className="button button--ghost button--block" onClick={() => onFocusAirport(flight.origin_airport)}>
              <LuNetwork aria-hidden /> View {flight.origin_airport} airport graph
            </button>
          )}
        </section>
        {flight.data_origin === 'SYNTHETIC' && <p className="details__muted">Synthetic demonstration data.</p>}
      </div>
    </div>
  );
}

function ReachList({ title, empty, items, nodes, onSelect }: {
  title: string;
  empty: string;
  items: FlightReach[];
  nodes: GraphResponse | undefined;
  onSelect: (flightId: string) => void;
}) {
  const byId = new Map(flightNodes(nodes).map((f) => [f.id, f]));
  return (
    <section className="details__section">
      <h3 className="details__label">
        {title} <span className="details__count">{items.length}</span>
      </h3>
      {items.length === 0 ? (
        <p className="details__muted">{empty}</p>
      ) : (
        <ul className="reach-list">
          {items.map((item) => {
            const node = byId.get(item.node_id);
            return (
              <li key={item.via_edge_id}>
                <button type="button" className="reach" onClick={() => onSelect(item.flight_instance_id)}>
                  <span className="reach__flight">{item.flight_number}</span>
                  <span className="reach__route">
                    {item.origin_airport} → {item.destination_airport} · {localClock(node?.scheduled_departure_local)}
                  </span>
                  <span className={`dep-tag dep-tag--${item.via_dependency_type.toLowerCase()}`}>
                    {dependencyShortLabel(item.via_dependency_type)}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
