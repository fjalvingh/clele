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

  // The gap between the symbol and the amount is a NON-BREAKING space: with an ordinary one the
  // browser treats "€ 6,064.90" as two words and wraps between them, which is what put a lone €
  // on its own line above the figure in a dashboard tile. Every price in the app goes through
  // here, so this keeps the symbol attached to its amount in tiles, tables and totals alike.
  const formatMoney = (amount: number | string | null | undefined) => {
    const n = Number(amount ?? 0);
    return `${settings.currencySymbol}\u00A0${n.toLocaleString(undefined, {
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
