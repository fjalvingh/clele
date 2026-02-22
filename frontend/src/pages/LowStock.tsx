import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getLowStock } from '../api';
import type { StockEntry } from '../api/types';
import Badge from '../components/Badge';
import DataTable from '../components/DataTable';
import type { Column } from '../components/DataTable';

export default function LowStockPage() {
  const [entries, setEntries] = useState<StockEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getLowStock()
      .then(setEntries)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  const columns: Column<StockEntry>[] = [
    {
      key: 'partNumber',
      header: 'Part Number',
      render: (row) => (
        <Link to={`/parts/${row.partId}`} className="text-blue-600 hover:underline">
          {row.partNumber}
        </Link>
      ),
    },
    { key: 'partName', header: 'Part Name' },
    { key: 'locationName', header: 'Location' },
    {
      key: 'quantity',
      header: 'Quantity',
      render: (row) => (
        <Badge variant="red">{row.quantity}</Badge>
      ),
    },
    {
      key: 'minimumQuantity',
      header: 'Minimum',
      render: (row) => row.minimumQuantity,
    },
    {
      key: 'deficit',
      header: 'Deficit',
      render: (row) => (
        <span className="font-semibold text-red-700">
          {row.minimumQuantity - row.quantity}
        </span>
      ),
    },
  ];

  return (
    <div className="p-8">
      <h1 className="mb-2 text-2xl font-bold text-gray-900">Low Stock Alerts</h1>
      <p className="mb-6 text-sm text-gray-500">
        Parts where current quantity is below the minimum threshold.
      </p>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && (
        <>
          {entries.length === 0 ? (
            <div className="rounded-xl border border-green-200 bg-green-50 p-8 text-center text-green-800">
              <p className="text-2xl">✅</p>
              <p className="mt-2 font-medium">All parts are well stocked!</p>
            </div>
          ) : (
            <DataTable
              columns={columns}
              data={entries}
              keyExtractor={(e) => e.id}
              emptyMessage="No low stock items."
            />
          )}
        </>
      )}
    </div>
  );
}
