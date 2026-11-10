import type { DataWindow } from '../../api/types';
import { formatDateShort } from '../../utils/format';
import { DatePicker } from './DatePicker';
import { ProfileMenu } from './ProfileMenu';

export function TopBar({ date, onDateChange, dataWindow }: {
  date: string | null;
  onDateChange: (date: string) => void;
  dataWindow: DataWindow | null;
}) {
  const hasWindow = dataWindow?.first_service_date && dataWindow.last_service_date;
  return (
    <header className="topbar">
      <div className="topbar__context">
        {hasWindow && (
          <span className="topbar__hint" title="Service dates that have flights in the database">
            Data available {formatDateShort(dataWindow?.first_service_date)} – {formatDateShort(dataWindow?.last_service_date)}
          </span>
        )}
      </div>
      <div className="topbar__actions">
        <DatePicker value={date} onChange={onDateChange} />
        <ProfileMenu />
      </div>
    </header>
  );
}
