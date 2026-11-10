import { useEffect, useMemo, useState } from 'react';
import { LuArrowLeft } from 'react-icons/lu';
import { DEPENDENCY_TYPES, type DependencyType, type GraphResponse } from '../api/types';
import { NetworkGraph } from '../graph/NetworkGraph';
import { airportNodes, buildFlightGraph, buildRouteSummary, flightNodes, type VisualGraph } from '../graph/model';
import type { Resource } from '../hooks/useResource';
import { useOperatingDate } from '../state/OperatingDate';
import {
  NETWORK_SCOPE,
  scopeKey,
  selectionFromNode,
  selectionNodeId,
  type GraphScope,
  type Selection,
} from '../state/selection';
import { ALL_DAY, TIME_WINDOWS, windowContaining, windowKey, windowLabel, type TimeWindow } from '../utils/filters';
import { dependencyShortLabel, formatDateLong, nowInZone, pluralize } from '../utils/format';
import { Card } from './common/Card';
import { GraphLegend } from './common/DependencyLegend';
import { EmptyState, ErrorState, LoadingState } from './common/States';

const OPERATIONS_TIME_ZONE = 'Asia/Kolkata';
const DEFAULT_WINDOW = TIME_WINDOWS[1];

export interface GraphPanelProps {
  scope: GraphScope;
  onScopeChange: (scope: GraphScope) => void;
  selection: Selection;
  onSelect: (selection: Selection) => void;
  /** Backend view for the scope (see useGraphView). */
  view: Resource<GraphResponse>;
  /** 'full' = Network Graph page (taller canvas, per-type legend, more controls). */
  variant?: 'dashboard' | 'full';
}

function defaultWindow(date: string | null): TimeWindow {
  const now = nowInZone(OPERATIONS_TIME_ZONE);
  return date === now.date ? windowContaining(now.minutes) : DEFAULT_WINDOW;
}

function scopeTitle(scope: GraphScope, data: GraphResponse | undefined, date: string | null): string {
  const focusFlight = flightNodes(data).find((f) => f.id === data?.focus_node_id);
  switch (scope.kind) {
    case 'network':
      return `National network · ${formatDateLong(date)}`;
    case 'airport':
      return `${scope.iata} airport · ${formatDateLong(date)}`;
    case 'flight':
      return `${focusFlight?.flight_number ?? 'Flight'} dependencies · depth ${scope.depth}`;
    case 'affected':
      return `Flights affected by ${focusFlight?.flight_number ?? 'flight'} · next ${scope.horizonHours} h`;
  }
}

