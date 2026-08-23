import client from './client';
import type {
  AcceptInvitationRequest,
  AdminUser,
  AiApplyRequest,
  AppSettings,
  AuthUser,
  CancelRequest,
  CategorizationStatus,
  Category,
  CategoryRequest,
  CategoryTree,
  ComponentCacheDetail,
  ComponentCacheMatch,
  ComponentCacheStatus,
  Dashboard,
  DatasheetExtraction,
  DatasheetSearchResponse,
  EmailLookup,
  Invitation,
  InvitationRequest,
  ImageSuggestion,
  Location,
  LocationRequest,
  LocationStats,
  LocationTree,
  OctopartApplyRequest,
  OctopartCredentialsRequest,
  OctopartCredentialsStatus,
  OctopartResult,
  Organisation,
  OrganisationRequest,
  PublicInvitation,
  OctopartUsage,
  PrintDaemon,
  PrintDaemonUpdateRequest,
  PrintingPreference,
  PrintingPreferenceRequest,
  PrintJob,
  AttachmentType,
  ConvertToNumberRequest,
  ConvertToNumberResult,
  Part,
  PartAttachment,
  PartFilters,
  PartKitGenerateRequest,
  PartKitGenerateResult,
  PartKitGeneration,
  PartKitTemplate,
  PartKitTemplateRequest,
  PartKitUndoResult,
  PartCreateRequest,
  PartRequest,
  PartSearchResult,
  Project,
  ProjectBomEntry,
  ProjectBomRequest,
  ProjectRequest,
  ProjectStockEntry,
  PullStockRequest,
  QuickAddRequest,
  QuickAddResponse,
  RecentPartsPage,
  SpecDefinition,
  SpecDefinitionRequest,
  SpecGroup,
  SpecGroupRequest,
  MergeSpecsRequest,
  MoveSpecsRequest,
  StockAdjustRequest,
  StockEntry,
  StockEntryRequest,
  StockMoveRequest,
  StockMovement,
  StockThreshold,
  StockThresholdRequest,
  Tag,
  UnreadChanges,
  User,
  UserRequest,
  BomApplyResult,
  BomCandidate,
  BomColumnMapping,
  BomImportPreview,
  BomLineMatchRequest,
  ImportedBom,
  ImportedBomLine,
} from './types';

// Categories
export const getCategories = () =>
  client.get<Category[]>('/categories').then((r) => r.data);

export const getCategoryTree = () =>
  client.get<CategoryTree[]>('/categories/tree').then((r) => r.data);

export const getCategory = (id: number) =>
  client.get<Category>(`/categories/${id}`).then((r) => r.data);

export const getCategoryChildren = (id: number) =>
  client.get<Category[]>(`/categories/${id}/children`).then((r) => r.data);

export const createCategory = (data: CategoryRequest) =>
  client.post<Category>('/categories', data).then((r) => r.data);

export const updateCategory = (id: number, data: CategoryRequest) =>
  client.put<Category>(`/categories/${id}`, data).then((r) => r.data);

export const deleteCategory = (id: number) =>
  client.delete(`/categories/${id}`);

// Parts
export const getParts = (
  search?: string,
  categoryId?: number,
  sort?: string,
  filters?: PartFilters,
) => {
  const params: Record<string, string | number | boolean | string[]> = {};
  if (search) params.search = search;
  if (categoryId) params.categoryId = categoryId;
  if (sort) params.sort = sort;
  if (filters?.personalNumber !== undefined) params.personalNumber = filters.personalNumber;
  if (filters?.manufacturer) params.manufacturer = filters.manufacturer;
  if (filters?.locationId !== undefined) params.locationId = filters.locationId;
  if (filters?.sparseSpecs) params.sparseSpecs = true;
  if (filters?.tags && filters.tags.length > 0) params.tags = filters.tags;
  if (filters?.specs && filters.specs.length > 0) params.spec = filters.specs;
  // indexes:null → repeated `tags=a&tags=b` (Axios would otherwise emit `tags[]=`, which Spring
  // does not bind to a List<String>).
  return client
    .get<Part[]>('/parts', { params, paramsSerializer: { indexes: null } })
    .then((r) => r.data);
};

