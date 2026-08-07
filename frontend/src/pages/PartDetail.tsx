import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import {
  addAttachmentFromUrl,
  addStock,
  applyAiLookup,
  applyOctopart,
  attachmentUrl,
  deletePart,
  deletePartAttachment,
  deleteStockEntry,
  deleteStockThreshold,
  extractDatasheetSpecs,
  getComponentCacheStatus,
  getLocations,
  getMyLocations,
  getOctopartUsage,
  getPart,
  getPartAttachments,
  getPartMovements,
  getPartStock,
  getSpecDefinitions,
  getStockThresholds,
  loadComponentCachePart,
  moveStock,
  searchComponentCache,
  searchOctopart,
  searchPartDatasheets,
  searchPartsOnline,
  searchPartImages,
  takeStock,
  updatePart,
  uploadPartAttachment,
  upsertStockThreshold,
} from '../api';
import type {
  AiApplyRequest,
  AttachmentType,
  ComponentCacheDetail,
  ComponentCacheMatch,
  DatasheetExtraction,
  DatasheetSearchResponse,
  DatasheetSuggestion,
  ImageSuggestion,
  Location,
  OctopartApplyRequest,
  OctopartResult,
  OctopartUsage,
  Part,
  PartAttachment,
  PartRequest,
  PartSearchResult,
  SpecDefinition,
  StockEntry,
  StockMovement,
  StockThreshold,
} from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useSettings } from '../settings/SettingsContext';
import Badge from '../components/Badge';
import DataTable from '../components/DataTable';
import type { Column } from '../components/DataTable';
import FormField from '../components/FormField';
import Modal from '../components/Modal';
import PartEditModal from '../components/PartEditModal';
import PrintLabelModal from '../components/PrintLabelModal';
import { formatMetric } from '../utils/units';
import { parseAiSpecs } from '../utils/specs';

// The three stock operations offered per location, plus the top-level "add".
type StockOp = 'add' | 'take' | 'move';

interface StockOpForm {
  locationId: number; // add (top-level): target; take/move: source (fixed to the line)
  destLocationId: number; // move: destination (may belong to any user)
  quantity: number;
  unitPrice: number | null;
  comment: string;
}

const emptyOpForm: StockOpForm = {
  locationId: 0,
  destLocationId: 0,
  quantity: 0,
  unitPrice: null,
  comment: '',
};

// Proxy external images through our backend to avoid CORS / Cloudflare bot-protection issues.
function displayUrl(img: { url: string; thumbnailUrl?: string }) {
  const src = img.thumbnailUrl ?? img.url;
  return `${import.meta.env.BASE_URL}api/image-proxy?url=${encodeURIComponent(src)}`;
}

// Button glyphs as inline SVG. Emoji (🔍 🏷️) render as empty boxes wherever the platform font
// lacks them, so the project rule is SVG everywhere; `currentColor` inherits the button's colour.
const btnIcon = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.7,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  viewBox: '0 0 24 24',
  className: 'h-4 w-4 shrink-0',
  'aria-hidden': true,
};

const SearchIcon = (
  <svg {...btnIcon}>
    <circle cx="11" cy="11" r="6" />
    <path d="m20 20-3.2-3.2" />
  </svg>
);

const TagIcon = (
  <svg {...btnIcon}>
    <path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0l-7.2-7.2A2 2 0 0 1 2.8 12V4.8A2 2 0 0 1 4.8 2.8H12a2 2 0 0 1 1.4.6l7.2 7.2a2 2 0 0 1 0 2.8Z" />
    <circle cx="7.8" cy="7.8" r="1.4" />
  </svg>
);

// Render a spec value as a display string for a table cell.
function formatSpecValue(spec: SpecDefinition, value: string): string {
  if (spec.dataType === 'BOOLEAN') {
    return value === 'true' ? '✓' : '✗';
  }
  if (spec.dataType === 'NUMBER') {
    const units = spec.unit ? spec.unit.split(',').map((s) => s.trim()) : [];
    // Metric: scale the base-unit value with a prefix (0.009 A → "9 mA")
    if (units.length === 1 && spec.metricPrefix) return formatMetric(value, units[0]);
    // Multi-unit: value already contains the chosen unit (e.g. "64 KB") — display as-is
    // Single unit: append the fixed unit suffix
    return units.length > 1 ? value : units[0] ? `${value} ${units[0]}` : value;
  }
  return value;
}

// Real part columns that an OctoPart result can change. Each must be confirmed (per-field
// checkbox) before it overwrites the existing value. Specs are applied wholesale, separately.
/** Bucket for spec values no definition covers — they still deserve to be shown, just last. */
const UNGROUPED = 'Other';

const OCTOPART_FIELDS = [
  { key: 'mpn', label: 'MPN' },
  { key: 'manufacturer', label: 'Manufacturer' },
  { key: 'description', label: 'Description' },
  { key: 'footprint', label: 'Footprint' },
  { key: 'datasheetUrl', label: 'Datasheet URL' },
] as const;

type OctopartFieldKey = (typeof OCTOPART_FIELDS)[number]['key'];

// Real part columns an AI lookup can change. No footprint — the lookup does not return one — and no
// category: it returns a category *name*, and resolving that to one of this organisation's
// categories is a separate, fuzzy problem, so the name is shown as context and never applied.
// `key` names the part column (and the AiApplyRequest field); `from` names the field on the search
// result, which is not always the same — the lookup calls the description `shortDescription`.
const AI_FIELDS = [
  { key: 'mpn', from: 'mpn', label: 'MPN' },
  { key: 'manufacturer', from: 'manufacturer', label: 'Manufacturer' },
  { key: 'description', from: 'shortDescription', label: 'Description' },
  { key: 'datasheetUrl', from: 'datasheetUrl', label: 'Datasheet URL' },
] as const;

type AiFieldKey = (typeof AI_FIELDS)[number]['key'];

/**
 * Real part columns a component-cache hit can change.
 *
 * It carries a footprint where the AI lookup does not — `package` is a first-class column on every
 * cached row — which is the one reason this is not simply `AI_FIELDS`. No category, for the same
 * reason as there: the cache names a category of its own taxonomy, and resolving that to one of this
 * organisation's is a separate, fuzzy problem.
 */
const CACHE_FIELDS = [
  { key: 'mpn', from: 'mpn', label: 'MPN' },
  { key: 'manufacturer', from: 'manufacturer', label: 'Manufacturer' },
  { key: 'description', from: 'description', label: 'Description' },
  { key: 'footprint', from: 'footprint', label: 'Footprint' },
  { key: 'datasheetUrl', from: 'datasheetUrl', label: 'Datasheet URL' },
] as const;

type CacheFieldKey = (typeof CACHE_FIELDS)[number]['key'];

/**
 * How one spec the lookup returned relates to what the part already holds.
 *
 * `new` is ticked by default and `conflict` is not: filling a gap is what the action is for, while
 * overwriting a value someone already curated should be a deliberate act. `same` is not shown at
 * all — a row you cannot act on is noise.
 */
type SpecVerdict = 'new' | 'conflict' | 'same';

interface AiSpecRow {
  key: string;
  /** The definition's title where one exists, else the raw key (which is then a new field). */
  label: string;
  known: boolean;
  oldValue: string;
  newValue: string;
  verdict: SpecVerdict;
  /** Datasheet extraction only: the PDF page the value was read from, so it can be checked. */
  page?: number | null;
}

