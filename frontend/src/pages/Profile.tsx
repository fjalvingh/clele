import { useEffect, useState } from 'react';
import { getOctopartCredentials, updateOctopartCredentials } from '../api';
import type { OctopartCredentialsStatus } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import FormField from '../components/FormField';
import { useTheme, type ThemePreference } from '../theme/ThemeContext';

const THEME_OPTIONS: { value: ThemePreference; label: string }[] = [
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
  { value: 'system', label: 'System' },
];

export default function ProfilePage() {
  const { refresh } = useAuth();
  const { preference, setPreference } = useTheme();
  const [status, setStatus] = useState<OctopartCredentialsStatus | null>(null);
  const [clientId, setClientId] = useState('');
  const [clientSecret, setClientSecret] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    getOctopartCredentials()
      .then((s) => {
        setStatus(s);
        setClientId(s.clientId ?? '');
      })
      .catch((err) => setError((err as Error).message));
  }, []);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      const s = await updateOctopartCredentials({ clientId, clientSecret });
      setStatus(s);
      setClientSecret('');
      setSaved(true);
      // Refresh the current user so OctoPart-gated UI (e.g. the Part detail button) updates.
      await refresh();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="mx-auto max-w-2xl p-6">
      <h1 className="text-2xl font-bold text-gray-900">My Account</h1>
      <p className="mt-1 text-sm text-gray-500">Your personal settings.</p>

      <section className="mt-6 rounded-lg border border-gray-200 bg-surface p-5 shadow-sm">
        <h2 className="text-lg font-semibold text-gray-900">Appearance</h2>
        <p className="mt-1 text-sm text-gray-600">Choose how Clele looks on this device.</p>

        <div className="mt-4 inline-flex rounded-lg border border-gray-300 p-1">
          {THEME_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              onClick={() => setPreference(opt.value)}
              className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
                preference === opt.value
                  ? 'bg-blue-600 text-white'
                  : 'text-gray-600 hover:bg-gray-100'
              }`}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </section>

      <section className="mt-6 rounded-lg border border-gray-200 bg-surface p-5 shadow-sm">
        <h2 className="text-lg font-semibold text-gray-900">OctoPart (Nexar) credentials</h2>
        <p className="mt-1 text-sm text-gray-600">
          Used to look up part information from OctoPart. Each account uses its own free Nexar
          contract (limited to a fixed number of requests per month). Get a Client ID and Client
          Secret by registering an application at{' '}
          <a
            href="https://nexar.com/"
            target="_blank"
            rel="noreferrer"
            className="text-blue-600 hover:underline"
          >
            nexar.com
          </a>
          .
        </p>

        {status && (
          <div className="mt-3 text-sm">
            <span
              className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${
                status.hasClientId && status.hasClientSecret
                  ? 'bg-green-100 text-green-800'
                  : 'bg-gray-100 text-gray-600'
              }`}
            >
              {status.hasClientId && status.hasClientSecret
                ? '✓ Credentials configured'
                : 'Not configured'}
            </span>
          </div>
        )}

        <form onSubmit={handleSave} className="mt-4">
          <FormField
            label="Client ID"
            value={clientId}
            onChange={(e) => setClientId(e.target.value)}
            placeholder="Nexar client id"
            autoComplete="off"
          />
          <FormField
            label="Client Secret"
            type="password"
            value={clientSecret}
            onChange={(e) => setClientSecret(e.target.value)}
            placeholder={
              status?.hasClientSecret ? 'Leave blank to keep current secret' : 'Nexar client secret'
            }
            autoComplete="new-password"
          />

          {error && <p className="mb-3 text-sm text-red-600">{error}</p>}
          {saved && !error && <p className="mb-3 text-sm text-green-600">Saved.</p>}

          <button
            type="submit"
            disabled={saving}
            className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save credentials'}
          </button>
        </form>
      </section>
    </div>
  );
}
