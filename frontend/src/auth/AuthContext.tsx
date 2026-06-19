import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { getMe, login as apiLogin, logout as apiLogout } from '../api';
import type { AuthUser } from '../api/types';

interface AuthContextValue {
  user: AuthUser | null;
  loading: boolean;
  hasPermission: (key: string) => boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);

  // Resolve the current session on first load.
  useEffect(() => {
    getMe()
      .then(setUser)
      .catch(() => setUser(null))
      .finally(() => setLoading(false));
  }, []);

  const login = async (email: string, password: string) => {
    const u = await apiLogin(email, password);
    setUser(u);
  };

  const logout = async () => {
    try {
      await apiLogout();
    } finally {
      setUser(null);
    }
  };

  const refresh = async () => {
    const u = await getMe().catch(() => null);
    setUser(u);
  };

  const hasPermission = (key: string) => !!user?.permissions?.includes(key);

  return (
    <AuthContext.Provider value={{ user, loading, hasPermission, login, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider');
  return ctx;
}
