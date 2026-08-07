import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { findLocalParts, getComponentCacheStatus, getMyLocations, getSpecDefinitions, loadComponentCachePart, quickAddPart, searchComponentCache, searchPartImages, searchPartsOnline, uploadPartAttachment } from '../api';
import type { ComponentCacheMatch, ImageSuggestion, Location, Part, PartSearchResult, QuickAddRequest, SpecDefinition } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import MetricNumberField from '../components/MetricNumberField';
import TagInput from '../components/TagInput';
import { parseAiSpecs } from '../utils/specs';

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
    <div className="rounded-lg border border-gray-200 bg-surface p-4 shadow-sm">
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

/**
 * One component-cache hit.
 *
 * Shows more identifying detail than the AI card does, because the cache returns near-misses the AI
 * does not: a dozen houses second-source the same part number, and package, manufacturer and stock
 * are what tell them apart. `score` is rendered for anything short of an exact match so a
 * suffix-tolerant hit does not read as a confirmed one.
 */
function CacheResultCard({
  match,
  onSelect,
  busy,
}: {
  match: ComponentCacheMatch;
  onSelect: () => void;
  busy: boolean;
}) {
  return (
    <div className="rounded-lg border border-gray-200 bg-surface p-4 shadow-sm">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2 mb-1">
            <span className="font-mono text-base font-semibold text-gray-900">{match.mpn}</span>
            {match.manufacturer && (
              <span className="rounded bg-blue-100 px-2 py-0.5 text-xs text-blue-700">
                {match.manufacturer}
              </span>
            )}
            {match.packageName && (
              <span className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-600">
                {match.packageName}
              </span>
            )}
            {match.score < 0.999 && (
              <span className="rounded bg-amber-500/15 px-2 py-0.5 text-xs text-amber-700">
                {Math.round(match.score * 100)}% match
              </span>
            )}
          </div>
          {match.description && (
            <p className="text-sm text-gray-600 mb-1 line-clamp-2">{match.description}</p>
          )}
          <p className="text-xs text-gray-400">
            {match.specCount} specification{match.specCount === 1 ? '' : 's'}
            {match.subcategory ? ` · ${match.subcategory}` : ''}
            {` · ${match.lcsc}`}
          </p>
        </div>
        <button
          onClick={onSelect}
          disabled={busy}
          className="shrink-0 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 transition-colors"
        >
          {busy ? 'Loading…' : 'Select'}
        </button>
      </div>
    </div>
  );
}

// ── Form state type for step 3 ────────────────────────────────────────────────

interface ConfirmForm {
  partNumber: string;
  description: string;
  details: string;
  manufacturer: string;
  footprint: string;
  personalNumber: boolean;
  datasheetUrl: string;
  locationId: string;
  quantity: string;
  unitPrice: string;
  /**
   * Specs the chosen source supplied, keyed by jsonName, waiting for step 3 to render them.
   *
   * Already a map rather than the AI's "key: value" strings, because two sources fill it now: the
   * AI search (parsed through `parseAiSpecs`) and the component cache (which returns a map). Keeping
   * the AI's wire format here would mean serialising the cache's values back into strings only to
   * split them again.
   */
  specsPrefill: Record<string, string>;
  tags: string[];
}

function displayUrl(img: { url: string; thumbnailUrl?: string }) {
  const src = img.thumbnailUrl ?? img.url;
  // Proxy all external images through our backend to avoid CORS / tainted canvas
  // issues and Cloudflare bot-protection blocking server-side downloads.
  return `${import.meta.env.BASE_URL}api/image-proxy?url=${encodeURIComponent(src)}`;
}

// ── Main page ────────────────────────────────────────────────────────────────

