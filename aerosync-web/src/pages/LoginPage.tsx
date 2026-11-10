import { useEffect, useRef, useState, type FormEvent } from 'react';
import { LuEye, LuEyeOff, LuLock, LuPlane, LuShieldCheck } from 'react-icons/lu';
import { ApiError } from '../api/client';
import { hasErrors, validateCredentials, type CredentialErrors } from '../auth/validation';
import type { BackendStatus } from '../hooks/useBackendStatus';

/** User-facing text for a failed login request. */
export function loginErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.kind) {
      case 'unauthorized':
        return 'Invalid username or password.';
      case 'rate_limited':
      case 'forbidden':
      case 'bad_request':
        return error.message;
      case 'network':
      case 'timeout':
        return 'Cannot reach the AeroSync backend. Check that it is running and try again.';
      case 'server':
        return `The backend could not complete the sign-in (HTTP ${error.status ?? 500}). Try again later.`;
      default:
        return error.message;
    }
  }
  return 'Sign-in failed. Try again.';
}

export function LoginPage({ notice, onLogin, status }: {
  notice: string | null;
  onLogin: (username: string, password: string) => Promise<void>;
  status: BackendStatus;
}) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<CredentialErrors>({});
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const usernameRef = useRef<HTMLInputElement | null>(null);
  const passwordRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    usernameRef.current?.focus();
  }, []);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (submitting) return;
    const errors = validateCredentials(username, password);
    setFieldErrors(errors);
    setError(null);
    if (hasErrors(errors)) {
      (errors.username ? usernameRef : passwordRef).current?.focus();
      return;
    }
    setSubmitting(true);
    try {
      await onLogin(username, password);
    } catch (failure) {
      setError(loginErrorMessage(failure));
      setPassword('');
      passwordRef.current?.focus();
      setSubmitting(false);
    }
  };

  return (
    <div className="login" data-testid="login-page">
      <main className="login__card">
        <div className="login__brand">
          <LuPlane className="brand__mark" aria-hidden />
          <span className="login__brand-name">AeroSync</span>
        </div>
        <h1 className="login__title">Sign in</h1>
        <p className="login__lead">Airport operations dashboard. Use the account issued for your airport.</p>

        {notice && !error && (
          <div className="notice notice--info" role="status" data-testid="login-notice">{notice}</div>
        )}
        {error && (
          <div className="notice notice--danger" role="alert" data-testid="login-error">{error}</div>
        )}

        <form className="login__form" onSubmit={submit} noValidate>
          <div className="field">
            <label className="field__label" htmlFor="login-username">Username</label>
            <input
              ref={usernameRef}
              id="login-username"
              name="username"
              className={`input${fieldErrors.username ? ' input--invalid' : ''}`}
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              aria-invalid={Boolean(fieldErrors.username)}
              aria-describedby={fieldErrors.username ? 'login-username-error' : undefined}
              disabled={submitting}
            />
            {fieldErrors.username && (
              <span className="field__error" id="login-username-error">{fieldErrors.username}</span>
            )}
          </div>

          <div className="field">
            <label className="field__label" htmlFor="login-password">Password</label>
            <div className="input-group">
              <input
                ref={passwordRef}
                id="login-password"
                name="password"
                type={showPassword ? 'text' : 'password'}
                className={`input${fieldErrors.password ? ' input--invalid' : ''}`}
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                aria-invalid={Boolean(fieldErrors.password)}
                aria-describedby={fieldErrors.password ? 'login-password-error' : undefined}
                disabled={submitting}
              />
              <button
                type="button"
                className="icon-button input-group__button"
                aria-label={showPassword ? 'Hide password' : 'Show password'}
                aria-pressed={showPassword}
                onClick={() => setShowPassword((v) => !v)}
              >
                {showPassword ? <LuEyeOff aria-hidden /> : <LuEye aria-hidden />}
              </button>
            </div>
            {fieldErrors.password && (
              <span className="field__error" id="login-password-error">{fieldErrors.password}</span>
            )}
          </div>

          <button type="submit" className="button button--primary button--block" disabled={submitting}>
            <LuLock aria-hidden /> {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <div className="login__footer">
          <span className="login__scope"><LuShieldCheck aria-hidden /> You will only see the airports assigned to your account.</span>
          <span className="login__status" data-testid="login-backend-status">
            <span className={`status-dot status-dot--${status}`} aria-hidden />
            {status === 'online' ? 'Backend online' : status === 'offline' ? 'Backend unreachable' : 'Checking backend…'}
          </span>
        </div>
      </main>
    </div>
  );
}

export function SplashScreen() {
  return (
    <div className="login" data-testid="splash">
      <div className="login__card login__card--compact" role="status">
        <div className="login__brand">
          <LuPlane className="brand__mark" aria-hidden />
          <span className="login__brand-name">AeroSync</span>
        </div>
        <p className="login__lead">Restoring your session…</p>
      </div>
    </div>
  );
}
