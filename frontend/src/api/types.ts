// A set of related spec fields ("Power", "MCU Specs"). Groups replace the old fixed MAJOR_TYPES
// buckets: every spec belongs to exactly one, and they drive the sections on the Part detail page.
export interface SpecGroup {
  id: number;
  name: string;
  description?: string;
  displayOrder: number;
  specCount: number;
}

export interface SpecGroupRequest {
  name: string;
  description?: string;
  displayOrder: number;
}

export interface SpecDefinition {
  id: number;
  jsonName: string; // machine key used inside part.specs
  name: string;
  dataType: string; // TEXT | NUMBER | BOOLEAN | SELECT
  unit?: string;
  metricPrefix?: boolean; // NUMBER + single unit: scale value with metric prefixes
  // What the field measures (a UnitFamily code, see utils/units.ts UNIT_FAMILIES), or absent.
  // This is what lets a stored base-unit number be rendered back into the form people write
  // (0.00000015 -> "150 ns") without the definition declaring a unit of its own.
  unitFamily?: string;
  options?: string[];
  displayOrder: number;
  groupId: number;
  groupName: string;
  aliases?: string[]; // other JSON names this spec is known by, at other sources
}

export interface SpecDefinitionRequest {
  jsonName: string;
  name: string;
  dataType: string;
  unit?: string;
  metricPrefix?: boolean;
  unitFamily?: string;
  options?: string[];
  displayOrder: number;
  groupId?: number;
  aliases?: string[];
}

export interface MergeSpecsRequest {
  targetId: number;
  sourceIds: number[];
}

export interface MoveSpecsRequest {
  specIds: number[];
  groupId: number;
}

export interface ConvertToNumberRequest {
  unit: string;
  metricPrefix: boolean;
  overrides: Record<string, string>;
  commit: boolean;
}

export interface ConvertToNumberResult {
  total: number;
  converted: number;
  suggestedUnit?: string;
  failures: { value: string; count: number }[];
  definition?: SpecDefinition;
}

export interface Category {
  id: number;
  name: string;
  description?: string;
  parentId?: number;
  parentName?: string;
  breadcrumb: string;
  specIds?: number[];
}

export interface CategoryTree {
  id: number;
  name: string;
  description?: string;
  parentId?: number;
  partCount: number;
  children: CategoryTree[];
}

export interface CategoryRequest {
  name: string;
  description?: string;
  parentId?: number | null;
  specIds?: number[];
}

export interface Part {
  id: number;
  partNumber: string;
  description?: string;
  details?: string;
  manufacturer?: string;
  mpn?: string;
  footprint?: string;
  octopartId?: string;
  personalNumber: boolean;
  datasheetUrl?: string;
  specs?: Record<string, string>;
  categoryId?: number;
  categoryName?: string;
  categoryBreadcrumb?: string;
  createdById?: number;
  createdByName?: string;
  createdAt: string;
  updatedAt: string;
  totalQuantity?: number;
  /** First photo attachment id — set on list/search results only; absent when the part has no photo. */
  thumbnailId?: number;
  tags?: string[];
}

export interface PartRequest {
  partNumber: string;
  description?: string;
  details?: string;
  manufacturer?: string;
  personalNumber?: boolean;
  datasheetUrl?: string;
  specs?: Record<string, string>;
  /**
   * How `specs` combines with what the part already holds. Defaults to `MERGE` server-side.
   *
   * **Send `REPLACE` only if you rendered every key the part carries.** A form that builds its
   * fields from the spec definitions does not qualify — a part can hold keys no definition covers
   * (the AI intake paths keep unrecognised keys so a later "rescan from parts" can promote them),
   * and replacing wholesale from such a form deletes them. Under `MERGE` an omitted key is left
   * alone and a key sent blank is removed.
   */
  specsMode?: 'MERGE' | 'REPLACE';
  categoryId?: number | null;
  tags?: string[];
}

/**
 * Creating a part, optionally with its opening stock — the New Part dialog asks for an amount, a
 * location and a per-item price alongside the part's own fields.
 *
 * Deliberately separate from `PartRequest`, which is what an edit sends: the update endpoint takes
 * the base shape and cannot carry stock at all, rather than accepting it and ignoring it.
 *
 * All three are optional and skipped entirely when `quantity` is null/undefined. When a quantity is
 * given, `locationId` is required (the server rejects it otherwise) while the price stays optional.
 */
