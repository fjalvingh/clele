import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  applyOctopart,
  createStockEntry,
  deletePartImage,
  deleteStockEntry,
  getMyLocations,
  getOctopartUsage,
  getPart,
  getPartImages,
  getPartMovements,
  getPartStock,
  getSpecDefinitions,
  partImageUrl,
  searchOctopart,
  searchPartImages,
  updateStockEntry,
  uploadPartImage,
} from '../api';
import type {
  ImageSuggestion,
  Location,
  OctopartApplyRequest,
  OctopartResult,
  OctopartUsage,
  Part,
  PartImage,
  SpecDefinition,
  StockEntry,
  StockEntryRequest,
  StockMovement,
} from '../api/types';
import { MAJOR_TYPES } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useSettings } from '../settings/SettingsContext';
import Badge from '../components/Badge';
import DataTable from '../components/DataTable';
import type { Column } from '../components/DataTable';
import FormField from '../components/FormField';
import Modal from '../components/Modal';
import PrintLabelModal from '../components/PrintLabelModal';

const emptyStockForm = (partId: number): StockEntryRequest => ({
  partId,
  locationId: 0,
  quantity: 0,
  minimumQuantity: 0,
});

// Proxy external images through our backend to avoid CORS / Cloudflare bot-protection issues.
function displayUrl(img: { url: string; thumbnailUrl?: string }) {
  const src = img.thumbnailUrl ?? img.url;
  return `${import.meta.env.BASE_URL}api/image-proxy?url=${encodeURIComponent(src)}`;
}

// Render a spec value as a display string for a table cell.
function formatSpecValue(spec: SpecDefinition, value: string): string {
  if (spec.dataType === 'BOOLEAN') {
    return value === 'true' ? '✓' : '✗';
  }
  if (spec.dataType === 'NUMBER') {
    const units = spec.unit ? spec.unit.split(',').map((s) => s.trim()) : [];
    // Multi-unit: value already contains the chosen unit (e.g. "64 KB") — display as-is
    // Single unit: append the fixed unit suffix
    return units.length > 1 ? value : units[0] ? `${value} ${units[0]}` : value;
  }
  return value;
}

// Real part columns that an OctoPart result can change. Each must be confirmed (per-field
// checkbox) before it overwrites the existing value. Specs are applied wholesale, separately.
const OCTOPART_FIELDS = [
  { key: 'mpn', label: 'MPN' },
  { key: 'manufacturer', label: 'Manufacturer' },
  { key: 'description', label: 'Description' },
  { key: 'footprint', label: 'Footprint' },
  { key: 'datasheetUrl', label: 'Datasheet URL' },
] as const;

type OctopartFieldKey = (typeof OCTOPART_FIELDS)[number]['key'];

