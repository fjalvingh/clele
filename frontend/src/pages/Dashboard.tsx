import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getDashboard } from '../api';
import type { Dashboard } from '../api/types';

interface StatCardProps {
  label: string;
  value: number;
  to: string;
  color: string;
  icon: string;
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
            icon="🔧"
          />
          <StatCard
            label="Categories"
            value={stats.totalCategories}
            to="/categories"
            color="bg-purple-50 text-purple-900"
            icon="📁"
          />
          <StatCard
            label="Locations"
            value={stats.totalLocations}
            to="/locations"
            color="bg-green-50 text-green-900"
            icon="📍"
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
