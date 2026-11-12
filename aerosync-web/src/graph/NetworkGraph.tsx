import cytoscape from 'cytoscape';
import { useEffect, useRef, useState } from 'react';
import { LuMaximize, LuMinus, LuPlus } from 'react-icons/lu';
import type { FlightNode } from '../api/types';
import { localClock, titleCase } from '../utils/format';
import { hasObservedTimes, sourceLabel } from '../utils/liveData';
import { graphStylesheet } from './style';
import type { VisualGraph, VisualNode } from './model';

export interface NetworkGraphProps {
  graph: VisualGraph;
  selectedId: string | null;
  onSelect: (node: VisualNode | null) => void;
  /** Changes to this value re-fit the viewport (e.g. new scope or date); selection does not. */
  fitKey: string;
  ariaLabel: string;
  /**
   * When true (default) selecting a node dims everything outside its direct neighbourhood.
   * Traversal views turn this off for their focus flight so the whole chain stays readable.
   */
  dimOnSelect?: boolean;
}

interface Hover {
  node: VisualNode;
  x: number;
  y: number;
}

function toElements(graph: VisualGraph): cytoscape.ElementDefinition[] {
  return [
    ...graph.nodes.map<cytoscape.ElementDefinition>((node) => ({
      group: 'nodes',
      data: {
        id: node.id,
        label: node.label,
        kind: node.kind,
        tone: node.tone,
        role: node.role ?? '',
        context: node.context ? 'yes' : 'no',
        disrupted: node.disrupted ? 'yes' : 'no',
        external: node.external ? 'yes' : 'no',
      },
      position: { ...node.position },
    })),
    ...graph.edges.map<cytoscape.ElementDefinition>((edge) => ({
      group: 'edges',
      data: {
        id: edge.id,
        source: edge.source,
        target: edge.target,
        relationship: edge.relationship,
        category: edge.category,
        weight: edge.weight,
        label: edge.label ?? '',
      },
    })),
  ];
}

const FIT_PADDING = 36;

/**
 * Cytoscape.js renderer for a {@link VisualGraph}. Rendering only: data
 * loading and filtering happen in the callers and in graph/model.ts.
 */
/** Fits the viewport once the graph has elements and the container has a size; otherwise retries on resize. */
function fitIfReady(cy: cytoscape.Core, pending: { current: boolean }) {
  if (cy.elements().length === 0 || cy.width() === 0 || cy.height() === 0) return;
  cy.fit(undefined, FIT_PADDING);
  pending.current = false;
}

