import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ApiError, api, type Me } from './api';

type AuthState =
  | { status: 'loading' }
  | { status: 'anonymous' }
  | { status: 'mfaPending'; me: Me }
  | { status: 'ready'; me: Me };

interface AuthContextValue {
  state: AuthState;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading' });

  const refresh = useCallback(async () => {
    try {
      const me = await api.me();
      setState(me.mfaVerified ? { status: 'ready', me } : { status: 'mfaPending', me });
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setState({ status: 'anonymous' });
      } else {
        throw error;
      }
    }
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      setState({ status: 'anonymous' });
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const value = useMemo(() => ({ state, refresh, logout }), [state, refresh, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return context;
}
