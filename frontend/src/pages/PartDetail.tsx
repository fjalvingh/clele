import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  createStockEntry,
  deletePartImage,
  deleteStockEntry,
  getLocations,
  getPart,
  getPartImages,
  getPartStock,
  partImageUrl,
  updateStockEntry,
  uploadPartImage,
} from '../api';
import type { Location, Part, PartImage, StockEntry, StockEntryRequest } from '../api/types';
import Badge from '../components/Badge';
import DataTable from '../components/DataTable';
import type { Column } from '../components/DataTable';
import FormField from '../components/FormField';
import Modal from '../components/Modal';

const emptyStockForm = (partId: number): StockEntryRequest => ({
  partId,
  locationId: 0,
  quantity: 0,
  minimumQuantity: 0,
});

export default function PartDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const partId = Number(id);

  const [part, setPart] = useState<Part | null>(null);
  const [stock, setStock] = useState<StockEntry[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [images, setImages] = useState<PartImage[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [stockModalOpen, setStockModalOpen] = useState(false);
  const [editingStock, setEditingStock] = useState<StockEntry | null>(null);
  const [stockForm, setStockForm] = useState<StockEntryRequest>(emptyStockForm(partId));
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const loadData = () => {
    Promise.all([getPart(partId), getPartStock(partId), getLocations(), getPartImages(partId)])
      .then(([p, s, l, imgs]) => {
        setPart(p);
        setStock(s);
        setLocations(l);
        setImages(imgs);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(loadData, [partId]);

  const openAddStock = () => {
    setEditingStock(null);
    setStockForm(emptyStockForm(partId));
    setFormError(null);
    setStockModalOpen(true);
  };

  const openEditStock = (entry: StockEntry) => {
    setEditingStock(entry);
    setStockForm({
      partId,
      locationId: entry.locationId,
      quantity: entry.quantity,
      minimumQuantity: entry.minimumQuantity,
      unitPrice: entry.unitPrice,
    });
    setFormError(null);
    setStockModalOpen(true);
  };

  const handleSaveStock = async () => {
    setSaving(true);
    setFormError(null);
    try {
      if (editingStock) {
        await updateStockEntry(editingStock.id, stockForm);
      } else {
        await createStockEntry(stockForm);
      }
      setStockModalOpen(false);
      loadData();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleDeleteStock = async (entry: StockEntry) => {
    if (!confirm(`Remove stock at "${entry.locationName}"?`)) return;
    try {
      await deleteStockEntry(entry.id);
      loadData();
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const handleImageUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    e.target.value = '';
    setUploading(true);
    setUploadError(null);
    try {
      await uploadPartImage(partId, file);
      const imgs = await getPartImages(partId);
      setImages(imgs);
    } catch (err: unknown) {
      setUploadError((err as Error).message);
    } finally {
      setUploading(false);
    }
  };

  const handleDeleteImage = async (image: PartImage) => {
    if (!confirm('Remove this image?')) return;
    try {
      await deletePartImage(partId, image.id);
      const imgs = await getPartImages(partId);
      setImages(imgs);
    } catch (err: unknown) {
      alert((err as Error).message);
    }
  };

  const stockColumns: Column<StockEntry>[] = [
    { key: 'locationName', header: 'Location' },
    {
      key: 'quantity',
      header: 'Quantity',
      render: (row) =>
        row.lowStock ? (
          <Badge variant="red">{row.quantity}</Badge>
        ) : (
          <Badge variant="green">{row.quantity}</Badge>
        ),
    },
    { key: 'minimumQuantity', header: 'Min Qty' },
    {
      key: 'unitPrice',
      header: 'Unit Price',
      render: (row) =>
        row.unitPrice != null ? (
          <span className="font-mono text-sm">{Number(row.unitPrice).toFixed(2)}</span>
        ) : (
          <span className="text-gray-400">—</span>
        ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) =>
        row.lowStock ? (
          <Badge variant="red">Low Stock</Badge>
        ) : (
          <Badge variant="green">OK</Badge>
        ),
    },
  ];

  if (loading) return <div className="p-8 text-gray-500">Loading...</div>;
  if (error) return <div className="p-8 text-red-600">{error}</div>;
  if (!part) return null;

  const primaryImage = images[0] ?? null;

  return (
    <div className="p-8">
      {/* Breadcrumb */}
      <nav className="mb-4 text-sm text-gray-500">
        <Link to="/parts" className="hover:underline">
          Parts
        </Link>{' '}
        / <span className="text-gray-800 font-medium">{part.name}</span>
      </nav>

      {/* Part header card — image left, details right */}
      <div className="mb-6 rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
        <div className="flex gap-6">
          {/* Image column */}
          <div className="flex shrink-0 flex-col gap-2">
            {/* Primary image */}
            <div className="flex h-52 w-52 items-center justify-center overflow-hidden rounded-lg border border-gray-200 bg-gray-50">
              {primaryImage ? (
                <img
                  src={partImageUrl(partId, primaryImage.id)}
                  alt={part.name}
                  className="h-full w-full object-contain"
                />
              ) : (
                <span className="text-4xl text-gray-300">🔧</span>
              )}
            </div>

            {/* Thumbnail strip */}
            {images.length > 0 && (
              <div className="flex flex-wrap gap-1">
                {images.map((img) => (
                  <div key={img.id} className="group relative">
                    <img
                      src={partImageUrl(partId, img.id)}
                      alt=""
                      className={`h-12 w-12 rounded border object-contain ${
                        img.id === primaryImage?.id
                          ? 'border-blue-400'
                          : 'border-gray-200'
                      }`}
                    />
                    <button
                      onClick={() => handleDeleteImage(img)}
                      className="absolute -right-1 -top-1 hidden h-4 w-4 items-center justify-center rounded-full bg-red-500 text-xs text-white group-hover:flex"
                      title="Remove"
                    >
                      ×
                    </button>
                  </div>
                ))}
              </div>
            )}

            {/* Upload button */}
            {images.length < 5 && (
              <>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/*"
                  className="hidden"
                  onChange={handleImageUpload}
                />
                <button
                  onClick={() => fileInputRef.current?.click()}
                  disabled={uploading}
                  className="rounded-lg border border-dashed border-gray-300 px-3 py-1.5 text-xs text-gray-500 hover:border-blue-400 hover:text-blue-600 disabled:opacity-50"
                >
                  {uploading ? 'Uploading…' : `+ Add photo (${images.length}/5)`}
                </button>
                {uploadError && (
                  <p className="text-xs text-red-600">{uploadError}</p>
                )}
              </>
            )}
          </div>

          {/* Details column */}
          <div className="min-w-0 flex-1">
            <div className="flex items-start justify-between">
              <div>
                <h1 className="text-2xl font-bold text-gray-900">{part.name}</h1>
                <p className="mt-1 font-mono text-sm text-gray-500">{part.partNumber}</p>
              </div>
              <button
                onClick={() => navigate('/parts')}
                className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50"
              >
                ← Back
              </button>
            </div>

            <div className="mt-4 grid grid-cols-1 gap-4 text-sm sm:grid-cols-2">
              {part.manufacturer && (
                <div>
                  <span className="font-medium text-gray-500">Manufacturer:</span>{' '}
                  <span className="text-gray-800">{part.manufacturer}</span>
                </div>
              )}
              {part.categoryBreadcrumb && (
                <div>
                  <span className="font-medium text-gray-500">Category:</span>{' '}
                  <span className="text-gray-800">{part.categoryBreadcrumb}</span>
                </div>
              )}
              {part.datasheetUrl && (
                <div>
                  <span className="font-medium text-gray-500">Datasheet:</span>{' '}
                  <a
                    href={part.datasheetUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-blue-600 hover:underline"
                  >
                    View
                  </a>
                </div>
              )}
              {part.description && (
                <div className="sm:col-span-2">
                  <span className="font-medium text-gray-500">Description:</span>{' '}
                  <span className="text-gray-800">{part.description}</span>
                </div>
              )}
            </div>

            {/* Specs */}
            {part.specs && Object.keys(part.specs).length > 0 && (
              <div className="mt-4">
                <h3 className="mb-2 text-sm font-semibold text-gray-700">Specifications</h3>
                <div className="flex flex-wrap gap-2">
                  {Object.entries(part.specs).map(([k, v]) => (
                    <span
                      key={k}
                      className="rounded-full bg-gray-100 px-3 py-1 text-xs text-gray-700"
                    >
                      <span className="font-medium">{k}:</span> {String(v)}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Stock section */}
      <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-gray-900">Stock Locations</h2>
          <button
            onClick={openAddStock}
            className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
          >
            + Add Stock
          </button>
        </div>
        {(() => {
          const priced = stock.filter((s) => s.unitPrice != null);
          if (priced.length === 0) return null;
          const total = priced.reduce((sum, s) => sum + s.quantity * Number(s.unitPrice), 0);
          const partial = priced.length < stock.length;
          return (
            <div className="mb-4 flex items-baseline gap-2 text-sm text-gray-600">
              <span className="font-medium">Total stock value:</span>
              <span className="font-mono text-base font-semibold text-gray-900">
                {total.toFixed(2)}
              </span>
              {partial && (
                <span className="text-xs text-gray-400">(some locations have no price)</span>
              )}
            </div>
          );
        })()}
        <DataTable
          columns={stockColumns}
          data={stock}
          keyExtractor={(s) => s.id}
          emptyMessage="No stock entries. Add this part to a location."
          actions={(entry) => (
            <div className="flex justify-end gap-2">
              <button
                onClick={() => openEditStock(entry)}
                className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
              >
                Edit
              </button>
              <button
                onClick={() => handleDeleteStock(entry)}
                className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50"
              >
                Remove
              </button>
            </div>
          )}
        />
      </div>

      {/* Stock entry modal */}
      <Modal
        open={stockModalOpen}
        onClose={() => setStockModalOpen(false)}
        title={editingStock ? 'Edit Stock Entry' : 'Add Stock Entry'}
      >
        <FormField
          as="select"
          label="Location *"
          value={stockForm.locationId || ''}
          onChange={(e) =>
            setStockForm({ ...stockForm, locationId: Number(e.target.value) })
          }
        >
          <option value="">— Select location —</option>
          {locations.map((l) => (
            <option key={l.id} value={l.id}>
              {l.name}
            </option>
          ))}
        </FormField>
        <FormField
          label="Quantity *"
          type="number"
          min={0}
          value={stockForm.quantity}
          onChange={(e) =>
            setStockForm({ ...stockForm, quantity: Number(e.target.value) })
          }
        />
        <FormField
          label="Minimum Quantity"
          type="number"
          min={0}
          value={stockForm.minimumQuantity}
          onChange={(e) =>
            setStockForm({ ...stockForm, minimumQuantity: Number(e.target.value) })
          }
        />
        <FormField
          label="Unit Price"
          type="number"
          min={0}
          step={0.01}
          placeholder="Optional"
          value={stockForm.unitPrice ?? ''}
          onChange={(e) =>
            setStockForm({
              ...stockForm,
              unitPrice: e.target.value !== '' ? Number(e.target.value) : null,
            })
          }
        />
        {formError && <p className="mb-3 text-sm text-red-600">{formError}</p>}
        <div className="flex justify-end gap-3">
          <button
            onClick={() => setStockModalOpen(false)}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSaveStock}
            disabled={saving || !stockForm.locationId}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </Modal>
    </div>
  );
}
