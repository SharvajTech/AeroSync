import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import type { LoginResponse, UserProfile } from '../api/types';
import { loginResponse, profile } from '../test/fixtures';
import { AuthStore, NOTICES, noticeFor, type AuthDeps } from './authStore';
import { SESSION_STORAGE_KEY, loadSession, memoryStorage, saveSession } from './session';

const NOW = Date.parse('2026-11-10T08:00:00Z');

interface Harness {
  store: AuthStore;
  storage: ReturnType<typeof memoryStorage>;
  calls: { me: number; clearCaches: number; login: string[] };
  timers: { callback: () => void; ms: number }[];
}

function harness(overrides: Partial<AuthDeps> = {}): Harness {
  const storage = memoryStorage();
  const calls = { me: 0, clearCaches: 0, login: [] as string[] };
  const timers: { callback: () => void; ms: number }[] = [];
  const store = new AuthStore({
    storage,
    login: async (username: string): Promise<LoginResponse> => {
      calls.login.push(username);
      return loginResponse(username, ['BOM', 'DEL'], '2026-11-10T16:00:00Z');
    },
    me: async (): Promise<UserProfile> => {
      calls.me += 1;
      return profile('bom.ops', ['BOM']);
    },
    clearCaches: () => {
      calls.clearCaches += 1;
    },
    now: () => NOW,
    setTimer: (callback, ms) => {
      timers.push({ callback, ms });
      return timers.length;
    },
    clearTimer: () => undefined,
    ...overrides,
  });
  return { store, storage, calls, timers };
}

function storeSession(storage: ReturnType<typeof memoryStorage>, token = 'stored-token') {
  saveSession(storage, { token, expiresAt: '2026-11-10T12:00:00Z', user: profile('bom.ops', ['BOM']) });
}

describe('AuthStore session restoration', () => {
  it('shows the login page when nothing is stored', async () => {
    const { store, calls } = harness();
    expect(store.getState().status).toBe('restoring');
    await store.restore();
    expect(store.getState().status).toBe('signed_out');
    expect(store.getState().notice).toBeNull();
    expect(calls.me).toBe(0);
  });

  it('restores a stored session after the backend accepted the token', async () => {
    const { store, storage } = harness({
      me: async () => profile('bom.ops', ['BOM', 'DEL']),
    });
    storeSession(storage);
    const restoring = store.restore();
    // While /me is pending the token is already sent, but the app is not shown yet.
    expect(store.getState().status).toBe('restoring');
    expect(store.token()).toBe('stored-token');
    await restoring;
    expect(store.getState().status).toBe('signed_in');
    expect(store.getState().session?.user.airports.map((a) => a.iata_code)).toEqual(['BOM', 'DEL']);
    expect(loadSession(storage, NOW)?.user.airports).toHaveLength(2);
  });

  it('drops a stored token the backend rejects (expired or revoked)', async () => {
    const { store, storage, calls } = harness({
      me: async () => {
        throw new ApiError('unauthorized', 'Your session has expired', '/api/auth/me', 401, 'token_expired');
      },
    });
    storeSession(storage);
    await store.restore();
    expect(store.getState().status).toBe('signed_out');
    expect(store.getState().notice).toBe(NOTICES.expired);
    expect(store.token()).toBeNull();
    expect(storage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    expect(calls.clearCaches).toBe(1);
  });

  it('keeps the session when the backend is temporarily unreachable', async () => {
    const { store, storage } = harness({
      me: async () => {
        throw new ApiError('network', 'Cannot reach the AeroSync backend', '/api/auth/me');
      },
    });
    storeSession(storage);
    await store.restore();
    expect(store.getState().status).toBe('signed_in');
    expect(store.token()).toBe('stored-token');
  });

  it('restores only once (React StrictMode runs effects twice)', async () => {
    const { store, storage, calls } = harness();
    storeSession(storage);
    await Promise.all([store.restore(), store.restore()]);
    expect(calls.me).toBe(1);
    expect(store.getState().status).toBe('signed_in');
  });
});

describe('AuthStore login and logout', () => {
  it('signs in, stores the session and clears caches of any previous user', async () => {
    const { store, storage, calls } = harness();
    await store.restore();
    await store.login('  bom.ops ', 'secret');
    expect(calls.login).toEqual(['bom.ops']);
    expect(store.getState().status).toBe('signed_in');
    expect(store.token()).toBe('token-of-bom.ops');
    expect(loadSession(storage, NOW)?.token).toBe('token-of-bom.ops');
    expect(calls.clearCaches).toBe(1);
  });

  it('stays signed out when the login request fails', async () => {
    const { store, storage } = harness({
      login: async () => {
        throw new ApiError('unauthorized', 'Invalid username or password', '/api/auth/login', 401, 'invalid_credentials');
      },
    });
    await store.restore();
    let failure: unknown = null;
    try {
      await store.login('bom.ops', 'wrong');
    } catch (error) {
      failure = error;
    }
    expect(failure instanceof ApiError).toBe(true);
    expect(store.getState().status).toBe('signed_out');
    expect(storage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('logout removes the token from storage and memory and clears cached data', async () => {
    const { store, storage, calls } = harness();
    await store.restore();
    await store.login('bom.ops', 'secret');
    store.logout();
    expect(store.getState().status).toBe('signed_out');
    expect(store.getState().notice).toBe(NOTICES.signedOut);
    expect(store.token()).toBeNull();
    expect(storage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    expect(calls.clearCaches).toBe(2);
  });

  it('notifies subscribers on every change', async () => {
    const { store } = harness();
    const seen: string[] = [];
    const unsubscribe = store.subscribe(() => seen.push(store.getState().status));
    await store.restore();
    await store.login('bom.ops', 'secret');
    unsubscribe();
    store.logout();
    expect(seen).toEqual(['signed_out', 'signed_in']);
  });
});

describe('AuthStore 401 handling and expiry', () => {
  it('a 401 on a protected request ends the session with a matching message', async () => {
    const { store } = harness();
    await store.restore();
    await store.login('bom.ops', 'secret');
    store.handleUnauthorized(new ApiError('unauthorized', 'disabled', '/api/flights', 401, 'account_inactive'));
    expect(store.getState().status).toBe('signed_out');
    expect(store.getState().notice).toBe(NOTICES.inactive);
  });

  it('ignores 401s when nobody is signed in', async () => {
    const { store, calls } = harness();
    await store.restore();
    store.handleUnauthorized(new ApiError('unauthorized', 'x', '/api/flights', 401, null));
    expect(store.getState().notice).toBeNull();
    expect(calls.clearCaches).toBe(0);
  });

  it('signs out when the token reaches its expiry time', async () => {
    const { store, timers } = harness();
    await store.restore();
    await store.login('bom.ops', 'secret');
    expect(timers).toHaveLength(1);
    expect(timers[0].ms).toBe(8 * 60 * 60 * 1000);
    timers[0].callback();
    expect(store.getState().status).toBe('signed_out');
    expect(store.getState().notice).toBe(NOTICES.expired);
  });

  it('maps backend codes to login-page notices', () => {
    expect(noticeFor('token_expired')).toBe(NOTICES.expired);
    expect(noticeFor('account_inactive')).toBe(NOTICES.inactive);
    expect(noticeFor('token_invalid')).toBe(NOTICES.required);
    expect(noticeFor(null)).toBe(NOTICES.required);
  });
});
