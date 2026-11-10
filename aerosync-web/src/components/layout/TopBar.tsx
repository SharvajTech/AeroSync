import type { AirportSummary, DataWindow, UserProfile } from '../../api/types';
import { formatDateShort } from '../../utils/format';
import { AirportSelector } from './AirportSelector';
import { DatePicker } from './DatePicker';
import { ProfileMenu } from './ProfileMenu';

export function TopBar({ date, onDateChange, dataWindow, airport, airports, onAirportChange, user, expiresAt, onLogout }: {
  date: string | null;
  onDateChange: (date: string) => void;
  dataWindow: DataWindow | null;
  airport: AirportSummary;
  airports: AirportSummary[];
  onAirportChange: (iata: string) => void;
  user: UserProfile;
  expiresAt: string | null;
  onLogout: () => void;
}) {
  const hasWindow = dataWindow?.first_service_date && dataWindow.last_service_date;
  return (
    <header className="topbar">
      <div className="topbar__context">
        <AirportSelector airport={airport} airports={airports} onChange={onAirportChange} />
        {hasWindow && (
          <span className="topbar__hint" title="Service dates that have flights in the database">
            Data available {formatDateShort(dataWindow?.first_service_date)} – {formatDateShort(dataWindow?.last_service_date)}
          </span>
        )}
      </div>
      <div className="topbar__actions">
        <DatePicker value={date} onChange={onDateChange} />
        <ProfileMenu user={user} expiresAt={expiresAt} onLogout={onLogout} />
      </div>
    </header>
  );
}
