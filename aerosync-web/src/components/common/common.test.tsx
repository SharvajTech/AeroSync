import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/client';
import { GraphLegend } from './DependencyLegend';
import { MetricCards } from './MetricCards';
import { EmptyState, ErrorState, LoadingState } from './States';
import { StatusBadge } from './StatusBadge';

describe('state components', () => {
  it('renders a 404 as a "Not found" alert with the backend message and a retry button', () => {
    const html = renderToStaticMarkup(
      <ErrorState error={new ApiError('not_found', 'Airport XYZ not found', '/api/graph/airports/XYZ', 404)} onRetry={() => undefined} />,
    );
    expect(html).toContain('role="alert"');
    expect(html).toContain('Not found');
    expect(html).toContain('Airport XYZ not found');
    expect(html).toContain('Retry');
  });

  it('renders a 400 as "Invalid request" without a retry button when none is given', () => {
    const html = renderToStaticMarkup(<ErrorState error={new ApiError('bad_request', 'Invalid date', '/api/x', 400)} />);
    expect(html).toContain('Invalid request');
    expect(html).not.toContain('Retry');
  });

  it('renders empty and loading states', () => {
    expect(renderToStaticMarkup(<EmptyState title="No flights" detail="Nothing scheduled" />)).toContain('Nothing scheduled');
    expect(renderToStaticMarkup(<LoadingState label="Loading network graph…" />)).toContain('role="status"');
  });
});

describe('MetricCards', () => {
  const metrics = [
    { id: 'airports', label: 'Airports', value: 15, icon: null },
    { id: 'flights', label: 'Flights', value: 1234, icon: null },
    { id: 'dependencies', label: 'Dependency edges', value: null, icon: null },
  ];

  it('shows backend values, formatted, and a placeholder for missing ones', () => {
    const html = renderToStaticMarkup(<MetricCards metrics={metrics} />);
    expect(html).toContain('data-testid="metric-flights"');
    expect(html).toContain('1,234');
    expect(html).toContain('—');
  });

  it('shows skeletons instead of numbers while loading', () => {
    const html = renderToStaticMarkup(<MetricCards metrics={metrics} loading />);
    expect(html).toContain('aria-busy="true"');
    expect(html).toContain('skeleton');
    expect(html).not.toContain('1,234');
  });
});

describe('GraphLegend and StatusBadge', () => {
  it('lists airport, flight, route and every dependency type in the detailed legend', () => {
    const html = renderToStaticMarkup(<GraphLegend detailed />);
    for (const label of ['Airport', 'Flight', 'Route', 'Aircraft rotation', 'Passenger connection', 'Crew rotation', 'Operational constraint']) {
      expect(html).toContain(label);
    }
  });

  it('collapses dependencies into one entry in the compact legend', () => {
    const html = renderToStaticMarkup(<GraphLegend />);
    expect(html).toContain('Dependency');
    expect(html).not.toContain('Crew rotation');
  });

  it('applies the tone class', () => {
    expect(renderToStaticMarkup(<StatusBadge tone="warning">Delayed</StatusBadge>)).toBe('<span class="badge badge--warning">Delayed</span>');
  });
});
