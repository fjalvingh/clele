import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { addPartImageFromUrl, getLocations, quickAddPart, searchPartImages, searchPartsOnline } from '../api';
import type { ImageSuggestion, Location, PartSearchResult, QuickAddRequest } from '../api/types';

// ── Sub-components ────────────────────────────────────────────────────────────

function StepIndicator({ step }: { step: number }) {
  const steps = ['Search', 'Select', 'Confirm'];
  return (
    <div className="flex items-center gap-2 mb-8">
      {steps.map((label, i) => {
        const n = i + 1;
        const active = n === step;
        const done = n < step;
        return (
          <div key={label} className="flex items-center gap-2">
            <div
              className={`flex h-8 w-8 items-center justify-center rounded-full text-sm font-semibold ${
                done
                  ? 'bg-green-500 text-white'
                  : active
                  ? 'bg-blue-600 text-white'
                  : 'bg-gray-200 text-gray-500'
              }`}
            >
              {done ? '✓' : n}
            </div>
            <span
              className={`text-sm font-medium ${active ? 'text-blue-600' : done ? 'text-green-600' : 'text-gray-400'}`}
            >
              {label}
            </span>
            {i < steps.length - 1 && <div className="mx-2 h-px w-8 bg-gray-300" />}
          </div>
        );
      })}
    </div>
  );
}

