import { DEPENDENCY_TYPES } from '../../api/types';
import { DEPENDENCY_DASH, GRAPH_COLORS } from '../../graph/style';
import { relationshipLabel } from '../../utils/format';

function LineSample({ color, dash, arrow = true }: { color: string; dash?: number[]; arrow?: boolean }) {
  return (
    <svg width="26" height="8" viewBox="0 0 26 8" aria-hidden>
      <line x1="1" y1="4" x2={arrow ? 20 : 25} y2="4" stroke={color} strokeWidth="2" strokeDasharray={dash?.join(' ')} />
      {arrow && <path d="M19 1 L25 4 L19 7 Z" fill={color} />}
    </svg>
  );
}

/** Legend for the hybrid graph. `detailed` lists every relationship type separately. */
export function GraphLegend({ detailed = false }: { detailed?: boolean }) {
  return (
    <ul className="legend" aria-label="Graph legend">
      <li><span className="legend__dot" style={{ background: GRAPH_COLORS.airport }} /> Airport</li>
      <li><span className="legend__dot legend__dot--small" style={{ background: GRAPH_COLORS.flight }} /> Flight</li>
      <li title="Airport → Flight (departs from) and Flight → Airport (arrives at)">
        <LineSample color={GRAPH_COLORS.route} /> Route
      </li>
      {detailed ? (
        DEPENDENCY_TYPES.map((type) => (
          <li key={type}>
            <LineSample color={GRAPH_COLORS.dependency[type]} dash={DEPENDENCY_DASH[type]} /> {relationshipLabel(type)}
          </li>
        ))
      ) : (
        <li title="Aircraft rotation, passenger connection, crew rotation, operational constraint">
          <LineSample color={GRAPH_COLORS.dependency.AIRCRAFT_ROTATION} dash={DEPENDENCY_DASH.AIRCRAFT_ROTATION} /> Dependency
        </li>
      )}
    </ul>
  );
}
