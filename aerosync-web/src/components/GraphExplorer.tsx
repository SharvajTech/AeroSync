import type { RouteName } from '../hooks/useHashRoute';
import { useGraphView } from '../hooks/useGraphView';
import { useOperatingDate } from '../state/OperatingDate';
import type { GraphScope, Selection } from '../state/selection';
import { DetailsPanel } from './details/DetailsPanel';
import { GraphPanel } from './GraphPanel';

export interface GraphExplorerProps {
  scope: GraphScope;
  onScopeChange: (scope: GraphScope) => void;
  selection: Selection;
  onSelect: (selection: Selection) => void;
  navigate: (route: RouteName, params?: Record<string, string | null | undefined>) => void;
  variant?: 'dashboard' | 'full';
}

/** Network graph with the contextual details panel next to it (Dashboard and Network Graph pages). */
export function GraphExplorer({ scope, onScopeChange, selection, onSelect, navigate, variant = 'dashboard' }: GraphExplorerProps) {
  const { date } = useOperatingDate();
  const view = useGraphView(scope);

  return (
    <div className={`explorer explorer--${variant}`}>
      <GraphPanel
        scope={scope}
        onScopeChange={onScopeChange}
        selection={selection}
        onSelect={onSelect}
        view={view}
        variant={variant}
      />
      <DetailsPanel
        selection={selection}
        date={date}
        context={view.data}
        onSelect={onSelect}
        onViewAirportGraph={(iata) => {
          onScopeChange({ kind: 'airport', iata });
          onSelect({ kind: 'airport', iata });
        }}
        onOpenOperations={(iata) => navigate('operations', { airport: iata })}
        onShowDependencies={(flightId) => {
          onScopeChange({ kind: 'flight', flightId, depth: 2 });
          onSelect({ kind: 'flight', flightId });
        }}
        onShowAffected={(flightId) => {
          onScopeChange({ kind: 'affected', flightId, horizonHours: 24 });
          onSelect({ kind: 'flight', flightId });
        }}
      />
    </div>
  );
}
