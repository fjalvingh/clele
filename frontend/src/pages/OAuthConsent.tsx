import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { approveOAuthConsent, denyOAuthConsent, getOAuthConsent } from '../api';
import type { OAuthConsent } from '../api/types';
import { useAuth } from '../auth/AuthContext';

/**
 * Where an AI client asks for access, and the only place that grants it.
 *
 * Registration is open — any client can obtain an id without anyone's approval — so this screen is
 * the entire security boundary of the OAuth flow. Two things follow from that, and both are
 * deliberate:
 *
 * - **The client's name is a claim, not an identity.** Anyone may register a client calling itself
 *   "Claude". The screen says where the browser will be sent back to, which is the part an attacker
 *   cannot fake, and words the name as something the client says about itself.
 * - **Deny is not a secondary action.** It is a real button, not a link in the corner, because a
 *   user who did not expect this page should find refusing at least as easy as accepting.
 */
export default function OAuthConsentPage() {
  const [params] = useSearchParams();
  const { user } = useAuth();
  const requestId = params.get('request');
  // The authorize endpoint sends errors it cannot safely return to the client here, so that they
  // are shown inside the app rather than on a hand-rolled error page.
  const upstreamError = params.get('error');
  const upstreamDescription = params.get('error_description');

  const [consent, setConsent] = useState<OAuthConsent | null>(null);
  const [organisationId, setOrganisationId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!requestId) {
      setLoading(false);
      return;
    }
    getOAuthConsent(requestId)
      .then((c) => {
        setConsent(c);
        setOrganisationId(c.defaultOrganisationId);
      })
      .catch((e: unknown) => setError((e as Error).message))
      .finally(() => setLoading(false));
  }, [requestId]);

  // Both answers end the same way: the browser goes back to the client, carrying either the
  // authorization code or the refusal. replace() keeps this page out of the back button.
  const answer = async (approve: boolean) => {
    if (!requestId || (approve && organisationId == null)) return;
    setBusy(true);
    setError(null);
    try {
      const { redirectUri } = approve
        ? await approveOAuthConsent(requestId, organisationId as number)
        : await denyOAuthConsent(requestId);
      window.location.replace(redirectUri);
    } catch (e: unknown) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  const shell = (children: React.ReactNode) => (
    <div className="flex min-h-screen items-center justify-center bg-gray-100 p-4">
      <div className="w-full max-w-md rounded-lg border border-gray-200 bg-surface p-6 shadow-sm">
        {children}
      </div>
    </div>
  );

  if (loading) {
    return shell(<p className="text-sm text-gray-500">Loading…</p>);
  }

  if (upstreamError || !requestId || !consent) {
    return shell(
      <>
        <h1 className="text-lg font-semibold text-gray-900">This request cannot be granted</h1>
        <p className="mt-2 text-sm text-gray-600">
          {upstreamDescription ??
            error ??
            'The authorization request is missing, already answered, or expired.'}
        </p>
        {upstreamError && (
          <p className="mt-1 text-xs text-gray-500">Error code: {upstreamError}</p>
        )}
        <p className="mt-4 text-sm text-gray-600">
          Start again from the application you were connecting.
        </p>
        <a
          href="/"
          className="mt-4 inline-block rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
        >
          Back to Sortiment
        </a>
      </>,
    );
  }

  return shell(
    <>
      <h1 className="text-lg font-semibold text-gray-900">Give an application access?</h1>

      <div className="mt-4 rounded-md border border-gray-200 bg-gray-50 p-3 text-sm">
        <p className="text-gray-800">
          An application calling itself{' '}
          <strong>{consent.clientName?.trim() || 'an unnamed client'}</strong> is asking to read
          your parts catalogue.
        </p>
        <p className="mt-2 text-gray-600">
          It will be sent back to <strong className="break-all">{consent.redirectHost}</strong>. If
          you do not recognise that address, refuse.
        </p>
      </div>

      <div className="mt-4 text-sm text-gray-700">
        <p className="font-medium text-gray-900">What it will be able to do</p>
        <ul className="mt-1 space-y-1 text-gray-600">
          <li className="flex items-start gap-2">
            <Tick />
            Read parts, specifications, stock levels, categories and locations
          </li>
          <li className="flex items-start gap-2">
            <Cross />
            Nothing else — it cannot add, change or delete anything, or move stock
          </li>
        </ul>
      </div>

      {consent.organisations.length > 1 ? (
        <label className="mt-4 block text-sm">
          <span className="font-medium text-gray-900">Organisation it may read</span>
          <select
            value={organisationId ?? ''}
            onChange={(e) => setOrganisationId(Number(e.target.value))}
            className="mt-1 w-full rounded-md border border-gray-300 bg-surface px-3 py-2 text-sm text-gray-900"
          >
            {consent.organisations.map((org) => (
              <option key={org.id} value={org.id}>
                {org.name}
              </option>
            ))}
          </select>
          <span className="mt-1 block text-xs text-gray-500">
            Access is limited to this one organisation, and cannot be switched afterwards.
          </span>
        </label>
      ) : (
        <p className="mt-4 text-sm text-gray-600">
          It will read{' '}
          <strong>{consent.organisations[0]?.name ?? 'your organisation'}</strong> only, as{' '}
          {user?.email}.
        </p>
      )}

      {error && <p className="mt-3 text-sm text-red-600">{error}</p>}

      <div className="mt-6 flex gap-2">
        <button
          type="button"
          onClick={() => answer(true)}
          disabled={busy}
          className="flex-1 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {busy ? 'Working…' : 'Allow access'}
        </button>
        <button
          type="button"
          onClick={() => answer(false)}
          disabled={busy}
          className="flex-1 rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
        >
          Refuse
        </button>
      </div>

      <p className="mt-4 text-xs text-gray-500">
        You can withdraw this at any time from My Account → AI access.
      </p>
    </>,
  );
}

function Tick() {
  return (
    <svg
      className="mt-0.5 h-4 w-4 shrink-0 text-green-600"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M20 6 9 17l-5-5" />
    </svg>
  );
}

function Cross() {
  return (
    <svg
      className="mt-0.5 h-4 w-4 shrink-0 text-gray-400"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  );
}
