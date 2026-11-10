import { describe, expect, it } from 'vitest';
import { AI101, AI202, AI505, BLR, BOM, DEL, HYD, PNQ, flight } from '../test/fixtures';
import { MIN_AIRPORT_DISTANCE, placeFlights, projectAirports, separate } from './layout';

const AIRPORTS = [BOM, PNQ, BLR, DEL, HYD];

describe('projectAirports', () => {
  it('keeps geographic orientation: north is up, east is right', () => {
    const p = projectAirports(AIRPORTS);
    const del = p.get('DEL')!;
    const blr = p.get('BLR')!;
    const bom = p.get('BOM')!;
    const hyd = p.get('HYD')!;
    expect(del.y).toBeLessThan(blr.y);
    expect(bom.x).toBeLessThan(hyd.x);
  });

  it('pushes nearby airports (BOM / PNQ) at least the minimum distance apart', () => {
    const p = projectAirports(AIRPORTS);
    const bom = p.get('BOM')!;
    const pnq = p.get('PNQ')!;
    expect(Math.hypot(bom.x - pnq.x, bom.y - pnq.y)).toBeGreaterThanOrEqual(MIN_AIRPORT_DISTANCE - 0.5);
  });

  it('is deterministic and independent of input order', () => {
    const a = projectAirports(AIRPORTS);
    const b = projectAirports([...AIRPORTS].reverse());
    for (const code of ['BOM', 'PNQ', 'BLR', 'DEL', 'HYD']) {
      expect(b.get(code)!.x).toBeCloseTo(a.get(code)!.x, 6);
      expect(b.get(code)!.y).toBeCloseTo(a.get(code)!.y, 6);
    }
  });

  it('returns an empty map for no airports', () => {
    expect(projectAirports([]).size).toBe(0);
  });
});

describe('separate', () => {
  it('splits identical points', () => {
    const result = separate(new Map([['A', { x: 0, y: 0 }], ['B', { x: 0, y: 0 }]]), 10);
    const a = result.get('A')!;
    const b = result.get('B')!;
    expect(Math.hypot(a.x - b.x, a.y - b.y)).toBeGreaterThanOrEqual(10);
  });
});

describe('placeFlights', () => {
  const airports = projectAirports(AIRPORTS);

  it('places a single flight next to the midpoint of its route', () => {
    const position = placeFlights([AI101], airports).get(AI101.id)!;
    const bom = airports.get('BOM')!;
    const blr = airports.get('BLR')!;
    const mid = { x: (bom.x + blr.x) / 2, y: (bom.y + blr.y) / 2 };
    const distance = Math.hypot(position.x - mid.x, position.y - mid.y);
    expect(distance).toBeGreaterThan(0);
    expect(distance).toBeLessThan(15);
  });

  it('never stacks flights of the same route on one point', () => {
    const flights = ['06:00', '08:00', '10:00'].map((t, i) => flight(`AI90${i}`, 'BOM', 'BLR', t, '12:00'));
    const positions = [...placeFlights(flights, airports).values()];
    expect(positions).toHaveLength(3);
    const keys = new Set(positions.map((p) => `${p.x.toFixed(2)},${p.y.toFixed(2)}`));
    expect(keys.size).toBe(3);
  });

  it('puts opposite directions on opposite sides of the route', () => {
    const outbound = flight('AI1', 'BOM', 'BLR', '07:00', '08:40');
    const inbound = flight('AI2', 'BLR', 'BOM', '07:00', '08:40');
    const positions = placeFlights([outbound, inbound], airports);
    const bom = airports.get('BOM')!;
    const blr = airports.get('BLR')!;
    const side = (p: { x: number; y: number }) => Math.sign((blr.x - bom.x) * (p.y - bom.y) - (blr.y - bom.y) * (p.x - bom.x));
    expect(side(positions.get(outbound.id)!)).toBe(-side(positions.get(inbound.id)!));
  });

  it('skips flights whose airports have no position', () => {
    const positions = placeFlights([AI202, AI505, flight('XX1', 'BOM', 'GOI', '07:00', '08:00')], airports);
    expect(positions.has(AI202.id)).toBe(true);
    expect(positions.has(AI505.id)).toBe(true);
    expect(positions.has('flight:fi-XX1')).toBe(false);
  });
});