export interface PartCreateRequest extends PartRequest {
  locationId?: number | null;
  quantity?: number | null;
  unitPrice?: number | null;
}

/**
 * The Parts screen's advanced search filters (the collapsible panel under the search bar). Every
 * field is optional; omitted ones are not filtered on. A part must carry *all* of `tags`.
 */
export interface PartFilters {
  personalNumber?: boolean;
  manufacturer?: string;
  locationId?: number;
  /** Keep only parts carrying fewer than SPARSE_SPEC_THRESHOLD spec keys. */
  sparseSpecs?: boolean;
  tags?: string[];
  /**
   * Parametric spec criteria, each `jsonName:op:value`, ANDed together — "Vds >= 60 V" is
   * `draintosourcevoltage_vdss_:gte:60`. The value is written the way people write it ("4k7",
   * "100nF", "3.3") and parsed server-side against the spec's unit family.
   */
  specs?: string[];
}

/** The comparisons a spec criterion can make. */
export type SpecOp = 'eq' | 'gte' | 'gt' | 'lte' | 'lt' | 'contains' | 'any';

export const SPEC_OP_LABELS: Record<SpecOp, string> = {
  eq: '=',
  gte: '\u2265',
  gt: '>',
  lte: '\u2264',
  lt: '<',
  contains: 'contains',
  any: 'has any value',
};

/**
 * A part with fewer than this many spec keys counts as "missing specs" — what the dashboard tile
 * reports and what the Parts screen's sparse filter keeps. Mirrors
 * `PartRepository.SPARSE_SPEC_THRESHOLD`; the two must agree or the tile's count and the filtered
 * list disagree.
 */
export const SPARSE_SPEC_THRESHOLD = 5;

/** A saved tag name, as returned by the tag autocomplete endpoint. */
export interface Tag {
  id: number;
  name: string;
}

export interface Location {
  id: number;
  name: string;
  description?: string;
  parentId?: number;
  parentName?: string;
  breadcrumb: string; // full path, e.g. "Building A > Room B > Cupboard C"
}

export interface LocationTree {
  id: number;
  name: string;
  description?: string;
  parentId?: number;
  children: LocationTree[];
}

/** Stock roll-up for one location: held directly there, and across its whole subtree. */
export interface LocationStats {
  locationId: number;
  directParts: number;
  directQuantity: number;
  directStockValue: number;
  totalParts: number;
  totalQuantity: number;
  totalStockValue: number;
}

export interface LocationRequest {
  name: string;
  description?: string;
  parentId?: number | null;
}

