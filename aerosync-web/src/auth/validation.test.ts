import { describe, expect, it } from 'vitest';
import { hasErrors, validateCredentials } from './validation';

describe('login form validation', () => {
  it('requires both fields', () => {
    const errors = validateCredentials('  ', '');
    expect(errors.username).toBe('Enter your username.');
    expect(errors.password).toBe('Enter your password.');
    expect(hasErrors(errors)).toBe(true);
  });

  it('accepts usernames like bom.ops and rejects other characters', () => {
    expect(hasErrors(validateCredentials('bom.ops', 'x'))).toBe(false);
    expect(hasErrors(validateCredentials(' Ops@BOM ', 'x'))).toBe(false);
    expect(validateCredentials('ab', 'x').username).toBeDefined();
    expect(validateCredentials('bom ops', 'x').username).toBeDefined();
    expect(validateCredentials('<script>', 'x').username).toBeDefined();
  });

  it('rejects passwords longer than BCrypt can check', () => {
    expect(validateCredentials('bom.ops', 'x'.repeat(72)).password).toBeUndefined();
    expect(validateCredentials('bom.ops', 'x'.repeat(73)).password).toBeDefined();
    expect(validateCredentials('bom.ops', 'é'.repeat(37)).password).toBeDefined();
  });
});
