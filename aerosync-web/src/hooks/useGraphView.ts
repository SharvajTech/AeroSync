import { api, keys } from '../api/endpoints';
import type { GraphResponse } from '../api/types';
import { useOperatingDate } from '../state/OperatingDate';
import type { GraphScope } from '../state/selection';
import { useResource, type Resource } from './useResource';

/**
 * Loads the backend graph view for a scope: the national network (shared,
 * from the date context), an airport, a flight's dependencies or the flights
 * affected by a flight. Only the active scope triggers a request.
 */
export function useGraphView(scope: GraphScope): Resource<GraphResponse> {
  const { date, network } = useOperatingDate();
  const airportIata = scope.kind === 'airport' ? scope.iata : null;
  const flightId = scope.kind === 'flight' || scope.kind === 'affected' ? scope.flightId : null;
  const airport = useResource(
    airportIata ? keys.airportGraph(airportIata, date) : null,
    () => api.airportGraph(airportIata as string, date),
  );
  const flight = useResource(
    scope.kind === 'flight' ? keys.flightGraph(scope.flightId, scope.depth) : null,
    () => api.flightGraph(flightId as string, scope.kind === 'flight' ? scope.depth : 1),
  );
  const affectedOptions = { maxDepth: 6, horizonHours: scope.kind === 'affected' ? scope.horizonHours : 24 };
  const affected = useResource(
    scope.kind === 'affected' ? keys.affected(scope.flightId, affectedOptions) : null,
    () => api.affected(flightId as string, affectedOptions),
  );
  switch (scope.kind) {
    case 'network':
      return network;
    case 'airport':
      return airport;
    case 'flight':
      return flight;
    case 'affected':
      return affected;
  }
}
