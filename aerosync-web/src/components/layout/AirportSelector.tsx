import { LuTowerControl } from 'react-icons/lu';
import type { AirportSummary } from '../../api/types';

/**
 * The airport being operated. A dropdown when the user has several airports,
 * a plain label when they have one; never offers airports outside their
 * assignment.
 */
export function AirportSelector({ airport, airports, onChange }: {
  airport: AirportSummary;
  airports: AirportSummary[];
  onChange: (iata: string) => void;
}) {
  if (airports.length <= 1) {
    return (
      <div className="airport-selector" data-testid="airport-selector">
        <LuTowerControl aria-hidden />
        <span className="airport-selector__code">{airport.iata_code}</span>
        <span className="airport-selector__name">{airport.city}</span>
      </div>
    );
  }
  return (
    <label className="airport-selector airport-selector--select" data-testid="airport-selector">
      <LuTowerControl aria-hidden />
      <span className="visually-hidden">Airport</span>
      <select
        aria-label="Operating airport"
        value={airport.iata_code}
        onChange={(event) => onChange(event.target.value)}
      >
        {airports.map((a) => (
          <option key={a.iata_code} value={a.iata_code}>
            {a.iata_code} · {a.city}
          </option>
        ))}
      </select>
    </label>
  );
}
