import { type ReactNode } from 'react';

export interface Column<T> {
  key: string;
  header: string;
  render?: (row: T) => ReactNode;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  data: T[];
  keyExtractor: (row: T) => string | number;
  actions?: (row: T) => ReactNode;
  emptyMessage?: string;
  /** Size the table to its content (columns take their natural width) instead of filling the row. */
  autoWidth?: boolean;
}

export default function DataTable<T>({
  columns,
  data,
  keyExtractor,
  actions,
  emptyMessage = 'No data found.',
  autoWidth = false,
}: DataTableProps<T>) {
  return (
    <div
      className={`overflow-x-auto rounded-xl bg-white ring-1 ring-gray-200 shadow-sm ${
        autoWidth ? 'inline-block max-w-full align-top' : ''
      }`}
    >
      <table
        className={`${
          autoWidth ? 'w-auto' : 'min-w-full'
        } divide-y divide-gray-200 text-sm`}
      >
        <thead className="sticky top-0 z-10 bg-blue-50/95 backdrop-blur">
          <tr>
            {columns.map((col) => (
              <th
                key={col.key}
                className="border-b border-blue-100 px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-blue-800/80"
              >
                {col.header}
              </th>
            ))}
            {actions && (
              <th className="border-b border-blue-100 px-4 py-3 text-right text-xs font-semibold uppercase tracking-wider text-blue-800/80">
                Actions
              </th>
            )}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {data.length === 0 ? (
            <tr>
              <td
                colSpan={columns.length + (actions ? 1 : 0)}
                className="px-4 py-12 text-center text-gray-400"
              >
                {emptyMessage}
              </td>
            </tr>
          ) : (
            data.map((row) => (
              <tr key={keyExtractor(row)} className="transition-colors hover:bg-blue-50/40">
                {columns.map((col) => (
                  <td key={col.key} className="px-4 py-3 text-gray-700">
                    {col.render
                      ? col.render(row)
                      : String((row as Record<string, unknown>)[col.key] ?? '')}
                  </td>
                ))}
                {actions && (
                  <td className="px-4 py-3 text-right">{actions(row)}</td>
                )}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
