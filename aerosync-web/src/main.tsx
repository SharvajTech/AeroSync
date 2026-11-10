import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { configureAuth } from './api/client';
import { api, apiCache } from './api/endpoints';
import { AuthStore } from './auth/authStore';
import { browserSessionStorage } from './auth/session';
import './styles/tokens.css';
import './styles/app.css';

const storage = browserSessionStorage();
const authStore = new AuthStore({
  storage,
  login: (username, password) => api.login(username, password),
  me: () => api.me(),
  clearCaches: () => apiCache.invalidate(),
});
// Every API request carries the session token; any 401 ends the session.
configureAuth({ token: authStore.token, onUnauthorized: authStore.handleUnauthorized });

const root = document.getElementById('root');
if (!root) throw new Error('Missing #root element');

createRoot(root).render(
  <StrictMode>
    <App store={authStore} storage={storage} />
  </StrictMode>,
);
