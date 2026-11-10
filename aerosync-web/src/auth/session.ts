import type { UserProfile } from '../api/types';

/** What the browser keeps between page loads. */
export interface Session {
  token: string;
  /** ISO instant after which the backend rejects the token. */
  expiresAt: string;
  user: UserProfile;
}

/** The subset of the Web Storage API used here (sessionStorage in the browser). */
export interface KeyValueStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export const SESSION_STORAGE_KEY = 'aerosync.session';

/**
 * Token storage decision: sessionStorage. The session survives a reload of
 * this tab (session restoration) but is not shared with other tabs and is
 * gone when the tab closes. Like any script-readable storage it is exposed to
 * XSS, which the nginx Content-Security-Policy (no inline or third-party
 * script) is there to prevent; the backend still re-checks the user on every
 * request.
 */
export function browserSessionStorage(): KeyValueStorage {
  try {
    if (typeof window !== 'undefined' && window.sessionStorage) {
      const probe = '__aerosync_probe__';
      window.sessionStorage.setItem(probe, probe);
      window.sessionStorage.removeItem(probe);
      return window.sessionStorage;
    }
  } catch {
    // Storage disabled (privacy mode, sandboxed frame): fall back to memory.
  }
  return memoryStorage();
}

/** Storage that lives as long as the page (no restoration after reload). */
export function memoryStorage(): KeyValueStorage {
  const values = new Map<string, string>();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => void values.set(key, value),
    removeItem: (key) => void values.delete(key),
  };
}

export function saveSession(storage: KeyValueStorage, session: Session): void {
  try {
    storage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
  } catch {
    // Quota or disabled storage: the session then only lasts for this page.
  }
}

export function clearSession(storage: KeyValueStorage): void {
  try {
    storage.removeItem(SESSION_STORAGE_KEY);
  } catch {
    // ignore
  }
}

/** The stored session, or null when there is none, it is malformed or it has expired (both removed). */
export function loadSession(storage: KeyValueStorage, now = Date.now()): Session | null {
  let raw: string | null;
  try {
    raw = storage.getItem(SESSION_STORAGE_KEY);
  } catch {
    return null;
  }
  if (!raw) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!isSession(parsed) || Date.parse(parsed.expiresAt) <= now) {
      clearSession(storage);
      return null;
    }
    return parsed;
  } catch {
    clearSession(storage);
    return null;
  }
}

function isSession(value: unknown): value is Session {
  if (!value || typeof value !== 'object') return false;
  const v = value as Partial<Session>;
  return (
    typeof v.token === 'string' &&
    v.token.length > 0 &&
    typeof v.expiresAt === 'string' &&
    !Number.isNaN(Date.parse(v.expiresAt)) &&
    !!v.user &&
    typeof v.user.username === 'string' &&
    Array.isArray(v.user.airports)
  );
}
