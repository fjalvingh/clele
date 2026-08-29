import { useEffect, useState } from 'react';
import {
  claimPrintDaemon,
  createMcpKey,
  deleteMcpKey,
  deletePrintDaemon,
  getMcpKeys,
  getOctopartCredentials,
  getPrintDaemons,
  getPrintingPreference,
  updateOctopartCredentials,
  updatePrintDaemon,
  updatePrintingPreference,
  switchOrganisation,
} from '../api';
import type {
  McpApiKey,
  OctopartCredentialsStatus,
  PrintDaemon,
  PrinterType,
  PrintingPreference,
  PrintMethod,
} from '../api/types';

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
    <div className="mx-auto max-w-2xl p-4 md:p-6">
      <h1 className="text-2xl font-bold text-gray-900">My Account</h1>
      <p className="mt-1 text-sm text-gray-500">Your personal settings.</p>

      <OrganisationSection />

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

      <McpAccessSection />
    </div>
  );
}

/** The printer configuration form's working copy, before it is saved. */
interface ConfigDraft {
  printerType: PrinterType;
  printerIp: string;
  printerQueue: string;
  mediaKeyword: string;
}

const draftFor = (d: PrintDaemon): ConfigDraft => ({
  printerType: d.printerType ?? 'BROTHER_QL',
  printerIp: d.printerIp ?? '',
  printerQueue: d.printerQueue ?? '',
  mediaKeyword: d.mediaKeyword ?? '',
});

/** Whether a daemon has everything its printer type needs before it can print. */
const isConfigured = (d: PrintDaemon): boolean =>
  d.printerType === 'DYMO_CUPS' ? !!d.printerQueue && !!d.mediaKeyword : !!d.printerIp;

const PRINTER_TYPE_LABELS: Record<PrinterType, string> = {
  BROTHER_QL: 'Brother QL (network)',
  DYMO_CUPS: 'Dymo LabelWriter (USB, via CUPS)',
};

