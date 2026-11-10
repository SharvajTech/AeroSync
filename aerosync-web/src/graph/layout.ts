import type { AirportNode, FlightNode } from '../api/types';

export interface Point {
  x: number;
  y: number;
}

/** Model pixels per degree of latitude (Cytoscape then fits the graph to the viewport). */
export const DEGREE_SCALE = 24;
/** Widens the map so it uses the landscape graph panel; orientation is preserved. */
export const HORIZONTAL_STRETCH = 1.9;
/** Airports closer than this (model px) are pushed apart so nodes and labels stay readable. */
export const MIN_AIRPORT_DISTANCE = 46;
/** Distance between a flight and its route line (opposite directions sit on opposite sides). */
export const FLIGHT_OFFSET = 7;

/**
 * Schematic geographic layout of airports, keyed by IATA code: an
 * equirectangular projection around the mean latitude (west is left, north
 * is up), widened for a landscape panel, with nearby airports nudged apart
 * (e.g. BOM / PNQ) so they never overlap.
 */
export function projectAirports(airports: readonly Pick<AirportNode, 'iata_code' | 'latitude' | 'longitude'>[]): Map<string, Point> {
  const positions = new Map<string, Point>();
  if (airports.length === 0) return positions;
  const meanLatitude = airports.reduce((sum, a) => sum + a.latitude, 0) / airports.length;
  const xScale = Math.cos((meanLatitude * Math.PI) / 180) * DEGREE_SCALE * HORIZONTAL_STRETCH;
  for (const airport of airports) {
    positions.set(airport.iata_code, { x: airport.longitude * xScale, y: -airport.latitude * DEGREE_SCALE });
  }
  return separate(positions, MIN_AIRPORT_DISTANCE);
}

/** Deterministically pushes apart points closer than `minDistance` (a few relaxation passes). */
export function separate(points: Map<string, Point>, minDistance: number, passes = 80): Map<string, Point> {
  const keys = [...points.keys()].sort();
  const result = new Map(keys.map((k) => [k, { ...(points.get(k) as Point) }]));
  for (let pass = 0; pass < passes; pass++) {
    let moved = false;
    for (let i = 0; i < keys.length; i++) {
      for (let j = i + 1; j < keys.length; j++) {
        const a = result.get(keys[i]) as Point;
        const b = result.get(keys[j]) as Point;
        let dx = b.x - a.x;
        let dy = b.y - a.y;
        let distance = Math.hypot(dx, dy);
        if (distance >= minDistance) continue;
        if (distance === 0) {
          dx = 1;
          dy = 0;
          distance = 1;
        }
        const push = (minDistance - distance) / 2 + 0.01;
        a.x -= (dx / distance) * push;
        a.y -= (dy / distance) * push;
        b.x += (dx / distance) * push;
        b.y += (dy / distance) * push;
        moved = true;
      }
    }
    if (!moved) break;
  }
  return result;
}

/**
 * Places each flight on the straight line between its origin and destination.
 * Flights of the same direction are spread along the middle of the line in
 * departure order and pushed to one side of it; the opposite direction uses
 * the other side, so out- and inbound flights never overlap. Keyed by node id.
 */
export function placeFlights(
  flights: readonly Pick<FlightNode, 'id' | 'origin_airport' | 'destination_airport' | 'scheduled_departure'>[],
  airportPositions: ReadonlyMap<string, Point>,
): Map<string, Point> {
  const byRoute = new Map<string, typeof flights[number][]>();
  for (const flight of flights) {
    const key = `${flight.origin_airport}>${flight.destination_airport}`;
    const group = byRoute.get(key);
    if (group) group.push(flight);
    else byRoute.set(key, [flight]);
  }

  const positions = new Map<string, Point>();
  for (const group of byRoute.values()) {
    group.sort((a, b) => a.scheduled_departure.localeCompare(b.scheduled_departure) || a.id.localeCompare(b.id));
    const { origin_airport: origin, destination_airport: destination } = group[0];
    const from = airportPositions.get(origin);
    const to = airportPositions.get(destination);
    if (!from || !to) continue;
    const dx = to.x - from.x;
    const dy = to.y - from.y;
    const length = Math.hypot(dx, dy) || 1;
    // Unit normal of the travel direction: the reverse direction gets the opposite normal,
    // so outbound and inbound flights of a city pair sit on opposite sides of the line.
    const nx = -dy / length;
    const ny = dx / length;
    const n = group.length;
    group.forEach((flight, index) => {
      // Spread over the middle 50% of the route (single flight: midpoint).
      const t = n === 1 ? 0.5 : 0.25 + (0.5 * index) / (n - 1);
      const offset = FLIGHT_OFFSET * (1 + (n > 4 ? (index % 2) * 0.8 : 0));
      positions.set(flight.id, {
        x: from.x + dx * t + nx * offset,
        y: from.y + dy * t + ny * offset,
      });
    });
  }
  return positions;
}
