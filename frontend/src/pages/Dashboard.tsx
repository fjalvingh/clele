import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { getDashboard, getRecentParts } from '../api';
import {
  SPARSE_SPEC_THRESHOLD,
  type Dashboard,
  type RecentPart,
  type RecentPartsPage,
} from '../api/types';
import { useSettings } from '../settings/SettingsContext';

const iconProps = {
  className: 'h-8 w-8',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  viewBox: '0 0 24 24',
};

// Microchip — represents electronic parts
const PartsIcon = (
  <svg {...iconProps}>
    <rect x="7" y="7" width="10" height="10" rx="1.5" />
    <path d="M10 3v2M14 3v2M10 19v2M14 19v2M3 10h2M3 14h2M19 10h2M19 14h2" />
  </svg>
);

// Map pin — represents locations
const LocationIcon = (
  <svg {...iconProps}>
    <path d="M12 21s7-6.3 7-11a7 7 0 1 0-14 0c0 4.7 7 11 7 11Z" />
    <circle cx="12" cy="10" r="2.5" />
  </svg>
);

// Document with an incomplete last line — represents parts whose specifications are thin
const SparseSpecsIcon = (
  <svg {...iconProps}>
    <path d="M6 3h8l4 4v14H6z" />
    <path d="M14 3v4h4" />
    <path d="M9 12h6M9 16h2" />
  </svg>
);

interface StatCardProps {
  label: string;
  value: number | string;
  to: string;
  color: string;
  icon: ReactNode;
}

// A tile is only ~186px of content wide at xl:grid-cols-5, and ~180px at lg:grid-cols-3 on a
// tablet. The icon used to sit on the same line as the value, leaving it ~149px — less than the
// 161px "€ 6,064.90" needs even at text-3xl, so a money value broke after the currency symbol
// and the digits ran past the edge of the tile. The icon now has its own line, giving the value
// the tile's full width, and the size follows the value's length so a long one still fits on one
// line. break-words is the guard that nothing can spill whatever the number turns out to be;
// tabular-nums keeps the digits from jittering as the figures change.
function valueSizeClass(value: number | string): string {
  const len = String(value).length;
  if (len > 12) return 'text-xl';
  if (len > 10) return 'text-2xl';
  if (len > 6) return 'text-3xl';
  return 'text-4xl';
}

function StatCard({ label, value, to, color, icon }: StatCardProps) {
  return (
    <Link
      to={to}
      className={`flex flex-col gap-2 rounded-xl p-6 shadow-sm transition-transform hover:scale-105 ${color}`}
    >
      <span className="text-3xl">{icon}</span>
      <span className={`break-words font-bold tabular-nums ${valueSizeClass(value)}`}>
        {value}
      </span>
      <span className="text-sm font-medium opacity-80">{label}</span>
    </Link>
  );
}

const RECENT_PAGE_SIZE = 10;

const formatAdded = (iso: string) =>
  new Date(iso).toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });

/**
 * Where a part's stock sits. A part usually lives in one place, but it may be spread over several
 * and it may be catalogued without any stock at all — so the row names the location holding the
 * most and summarises the rest, with the full list in the tooltip rather than a wrapped cell.
 */
function LocationCell({ locations }: { locations: string[] }) {
  if (locations.length === 0) {
    return <span className="text-gray-400">—</span>;
  }
  return (
    <span title={locations.join('\n')}>
      {locations[0]}
      {locations.length > 1 && (
        <span className="ml-1 text-xs text-gray-500">+{locations.length - 1} more</span>
      )}
    </span>
  );
}

/**
 * The newest parts in the catalogue, newest first. Paged rather than capped at a fixed "last N":
 * the question this answers ("what did we just take in?") does not stop at ten, and walking back
 * through it is how a mis-entered part gets found.
 */
