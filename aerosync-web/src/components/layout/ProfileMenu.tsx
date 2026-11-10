import { useEffect, useRef, useState } from 'react';
import { LuUser } from 'react-icons/lu';

/**
 * Session indicator. The operational network views use the backend's public,
 * read-only endpoints, so there is no signed-in user to show.
 */
export function ProfileMenu() {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent) => {
      if (ref.current && !ref.current.contains(event.target as Node)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => event.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  return (
    <div className="profile" ref={ref}>
      <button
        type="button"
        className="avatar"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label="Session details"
        onClick={() => setOpen((v) => !v)}
      >
        <LuUser aria-hidden />
      </button>
      {open && (
        <div className="profile__menu" role="dialog" aria-label="Session">
          <div className="profile__title">Operations viewer</div>
          <div className="profile__meta">Read-only session</div>
          <p className="profile__text">
            The network, airport and flight views use the backend&apos;s public read-only endpoints. No sign-in is required and
            no credentials are stored in the browser.
          </p>
        </div>
      )}
    </div>
  );
}
