import type cytoscape from 'cytoscape';

/**
 * Visual encoding of the operational graph. Colours mirror the design
 * tokens in styles/tokens.css (canvas rendering cannot read CSS variables).
 */
export const GRAPH_COLORS = {
  airport: '#2563EB',
  flight: '#22C55E',
  warning: '#F59E0B',
  critical: '#DC2626',
  route: '#3B82F6',
  label: '#111827',
  labelMuted: '#4B5563',
  halo: '#2563EB',
  upstream: '#64748B',
  downstream: '#EA580C',
  dependency: {
    AIRCRAFT_ROTATION: '#EA580C',
    PASSENGER_CONNECTION: '#F97316',
    CREW_ROTATION: '#C2410C',
    OPERATIONAL_CONSTRAINT: '#FB923C',
  },
} as const;

/** Dash patterns that tell the four dependency types apart (all dashed, orange family). */
export const DEPENDENCY_DASH: Record<keyof typeof GRAPH_COLORS.dependency, number[]> = {
  AIRCRAFT_ROTATION: [7, 4],
  PASSENGER_CONNECTION: [2, 3],
  CREW_ROTATION: [8, 3, 2, 3],
  OPERATIONAL_CONSTRAINT: [3, 5],
};

const FONT = 'Inter, "Segoe UI", system-ui, -apple-system, Roboto, "Helvetica Neue", Arial, sans-serif';

type Block = { selector: string; style: Record<string, unknown> };

export function graphStylesheet(): cytoscape.StylesheetJson {
  const blocks: Block[] = [
    {
      selector: 'node',
      style: {
        label: 'data(label)',
        'font-family': FONT,
        'text-valign': 'center',
        'text-halign': 'right',
        'text-margin-x': 5,
        'text-background-color': '#FFFFFF',
        'text-background-opacity': 0.85,
        'text-background-padding': '1px',
        'min-zoomed-font-size': 7,
        'border-color': '#FFFFFF',
        'overlay-opacity': 0,
      },
    },
    {
      selector: 'node[kind = "airport"]',
      style: {
        width: 16,
        height: 16,
        'background-color': GRAPH_COLORS.airport,
        'border-width': 3,
        'font-size': 11,
        'font-weight': 700,
        color: GRAPH_COLORS.label,
        'z-index': 20,
      },
    },
    {
      selector: 'node[kind = "flight"]',
      style: {
        width: 9,
        height: 9,
        'background-color': GRAPH_COLORS.flight,
        'border-width': 2,
        'font-size': 8,
        'font-weight': 600,
        color: GRAPH_COLORS.labelMuted,
        'z-index': 10,
      },
    },
    { selector: 'node[context = "yes"]', style: { 'background-opacity': 0.45, 'font-weight': 500 } },
    { selector: 'node[tone = "warning"]', style: { 'background-color': GRAPH_COLORS.warning } },
    { selector: 'node[tone = "critical"]', style: { 'background-color': GRAPH_COLORS.critical } },
    {
      selector: 'node[role = "upstream"]',
      style: { 'border-width': 3, 'border-color': GRAPH_COLORS.upstream },
    },
    {
      selector: 'node[role = "downstream"]',
      style: { 'border-width': 3, 'border-color': GRAPH_COLORS.downstream },
    },
    {
      selector: 'node[role = "focus"], node.is-selected',
      style: {
        'underlay-color': GRAPH_COLORS.halo,
        'underlay-padding': 6,
        'underlay-opacity': 0.22,
        'underlay-shape': 'ellipse',
        'z-index': 30,
      },
    },
    {
      selector: 'edge',
      style: {
        width: 1.2,
        'curve-style': 'straight',
        'target-arrow-shape': 'triangle',
        'arrow-scale': 0.55,
        'overlay-opacity': 0,
      },
    },
    {
      selector: 'edge[category = "ROUTE"]',
      style: {
        'line-color': GRAPH_COLORS.route,
        'target-arrow-color': GRAPH_COLORS.route,
        opacity: 0.7,
      },
    },
    {
      selector: 'edge[relationship = "ROUTE_SUMMARY"]',
      style: {
        width: 'mapData(weight, 1, 30, 1.2, 6)',
        'target-arrow-shape': 'none',
        opacity: 0.45,
      },
    },
    {
      selector: 'edge[category = "DEPENDENCY"]',
      style: {
        width: 1.6,
        'curve-style': 'bezier',
        'control-point-step-size': 18,
        'line-style': 'dashed',
        opacity: 0.9,
        'z-index': 5,
      },
    },
    ...(Object.keys(GRAPH_COLORS.dependency) as (keyof typeof GRAPH_COLORS.dependency)[]).map<Block>((type) => ({
      selector: `edge[relationship = "${type}"]`,
      style: {
        'line-color': GRAPH_COLORS.dependency[type],
        'target-arrow-color': GRAPH_COLORS.dependency[type],
        'line-dash-pattern': DEPENDENCY_DASH[type],
      },
    })),
    { selector: '.is-dimmed', style: { opacity: 0.12 } },
    {
      selector: 'edge.is-highlighted',
      style: { opacity: 1, width: 2.4, 'z-index': 40 },
    },
    {
      selector: 'edge.is-highlighted[relationship = "ROUTE_SUMMARY"]',
      style: { label: 'data(label)', 'font-size': 10, color: GRAPH_COLORS.labelMuted, 'text-background-color': '#FFFFFF', 'text-background-opacity': 1 },
    },
  ];
  return blocks as unknown as cytoscape.StylesheetJson;
}