export const getPart = (id: number) =>
  client.get<Part>(`/parts/${id}`).then((r) => r.data);

export const getPartStock = (id: number) =>
  client.get<StockEntry[]>(`/parts/${id}/stock`).then((r) => r.data);

export const getPartMovements = (id: number) =>
  client.get<StockMovement[]>(`/parts/${id}/movements`).then((r) => r.data);

export const createPart = (data: PartCreateRequest) =>
  client.post<Part>('/parts', data).then((r) => r.data);

export const updatePart = (id: number, data: PartRequest) =>
  client.put<Part>(`/parts/${id}`, data).then((r) => r.data);

export const deletePart = (id: number) => client.delete(`/parts/${id}`);

// Tags
export const searchTags = (q: string) =>
  client.get<Tag[]>('/tags', { params: { q } }).then((r) => r.data);

/** Admin: delete every part created by a user. Resolves to the number of parts removed. */
export const deletePartsByUser = (userId: number) =>
  client
    .delete<{ deleted: number }>(`/parts/by-user/${userId}`)
    .then((r) => r.data.deleted);

// AI auto-categorization (local Ollama)
export const startAutoCategorize = (onlyUncategorized = false) =>
  client
    .post<CategorizationStatus>('/parts/auto-categorize', null, { params: { onlyUncategorized } })
    .then((r) => r.data);

export const getAutoCategorizeStatus = () =>
  client.get<CategorizationStatus>('/parts/auto-categorize/status').then((r) => r.data);

// Locations
export const getLocations = () =>
  client.get<Location[]>('/locations').then((r) => r.data);

export const getLocationTree = () =>
  client.get<LocationTree[]>('/locations/tree').then((r) => r.data);

export const getLocationStats = () =>
  client.get<LocationStats[]>('/locations/stats').then((r) => r.data);

// Locations owned by the current user (for stock-add pickers)
export const getMyLocations = () =>
  client.get<Location[]>('/locations/mine').then((r) => r.data);

export const getLocation = (id: number) =>
  client.get<Location>(`/locations/${id}`).then((r) => r.data);

export const createLocation = (data: LocationRequest) =>
  client.post<Location>('/locations', data).then((r) => r.data);

export const updateLocation = (id: number, data: LocationRequest) =>
  client.put<Location>(`/locations/${id}`, data).then((r) => r.data);

export const deleteLocation = (id: number) =>
  client.delete(`/locations/${id}`);

// Merge a location into another: its stock moves to the target, then the source is deleted.
export const mergeLocation = (id: number, targetId: number) =>
  client.post(`/locations/${id}/merge`, { targetId });

// Auth
export const login = (email: string, password: string) =>
  client.post<AuthUser>('/auth/login', { email, password }).then((r) => r.data);

export const logout = () => client.post('/auth/logout');

export const getMe = () =>
  client.get<AuthUser>('/auth/me').then((r) => r.data);

// Organisations — the tenant boundary (see types.ts)
export const getSelectableOrganisations = () =>
  client.get<Organisation[]>('/organisations/selectable').then((r) => r.data);

export const getOrganisations = () =>
  client.get<Organisation[]>('/organisations').then((r) => r.data);

export const createOrganisation = (data: OrganisationRequest) =>
  client.post<Organisation>('/organisations', data).then((r) => r.data);

export const updateOrganisation = (id: number, data: OrganisationRequest) =>
  client.put<Organisation>(`/organisations/${id}`, data).then((r) => r.data);

export const deleteOrganisation = (id: number) => client.delete(`/organisations/${id}`);

/** Switch the organisation in force for this session. */
export const switchOrganisation = (organisationId: number) =>
  client.put<Organisation>('/profile/organisation', { organisationId }).then((r) => r.data);

// App-wide settings
export const getSettings = () =>
  client.get<AppSettings>('/settings').then((r) => r.data);

// Users
/** Remove a user from the current organisation (the account itself remains). */
export const removeOrganisationMember = (id: number) => client.delete(`/users/members/${id}`);

