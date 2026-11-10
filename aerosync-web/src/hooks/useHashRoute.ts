import { useCallback, useEffect, useState } from 'react';

export type RouteName = 'dashboard' | 'network' | 'airports' | 'flights' | 'operations' | 'settings';

export const ROUTES: readonly RouteName[] = ['dashboard', 'network', 'airports', 'flights', 'operations', 'settings'];

export interface Route {
  name: RouteName;
  params: Record<string, string>;
}

/** "#/network?airport=BLR" -> { name: 'network', params: { airport: 'BLR' } }; unknown routes -> dashboard. */
export function parseHash(hash: string): Route {
  const raw = hash.replace(/^#\/?/, '');
  const [path, query = ''] = raw.split('?');
  const name = (ROUTES as readonly string[]).includes(path) ? (path as RouteName) : 'dashboard';
  const params: Record<string, string> = {};
  new URLSearchParams(query).forEach((value, key) => {
    params[key] = value;
  });
  return { name, params };
}

export function formatHash(name: RouteName, params: Record<string, string | null | undefined> = {}): string {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value) query.set(key, value);
  });
  const qs = query.toString();
  return `#/${name}${qs ? `?${qs}` : ''}`;
}

/** Hash-based routing: works with any static host (nginx, vite preview) without rewrite rules. */
export function useHashRoute(): [Route, (name: RouteName, params?: Record<string, string | null | undefined>) => void] {
  const [route, setRoute] = useState<Route>(() => parseHash(window.location.hash));

  useEffect(() => {
    const onChange = () => setRoute(parseHash(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  const navigate = useCallback((name: RouteName, params: Record<string, string | null | undefined> = {}) => {
    const next = formatHash(name, params);
    if (window.location.hash !== next) window.location.hash = next;
  }, []);

  return [route, navigate];
}
