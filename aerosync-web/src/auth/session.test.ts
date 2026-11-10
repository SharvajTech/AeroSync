import { describe, expect, it } from 'vitest';
import { profile } from '../test/fixtures';
import { SESSION_STORAGE_KEY, clearSession, loadSession, memoryStorage, saveSession, type Session } from './session';

const NOW = Date.parse('2026-11-10T08:00:00Z');

function session(expiresAt = '2026-11-10T16:00:00Z'): Session {
  return { token: 'abc.def.ghi', expiresAt, user: profile('bom.ops', ['BOM']) };
}

describe('session storage', () => {
  it('round-trips a session', () => {
    const storage = memoryStorage();
    saveSession(storage, session());
    expect(loadSession(storage, NOW)).toEqual(session());
  });

  it('removes an expired session instead of restoring it', () => {
    const storage = memoryStorage();
    saveSession(storage, session('2026-11-10T07:59:59Z'));
    expect(loadSession(storage, NOW)).toBeNull();
    expect(storage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('removes malformed or tampered entries', () => {
    const storage = memoryStorage();
    for (const raw of ['not json', '{}', JSON.stringify({ token: '', expiresAt: '2099-01-01', user: {} }),
      JSON.stringify({ token: 'x', expiresAt: 'soon', user: { username: 'a', airports: [] } })]) {
      storage.setItem(SESSION_STORAGE_KEY, raw);
      expect(loadSession(storage, NOW)).toBeNull();
      expect(storage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    }
  });

  it('clears the stored token', () => {
    const storage = memoryStorage();
    saveSession(storage, session());
    clearSession(storage);
    expect(storage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    expect(loadSession(storage, NOW)).toBeNull();
  });

  it('never throws when storage is unavailable', () => {
    const broken = {
      getItem: () => { throw new Error('denied'); },
      setItem: () => { throw new Error('denied'); },
      removeItem: () => { throw new Error('denied'); },
    };
    saveSession(broken, session());
    clearSession(broken);
    expect(loadSession(broken, NOW)).toBeNull();
  });
});