/** Set a member's permissions within the current organisation. */
export const updateUserPermissions = (id: number, permissions: string[]) =>
  client.put<User>(`/users/${id}/permissions`, { permissions }).then((r) => r.data);

export const getUsers = () =>
  client.get<User[]>('/users').then((r) => r.data);

export const getUser = (id: number) =>
  client.get<User>(`/users/${id}`).then((r) => r.data);

// Invitations — how an Organisation Admin brings someone in. Creating and editing the account
// itself is GLOBAL_ADMIN and lives under /admin/users below.
export const getInvitations = () =>
  client.get<Invitation[]>('/invitations').then((r) => r.data);

/** Who an email address belongs to, for the invite dialog. */
export const lookupEmail = (email: string) =>
  client.get<EmailLookup>('/invitations/lookup', { params: { email } }).then((r) => r.data);

export const createInvitation = (data: InvitationRequest) =>
  client.post<Invitation>('/invitations', data).then((r) => r.data);

export const revokeInvitation = (id: number) => client.delete(`/invitations/${id}`);

// The invitee's side: reached from a mailed link with no session at all, so these are the only
// user endpoints that work unauthenticated. The token is the credential.
export const getInvitationByToken = (token: string) =>
  client.get<PublicInvitation>(`/invitations/token/${token}`).then((r) => r.data);

export const acceptInvitation = (token: string, data: AcceptInvitationRequest) =>
  client.post<PublicInvitation>(`/invitations/token/${token}/accept`, data).then((r) => r.data);

export const declineInvitation = (token: string) =>
  client.post<PublicInvitation>(`/invitations/token/${token}/decline`).then((r) => r.data);

// All Users — installation-wide administration (GLOBAL_ADMIN). Unlike the /users endpoints above,
// these cross organisation boundaries: they see every account and its permissions everywhere.
export const getAllUsers = () =>
  client.get<AdminUser[]>('/admin/users').then((r) => r.data);

export const getAdminUser = (id: number) =>
  client.get<AdminUser>(`/admin/users/${id}`).then((r) => r.data);

/** Create an account, in one or more organisations. */
export const createAdminUser = (data: UserRequest) =>
  client.post<AdminUser>('/admin/users', data).then((r) => r.data);

/** Delete an account entirely, in every organisation. */
export const deleteAdminUser = (id: number) => client.delete(`/admin/users/${id}`);

/** Update account details and global permissions (not per-organisation ones). */
export const updateAdminUser = (id: number, data: UserRequest) =>
  client.put<AdminUser>(`/admin/users/${id}`, data).then((r) => r.data);

export const addUserToOrganisation = (id: number, organisationId: number) =>
  client.post<AdminUser>(`/admin/users/${id}/organisations`, { organisationId }).then((r) => r.data);

export const removeUserFromOrganisation = (id: number, organisationId: number) =>
  client.delete<AdminUser>(`/admin/users/${id}/organisations/${organisationId}`).then((r) => r.data);

export const setUserPermissionsInOrganisation = (
  id: number,
  organisationId: number,
  permissions: string[],
) =>
  client
    .put<AdminUser>(`/admin/users/${id}/organisations/${organisationId}/permissions`, { permissions })
    .then((r) => r.data);

// Stock
export const getStock = () =>
  client.get<StockEntry[]>('/stock').then((r) => r.data);

// Stock thresholds (minimum on-hand per part at a root location)
export const getStockThresholds = (partId?: number) =>
  client
    .get<StockThreshold[]>('/stock-thresholds', { params: partId ? { partId } : {} })
    .then((r) => r.data);

export const getLowStockThresholds = () =>
  client.get<StockThreshold[]>('/stock-thresholds/low').then((r) => r.data);

export const upsertStockThreshold = (data: StockThresholdRequest) =>
  client.post<StockThreshold>('/stock-thresholds', data).then((r) => r.data);

export const deleteStockThreshold = (id: number) =>
  client.delete(`/stock-thresholds/${id}`);

export const getStockEntry = (id: number) =>
  client.get<StockEntry>(`/stock/${id}`).then((r) => r.data);