export default function PartDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const routeLocation = useLocation();
  // Where the breadcrumb's "Parts" link returns to — back to the originating search results
  // (carried in navigation state) when we arrived from the Parts page, else the bare list.
  const partsListUrl = (routeLocation.state as { from?: string } | null)?.from ?? '/parts';
  const { user, hasPermission, refresh } = useAuth();
  const { formatMoney } = useSettings();
  const canEdit = hasPermission('PARTS_EDIT');
  const partId = Number(id);

  const [part, setPart] = useState<Part | null>(null);
  const [stock, setStock] = useState<StockEntry[]>([]);
  const [movements, setMovements] = useState<StockMovement[]>([]);
  const [stockTab, setStockTab] = useState<'locations' | 'thresholds' | 'movements'>('locations');
  const [locations, setLocations] = useState<Location[]>([]);
  const [allLocations, setAllLocations] = useState<Location[]>([]);
  const [images, setImages] = useState<PartAttachment[]>([]);
  const [datasheets, setDatasheets] = useState<PartAttachment[]>([]);
  const [attachments, setAttachments] = useState<PartAttachment[]>([]);
  const [selectedImageId, setSelectedImageId] = useState<number | null>(null);
  const [specDefs, setSpecDefs] = useState<SpecDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Stock operation modal: which op, on which line (null = top-level add), and the form.
  const [stockOp, setStockOp] = useState<StockOp | null>(null);
  const [opEntry, setOpEntry] = useState<StockEntry | null>(null);
  const [opForm, setOpForm] = useState<StockOpForm>(emptyOpForm);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Datasheet + generic attachment uploads (preserve original file, no cap).
  const [fileBusy, setFileBusy] = useState(false);
  const [fileError, setFileError] = useState<string | null>(null);
  const datasheetInputRef = useRef<HTMLInputElement>(null);
  const attachmentInputRef = useRef<HTMLInputElement>(null);

  // "Find image" modal — same image search/attach flow used by Quick Add.
  const [imageModalOpen, setImageModalOpen] = useState(false);
  const [imageQuery, setImageQuery] = useState('');
  const [imageSuggestions, setImageSuggestions] = useState<ImageSuggestion[]>([]);
  const [imagesLoading, setImagesLoading] = useState(false);
  const [selectedImageUrls, setSelectedImageUrls] = useState<Set<string>>(new Set());
  const [failedImageUrls, setFailedImageUrls] = useState<Set<string>>(new Set());
  const [attaching, setAttaching] = useState(false);
  const [attachError, setAttachError] = useState<string | null>(null);

  // "Find datasheet" modal — same search/pick pattern as "Find image", but attaches via URL.
  const [datasheetModalOpen, setDatasheetModalOpen] = useState(false);
  const [datasheetQuery, setDatasheetQuery] = useState('');
  const [datasheetSuggestions, setDatasheetSuggestions] = useState<DatasheetSuggestion[]>([]);
  // How the last search ended. Empty results mean nothing without it: a bot-challenged search and a
  // part with no datasheet anywhere both return nothing, and only the first is worth retrying.
  const [datasheetOutcome, setDatasheetOutcome] = useState<DatasheetSearchResponse | null>(null);
  const [datasheetsLoading, setDatasheetsLoading] = useState(false);
  const [datasheetAttaching, setDatasheetAttaching] = useState(false);
  const [datasheetAttachError, setDatasheetAttachError] = useState<string | null>(null);

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

  // AI lookup — "Look up specs" on an existing part. Same search/pick/confirm shape as OctoPart
  // above, but the specs are confirmed one by one rather than applied wholesale, because this runs
  // on parts that already carry curated data.
  const [aiModalOpen, setAiModalOpen] = useState(false);
  const [aiQuery, setAiQuery] = useState('');
  const [aiResults, setAiResults] = useState<PartSearchResult[]>([]);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiError, setAiError] = useState<string | null>(null);
  const [aiPicked, setAiPicked] = useState<PartSearchResult | null>(null);
  // "Not searched yet" and "searched, found nothing" are different facts and must not share a
  // message — vintage and one-off parts genuinely return nothing, and telling the user to press
  // the button they just pressed reads as if the search never ran.
  const [aiSearched, setAiSearched] = useState(false);
  const [aiAcceptFields, setAiAcceptFields] = useState<Record<AiFieldKey, boolean>>(
    {} as Record<AiFieldKey, boolean>,
  );
  const [aiAcceptSpecs, setAiAcceptSpecs] = useState<Record<string, boolean>>({});
  const [aiApplying, setAiApplying] = useState(false);

  // Component cache — "Look up in cache". Same search/pick/confirm shape as the AI lookup, and it
  // applies through the same endpoint, but it is free and local: no cost warning, and it runs the
  // search as soon as the modal opens rather than waiting for a button.
  const [ccAvailable, setCcAvailable] = useState(false);
  const [ccModalOpen, setCcModalOpen] = useState(false);
  const [ccQuery, setCcQuery] = useState('');
  const [ccResults, setCcResults] = useState<ComponentCacheMatch[]>([]);
  const [ccLoading, setCcLoading] = useState(false);
  const [ccError, setCcError] = useState<string | null>(null);
  const [ccSearched, setCcSearched] = useState(false);
  // The picked *detail*, not the match: the second call is what carries the specifications.
  const [ccPicked, setCcPicked] = useState<ComponentCacheDetail | null>(null);
  const [ccAcceptFields, setCcAcceptFields] = useState<Record<CacheFieldKey, boolean>>(
    {} as Record<CacheFieldKey, boolean>,
  );
  const [ccAcceptSpecs, setCcAcceptSpecs] = useState<Record<string, boolean>>({});
  const [ccApplying, setCcApplying] = useState(false);

  // Datasheet extraction — "Get specs from document". Reads a PDF already stored on the part, so
  // there is nothing to search and nothing to pick: it is one run, then the same per-field
  // confirmation the AI lookup uses.
  const [dsModalOpen, setDsModalOpen] = useState(false);
  const [dsAttachment, setDsAttachment] = useState<PartAttachment | null>(null);
  const [dsResult, setDsResult] = useState<DatasheetExtraction | null>(null);
  const [dsLoading, setDsLoading] = useState(false);
  const [dsError, setDsError] = useState<string | null>(null);
  const [dsAcceptSpecs, setDsAcceptSpecs] = useState<Record<string, boolean>>({});
  const [dsAcceptDetails, setDsAcceptDetails] = useState(false);
  const [dsApplying, setDsApplying] = useState(false);

  const [printModalOpen, setPrintModalOpen] = useState(false);
  const [editModalOpen, setEditModalOpen] = useState(false);

  // Stock thresholds — per root location minimums.
  const [thresholds, setThresholds] = useState<StockThreshold[]>([]);
  const [thresholdModalOpen, setThresholdModalOpen] = useState(false);
  const [editingThreshold, setEditingThreshold] = useState<StockThreshold | null>(null);
  const [thresholdForm, setThresholdForm] = useState({ locationId: 0, minimumQuantity: 0 });
  const [thresholdError, setThresholdError] = useState<string | null>(null);

  const splitAttachments = (atts: PartAttachment[]) => {
    setImages(atts.filter((a) => a.type === 'PHOTO'));
    setDatasheets(atts.filter((a) => a.type === 'DATASHEET'));
    setAttachments(atts.filter((a) => a.type === 'ATTACHMENT'));
  };

  const refreshAttachments = () =>
    getPartAttachments(partId).then(splitAttachments).catch(() => {});

  const loadData = () => {
    Promise.all([
      getPart(partId),
      getPartStock(partId),
      getMyLocations(),
      getLocations(),
      getPartAttachments(partId),
    ])
      .then(([p, s, l, all, atts]) => {
        setPart(p);
        setStock(s);
        setLocations(l);
        setAllLocations(all);
        splitAttachments(atts);
        // Movement history is supplementary — load best-effort, don't fail the page
        getPartMovements(partId)
          .then(setMovements)
          .catch(() => setMovements([]));
        // Match against the full definition list (every key has a name + group),
        // not the category-scoped subset. Best-effort — don't fail the page if unavailable.
        getSpecDefinitions()
          .then(setSpecDefs)
          .catch(() => setSpecDefs([]));
        // Thresholds — best-effort
        getStockThresholds(partId)
          .then(setThresholds)
          .catch(() => setThresholds([]));
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(loadData, [partId]);

  // Is the component cache installed? An installation without the snapshot must not show a button
  // that can only ever find nothing, and a failure here simply means "no cache".
  useEffect(() => {
    if (!canEdit) return;
    getComponentCacheStatus()
      .then((s) => setCcAvailable(s.available))
      .catch(() => setCcAvailable(false));
  }, [canEdit]);

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
    const q = part?.mpn || part?.partNumber || '';
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

  // ── AI lookup ────────────────────────────────────────────────────────────

  const runAiSearch = (q: string) => {
    if (!q.trim()) return;
    setAiLoading(true);
    setAiError(null);
    setAiResults([]);
    setAiPicked(null);
    searchPartsOnline(q.trim())
      .then((results) => {
        setAiResults(results);
        setAiSearched(true);
      })
      .catch((err) => setAiError((err as Error).message))
      .finally(() => setAiLoading(false));
  };

  const openAiLookup = () => {
    const q = part?.mpn || part?.partNumber || '';
    setAiQuery(q);
    setAiResults([]);
    setAiPicked(null);
    setAiError(null);
    setAiSearched(false);
    setAiModalOpen(true);
  };

  /**
   * Classify incoming specs against what the part already holds. Values that match are dropped:
   * there is nothing to decide about them.
   *
   * Shared by the web lookup and the datasheet reader — both present the user with the same
   * question ("this source says X, the part says Y, which do you keep?") and must answer it the
   * same way, or the two actions would default differently on the same part.
   */
  const classifySpecs = (
    incoming: Record<string, string>,
    pageByKey?: Record<string, number | null | undefined>,
  ): AiSpecRow[] => {
    const existing = part?.specs ?? {};
    // Built here rather than reusing the render-scope map so this does not depend on where that
    // one happens to be declared relative to the handlers.
    const byKey = new Map(specDefs.map((d) => [d.jsonName, d]));
    return Object.entries(incoming)
      .map(([key, newValue]) => {
        const oldValue = existing[key] == null ? '' : String(existing[key]);
        const verdict: SpecVerdict =
          oldValue.trim() === '' ? 'new' : oldValue.trim() === newValue.trim() ? 'same' : 'conflict';
        return {
          key,
          label: byKey.get(key)?.name ?? key,
          known: byKey.has(key),
          oldValue,
          newValue,
          verdict,
          page: pageByKey?.[key] ?? null,
        };
      })
      .filter((r) => r.verdict !== 'same')
      .sort((a, b) => (a.verdict === b.verdict ? a.label.localeCompare(b.label) : a.verdict === 'new' ? -1 : 1));
  };

  const aiSpecRows = (result: PartSearchResult): AiSpecRow[] =>
    classifySpecs(parseAiSpecs(result.specs));

  const pickAiResult = (result: PartSearchResult) => {
    setAiPicked(result);
    // Columns default to accepted, as in the OctoPart flow — only changed ones are rendered.
    const fields = {} as Record<AiFieldKey, boolean>;
    for (const f of AI_FIELDS) fields[f.key] = true;
    setAiAcceptFields(fields);
    // Specs default to "fill the gaps, leave curated values alone".
    const specs: Record<string, boolean> = {};
    for (const row of aiSpecRows(result)) specs[row.key] = row.verdict === 'new';
    setAiAcceptSpecs(specs);
  };

  const handleApplyAiLookup = async () => {
    if (!aiPicked || !part) return;
    setAiApplying(true);
    setAiError(null);
    try {
      const body: AiApplyRequest = {};
      for (const f of AI_FIELDS) {
        const newVal = aiPicked[f.from];
        if (aiAcceptFields[f.key] && newVal) body[f.key] = newVal;
      }
      const specs: Record<string, string> = {};
      for (const row of aiSpecRows(aiPicked)) {
        if (aiAcceptSpecs[row.key]) specs[row.key] = row.newValue;
      }
      if (Object.keys(specs).length > 0) body.specs = specs;
      await applyAiLookup(part.id, body);
      setAiModalOpen(false);
      loadData();
    } catch (err) {
      setAiError((err as Error).message);
    } finally {
      setAiApplying(false);
    }
  };

  // ── Component cache ──────────────────────────────────────────────────────

  const runCcSearch = (q: string) => {
    if (!q.trim()) return;
    setCcLoading(true);
    setCcError(null);
    setCcResults([]);
    setCcPicked(null);
    searchComponentCache(q.trim())
      .then((results) => {
        setCcResults(results);
        setCcSearched(true);
      })
      .catch((err) => setCcError((err as Error).message))
      .finally(() => setCcLoading(false));
  };

  const openComponentCache = () => {
    const q = part?.mpn || part?.partNumber || '';
    setCcQuery(q);
    setCcResults([]);
    setCcPicked(null);
    setCcError(null);
    setCcSearched(false);
    setCcModalOpen(true);
    // Search immediately. The AI modal waits for a button because each press costs money; this one
    // is a local index query, and making the user press Search on a term already on the screen
    // would be ceremony for nothing.
    runCcSearch(q);
  };

  const ccSpecRows = (detail: ComponentCacheDetail): AiSpecRow[] => classifySpecs(detail.specs);

  /** Picking a result runs the second call — the match alone carries no specifications. */
  const pickCcResult = (match: ComponentCacheMatch) => {
    setCcLoading(true);
    setCcError(null);
    loadComponentCachePart(match.lcsc)
      .then((detail) => {
        setCcPicked(detail);
        const fields = {} as Record<CacheFieldKey, boolean>;
        for (const f of CACHE_FIELDS) fields[f.key] = true;
        setCcAcceptFields(fields);
        // Same default as every other source: fill the gaps, leave curated values alone.
        const specs: Record<string, boolean> = {};
        for (const row of ccSpecRows(detail)) specs[row.key] = row.verdict === 'new';
        setCcAcceptSpecs(specs);
      })
      .catch((err) => setCcError((err as Error).message))
      .finally(() => setCcLoading(false));
  };

  const handleApplyComponentCache = async () => {
    if (!ccPicked || !part) return;
    setCcApplying(true);
    setCcError(null);
    try {
      const body: AiApplyRequest = {};
      for (const f of CACHE_FIELDS) {
        const newVal = ccPicked[f.from];
        if (ccAcceptFields[f.key] && newVal) body[f.key] = newVal;
      }
      const specs: Record<string, string> = {};
      for (const row of ccSpecRows(ccPicked)) {
        if (ccAcceptSpecs[row.key]) specs[row.key] = row.newValue;
      }
      if (Object.keys(specs).length > 0) body.specs = specs;
      await applyAiLookup(part.id, body);
      setCcModalOpen(false);
      loadData();
    } catch (err) {
      setCcError((err as Error).message);
    } finally {
      setCcApplying(false);
    }
  };

  // ── Datasheet extraction ─────────────────────────────────────────────────

  /** Rows for the confirm step, carrying the page each value was read from. */
  const dsSpecRows = (result: DatasheetExtraction): AiSpecRow[] => {
    const incoming: Record<string, string> = {};
    const pages: Record<string, number | null | undefined> = {};
    for (const s of result.specs) {
      incoming[s.key] = s.value;
      pages[s.key] = s.page;
    }
    return classifySpecs(incoming, pages);
  };

  const runDatasheetExtract = (attachment: PartAttachment) => {
    setDsLoading(true);
    setDsError(null);
    setDsResult(null);
    extractDatasheetSpecs(partId, attachment.id)
      .then((result) => {
        setDsResult(result);
        // Same default as the AI lookup: fill the gaps, leave curated values alone.
        const accept: Record<string, boolean> = {};
        for (const row of dsSpecRows(result)) accept[row.key] = row.verdict === 'new';
        setDsAcceptSpecs(accept);
        // Details is one long block, not a merge — only offer it ticked when there is nothing to
        // overwrite.
        setDsAcceptDetails(!!result.details && !part?.details);
      })
      .catch((err) => setDsError((err as Error).message))
      .finally(() => setDsLoading(false));
  };

  const openDatasheetExtract = (attachment: PartAttachment) => {
    setDsAttachment(attachment);
    setDsResult(null);
    setDsError(null);
    setDsModalOpen(true);
    runDatasheetExtract(attachment);
  };

  const handleApplyDatasheetExtract = async () => {
    if (!dsResult || !part) return;
    setDsApplying(true);
    setDsError(null);
    try {
      const body: AiApplyRequest = {};
      const specs: Record<string, string> = {};
      for (const row of dsSpecRows(dsResult)) {
        if (dsAcceptSpecs[row.key]) specs[row.key] = row.newValue;
      }
      if (Object.keys(specs).length > 0) body.specs = specs;
      if (dsAcceptDetails && dsResult.details) body.details = dsResult.details;
      await applyAiLookup(part.id, body);
      setDsModalOpen(false);
      loadData();
    } catch (err) {
      setDsError((err as Error).message);
    } finally {
      setDsApplying(false);
    }
  };

  // Open the "add stock" modal. With an entry the target location is fixed to that line; without
  // one (top-level button) the user picks a location, pre-selecting their last-used one.
  const openAddStock = (entry?: StockEntry) => {
    const defaultLoc =
      user?.lastLocationId && locations.some((l) => l.id === user.lastLocationId)
        ? user.lastLocationId
        : 0;
    setOpEntry(entry ?? null);
    setOpForm({
      ...emptyOpForm,
      locationId: entry ? entry.locationId : defaultLoc,
      unitPrice: entry?.unitPrice ?? null,
    });
    setFormError(null);
    setStockOp('add');
  };

  const openTakeStock = (entry: StockEntry) => {
    setOpEntry(entry);
    setOpForm({ ...emptyOpForm, locationId: entry.locationId });
    setFormError(null);
    setStockOp('take');
  };

  const openMoveStock = (entry: StockEntry) => {
    setOpEntry(entry);
    setOpForm({ ...emptyOpForm, locationId: entry.locationId });
    setFormError(null);
    setStockOp('move');
  };

  const handleSubmitStockOp = async () => {
    if (!stockOp) return;
    setSaving(true);
    setFormError(null);
    try {
      if (stockOp === 'add') {
        await addStock({
          partId,
          locationId: opEntry ? opEntry.locationId : opForm.locationId,
          quantity: opForm.quantity,
          unitPrice: opForm.unitPrice,
          comments: opForm.comment || null,
        });
        // Adding stock updates the user's last-used location; refresh so it pre-selects next time.
        refresh();
      } else if (stockOp === 'take') {
        await takeStock({
          partId,
          locationId: opForm.locationId,
          quantity: opForm.quantity,
          comments: opForm.comment || null,
        });
      } else {
        await moveStock({
          partId,
          fromLocationId: opForm.locationId,
          toLocationId: opForm.destLocationId,
          quantity: opForm.quantity,
          comments: opForm.comment || null,
        });
      }
      setStockOp(null);
      loadData();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const openAddThreshold = () => {
    setEditingThreshold(null);
    setThresholdForm({ locationId: 0, minimumQuantity: 1 });
    setThresholdError(null);
    setThresholdModalOpen(true);
  };

  const openEditThreshold = (t: StockThreshold) => {
    setEditingThreshold(t);
    setThresholdForm({ locationId: t.locationId, minimumQuantity: t.minimumQuantity });
    setThresholdError(null);
    setThresholdModalOpen(true);
  };

  const handleSubmitThreshold = async () => {
    if (!thresholdForm.locationId) return;
    setThresholdError(null);
    try {
      await upsertStockThreshold({
        partId,
        locationId: thresholdForm.locationId,
        minimumQuantity: thresholdForm.minimumQuantity,
      });
      setThresholdModalOpen(false);
      getStockThresholds(partId).then(setThresholds).catch(() => {});
    } catch (e: unknown) {
      setThresholdError((e as Error).message);
    }
  };

  const handleDeletePart = async () => {
    if (!part) return;
    if (!confirm(`Delete part "${part.partNumber}"? This cannot be undone.`)) return;
    try {
      await deletePart(part.id);
      navigate(partsListUrl);
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const handleDeleteThreshold = async (t: StockThreshold) => {
    if (!confirm(`Remove threshold for "${t.locationName}"?`)) return;
    try {
      await deleteStockThreshold(t.id);
      setThresholds((prev) => prev.filter((x) => x.id !== t.id));
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const handleDeleteStock = async (entry: StockEntry) => {
    if (!confirm(`Remove stock at "${entry.locationBreadcrumb || entry.locationName}"?`)) return;
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
      await uploadPartAttachment(partId, file, 'PHOTO');
      await refreshAttachments();
    } catch (err: unknown) {
      setUploadError((err as Error).message);
    } finally {
      setUploading(false);
    }
  };

  const handleDeleteImage = async (image: PartAttachment) => {
    if (!confirm('Remove this image?')) return;
    try {
      await deletePartAttachment(partId, image.id);
      await refreshAttachments();
    } catch (err: unknown) {
      alert((err as Error).message);
    }
  };

  // Datasheets & generic attachments — upload the original file as-is (no conversion, no cap).
  const handleFileUpload = async (
    e: React.ChangeEvent<HTMLInputElement>,
    type: AttachmentType,
  ) => {
    const file = e.target.files?.[0];
    if (!file) return;
    e.target.value = '';
    setFileBusy(true);
    setFileError(null);
    try {
      await uploadPartAttachment(partId, file, type);
      await refreshAttachments();
    } catch (err: unknown) {
      setFileError((err as Error).message);
    } finally {
      setFileBusy(false);
    }
  };

  const handleDownloadDatasheet = async () => {
    if (!part?.datasheetUrl) return;
    setFileBusy(true);
    setFileError(null);
    try {
      await addAttachmentFromUrl(partId, part.datasheetUrl, 'DATASHEET');
      await refreshAttachments();
    } catch (err: unknown) {
      setFileError((err as Error).message);
    } finally {
      setFileBusy(false);
    }
  };

  const handleRemoveDatasheetUrl = async () => {
    if (!part) return;
    if (!confirm('Remove the datasheet URL from this part?')) return;
    setFileBusy(true);
    setFileError(null);
    try {
      const request: PartRequest = {
        partNumber: part.partNumber,
        description: part.description,
        details: part.details,
        manufacturer: part.manufacturer,
        personalNumber: part.personalNumber,
        datasheetUrl: undefined,
        specs: part.specs,
        categoryId: part.categoryId ?? null,
      };
      const updated = await updatePart(partId, request);
      setPart(updated);
    } catch (err: unknown) {
      setFileError((err as Error).message);
    } finally {
      setFileBusy(false);
    }
  };

  const handleDeleteAttachment = async (att: PartAttachment) => {
    if (!confirm('Remove this file?')) return;
    try {
      await deletePartAttachment(partId, att.id);
      await refreshAttachments();
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
        await uploadPartAttachment(partId, file, 'PHOTO');
      } catch (err: unknown) {
        errors.push((err as Error).message);
      }
      i++;
    }
    setAttaching(false);

    await refreshAttachments();

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

  const runDatasheetSearch = (q: string, forceAi = false) => {
    if (!q.trim()) return;
    setDatasheetsLoading(true);
    setDatasheetSuggestions([]);
    setDatasheetOutcome(null);
    searchPartDatasheets(q.trim(), forceAi)
      .then((res) => {
        setDatasheetSuggestions(res.results ?? []);
        setDatasheetOutcome(res);
      })
      .catch((err: unknown) => {
        setDatasheetSuggestions([]);
        setDatasheetOutcome({
          results: [],
          source: 'NONE',
          webSearchStatus: 'FAILED',
          detail: (err as Error).message,
        });
      })
      .finally(() => setDatasheetsLoading(false));
  };

  const openFindDatasheet = () => {
    const q = [part?.manufacturer, part?.partNumber].filter(Boolean).join(' ') || part?.partNumber || '';
    setDatasheetQuery(q);
    setDatasheetAttachError(null);
    setDatasheetModalOpen(true);
    runDatasheetSearch(q);
  };

  const handleAttachDatasheet = async (url: string) => {
    setDatasheetAttaching(true);
    setDatasheetAttachError(null);
    try {
      await addAttachmentFromUrl(partId, url, 'DATASHEET');
      await refreshAttachments();
      setDatasheetModalOpen(false);
    } catch (err: unknown) {
      setDatasheetAttachError((err as Error).message);
    } finally {
      setDatasheetAttaching(false);
    }
  };

  const stockColumns: Column<StockEntry>[] = [
    { key: 'locationName', header: 'Location', render: (row) => row.locationBreadcrumb || row.locationName },
    {
      key: 'quantity',
      header: 'Quantity',
      render: (row) => <Badge variant="blue">{row.quantity}</Badge>,
    },
    {
      key: 'unitPrice',
      header: 'Avg. Cost',
      render: (row) =>
        row.unitPrice != null ? (
          <span className="font-mono text-sm">{formatMoney(row.unitPrice)}</span>
        ) : (
          <span className="text-gray-400">—</span>
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
    {
      key: 'locationName',
      header: 'Location',
      render: (m) =>
        m.type === 'MOVE' && m.targetLocationId != null ? (
          <span className="text-sm">
            {m.locationBreadcrumb || m.locationName}
            <span className="mx-1 text-gray-400">→</span>
            {m.targetLocationBreadcrumb || m.targetLocationName}
          </span>
        ) : (
          m.locationBreadcrumb || m.locationName || '—'
        ),
    },
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
    .map((d) => ({
      label: d.name,
      value: formatSpecValue(d, partSpecs[d.jsonName]),
      group: d.groupName ?? UNGROUPED,
    }));

  // Raw keys not covered by any definition
  const unmatchedEntries = Object.entries(partSpecs).filter(
    ([k, v]) => !specDefsMap.has(k) && v !== ''
  );

  // Group every spec row under its spec group. Raw keys that no definition covers have no group,
  // so they collect in a trailing bucket rather than being silently attached to a real one.
  const specRows = [
    ...definedSpecEntries,
    ...unmatchedEntries.map(([k, v]) => ({ label: k, value: String(v), group: UNGROUPED })),
  ];
  // Group order follows the definition list, which the API returns in display order; the
  // ungrouped bucket always comes last.
  const groupOrder: string[] = [];
  for (const def of specDefs) {
    const name = def.groupName ?? UNGROUPED;
    if (name !== UNGROUPED && !groupOrder.includes(name)) groupOrder.push(name);
  }
  groupOrder.push(UNGROUPED);
  const specGroups = groupOrder.map((name) => ({
    label: name,
    rows: specRows.filter((r) => r.group === name),
  }));
  const hasSpecs = specRows.length > 0;

  return (
    <div className="p-4 md:p-8">
      {/* Constrain content width — full-bleed cards look lost on wide monitors. */}
      <div className="mx-auto max-w-6xl">
      {/* Breadcrumb */}
      <nav className="mb-4 text-sm text-gray-500">
        <Link to={partsListUrl} className="hover:underline">
          Parts
        </Link>{' '}
        / <span className="text-gray-800 font-medium">{part.partNumber}</span>
      </nav>

      {/* Part header card — image left, details right; stacked below sm, where the fixed 208px
          image column left the details ~110px and broke the description onto one word per line. */}
      <div className="mb-6 rounded-xl border border-gray-200 bg-surface p-6 shadow-sm">
        <div className="flex flex-col gap-4 lg:flex-row lg:gap-6">
          {/* Image column */}
          <div className="flex shrink-0 flex-col gap-2">
            {/* Primary image */}
            <div className="flex h-52 w-52 items-center justify-center overflow-hidden rounded-lg border border-gray-200 bg-gray-50">
              {primaryImage ? (
                <img
                  src={attachmentUrl(partId, primaryImage.id)}
                  alt={part.partNumber}
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
                        src={attachmentUrl(partId, img.id)}
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
                        className="absolute -right-1 -top-1 flex h-4 w-4 items-center justify-center rounded-full bg-red-500 text-xs text-white"
                        title="Remove"
                      >
                        ×
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}

            {/* Find image (whenever there's room for more photos) */}
            {canEdit && images.length < 5 && (
              <button
                onClick={openFindImage}
                className="inline-flex items-center gap-1.5 rounded-lg border border-dashed border-gray-300 px-3 py-1.5 text-xs text-gray-500 hover:border-blue-400 hover:text-blue-600"
              >
                {SearchIcon}
                Find image
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
            {/* Wraps below sm: the action group is ~330px and used to be shrink-0 at every width,
                which squeezed the title column to ~60px on a phone and pushed the buttons off the
                page. From sm up the sm: overrides restore the original single row. */}
            <div className="flex flex-wrap items-start justify-between gap-3 lg:flex-nowrap">
              <div className="min-w-0">
                <h1 className="flex flex-wrap items-center gap-2 text-2xl font-bold font-mono text-gray-900">
                  {part.partNumber}
                  {part.personalNumber && (
                    <span
                      className="rounded-md bg-amber-100 px-2 py-0.5 text-xs font-medium normal-case text-amber-700 ring-1 ring-inset ring-amber-600/20"
                      title="This is a personal/internal number, not a real manufacturer part number"
                    >
                      Personal number
                    </span>
                  )}
                </h1>
                {part.description && (
                  <p className="mt-1 text-sm text-gray-600">{part.description}</p>
                )}
                <div className="mt-2 flex flex-wrap items-center gap-2">
                  {part.footprint && (
                    <span className="rounded-md bg-gray-100 px-2 py-1 text-xs font-medium text-gray-600 ring-1 ring-inset ring-gray-500/20">
                      {part.footprint}
                    </span>
                  )}
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-2 lg:shrink-0">
                {/* AI lookup. Unconditional for an editor — no credentials to hold and no
                    "already linked" state to exclude it, which is the point: a part typed in by
                    hand is exactly the one that needs it, and it must be available on every part. */}
                {/* The cache first — it is free and instant, so it belongs to the left of the
                    lookup that costs money and takes seconds. */}
                {canEdit && ccAvailable && (
                  <button
                    onClick={openComponentCache}
                    title="Fill in details and specifications from the local component cache — free, no Internet search"
                    className="inline-flex items-center gap-1.5 rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50"
                  >
                    {SearchIcon}
                    Look up in cache
                  </button>
                )}
                {canEdit && (
                  <button
                    onClick={openAiLookup}
                    title="Look this part up and fill in missing details"
                    className="inline-flex items-center gap-1.5 rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50"
                  >
                    {SearchIcon}
                    Look up specs
                  </button>
                )}
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
                        className="inline-flex items-center gap-1.5 rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50 disabled:opacity-50"
                      >
                        {SearchIcon}
                        Search OctoPart
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
                {canEdit && (
                  <button
                    onClick={() => setEditModalOpen(true)}
                    className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50"
                  >
                    Edit
                  </button>
                )}
                {canEdit && (
                  <button
                    onClick={handleDeletePart}
                    className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm text-red-600 hover:bg-red-50"
                  >
                    Delete
                  </button>
                )}
                <button
                  onClick={() => setPrintModalOpen(true)}
                  title="Print a label for this part"
                  className="inline-flex items-center gap-1.5 rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50"
                >
                  {TagIcon}
                  Print label
                </button>
                <button
                  onClick={() => navigate(-1)}
                  className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50"
                >
                  ← Back
                </button>
              </div>
            </div>

            {/* Single column at every width — these are label/value lines of very uneven length
                (a category breadcrumb next to a one-word manufacturer), which read better stacked. */}
            <div className="mt-4 grid grid-cols-1 gap-4 text-sm">
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
              {part.tags && part.tags.length > 0 && (
                <div>
                  <span className="font-medium text-gray-500">Tags:</span>{' '}
                  <div className="mt-1 inline-flex flex-wrap gap-1 align-middle">
                    {part.tags.map((t) => (
                      <Badge key={t} variant="blue">{t}</Badge>
                    ))}
                  </div>
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
              {part.details && (
                <div>
                  <span className="font-medium text-gray-500">Details:</span>{' '}
                  <span className="text-gray-800 whitespace-pre-wrap">{part.details}</span>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Datasheets & attachments — original files stored as binary on the part */}
      <div className="mb-6 rounded-xl border border-gray-200 bg-surface p-6 shadow-sm">
        <h2 className="mb-4 flex items-center gap-2 text-lg font-semibold text-gray-900">
          <span className="h-5 w-1 rounded-full bg-blue-500" />
          Documents
        </h2>
        <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
          {/* Datasheets */}
          <div>
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-blue-700/80">
              Datasheets
            </h3>
            {datasheets.length === 0 ? (
              <p className="text-sm text-gray-400">No datasheet files stored.</p>
            ) : (
              <ul className="space-y-1">
                {datasheets.map((d) => (
                  <li key={d.id} className="flex items-center gap-2 text-sm">
                    <svg
                      className="h-4 w-4 shrink-0 text-blue-600"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth={1.8}
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      aria-hidden="true"
                    >
                      <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
                      <path d="M14 3v5h5" />
                      <path d="M9 13h6" />
                      <path d="M9 17h6" />
                    </svg>
                    <a
                      href={attachmentUrl(partId, d.id)}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="truncate text-blue-600 hover:underline"
                    >
                      {d.filename ?? `datasheet-${d.id}`}
                    </a>
                    {canEdit && (
                      <>
                        <button
                          onClick={() => openDatasheetExtract(d)}
                          title="Read this PDF and propose specifications and a description from it"
                          className="shrink-0 text-xs text-blue-600 hover:underline"
                        >
                          Get specs
                        </button>
                        <button
                          onClick={() => handleDeleteAttachment(d)}
                          className="shrink-0 text-xs text-red-600 hover:underline"
                        >
                          Remove
                        </button>
                      </>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {part.datasheetUrl && (
              <div className="mt-2 flex items-center gap-2 text-xs text-gray-400">
                <span className="shrink-0">Octopart datasheet:</span>
                <a
                  href={part.datasheetUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="truncate text-blue-600 hover:underline"
                >
                  {part.datasheetUrl}
                </a>
                {canEdit && (
                  <span className="ml-auto flex shrink-0 gap-2">
                    <button
                      onClick={handleDownloadDatasheet}
                      disabled={fileBusy}
                      title={`Download from ${part.datasheetUrl}`}
                      className="hover:underline disabled:opacity-50"
                    >
                      Download
                    </button>
                    <button
                      onClick={() => setEditModalOpen(true)}
                      className="hover:underline"
                    >
                      Edit
                    </button>
                    <button
                      onClick={handleRemoveDatasheetUrl}
                      disabled={fileBusy}
                      className="text-red-600 hover:underline disabled:opacity-50"
                    >
                      Remove
                    </button>
                  </span>
                )}
              </div>
            )}
            {canEdit && (
              <div className="mt-3 flex flex-wrap gap-2">
                <input
                  ref={datasheetInputRef}
                  type="file"
                  className="hidden"
                  onChange={(e) => handleFileUpload(e, 'DATASHEET')}
                />
                <button
                  onClick={() => datasheetInputRef.current?.click()}
                  disabled={fileBusy}
                  className="rounded-lg border border-dashed border-gray-300 px-3 py-1.5 text-xs text-gray-500 hover:border-blue-400 hover:text-blue-600 disabled:opacity-50"
                >
                  + Upload datasheet
                </button>
                <button
                  onClick={openFindDatasheet}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-dashed border-gray-300 px-3 py-1.5 text-xs text-gray-500 hover:border-blue-400 hover:text-blue-600"
                >
                  <svg
                    className="h-3.5 w-3.5 shrink-0"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth={1.8}
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    aria-hidden="true"
                  >
                    <circle cx="11" cy="11" r="7" />
                    <path d="m20 20-3.5-3.5" />
                  </svg>
                  Find datasheet
                </button>
              </div>
            )}
          </div>

          {/* Generic attachments */}
          <div>
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-blue-700/80">
              Attachments
            </h3>
            {attachments.length === 0 ? (
              <p className="text-sm text-gray-400">No attachments.</p>
            ) : (
              <ul className="space-y-1">
                {attachments.map((a) => (
                  <li key={a.id} className="flex items-center gap-2 text-sm">
                    <svg
                      className="h-4 w-4 shrink-0 text-blue-600"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth={1.8}
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      aria-hidden="true"
                    >
                      <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
                    </svg>
                    <a
                      href={attachmentUrl(partId, a.id)}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="truncate text-blue-600 hover:underline"
                    >
                      {a.filename ?? `attachment-${a.id}`}
                    </a>
                    {canEdit && (
                      <button
                        onClick={() => handleDeleteAttachment(a)}
                        className="shrink-0 text-xs text-red-600 hover:underline"
                      >
                        Remove
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {canEdit && (
              <div className="mt-3">
                <input
                  ref={attachmentInputRef}
                  type="file"
                  className="hidden"
                  onChange={(e) => handleFileUpload(e, 'ATTACHMENT')}
                />
                <button
                  onClick={() => attachmentInputRef.current?.click()}
                  disabled={fileBusy}
                  className="rounded-lg border border-dashed border-gray-300 px-3 py-1.5 text-xs text-gray-500 hover:border-blue-400 hover:text-blue-600 disabled:opacity-50"
                >
                  + Upload file
                </button>
              </div>
            )}
          </div>
        </div>
        {fileError && <p className="mt-3 text-sm text-red-600">{fileError}</p>}
      </div>

      {/* Specifications — grouped into three columns by major type */}
      {hasSpecs && (
        <div className="mb-6 rounded-xl border border-gray-200 bg-surface p-6 shadow-sm">
          <h2 className="mb-4 flex items-center gap-2 text-lg font-semibold text-gray-900">
            <span className="h-5 w-1 rounded-full bg-blue-500" />
            Specifications
          </h2>
          {/* Three columns of groups, each group's rows kept together under its heading. */}
          <div className="grid grid-cols-1 items-start gap-x-10 gap-y-6 sm:grid-cols-2 lg:grid-cols-3">
            {specGroups
              .filter((group) => group.rows.length > 0)
              .map((group) => (
                <div key={group.label}>
                  <h3 className="mb-2 border-b border-gray-200 pb-1.5 text-xs font-semibold uppercase tracking-wider text-blue-700/80">
                    {group.label}
                  </h3>
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
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
                </div>
              ))}
          </div>
        </div>
      )}

      {/* Stock section — tabbed: locations / thresholds / movements */}
      <div className="rounded-xl border border-gray-200 bg-surface shadow-sm">
        {/* Wraps below sm: the three tabs plus the action button need more than a phone's width,
            which pushed the button past the card edge. sm: restores the original single row. */}
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-gray-100 px-4 pt-4 sm:flex-nowrap sm:px-6">
          <div className="flex flex-wrap gap-1">
            {(
              [
                { key: 'locations', label: 'Locations' },
                { key: 'thresholds', label: 'Thresholds' },
                { key: 'movements', label: `Movements${movements.length ? ` (${movements.length})` : ''}` },
              ] as const
            ).map((tab) => (
              <button
                key={tab.key}
                onClick={() => setStockTab(tab.key)}
                className={`rounded-t-lg px-4 py-2 text-sm font-medium ${
                  stockTab === tab.key
                    ? 'border border-b-0 border-gray-200 bg-surface text-gray-900'
                    : 'text-gray-500 hover:text-gray-700'
                }`}
              >
                {tab.label}
              </button>
            ))}
          </div>
          {canEdit && stockTab === 'locations' && (
            <button
              onClick={() => openAddStock()}
              className="mb-2 rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
            >
              + Add Stock
            </button>
          )}
          {canEdit && stockTab === 'thresholds' && (
            <button
              onClick={openAddThreshold}
              className="mb-2 rounded-lg bg-amber-500 px-3 py-1.5 text-sm font-medium text-white hover:bg-amber-600"
            >
              + Set Threshold
            </button>
          )}
        </div>

        {stockTab === 'locations' && (
          <div className="p-6">
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
              actions={(entry) =>
                // Locations belong to the organisation, so any member who can edit parts may
                // change the stock held in them.
                canEdit ? (
                  <div className="flex justify-end gap-1">
                    <button
                      onClick={() => openAddStock(entry)}
                      className="rounded px-2 py-1 text-xs text-green-700 hover:bg-green-50"
                    >
                      Add
                    </button>
                    <button
                      onClick={() => openTakeStock(entry)}
                      disabled={entry.quantity <= 0}
                      className="rounded px-2 py-1 text-xs text-amber-700 hover:bg-amber-50 disabled:opacity-40"
                    >
                      Take
                    </button>
                    <button
                      onClick={() => openMoveStock(entry)}
                      disabled={entry.quantity <= 0}
                      className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50 disabled:opacity-40"
                    >
                      Move
                    </button>
                    <button
                      onClick={() => handleDeleteStock(entry)}
                      className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50"
                    >
                      Remove
                    </button>
                  </div>
                ) : null
              }
            />
          </div>
        )}

        {stockTab === 'thresholds' && (
          <div className="p-6">
            {thresholds.length === 0 ? (
              <p className="text-sm text-gray-400">No minimum stock thresholds set for this part.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-100 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                      <th className="pb-2 pr-4">Location</th>
                      <th className="pb-2 pr-4">On Hand</th>
                      <th className="pb-2 pr-4">Minimum</th>
                      <th className="pb-2 pr-4">Status</th>
                      {canEdit && <th className="pb-2" />}
                    </tr>
                  </thead>
                  <tbody>
                    {thresholds.map((t) => (
                      <tr key={t.id} className="border-b border-gray-50 last:border-0">
                        <td className="py-2 pr-4 font-medium text-gray-800">{t.locationName}</td>
                        <td className="py-2 pr-4 font-mono">{t.totalQuantity}</td>
                        <td className="py-2 pr-4 font-mono">{t.minimumQuantity}</td>
                        <td className="py-2 pr-4">
                          {t.lowStock ? (
                            <Badge variant="red">Low — {t.minimumQuantity - t.totalQuantity} short</Badge>
                          ) : (
                            <Badge variant="green">OK</Badge>
                          )}
                        </td>
                        {canEdit && (
                          <td className="py-2">
                            <div className="flex gap-1">
                              <button
                                onClick={() => openEditThreshold(t)}
                                className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
                              >
                                Edit
                              </button>
                              <button
                                onClick={() => handleDeleteThreshold(t)}
                                className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50"
                              >
                                Remove
                              </button>
                            </div>
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {stockTab === 'movements' && (
          <div className="p-6">
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

      {/* Threshold modal */}
      <Modal
        open={thresholdModalOpen}
        onClose={() => setThresholdModalOpen(false)}
        title={editingThreshold ? 'Edit threshold' : 'Set threshold'}
      >
        <FormField
          as="select"
          label="Root location *"
          value={thresholdForm.locationId || ''}
          onChange={(e) => setThresholdForm({ ...thresholdForm, locationId: Number(e.target.value) })}
          disabled={!!editingThreshold}
        >
          <option value="">— Select root location —</option>
          {[...allLocations]
            .filter((l) => !l.parentId)
            .sort((a, b) => a.name.localeCompare(b.name))
            .map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
        </FormField>
        <FormField
          label="Minimum quantity *"
          type="number"
          min={0}
          value={thresholdForm.minimumQuantity}
          onChange={(e) => setThresholdForm({ ...thresholdForm, minimumQuantity: Number(e.target.value) })}
        />
        {thresholdError && <p className="mb-3 text-sm text-red-600">{thresholdError}</p>}
        <div className="flex justify-end gap-2">
          <button
            onClick={() => setThresholdModalOpen(false)}
            className="rounded-lg border border-gray-200 px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmitThreshold}
            disabled={!thresholdForm.locationId}
            className="rounded-lg bg-amber-500 px-4 py-2 text-sm font-medium text-white hover:bg-amber-600 disabled:opacity-40"
          >
            Save
          </button>
        </div>
      </Modal>


      {/* Stock operation modal (add / take / move) */}
      <Modal
        open={stockOp !== null}
        onClose={() => setStockOp(null)}
        title={
          stockOp === 'add'
            ? 'Add stock'
            : stockOp === 'take'
              ? 'Take stock'
              : stockOp === 'move'
                ? 'Move stock'
                : ''
        }
      >
        {stockOp && (
          <>
            {/* Source / target location: a fixed line when operating on an existing entry. */}
            {opEntry ? (
              <div className="mb-4 rounded-lg border border-gray-200 bg-gray-50 px-3 py-2 text-sm">
                <div className="text-xs font-medium uppercase tracking-wide text-gray-500">
                  {stockOp === 'move' ? 'From' : 'Location'}
                </div>
                <div className="font-medium text-gray-800">
                  {opEntry.locationBreadcrumb || opEntry.locationName}
                </div>
                <div className="text-xs text-gray-500">On hand: {opEntry.quantity}</div>
              </div>
            ) : (
              <FormField
                as="select"
                label="Location *"
                value={opForm.locationId || ''}
                onChange={(e) => {
                  const locId = Number(e.target.value);
                  const existing = stock.find((s) => s.locationId === locId);
                  setOpForm({
                    ...opForm,
                    locationId: locId,
                    unitPrice: existing?.unitPrice ?? null,
                  });
                }}
              >
                <option value="">— Select location —</option>
                {[...locations]
                  .sort((a, b) => a.breadcrumb.localeCompare(b.breadcrumb))
                  .map((l) => (
                    <option key={l.id} value={l.id}>
                      {l.breadcrumb || l.name}
                    </option>
                  ))}
              </FormField>
            )}

            {/* Destination picker — any user's location — for moves. */}
            {stockOp === 'move' && (
              <FormField
                as="select"
                label="To *"
                value={opForm.destLocationId || ''}
                onChange={(e) =>
                  setOpForm({ ...opForm, destLocationId: Number(e.target.value) })
                }
              >
                <option value="">— Select destination —</option>
                {[...allLocations]
                  .filter((l) => l.id !== opForm.locationId)
                  .sort((a, b) => a.breadcrumb.localeCompare(b.breadcrumb))
                  .map((l) => (
                    <option key={l.id} value={l.id}>
                      {l.breadcrumb || l.name}
                    </option>
                  ))}
              </FormField>
            )}

            <FormField
              label={
                stockOp === 'add'
                  ? 'Quantity to add *'
                  : stockOp === 'take'
                    ? 'Quantity to take *'
                    : 'Quantity to move *'
              }
              type="number"
              min={1}
              max={stockOp === 'add' ? undefined : opEntry?.quantity}
              value={opForm.quantity || ''}
              onChange={(e) => setOpForm({ ...opForm, quantity: Number(e.target.value) })}
            />

            {/* Price only makes sense when adding. */}
            {stockOp === 'add' && (
              <FormField
                label="Unit Price"
                type="number"
                min={0}
                step={0.01}
                placeholder="Optional"
                value={opForm.unitPrice ?? ''}
                onChange={(e) =>
                  setOpForm({
                    ...opForm,
                    unitPrice: e.target.value !== '' ? Number(e.target.value) : null,
                  })
                }
              />
            )}

            <FormField
              label="Comment"
              placeholder="Optional note for the stock movement"
              value={opForm.comment}
              onChange={(e) => setOpForm({ ...opForm, comment: e.target.value })}
            />

            {formError && <p className="mb-3 text-sm text-red-600">{formError}</p>}

            <div className="flex justify-end gap-3">
              <button
                onClick={() => setStockOp(null)}
                className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
              >
                Cancel
              </button>
              <button
                onClick={handleSubmitStockOp}
                disabled={
                  saving ||
                  opForm.quantity < 1 ||
                  (stockOp === 'add' && !opEntry && !opForm.locationId) ||
                  (stockOp === 'move' && !opForm.destLocationId) ||
                  (stockOp !== 'add' && !!opEntry && opForm.quantity > opEntry.quantity)
                }
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {saving
                  ? 'Saving…'
                  : stockOp === 'add'
                    ? 'Add stock'
                    : stockOp === 'take'
                      ? 'Take stock'
                      : 'Move stock'}
              </button>
            </div>
          </>
        )}
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

      {/* Find datasheet modal */}
      <Modal open={datasheetModalOpen} onClose={() => setDatasheetModalOpen(false)} title="Find datasheet">
        <div className="mb-4 flex gap-2">
          <input
            type="text"
            value={datasheetQuery}
            onChange={(e) => setDatasheetQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && runDatasheetSearch(datasheetQuery)}
            placeholder="e.g. Texas Instruments LM317"
            className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
          <button
            onClick={() => runDatasheetSearch(datasheetQuery)}
            disabled={!datasheetQuery.trim() || datasheetsLoading}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            Search
          </button>
          <button
            onClick={() => runDatasheetSearch(datasheetQuery, true)}
            disabled={!datasheetQuery.trim() || datasheetsLoading}
            title="Skip the web search and ask the AI to suggest datasheet links directly"
            className="shrink-0 rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50 disabled:opacity-50"
          >
            Ask AI instead
          </button>
        </div>

        {/* A blocked web search is not an empty one — say which happened rather than "not found". */}
        {!datasheetsLoading && datasheetOutcome?.webSearchStatus === 'BLOCKED' && datasheetSuggestions.length > 0 && (
          <p className="mb-3 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800 ring-1 ring-inset ring-amber-600/20">
            The web search was blocked by a bot check, so these are AI suggestions rather than search
            results.
          </p>
        )}

        <div className="min-h-[8rem]">
          {datasheetsLoading ? (
            <p className="text-sm text-gray-400">Searching for datasheets…</p>
          ) : datasheetSuggestions.length === 0 && datasheetOutcome?.webSearchStatus === 'BLOCKED' ? (
            <div className="text-sm text-gray-500">
              <p>
                The web search was blocked by a bot check — it did not run out of results, so this says
                nothing about whether a datasheet exists. Try again in a minute, or{' '}
                <button
                  onClick={() => runDatasheetSearch(datasheetQuery, true)}
                  disabled={!datasheetQuery.trim()}
                  className="text-blue-600 hover:underline disabled:opacity-50"
                >
                  ask the AI instead
                </button>
                .
              </p>
              {datasheetOutcome.detail && (
                <p className="mt-1 text-xs text-gray-400">{datasheetOutcome.detail}</p>
              )}
            </div>
          ) : datasheetSuggestions.length === 0 && datasheetOutcome?.webSearchStatus === 'FAILED' ? (
            <div className="text-sm text-gray-500">
              <p>
                The web search could not be completed. Try again, or{' '}
                <button
                  onClick={() => runDatasheetSearch(datasheetQuery, true)}
                  disabled={!datasheetQuery.trim()}
                  className="text-blue-600 hover:underline disabled:opacity-50"
                >
                  ask the AI instead
                </button>
                .
              </p>
              {datasheetOutcome.detail && (
                <p className="mt-1 text-xs text-gray-400">{datasheetOutcome.detail}</p>
              )}
            </div>
          ) : datasheetSuggestions.length === 0 ? (
            <p className="text-sm text-gray-400">
              No datasheets found. Try a different search term, or{' '}
              <button
                onClick={() => runDatasheetSearch(datasheetQuery, true)}
                disabled={!datasheetQuery.trim()}
                className="text-blue-600 hover:underline disabled:opacity-50"
              >
                ask the AI instead
              </button>
              .
            </p>
          ) : (
            <ul className="space-y-2">
              {datasheetSuggestions.map((d) => (
                <li
                  key={d.url}
                  className="flex items-center gap-3 rounded-lg border border-gray-200 px-3 py-2"
                >
                  <svg
                    className="h-5 w-5 shrink-0 text-blue-600"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth={1.8}
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    aria-hidden="true"
                  >
                    <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
                    <path d="M14 3v5h5" />
                    <path d="M9 13h6" />
                    <path d="M9 17h6" />
                  </svg>
                  <div className="min-w-0 flex-1">
                    <a
                      href={d.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      title={d.url}
                      className="block truncate text-sm text-blue-600 hover:underline"
                    >
                      {d.title ?? d.url}
                    </a>
                    <p className="truncate text-xs text-gray-400">{d.source ?? d.url}</p>
                  </div>
                  <button
                    onClick={() => handleAttachDatasheet(d.url)}
                    disabled={datasheetAttaching}
                    className="shrink-0 rounded-lg border border-gray-300 px-3 py-1.5 text-xs font-medium hover:bg-gray-50 disabled:opacity-50"
                  >
                    Use this
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        {datasheetAttachError && <p className="mt-3 text-sm text-red-600">{datasheetAttachError}</p>}

        <div className="mt-4 flex justify-end">
          <button
            onClick={() => setDatasheetModalOpen(false)}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
          >
            Cancel
          </button>
        </div>
      </Modal>

      {/* AI lookup — search / pick / confirm. Wide, because the confirm step shows old and new
          values side by side for every spec. */}
      <Modal
        open={aiModalOpen}
        onClose={() => setAiModalOpen(false)}
        title="Look up specs"
        wide
      >
        {!aiPicked ? (
          <>
            <div className="mb-2 flex gap-2">
              <input
                type="text"
                value={aiQuery}
                onChange={(e) => setAiQuery(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && runAiSearch(aiQuery)}
                placeholder="Part number, or a description of the part"
                className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
              <button
                onClick={() => runAiSearch(aiQuery)}
                disabled={!aiQuery.trim() || aiLoading}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                Search
              </button>
            </div>
            <p className="mb-3 text-xs text-gray-400">
              Searches the web and reads datasheets. Takes a few seconds and costs a little each
              time, so it does not run until you press Search.
            </p>

            {aiError && <p className="mb-3 text-sm text-red-600">{aiError}</p>}

            <div className="min-h-[8rem]">
              {aiLoading ? (
                <p className="text-sm text-gray-400">Searching… this usually takes 5–15 seconds.</p>
              ) : aiResults.length === 0 ? (
                aiSearched ? (
                  <div className="text-sm text-gray-500">
                    <p className="font-medium text-gray-700">The search found nothing.</p>
                    <p className="mt-1">
                      That is a real answer, not a failure: one-off, vintage and house-numbered
                      parts often have no public data to find. Try the manufacturer's own name for
                      it, or fill the specifications in by hand.
                    </p>
                  </div>
                ) : (
                  <p className="text-sm text-gray-400">
                    Nothing searched yet. Check the term above and press Search.
                  </p>
                )
              ) : (
                <ul className="divide-y divide-gray-100">
                  {aiResults.map((r, i) => (
                    <li key={`${r.mpn}-${i}`} className="flex items-start justify-between gap-3 py-3">
                      <div className="min-w-0">
                        <div className="font-mono text-sm font-medium text-gray-900">{r.mpn}</div>
                        {r.manufacturer && (
                          <div className="text-xs text-gray-500">{r.manufacturer}</div>
                        )}
                        {r.shortDescription && (
                          <div className="mt-0.5 truncate text-xs text-gray-600">
                            {r.shortDescription}
                          </div>
                        )}
                        <div className="mt-0.5 text-xs text-gray-400">
                          {r.specs.length} specification{r.specs.length === 1 ? '' : 's'}
                          {r.category ? ` · ${r.category}` : ''}
                        </div>
                      </div>
                      <button
                        onClick={() => pickAiResult(r)}
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
          (() => {
            const rows = aiSpecRows(aiPicked);
            const newCount = rows.filter((r) => r.verdict === 'new').length;
            const conflictCount = rows.filter((r) => r.verdict === 'conflict').length;
            // Only columns that would actually change are worth a row.
            const fieldChanges = AI_FIELDS.map((f) => ({
              field: f,
              oldVal: (part?.[f.key] as string | undefined) ?? '',
              newVal: aiPicked[f.from] ?? '',
            })).filter((c) => c.newVal && c.newVal !== c.oldVal);
            const setAll = (only: SpecVerdict | null) => {
              const next: Record<string, boolean> = {};
              for (const r of rows) next[r.key] = only == null ? false : r.verdict === only;
              setAiAcceptSpecs(next);
            };
            return (
              <>
                <p className="mb-3 text-sm text-gray-600">
                  Found <span className="font-mono">{aiPicked.mpn}</span>
                  {aiPicked.manufacturer ? ` — ${aiPicked.manufacturer}` : ''}
                  {aiPicked.category ? (
                    <span className="text-gray-400"> · suggested category: {aiPicked.category}</span>
                  ) : null}
                </p>

                {fieldChanges.length > 0 && (
                  <div className="mb-4">
                    <h4 className="mb-2 text-sm font-semibold text-gray-900">Part details</h4>
                    <div className="space-y-2">
                      {fieldChanges.map((c) => (
                        <label key={c.field.key} className="flex items-start gap-2 text-sm">
                          <input
                            type="checkbox"
                            checked={aiAcceptFields[c.field.key] ?? false}
                            onChange={(e) =>
                              setAiAcceptFields((prev) => ({
                                ...prev,
                                [c.field.key]: e.target.checked,
                              }))
                            }
                            className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                          />
                          <span className="min-w-0">
                            <span className="font-medium text-gray-700">{c.field.label}</span>
                            {c.oldVal && (
                              <span className="ml-2 text-gray-400 line-through">{c.oldVal}</span>
                            )}
                            <span className="ml-2 break-words text-gray-900">{c.newVal}</span>
                          </span>
                        </label>
                      ))}
                    </div>
                  </div>
                )}

                <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
                  <h4 className="text-sm font-semibold text-gray-900">Specifications</h4>
                  {rows.length > 0 && (
                    <span className="text-xs text-gray-500">
                      {newCount} new, {conflictCount} differ from stored values. Unticked rows are
                      left as they are.
                    </span>
                  )}
                </div>
                {rows.length > 0 && (
                  <div className="mb-2 flex gap-3 text-xs">
                    <button
                      type="button"
                      onClick={() => setAll('new')}
                      className="text-blue-600 hover:underline"
                    >
                      Select all new
                    </button>
                    <button
                      type="button"
                      onClick={() => setAll(null)}
                      className="text-blue-600 hover:underline"
                    >
                      Select none
                    </button>
                  </div>
                )}

                {rows.length === 0 ? (
                  <p className="text-sm text-gray-500">
                    Nothing to add — every specification this lookup returned already matches what
                    the part holds.
                  </p>
                ) : (
                  <ul className="max-h-72 divide-y divide-gray-100 overflow-y-auto">
                    {rows.map((r) => (
                      <li key={r.key} className="py-2">
                        <label className="flex items-start gap-2 text-sm">
                          <input
                            type="checkbox"
                            checked={aiAcceptSpecs[r.key] ?? false}
                            onChange={(e) =>
                              setAiAcceptSpecs((prev) => ({ ...prev, [r.key]: e.target.checked }))
                            }
                            className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                          />
                          <span className="min-w-0 flex-1">
                            <span className="font-medium text-gray-700">{r.label}</span>
                            {!r.known && (
                              <span className="ml-2 rounded bg-blue-50 px-1.5 py-0.5 text-[11px] font-medium text-blue-700">
                                new field
                              </span>
                            )}
                            {r.verdict === 'conflict' && (
                              <span className="ml-2 rounded bg-amber-50 px-1.5 py-0.5 text-[11px] font-medium text-amber-800">
                                differs
                              </span>
                            )}
                            <span className="mt-0.5 block break-words">
                              {r.oldValue && (
                                <span className="mr-2 text-gray-400 line-through">{r.oldValue}</span>
                              )}
                              <span className="text-gray-900">{r.newValue}</span>
                            </span>
                          </span>
                        </label>
                      </li>
                    ))}
                  </ul>
                )}

                {aiError && <p className="mt-3 text-sm text-red-600">{aiError}</p>}

                <div className="mt-4 flex justify-between gap-2">
                  <button
                    onClick={() => setAiPicked(null)}
                    className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
                  >
                    ← Back to results
                  </button>
                  <button
                    onClick={handleApplyAiLookup}
                    disabled={aiApplying}
                    className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                  >
                    {aiApplying ? 'Applying…' : 'Apply to part'}
                  </button>
                </div>
              </>
            );
          })()
        )}
      </Modal>

      {/* Component cache — search / pick / confirm, the same three steps as the AI lookup and the
          same per-spec confirmation, because both answer "this source says X, the part says Y". */}
      <Modal
        open={ccModalOpen}
        onClose={() => setCcModalOpen(false)}
        title="Look up in component cache"
        wide
      >
        {!ccPicked ? (
          <>
            <div className="mb-2 flex gap-2">
              <input
                type="text"
                value={ccQuery}
                onChange={(e) => setCcQuery(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && runCcSearch(ccQuery)}
                placeholder="Part number, or a description of the part"
                className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
              <button
                onClick={() => runCcSearch(ccQuery)}
                disabled={!ccQuery.trim() || ccLoading}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                Search
              </button>
            </div>
            <p className="mb-3 text-xs text-gray-400">
              Searches a local snapshot of a distributor catalogue. Free, offline and instant — no
              web search and nothing to pay.
            </p>

            {ccError && <p className="mb-3 text-sm text-red-600">{ccError}</p>}

            <div className="min-h-[8rem]">
              {ccLoading ? (
                <p className="text-sm text-gray-400">Searching the cache…</p>
              ) : ccResults.length === 0 ? (
                ccSearched ? (
                  <div className="text-sm text-gray-500">
                    <p className="font-medium text-gray-700">Not in the cache.</p>
                    <p className="mt-1">
                      The snapshot covers a distributor's catalogue, so house-numbered, vintage and
                      one-off parts are simply not in it. Try "Look up specs" for a web search, or
                      read a stored datasheet.
                    </p>
                  </div>
                ) : (
                  <p className="text-sm text-gray-400">
                    Nothing searched yet. Check the term above and press Search.
                  </p>
                )
              ) : (
                <ul className="divide-y divide-gray-100">
                  {ccResults.map((m) => (
                    <li key={m.lcsc} className="flex items-start justify-between gap-3 py-3">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-baseline gap-2">
                          <span className="font-mono text-sm font-medium text-gray-900">{m.mpn}</span>
                          {m.score < 0.999 && (
                            <span className="rounded bg-amber-500/15 px-1.5 py-0.5 text-[11px] font-medium text-amber-700">
                              {Math.round(m.score * 100)}% match
                            </span>
                          )}
                        </div>
                        {m.manufacturer && <div className="text-xs text-gray-500">{m.manufacturer}</div>}
                        {m.description && (
                          <div className="mt-0.5 truncate text-xs text-gray-600">{m.description}</div>
                        )}
                        <div className="mt-0.5 text-xs text-gray-400">
                          {m.specCount} specification{m.specCount === 1 ? '' : 's'}
                          {m.packageName ? ` · ${m.packageName}` : ''}
                          {m.subcategory ? ` · ${m.subcategory}` : ''}
                        </div>
                      </div>
                      <button
                        onClick={() => pickCcResult(m)}
                        disabled={ccLoading}
                        className="shrink-0 rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700 disabled:opacity-50"
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
          (() => {
            const rows = ccSpecRows(ccPicked);
            const newCount = rows.filter((r) => r.verdict === 'new').length;
            const conflictCount = rows.filter((r) => r.verdict === 'conflict').length;
            const fieldChanges = CACHE_FIELDS.map((f) => ({
              field: f,
              oldVal: (part?.[f.key] as string | undefined) ?? '',
              newVal: ccPicked[f.from] ?? '',
            })).filter((c) => c.newVal && c.newVal !== c.oldVal);
            const setAll = (only: SpecVerdict | null) => {
              const next: Record<string, boolean> = {};
              for (const r of rows) next[r.key] = only == null ? false : r.verdict === only;
              setCcAcceptSpecs(next);
            };
            return (
              <>
                <p className="mb-3 text-sm text-gray-600">
                  Found <span className="font-mono">{ccPicked.mpn}</span>
                  {ccPicked.manufacturer ? ` — ${ccPicked.manufacturer}` : ''}
                  {ccPicked.subcategory ? (
                    <span className="text-gray-400"> · suggested category: {ccPicked.subcategory}</span>
                  ) : null}
                </p>

                {fieldChanges.length > 0 && (
                  <div className="mb-4">
                    <h4 className="mb-2 text-sm font-semibold text-gray-900">Part details</h4>
                    <div className="space-y-2">
                      {fieldChanges.map((c) => (
                        <label key={c.field.key} className="flex items-start gap-2 text-sm">
                          <input
                            type="checkbox"
                            checked={ccAcceptFields[c.field.key] ?? false}
                            onChange={(e) =>
                              setCcAcceptFields((prev) => ({
                                ...prev,
                                [c.field.key]: e.target.checked,
                              }))
                            }
                            className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                          />
                          <span className="min-w-0">
                            <span className="font-medium text-gray-700">{c.field.label}</span>
                            {c.oldVal && (
                              <span className="ml-2 text-gray-400 line-through">{c.oldVal}</span>
                            )}
                            <span className="ml-2 break-words text-gray-900">{c.newVal}</span>
                          </span>
                        </label>
                      ))}
                    </div>
                  </div>
                )}

                <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
                  <h4 className="text-sm font-semibold text-gray-900">Specifications</h4>
                  {rows.length > 0 && (
                    <span className="text-xs text-gray-500">
                      {newCount} new, {conflictCount} differ from stored values. Unticked rows are
                      left as they are.
                    </span>
                  )}
                </div>
                {rows.length > 0 && (
                  <div className="mb-2 flex gap-3 text-xs">
                    <button
                      type="button"
                      onClick={() => setAll('new')}
                      className="text-blue-600 hover:underline"
                    >
                      Select all new
                    </button>
                    <button
                      type="button"
                      onClick={() => setAll(null)}
                      className="text-blue-600 hover:underline"
                    >
                      Select none
                    </button>
                  </div>
                )}

                {rows.length === 0 ? (
                  <p className="text-sm text-gray-500">
                    Nothing to add — every specification the cache holds already matches what the
                    part has.
                  </p>
                ) : (
                  <ul className="max-h-72 divide-y divide-gray-100 overflow-y-auto">
                    {rows.map((r) => (
                      <li key={r.key} className="py-2">
                        <label className="flex items-start gap-2 text-sm">
                          <input
                            type="checkbox"
                            checked={ccAcceptSpecs[r.key] ?? false}
                            onChange={(e) =>
                              setCcAcceptSpecs((prev) => ({ ...prev, [r.key]: e.target.checked }))
                            }
                            className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                          />
                          <span className="min-w-0 flex-1">
                            <span className="font-medium text-gray-700">{r.label}</span>
                            {!r.known && (
                              <span className="ml-2 rounded bg-blue-50 px-1.5 py-0.5 text-[11px] font-medium text-blue-700">
                                new field
                              </span>
                            )}
                            {r.verdict === 'conflict' && (
                              <span className="ml-2 rounded bg-amber-50 px-1.5 py-0.5 text-[11px] font-medium text-amber-800">
                                differs
                              </span>
                            )}
                            <span className="mt-0.5 block break-words">
                              {r.oldValue && (
                                <span className="mr-2 text-gray-400 line-through">{r.oldValue}</span>
                              )}
                              <span className="text-gray-900">{r.newValue}</span>
                            </span>
                          </span>
                        </label>
                      </li>
                    ))}
                  </ul>
                )}

                {ccError && <p className="mt-3 text-sm text-red-600">{ccError}</p>}

                <div className="mt-4 flex justify-between gap-2">
                  <button
                    onClick={() => setCcPicked(null)}
                    className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
                  >
                    ← Back to results
                  </button>
                  <button
                    onClick={handleApplyComponentCache}
                    disabled={ccApplying}
                    className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                  >
                    {ccApplying ? 'Applying…' : 'Apply to part'}
                  </button>
                </div>
              </>
            );
          })()
        )}
      </Modal>

      {/* Datasheet extraction — read the stored PDF, then confirm field by field. No search step:
          the document is already chosen, so this opens straight into the run. */}
      <Modal
        open={dsModalOpen}
        onClose={() => setDsModalOpen(false)}
        title="Get specs from document"
        wide
      >
        <p className="mb-3 text-sm text-gray-600">
          Reading{' '}
          <span className="font-mono">
            {dsAttachment?.filename ?? `datasheet-${dsAttachment?.id ?? ''}`}
          </span>
          . Nothing is changed until you apply it below.
        </p>

        {dsLoading && (
          <p className="text-sm text-gray-400">
            Reading the document… this usually takes 10–30 seconds.
          </p>
        )}

        {dsError && !dsLoading && (
          <div className="text-sm">
            <p className="text-red-600">{dsError}</p>
            {dsAttachment && (
              <button
                onClick={() => runDatasheetExtract(dsAttachment)}
                className="mt-3 rounded-lg border border-gray-300 px-3 py-1.5 text-xs hover:bg-gray-50"
              >
                Try again
              </button>
            )}
          </div>
        )}

        {dsResult && !dsLoading && (
          (() => {
            const rows = dsSpecRows(dsResult);
            const newCount = rows.filter((r) => r.verdict === 'new').length;
            const conflictCount = rows.filter((r) => r.verdict === 'conflict').length;
            const setAll = (only: SpecVerdict | null) => {
              const next: Record<string, boolean> = {};
              for (const r of rows) next[r.key] = only == null ? false : r.verdict === only;
              setDsAcceptSpecs(next);
            };
            return (
              <>
                <p className="mb-4 text-xs text-gray-500">
                  {dsResult.pages} page{dsResult.pages === 1 ? '' : 's'}, {dsResult.excerptChars}{' '}
                  characters read
                  {dsResult.headings.length > 0
                    ? ` from ${dsResult.headings.length} parametric section${
                        dsResult.headings.length === 1 ? '' : 's'
                      }`
                    : ''}
                  .
                </p>

                {/* A thin result from an IMAGE_TABLES document is not the same fact as a thin
                    result from a fully readable one, and saying so is the difference between "this
                    part has little data" and "this document could not be read". */}
                {dsResult.route === 'IMAGE_TABLES' && (
                  <p className="mb-4 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800">
                    This datasheet's specification tables are images, not text — only the
                    description and whatever else sits in the text layer could be read. Expect few
                    or no specifications; that is the document, not the part.
                  </p>
                )}

                {dsResult.details && (
                  <div className="mb-4">
                    <h4 className="mb-2 text-sm font-semibold text-gray-900">Description</h4>
                    <label className="flex items-start gap-2 text-sm">
                      <input
                        type="checkbox"
                        checked={dsAcceptDetails}
                        onChange={(e) => setDsAcceptDetails(e.target.checked)}
                        className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                      <span className="min-w-0 flex-1">
                        <span className="font-medium text-gray-700">Details</span>
                        {part?.details && (
                          <span className="ml-2 rounded bg-amber-50 px-1.5 py-0.5 text-[11px] font-medium text-amber-800">
                            replaces the current text
                          </span>
                        )}
                        <span className="mt-1 block whitespace-pre-wrap text-gray-900">
                          {dsResult.details}
                        </span>
                      </span>
                    </label>
                  </div>
                )}

                <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
                  <h4 className="text-sm font-semibold text-gray-900">Specifications</h4>
                  {rows.length > 0 && (
                    <span className="text-xs text-gray-500">
                      {newCount} new, {conflictCount} differ from stored values. Unticked rows are
                      left as they are.
                    </span>
                  )}
                </div>
                {rows.length > 0 && (
                  <div className="mb-2 flex gap-3 text-xs">
                    <button
                      type="button"
                      onClick={() => setAll('new')}
                      className="text-blue-600 hover:underline"
                    >
                      Select all new
                    </button>
                    <button
                      type="button"
                      onClick={() => setAll(null)}
                      className="text-blue-600 hover:underline"
                    >
                      Select none
                    </button>
                  </div>
                )}

                {rows.length === 0 ? (
                  <p className="text-sm text-gray-500">
                    Nothing to add — the document yielded no specifications beyond what the part
                    already holds.
                  </p>
                ) : (
                  <ul className="max-h-72 divide-y divide-gray-100 overflow-y-auto">
                    {rows.map((r) => (
                      <li key={r.key} className="py-2">
                        <label className="flex items-start gap-2 text-sm">
                          <input
                            type="checkbox"
                            checked={dsAcceptSpecs[r.key] ?? false}
                            onChange={(e) =>
                              setDsAcceptSpecs((prev) => ({ ...prev, [r.key]: e.target.checked }))
                            }
                            className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                          />
                          <span className="min-w-0 flex-1">
                            <span className="font-medium text-gray-700">{r.label}</span>
                            {!r.known && (
                              <span className="ml-2 rounded bg-blue-50 px-1.5 py-0.5 text-[11px] font-medium text-blue-700">
                                new field
                              </span>
                            )}
                            {r.verdict === 'conflict' && (
                              <span className="ml-2 rounded bg-amber-50 px-1.5 py-0.5 text-[11px] font-medium text-amber-800">
                                differs
                              </span>
                            )}
                            {r.page != null && (
                              <span className="ml-2 text-[11px] text-gray-400">page {r.page}</span>
                            )}
                            <span className="mt-0.5 block break-words">
                              {r.oldValue && (
                                <span className="mr-2 text-gray-400 line-through">{r.oldValue}</span>
                              )}
                              <span className="text-gray-900">{r.newValue}</span>
                            </span>
                          </span>
                        </label>
                      </li>
                    ))}
                  </ul>
                )}

                <p className="mt-3 text-xs text-gray-400">
                  Values are read from this document only. Check anything that matters against the
                  page shown — a datasheet that covers a family prints values for parts other than
                  this one.
                </p>

                {dsError && <p className="mt-3 text-sm text-red-600">{dsError}</p>}

                <div className="mt-4 flex justify-between gap-2">
                  <button
                    onClick={() => setDsModalOpen(false)}
                    className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleApplyDatasheetExtract}
                    disabled={dsApplying}
                    className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                  >
                    {dsApplying ? 'Applying…' : 'Apply to part'}
                  </button>
                </div>
              </>
            );
          })()
        )}
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

      {/* Edit part modal */}
      <PartEditModal
        open={editModalOpen}
        part={part}
        onClose={() => setEditModalOpen(false)}
        onSaved={(updated) => {
          setPart(updated);
          setEditModalOpen(false);
        }}
      />
      </div>
    </div>
  );
}
