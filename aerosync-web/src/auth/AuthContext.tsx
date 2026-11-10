import { createContext, useContext, useEffect, useSyncExternalStore, type ReactNode } from 'react';
import type { AuthState, AuthStore } from './authStore';

const AuthContext = createContext<AuthStore | null>(null);

/** Provides the session store and restores a saved session once on mount. */
export function AuthProvider({ store, children }: { store: AuthStore; children: ReactNode }) {
  useEffect(() => {
    void store.restore();
  }, [store]);
  return <AuthContext.Provider value={store}>{children}</AuthContext.Provider>;
}

export function useAuthStore(): AuthStore {
  const store = useContext(AuthContext);
  if (!store) throw new Error('useAuthStore must be used inside <AuthProvider>');
  return store;
}

export function useAuth(): AuthState & { store: AuthStore } {
  const store = useAuthStore();
  const state = useSyncExternalStore(store.subscribe, store.getState, store.getState);
  return { ...state, store };
}

/** Which top-level screen to render for an auth status (all app routes are protected). */
export type Screen = 'splash' | 'login' | 'app';

export function resolveScreen(status: AuthState['status']): Screen {
  if (status === 'signed_in') return 'app';
  if (status === 'restoring') return 'splash';
  return 'login';
}