export const createStockEntry = (data: StockEntryRequest) =>
  client.post<StockEntry>('/stock', data).then((r) => r.data);

export const updateStockEntry = (id: number, data: StockEntryRequest) =>
  client.put<StockEntry>(`/stock/${id}`, data).then((r) => r.data);

export const deleteStockEntry = (id: number) =>
  client.delete(`/stock/${id}`);

// Add a quantity of stock at a location (creates the entry if needed).
export const addStock = (data: StockAdjustRequest) =>
  client.post<StockEntry>('/stock/add', data).then((r) => r.data);

// Take a quantity of stock from a location.
export const takeStock = (data: StockAdjustRequest) =>
  client.post<StockEntry>('/stock/take', data).then((r) => r.data);

// Move a quantity of stock from one location to another (destination may belong to any user).
export const moveStock = (data: StockMoveRequest) =>
  client.post('/stock/move', data);

// Dashboard
export const getDashboard = () =>
  client.get<Dashboard>('/dashboard').then((r) => r.data);

// The most recently added parts, newest first, one page at a time.
export const getRecentParts = (page: number, size: number) =>
  client
    .get<RecentPartsPage>('/dashboard/recent-parts', { params: { page, size } })
    .then((r) => r.data);

// Part attachments (photos, datasheets, user files)
export const getPartAttachments = (partId: number, type?: AttachmentType) =>
  client.get<PartAttachment[]>(`/parts/${partId}/attachments`, {
    params: type ? { type } : undefined,
  }).then((r) => r.data);