export function NetworkGraph({ graph, selectedId, onSelect, fitKey, ariaLabel, dimOnSelect = true }: NetworkGraphProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const cyRef = useRef<cytoscape.Core | null>(null);
  const nodesById = useRef(new Map<string, VisualNode>());
  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;
  const lastFitKey = useRef<string | null>(null);
  const fitPending = useRef(false);
  /** Set once the user zooms or pans; until then a container resize re-fits the graph. */
  const userMoved = useRef(false);
  const [hover, setHover] = useState<Hover | null>(null);

  // Create the Cytoscape instance once.
  useEffect(() => {
    if (!containerRef.current) return;
    const cy = cytoscape({
      container: containerRef.current,
      style: graphStylesheet(),
      elements: [],
      minZoom: 0.15,
      maxZoom: 5,
      boxSelectionEnabled: false,
      autoungrabify: true,
      selectionType: 'single',
    });
    cy.on('tap', 'node', (event) => {
      onSelectRef.current(nodesById.current.get(event.target.id()) ?? null);
    });
    cy.on('tap', (event) => {
      if (event.target === cy) onSelectRef.current(null);
    });
    cy.on('mouseover', 'node', (event) => {
      const node = nodesById.current.get(event.target.id());
      const position = event.target.renderedPosition();
      if (node) setHover({ node, x: position.x, y: position.y });
    });
    cy.on('mouseout', 'node', () => setHover(null));
    cy.on('viewport', () => setHover(null));
    cy.on('scrollzoom pinchzoom dragpan', () => {
      userMoved.current = true;
    });
    cyRef.current = cy;
    // A new instance starts with the default viewport, so the next graph must be fitted
    // (also covers React StrictMode, which mounts effects twice in development).
    lastFitKey.current = null;
    userMoved.current = false;
    if (import.meta.env.DEV) {
      // Development builds only: lets browser tests and the console inspect the graph.
      (containerRef.current as HTMLDivElement & { __cy?: cytoscape.Core }).__cy = cy;
    }

    const observer = new ResizeObserver(() => {
      cy.resize();
      if (!userMoved.current) fitPending.current = true;
      if (fitPending.current) fitIfReady(cy, fitPending);
    });
    observer.observe(containerRef.current);
    return () => {
      observer.disconnect();
      cy.destroy();
      cyRef.current = null;
    };
  }, []);

  // Replace the elements when the graph changes.
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    nodesById.current = new Map(graph.nodes.map((node) => [node.id, node]));
    cy.batch(() => {
      cy.elements().remove();
      cy.add(toElements(graph));
    });
    if (lastFitKey.current !== fitKey) {
      lastFitKey.current = fitKey;
      fitPending.current = true;
      userMoved.current = false;
    }
    if (fitPending.current) fitIfReady(cy, fitPending);
  }, [graph, fitKey]);

  // Highlight the selected node with its neighbours; dim everything else.
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.batch(() => {
      cy.elements().removeClass('is-dimmed is-highlighted is-selected');
      if (!selectedId) return;
      const node = cy.getElementById(selectedId);
      if (node.empty()) return;
      const neighbourhood = node.closedNeighborhood();
      node.addClass('is-selected');
      neighbourhood.edges().addClass('is-highlighted');
      if (dimOnSelect) cy.elements().not(neighbourhood).addClass('is-dimmed');
    });
  }, [selectedId, graph, dimOnSelect]);

  const zoomBy = (factor: number) => {
    const cy = cyRef.current;
    if (!cy) return;
    userMoved.current = true;
    cy.zoom({ level: cy.zoom() * factor, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
  };

  return (
    <div className="graph-canvas">
      <div ref={containerRef} className="graph-canvas__surface" role="img" aria-label={ariaLabel} data-testid="network-graph" />
      <div className="graph-controls" role="toolbar" aria-label="Graph view controls">
        <button type="button" className="icon-button" onClick={() => zoomBy(1.25)} aria-label="Zoom in" title="Zoom in">
          <LuPlus aria-hidden />
        </button>
        <button type="button" className="icon-button" onClick={() => zoomBy(0.8)} aria-label="Zoom out" title="Zoom out">
          <LuMinus aria-hidden />
        </button>
        <button
          type="button"
          className="icon-button"
          onClick={() => {
            userMoved.current = false;
            cyRef.current?.fit(undefined, FIT_PADDING);
          }}
          aria-label="Fit graph to screen"
          title="Fit to screen"
        >
          <LuMaximize aria-hidden />
        </button>
      </div>
      {hover && <HoverCard hover={hover} />}
    </div>
  );
}

function HoverCard({ hover }: { hover: Hover }) {
  const { node } = hover;
  const style = { left: hover.x + 14, top: hover.y + 10 };
  if (node.kind === 'airport' && node.ref.type === 'AIRPORT') {
    return (
      <div className="graph-tooltip" style={style} role="tooltip">
        <strong>{node.ref.iata_code}</strong> · {node.ref.airport_name}
        <div className="graph-tooltip__meta">{node.ref.city} · {titleCase(node.ref.status)}</div>
      </div>
    );
  }
  const flight = node.ref as FlightNode;
  return (
    <div className="graph-tooltip" style={style} role="tooltip">
      <strong>{flight.flight_number}</strong> · {flight.origin_airport} → {flight.destination_airport}
      <div className="graph-tooltip__meta">
        {localClock(flight.scheduled_departure_local)}–{localClock(flight.scheduled_arrival_local)} · {titleCase(flight.status)}
        {flight.delay_minutes > 0 ? ` · +${flight.delay_minutes} min` : ''}
      </div>
      <div className="graph-tooltip__meta">
        {sourceLabel(flight.provenance?.data_source, flight.data_origin)}
        {hasObservedTimes(flight.provenance) ? ' · observed times' : ''}
      </div>
      {flight.disruption && (
        <div className="graph-tooltip__alert">
          {titleCase(flight.disruption.disruption_type)}
          {flight.disruption.severity ? ` · ${titleCase(flight.disruption.severity)}` : ''}
        </div>
      )}
    </div>
  );
}
