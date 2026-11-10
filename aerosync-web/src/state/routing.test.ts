import { describe, expect, it } from 'vitest';
import { formatHash, parseHash } from '../hooks/useHashRoute';
import { paramsFromState, scopeFromParams } from '../pages/NetworkGraphPage';
import { selectionFromNode, selectionNodeId, scopeKey } from './selection';
import { AI101, BOM } from '../test/fixtures';

describe('hash routes', () => {
  it('parses route names and query parameters', () => {
    expect(parseHash('#/network?airport=BLR')).toEqual({ name: 'network', params: { airport: 'BLR' } });
    expect(parseHash('#/flights')).toEqual({ name: 'flights', params: {} });
  });

  it('falls back to the dashboard for unknown or empty routes', () => {
    expect(parseHash('#/nope').name).toBe('dashboard');
    expect(parseHash('').name).toBe('dashboard');
  });

  it('formats routes and drops empty parameters', () => {
    expect(formatHash('network', { flight: 'abc', depth: '2', select: null })).toBe('#/network?flight=abc&depth=2');
    expect(formatHash('dashboard')).toBe('#/dashboard');
  });
});

describe('network page deep links', () => {
  it('opens an airport view', () => {
    expect(scopeFromParams({ airport: 'blr' })).toEqual({
      scope: { kind: 'airport', iata: 'BLR' },
      selection: { kind: 'airport', iata: 'BLR' },
    });
  });

  it('opens a dependency view with a bounded depth', () => {
    expect(scopeFromParams({ flight: 'x', depth: '9' }).scope).toEqual({ kind: 'flight', flightId: 'x', depth: 3 });
    expect(scopeFromParams({ flight: 'x' }).scope).toEqual({ kind: 'flight', flightId: 'x', depth: 2 });
  });

  it('opens an affected-flights view', () => {
    expect(scopeFromParams({ flight: 'x', view: 'affected', horizon: '12' }).scope)
      .toEqual({ kind: 'affected', flightId: 'x', horizonHours: 12 });
  });

  it('round-trips scopes through URL parameters', () => {
    const scopes = [
      { kind: 'airport', iata: 'BOM' },
      { kind: 'flight', flightId: 'x', depth: 1 },
      { kind: 'affected', flightId: 'x', horizonHours: 6 },
    ] as const;
    for (const scope of scopes) {
      const params = Object.fromEntries(Object.entries(paramsFromState(scope, null)).filter(([, v]) => v)) as Record<string, string>;
      expect(scopeFromParams(params).scope).toEqual(scope);
    }
  });
});

describe('selection helpers', () => {
  it('maps selections to graph node ids and back', () => {
    expect(selectionNodeId({ kind: 'airport', iata: 'BOM' })).toBe('airport:BOM');
    expect(selectionNodeId({ kind: 'flight', flightId: AI101.flight_instance_id })).toBe(AI101.id);
    expect(selectionNodeId(null)).toBeNull();
    const node = { id: AI101.id, kind: 'flight' as const, label: 'AI101', position: { x: 0, y: 0 }, tone: 'normal' as const, ref: AI101 };
    expect(selectionFromNode(node)).toEqual({ kind: 'flight', flightId: AI101.flight_instance_id });
    const airportNode = { id: BOM.id, kind: 'airport' as const, label: 'BOM', position: { x: 0, y: 0 }, tone: 'normal' as const, ref: BOM };
    expect(selectionFromNode(airportNode)).toEqual({ kind: 'airport', iata: 'BOM' });
  });

  it('gives each scope a distinct cache/fit key', () => {
    const keys = new Set([
      scopeKey({ kind: 'network' }),
      scopeKey({ kind: 'airport', iata: 'BOM' }),
      scopeKey({ kind: 'flight', flightId: 'x', depth: 1 }),
      scopeKey({ kind: 'flight', flightId: 'x', depth: 2 }),
      scopeKey({ kind: 'affected', flightId: 'x', horizonHours: 24 }),
    ]);
    expect(keys.size).toBe(5);
  });
});