export const uploadPartAttachment = (partId: number, file: File, type: AttachmentType) => {
  const form = new FormData();
  form.append('file', file);
  form.append('type', type);
  return client.post<PartAttachment>(`/parts/${partId}/attachments`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((r) => r.data);
};

export const deletePartAttachment = (partId: number, attachmentId: number) =>
  client.delete(`/parts/${partId}/attachments/${attachmentId}`);

export const attachmentUrl = (partId: number, attachmentId: number) =>
  `${import.meta.env.BASE_URL}api/parts/${partId}/attachments/${attachmentId}`;

export const addAttachmentFromUrl = (partId: number, url: string, type: AttachmentType) =>
  client.post<PartAttachment>(`/parts/${partId}/attachments/from-url`, { url, type }).then((r) => r.data);

// Spec Definitions
export const getSpecDefinitions = () =>
  client.get<SpecDefinition[]>('/spec-definitions').then((r) => r.data);

export const createSpecDefinition = (data: SpecDefinitionRequest) =>
  client.post<SpecDefinition>('/spec-definitions', data).then((r) => r.data);

export const updateSpecDefinition = (id: number, data: SpecDefinitionRequest) =>
  client.put<SpecDefinition>(`/spec-definitions/${id}`, data).then((r) => r.data);

export const deleteSpecDefinition = (id: number) =>
  client.delete(`/spec-definitions/${id}`);

export const mergeSpecDefinitions = (data: MergeSpecsRequest) =>
  client.post<SpecDefinition>('/spec-definitions/merge', data).then((r) => r.data);

export const moveSpecDefinitions = (data: MoveSpecsRequest) =>
  client.post<SpecDefinition[]>('/spec-definitions/move', data).then((r) => r.data);

// ---- Spec groups ----

export const getSpecGroups = () =>
  client.get<SpecGroup[]>('/spec-groups').then((r) => r.data);

export const getSpecGroup = (id: number) =>
  client.get<SpecGroup>(`/spec-groups/${id}`).then((r) => r.data);

export const getSpecGroupFields = (id: number) =>
  client.get<SpecDefinition[]>(`/spec-groups/${id}/spec-definitions`).then((r) => r.data);

export const createSpecGroup = (data: SpecGroupRequest) =>
  client.post<SpecGroup>('/spec-groups', data).then((r) => r.data);

export const updateSpecGroup = (id: number, data: SpecGroupRequest) =>
  client.put<SpecGroup>(`/spec-groups/${id}`, data).then((r) => r.data);

export const deleteSpecGroup = (id: number) => client.delete(`/spec-groups/${id}`);

export const rescanSpecDefinitions = () =>
  client.post<SpecDefinition[]>('/spec-definitions/rescan').then((r) => r.data);

export const convertSpecToNumber = (id: number, body: ConvertToNumberRequest) =>
  client
    .post<ConvertToNumberResult>(`/spec-definitions/${id}/convert-to-number`, body)
    .then((r) => r.data);

export const getSpecsForCategory = (categoryId: number | null) =>
  categoryId != null
    ? client.get<SpecDefinition[]>(`/spec-definitions/for-category/${categoryId}`).then((r) => r.data)
    : client.get<SpecDefinition[]>('/spec-definitions').then((r) => r.data);

// Part search (AI-powered)
export const searchPartsOnline = (q: string) =>
  client.get<PartSearchResult[]>('/parts-search', { params: { q } }).then((r) => r.data);

/**
 * Read one page the user pasted and return what it says the component is.
 *
 * The way out when the search does not find a part: paste the distributor page, the manufacturer
 * page or the datasheet PDF and the AI reads that instead. Same result shape as
 * `searchPartsOnline`, so the caller shows the same cards.
 */
export const searchPartsByUrl = (url: string) =>
  client.get<PartSearchResult[]>('/parts-search/from-url', { params: { url } }).then((r) => r.data);

/** Quick Add: fuzzy-match existing parts by part number before searching the Internet. */
export const findLocalParts = (q: string) =>
  client.get<Part[]>('/parts/local-match', { params: { q } }).then((r) => r.data);

// Component cache — the local snapshot, consulted after the catalogue and before the web/AI search.
// Free, offline and instant, which is the whole argument for asking it first: the AI lookup costs
// 5–13 cents and several seconds, and for a mass-market part it mostly rediscovers what this holds.

/** Whether the snapshot is installed. Asked once so a screen can hide the stage entirely. */
export const getComponentCacheStatus = () =>
  client.get<ComponentCacheStatus>('/component-cache/status').then((r) => r.data);

/**
 * Matching parts, best first. Returns an empty list — never an error — when the cache is absent or
 * the term is too short, so a caller can always try it and move on.
 */
export const searchComponentCache = (q: string) =>
  client.get<ComponentCacheMatch[]>('/component-cache/search', { params: { q } }).then((r) => r.data);

/**
 * Everything the cache holds about one part, mapped onto this app's fields and spec keys. Writes
 * nothing: apply it through `quickAddPart` or `applyAiLookup`.
 */
export const loadComponentCachePart = (lcsc: string) =>
  client.get<ComponentCacheDetail>(`/component-cache/${encodeURIComponent(lcsc)}`).then((r) => r.data);

export const quickAddPart = (data: QuickAddRequest) =>
  client.post<QuickAddResponse>('/parts/quick-add', data).then((r) => r.data);

export const searchPartImages = (q: string) =>
  client.get<ImageSuggestion[]>('/parts-search/images', { params: { q } }).then((r) => r.data);

export const searchPartDatasheets = (q: string, forceAi?: boolean) =>
  client
    .get<DatasheetSearchResponse>('/parts-search/datasheets', { params: { q, forceAi } })
    .then((r) => r.data);

/**
 * Apply a chosen lookup result to an existing part. Free — the search already happened; this writes
 * only what the user ticked, merging specs onto the part rather than replacing them.
 */
export const applyAiLookup = (partId: number, data: AiApplyRequest) =>
  client.post<Part>(`/parts/${partId}/ai-apply`, data).then((r) => r.data);

/**
 * Read a datasheet already stored on the part and propose specs + a description from it. Writes
 * nothing: the result is confirmed field by field and applied through `applyAiLookup`.
 *
 * Omit `attachmentId` to read the part's first stored datasheet, which is the usual case.
 */
export const extractDatasheetSpecs = (partId: number, attachmentId?: number) =>
  client
    .post<DatasheetExtraction>(`/parts/${partId}/datasheet-extract`, null, {
      params: attachmentId ? { attachmentId } : undefined,
    })
    .then((r) => r.data);

// OctoPart (Nexar) enrichment
export const getOctopartUsage = () =>
  client.get<OctopartUsage>('/parts/octopart/usage').then((r) => r.data);

export const searchOctopart = (q: string) =>
  client.get<OctopartResult[]>('/parts/octopart/search', { params: { q } }).then((r) => r.data);

export const applyOctopart = (partId: number, data: OctopartApplyRequest) =>
  client.post<Part>(`/parts/octopart/${partId}/apply`, data).then((r) => r.data);

export const getOctopartCredentials = () =>
  client.get<OctopartCredentialsStatus>('/profile/octopart').then((r) => r.data);

export const updateOctopartCredentials = (data: OctopartCredentialsRequest) =>
  client.put<OctopartCredentialsStatus>('/profile/octopart', data).then((r) => r.data);

// Label printing: browser vs daemon
export const getPrintingPreference = () =>
  client.get<PrintingPreference>('/profile/printing').then((r) => r.data);

export const updatePrintingPreference = (data: PrintingPreferenceRequest) =>
  client.put<PrintingPreference>('/profile/printing', data).then((r) => r.data);

export const getPrintDaemons = () =>
  client.get<PrintDaemon[]>('/profile/print-daemons').then((r) => r.data);

export const claimPrintDaemon = (id: number) =>
  client.post<PrintDaemon>(`/profile/print-daemons/${id}/claim`).then((r) => r.data);

export const updatePrintDaemon = (id: number, data: PrintDaemonUpdateRequest) =>
  client.put<PrintDaemon>(`/profile/print-daemons/${id}`, data).then((r) => r.data);

export const deletePrintDaemon = (id: number) =>
  client.delete(`/profile/print-daemons/${id}`);

export const createPrintJob = (daemonId: number, labelPngBase64: string) =>
  client.post<PrintJob>('/print-jobs', { daemonId, labelPngBase64 }).then((r) => r.data);

export const getPrintJobStatus = (id: number) =>
  client.get<PrintJob>(`/print-jobs/${id}`).then((r) => r.data);

// Changelog
export const getUnreadChanges = () =>
  client.get<UnreadChanges>('/changes/unread').then((r) => r.data);

export const markChangesRead = (date: string) =>
  client.post('/changes/mark-read', { date });

// Projects
export const getProjects = () =>
  client.get<Project[]>('/projects').then((r) => r.data);

export const getProject = (id: number) =>
  client.get<Project>(`/projects/${id}`).then((r) => r.data);

export const createProject = (data: ProjectRequest) =>
  client.post<Project>('/projects', data).then((r) => r.data);

export const updateProject = (id: number, data: ProjectRequest) =>
  client.put<Project>(`/projects/${id}`, data).then((r) => r.data);

export const deleteProject = (id: number) =>
  client.delete(`/projects/${id}`);

export const addBomEntry = (projectId: number, data: ProjectBomRequest) =>
  client.post<ProjectBomEntry>(`/projects/${projectId}/bom`, data).then((r) => r.data);

export const updateBomEntry = (projectId: number, bomId: number, data: ProjectBomRequest) =>
  client.put<ProjectBomEntry>(`/projects/${projectId}/bom/${bomId}`, data).then((r) => r.data);

export const removeBomEntry = (projectId: number, bomId: number) =>
  client.delete(`/projects/${projectId}/bom/${bomId}`);

export const startBuild = (projectId: number) =>
  client.post<Project>(`/projects/${projectId}/start-build`).then((r) => r.data);

export const pullStock = (projectId: number, data: PullStockRequest) =>
  client.post<ProjectStockEntry>(`/projects/${projectId}/pull-stock`, data).then((r) => r.data);

export const completeProject = (projectId: number) =>
  client.post<Project>(`/projects/${projectId}/complete`).then((r) => r.data);

export const cancelProject = (projectId: number, data: CancelRequest) =>
  client.post<Project>(`/projects/${projectId}/cancel`, data).then((r) => r.data);

// Imported BOM

/** Null when the project has no BOM yet — the backend answers 204, not 404. */
export const getImportedBom = (projectId: number) =>
  client.get<ImportedBom | ''>(`/projects/${projectId}/bom`).then((r) => (r.data ? r.data : null));

/**
 * Uploads a BOM export. `commit: false` (the default) is a dry run: nothing is written and the
 * response reports what a commit would add, update and remove.
 *
 * Content-Type is overridden because the axios instance defaults to application/json; letting the
 * browser set it is what supplies the multipart boundary.
 */
export const importBomFile = (
  projectId: number,
  file: File,
  options: { mapping?: BomColumnMapping; commit?: boolean } = {},
) => {
  const form = new FormData();
  form.append('file', file);
  if (options.mapping) form.append('mapping', JSON.stringify(options.mapping));
  form.append('commit', String(options.commit ?? false));
  return client
    .post<BomImportPreview>(`/projects/${projectId}/bom/import`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    .then((r) => r.data);
};

export const bomFileUrl = (projectId: number) =>
  `${import.meta.env.BASE_URL}api/projects/${projectId}/bom/file`;

export const getBomLineCandidates = (projectId: number, lineId: number) =>
  client
    .get<BomCandidate[]>(`/projects/${projectId}/bom/lines/${lineId}/candidates`)
    .then((r) => r.data);

export const setBomLineMatch = (projectId: number, lineId: number, data: BomLineMatchRequest) =>
  client
    .put<ImportedBomLine>(`/projects/${projectId}/bom/lines/${lineId}`, data)
    .then((r) => r.data);

export const applyImportedBom = (projectId: number) =>
  client.post<BomApplyResult>(`/projects/${projectId}/bom/apply`).then((r) => r.data);

export const deleteImportedBom = (projectId: number) =>
  client.delete(`/projects/${projectId}/bom`);

// ── Part kit templates ────────────────────────────────────────────────────────

export const getPartKitTemplates = () =>
  client.get<PartKitTemplate[]>('/part-kit-templates').then((r) => r.data);

export const getPartKitTemplate = (id: number) =>
  client.get<PartKitTemplate>(`/part-kit-templates/${id}`).then((r) => r.data);

export const createPartKitTemplate = (data: PartKitTemplateRequest) =>
  client.post<PartKitTemplate>('/part-kit-templates', data).then((r) => r.data);

export const updatePartKitTemplate = (id: number, data: PartKitTemplateRequest) =>
  client.put<PartKitTemplate>(`/part-kit-templates/${id}`, data).then((r) => r.data);

export const deletePartKitTemplate = (id: number) =>
  client.delete(`/part-kit-templates/${id}`);

export const generatePartsFromKit = (id: number, data: PartKitGenerateRequest) =>
  client
    .post<PartKitGenerateResult>(`/part-kit-templates/${id}/generate`, data)
    .then((r) => r.data);

// The kit's generation history. Each run says whether it can still be undone and, when it cannot,
// why — a disabled Undo with no reason reads as a bug.
export const getPartKitGenerations = (id: number) =>
  client
    .get<PartKitGeneration[]>(`/part-kit-templates/${id}/generations`)
    .then((r) => r.data);

export const undoPartKitGeneration = (id: number, generationId: number) =>
  client
    .post<PartKitUndoResult>(`/part-kit-templates/${id}/generations/${generationId}/undo`)
    .then((r) => r.data);

// A kit template's images: the photos every part it generates is given. Same store as a part's own
// photos — generating links the parts to these very rows rather than copying them.
export const getPartKitImages = (id: number) =>
  client.get<PartAttachment[]>(`/part-kit-templates/${id}/images`).then((r) => r.data);

export const uploadPartKitImage = (id: number, file: File) => {
  const form = new FormData();
  form.append('file', file);
  return client
    .post<PartAttachment>(`/part-kit-templates/${id}/images`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    .then((r) => r.data);
};

export const deletePartKitImage = (id: number, attachmentId: number) =>
  client.delete(`/part-kit-templates/${id}/images/${attachmentId}`);

export const partKitImageUrl = (id: number, attachmentId: number) =>
  `${import.meta.env.BASE_URL}api/part-kit-templates/${id}/images/${attachmentId}`;
