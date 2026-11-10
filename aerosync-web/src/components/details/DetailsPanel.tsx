import { LuMousePointerClick, LuX } from 'react-icons/lu';
import type { FlightNode, GraphResponse } from '../../api/types';
import { flightNodes } from '../../graph/model';
import type { Selection } from '../../state/selection';
import { AirportDetails } from './AirportDetails';
import { FlightDetails } from './FlightDetails';

export interface DetailsPanelProps {
  selection: Selection;
  date: string | null;
  /** Response of the view the selection was made in (connected flights, dependency counts). */
  context: GraphResponse | undefined;
  onSelect: (selection: Selection) => void;
  onViewAirportGraph?: (iata: string) => void;
  onOpenOperations?: (iata: string) => void;
  onShowDependencies?: (flightId: string) => void;
  onShowAffected?: (flightId: string) => void;
}

/** Side panel for the selected airport or flight. */
export function DetailsPanel(props: DetailsPanelProps) {
  const { selection, date, context, onSelect } = props;
  const title = selection?.kind === 'flight' ? 'Flight Details' : 'Airport Details';

  let content;
  if (!selection) {
    content = (
      <div className="details-placeholder">
        <LuMousePointerClick className="details-placeholder__icon" aria-hidden />
        <p>Select an airport or a flight to see its operations and dependencies.</p>
      </div>
    );
  } else if (selection.kind === 'airport') {
    content = (
      <AirportDetails
        key={`${selection.iata}:${date}`}
        iata={selection.iata}
        date={date}
        context={context}
        onSelect={onSelect}
        onViewAirportGraph={props.onViewAirportGraph}
        onOpenOperations={props.onOpenOperations}
      />
    );
  } else {
    const initial: FlightNode | undefined = flightNodes(context).find((f) => f.flight_instance_id === selection.flightId);
    content = (
      <FlightDetails
        key={selection.flightId}
        flightId={selection.flightId}
        initial={initial}
        onSelect={onSelect}
        onShowDependencies={props.onShowDependencies}
        onShowAffected={props.onShowAffected}
        onFocusAirport={props.onViewAirportGraph}
      />
    );
  }

  return (
    <aside className="card details-panel" aria-label={title} data-testid="details-panel">
      <header className="card__header">
        <h2 className="card__title">{title}</h2>
        {selection && (
          <button type="button" className="icon-button icon-button--plain" onClick={() => onSelect(null)} aria-label="Clear selection">
            <LuX aria-hidden />
          </button>
        )}
      </header>
      <div className="card__body details-panel__body">{content}</div>
    </aside>
  );
}