export default function QuickAddPage() {
  const navigate = useNavigate();
  const { user, refresh } = useAuth();
  const [step, setStep] = useState(1);

  // Step 1
  // ?q= pre-fills the search. The BOM matching screen sends the user here for a line it found no
  // part for, carrying the line's part number so they do not have to retype it.
  const [searchParams] = useSearchParams();
  const [query, setQuery] = useState(searchParams.get('q') ?? '');
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [results, setResults] = useState<PartSearchResult[]>([]);
  // Existing parts whose part number fuzzy-matches the query — shown before searching the Internet.
  const [localMatches, setLocalMatches] = useState<Part[]>([]);
  // Component-cache hits, the stage between the catalogue and the Internet.
  const [cacheMatches, setCacheMatches] = useState<ComponentCacheMatch[]>([]);
  const [cacheAvailable, setCacheAvailable] = useState(false);
  const [loadingLcsc, setLoadingLcsc] = useState<string | null>(null);

  // Step 3
  const [form, setForm] = useState<ConfirmForm>({
    partNumber: '',
    description: '',
    details: '',
    manufacturer: '',
    footprint: '',
    personalNumber: false,
    datasheetUrl: '',
    locationId: '',
    quantity: '1',
    unitPrice: '',
    specsPrefill: {},
    tags: [],
  });
  const [locations, setLocations] = useState<Location[]>([]);
  const [locLoading, setLocLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [createdPartId, setCreatedPartId] = useState<number | null>(null);

  // Spec fields (no category for quick-add; show all definitions)
  const [specDefs, setSpecDefs] = useState<SpecDefinition[]>([]);
  const [specValues, setSpecValues] = useState<Record<string, string>>({});
  // jsonNames of the spec fields currently shown on the form. Starts with the specs
  // pre-filled by the lookup; the user adds more via the "Add specification" combobox.
  const [visibleSpecs, setVisibleSpecs] = useState<Set<string>>(new Set());

  // Image suggestions
  const [imageSuggestions, setImageSuggestions] = useState<ImageSuggestion[]>([]);
  const [imagesLoading, setImagesLoading] = useState(false);
  const [selectedImageUrls, setSelectedImageUrls] = useState<Set<string>>(new Set());
  const [failedImageUrls, setFailedImageUrls] = useState<Set<string>>(new Set());
  const [imageQuery, setImageQuery] = useState('');

  // Is the component cache installed? Asked once — an installation without the snapshot must behave
  // exactly as before rather than showing a stage that always finds nothing. A failure here is not
  // worth surfacing: the answer is simply "no cache".
  useEffect(() => {
    getComponentCacheStatus()
      .then((s) => setCacheAvailable(s.available))
      .catch(() => setCacheAvailable(false));
  }, []);

  // Load locations + all spec definitions when entering step 3
  useEffect(() => {
    if (step !== 3) return;
    setLocLoading(true);
    Promise.all([getMyLocations(), getSpecDefinitions()])
      .then(([locs, defs]) => {
        setLocations(locs);
        // Resolve the selected location: keep the current choice if it's still one of the user's
        // own locations, otherwise fall back to the location they last added stock to.
        const savedLocId = form.locationId;
        const validSaved = savedLocId && locs.some((l: Location) => String(l.id) === savedLocId);
        if (!validSaved) {
          const fallback =
            user?.lastLocationId && locs.some((l: Location) => l.id === user.lastLocationId)
              ? String(user.lastLocationId)
              : '';
          setForm((prev) => ({ ...prev, locationId: fallback }));
        }
        setSpecDefs(defs);
        // Pre-fill spec values from whichever source was chosen, keyed by jsonName.
        // Seed from the definitions first, then carry over every remaining key the source
        // returned. Iterating only the definitions would silently drop anything it named
        // that this organisation has no field for yet — which is also how the catalogue learns a
        // new field, since "rescan from parts" promotes surviving unknown keys to definitions.
        const sourceSpecs = form.specsPrefill;
        const known = new Set(defs.map((d) => d.jsonName));
        const prefilled: Record<string, string> = {};
        for (const def of defs) prefilled[def.jsonName] = sourceSpecs[def.jsonName] ?? '';
        for (const [key, value] of Object.entries(sourceSpecs)) {
          if (!known.has(key)) prefilled[key] = value;
        }
        setSpecValues(prefilled);
        // Initially only show specs the lookup actually filled in. From here on visibleSpecs is
        // the single source of truth for what is on the form, and legitimately holds keys with
        // no definition.
        setVisibleSpecs(
          new Set(Object.entries(prefilled).filter(([, v]) => v !== '').map(([k]) => k)),
        );
      })
      .finally(() => setLocLoading(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step]);

  // ── Step 1 handlers ──────────────────────────────────────────────────────

  // Run the online (AI) search and advance to the result-selection step.
  async function runOnlineSearch() {
    const data = await searchPartsOnline(query.trim());
    setResults(data);
    setLocalMatches([]);
    setCacheMatches([]);
    setStep(2);
  }

  /**
   * Try the component cache. Returns whether it had anything, so the caller can fall through.
   *
   * A cache failure is deliberately not fatal — it is an optional local dataset and the Internet
   * search is still there. Reporting "the cache is broken" to someone who only wants to add a part
   * would stop a flow that can perfectly well continue.
   */
  async function tryComponentCache(): Promise<boolean> {
    if (!cacheAvailable) return false;
    try {
      const matches = await searchComponentCache(query.trim());
      if (matches.length === 0) return false;
      setCacheMatches(matches);
      return true;
    } catch {
      return false;
    }
  }

  /**
   * Three sources, cheapest first: the catalogue we already have, then the local component cache,
   * then the AI web search. The order is the whole point — the last one costs real money and takes
   * seconds, and for a mass-market part the first two usually answer.
   */
  async function handleSearch(e: React.FormEvent) {
    e.preventDefault();
    if (!query.trim()) return;
    setSearching(true);
    setSearchError(null);
    try {
      const local = await findLocalParts(query.trim());
      if (local.length > 0) {
        setLocalMatches(local);
      } else if (!(await tryComponentCache())) {
        await runOnlineSearch();
      }
    } catch (err: unknown) {
      setSearchError((err as Error).message ?? 'Search failed. Please try again.');
    } finally {
      setSearching(false);
    }
  }

  // "None of these" from the local-match panel: fall to the next source rather than straight to the
  // Internet, so the cache is not skipped just because the catalogue held a near-miss.
  async function handleRejectLocalMatches() {
    setSearching(true);
    setSearchError(null);
    try {
      setLocalMatches([]);
      if (!(await tryComponentCache())) {
        await runOnlineSearch();
      }
    } catch (err: unknown) {
      setSearchError((err as Error).message ?? 'Search failed. Please try again.');
    } finally {
      setSearching(false);
    }
  }

  // "None of these — search online", from either panel.
  async function handleSearchOnlineAnyway() {
    setSearching(true);
    setSearchError(null);
    try {
      await runOnlineSearch();
    } catch (err: unknown) {
      setSearchError((err as Error).message ?? 'Search failed. Please try again.');
    } finally {
      setSearching(false);
    }
  }

  /**
   * Take a cache hit: fetch the whole record and pre-fill step 3 from it.
   *
   * The second of the two calls the cache exposes. Nothing is written here — the ordinary quick-add
   * create stores it, exactly as it does for an AI result, and the post-commit hook pulls the
   * datasheet PDF in behind it.
   */
  async function handleSelectCacheMatch(match: ComponentCacheMatch) {
    setLoadingLcsc(match.lcsc);
    setSearchError(null);
    try {
      const detail = await loadComponentCachePart(match.lcsc);
      setForm({
        partNumber: detail.mpn ?? match.mpn ?? query.trim(),
        description: detail.description ?? '',
        details: '',
        manufacturer: detail.manufacturer ?? '',
        footprint: detail.footprint ?? '',
        personalNumber: false,
        datasheetUrl: detail.datasheetUrl ?? '',
        locationId: '', // resolved to the last-used location when step 3 loads the user's locations
        quantity: '1',
        unitPrice: '',
        specsPrefill: detail.specs,
        tags: [],
      });
      setSaveError(null);
      setSelectedImageUrls(new Set());
      setFailedImageUrls(new Set());
      setSpecValues({});
      setVisibleSpecs(new Set());
      // The cache carries the vendor's own product photo, so offer it instead of running an image
      // search: it is the right part by construction, which a search result only might be.
      const cached: ImageSuggestion[] = detail.imageUrl
        ? [{ url: detail.imageUrl, thumbnailUrl: detail.imageUrl, description: detail.mpn ?? match.lcsc }]
        : [];
      setImageSuggestions(cached);
      setImageQuery(detail.mpn ?? match.mpn ?? '');
      setImagesLoading(false);
      setStep(3);
    } catch (err: unknown) {
      setSearchError((err as Error).message ?? 'Could not load that part from the cache.');
    } finally {
      setLoadingLcsc(null);
    }
  }

  // ── Step 2 handlers ──────────────────────────────────────────────────────

  function handleSelect(result: PartSearchResult) {
    setForm({
      partNumber: result.mpn,
      description: result.shortDescription ?? '',
      details: '',
      manufacturer: result.manufacturer ?? '',
      footprint: '', // the AI lookup returns no package
      personalNumber: false,
      datasheetUrl: result.datasheetUrl ?? '',
      locationId: '', // resolved to the last-used location when step 3 loads the user's locations
      quantity: '1',
      unitPrice: '',
      specsPrefill: parseAiSpecs(result.specs),
      tags: [],
    });
    setSaveError(null);
    setSelectedImageUrls(new Set());
    setFailedImageUrls(new Set());
    setImageSuggestions([]);
    setImageQuery(result.mpn);
    setSpecValues({});
    setVisibleSpecs(new Set());
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
    if (!form.locationId) {
      setSaveError('Please select a location.');
      return;
    }
    setSaving(true);
    setSaveError(null);

    // Every non-empty value shown on the form, including keys no definition covers — iterating
    // specDefs instead would drop exactly the new fields the "Other" section exists to keep.
    const specs: Record<string, string> = {};
    for (const key of visibleSpecs) {
      const v = specValues[key];
      if (v !== undefined && v !== '') {
        specs[key] = v;
      }
    }

    const payload: QuickAddRequest = {
      partNumber: form.partNumber,
      description: form.description || undefined,
      details: form.details || undefined,
      manufacturer: form.manufacturer || undefined,
      footprint: form.footprint || undefined,
      personalNumber: form.personalNumber,
      datasheetUrl: form.datasheetUrl || undefined,
      specs: Object.keys(specs).length > 0 ? specs : undefined,
      tags: form.tags.length > 0 ? form.tags : undefined,
      locationId: parseInt(form.locationId, 10),
      quantity: parseInt(form.quantity, 10),
      unitPrice: form.unitPrice !== '' ? parseFloat(form.unitPrice) : null,
    };

    try {
      const response = await quickAddPart(payload);
      // The backend now remembers this as the user's last-used location; refresh so the next
      // Quick Add pre-selects it.
      refresh();
      const partId = response.part.id;

      // Upload selected images: fetch via our same-origin proxy, then upload as multipart.
      const imageErrors: string[] = [];
      let imgIndex = 0;
      for (const originalUrl of selectedImageUrls) {
        try {
          const suggestion = imageSuggestions.find((s) => s.url === originalUrl);
          const proxyUrl = suggestion ? displayUrl(suggestion) : `${import.meta.env.BASE_URL}api/image-proxy?url=${encodeURIComponent(originalUrl)}`;
          const resp = await fetch(proxyUrl);
          if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
          const blob = await resp.blob();
          const file = new File([blob], `image-${imgIndex}.png`, { type: blob.type || 'image/png' });
          await uploadPartAttachment(partId, file, 'PHOTO');
        } catch (imgErr) {
          imageErrors.push((imgErr as Error).message);
        }
        imgIndex++;
      }

      if (imageErrors.length > 0) {
        const succeeded = selectedImageUrls.size - imageErrors.length;
        setSaveError(
          `Part saved, but ${imageErrors.length} photo(s) failed to upload` +
          (succeeded > 0 ? ` (${succeeded} succeeded)` : '') +
          `: ${imageErrors[0]}` +
          (imageErrors.length > 1 ? ` (and ${imageErrors.length - 1} more)` : '') +
          `. View your part or try uploading photos manually.`
        );
        setSaving(false);
        // Store partId so user can navigate manually
        setCreatedPartId(partId);
        return;
      }

      navigate(`/parts/${partId}`);
    } catch (err: unknown) {
      setSaveError((err as Error).message ?? 'Failed to save. Please try again.');
    } finally {
      setSaving(false);
    }
  }

  // Render the input control for one spec definition (label + field), matching its data type.
  function renderSpecField(spec: SpecDefinition) {
    if (spec.dataType === 'BOOLEAN') {
      return (
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            checked={specValues[spec.jsonName] === 'true'}
            onChange={(e) =>
              setSpecValues((prev) => ({
                ...prev,
                [spec.jsonName]: e.target.checked ? 'true' : 'false',
              }))
            }
            className="rounded border-gray-300 text-blue-600"
          />
          <span className="text-sm font-medium text-gray-700">{spec.name}</span>
        </label>
      );
    }
    if (spec.dataType === 'SELECT' && spec.options && spec.options.length > 0) {
      return (
        <>
          <label className="block text-sm font-medium text-gray-700 mb-1">{spec.name}</label>
          <select
            value={specValues[spec.jsonName] ?? ''}
            onChange={(e) =>
              setSpecValues((prev) => ({ ...prev, [spec.jsonName]: e.target.value }))
            }
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          >
            <option value="">— Select —</option>
            {spec.options.map((opt) => (
              <option key={opt} value={opt}>{opt}</option>
            ))}
          </select>
        </>
      );
    }
    if (spec.dataType === 'NUMBER') {
      const units = spec.unit ? spec.unit.split(',').map((s) => s.trim()) : [];
      const isMulti = units.length > 1;
      const currentVal = specValues[spec.jsonName] ?? '';
      if (!isMulti && spec.metricPrefix && units[0]) {
        return (
          <MetricNumberField
            label={spec.name}
            unit={units[0]}
            value={currentVal}
            onChange={(val) =>
              setSpecValues((prev) => ({ ...prev, [spec.jsonName]: val }))
            }
            labelClassName="block text-sm font-medium text-gray-700 mb-1"
            inputClassName="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            selectClassName="rounded-lg border border-gray-300 px-2 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        );
      }
      if (isMulti) {
        let numPart = currentVal, unitPart = units[0] ?? '';
        for (const u of units) {
          if (currentVal.endsWith(' ' + u)) { numPart = currentVal.slice(0, -(u.length + 1)); unitPart = u; break; }
        }
        return (
          <>
            <label className="block text-sm font-medium text-gray-700 mb-1">{spec.name}</label>
            <div className="flex gap-2">
              <input
                type="number"
                step="any"
                value={numPart}
                onChange={(e) =>
                  setSpecValues((prev) => ({ ...prev, [spec.jsonName]: e.target.value ? e.target.value + ' ' + unitPart : '' }))
                }
                className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
              <select
                value={unitPart}
                onChange={(e) =>
                  setSpecValues((prev) => ({ ...prev, [spec.jsonName]: numPart ? numPart + ' ' + e.target.value : '' }))
                }
                className="rounded-lg border border-gray-300 px-2 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                {units.map((u) => <option key={u} value={u}>{u}</option>)}
              </select>
            </div>
          </>
        );
      }
      return (
        <>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            {spec.name}{units[0] ? ` (${units[0]})` : ''}
          </label>
          <input
            type="number"
            step="any"
            value={currentVal}
            onChange={(e) =>
              setSpecValues((prev) => ({ ...prev, [spec.jsonName]: e.target.value }))
            }
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </>
      );
    }
    return (
      <>
        <label className="block text-sm font-medium text-gray-700 mb-1">{spec.name}</label>
        <input
          type="text"
          value={specValues[spec.jsonName] ?? ''}
          onChange={(e) =>
            setSpecValues((prev) => ({ ...prev, [spec.jsonName]: e.target.value }))
          }
          className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
      </>
    );
  }

  // ── Render ───────────────────────────────────────────────────────────────

  return (
    <div className="p-4 md:p-6 max-w-3xl mx-auto">
      <h1 className="text-2xl font-bold text-gray-900 mb-2">Quick Add Part</h1>
      <p className="text-sm text-gray-500 mb-6">AI-powered part search — enter a part number or description, pick a result, and add to stock.</p>

      <StepIndicator step={step} />

      {/* ── Step 1: Search ── */}
      {step === 1 && localMatches.length === 0 && cacheMatches.length === 0 && (
        <div className="rounded-lg border border-gray-200 bg-surface p-6 shadow-sm">
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
          <p className="mt-3 text-xs text-gray-400">
            {cacheAvailable
              ? "We'll check your existing catalogue first, then the local component cache, and only search the Internet if neither has it."
              : "We'll check your existing catalogue first, then search the Internet if there's no match."}
          </p>
          {searchError && (
            <div className="mt-4 rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
              {searchError}
            </div>
          )}
        </div>
      )}

      {/* ── Step 1b: Existing local matches ── */}
      {step === 1 && localMatches.length > 0 && (
        <div>
          <div className="flex items-center justify-between mb-2">
            <h2 className="text-lg font-semibold text-gray-900">
              You already have {localMatches.length} matching part{localMatches.length !== 1 ? 's' : ''}
            </h2>
            <button
              onClick={() => setLocalMatches([])}
              className="text-sm text-blue-600 hover:underline"
            >
              ← New search
            </button>
          </div>
          <p className="text-sm text-gray-500 mb-4">
            Does one of these match "{query}"? Pick it to go to the part and add stock — otherwise
            {cacheAvailable ? ' look it up as a new part.' : ' search the Internet for a new part.'}
          </p>
          <div className="space-y-3">
            {localMatches.map((p) => (
              <div key={p.id} className="rounded-lg border border-gray-200 bg-surface p-4 shadow-sm">
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2 mb-1">
                      <span className="font-mono text-base font-semibold text-gray-900">{p.partNumber}</span>
                      {p.manufacturer && (
                        <span className="rounded bg-blue-100 px-2 py-0.5 text-xs text-blue-700">
                          {p.manufacturer}
                        </span>
                      )}
                      {p.categoryName && (
                        <span className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-600">
                          {p.categoryName}
                        </span>
                      )}
                    </div>
                    {p.description && (
                      <p className="text-sm text-gray-500 mt-1 line-clamp-2">{p.description}</p>
                    )}
                    <p className="mt-1 text-xs text-gray-400">{p.totalQuantity ?? 0} on hand</p>
                  </div>
                  <button
                    onClick={() => navigate(`/parts/${p.id}`)}
                    className="shrink-0 rounded-lg bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 transition-colors"
                  >
                    Use this · add stock
                  </button>
                </div>
              </div>
            ))}
          </div>
          <div className="mt-5 flex items-center justify-between">
            <span className="text-sm text-gray-500">None of these is the part you want?</span>
            <button
              onClick={handleRejectLocalMatches}
              disabled={searching}
              className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {searching ? 'Searching…' : cacheAvailable ? 'Look up a new part' : 'Search the Internet instead'}
            </button>
          </div>
          {searchError && (
            <div className="mt-4 rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
              {searchError}
            </div>
          )}
        </div>
      )}

      {/* ── Step 1c: Component-cache matches ──
          Between the catalogue and the Internet. Selecting one goes straight to the confirm step,
          exactly as picking an AI result does — the cache is another source, not another ceremony. */}
      {step === 1 && localMatches.length === 0 && cacheMatches.length > 0 && (
        <div>
          <div className="flex items-center justify-between mb-2">
            <h2 className="text-lg font-semibold text-gray-900">
              {cacheMatches.length} match{cacheMatches.length !== 1 ? 'es' : ''} in the component cache
            </h2>
            <button
              onClick={() => setCacheMatches([])}
              className="text-sm text-blue-600 hover:underline"
            >
              ← New search
            </button>
          </div>
          <p className="text-sm text-gray-500 mb-4">
            Found locally for "{query}" — no Internet search needed. Picking one fills in the part
            details, its specifications and its datasheet.
          </p>
          <div className="space-y-3">
            {cacheMatches.map((m) => (
              <CacheResultCard
                key={m.lcsc}
                match={m}
                busy={loadingLcsc === m.lcsc}
                onSelect={() => handleSelectCacheMatch(m)}
              />
            ))}
          </div>
          <div className="mt-5 flex items-center justify-between">
            <span className="text-sm text-gray-500">None of these is the part you want?</span>
            <button
              onClick={handleSearchOnlineAnyway}
              disabled={searching}
              className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {searching ? 'Searching…' : 'Search the Internet instead'}
            </button>
          </div>
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
            <div className="rounded-lg border border-gray-200 bg-surface p-6 text-center text-sm text-gray-500">
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
          <div className="rounded-lg border border-gray-200 bg-surface p-6 shadow-sm">
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
                <label className="mt-2 flex items-center gap-2 text-sm text-gray-700">
                  <input
                    type="checkbox"
                    checked={form.personalNumber}
                    onChange={(e) => setForm((prev) => ({ ...prev, personalNumber: e.target.checked }))}
                  />
                  Personal/internal number (not a real manufacturer part number)
                </label>
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Manufacturer</label>
                <input
                  name="manufacturer"
                  value={form.manufacturer}
                  onChange={handleFormChange}
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
                <label className="mt-3 block text-sm font-medium text-gray-700 mb-1">Package / footprint</label>
                <input
                  name="footprint"
                  value={form.footprint}
                  onChange={handleFormChange}
                  placeholder="e.g. SOIC-8, 0402"
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
              <div className="col-span-2">
                <label className="block text-sm font-medium text-gray-700 mb-1">Details</label>
                <textarea
                  name="details"
                  value={form.details}
                  onChange={handleFormChange}
                  rows={4}
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
              <div className="col-span-2">
                <TagInput
                  value={form.tags}
                  onChange={(tags) => setForm((prev) => ({ ...prev, tags }))}
                />
              </div>
            </div>
          </div>

          {/* Stock details */}
          <div className="rounded-lg border border-gray-200 bg-surface p-6 shadow-sm">
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
                    {[...locations]
                      .sort((a, b) => a.breadcrumb.localeCompare(b.breadcrumb))
                      .map((loc) => (
                        <option key={loc.id} value={loc.id}>
                          {loc.breadcrumb || loc.name}
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
              <div className="rounded-lg border border-gray-200 bg-surface p-6 shadow-sm">
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

          {/* Spec fields — at the bottom. Shows only specs the lookup filled in; more can be
              added on demand via the combobox. */}
          {!locLoading && specDefs.length > 0 && (() => {
            const shownDefs = specDefs.filter((d) => visibleSpecs.has(d.jsonName));
            const availableDefs = specDefs.filter((d) => !visibleSpecs.has(d.jsonName));
            const known = new Set(specDefs.map((d) => d.jsonName));
            // Keys the lookup returned that this organisation has no field for yet. They are shown
            // and submitted like any other spec; "Rescan from parts" turns them into definitions.
            const unknownKeys = [...visibleSpecs].filter((k) => !known.has(k));
            return (
              <div className="rounded-lg border border-gray-200 bg-surface p-6 shadow-sm">
                <h2 className="text-lg font-semibold text-gray-900 mb-1">Specifications</h2>
                <p className="text-xs text-gray-400 mb-4">
                  Pre-filled from AI search results where names match. Add more fields below.
                </p>
                {shownDefs.length > 0 && (
                  <div className="grid grid-cols-2 gap-x-4">
                    {shownDefs.map((spec) => (
                      <div key={spec.id} className="mb-4 flex items-start gap-2">
                        <div className="min-w-0 flex-1">{renderSpecField(spec)}</div>
                        <button
                          type="button"
                          onClick={() =>
                            setVisibleSpecs((prev) => {
                              const next = new Set(prev);
                              next.delete(spec.jsonName);
                              return next;
                            })
                          }
                          title="Remove this specification"
                          aria-label={`Remove ${spec.name}`}
                          className="mt-7 shrink-0 text-gray-400 hover:text-red-600"
                        >
                          <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                            <line x1="18" y1="6" x2="6" y2="18" />
                            <line x1="6" y1="6" x2="18" y2="18" />
                          </svg>
                        </button>
                      </div>
                    ))}
                  </div>
                )}
                {unknownKeys.length > 0 && (
                  <div className="mb-4 border-t border-gray-200 pt-4 dark:border-gray-700">
                    <h3 className="text-sm font-semibold text-gray-900">Other</h3>
                    <p className="mb-3 text-xs text-gray-500 dark:text-gray-400">
                      The lookup returned these under names your spec catalogue does not have yet.
                      They are saved with the part; “Rescan from parts” on the Spec Fields screen
                      turns them into proper fields.
                    </p>
                    <div className="grid grid-cols-2 gap-x-4">
                      {unknownKeys.map((key) => (
                        <div key={key} className="mb-4 flex items-start gap-2">
                          <div className="min-w-0 flex-1">
                            {renderSpecField({
                              id: -1,
                              jsonName: key,
                              name: key,
                              dataType: 'TEXT',
                              displayOrder: 0,
                              groupId: -1,
                              groupName: 'Other',
                            })}
                          </div>
                          <button
                            type="button"
                            onClick={() =>
                              setVisibleSpecs((prev) => {
                                const next = new Set(prev);
                                next.delete(key);
                                return next;
                              })
                            }
                            title="Remove this specification"
                            aria-label={`Remove ${key}`}
                            className="mt-7 shrink-0 text-gray-400 hover:text-red-600"
                          >
                            <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                              <line x1="18" y1="6" x2="6" y2="18" />
                              <line x1="6" y1="6" x2="18" y2="18" />
                            </svg>
                          </button>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
                {availableDefs.length > 0 && (
                  <div className="max-w-xs">
                    <label className="block text-sm font-medium text-gray-700 mb-1">
                      Add a specification
                    </label>
                    <select
                      value=""
                      onChange={(e) => {
                        const jsonName = e.target.value;
                        if (!jsonName) return;
                        setVisibleSpecs((prev) => new Set(prev).add(jsonName));
                      }}
                      className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                    >
                      <option value="">— Select a specification… —</option>
                      {availableDefs.map((d) => (
                        <option key={d.id} value={d.jsonName}>{d.name}</option>
                      ))}
                    </select>
                  </div>
                )}
              </div>
            );
          })()}

          {saveError && (
            <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
              {saveError}
              {createdPartId && (
                <button
                  type="button"
                  onClick={() => navigate(`/parts/${createdPartId}`)}
                  className="ml-2 font-medium text-blue-600 hover:underline"
                >
                  View part →
                </button>
              )}
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
              disabled={saving || !form.locationId}
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
