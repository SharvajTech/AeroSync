/**
 * Backend base URL. Empty (the default) means same-origin relative /api
 * calls, which the Vite dev server and the nginx container proxy to the
 * Spring Boot backend. No credentials are configured here: the operational
 * network endpoints are read-only and public.
 */
export function apiBaseUrl(): string {
  const configured = import.meta.env?.VITE_API_BASE_URL;
  return typeof configured === 'string' ? configured.trim().replace(/\/+$/, '') : '';
}

export const REQUEST_TIMEOUT_MS = 15_000;
