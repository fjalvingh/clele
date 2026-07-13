import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import loginPhoto from '../assets/clele.jpg';

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const from = (location.state as { from?: string } | null)?.from ?? '/';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login(email.trim(), password);
      navigate(from, { replace: true });
    } catch (err: unknown) {
      setError((err as Error).message);
    } finally {
      setSubmitting(false);
    }
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
        <form
          onSubmit={handleSubmit}
          className="w-full max-w-sm rounded-xl bg-surface p-8 shadow-2xl"
        >
          <div className="mb-6 flex items-center gap-2">
            <svg
              className="h-7 w-7 text-blue-600"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.75"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <rect x="6" y="6" width="12" height="12" rx="1.5" />
              <rect x="9.5" y="9.5" width="5" height="5" rx="0.5" />
              <line x1="9" y1="2" x2="9" y2="6" />
              <line x1="15" y1="2" x2="15" y2="6" />
              <line x1="9" y1="18" x2="9" y2="22" />
              <line x1="15" y1="18" x2="15" y2="22" />
              <line x1="2" y1="9" x2="6" y2="9" />
              <line x1="2" y1="15" x2="6" y2="15" />
              <line x1="18" y1="9" x2="22" y2="9" />
              <line x1="18" y1="15" x2="22" y2="15" />
            </svg>
            <h1 className="text-xl font-bold tracking-tight text-gray-900">Clele</h1>
          </div>
          <h2 className="mb-6 text-sm font-medium text-gray-500">Sign in to continue</h2>

          <label className="block text-sm font-medium text-gray-700">Email</label>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="username"
            required
            className="mt-1 mb-4 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />

          <label className="block text-sm font-medium text-gray-700">Password</label>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
            className="mt-1 mb-4 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />

          {error && <p className="mb-4 text-sm text-red-600">{error}</p>}

          <button
            type="submit"
            disabled={submitting || !email.trim() || !password}
            className="w-full rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </div>
    </div>
  );
}
