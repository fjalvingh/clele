import { useEffect, useState } from 'react';
import {
  claimPrintDaemon,
  deletePrintDaemon,
  getOctopartCredentials,
  getPrintDaemons,
  getPrintingPreference,
  updateOctopartCredentials,
  updatePrintDaemon,
  updatePrintingPreference,
} from '../api';
import type { OctopartCredentialsStatus, PrintDaemon, PrintingPreference, PrintMethod } from '../api/types';

import { useAuth } from '../auth/AuthContext';
import FormField from '../components/FormField';
import { useTheme, type ThemePreference } from '../theme/ThemeContext';
import { labelSizeFor, printLabelViaDaemon, type DaemonPrintState } from '../utils/labelPrint';

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
        <p className="mt-1 text-sm text-gray-600">Choose how Sortiment looks on this device.</p>

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

      <LabelPrintingSection />
    </div>
  );
}

function LabelPrintingSection() {
  const { refresh } = useAuth();
  const [preference, setPreferenceState] = useState<PrintingPreference | null>(null);
  const [daemons, setDaemons] = useState<PrintDaemon[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [printerIpDrafts, setPrinterIpDrafts] = useState<Record<number, string>>({});
  const [busyDaemonId, setBusyDaemonId] = useState<number | null>(null);
  const [testPrintState, setTestPrintState] = useState<Record<number, DaemonPrintState>>({});
  const [testPrintError, setTestPrintError] = useState<Record<number, string | undefined>>({});

  const load = () => {
    setLoading(true);
    setError(null);
    Promise.all([getPrintingPreference(), getPrintDaemons()])
      .then(([pref, list]) => {
        setPreferenceState(pref);
        setDaemons(list);
        setPrinterIpDrafts(Object.fromEntries(list.map((d) => [d.id, d.printerIp ?? ''])));
      })
      .catch((err) => setError((err as Error).message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const savePreference = async (method: PrintMethod, daemonId?: number) => {
    setError(null);
    try {
      const pref = await updatePrintingPreference({
        printMethod: method,
        preferredDaemonId: method === 'DAEMON' ? (daemonId ?? null) : null,
      });
      setPreferenceState(pref);
      await refresh();
    } catch (err) {
      setError((err as Error).message);
    }
  };

  const handleClaim = async (id: number) => {
    setBusyDaemonId(id);
    setError(null);
    try {
      await claimPrintDaemon(id);
      load();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusyDaemonId(null);
    }
  };

  const handleSavePrinterConfig = async (id: number) => {
    setBusyDaemonId(id);
    setError(null);
    try {
      const updated = await updatePrintDaemon(id, { printerIp: printerIpDrafts[id] });
      setDaemons((prev) => prev.map((d) => (d.id === id ? updated : d)));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusyDaemonId(null);
    }
  };

  const handleDelete = async (id: number) => {
    setBusyDaemonId(id);
    setError(null);
    try {
      await deletePrintDaemon(id);
      load();
      if (preference?.preferredDaemonId === id) {
        await refresh();
      }
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusyDaemonId(null);
    }
  };

  const handleTestPrint = async (id: number) => {
    setTestPrintState((prev) => ({ ...prev, [id]: 'sending' }));
    setTestPrintError((prev) => ({ ...prev, [id]: undefined }));
    const daemon = daemons.find((d) => d.id === id);
    await printLabelViaDaemon(
      id,
      'TEST LABEL',
      'Sortiment test print',
      (state, error) => {
        setTestPrintState((prev) => ({ ...prev, [id]: state }));
        if (error) setTestPrintError((prev) => ({ ...prev, [id]: error }));
      },
      labelSizeFor(daemon),
    );
  };

  const installOrigin = window.location.origin;
  const installCommand = `sudo ./install.sh --backend-url ${installOrigin}`;

  return (
    <section className="mt-6 rounded-lg border border-gray-200 bg-surface p-5 shadow-sm">
      <h2 className="text-lg font-semibold text-gray-900">Label printing</h2>
      <p className="mt-1 text-sm text-gray-600">
        Print labels through the browser's print dialog, or silently through a paired daemon
        running next to a network label printer.
      </p>

      {error && <p className="mt-3 text-sm text-red-600">{error}</p>}

      {!loading && preference && (
        <>
          <div className="mt-4 inline-flex rounded-lg border border-gray-300 p-1">
            <button
              type="button"
              onClick={() => savePreference('BROWSER')}
              className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
                preference.printMethod === 'BROWSER'
                  ? 'bg-blue-600 text-white'
                  : 'text-gray-600 hover:bg-gray-100'
              }`}
            >
              Browser
            </button>
            <button
              type="button"
              onClick={() => savePreference('DAEMON', preference.preferredDaemonId)}
              className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
                preference.printMethod === 'DAEMON'
                  ? 'bg-blue-600 text-white'
                  : 'text-gray-600 hover:bg-gray-100'
              }`}
            >
              Daemon
            </button>
          </div>

          {preference.printMethod === 'DAEMON' && (
            <div className="mt-4 space-y-3">
              {daemons.length === 0 && (
                <p className="text-sm text-gray-500">
                  No daemons found on your current network. Install one below, then reload this page.
                </p>
              )}
              {daemons.map((d) => (
                <div key={d.id} className="rounded-md border border-gray-200 p-3">
                  <div className="flex items-center justify-between gap-3">
                    <div>
                      <span className="font-medium text-gray-900">{d.name}</span>
                      <span
                        className={`ml-2 inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${
                          d.status === 'ACTIVE' ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-600'
                        }`}
                      >
                        {d.status === 'ACTIVE' ? 'Claimed' : 'Pending'}
                      </span>
                      <p className="mt-0.5 text-xs text-gray-500">
                        Version {d.version ?? 'unknown'}
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      {d.status === 'PENDING' && (
                        <button
                          type="button"
                          onClick={() => handleClaim(d.id)}
                          disabled={busyDaemonId === d.id}
                          className="rounded-md bg-blue-600 px-3 py-1 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                        >
                          Claim
                        </button>
                      )}
                      {d.owned && (
                        <>
                          <input
                            type="radio"
                            name="preferredDaemon"
                            checked={preference.preferredDaemonId === d.id}
                            onChange={() => savePreference('DAEMON', d.id)}
                            aria-label={`Use ${d.name} as the default printer`}
                          />
                          <button
                            type="button"
                            onClick={() => handleDelete(d.id)}
                            disabled={busyDaemonId === d.id}
                            className="rounded-md border border-gray-300 px-3 py-1 text-sm hover:bg-gray-50 disabled:opacity-50"
                          >
                            Remove
                          </button>
                        </>
                      )}
                    </div>
                  </div>
                  {d.outdated && (
                    <div className="mt-2 flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900">
                      <svg
                        className="mt-0.5 h-4 w-4 flex-shrink-0"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                        strokeWidth={2}
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z"
                        />
                      </svg>
                      <span>
                        This daemon is running version <strong>{d.version ?? 'unknown'}</strong>, but
                        this app expects <strong>{d.expectedVersion}</strong>. Download the daemon
                        below and re-run the installer to update it.
                      </span>
                    </div>
                  )}
                  {d.owned && (
                    <div className="mt-2">
                    <p className="mb-1 text-xs text-gray-500">
                      {d.mediaDescription ? (
                        <>
                          Loaded media: <span className="font-medium text-gray-700">{d.mediaDescription}</span>{' '}
                          — read from the printer, so labels are sized to it automatically.
                        </>
                      ) : (
                        <>
                          Media not detected yet. Set the printer address below; the daemon reads the
                          loaded label stock from the printer and sizes labels to it automatically.
                        </>
                      )}
                    </p>
                    <div className="flex items-end gap-2">
                      <FormField
                        label="Printer IP address"
                        value={printerIpDrafts[d.id] ?? ''}
                        onChange={(e) =>
                          setPrinterIpDrafts((prev) => ({ ...prev, [d.id]: e.target.value }))
                        }
                        placeholder="192.168.1.50"
                      />
                      <button
                        type="button"
                        onClick={() => handleSavePrinterConfig(d.id)}
                        disabled={busyDaemonId === d.id}
                        className="mb-4 rounded-md border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50 disabled:opacity-50"
                      >
                        Save
                      </button>
                      {d.printerIp && (
                        <button
                          type="button"
                          onClick={() => handleTestPrint(d.id)}
                          disabled={
                            testPrintState[d.id] === 'sending' || testPrintState[d.id] === 'printing'
                          }
                          className="mb-4 rounded-md border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50 disabled:opacity-50"
                        >
                          {testPrintState[d.id] === 'sending' || testPrintState[d.id] === 'printing'
                            ? 'Printing…'
                            : 'Test print'}
                        </button>
                      )}
                    </div>
                    </div>
                  )}
                  {testPrintState[d.id] === 'done' && (
                    <p className="mt-1 text-sm text-green-600">Test label printed.</p>
                  )}
                  {testPrintState[d.id] === 'failed' && (
                    <p className="mt-1 text-sm text-red-600">
                      Test print failed{testPrintError[d.id] ? `: ${testPrintError[d.id]}` : '.'}
                    </p>
                  )}
                </div>
              ))}
            </div>
          )}
        </>
      )}

      <div className="mt-5 border-t border-gray-200 pt-4">
        <p className="text-sm text-gray-600">
          To add a new daemon, download it onto an Ubuntu machine on the same network as your
          browser, extract it, and run the installer — it sets up a systemd service and
          self-registers. Once it appears above, claim it:
        </p>
        <a
          href="/downloads/clele-print-daemon.tar.gz"
          download
          className="mt-2 inline-flex items-center gap-2 rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
        >
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75V16.5M16.5 12 12 16.5m0 0L7.5 12m4.5 4.5V3" />
          </svg>
          Download for Linux (amd64)
        </a>
        <pre className="mt-2 overflow-x-auto rounded-md bg-gray-900 p-3 text-xs text-gray-100">
          <code>
            tar xzf clele-print-daemon.tar.gz{'\n'}
            {installCommand}
          </code>
        </pre>
      </div>
    </section>
  );
}