export default function PartDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user, hasPermission } = useAuth();
  const { formatMoney } = useSettings();
  const canEdit = hasPermission('PARTS_EDIT');
  const partId = Number(id);

  const [part, setPart] = useState<Part | null>(null);
  const [stock, setStock] = useState<StockEntry[]>([]);
  const [movements, setMovements] = useState<StockMovement[]>([]);
  const [movementsOpen, setMovementsOpen] = useState(false);
  const [locations, setLocations] = useState<Location[]>([]);
  const [images, setImages] = useState<PartImage[]>([]);
  const [selectedImageId, setSelectedImageId] = useState<number | null>(null);
  const [specDefs, setSpecDefs] = useState<SpecDefinition[]>([]);
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

  // "Find image" modal — same image search/attach flow used by Quick Add.
  const [imageModalOpen, setImageModalOpen] = useState(false);
  const [imageQuery, setImageQuery] = useState('');
  const [imageSuggestions, setImageSuggestions] = useState<ImageSuggestion[]>([]);
  const [imagesLoading, setImagesLoading] = useState(false);
  const [selectedImageUrls, setSelectedImageUrls] = useState<Set<string>>(new Set());
  const [failedImageUrls, setFailedImageUrls] = useState<Set<string>>(new Set());
  const [attaching, setAttaching] = useState(false);
  const [attachError, setAttachError] = useState<string | null>(null);

  // OctoPart (Nexar) enrichment — search/pick/confirm.
  const [octoModalOpen, setOctoModalOpen] = useState(false);
  const [octoUsage, setOctoUsage] = useState<OctopartUsage | null>(null);
  const [octoQuery, setOctoQuery] = useState('');
  const [octoResults, setOctoResults] = useState<OctopartResult[]>([]);
  const [octoLoading, setOctoLoading] = useState(false);
  const [octoError, setOctoError] = useState<string | null>(null);
  const [octoPicked, setOctoPicked] = useState<OctopartResult | null>(null);
  const [octoAccept, setOctoAccept] = useState<Record<OctopartFieldKey, boolean>>(
    {} as Record<OctopartFieldKey, boolean>,
  );
  const [octoApplying, setOctoApplying] = useState(false);

  const [printModalOpen, setPrintModalOpen] = useState(false);

  const loadData = () => {
    Promise.all([getPart(partId), getPartStock(partId), getMyLocations(), getPartImages(partId)])
      .then(([p, s, l, imgs]) => {
        setPart(p);
        setStock(s);
        setLocations(l);
        setImages(imgs);
        // Movement history is supplementary — load best-effort, don't fail the page
        getPartMovements(partId)
          .then(setMovements)
          .catch(() => setMovements([]));
        // Match against the full definition list (every key has a name + majorType),
        // not the category-scoped subset. Best-effort — don't fail the page if unavailable.
        getSpecDefinitions()
          .then(setSpecDefs)
          .catch(() => setSpecDefs([]));
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(loadData, [partId]);

  // Load the user's OctoPart quota once we know the part has no link yet and the user can edit.
  useEffect(() => {
    if (canEdit && part && !part.octopartId && user?.hasOctopartCredentials) {
      getOctopartUsage().then(setOctoUsage).catch(() => setOctoUsage(null));
    }
  }, [canEdit, part, user?.hasOctopartCredentials]);

  const runOctopartSearch = (q: string) => {
    if (!q.trim()) return;
    setOctoLoading(true);
    setOctoError(null);
    setOctoResults([]);
    setOctoPicked(null);
    searchOctopart(q.trim())
      .then((results) => {
        setOctoResults(results);
        // A search spends one request — refresh the remaining count.
        getOctopartUsage().then(setOctoUsage).catch(() => {});
      })
      .catch((err) => {
        setOctoError((err as Error).message);
        getOctopartUsage().then(setOctoUsage).catch(() => {});
      })
      .finally(() => setOctoLoading(false));
  };

  const openOctopart = () => {
    const q = part?.mpn || part?.partNumber || part?.name || '';
    setOctoQuery(q);
    setOctoResults([]);
    setOctoPicked(null);
    setOctoError(null);
    setOctoModalOpen(true);
  };

  const pickOctopartResult = (result: OctopartResult) => {
    setOctoPicked(result);
    // Default every changed column to accepted (ticked).
    const accept = {} as Record<OctopartFieldKey, boolean>;
    for (const f of OCTOPART_FIELDS) {
      accept[f.key] = true;
    }
    setOctoAccept(accept);
  };

  const handleApplyOctopart = async () => {
    if (!octoPicked || !part) return;
    setOctoApplying(true);
    setOctoError(null);
    try {
      const body: OctopartApplyRequest = {
        octopartId: octoPicked.octopartId,
        specs: octoPicked.specs,
      };
      for (const f of OCTOPART_FIELDS) {
        const newVal = octoPicked[f.key];
        if (octoAccept[f.key] && newVal) {
          body[f.key] = newVal;
        }
      }
      await applyOctopart(part.id, body);
      setOctoModalOpen(false);
      loadData();
    } catch (err) {
      setOctoError((err as Error).message);
    } finally {
      setOctoApplying(false);
    }
  };

  const openAddStock = () => {
    setEditingStock(null);
    // Default to the user's default location when it is one of their own locations.
    const defaultLoc =
      user?.defaultLocationId && locations.some((l) => l.id === user.defaultLocationId)
        ? user.defaultLocationId
        : 0;
    setStockForm({ ...emptyStockForm(partId), locationId: defaultLoc });
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

  const runImageSearch = (q: string) => {
    if (!q.trim()) return;
    setImagesLoading(true);
    setImageSuggestions([]);
    setSelectedImageUrls(new Set());
    setFailedImageUrls(new Set());
    searchPartImages(q.trim())
      .then(setImageSuggestions)
      .catch(() => setImageSuggestions([]))
      .finally(() => setImagesLoading(false));
  };

  const openFindImage = () => {
    const q = part?.partNumber ?? '';
    setImageQuery(q);
    setAttachError(null);
    setImageModalOpen(true);
    runImageSearch(q);
  };

  const toggleImageSelect = (url: string) => {
    setSelectedImageUrls((prev) => {
      const next = new Set(prev);
      if (next.has(url)) next.delete(url);
      else next.add(url);
      return next;
    });
  };

  const handleAttachImages = async () => {
    if (selectedImageUrls.size === 0) return;
    setAttaching(true);
    setAttachError(null);
    // Fetch each selected image via the same-origin proxy, then upload as multipart (same approach
    // as Quick Add) to sidestep CORS / tainted-canvas issues.
    const errors: string[] = [];
    let i = 0;
    for (const originalUrl of selectedImageUrls) {
      try {
        const suggestion = imageSuggestions.find((s) => s.url === originalUrl);
        const proxyUrl = suggestion
          ? displayUrl(suggestion)
          : `${import.meta.env.BASE_URL}api/image-proxy?url=${encodeURIComponent(originalUrl)}`;
        const resp = await fetch(proxyUrl);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const blob = await resp.blob();
        const file = new File([blob], `image-${i}.png`, { type: blob.type || 'image/png' });
        await uploadPartImage(partId, file);
      } catch (err: unknown) {
        errors.push((err as Error).message);
      }
      i++;
    }
    setAttaching(false);

    const imgs = await getPartImages(partId).catch(() => images);
    setImages(imgs);

    if (errors.length > 0) {
      const succeeded = selectedImageUrls.size - errors.length;
      setAttachError(
        `${errors.length} photo(s) failed to attach` +
          (succeeded > 0 ? ` (${succeeded} succeeded)` : '') +
          `: ${errors[0]}` +
          (errors.length > 1 ? ` (and ${errors.length - 1} more)` : ''),
      );
      return;
    }
    setImageModalOpen(false);
  };

  const stockColumns: Column<StockEntry>[] = [
    { key: 'ownerName', header: 'Owner', render: (row) => row.ownerName ?? '—' },
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
          <span className="font-mono text-sm">{formatMoney(row.unitPrice)}</span>
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

  const movementColumns: Column<StockMovement>[] = [
    {
      key: 'quantity',
      header: 'Change',
      render: (m) => (
        <Badge variant={m.quantity >= 0 ? 'green' : 'red'}>
          {m.quantity >= 0 ? `+${m.quantity}` : m.quantity}
        </Badge>
      ),
    },
    {
      key: 'type',
      header: 'Type',
      render: (m) =>
        m.type ? (
          <span className="rounded bg-gray-100 px-1.5 py-0.5 text-xs font-medium text-gray-600">
            {m.type}
          </span>
        ) : (
          <span className="text-gray-400">—</span>
        ),
    },
    { key: 'locationName', header: 'Location', render: (m) => m.locationName ?? '—' },
    {
      key: 'unitPrice',
      header: 'Unit Price',
      render: (m) =>
        m.unitPrice != null ? (
          <span className="whitespace-nowrap font-mono text-sm">
            {formatMoney(m.unitPrice)}
          </span>
        ) : (
          <span className="text-gray-400">—</span>
        ),
    },
    {
      key: 'comments',
      header: 'Comments',
      render: (m) =>
        m.comments ? (
          <span className="text-gray-600">{m.comments}</span>
        ) : (
          <span className="text-gray-400">—</span>
        ),
    },
    {
      key: 'movedAt',
      header: 'Date',
      render: (m) => (
        <span className="whitespace-nowrap text-gray-600">
          {new Date(m.movedAt).toLocaleString()}
        </span>
      ),
    },
    { key: 'createdBy', header: 'By', render: (m) => m.createdBy ?? '—' },
  ];

  if (loading) return <div className="p-8 text-gray-500">Loading...</div>;
  if (error) return <div className="p-8 text-red-600">{error}</div>;
  if (!part) return null;

  const primaryImage =
    images.find((img) => img.id === selectedImageId) ?? images[0] ?? null;

  // Build spec display: use definitions where available, fall back to raw keys for unmatched
  const specDefsMap = new Map(specDefs.map((d) => [d.jsonName, d]));
  const partSpecs = part.specs ?? {};

  // Defined specs that have a value
  const definedSpecEntries = specDefs
    .filter((d) => partSpecs[d.jsonName] !== undefined && partSpecs[d.jsonName] !== '')
    .map((d) => ({ label: d.name, value: formatSpecValue(d, partSpecs[d.jsonName]), majorType: d.majorType }));

  // Raw keys not covered by any definition
  const unmatchedEntries = Object.entries(partSpecs).filter(
    ([k, v]) => !specDefsMap.has(k) && v !== ''
  );

  // Group every spec row into its major type; unmatched raw keys fall under TECHNICAL.
  const specRows = [
    ...definedSpecEntries,
    ...unmatchedEntries.map(([k, v]) => ({ label: k, value: String(v), majorType: 'TECHNICAL' })),
  ];
  const specGroups = MAJOR_TYPES.map((t) => ({
    label: t.label,
    rows: specRows.filter((r) => (r.majorType ?? 'TECHNICAL') === t.key),
  }));
  const hasSpecs = specRows.length > 0;

  return (
    <div className="p-8">
      {/* Constrain content width — full-bleed cards look lost on wide monitors. */}
      <div className="mx-auto max-w-6xl">
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
                <svg
                  viewBox="0 0 24 24"
                  className="h-16 w-16 text-gray-300"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.4"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                >
                  <rect x="7" y="7" width="10" height="10" rx="1.5" />
                  <path d="M10 3v2M14 3v2M10 19v2M14 19v2M3 10h2M3 14h2M19 10h2M19 14h2" />
                </svg>
              )}
            </div>

            {/* Thumbnail strip */}
            {images.length > 0 && (
              <div className="flex flex-wrap gap-1">
                {images.map((img) => (
                  <div key={img.id} className="group relative">
                    <button
                      type="button"
                      onClick={() => setSelectedImageId(img.id)}
                      className="block"
                      title="Show this photo"
                    >
                      <img
                        src={partImageUrl(partId, img.id)}
                        alt=""
                        className={`h-12 w-12 rounded border object-contain ${
                          img.id === primaryImage?.id
                            ? 'border-blue-400 ring-2 ring-blue-200'
                            : 'border-gray-200 hover:border-gray-400'
                        }`}
                      />
                    </button>
                    {canEdit && (
                      <button
                        onClick={() => handleDeleteImage(img)}
                        className="absolute -right-1 -top-1 hidden h-4 w-4 items-center justify-center rounded-full bg-red-500 text-xs text-white group-hover:flex"
                        title="Remove"
                      >
                        ×
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}

            {/* Find image (only when the part has no images) */}
            {canEdit && images.length === 0 && (
              <button
                onClick={openFindImage}
                className="rounded-lg border border-dashed border-gray-300 px-3 py-1.5 text-xs text-gray-500 hover:border-blue-400 hover:text-blue-600"
              >
                🔍 Find image
              </button>
            )}

            {/* Upload button */}
            {canEdit && images.length < 5 && (
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
              <div className="min-w-0">
                <h1 className="text-2xl font-bold text-gray-900">{part.name}</h1>
                <div className="mt-2 flex flex-wrap items-center gap-2">
                  <span className="rounded-md bg-blue-50 px-2 py-1 font-mono text-xs font-medium text-blue-700 ring-1 ring-inset ring-blue-600/20">
                    {part.partNumber}
                  </span>
                  {part.footprint && (
                    <span className="rounded-md bg-gray-100 px-2 py-1 text-xs font-medium text-gray-600 ring-1 ring-inset ring-gray-500/20">
                      {part.footprint}
                    </span>
                  )}
                </div>
              </div>
              <div className="flex shrink-0 items-center gap-2">
                {/* OctoPart enrichment — only when the part has no link yet */}
                {canEdit && !part.octopartId && (
                  user?.hasOctopartCredentials ? (
                    <div className="flex items-center gap-1.5">
                      <button
                        onClick={openOctopart}
                        disabled={octoUsage != null && octoUsage.remaining <= 0}
                        title={
                          octoUsage != null && octoUsage.remaining <= 0
                            ? 'Monthly OctoPart request limit reached'
                            : 'Look this part up on OctoPart'
                        }
                        className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50 disabled:opacity-50"
                      >
                        🔎 Search OctoPart
                      </button>
                      {octoUsage != null && (
                        <span className="text-xs text-gray-400">
                          {octoUsage.remaining} left this month
                        </span>
                      )}
                    </div>
                  ) : (
                    <Link
                      to="/profile"
                      className="text-xs text-blue-600 hover:underline"
                      title="Set your OctoPart credentials to enable lookups"
                    >
                      Set OctoPart credentials
                    </Link>
                  )
                )}
                <button
                  onClick={() => setPrintModalOpen(true)}
                  title="Print a label for this part"
                  className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50"
                >
                  🏷️ Print label
                </button>
                <button
                  onClick={() => navigate(-1)}
                  className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50"
                >
                  ← Back
                </button>
              </div>
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
              {part.octopartId && (
                <div>
                  <span className="font-medium text-gray-500">OctoPart:</span>{' '}
                  <span className="font-mono text-gray-800">{part.octopartId}</span>
                </div>
              )}
              {part.createdByName && (
                <div>
                  <span className="font-medium text-gray-500">Added by:</span>{' '}
                  <span className="text-gray-800">{part.createdByName}</span>
                </div>
              )}
              {part.description && (
                <div className="sm:col-span-2">
                  <span className="font-medium text-gray-500">Description:</span>{' '}
                  <span className="text-gray-800">{part.description}</span>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Specifications — grouped into three columns by major type */}
      {hasSpecs && (
        <div className="mb-6 rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
          <h2 className="mb-4 flex items-center gap-2 text-lg font-semibold text-gray-900">
            <span className="h-5 w-1 rounded-full bg-blue-500" />
            Specifications
          </h2>
          {/* Each group sizes to its own content (wrapping when the row runs out of
              room) instead of three equal thirds — so short groups stop wasting
              space and the wide Technical group can take what it needs. */}
          <div className="flex flex-col gap-x-12 gap-y-6 md:flex-row md:flex-wrap md:items-start">
            {specGroups
              .filter((group) => group.rows.length > 0)
              .map((group) => (
                <div key={group.label}>
                  <h3 className="mb-2 border-b border-gray-200 pb-1.5 text-xs font-semibold uppercase tracking-wider text-blue-700/80">
                    {group.label}
                  </h3>
                  <table className="w-auto text-sm">
                    <tbody>
                      {group.rows.map((row) => (
                        <tr key={row.label} className="align-top odd:bg-gray-50">
                          <td className="whitespace-nowrap rounded-l-md px-2 py-1.5 pr-3 text-gray-500">{row.label}</td>
                          <td className="max-w-sm break-words rounded-r-md px-2 py-1.5 font-medium text-gray-900">{row.value}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ))}
          </div>
        </div>
      )}

      {/* Stock section */}
      <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="flex items-center gap-2 text-lg font-semibold text-gray-900">
            <span className="h-5 w-1 rounded-full bg-blue-500" />
            Stock Locations
          </h2>
          <button
            onClick={openAddStock}
            className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
          >
            + Add Stock
          </button>
        </div>
        {stock.length > 0 && (
          <div className="mb-5 grid grid-cols-2 gap-3 sm:max-w-md">
            <div className="rounded-lg border border-blue-100 bg-blue-50 px-4 py-3">
              <div className="text-xs font-medium uppercase tracking-wide text-blue-800/70">
                Total on hand
              </div>
              <div className="mt-1 font-mono text-2xl font-semibold text-gray-900">
                {stock.reduce((sum, s) => sum + s.quantity, 0)}
              </div>
              <div className="text-xs text-gray-400">
                across {stock.length} location{stock.length === 1 ? '' : 's'}
              </div>
            </div>
            {(() => {
              const priced = stock.filter((s) => s.unitPrice != null);
              if (priced.length === 0) return null;
              const total = priced.reduce((sum, s) => sum + s.quantity * Number(s.unitPrice), 0);
              const partial = priced.length < stock.length;
              return (
                <div className="rounded-lg border border-gray-200 bg-gray-50 px-4 py-3">
                  <div className="text-xs font-medium uppercase tracking-wide text-gray-500">
                    Total stock value
                  </div>
                  <div className="mt-1 font-mono text-2xl font-semibold text-gray-900">
                    {formatMoney(total)}
                  </div>
                  {partial && (
                    <div className="text-xs text-gray-400">some locations have no price</div>
                  )}
                </div>
              );
            })()}
          </div>
        )}
        <DataTable
          autoWidth
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

      {/* Stock movement history (collapsible) */}
      <div className="mt-6 rounded-xl border border-gray-200 bg-white shadow-sm">
        <button
          onClick={() => setMovementsOpen((o) => !o)}
          className="flex w-full items-center justify-between px-6 py-4 text-left"
        >
          <span className="flex items-center gap-2">
            <span className="h-5 w-1 rounded-full bg-blue-500" />
            <h2 className="text-lg font-semibold text-gray-900">Stock Movements</h2>
            <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
              {movements.length}
            </span>
          </span>
          <span className="text-gray-400">{movementsOpen ? '▲' : '▼'}</span>
        </button>
        {movementsOpen && (
          <div className="border-t border-gray-100 px-6 py-4">
            {movements.length === 0 ? (
              <p className="text-sm text-gray-500">No stock movements recorded for this part.</p>
            ) : (
              <DataTable
                autoWidth
                columns={movementColumns}
                data={movements}
                keyExtractor={(m) => m.id}
              />
            )}
          </div>
        )}
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
        <FormField
          label="Comment"
          placeholder="Optional note for the stock movement"
          value={stockForm.comments ?? ''}
          onChange={(e) =>
            setStockForm({
              ...stockForm,
              comments: e.target.value !== '' ? e.target.value : null,
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

      {/* Find image modal */}
      <Modal open={imageModalOpen} onClose={() => setImageModalOpen(false)} title="Find image">
        <div className="mb-4 flex gap-2">
          <input
            type="text"
            value={imageQuery}
            onChange={(e) => setImageQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && runImageSearch(imageQuery)}
            placeholder="e.g. LM317 voltage regulator"
            className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
          <button
            onClick={() => runImageSearch(imageQuery)}
            disabled={!imageQuery.trim() || imagesLoading}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            Search
          </button>
        </div>

        <div className="min-h-[8rem]">
          {imagesLoading ? (
            <p className="text-sm text-gray-400">Searching for photos…</p>
          ) : (
            (() => {
              const visible = imageSuggestions.filter((img) => !failedImageUrls.has(img.url));
              if (visible.length === 0) {
                return (
                  <p className="text-sm text-gray-400">
                    No photos found. Try a different search term.
                  </p>
                );
              }
              return (
                <div className="grid grid-cols-3 gap-3">
                  {imageSuggestions.map((img) => {
                    if (failedImageUrls.has(img.url)) return null;
                    const selected = selectedImageUrls.has(img.url);
                    return (
                      <button
                        key={img.url}
                        type="button"
                        onClick={() => toggleImageSelect(img.url)}
                        className={`relative overflow-hidden rounded-lg border-2 transition-all ${
                          selected
                            ? 'border-blue-500 ring-2 ring-blue-200'
                            : 'border-gray-200 hover:border-gray-400'
                        }`}
                      >
                        <img
                          src={displayUrl(img)}
                          alt={img.description ?? ''}
                          className="h-24 w-full object-cover"
                          onError={() =>
                            setFailedImageUrls((prev) => new Set(prev).add(img.url))
                          }
                        />
                        {selected && (
                          <div className="absolute inset-0 flex items-center justify-center bg-blue-500/20">
                            <span className="rounded-full bg-blue-600 px-2 py-0.5 text-xs font-bold text-white">
                              ✓
                            </span>
                          </div>
                        )}
                      </button>
                    );
                  })}
                </div>
              );
            })()
          )}
        </div>

        {attachError && <p className="mt-3 text-sm text-red-600">{attachError}</p>}

        <div className="mt-4 flex items-center justify-between">
          <span className="text-xs text-blue-600">
            {selectedImageUrls.size > 0
              ? `${selectedImageUrls.size} selected`
              : ''}
          </span>
          <div className="flex gap-3">
            <button
              onClick={() => setImageModalOpen(false)}
              className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              onClick={handleAttachImages}
              disabled={attaching || selectedImageUrls.size === 0}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {attaching ? 'Attaching…' : 'Attach selected'}
            </button>
          </div>
        </div>
      </Modal>

      {/* OctoPart search / pick / confirm modal */}
      <Modal
        open={octoModalOpen}
        onClose={() => setOctoModalOpen(false)}
        title="Search OctoPart"
      >
        {!octoPicked ? (
          <>
            <div className="mb-2 flex gap-2">
              <input
                type="text"
                value={octoQuery}
                onChange={(e) => setOctoQuery(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && runOctopartSearch(octoQuery)}
                placeholder="Manufacturer part number"
                className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
              <button
                onClick={() => runOctopartSearch(octoQuery)}
                disabled={
                  !octoQuery.trim() ||
                  octoLoading ||
                  (octoUsage != null && octoUsage.remaining <= 0)
                }
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                Search
              </button>
            </div>
            {octoUsage != null && (
              <p className="mb-3 text-xs text-gray-400">
                {octoUsage.remaining} of {octoUsage.limit} requests left this month. Each search uses
                one.
              </p>
            )}

            {octoError && <p className="mb-3 text-sm text-red-600">{octoError}</p>}

            <div className="min-h-[8rem]">
              {octoLoading ? (
                <p className="text-sm text-gray-400">Searching OctoPart…</p>
              ) : octoResults.length === 0 ? (
                <p className="text-sm text-gray-400">
                  No results yet. Enter an MPN and search.
                </p>
              ) : (
                <ul className="divide-y divide-gray-100">
                  {octoResults.map((r) => (
                    <li key={r.octopartId} className="flex items-start justify-between gap-3 py-3">
                      <div className="min-w-0">
                        <div className="font-mono text-sm font-medium text-gray-900">{r.mpn}</div>
                        {r.manufacturer && (
                          <div className="text-xs text-gray-500">{r.manufacturer}</div>
                        )}
                        {r.description && (
                          <div className="mt-0.5 truncate text-xs text-gray-600">
                            {r.description}
                          </div>
                        )}
                      </div>
                      <button
                        onClick={() => pickOctopartResult(r)}
                        className="shrink-0 rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700"
                      >
                        Use this
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </>
        ) : (
          <>
            <p className="mb-3 text-sm text-gray-600">
              Review the changes from{' '}
              <span className="font-mono font-medium text-gray-900">{octoPicked.mpn}</span>. Untick
              any column you want to keep as-is.
            </p>

            {(() => {
              const changes = OCTOPART_FIELDS.map((f) => ({
                field: f,
                oldVal: (part[f.key] ?? '') as string,
                newVal: (octoPicked[f.key] ?? '') as string,
              })).filter((c) => c.newVal && c.newVal !== c.oldVal);

              if (changes.length === 0) {
                return (
                  <p className="mb-3 text-sm text-gray-500">
                    No column changes — only specs and the OctoPart link will be set.
                  </p>
                );
              }
              return (
                <ul className="mb-3 space-y-3">
                  {changes.map(({ field, oldVal, newVal }) => (
                    <li key={field.key} className="flex gap-2">
                      <input
                        type="checkbox"
                        checked={octoAccept[field.key] ?? false}
                        onChange={(e) =>
                          setOctoAccept((prev) => ({ ...prev, [field.key]: e.target.checked }))
                        }
                        className="mt-1 h-4 w-4 shrink-0"
                      />
                      <div className="min-w-0 text-sm">
                        <div className="font-medium text-gray-700">{field.label}</div>
                        {oldVal && (
                          <div className="truncate text-xs text-gray-400 line-through">{oldVal}</div>
                        )}
                        <div className="break-words text-gray-900">{newVal}</div>
                      </div>
                    </li>
                  ))}
                </ul>
              );
            })()}

            <p className="mb-4 text-xs text-gray-500">
              All {Object.keys(octoPicked.specs ?? {}).length} OctoPart spec field(s) will be applied,
              and the part will be linked to OctoPart.
            </p>

            {octoError && <p className="mb-3 text-sm text-red-600">{octoError}</p>}

            <div className="flex justify-between gap-3">
              <button
                onClick={() => setOctoPicked(null)}
                className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
              >
                ← Back to results
              </button>
              <button
                onClick={handleApplyOctopart}
                disabled={octoApplying}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {octoApplying ? 'Applying…' : 'Apply to part'}
              </button>
            </div>
          </>
        )}
      </Modal>

      {/* Print label modal */}
      <PrintLabelModal open={printModalOpen} onClose={() => setPrintModalOpen(false)} part={part} />
      </div>
    </div>
  );
}
