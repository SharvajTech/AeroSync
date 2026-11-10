import type { VisualNode } from '../graph/model';

/** What the details panel shows. */
export type Selection =
  | { kind: 'airport'; iata: string }
  | { kind: 'flight'; flightId: string }
  | null;

/** Which backend graph view feeds the graph. */
export type GraphScope =
  | { kind: 'network' }
  | { kind: 'airport'; iata: string }
  | { kind: 'flight'; flightId: string; depth: number }
  | { kind: 'affected'; flightId: string; horizonHours: number };

export const NETWORK_SCOPE: GraphScope = { kind: 'network' };

export function flightNodeId(flightId: string): string {
  return `flight:${flightId}`;
}

export function airportNodeId(iata: string): string {
  return `airport:${iata}`;
}

export function selectionNodeId(selection: Selection): string | null {
  if (!selection) return null;
  return selection.kind === 'airport' ? airportNodeId(selection.iata) : flightNodeId(selection.flightId);
}

export function selectionFromNode(node: VisualNode | null): Selection {
  if (!node) return null;
  if (node.ref.type === 'AIRPORT') return { kind: 'airport', iata: node.ref.iata_code };
  return { kind: 'flight', flightId: node.ref.flight_instance_id };
}

export function scopeKey(scope: GraphScope): string {
  switch (scope.kind) {
    case 'network':
      return 'network';
    case 'airport':
      return `airport:${scope.iata}`;
    case 'flight':
      return `flight:${scope.flightId}:${scope.depth}`;
    case 'affected':
      return `affected:${scope.flightId}:${scope.horizonHours}`;
  }
}