function RecentlyAdded() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<RecentPartsPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getRecentParts(page, RECENT_PAGE_SIZE)
      .then((d) => {
        if (cancelled) return;
        setData(d);
        // The backend clamps a page index past the end of a list that shrank; follow it back so
        // the controls keep agreeing with what is on screen.
        if (d.page !== page) setPage(d.page);
        setError(null);
      })
      .catch((e: Error) => !cancelled && setError(e.message))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [page]);

  const total = data?.total ?? 0;
  const items: RecentPart[] = data?.items ?? [];
  const lastPage = total === 0 ? 0 : Math.ceil(total / RECENT_PAGE_SIZE) - 1;
  const first = total === 0 ? 0 : page * RECENT_PAGE_SIZE + 1;
  const last = page * RECENT_PAGE_SIZE + items.length;

  return (
    <div className="mt-10">
      <h2 className="mb-4 text-lg font-semibold text-gray-900">Recently Added</h2>

      {error && <p className="text-red-600">{error}</p>}
      {!error && !loading && items.length === 0 && (
        <p className="text-gray-500">No parts yet.</p>
      )}

      {!error && items.length > 0 && (
        <>
          <div className="max-w-full overflow-x-auto rounded-lg border border-gray-200 shadow-sm">
            <table className="w-full divide-y divide-gray-200 bg-surface text-sm">
              <thead className="bg-blue-50">
                <tr>
                  {['Part', 'Description', 'Location', 'Count', 'Added'].map((h) => (
                    <th
                      key={h}
                      className={`border-b border-blue-100 px-4 py-3 text-xs font-semibold uppercase tracking-wider text-blue-800/80 ${
                        h === 'Count' ? 'text-right' : 'text-left'
                      }`}
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className={`divide-y divide-gray-100 ${loading ? 'opacity-50' : ''}`}>
                {items.map((p) => (
                  <tr key={p.id} className="hover:bg-gray-50">
                    <td className="whitespace-nowrap px-4 py-3 font-medium">
                      <Link to={`/parts/${p.id}`} className="text-blue-700 hover:underline">
                        {p.partNumber}
                      </Link>
                    </td>
                    <td className="px-4 py-3 text-gray-700">{p.description}</td>
                    <td className="px-4 py-3 text-gray-700">
                      <LocationCell locations={p.locations} />
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-right font-mono text-gray-700">
                      {p.totalQuantity}
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-gray-700">
                      {formatAdded(p.createdAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="mt-3 flex items-center gap-4 text-sm text-gray-600">
            <span>
              {first}–{last} of {total}
            </span>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => setPage((n) => Math.max(0, n - 1))}
                disabled={page === 0 || loading}
                className="rounded border border-gray-300 px-3 py-1 hover:bg-gray-50 disabled:opacity-40"
              >
                Previous
              </button>
              <button
                type="button"
                onClick={() => setPage((n) => n + 1)}
                disabled={page >= lastPage || loading}
                className="rounded border border-gray-300 px-3 py-1 hover:bg-gray-50 disabled:opacity-40"
              >
                Next
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

export default function DashboardPage() {
  const [stats, setStats] = useState<Dashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const { formatMoney } = useSettings();

  useEffect(() => {
    getDashboard()
      .then(setStats)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="p-4 md:p-8">
      <h1 className="mb-8 text-2xl font-bold text-gray-900">Dashboard</h1>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {stats && (
        <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
          <StatCard
            label="Total Parts"
            value={stats.totalParts}
            to="/parts"
            color="bg-blue-50 text-blue-900"
            icon={PartsIcon}
          />
          <StatCard
            label="Locations"
            value={stats.totalLocations}
            to="/locations"
            color="bg-green-50 text-green-900"
            icon={LocationIcon}
          />
          <StatCard
            label="Total Stock Value"
            value={formatMoney(stats.totalStockValue, { whole: true })}
            to="/parts"
            color="bg-amber-50 text-amber-900"
            icon="💰"
          />
          <StatCard
            label="Low Stock Alerts"
            value={stats.lowStockCount}
            to="/low-stock"
            color={
              stats.lowStockCount > 0
                ? 'bg-red-50 text-red-900'
                : 'bg-gray-50 text-gray-900'
            }
            icon="⚠️"
          />
          {/* Parts that arrived without specifications. Links to the Parts screen with the same
              filter applied, so the number here and the list there always agree. */}
          <StatCard
            label={`Parts with under ${SPARSE_SPEC_THRESHOLD} specs`}
            value={stats.sparseSpecCount}
            to="/parts?sparse=1"
            color={
              stats.sparseSpecCount > 0
                ? 'bg-amber-50 text-amber-900'
                : 'bg-gray-50 text-gray-900'
            }
            icon={SparseSpecsIcon}
          />
        </div>
      )}

      {stats && stats.perLocation.length > 0 && (
        <div className="mt-10">
          <h2 className="mb-4 text-lg font-semibold text-gray-900">By Location</h2>
          <div className="inline-block max-w-full overflow-x-auto rounded-lg border border-gray-200 shadow-sm">
            <table className="w-auto divide-y divide-gray-200 bg-surface text-sm">
              <thead className="bg-blue-50">
                <tr>
                  {['Location', 'Sub-locations', 'Parts', 'On Hand', 'Stock Value'].map((h, i) => (
                    <th
                      key={h}
                      className={`border-b border-blue-100 px-4 py-3 text-xs font-semibold uppercase tracking-wider text-blue-800/80 ${
                        i === 0 ? 'text-left' : 'text-right'
                      }`}
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {stats.perLocation.map((l) => (
                  <tr key={l.locationId} className="hover:bg-gray-50">
                    <td className="whitespace-nowrap px-4 py-3 font-medium text-gray-800">
                      {l.locationName}
                    </td>
                    <td className="px-4 py-3 text-right text-gray-700">{l.locations}</td>
                    <td className="px-4 py-3 text-right text-gray-700">{l.parts}</td>
                    <td className="px-4 py-3 text-right font-mono text-gray-700">
                      {l.totalQuantity}
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-gray-700">
                      {formatMoney(l.totalStockValue)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <RecentlyAdded />
    </div>
  );
}
