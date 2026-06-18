import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { getDashboard } from '../api';
import type { Dashboard } from '../api/types';

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

interface StatCardProps {
  label: string;
  value: number | string;
  to: string;
  color: string;
  icon: ReactNode;
}

function StatCard({ label, value, to, color, icon }: StatCardProps) {
  return (
    <Link
      to={to}
      className={`flex flex-col gap-2 rounded-xl p-6 shadow-sm transition-transform hover:scale-105 ${color}`}
    >
      <div className="flex items-center justify-between">
        <span className="text-3xl">{icon}</span>
        <span className="text-4xl font-bold">{value}</span>
      </div>
      <span className="text-sm font-medium opacity-80">{label}</span>
    </Link>
  );
}

export default function DashboardPage() {
  const [stats, setStats] = useState<Dashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getDashboard()
      .then(setStats)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="p-8">
      <h1 className="mb-8 text-2xl font-bold text-gray-900">Dashboard</h1>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {stats && (
        <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
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
            value={Number(stats.totalStockValue ?? 0).toLocaleString(undefined, {
              minimumFractionDigits: 2,
              maximumFractionDigits: 2,
            })}
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
        </div>
      )}
    </div>
  );
}
