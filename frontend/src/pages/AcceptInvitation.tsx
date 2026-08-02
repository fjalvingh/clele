import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { acceptInvitation, declineInvitation, getInvitationByToken } from '../api';
import { PERMISSIONS, type PublicInvitation } from '../api/types';
import loginPhoto from '../assets/clele.jpg';

const permLabel = (key: string) => PERMISSIONS.find((p) => p.key === key)?.label ?? key;

/**
 * The invitee's page, reached from the link in the invitation mail. Public by design: whoever
 * follows it may have no account at all, and the token in the URL is the only credential.
 *
 * <p>When there is no account yet, accepting is also the sign-up: name, phone number and a password
 * are asked for and required. For an existing account nothing is asked — accepting just adds the
 * membership, and the page sends them to the login screen.
 */
export default function AcceptInvitationPage() {
  const { token = '' } = useParams();
  const navigate = useNavigate();

  const [invitation, setInvitation] = useState<PublicInvitation | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState<'ACCEPTED' | 'DECLINED' | null>(null);

  const [fullName, setFullName] = useState('');
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');

  useEffect(() => {
    getInvitationByToken(token)
      .then(setInvitation)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [token]);

  const handleAccept = async (e: React.FormEvent) => {
    e.preventDefault();
    if (invitation?.newAccount && password !== confirmPassword) {
      setError('The two passwords do not match');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const result = await acceptInvitation(token, {
        fullName: fullName.trim(),
        phone: phone.trim(),
        password,
      });
      setInvitation(result);
      setDone('ACCEPTED');
    } catch (err: unknown) {
      setError((err as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  const handleDecline = async () => {
    if (!confirm('Refuse this invitation? The link stops working once you do.')) return;
    setSubmitting(true);
    setError(null);
    try {
      const result = await declineInvitation(token);
      setInvitation(result);
      setDone('DECLINED');
    } catch (err: unknown) {
      setError((err as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  const body = () => {
    if (loading) return <p className="text-gray-500">Loading…</p>;

    if (!invitation) {
      return (
        <>
          <h1 className="mb-2 text-xl font-bold text-gray-900">Invitation not found</h1>
          <p className="mb-6 text-sm text-gray-600">
            {error ?? 'This invitation link is not valid.'}
          </p>
          <Link to="/login" className="text-sm font-medium text-blue-600 hover:underline">
            Go to sign in
          </Link>
        </>
      );
    }

    if (done === 'ACCEPTED') {
      return (
        <>
          <h1 className="mb-2 text-xl font-bold text-gray-900">
            Welcome to {invitation.organisationName}
          </h1>
          <p className="mb-6 text-sm text-gray-600">
            You are now a member. Sign in with <strong>{invitation.email}</strong> to get started.
          </p>
          <button
            onClick={() => navigate('/login')}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            Sign in
          </button>
        </>
      );
    }

    if (done === 'DECLINED') {
      return (
        <>
          <h1 className="mb-2 text-xl font-bold text-gray-900">Invitation refused</h1>
          <p className="text-sm text-gray-600">
            Nothing was changed and no account was created. You can close this page.
          </p>
        </>
      );
    }

    if (!invitation.open) {
      return (
        <>
          <h1 className="mb-2 text-xl font-bold text-gray-900">This invitation is closed</h1>
          <p className="mb-6 text-sm text-gray-600">
            {invitation.expired
              ? 'It has expired. Ask whoever invited you to send a new one.'
              : invitation.status === 'ACCEPTED'
                ? 'It has already been accepted.'
                : invitation.status === 'DECLINED'
                  ? 'It has already been refused.'
                  : 'It was withdrawn by an administrator.'}
          </p>
          <Link to="/login" className="text-sm font-medium text-blue-600 hover:underline">
            Go to sign in
          </Link>
        </>
      );
    }

    return (
      <form onSubmit={handleAccept}>
        <h1 className="mb-2 text-xl font-bold text-gray-900">
          Join {invitation.organisationName}
        </h1>
        <p className="mb-4 text-sm text-gray-600">
          {invitation.invitedByName ?? 'An administrator'} invited{' '}
          <strong>{invitation.email}</strong> to join{' '}
          <strong>{invitation.organisationName}</strong> in Sortiment.
        </p>

        <div className="mb-6 rounded-lg bg-gray-50 px-4 py-3 text-sm text-gray-700">
          <span className="font-medium">You will be able to: </span>
          {invitation.permissions.length
            ? invitation.permissions.map(permLabel).join(', ')
            : 'view the catalogue (no editing rights)'}
        </div>

        {invitation.newAccount && (
          <>
            <p className="mb-4 text-sm text-gray-600">
              You have no account yet — fill these in and one is created for you.
            </p>
            <label className="mb-3 block">
              <span className="mb-1 block text-sm font-medium text-gray-700">Full name *</span>
              <input
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                required
                className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
              />
            </label>
            <label className="mb-3 block">
              <span className="mb-1 block text-sm font-medium text-gray-700">Phone number *</span>
              <input
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                required
                className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
              />
            </label>
            <label className="mb-3 block">
              <span className="mb-1 block text-sm font-medium text-gray-700">Password *</span>
              <input
                type="password"
                autoComplete="new-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
              />
            </label>
            <label className="mb-4 block">
              <span className="mb-1 block text-sm font-medium text-gray-700">
                Confirm password *
              </span>
              <input
                type="password"
                autoComplete="new-password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                required
                className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
              />
            </label>
          </>
        )}

        {error && <p className="mb-3 text-sm text-red-600">{error}</p>}

        <div className="flex items-center justify-between">
          <button
            type="button"
            onClick={handleDecline}
            disabled={submitting}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50 disabled:opacity-50"
          >
            Refuse
          </button>
          <button
            type="submit"
            disabled={submitting}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {submitting ? 'Working…' : 'Accept invitation'}
          </button>
        </div>
      </form>
    );
  };

  return (
    <div className="flex min-h-screen bg-gray-100">
      <div
        className="relative hidden w-1/2 flex-col justify-end bg-cover bg-center p-12 md:flex"
        style={{ backgroundImage: `url(${loginPhoto})` }}
      >
        <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-black/20 to-transparent" />
        <div className="relative max-w-md text-white">
          <h2 className="text-2xl font-bold tracking-tight">Know what's on the bench.</h2>
          <p className="mt-2 text-sm text-teal-50/80">
            Track parts, stock and locations across your whole workshop — from a single reel of
            resistors to a full cabinet of modules.
          </p>
        </div>
      </div>

      <div className="flex w-full items-center justify-center p-4 md:w-1/2">
        <div className="w-full max-w-sm rounded-xl bg-surface p-8 shadow-2xl">{body()}</div>
      </div>
    </div>
  );
}