function ResultCard({
  result,
  onSelect,
}: {
  result: PartSearchResult;
  onSelect: () => void;
}) {
  const maxSpecs = 6;
  const shown = result.specs.slice(0, maxSpecs);
  const overflow = result.specs.length - maxSpecs;
  return (
    <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2 mb-1">
            <span className="font-mono text-base font-semibold text-gray-900">{result.mpn}</span>
            {result.manufacturer && (
              <span className="rounded bg-blue-100 px-2 py-0.5 text-xs text-blue-700">
                {result.manufacturer}
              </span>
            )}
            {result.category && (
              <span className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-600">
                {result.category}
              </span>
            )}
          </div>
          {result.shortDescription && (
            <p className="text-sm text-gray-600 mb-2">{result.shortDescription}</p>
          )}
          {shown.length > 0 && (
            <div className="flex flex-wrap gap-1">
              {shown.map((s) => (
                <span key={s} className="rounded bg-gray-50 border border-gray-200 px-2 py-0.5 text-xs text-gray-700">
                  {s}
                </span>
              ))}
              {overflow > 0 && (
                <span className="rounded bg-gray-50 border border-gray-200 px-2 py-0.5 text-xs text-gray-500">
                  +{overflow} more
                </span>
              )}
            </div>
          )}
          {result.datasheetUrl && (
            <a
              href={result.datasheetUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="mt-2 inline-block text-xs text-blue-600 hover:underline"
            >
              Datasheet ↗
            </a>
          )}
        </div>
        <button
          onClick={onSelect}
          className="shrink-0 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 transition-colors"
        >
          Select
        </button>
      </div>
    </div>
  );
}

// ── Form state type for step 3 ────────────────────────────────────────────────

interface ConfirmForm {
  partNumber: string;
  name: string;
  description: string;
  manufacturer: string;
  datasheetUrl: string;
  locationId: string;
  quantity: string;
  minimumQuantity: string;
  unitPrice: string;
  // raw specs from search result (kept for payload)
  specsRaw: string[];
}

const PROXY_HOSTS = ['upload.wikimedia.org', 'commons.wikimedia.org', 'external-content.duckduckgo.com'];

function displayUrl(img: { url: string; thumbnailUrl?: string }) {
  const src = img.thumbnailUrl ?? img.url;
  try {
    const host = new URL(src).hostname;
    if (PROXY_HOSTS.includes(host)) return `/api/image-proxy?url=${encodeURIComponent(src)}`;
  } catch { /* invalid URL */ }
  return src;
}

// ── Main page ────────────────────────────────────────────────────────────────

export default function QuickAddPage() {
  const navigate = useNavigate();
  const [step, setStep] = useState(1);

  // Step 1
  const [query, setQuery] = useState('');
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [results, setResults] = useState<PartSearchResult[]>([]);

  // Step 3
  const [form, setForm] = useState<ConfirmForm>({
    partNumber: '',
    name: '',
    description: '',
    manufacturer: '',
    datasheetUrl: '',
    locationId: '',
    quantity: '1',
    minimumQuantity: '1',
    unitPrice: '',
    specsRaw: [],
  });
  const [locations, setLocations] = useState<Location[]>([]);
  const [locLoading, setLocLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  // Image suggestions
  const [imageSuggestions, setImageSuggestions] = useState<ImageSuggestion[]>([]);
  const [imagesLoading, setImagesLoading] = useState(false);
  const [selectedImageUrls, setSelectedImageUrls] = useState<Set<string>>(new Set());
  const [failedImageUrls, setFailedImageUrls] = useState<Set<string>>(new Set());
  const [imageQuery, setImageQuery] = useState('');

  // Load locations when entering step 3
  useEffect(() => {
    if (step !== 3) return;
    setLocLoading(true);
    getLocations()
      .then(setLocations)
      .finally(() => setLocLoading(false));
  }, [step]);

  // ── Step 1 handlers ──────────────────────────────────────────────────────

  async function handleSearch(e: React.FormEvent) {
    e.preventDefault();
    if (!query.trim()) return;
    setSearching(true);
    setSearchError(null);
    try {
      const data = await searchPartsOnline(query.trim());
      setResults(data);
      setStep(2);
    } catch (err: unknown) {
      setSearchError((err as Error).message ?? 'Search failed. Please try again.');
    } finally {
      setSearching(false);
    }
  }

  // ── Step 2 handlers ──────────────────────────────────────────────────────

  function handleSelect(result: PartSearchResult) {
    setForm({
      partNumber: result.mpn,
      name: result.shortDescription ?? result.mpn,
      description: result.shortDescription ?? '',
      manufacturer: result.manufacturer ?? '',
      datasheetUrl: result.datasheetUrl ?? '',
      locationId: '',
      quantity: '1',
      minimumQuantity: '1',
      unitPrice: '',
      specsRaw: result.specs,
    });
    setSaveError(null);
    setSelectedImageUrls(new Set());
    setFailedImageUrls(new Set());
    setImageSuggestions([]);
    setImageQuery(result.mpn);
    setStep(3);

    // Kick off image search in the background so results are ready by the time the user submits
    setImagesLoading(true);
    searchPartImages(result.mpn)
      .then(setImageSuggestions)
      .catch(() => setImageSuggestions([]))
      .finally(() => setImagesLoading(false));
  }

  // ── Step 3 handlers ──────────────────────────────────────────────────────

  function handleFormChange(e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) {
    const { name, value } = e.target;
    setForm((prev) => ({ ...prev, [name]: value }));
  }

  function handleImageSearch() {
    if (!imageQuery.trim()) return;
    setImagesLoading(true);
    setImageSuggestions([]);
    setFailedImageUrls(new Set());
    searchPartImages(imageQuery.trim())
      .then(setImageSuggestions)
      .catch(() => setImageSuggestions([]))
      .finally(() => setImagesLoading(false));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaveError(null);

    // Convert specs: ["Name: Value", ...] → { Name: Value, ... }
    const specs: Record<string, string> = {};
    for (const s of form.specsRaw) {
      const idx = s.indexOf(': ');
      if (idx !== -1) {
        specs[s.slice(0, idx)] = s.slice(idx + 2);
      }
    }

    const payload: QuickAddRequest = {
      partNumber: form.partNumber,
      name: form.name,
      description: form.description || undefined,
      manufacturer: form.manufacturer || undefined,
      datasheetUrl: form.datasheetUrl || undefined,
      specs: Object.keys(specs).length > 0 ? specs : undefined,
      locationId: parseInt(form.locationId, 10),
      quantity: parseInt(form.quantity, 10),
      minimumQuantity: parseInt(form.minimumQuantity, 10),
      unitPrice: form.unitPrice !== '' ? parseFloat(form.unitPrice) : null,
    };

    try {
      const response = await quickAddPart(payload);
      const partId = response.part.id;

      // Upload selected images sequentially; ignore individual failures
      for (const url of selectedImageUrls) {
        try {
          await addPartImageFromUrl(partId, url);
        } catch {
          // best-effort
        }
      }

      navigate(`/parts/${partId}`);
    } catch (err: unknown) {
      setSaveError((err as Error).message ?? 'Failed to save. Please try again.');
    } finally {
      setSaving(false);
    }
  }

  // ── Render ───────────────────────────────────────────────────────────────

  return (
    <div className="p-6 max-w-3xl mx-auto">
      <h1 className="text-2xl font-bold text-gray-900 mb-2">Quick Add Part</h1>
      <p className="text-sm text-gray-500 mb-6">AI-powered part search — enter a part number or description, pick a result, and add to stock.</p>

      <StepIndicator step={step} />

      {/* ── Step 1: Search ── */}
      {step === 1 && (
        <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
          <h2 className="text-lg font-semibold text-gray-900 mb-4">Search for a part (AI-powered)</h2>
          <form onSubmit={handleSearch} className="flex gap-3">
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="e.g. NE555, BC547, LM358"
              className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
            <button
              type="submit"
              disabled={searching || !query.trim()}
              className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {searching ? 'Searching…' : 'Search'}
            </button>
          </form>
          {searchError && (
            <div className="mt-4 rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
              {searchError}
            </div>
          )}
        </div>
      )}

      {/* ── Step 2: Select ── */}
      {step === 2 && (
        <div>
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-gray-900">
              {results.length} result{results.length !== 1 ? 's' : ''} for "{query}"
            </h2>
            <button
              onClick={() => setStep(1)}
              className="text-sm text-blue-600 hover:underline"
            >
              ← New search
            </button>
          </div>
          {results.length === 0 ? (
            <div className="rounded-lg border border-gray-200 bg-white p-6 text-center text-sm text-gray-500">
              No results found. Try a different search term.
            </div>
          ) : (
            <div className="space-y-3">
              {results.map((r) => (
                <ResultCard key={r.mpn} result={r} onSelect={() => handleSelect(r)} />
              ))}
            </div>
          )}
        </div>
      )}

      {/* ── Step 3: Confirm ── */}
      {step === 3 && (
        <form onSubmit={handleSubmit} className="space-y-6">
          {/* Part details */}
          <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
            <h2 className="text-lg font-semibold text-gray-900 mb-4">Part details</h2>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Part number <span className="text-red-500">*</span>
                </label>
                <input
                  name="partNumber"
                  value={form.partNumber}
                  onChange={handleFormChange}
                  required
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Name <span className="text-red-500">*</span>
                </label>
                <input
                  name="name"
                  value={form.name}
                  onChange={handleFormChange}
                  required
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Manufacturer</label>
                <input
                  name="manufacturer"
                  value={form.manufacturer}
                  onChange={handleFormChange}
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Datasheet URL</label>
                <input
                  name="datasheetUrl"
                  value={form.datasheetUrl}
                  onChange={handleFormChange}
                  type="url"
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
              <div className="col-span-2">
                <label className="block text-sm font-medium text-gray-700 mb-1">Description</label>
                <textarea
                  name="description"
                  value={form.description}
                  onChange={handleFormChange}
                  rows={2}
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
            </div>
          </div>

          {/* Stock details */}
          <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
            <h2 className="text-lg font-semibold text-gray-900 mb-4">Stock details</h2>
            <div className="grid grid-cols-2 gap-4">
              <div className="col-span-2">
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Location <span className="text-red-500">*</span>
                </label>
                {locLoading ? (
                  <p className="text-sm text-gray-400">Loading locations…</p>
                ) : (
                  <select
                    name="locationId"
                    value={form.locationId}
                    onChange={handleFormChange}
                    required
                    className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                  >
                    <option value="">Select location…</option>
                    {locations.map((loc) => (
                      <option key={loc.id} value={loc.id}>
                        {loc.name}
                      </option>
                    ))}
                  </select>
                )}
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Quantity <span className="text-red-500">*</span>
                </label>
                <input
                  name="quantity"
                  value={form.quantity}
                  onChange={handleFormChange}
                  type="number"
                  min="0"
                  required
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Minimum qty <span className="text-red-500">*</span>
                </label>
                <input
                  name="minimumQuantity"
                  value={form.minimumQuantity}
                  onChange={handleFormChange}
                  type="number"
                  min="0"
                  required
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Unit price</label>
                <input
                  name="unitPrice"
                  value={form.unitPrice}
                  onChange={handleFormChange}
                  type="number"
                  min="0"
                  step="0.01"
                  placeholder="Optional"
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
            </div>
          </div>

          {/* Image picker */}
          {(() => {
            const visibleSuggestions = imageSuggestions.filter(
              (img) => !failedImageUrls.has(img.url),
            );
            const showSearchForm = !imagesLoading && visibleSuggestions.length === 0;
            return (
              <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
                <h2 className="text-lg font-semibold text-gray-900 mb-1">Photos</h2>
                <p className="text-xs text-gray-400 mb-4">
                  Select images to attach (optional). Only images that load are shown.
                </p>
                {imagesLoading ? (
                  <p className="text-sm text-gray-400">Searching for photos…</p>
                ) : (
                  <>
                    {visibleSuggestions.length > 0 && (
                      <>
                        <div className="grid grid-cols-5 gap-3">
                          {imageSuggestions.map((img) => {
                            if (failedImageUrls.has(img.url)) return null;
                            const selected = selectedImageUrls.has(img.url);
                            return (
                              <button
                                key={img.url}
                                type="button"
                                onClick={() =>
                                  setSelectedImageUrls((prev) => {
                                    const next = new Set(prev);
                                    selected ? next.delete(img.url) : next.add(img.url);
                                    return next;
                                  })
                                }
                                className={`relative rounded-lg border-2 overflow-hidden transition-all ${
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
                                {img.description && (
                                  <p className="px-1 py-0.5 text-center text-xs text-gray-500 truncate">
                                    {img.description}
                                  </p>
                                )}
                              </button>
                            );
                          })}
                        </div>
                        {selectedImageUrls.size > 0 && (
                          <p className="mt-2 text-xs text-blue-600">
                            {selectedImageUrls.size} photo{selectedImageUrls.size !== 1 ? 's' : ''} selected
                          </p>
                        )}
                      </>
                    )}
                    {showSearchForm && (
                      <div>
                        <p className="mb-3 text-sm text-gray-400">
                          No photos found. Try a different search term:
                        </p>
                        <div className="flex gap-2">
                          <input
                            type="text"
                            value={imageQuery}
                            onChange={(e) => setImageQuery(e.target.value)}
                            onKeyDown={(e) => e.key === 'Enter' && handleImageSearch()}
                            placeholder="e.g. LM317 voltage regulator chip"
                            className="flex-1 rounded-lg border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                          />
                          <button
                            type="button"
                            onClick={handleImageSearch}
                            disabled={!imageQuery.trim()}
                            className="rounded-lg bg-blue-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                          >
                            Search
                          </button>
                        </div>
                      </div>
                    )}
                  </>
                )}
              </div>
            );
          })()}


          {saveError && (
            <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
              {saveError}
            </div>
          )}

          <div className="flex items-center justify-between">
            <button
              type="button"
              onClick={() => setStep(2)}
              className="text-sm text-blue-600 hover:underline"
            >
              ← Back to results
            </button>
            <button
              type="submit"
              disabled={saving}
              className="rounded-lg bg-green-600 px-6 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50 transition-colors"
            >
              {saving ? 'Saving…' : 'Add to stock'}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
