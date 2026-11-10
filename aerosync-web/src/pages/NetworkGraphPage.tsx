import { useEffect, useMemo, useState } from 'react';
import { GraphExplorer } from '../components/GraphExplorer';
import { PageHeader } from '../components/layout/PageHeader';
import type { RouteName } from '../hooks/useHashRoute';
import { NETWORK_SCOPE, type GraphScope, type Selection } from '../state/selection';

type Navigate = (route: RouteName, params?: Record<string, string | null | undefined>) => void;

/** Reads the graph scope and selection from the URL (#/network?airport=BLR, ?flight=<id>&view=affected). */
export function scopeFromParams(params: Record<string, string>): { scope: GraphScope; selection: Selection } {
  if (params.flight) {
    const flightId = params.flight;
    const scope: GraphScope = params.view === 'affected'
      ? { kind: 'affected', flightId, horizonHours: Number(params.horizon) || 24 }
      : { kind: 'flight', flightId, depth: Math.min(3, Math.max(1, Number(params.depth) || 2)) };
    return { scope, selection: { kind: 'flight', flightId } };
  }
  if (params.airport) {
    const iata = params.airport.toUpperCase();
    return { scope: { kind: 'airport', iata }, selection: { kind: 'airport', iata } };
  }
  const selection: Selection = params.select ? { kind: 'airport', iata: params.select.toUpperCase() } : null;
  return { scope: NETWORK_SCOPE, selection };
}

export function paramsFromState(scope: GraphScope, selection: Selection): Record<string, string | null> {
  switch (scope.kind) {
    case 'airport':
      return { airport: scope.iata };
    case 'flight':
      return { flight: scope.flightId, depth: String(scope.depth) };
    case 'affected':
      return { flight: scope.flightId, view: 'affected', horizon: String(scope.horizonHours) };
    case 'network':
      return { select: selection?.kind === 'airport' ? selection.iata : null };
  }
}

/** Full-size graph explorer; the scope lives in the URL so views can be linked and reloaded. */
export function NetworkGraphPage({ params, navigate }: { params: Record<string, string>; navigate: Navigate }) {
  const initial = useMemo(() => scopeFromParams(params), [params]);
  const [selection, setSelection] = useState<Selection>(initial.selection);

  // A new scope (URL change) starts with that scope's own focus selected.
  useEffect(() => setSelection(initial.selection), [initial]);

  return (
    <>
      <PageHeader
        title="Network Graph"
        subtitle="Hybrid airport + flight graph: routes (departs from / arrives at) and flight-to-flight dependencies"
      />
      <GraphExplorer
        variant="full"
        scope={initial.scope}
        onScopeChange={(next) => navigate('network', paramsFromState(next, null))}
        selection={selection}
        onSelect={setSelection}
        navigate={navigate}
      />
    </>
  );
}
