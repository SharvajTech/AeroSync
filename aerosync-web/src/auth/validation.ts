export interface CredentialErrors {
  username?: string;
  password?: string;
}

const USERNAME = /^[A-Za-z0-9._@-]{3,64}$/;

/** Client-side checks before calling the backend (which validates again). */
export function validateCredentials(username: string, password: string): CredentialErrors {
  const errors: CredentialErrors = {};
  const name = username.trim();
  if (!name) errors.username = 'Enter your username.';
  else if (!USERNAME.test(name)) errors.username = 'Usernames are 3–64 letters, digits or . _ @ -';
  if (!password) errors.password = 'Enter your password.';
  else if (new TextEncoder().encode(password).length > 72) errors.password = 'Passwords are at most 72 bytes.';
  return errors;
}

export function hasErrors(errors: CredentialErrors): boolean {
  return Boolean(errors.username || errors.password);
}
