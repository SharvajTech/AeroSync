import { useEffect, useRef, useState } from 'react';
import { LuLogOut } from 'react-icons/lu';
import type { UserProfile } from '../../api/types';

export function roleLabel(role: UserProfile['role']): string {
  return role === 'OPERATIONS_CONTROLLER' ? 'Operations controller' : 'Operations viewer (read-only)';
}

export function initials(name: string): string {
  const parts = name.replace(/\(.*?\)/g, '').trim().split(/[\s._-]+/).filter(Boolean);
  const letters = parts.length >= 2 ? parts[0][0] + parts[1][0] : (parts[0] ?? '?').slice(0, 2);
  return letters.toUpperCase();
}

/** Signed-in user, their airports and role, and sign-out. */
export function ProfileMenu({ user, expiresAt, onLogout }: {
  user: UserProfile;
  expiresAt: string | null;
  onLogout: () => void;
}) {
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

  const expires = expiresAt ? new Date(expiresAt) : null;

  return (
    <div className="profile" ref={ref}>
      <button
        type="button"
        className="avatar"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={`Account: ${user.display_name}`}
        data-testid="profile-button"
        onClick={() => setOpen((v) => !v)}
      >
        {initials(user.display_name || user.username)}
      </button>
      {open && (
        <div className="profile__menu" role="dialog" aria-label="Account">
          <div className="profile__title">{user.display_name}</div>
          <div className="profile__meta">{user.username} · {roleLabel(user.role)}</div>
          <div className="profile__airports">
            {user.airports.map((a) => (
              <span key={a.iata_code} className="chip chip--static" title={a.airport_name}>
                {a.iata_code}
              </span>
            ))}
          </div>
          {expires && !Number.isNaN(expires.getTime()) && (
            <p className="profile__text">
              Session ends {expires.toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })}
            </p>
          )}
          <button type="button" className="button button--outline button--block" onClick={onLogout} data-testid="logout">
            <LuLogOut aria-hidden /> Sign out
          </button>
        </div>
      )}
    </div>
  );
}
