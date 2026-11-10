import { ApiError } from '../api/client';
import type { LoginResponse, UserProfile } from '../api/types';
import { clearSession, loadSession, saveSession, type KeyValueStorage, type Session } from './session';

export type AuthStatus = 'restoring' | 'signed_out' | 'signed_in';

export interface AuthState {
  status: AuthStatus;
  session: Session | null;
  /** Message for the login page (signed out, session expired, ...). */
  notice: string | null;
}

export interface AuthDeps {
  storage: KeyValueStorage;
  login: (username: string, password: string) => Promise<LoginResponse>;
  me: () => Promise<UserProfile>;
  /** Drops every cached API response (called whenever the user changes). */
  clearCaches: () => void;
  now?: () => number;
  setTimer?: (callback: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

export const NOTICES = {
  signedOut: 'You have signed out.',
  expired: 'Your session has expired. Please sign in again.',
  inactive: 'Your account is disabled or no longer exists.',
  required: 'Please sign in again.',
} as const;

/** Login-page message for a 401 code from the backend. */
export function noticeFor(code: string | null): string {
  if (code === 'token_expired') return NOTICES.expired;
  if (code === 'account_inactive') return NOTICES.inactive;
  return NOTICES.required;
}

/** Longest delay setTimeout accepts (about 24.8 days). */
const MAX_TIMER_MS = 2_147_483_647;

/**
 * Session state of the app, independent of React so it can be tested in
 * isolation. The backend is the authority: the token is only trusted after
 * GET /api/auth/me accepted it, and any 401 ends the session.
 */
export class AuthStore {
  private state: AuthState = { status: 'restoring', session: null, notice: null };
  private readonly listeners = new Set<() => void>();
  private expiryTimer: unknown = null;
  private restoreStarted = false;

  constructor(private readonly deps: AuthDeps) {}

  getState = (): AuthState => this.state;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  /** Current bearer token, used by the API client. */
  token = (): string | null => this.state.session?.token ?? null;

  /** Restores a session saved by an earlier page load (runs once). */
  async restore(): Promise<void> {
    if (this.restoreStarted) return;
    this.restoreStarted = true;
    const stored = loadSession(this.deps.storage, this.now());
    if (!stored) {
      this.set({ status: 'signed_out', session: null, notice: null });
      return;
    }
    // The token is available to the /me request while the state is "restoring".
    this.set({ status: 'restoring', session: stored, notice: null });
    try {
      const user = await this.deps.me();
      if (this.state.session?.token !== stored.token) return;
      this.establish({ ...stored, user });
    } catch (error) {
      if (this.state.session?.token !== stored.token) return;
      if (error instanceof ApiError && error.kind === 'unauthorized') {
        this.end(noticeFor(error.code));
        return;
      }
      // Backend unreachable or failing: keep the session; the next protected
      // request is checked by the backend again and a 401 ends it.
      this.establish(stored);
    }
  }

  /** Signs in. Rejects with the ApiError of the login request (wrong credentials, locked, ...). */
  async login(username: string, password: string): Promise<void> {
    const response = await this.deps.login(username.trim(), password);
    this.deps.clearCaches();
    this.establish({ token: response.token, expiresAt: response.expires_at, user: response.user });
  }

  /** Removes the token from the browser and every cached response. */
  logout(notice: string | null = NOTICES.signedOut): void {
    this.end(notice);
  }

  /** Called by the API client for a 401 on a protected request. */
  handleUnauthorized = (error: ApiError): void => {
    if (this.state.session) this.end(noticeFor(error.code));
  };

  private establish(session: Session): void {
    saveSession(this.deps.storage, session);
    this.set({ status: 'signed_in', session, notice: null });
    this.scheduleExpiry(session);
  }

  private end(notice: string | null): void {
    this.cancelExpiry();
    clearSession(this.deps.storage);
    this.deps.clearCaches();
    this.set({ status: 'signed_out', session: null, notice });
  }

  private scheduleExpiry(session: Session): void {
    this.cancelExpiry();
    const remaining = Date.parse(session.expiresAt) - this.now();
    if (remaining <= 0) {
      this.end(NOTICES.expired);
      return;
    }
    const setTimer = this.deps.setTimer ?? ((callback, ms) => setTimeout(callback, ms));
    this.expiryTimer = setTimer(() => {
      if (this.state.session?.token === session.token) this.end(NOTICES.expired);
    }, Math.min(remaining, MAX_TIMER_MS));
  }

  private cancelExpiry(): void {
    if (this.expiryTimer === null) return;
    const clearTimer = this.deps.clearTimer ?? ((handle) => clearTimeout(handle as ReturnType<typeof setTimeout>));
    clearTimer(this.expiryTimer);
    this.expiryTimer = null;
  }

  private now(): number {
    return this.deps.now ? this.deps.now() : Date.now();
  }

  private set(next: AuthState): void {
    this.state = next;
    this.listeners.forEach((listener) => listener());
  }
}