function LabelPrintingSection() {
  const { refresh } = useAuth();
  const [preference, setPreferenceState] = useState<PrintingPreference | null>(null);
  const [daemons, setDaemons] = useState<PrintDaemon[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [configDrafts, setConfigDrafts] = useState<Record<number, ConfigDraft>>({});
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
        setConfigDrafts(Object.fromEntries(list.map((d) => [d.id, draftFor(d)])));
      })
      .catch((err) => setError((err as Error).message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const savePreference = async (method: PrintMethod, daemonId?: number, printBarcodeLabel?: boolean) => {
    setError(null);
    try {
      const pref = await updatePrintingPreference({
        printMethod: method,
        preferredDaemonId: method === 'DAEMON' ? (daemonId ?? null) : null,
        printBarcodeLabel: printBarcodeLabel ?? preference?.printBarcodeLabel,
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
    const draft = configDrafts[id];
    if (!draft) return;
    setBusyDaemonId(id);
    setError(null);
    try {
      // The whole configuration goes every time, with the fields that do not apply to the chosen
      // printer type explicitly cleared, so switching type cannot leave the old target behind.
      const cups = draft.printerType === 'DYMO_CUPS';
      const updated = await updatePrintDaemon(id, {
        printerType: draft.printerType,
        printerIp: cups ? null : draft.printerIp,
        printerQueue: cups ? draft.printerQueue : null,
        mediaKeyword: cups ? draft.mediaKeyword : null,
      });
      setDaemons((prev) => prev.map((d) => (d.id === id ? updated : d)));
      setConfigDrafts((prev) => ({ ...prev, [id]: draftFor(updated) }));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusyDaemonId(null);
    }
  };

  const patchDraft = (id: number, patch: Partial<ConfigDraft>) =>
    setConfigDrafts((prev) => {
      // load() seeds a draft for every visible daemon, but fall back rather than build a partial
      // one if that ever stops holding — a half-filled draft would silently clear fields on save.
      const base = prev[id] ?? draftFor(daemons.find((d) => d.id === id)!);
      return { ...prev, [id]: { ...base, ...patch } };
    });

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
        running next to your label printer — a Brother QL on the network, or a Dymo LabelWriter
        on USB.
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

          <label className="mt-4 flex items-start gap-2 text-sm text-gray-700">
            <input
              type="checkbox"
              checked={preference.printBarcodeLabel}
              onChange={(e) =>
                savePreference(preference.printMethod, preference.preferredDaemonId, e.target.checked)
              }
              className="mt-0.5"
            />
            <span>
              Print a barcode label alongside the part label
              <span className="block text-xs text-gray-500">
                A second label with a Code 128 barcode (CLE-000123) identifying the part. Scanning it
                on the Barcode Scan page opens that part directly. This is only the default — you can
                change it for each print.
              </span>
            </span>
          </label>

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
                  {d.owned && (() => {
                    const draft = configDrafts[d.id] ?? draftFor(d);
                    const cups = draft.printerType === 'DYMO_CUPS';
                    const queues = d.capabilities?.queues ?? [];
                    const queueMedia =
                      queues.find((q) => q.name === draft.printerQueue)?.media ?? [];
                    return (
                    <div className="mt-2">
                    <p className="mb-1 text-xs text-gray-500">
                      {d.mediaDescription ? (
                        <>
                          Label size: <span className="font-medium text-gray-700">{d.mediaDescription}</span>
                          {d.printerModel ? ` on ${d.printerModel}` : ''}
                          {cups
                            ? ' — this printer cannot sense its roll, so labels are sized to your choice.'
                            : ' — read from the printer, so labels are sized to it automatically.'}
                        </>
                      ) : cups ? (
                        <>Pick the print queue and the label size loaded in the printer.</>
                      ) : (
                        <>
                          Media not detected yet. Set the printer address below; the daemon reads the
                          loaded label stock from the printer and sizes labels to it automatically.
                        </>
                      )}
                    </p>
                    <div className="flex flex-wrap items-end gap-2">
                      <div className="w-56">
                        <FormField
                          as="select"
                          label="Printer type"
                          value={draft.printerType}
                          onChange={(e) => {
                            // Changing family invalidates the other family's target entirely.
                            const printerType = e.target.value as PrinterType;
                            patchDraft(d.id, { printerType, printerIp: '', printerQueue: '', mediaKeyword: '' });
                          }}
                        >
                          {(Object.keys(PRINTER_TYPE_LABELS) as PrinterType[]).map((t) => (
                            <option key={t} value={t}>
                              {PRINTER_TYPE_LABELS[t]}
                            </option>
                          ))}
                        </FormField>
                      </div>

                      {!cups && (
                        <FormField
                          label="Printer IP address"
                          value={draft.printerIp}
                          onChange={(e) => patchDraft(d.id, { printerIp: e.target.value })}
                          placeholder="192.168.1.50"
                        />
                      )}

                      {cups && (
                        <>
                          <div className="w-72">
                            <FormField
                              as="select"
                              label="Print queue"
                              value={draft.printerQueue}
                              disabled={queues.length === 0}
                              onChange={(e) =>
                                // A different queue offers different stock, so the size must be re-picked.
                                patchDraft(d.id, { printerQueue: e.target.value, mediaKeyword: '' })
                              }
                            >
                              <option value="">
                                {queues.length === 0 ? 'Waiting for the daemon…' : 'Select a queue…'}
                              </option>
                              {queues.map((q) => (
                                <option key={q.name} value={q.name}>
                                  {q.name}
                                  {q.makeAndModel ? ` — ${q.makeAndModel}` : ''}
                                </option>
                              ))}
                            </FormField>
                          </div>
                          <div className="w-64">
                            <FormField
                              as="select"
                              label="Label size"
                              value={draft.mediaKeyword}
                              disabled={queueMedia.length === 0}
                              onChange={(e) => patchDraft(d.id, { mediaKeyword: e.target.value })}
                            >
                              <option value="">
                                {draft.printerQueue ? 'Select the loaded label…' : 'Pick a queue first'}
                              </option>
                              {queueMedia.map((m) => (
                                <option key={m.keyword} value={m.keyword}>
                                  {m.displayName ?? m.keyword}
                                </option>
                              ))}
                            </FormField>
                          </div>
                        </>
                      )}

                      <button
                        type="button"
                        onClick={() => handleSavePrinterConfig(d.id)}
                        disabled={busyDaemonId === d.id}
                        className="mb-4 rounded-md border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50 disabled:opacity-50"
                      >
                        Save
                      </button>
                      {isConfigured(d) && (
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
                    {cups && queues.length === 0 && (
                      // The daemon discovers its queues on its next poll, so this state is normal
                      // for up to a poll window after switching type — but only ends when it does.
                      <p className="-mt-2 mb-2 text-xs text-gray-500">
                        The daemon reports the print queues on its machine within about half a
                        minute of being asked.{' '}
                        <button type="button" onClick={load} className="underline hover:text-gray-700">
                          Refresh
                        </button>
                      </p>
                    )}
                    </div>
                    );
                  })()}
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

/**
 * The organisation in force for this session. Everything else in the app — parts, stock, locations,
 * categories, spec fields, tags — belongs to exactly one organisation, so switching here changes
 * what every screen shows. The same switcher lives in the sidebar next to the current user.
 */
function OrganisationSection() {
  const { user } = useAuth();
  const [switching, setSwitching] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const organisations = user?.selectableOrganisations ?? [];

  const handleSwitch = async (id: number) => {
    if (id === user?.currentOrganisationId) return;
    setSwitching(true);
    setError(null);
    try {
      await switchOrganisation(id);
      // Reload rather than refresh(): every page loads its data on mount, so only a full reload
      // guarantees nothing is left showing the previous organisation's data.
      window.location.reload();
    } catch (e: unknown) {
      setError((e as Error).message);
      setSwitching(false);
    }
  };

  if (!user?.currentOrganisationName) return null;

  return (
    <section className="mt-6 rounded-lg border border-gray-200 bg-surface p-5 shadow-sm">
      <h2 className="text-lg font-semibold text-gray-900">Organisation</h2>
      <p className="mt-1 text-sm text-gray-600">
        The organisation you are working in. Parts, stock, locations, categories and spec fields all
        belong to it; switching reloads the app with that organisation's data.
      </p>

      <div className="mt-4 space-y-1">
        {organisations.map((org) => (
          <label
            key={org.id}
            className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 hover:bg-gray-50"
          >
            <input
              type="radio"
              name="organisation"
              checked={org.id === user.currentOrganisationId}
              disabled={switching}
              onChange={() => handleSwitch(org.id)}
              className="text-blue-600"
            />
            <span className="text-sm text-gray-800">{org.name}</span>
            {org.template && (
              <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-gray-500">
                Template
              </span>
            )}
          </label>
        ))}
      </div>

      {organisations.length <= 1 && (
        <p className="mt-3 text-sm text-gray-500">
          You belong to a single organisation. An administrator can add you to more.
        </p>
      )}
      {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
    </section>
  );
}

/**
 * Keys for the MCP endpoint — the door an AI assistant reads the catalogue through. A key carries
 * no more access than its owner has, and the endpoint behind it is read-only, so issuing one needs
 * no permission beyond being able to log in.
 *
 * <p>The token is shown once, here, and never again: only its hash is stored. That is why the
 * newly created key gets its own panel with the ready-made command rather than a line in the list.
 */
function McpAccessSection() {
  const { user } = useAuth();
  const [keys, setKeys] = useState<McpApiKey[]>([]);
  const [name, setName] = useState('');
  const [creating, setCreating] = useState(false);
  const [issued, setIssued] = useState<{ token: string; name: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const endpoint = `${window.location.origin}/api/mcp`;
  const command = issued
    ? `claude mcp add --transport http sortiment ${endpoint} --header "X-Api-Key: ${issued.token}"`
    : '';

  useEffect(() => {
    getMcpKeys()
      .then(setKeys)
      .catch((e: unknown) => setError((e as Error).message));
  }, []);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;
    setCreating(true);
    setError(null);
    setCopied(false);
    try {
      const created = await createMcpKey({ name: name.trim() });
      setIssued({ token: created.token, name: created.key.name });
      setKeys((current) => [created.key, ...current]);
      setName('');
    } catch (err: unknown) {
      setError((err as Error).message);
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async (key: McpApiKey) => {
    if (!confirm(`Revoke "${key.name}"? Anything using it stops working immediately.`)) return;
    setError(null);
    try {
      await deleteMcpKey(key.id);
      setKeys((current) => current.filter((k) => k.id !== key.id));
    } catch (err: unknown) {
      setError((err as Error).message);
    }
  };

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(command);
      setCopied(true);
    } catch {
      setError('Could not copy — select the command and copy it by hand.');
    }
  };

  return (
    <section className="mt-6 rounded-lg border border-gray-200 bg-surface p-5 shadow-sm">
      <h2 className="text-lg font-semibold text-gray-900">AI access (MCP)</h2>
      <p className="mt-1 text-sm text-gray-600">
        Let an AI assistant search this catalogue — parts, specifications, stock and locations —
        through the Model Context Protocol. Access is <strong>read-only</strong>: nothing reached
        this way can change a part, a specification or your stock. A key reads only{' '}
        {user?.currentOrganisationName ?? 'your current organisation'} and carries no more
        permission than you have there.
      </p>

      <form onSubmit={handleCreate} className="mt-4 flex flex-wrap items-end gap-2">
        <div className="min-w-[16rem] flex-1">
          <FormField
            label="New key"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="What will use it, e.g. Claude on the laptop"
            autoComplete="off"
          />
        </div>
        <button
          type="submit"
          disabled={creating || !name.trim()}
          className="mb-4 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {creating ? 'Creating…' : 'Create key'}
        </button>
      </form>

      {issued && (
        <div className="mb-4 rounded-md border border-amber-300 bg-amber-50 p-3">
          <p className="text-sm font-medium text-amber-900">
            Copy this now — it is not shown again.
          </p>
          <p className="mt-1 text-xs text-amber-800">
            Run this to connect Claude Code; other MCP clients want the same URL and header.
          </p>
          <pre className="mt-2 overflow-x-auto rounded bg-white/70 p-2 text-xs text-gray-800">
            {command}
          </pre>
          <button
            type="button"
            onClick={handleCopy}
            className="mt-2 inline-flex items-center gap-1.5 rounded-md border border-amber-400 bg-white px-3 py-1.5 text-xs font-medium text-amber-900 hover:bg-amber-100"
          >
            <svg
              className="h-3.5 w-3.5"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <rect x="9" y="9" width="13" height="13" rx="2" />
              <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
            </svg>
            {copied ? 'Copied' : 'Copy command'}
          </button>
        </div>
      )}

      {keys.length > 0 ? (
        <ul className="divide-y divide-gray-200 border-t border-gray-200">
          {keys.map((key) => (
            <li key={key.id} className="flex items-center justify-between gap-3 py-2.5">
              <div className="min-w-0">
                <p className="truncate text-sm font-medium text-gray-900">{key.name}</p>
                <p className="text-xs text-gray-500">
                  {key.organisationName} · created {new Date(key.createdAt).toLocaleDateString()} ·{' '}
                  {key.lastUsedAt
                    ? `last used ${new Date(key.lastUsedAt).toLocaleString()}`
                    : 'never used'}
                </p>
              </div>
              <button
                type="button"
                onClick={() => handleDelete(key)}
                className="shrink-0 rounded-md border border-gray-300 px-3 py-1.5 text-xs font-medium text-red-700 hover:bg-red-50"
              >
                Revoke
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-gray-500">No keys yet.</p>
      )}

      {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
    </section>
  );
}
