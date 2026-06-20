import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { getSettings } from '../api';
import type { AppSettings } from '../api/types';

// Sensible default so prices render correctly before the fetch resolves (and if it fails).
const DEFAULT_SETTINGS: AppSettings = { currencyCode: 'EUR', currencySymbol: '€' };

interface SettingsContextValue {
  settings: AppSettings;
  /** Format a numeric amount with the app currency symbol, e.g. "€ 12.34". */
  formatMoney: (amount: number | string | null | undefined) => string;
}

const SettingsContext = createContext<SettingsContextValue | undefined>(undefined);

export function SettingsProvider({ children }: { children: ReactNode }) {
  const [settings, setSettings] = useState<AppSettings>(DEFAULT_SETTINGS);

  // App-wide settings are public and rarely change — load once on mount, best-effort.
  useEffect(() => {
    getSettings()
      .then(setSettings)
      .catch(() => setSettings(DEFAULT_SETTINGS));
  }, []);

  const formatMoney = (amount: number | string | null | undefined) => {
    const n = Number(amount ?? 0);
    return `${settings.currencySymbol} ${n.toLocaleString(undefined, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })}`;
  };

  return (
    <SettingsContext.Provider value={{ settings, formatMoney }}>
      {children}
    </SettingsContext.Provider>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useSettings() {
  const ctx = useContext(SettingsContext);
  if (!ctx) throw new Error('useSettings must be used within a SettingsProvider');
  return ctx;
}
