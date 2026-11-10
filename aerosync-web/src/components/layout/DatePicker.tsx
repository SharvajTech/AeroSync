import { useRef } from 'react';
import { LuCalendar, LuChevronDown, LuChevronLeft, LuChevronRight } from 'react-icons/lu';
import { formatDateLong, shiftDate } from '../../utils/format';

/** Operating-date selector: formatted label over a native date input, plus previous/next day. */
export function DatePicker({ value, onChange }: { value: string | null; onChange: (date: string) => void }) {
  const inputRef = useRef<HTMLInputElement | null>(null);

  const openPicker = () => {
    const input = inputRef.current;
    if (!input) return;
    if (typeof input.showPicker === 'function') {
      try {
        input.showPicker();
        return;
      } catch {
        // showPicker can throw outside a user gesture; fall back to focus
      }
    }
    input.focus();
  };

  return (
    <div className="date-picker">
      <button
        type="button"
        className="icon-button icon-button--plain"
        onClick={() => value && onChange(shiftDate(value, -1))}
        disabled={!value}
        aria-label="Previous day"
        title="Previous day"
      >
        <LuChevronLeft aria-hidden />
      </button>
      <button type="button" className="date-picker__button" onClick={openPicker} disabled={!value} aria-label="Operating date">
        <LuCalendar aria-hidden className="date-picker__icon" />
        <span data-testid="operating-date">{value ? formatDateLong(value) : 'Loading…'}</span>
        <LuChevronDown aria-hidden className="date-picker__chevron" />
      </button>
      <input
        ref={inputRef}
        type="date"
        className="visually-hidden"
        tabIndex={-1}
        aria-hidden
        value={value ?? ''}
        onChange={(event) => event.target.value && onChange(event.target.value)}
      />
      <button
        type="button"
        className="icon-button icon-button--plain"
        onClick={() => value && onChange(shiftDate(value, 1))}
        disabled={!value}
        aria-label="Next day"
        title="Next day"
      >
        <LuChevronRight aria-hidden />
      </button>
    </div>
  );
}
