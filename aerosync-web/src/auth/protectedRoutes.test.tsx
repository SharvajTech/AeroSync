import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Root } from '../App';
import { ApiError } from '../api/client';
import { LoginPage, loginErrorMessage } from '../pages/LoginPage';
import { AuthProvider, resolveScreen } from './AuthContext';
import { AuthStore } from './authStore';
import { memoryStorage } from './session';
import { loginResponse, profile } from '../test/fixtures';

function store(): AuthStore {
  return new AuthStore({
    storage: memoryStorage(),
    login: async (username) => loginResponse(username, ['BOM']),
    me: async () => profile('bom.ops', ['BOM']),
    clearCaches: () => undefined,
    setTimer: () => 0,
    clearTimer: () => undefined,
  });
}

function render(authStore: AuthStore): string {
  return renderToStaticMarkup(
    <AuthProvider store={authStore}>
      <Root storage={memoryStorage()} />
    </AuthProvider>,
  );
}

describe('protected routes', () => {
  it('maps auth states to screens', () => {
    expect(resolveScreen('restoring')).toBe('splash');
    expect(resolveScreen('signed_out')).toBe('login');
    expect(resolveScreen('signed_in')).toBe('app');
  });

  it('shows a neutral splash, not the dashboard, while the session is checked', () => {
    const html = render(store());
    expect(html).toContain('data-testid="splash"');
    expect(html).not.toContain('Flight Network Dashboard');
  });

  it('shows only the login page when signed out, whatever the route', async () => {
    const authStore = store();
    await authStore.restore();
    const html = render(authStore);
    expect(html).toContain('data-testid="login-page"');
    expect(html).not.toContain('Flight Network Dashboard');
    expect(html).not.toContain('data-testid="network-graph"');
  });

  it('returns to the login page with a notice after logout', async () => {
    const authStore = store();
    await authStore.restore();
    await authStore.login('bom.ops', 'secret');
    authStore.logout();
    const html = render(authStore);
    expect(html).toContain('data-testid="login-page"');
    expect(html).toContain('You have signed out.');
  });
});

describe('login page', () => {
  it('renders an accessible form with browser autofill hints and no credentials', () => {
    const html = renderToStaticMarkup(
      <LoginPage notice="Your session has expired. Please sign in again." onLogin={async () => undefined} status="online" />,
    );
    // Attribute names are case-insensitive in HTML (React may emit autoComplete).
    expect(html.toLowerCase()).toContain('autocomplete="username"');
    expect(html.toLowerCase()).toContain('autocomplete="current-password"');
    expect(html).toContain('type="password"');
    expect(html).toContain('Your session has expired. Please sign in again.');
    expect(html).toContain('Backend online');
    expect(html).toContain('value=""');
  });

  it('explains login failures without revealing which part was wrong', () => {
    expect(loginErrorMessage(new ApiError('unauthorized', 'Invalid username or password', '/api/auth/login', 401, 'invalid_credentials')))
      .toBe('Invalid username or password.');
    expect(loginErrorMessage(new ApiError('rate_limited', 'Too many failed login attempts. Try again in 15 minute(s).', '/x', 429)))
      .toContain('Too many failed login attempts');
    expect(loginErrorMessage(new ApiError('network', 'down', '/x'))).toContain('Cannot reach the AeroSync backend');
    expect(loginErrorMessage(new ApiError('forbidden', 'Your account has no airport assignments', '/x', 403)))
      .toContain('no airport assignments');
  });
});
