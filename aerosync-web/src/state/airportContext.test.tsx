import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { memoryStorage } from '../auth/session';
import { AirportSelector } from '../components/layout/AirportSelector';
import { airportSummary, profile } from '../test/fixtures';
import { AirportProvider, airportStorageKey, chooseAirport, useAirport } from './AirportContext';

const BOM = airportSummary('BOM', 'Mumbai');
const DEL = airportSummary('DEL', 'Delhi');

function Probe() {
  const { airport, airports, isAuthorized } = useAirport();
  return (
    <span>
      {airport.iata_code}|{airports.map((a) => a.iata_code).join(',')}|{String(isAuthorized('del'))}|{String(isAuthorized('BLR'))}
    </span>
  );
}

describe('airport selection', () => {
  it('only ever selects one of the user\'s airports', () => {
    expect(chooseAirport([BOM, DEL], 'del')?.iata_code).toBe('DEL');
    expect(chooseAirport([BOM, DEL], 'BLR')?.iata_code).toBe('BOM');
    expect(chooseAirport([BOM, DEL], null)?.iata_code).toBe('BOM');
    expect(chooseAirport([], 'BOM')).toBeNull();
  });

  it('restores the remembered airport of this user and ignores unauthorized ones', () => {
    const user = profile('multi.ops', ['BOM', 'DEL']);
    const storage = memoryStorage();
    storage.setItem(airportStorageKey(user.user_id), 'DEL');
    expect(renderToStaticMarkup(<AirportProvider user={user} storage={storage}><Probe /></AirportProvider>))
      .toContain('DEL|BOM,DEL|true|false');

    storage.setItem(airportStorageKey(user.user_id), 'BLR');
    expect(renderToStaticMarkup(<AirportProvider user={user} storage={storage}><Probe /></AirportProvider>))
      .toContain('BOM|BOM,DEL|true|false');
  });

  it('shows a dropdown only when the user has several airports', () => {
    const single = renderToStaticMarkup(<AirportSelector airport={BOM} airports={[BOM]} onChange={() => undefined} />);
    expect(single).not.toContain('<select');
    expect(single).toContain('BOM');
    const multiple = renderToStaticMarkup(<AirportSelector airport={BOM} airports={[BOM, DEL]} onChange={() => undefined} />);
    expect(multiple).toContain('<select');
    expect(multiple).toContain('DEL · Delhi');
    expect(multiple).not.toContain('BLR');
  });
});
