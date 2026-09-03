import { useEffect, useState } from 'react';
import { checkAiStatus, getAiConfig, getAutoCategorizeStatus, startAutoCategorize, updateAiConfig } from '../api';
import FormField from '../components/FormField';
import type { AiConfig, CategorizationStatus } from '../api/types';

export default function AdminActionsPage() {
  const [catStatus, setCatStatus] = useState<CategorizationStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Poll the auto-categorization job until it finishes.
  const pollCategorize = () => {
    getAutoCategorizeStatus()
      .then((st) => {
        setCatStatus(st);
        if (st.running) {
          setTimeout(pollCategorize, 1500);
        }
      })
      .catch((e: Error) => setError(e.message));
  };

  // Resume progress display if a categorization job is already running (e.g. after a reload).
  useEffect(() => {
    getAutoCategorizeStatus()
      .then((st) => {
        if (st.running) {
          setCatStatus(st);
          setTimeout(pollCategorize, 1500);
        }
      })
      .catch(() => {});
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleAutoCategorize = async (onlyUncategorized: boolean) => {
    const msg = onlyUncategorized
      ? 'Auto-categorize only the uncategorized parts using the local AI?'
      : 'Auto-categorize ALL parts using the local AI? This overwrites existing categories.';
    if (!confirm(msg)) return;
    try {
      const st = await startAutoCategorize(onlyUncategorized);
      setCatStatus(st);
      setTimeout(pollCategorize, 1000);
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  return (
    <div className="p-4 md:p-8">
      <h1 className="mb-6 text-2xl font-bold text-gray-900">Admin Actions</h1>

      <AiLookupSection />

      <div className="mt-6 rounded-lg border border-gray-200 bg-white p-6">
        <h2 className="mb-1 text-lg font-semibold text-gray-900">Auto-categorization</h2>
        <p className="mb-4 text-sm text-gray-500">
          Assign categories to parts using the local AI (Ollama).
        </p>
        <div className="flex gap-3">
          <button
            onClick={() => handleAutoCategorize(true)}
            disabled={catStatus?.running}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            title="Categorize only the parts that have no category yet (local AI / Ollama)"
          >
            ✨ Categorize uncategorized
          </button>
          <button
            onClick={() => handleAutoCategorize(false)}
            disabled={catStatus?.running}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            title="Re-categorize every part, overwriting existing assignments (local AI / Ollama)"
          >
            {catStatus?.running ? 'Categorizing…' : '✨ Re-categorize all'}
          </button>
        </div>

        {error && <p className="mt-4 text-sm text-red-600">{error}</p>}

        {/* Progress / result */}
        {catStatus && (catStatus.running || catStatus.finishedAt) && (
          <div className="mt-4 rounded-lg border border-gray-200 bg-gray-50 p-4">
            <div className="flex items-center justify-between text-sm">
              <span className="font-medium text-gray-700">
                {catStatus.running
                  ? `Auto-categorizing parts… ${catStatus.processed}/${catStatus.total}`
                  : `Auto-categorization complete — assigned ${catStatus.assigned}, skipped ${catStatus.skipped} of ${catStatus.total}`}
              </span>
              {!catStatus.running && (
                <button
                  onClick={() => setCatStatus(null)}
                  className="text-xs text-gray-400 hover:text-gray-600"
                >
                  Dismiss
                </button>
              )}
            </div>
            <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-gray-200">
              <div
                className="h-full rounded-full bg-blue-600 transition-all"
                style={{ width: `${catStatus.total ? (catStatus.processed / catStatus.total) * 100 : 0}%` }}
              />
            </div>
            {catStatus.lastError && (
              <p className="mt-2 text-xs text-red-600">{catStatus.lastError}</p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * The organisation's own Anthropic key.
 *
 * An AI part search costs 5-13 cents, and until this existed every organisation spent it through one
 * app-wide key — so one tenant's enthusiasm landed on everybody's bill with nothing to attribute it
 * to. Each organisation now brings its own contract and pays for what it uses; one that has not is
 * not broken, it simply has no AI, and the rest of the app carries on.
 *
 * The stored key is never sent back here — only its last four characters, which is enough for the
 * person who pasted it to recognise it and useless to anybody else.
 */
function AiLookupSection() {
  const [config, setConfig] = useState<AiConfig | null>(null);
  const [apiKey, setApiKey] = useState('');
  const [model, setModel] = useState('');
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = (fresh: AiConfig) => {
    setConfig(fresh);
    setModel(fresh.model ?? '');
    setApiKey('');
  };

  useEffect(() => {
    getAiConfig()
      .then(load)
      .catch((e: Error) => setError(e.message));
  }, []);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      load(await updateAiConfig({ apiKey, model }));
      setSaved(true);
    } catch (e: unknown) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleRemove = async () => {
    if (!confirm('Remove this organisation\u2019s API key? AI lookups stop until a new key is entered.')) return;
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      load(await updateAiConfig({ clearApiKey: true, model }));
    } catch (e: unknown) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  // Costs a fraction of a cent, and is the only way to tell a good key from a revoked one or an
  // empty balance without running a real lookup. A success also clears a recorded failure.
  const handleTest = async () => {
    setTesting(true);
    setError(null);
    setSaved(false);
    try {
      const status = await checkAiStatus();
      setConfig((prev) =>
        prev === null
          ? prev
          : { ...prev, state: status.state, usable: status.usable, message: status.message, since: status.since },
      );
    } catch (e: unknown) {
      setError((e as Error).message);
    } finally {
      setTesting(false);
    }
  };

  return (
    <div className="rounded-lg border border-gray-200 bg-surface p-6">
      <h2 className="mb-1 text-lg font-semibold text-gray-900">AI lookup (Anthropic)</h2>
      <p className="mb-4 text-sm text-gray-500">
        This organisation looks parts up with its own Anthropic API key and is billed for its own
        lookups — a part search costs roughly 5–13 cents. Get a key from{' '}
        <a
          href="https://console.anthropic.com/settings/keys"
          target="_blank"
          rel="noreferrer"
          className="text-blue-600 hover:underline"
        >
          console.anthropic.com
        </a>
        . Without one, adding a part still searches your own catalogue, the component cache and the
        web — only the AI sources are hidden.
      </p>

      {config && !config.serverSecretConfigured && (
        <p className="mb-4 rounded-md bg-amber-50 p-3 text-sm text-amber-800">
          This server cannot store API keys: <code>APP_SECRET_KEY</code> is not set on it. Ask
          whoever runs the installation to set it (any long random string) and restart.
        </p>
      )}

      {config && (
        <div className="mb-4 flex flex-wrap items-center gap-3 text-sm">
          <span
            className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${
              config.usable ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-600'
            }`}
          >
            {config.usable ? '✓ AI lookups available' : stateLabel(config.state)}
          </span>
          {config.hasApiKey && (
            <span className="text-xs text-gray-500">
              key ending …{config.keyHint} · model {config.model || config.defaultModel}
            </span>
          )}
          {config.hasApiKey && (
            <button
              onClick={handleTest}
              disabled={testing}
              className="rounded-md border border-gray-300 px-3 py-1 text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
              title="Send a one-token request to check the key and the account balance"
            >
              {testing ? 'Testing…' : 'Test connection'}
            </button>
          )}
        </div>
      )}

      {config && !config.usable && config.message && (
        <p className="mb-4 text-sm text-gray-600">{config.message}</p>
      )}

      <form onSubmit={handleSave}>
        <FormField
          label="Anthropic API key"
          type="password"
          value={apiKey}
          onChange={(e) => setApiKey(e.target.value)}
          placeholder={config?.hasApiKey ? 'Leave blank to keep the current key' : 'sk-ant-…'}
          autoComplete="new-password"
        />
        <FormField
          label="Model (optional)"
          value={model}
          onChange={(e) => setModel(e.target.value)}
          placeholder={config?.defaultModel ?? ''}
          autoComplete="off"
        />

        {error && <p className="mb-3 text-sm text-red-600">{error}</p>}
        {saved && !error && <p className="mb-3 text-sm text-green-600">Saved.</p>}

        <div className="flex gap-3">
          <button
            type="submit"
            disabled={saving || config?.serverSecretConfigured === false}
            className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
          {config?.hasApiKey && (
            <button
              type="button"
              onClick={handleRemove}
              disabled={saving}
              className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            >
              Remove key
            </button>
          )}
        </div>
      </form>
    </div>
  );
}

/** The unusable states, in the words the admin needs rather than the enum's. */
function stateLabel(state: string): string {
  switch (state) {
    case 'NOT_CONFIGURED':
      return 'No API key';
    case 'NO_CREDITS':
      return 'Out of credit';
    case 'KEY_REJECTED':
      return 'Key rejected';
    case 'KEY_UNREADABLE':
      return 'Key unreadable';
    case 'SERVER_SECRET_MISSING':
      return 'Server not configured';
    default:
      return state;
  }
}