export function GraphPanel({ scope, onScopeChange, selection, onSelect, view: data, variant = 'dashboard' }: GraphPanelProps) {
  const { date, setDate, dataWindow } = useOperatingDate();
  const [timeWindow, setTimeWindow] = useState<TimeWindow>(() => defaultWindow(date));
  const [types, setTypes] = useState<ReadonlySet<DependencyType>>(() => new Set(DEPENDENCY_TYPES));
  const [connected, setConnected] = useState(false);

  // A new operating date starts again from that date's default window.
  useEffect(() => setTimeWindow(defaultWindow(date)), [date]);

  const usesWindow = scope.kind === 'network' || scope.kind === 'airport';
  const allDay = windowKey(timeWindow) === windowKey(ALL_DAY);

  const graph: VisualGraph | null = useMemo(() => {
    if (!data.data) return null;
    if (scope.kind === 'network' && allDay) return buildRouteSummary(data.data);
    return buildFlightGraph(data.data, {
      window: usesWindow && !allDay ? timeWindow : null,
      focusAirport: scope.kind === 'airport' ? scope.iata : null,
      dependencyTypes: types,
      includeAllAirports: scope.kind === 'network',
      includeConnectedFlights: connected,
    });
  }, [data.data, scope, timeWindow, allDay, usesWindow, types, connected]);

  const toggleType = (type: DependencyType) =>
    setTypes((current) => {
      const next = new Set(current);
      if (next.has(type)) next.delete(type);
      else next.add(type);
      return next;
    });

  const totalFlights = flightNodes(data.data).length;
  const visibleFlights = graph ? graph.nodes.filter((n) => n.kind === 'flight').length : 0;
  const fitKey = `${scopeKey(scope)}|${date}|${windowKey(timeWindow)}|${connected}`;

  let body;
  if (data.loading && !data.data) {
    body = <LoadingState label="Loading network graph…" />;
  } else if (data.error) {
    body = <ErrorState error={data.error} onRetry={data.reload} />;
  } else if (!data.data || !graph) {
    body = <LoadingState label="Loading network graph…" />;
  } else if (airportNodes(data.data).length === 0) {
    body = <EmptyState title="No airports in the operational network" detail="The airports table is empty; run the database migrations." />;
  } else if (totalFlights === 0 && scope.kind !== 'network') {
    body = <EmptyState title="No flights for this view" detail={`Nothing scheduled on ${formatDateLong(date)}.`} />;
  } else if (totalFlights === 0) {
    body = (
      <EmptyState
        title={`No flights scheduled on ${formatDateLong(date)}`}
        detail={dataWindow?.first_service_date ? `Flights are available from ${formatDateLong(dataWindow.first_service_date)} to ${formatDateLong(dataWindow.last_service_date)}.` : 'The flight schedule is empty.'}
        action={dataWindow?.first_service_date ? (
          <button type="button" className="button button--secondary" onClick={() => setDate(dataWindow.first_service_date as string)}>
            Go to {formatDateLong(dataWindow.first_service_date)}
          </button>
        ) : undefined}
      />
    );
  } else if (graph.mode === 'flights' && visibleFlights === 0) {
    body = (
      <EmptyState
        title={`No flights between ${windowLabel(timeWindow)}`}
        detail="Choose another time window to see flights."
      />
    );
  } else {
    body = (
      <NetworkGraph
        graph={graph}
        selectedId={selectionNodeId(selection)}
        onSelect={(node) => onSelect(selectionFromNode(node))}
        fitKey={fitKey}
        dimOnSelect={!isScopeFocus(scope, selection)}
        ariaLabel={`${scopeTitle(scope, data.data, date)}: ${graph.nodes.length} nodes, ${graph.edges.length} edges`}
      />
    );
  }

  return (
    <Card
      className={`graph-card graph-card--${variant}`}
      title={variant === 'full' ? scopeTitle(scope, data.data, date) : 'Network Graph'}
      actions={<GraphLegend detailed={variant === 'full'} />}
      bodyClassName="graph-card__body"
    >
      <div className="graph-toolbar" role="group" aria-label="Graph filters">
        {scope.kind !== 'network' && (
          <button type="button" className="button button--ghost button--small" onClick={() => onScopeChange(NETWORK_SCOPE)}>
            <LuArrowLeft aria-hidden /> National network
          </button>
        )}
        {variant === 'dashboard' && scope.kind !== 'network' && (
          <span className="graph-toolbar__scope">{scopeTitle(scope, data.data, date)}</span>
        )}
        {usesWindow && (
          <label className="field field--inline">
            <span className="field__label">{scope.kind === 'airport' ? 'Movements' : 'Departures'}</span>
            <select
              className="select select--small"
              value={windowKey(timeWindow)}
              onChange={(event) => {
                const next = [...TIME_WINDOWS, ALL_DAY].find((w) => windowKey(w) === event.target.value);
                if (next) setTimeWindow(next);
              }}
              aria-label="Time window"
            >
              {TIME_WINDOWS.map((w) => <option key={windowKey(w)} value={windowKey(w)}>{windowLabel(w)}</option>)}
              <option value={windowKey(ALL_DAY)}>{scope.kind === 'network' ? 'All day (routes)' : 'All day'}</option>
            </select>
          </label>
        )}
        {scope.kind === 'flight' && (
          <label className="field field--inline">
            <span className="field__label">Depth</span>
            <select
              className="select select--small"
              value={scope.depth}
              onChange={(event) => onScopeChange({ ...scope, depth: Number(event.target.value) })}
              aria-label="Dependency depth"
            >
              {[1, 2, 3].map((d) => <option key={d} value={d}>{d}</option>)}
            </select>
          </label>
        )}
        {scope.kind === 'affected' && (
          <label className="field field--inline">
            <span className="field__label">Horizon</span>
            <select
              className="select select--small"
              value={scope.horizonHours}
              onChange={(event) => onScopeChange({ ...scope, horizonHours: Number(event.target.value) })}
              aria-label="Time horizon"
            >
              {[6, 12, 24].map((h) => <option key={h} value={h}>{h} h</option>)}
            </select>
          </label>
        )}
        {!(scope.kind === 'network' && allDay) && (
          <div className="chip-group" role="group" aria-label="Dependency types">
            {DEPENDENCY_TYPES.map((type) => (
              <button
                key={type}
                type="button"
                className={`chip chip--toggle${types.has(type) ? ' is-on' : ''}`}
                aria-pressed={types.has(type)}
                onClick={() => toggleType(type)}
              >
                {dependencyShortLabel(type)}
              </button>
            ))}
          </div>
        )}
        {usesWindow && !allDay && variant === 'full' && (
          <label className="checkbox">
            <input type="checkbox" checked={connected} onChange={(event) => setConnected(event.target.checked)} />
            Include connected flights
          </label>
        )}
      </div>
      <div className="graph-card__canvas">{body}</div>
      {graph && data.data && (
        <div className="graph-card__footer">
          <span data-testid="graph-summary">
            {graph.mode === 'routes'
              ? `${pluralize(graph.edges.length, 'route')} · ${pluralize(totalFlights, 'flight')} aggregated · select an airport for its flights`
              : `${pluralize(visibleFlights, 'flight')} shown of ${totalFlights}` +
                (usesWindow && !allDay ? ` (${windowLabel(timeWindow)})` : '') +
                ` · ${pluralize(graph.edges.filter((e) => e.category === 'DEPENDENCY').length, 'dependency', 'dependencies')}`}
          </span>
          {data.data.synthetic_data && <span className="badge badge--neutral" title={data.data.data_notice}>Synthetic data</span>}
        </div>
      )}
    </Card>
  );
}

/** True when the selection is the flight a dependency or affected-flights view is centred on. */
function isScopeFocus(scope: GraphScope, selection: Selection): boolean {
  return (scope.kind === 'flight' || scope.kind === 'affected') && selection?.kind === 'flight' && selection.flightId === scope.flightId;
}