// Organisations — the tenant boundary. All parts, stock, locations, categories, spec fields and
// tags belong to exactly one organisation; users are members of one or more and switch between them.
export interface Organisation {
  id: number;
  name: string;
  description?: string;
  /** The blueprint copied into every new organisation. Selectable by Global Administrators only. */
  template: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface OrganisationRequest {
  name: string;
  description?: string;
}

/**
 * One user's membership of one organisation — what the All Users screen edits. `implied` marks
 * permissions that come from GLOBAL_ADMIN rather than stored grants, so they render read-only.
 */
export interface UserMembership {
  organisationId: number;
  organisationName: string;
  template: boolean;
  permissions: string[];
  implied: boolean;
}

/** A user account seen installation-wide: the account plus every organisation it belongs to. */
export interface AdminUser {
  id: number;
  email: string;
  fullName?: string;
  phone?: string;
  globalPermissions: string[];
  memberships: UserMembership[];
}

// Users & auth
export interface User {
  id: number;
  email: string;
  fullName?: string;
  phone?: string;
  /** Permissions in the organisation this response is scoped to (the current one). */
  permissions: string[];
  /** Permissions in force everywhere, independent of organisation. */
  globalPermissions?: string[];
  lastLocationId?: number;
  lastLocationName?: string;
  /** Organisations this user belongs to. */
  organisationIds?: number[];
  /** The organisation in force for this session (only populated by /auth/me). */
  currentOrganisationId?: number;
  currentOrganisationName?: string;
  selectableOrganisations?: Organisation[];
  hasOctopartCredentials?: boolean;
  /** 8-digit date of the last changelog entry the user acknowledged, e.g. "20260623". */
  lastReadChanges?: string;
  printMethod?: PrintMethod;
  preferredDaemonId?: number;
  /** Whether printing a label also prints a second label with the part's barcode. */
  printBarcodeLabel?: boolean;
}

export interface UnreadChanges {
  html: string;
  latestDate?: string;
  count: number;
}

export interface UserRequest {
  email: string;
  password?: string; // blank when editing keeps the existing password
  fullName?: string;
  phone?: string;
  /** Permissions to grant within the organisation currently in force. */
  permissions: string[];
  /** Global permissions. Only a Global Administrator may set these. */
  globalPermissions?: string[];
  /** Organisations a newly created account belongs to (All Users screen; at least one). */
  organisationIds?: number[];
}

/** An invitation as the inviting organisation's admins see it. Never carries the token. */
export interface Invitation {
  id: number;
  email: string;
  /** Full name of the existing account behind the address, if there is one. */
  fullName?: string;
  permissions: string[];
  status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'REVOKED';
  expired: boolean;
  invitedByName?: string;
  createdAt: string;
  expiresAt: string;
  respondedAt?: string;
  /** False when the mail could not be sent — the admin then passes `link` on themselves. */
  mailSent: boolean;
  /** Why the mail did not go out, ready to show as-is; absent when it did. */
  mailError?: string;
  /** Only returned in the response to creating the invitation. */
  link?: string;
}

/** What the invitee sees on the (unauthenticated) accept/decline page. */
export interface PublicInvitation {
  email: string;
  organisationName: string;
  invitedByName?: string;
  permissions: string[];
  status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'REVOKED';
  expired: boolean;
  open: boolean;
  /** No account for this address yet — accepting creates one, so we ask for name/phone/password. */
  newAccount: boolean;
}

/** Who an email address belongs to, shown next to the invite dialog's email field. */
export interface EmailLookup {
  email: string;
  exists: boolean;
  fullName?: string;
  member: boolean;
  invited: boolean;
}

export interface InvitationRequest {
  email: string;
  permissions: string[];
}

export interface AcceptInvitationRequest {
  fullName?: string;
  phone?: string;
  password?: string;
}

/** The currently authenticated user (same shape as User). */
export type AuthUser = User;

/**
 * Permissions held per (user, organisation) — what the Users screen edits. They apply only in the
 * organisation you are currently in.
 */
export const ORGANISATION_PERMISSIONS: { key: string; label: string }[] = [
  { key: 'ORG_ADMIN', label: 'Organisation Admin' },
  { key: 'PARTS_EDIT', label: 'Add/edit parts' },
];

/** Permissions that are a property of the account itself, in force in every organisation. */
export const GLOBAL_PERMISSIONS: { key: string; label: string }[] = [
  { key: 'GLOBAL_ADMIN', label: 'Global Administrator' },
];

/** Every known permission key → label, for rendering a user's permissions anywhere. */
export const PERMISSIONS = [...ORGANISATION_PERMISSIONS, ...GLOBAL_PERMISSIONS];

export interface StockEntry {
  id: number;
  partId: number;
  partName: string;
  partNumber: string;
  locationId: number;
  locationName: string;
  locationBreadcrumb: string;
  quantity: number;
  unitPrice?: number | null;
}

/** Add or take a quantity of stock at a single location. */
export interface StockAdjustRequest {
  partId: number;
  locationId: number;
  quantity: number;
  unitPrice?: number | null;
  comments?: string | null;
}

export interface StockThreshold {
  id: number;
  partId: number;
  partName: string;
  partNumber: string;
  locationId: number;
  locationName: string;
  minimumQuantity: number;
  totalQuantity: number;
  lowStock: boolean;
}

export interface StockThresholdRequest {
  partId: number;
  locationId: number;
  minimumQuantity: number;
}

/** Move a quantity of stock from one location to another (destination may belong to any user). */
export interface StockMoveRequest {
  partId: number;
  fromLocationId: number;
  toLocationId: number;
  quantity: number;
  comments?: string | null;
}

export interface StockMovement {
  id: number;
  partId: number;
  locationId: number;
  locationName: string;
  locationBreadcrumb: string;
  targetLocationId?: number | null;
  targetLocationName?: string | null;
  targetLocationBreadcrumb?: string | null;
  quantity: number;
  unitPrice?: number | null;
  comments?: string | null;
  movedAt: string;
  createdBy?: string | null;
  type?: string | null;
  projectId?: number | null;
  projectName?: string | null;
}

// Projects
export type ProjectStatus = 'PLANNING' | 'BUILDING' | 'COMPLETED' | 'CANCELLED';

export interface Project {
  id: number;
  name: string;
  description?: string;
  status: ProjectStatus;
  instanceCount: number;
  ownerId: number;
  ownerName?: string;
  bomPartCount: number;
  totalStockValue?: number | null;
  createdAt: string;
  updatedAt: string;
  /** Populated only by the detail endpoint. */
  bom?: ProjectBomEntry[];
  /** Populated only by the detail endpoint. */
  stock?: ProjectStockEntry[];
}

export interface ProjectBomEntry {
  id: number;
  partId: number;
  partName: string;
  partNumber: string;
  qtyPerInstance: number;
  totalNeeded: number;
  pulledTotal: number;
  notes?: string;
}

export interface ProjectStockEntry {
  id: number;
  partId: number;
  partName: string;
  partNumber: string;
  locationId: number;
  locationName: string;
  locationBreadcrumb: string;
  quantity: number;
  unitPrice?: number | null;
  movementId?: number | null;
  addedAt: string;
  addedByName?: string;
}

export interface ProjectRequest {
  name: string;
  description?: string;
  instanceCount: number;
}

export interface ProjectBomRequest {
  partId: number;
  qtyPerInstance: number;
  notes?: string;
}

export interface PullStockRequest {
  partId: number;
  locationId: number;
  quantity: number;
  unitPrice?: number | null;
}

export interface CancelRequest {
  returnStockIds: number[];
}

// Imported BOM
//
// Distinct from ProjectBomEntry above, which is a `project_part` row — the project's own BOM, what
// Pull Stock reads. An ImportedBom is the uploaded CSV and the matching work on it; applying it
// writes ProjectBomEntry rows.

export type BomLineStatus = 'UNMATCHED' | 'MATCHED' | 'PROVIDED' | 'EXCLUDED';
export type BomMatchSource = 'AUTO' | 'MANUAL';

/** The column roles the importer maps headers onto. */
export const BOM_COLUMN_ROLES = [
  'REFERENCES',
  'VALUE',
  'FOOTPRINT',
  'QUANTITY',
  'MPN',
  'MANUFACTURER',
  'DESCRIPTION',
  'DATASHEET',
  'DNP',
] as const;

export type BomColumnRole = (typeof BOM_COLUMN_ROLES)[number];

/** Role → the header in the file it reads from. */
export type BomColumnMapping = Partial<Record<BomColumnRole, string>>;

export interface ImportedBomLine {
  id: number;
  lineNo: number;
  designators?: string | null;
  value?: string | null;
  footprint?: string | null;
  mpn?: string | null;
  manufacturer?: string | null;
  description?: string | null;
  datasheetUrl?: string | null;
  /** Per build instance. */
  quantity: number;
  dnp: boolean;
  /** Columns the mapping did not claim, kept verbatim. */
  extra?: Record<string, string> | null;
  status: BomLineStatus;
  matchSource?: BomMatchSource | null;
  /** Value or footprint moved under an existing match — worth re-checking. */
  changed: boolean;
  notes?: string | null;
  partId?: number | null;
  partNumber?: string | null;
  partDescription?: string | null;
  onHand?: number | null;
  totalNeeded: number;
}

export interface ImportedBom {
  id: number;
  projectId: number;
  projectName: string;
  instanceCount: number;
  /** False outside PLANNING — the BOM can only be applied then. */
  canApply: boolean;
  filename?: string | null;
  contentType?: string | null;
  importedAt: string;
  importedByName?: string | null;
  columnMapping?: BomColumnMapping | null;
  totalLines: number;
  matchedCount: number;
  unmatchedCount: number;
  providedCount: number;
  excludedCount: number;
  changedCount: number;
  lines: ImportedBomLine[];
}

export interface BomImportLinePreview {
  action: 'ADDED' | 'UPDATED' | 'UNCHANGED' | 'REMOVED';
  designators?: string | null;
  value?: string | null;
  footprint?: string | null;
  mpn?: string | null;
  manufacturer?: string | null;
  quantity: number;
  dnp: boolean;
  matchKept: boolean;
  matchedPartNumber?: string | null;
  changed: boolean;
}

export interface BomImportPreview {
  /** False for a dry run: nothing was written. */
  committed: boolean;
  mapping: BomColumnMapping;
  headers: string[];
  delimiter: string;
  warnings: string[];
  totalLines: number;
  added: number;
  updated: number;
  unchanged: number;
  removed: number;
  changed: number;
  autoMatched: number;
  lines: BomImportLinePreview[];
}

export interface BomCandidate {
  part: Part;
  /** pg_trgm similarity, 0–1. Advisory — nothing is auto-accepted on it. */
  score: number;
  exact: boolean;
  matchedOn?: string | null;
}

export interface BomLineMatchRequest {
  partId?: number | null;
  status?: BomLineStatus;
  notes?: string | null;
}

export interface BomApplyResult {
  created: number;
  updated: number;
  unchanged: number;
  skippedUnmatched: number;
  skippedProvided: number;
  skippedExcluded: number;
  unaccountedProjectParts: number;
}

// Currency was removed from movements — the app uses a single app-wide currency (AppSettings).

export interface AppSettings {
  currencyCode: string;
  currencySymbol: string;
}

export interface StockEntryRequest {
  partId: number;
  locationId: number;
  quantity: number;
  unitPrice?: number | null;
  comments?: string | null;
}

export interface PartSearchResult {
  mpn: string;
  manufacturer?: string;
  shortDescription?: string;
  datasheetUrl?: string;
  category?: string;
  /** A few sentences on what the part is — only the datasheet reader supplies this. */
  details?: string;
  specs: string[];
}

/**
 * Applies a chosen AI-lookup result to an existing part ("Look up specs" on Part Detail).
 *
 * Every field is what the user ticked in the confirmation step, so an omitted column field leaves
 * the part's value alone rather than clearing it, and `specs` carries only the accepted entries and
 * is merged onto the part rather than replacing its map.
 */
export interface AiApplyRequest {
  description?: string;
  /**
   * The long free-text description. Only the datasheet reader fills this — the web lookup returns a
   * one-line `shortDescription`, which belongs in `description`, while a datasheet carries several
   * sentences of what the part actually does.
   */
  details?: string;
  manufacturer?: string;
  mpn?: string;
  datasheetUrl?: string;
  /**
   * The package/case. Only the component cache fills it — the web lookup returns no footprint and
   * a datasheet is not parsed for one.
   */
  footprint?: string;
  specs?: Record<string, string>;
}

/** One specification read out of a datasheet, with the page of the PDF it came from. */
export interface ExtractedSpec {
  key: string;
  value: string;
  /** Null where the value came from prose rather than a table the model could place. */
  page?: number | null;
}

/**
 * What reading a stored datasheet produced — a proposal, not a change. Applied (after per-field
 * confirmation) through `applyAiLookup`, the same path the web lookup uses.
 *
 * `route` and `headings` explain a thin result rather than leaving it looking like the whole
 * document had been read: `IMAGE_TABLES` means the parametric tables are pasted-in scans, so only
 * the description and whatever sits in the text layer could be reached.
 */
export interface DatasheetExtraction {
  attachmentId: number;
  filename?: string | null;
  route: 'TEXT' | 'IMAGE_TABLES';
  pages: number;
  headings: string[];
  excerptChars: number;
  details?: string | null;
  specs: ExtractedSpec[];
}

// Component cache — the local jlcparts snapshot (cc_* tables), consulted before the web/AI lookups.

/**
 * Whether the snapshot is installed, and how old it is.
 *
 * The cache is an optional local dataset, not part of the schema, so every screen that uses it must
 * cope with it being absent. `snapshotDate` is the honest label for `stock` and `priceQty1`: they
 * were the vendor's figures at that moment and nothing refreshes them.
 */
export interface ComponentCacheStatus {
  available: boolean;
  componentCount: number;
  snapshotDate?: string | null;
  source?: string | null;
}

/** One cache hit — enough to recognise the part, not the whole record. */
export interface ComponentCacheMatch {
  /** The cache's key, and what `loadComponentCachePart` takes. */
  lcsc: string;
  mpn?: string;
  manufacturer?: string;
  description?: string;
  packageName?: string;
  category?: string;
  subcategory?: string;
  basicExtended?: string;
  status?: string;
  stock?: number | null;
  priceQty1?: number | null;
  datasheetUrl?: string;
  imageUrl?: string;
  productUrl?: string;
  /** How many attributes the cache holds for it — fetched with the detail, not here. */
  specCount: number;
  /** How well it matched, 0–1, so a weak hit reads as a weak hit. */
  score: number;
}

/** One cached attribute, already translated into this app's spec key and value. */
export interface ComponentCacheSpec {
  /** Where it lands in `part.specs`: a spec definition's jsonName, or a new key derived from the source name. */
  key: string;
  /** What the cache calls it, e.g. "Gain Bandwidth Product". */
  sourceName: string;
  value: string;
  /** Whether `key` matched an existing spec definition (directly or through an alias). */
  known: boolean;
}

/**
 * The whole cached record for a selected part, mapped onto this app's fields.
 *
 * It writes nothing. Quick Add pre-fills its confirm step from it and the ordinary create stores the
 * result; Part Detail ticks values one at a time and applies them through `applyAiLookup`.
 *
 * `category` is the cache's category *name* and is context only — resolving it to one of this
 * organisation's categories is a separate, fuzzy problem the AI lookup does not attempt either.
 */
export interface ComponentCacheDetail {
  lcsc: string;
  mpn?: string;
  manufacturer?: string;
  description?: string;
  footprint?: string;
  category?: string;
  subcategory?: string;
  basicExtended?: string;
  status?: string;
  stock?: number | null;
  joints?: number | null;
  priceQty1?: number | null;
  priceMin?: number | null;
  datasheetUrl?: string;
  imageUrl?: string;
  productUrl?: string;
  /** Ready to merge into `part.specs` — keys are canonical jsonNames. */
  specs: Record<string, string>;
  attributes: ComponentCacheSpec[];
  /** Attributes not taken: absent values, and the four the row already carries as columns. */
  skipped: string[];
}

export interface QuickAddRequest {
  partNumber: string;
  description?: string;
  details?: string;
  manufacturer?: string;
  personalNumber?: boolean;
  /** The package/case. Filled by the component-cache path; the AI lookup returns none. */
  footprint?: string;
  datasheetUrl?: string;
  specs?: Record<string, string>;
  categoryId?: number | null;
  tags?: string[];
  locationId: number;
  quantity: number;
  unitPrice?: number | null;
}

export interface QuickAddResponse {
  part: Part;
  stockEntry: StockEntry;
}

// OctoPart (Nexar) enrichment
export interface OctopartResult {
  octopartId: string;
  mpn?: string;
  manufacturer?: string;
  description?: string;
  datasheetUrl?: string;
  footprint?: string;
  specs?: Record<string, string>;
}

export interface OctopartUsage {
  limit: number;
  used: number;
  remaining: number;
  hasCredentials: boolean;
}

export interface OctopartApplyRequest {
  octopartId: string;
  description?: string;
  manufacturer?: string;
  mpn?: string;
  footprint?: string;
  datasheetUrl?: string;
  specs?: Record<string, string>;
}

export interface OctopartCredentialsStatus {
  hasClientId: boolean;
  hasClientSecret: boolean;
  clientId?: string;
}

export interface OctopartCredentialsRequest {
  clientId: string;
  clientSecret?: string; // blank keeps the existing secret
}

export interface ImageSuggestion {
  url: string;          // original image URL — used for saving to the part
  thumbnailUrl?: string; // smaller preview URL — used for display only
  description?: string;
}

export interface DatasheetSuggestion {
  url: string;      // candidate datasheet URL (usually a PDF)
  title?: string;   // result title/label, for display
  source?: string;  // hostname the result came from, for display
}

/** BLOCKED means the search engine refused to answer — it says nothing about the part. */
export type WebSearchStatus = 'OK' | 'NO_RESULTS' | 'BLOCKED' | 'FAILED' | 'SKIPPED';

export interface DatasheetSearchResponse {
  results: DatasheetSuggestion[];
  source: 'WEB' | 'AI' | 'NONE';
  webSearchStatus: WebSearchStatus;
  detail?: string; // why, when there is something worth showing ("bot challenge served as HTTP 202")
}

export type AttachmentType = 'PHOTO' | 'DATASHEET' | 'ATTACHMENT';

export interface PartAttachment {
  id: number;
  /** Absent on a kit template's images — the same content, with no part behind it yet. */
  partId?: number;
  type: AttachmentType;
  displayOrder: number;
  contentType?: string;
  filename?: string;
  /** Part number of the first part this attachment was used for; it never changes. */
  description?: string;
  /** MD5 of the stored bytes — identical content is stored once and linked from every part using it. */
  md5Hash?: string;
  /** How many parts use this attachment; more than 1 means removing it here leaves the others alone. */
  partCount?: number;
  createdAt: string;
}

export interface CategorizationStatus {
  running: boolean;
  total: number;
  processed: number;
  assigned: number;
  skipped: number;
  startedAt?: string | null;
  finishedAt?: string | null;
  lastError?: string | null;
}

/** Per-root-location breakdown of the stock held in the current organisation. */
export interface LocationDashboard {
  locationId: number;
  locationName: string;
  /** Locations in this root's subtree, including the root itself. */
  locations: number;
  parts: number;
  totalQuantity: number;
  totalStockValue: number;
}

// Label printing: browser print dialog vs a paired local daemon
export type PrintMethod = 'BROWSER' | 'DAEMON';

/**
 * Printer family a daemon drives. BROTHER_QL is a network printer that reports its own media;
 * DYMO_CUPS is a USB printer behind the machine's local CUPS queue, which cannot sense which roll
 * is loaded and so needs the label size picking here.
 */
export type PrinterType = 'BROTHER_QL' | 'DYMO_CUPS';

/** One label stock a queue offers. Printable dimensions already exclude the printer's margins. */
export interface DaemonMediaOption {
  keyword: string;
  displayName?: string;
  widthMm: number;
  lengthMm?: number;
  printableWidthMm: number;
  printableLengthMm?: number;
}

/** A print queue the daemon found on the machine it runs on. */
export interface DaemonQueue {
  name: string;
  description?: string;
  makeAndModel?: string;
  media: DaemonMediaOption[];
}

/**
 * What the daemon discovered locally, pushed to the backend separately from its poll (the media
 * lists are far too big for a header) and read back here to populate the pickers.
 */
export interface DaemonCapabilities {
  queues: DaemonQueue[];
}

export interface PrintDaemon {
  id: number;
  name: string;
  status: 'PENDING' | 'ACTIVE';
  printerType: PrinterType;
  printerIp?: string;
  printerQueue?: string;
  /** Label size chosen here, for a printer that cannot sense its own roll. */
  mediaKeyword?: string;
  /** Model the printer reports over IPP, e.g. "DYMO LabelWriter 320". */
  printerModel?: string;
  /** Media in the printer: detected for a Brother, resolved from mediaKeyword for a Dymo. */
  mediaKind?: 'CONTINUOUS' | 'DIE_CUT';
  mediaWidthMm?: number;
  mediaLengthMm?: number;
  mediaName?: string;
  /** Human-readable media summary, e.g. "17 × 54 mm labels". */
  mediaDescription?: string;
  /**
   * The area the printer can actually mark, as the daemon reports it. Labels are rendered to
   * exactly this — see labelSizeFor.
   */
  printableWidthMm?: number;
  printableLengthMm?: number;
  /** Queues and label sizes found on the daemon's machine; absent until it has reported them. */
  capabilities?: DaemonCapabilities;
  capabilitiesReportedAt?: string;
  owned: boolean;
  /** Version the daemon reports; absent if it never reported one (pre-versioning build). */
  version?: string;
  /** Version this build of the app ships; absent when the app doesn't know one. */
  expectedVersion?: string;
  /** True only when both versions are known and differ. */
  outdated: boolean;
}

/** The whole printer configuration is sent on every save; an omitted field is cleared. */
export interface PrintDaemonUpdateRequest {
  name?: string;
  printerType?: PrinterType;
  printerIp?: string | null;
  printerQueue?: string | null;
  mediaKeyword?: string | null;
}

export interface PrintingPreference {
  printMethod: PrintMethod;
  preferredDaemonId?: number;
  printBarcodeLabel: boolean;
}

export interface PrintingPreferenceRequest {
  printMethod: PrintMethod;
  preferredDaemonId?: number | null;
  printBarcodeLabel?: boolean;
}

export interface PrintJob {
  id: number;
  status: 'QUEUED' | 'SENT' | 'DONE' | 'FAILED';
  errorMessage?: string;
}

export interface Dashboard {
  totalParts: number;
  totalLocations: number;
  totalCategories: number;
  lowStockCount: number;
  /** Parts carrying fewer than SPARSE_SPEC_THRESHOLD spec keys. */
  sparseSpecCount: number;
  totalStockValue: number;
  perLocation: LocationDashboard[];
}

/** One row of the dashboard's "Recently Added" list. */
export interface RecentPart {
  id: number;
  partNumber: string;
  description?: string | null;
  /** Location breadcrumbs holding this part, most stock first; empty when nothing is stocked. */
  locations: string[];
  /** On-hand total across every location in the current organisation. */
  totalQuantity: number;
  createdAt: string;
}

export interface RecentPartsPage {
  items: RecentPart[];
  total: number;
  /** Zero-based page index actually served — may be lower than asked for if the list shrank. */
  page: number;
  size: number;
}

// ── Part kit templates ────────────────────────────────────────────────────────
// A pack of parts differing in one value (a resistor kit): the part fields once, with the
// placeholder ${value} where the varying value belongs, plus the list of values.

export interface PartKitTemplate {
  id: number;
  name: string;
  notes?: string;
  partNumberTemplate: string;
  personalNumber: boolean;
  manufacturerTemplate?: string;
  descriptionTemplate?: string;
  detailsTemplate?: string;
  footprintTemplate?: string;
  datasheetUrlTemplate?: string;
  categoryId?: number | null;
  categoryName?: string;
  categoryBreadcrumb?: string;
  /** Keyed by spec jsonName; every value is a template string. */
  specs: Record<string, string>;
  tags: string[];
  values: string[];
  createdByName?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface PartKitTemplateRequest {
  name: string;
  notes?: string;
  partNumberTemplate: string;
  personalNumber?: boolean;
  manufacturerTemplate?: string;
  descriptionTemplate?: string;
  detailsTemplate?: string;
  footprintTemplate?: string;
  datasheetUrlTemplate?: string;
  categoryId?: number | null;
  specs?: Record<string, string>;
  tags?: string[];
  values?: string[];
}

export interface PartKitGenerateRequest {
  quantityPerValue: number;
  locationId: number;
  unitPrice?: number | null;
}

export interface PartKitGenerateResultLine {
  value: string;
  partId: number;
  partNumber: string;
  /** True when this run created the part; false when it was already in the catalogue. */
  created: boolean;
  quantityAdded: number;
}

export interface PartKitGenerateResult {
  /** The recorded run — what the history lists, and what an undo takes back. */
  generationId: number;
  partsCreated: number;
  partsFound: number;
  stockAdded: number;
  lines: PartKitGenerateResultLine[];
}

// Generation history. Every run of "Generate parts" is recorded so the most recent one can be
// undone — see the backend's PartKitGenerationService for exactly when it can.

export interface PartKitGenerationLine {
  value: string;
  /** Null when the part has since been deleted by some other route. */
  partId?: number | null;
  partNumber?: string;
  created: boolean;
  quantityAdded: number;
}

export interface PartKitGeneration {
  id: number;
  generatedAt: string;
  generatedByName?: string;
  quantityPerValue: number;
  unitPrice?: number | null;
  locationId?: number | null;
  locationBreadcrumb?: string;
  partsCreated: number;
  partsFound: number;
  stockAdded: number;
  undoable: boolean;
  /** Why not, in the user's terms — null when it can be undone. */
  undoBlockedReason?: string | null;
  lines: PartKitGenerationLine[];
}

export interface PartKitUndoResult {
  generationId: number;
  partsDeleted: number;
  partsKept: number;
  stockRemoved: number;
  deletedPartNumbers: string[];
}

/**
 * A key for the read-only MCP endpoint, as listed to its owner. The token itself is returned once,
 * at creation (see {@link McpApiKeyCreated}), and is unrecoverable afterwards.
 */
export interface McpApiKey {
  id: number;
  name: string;
  organisationId: number;
  organisationName: string;
  createdAt: string;
  /** Null until the key is first used. */
  lastUsedAt?: string | null;
}

export interface McpApiKeyCreated {
  key: McpApiKey;
  /** The one and only sight of the token. */
  token: string;
}

export interface McpApiKeyRequest {
  name: string;
  /** Defaults to the organisation in force. */
  organisationId?: number;
}

/**
 * A pending OAuth authorization request, as the consent screen shows it. `clientName` and
 * `redirectHost` are the client's own claims — anyone may register a client calling itself
 * anything, so the screen presents them as claims rather than as identity.
 */
export interface OAuthConsent {
  requestId: string;
  clientName?: string | null;
  redirectHost: string;
  scope: string;
  /** The organisations this user may grant; the token is pinned to the one chosen. */
  organisations: Organisation[];
  defaultOrganisationId: number;
}
