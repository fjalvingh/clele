import client from './client';
import type {
  AppSettings,
  AuthUser,
  CategorizationStatus,
  Category,
  CategoryRequest,
  CategoryTree,
  Dashboard,
  ImageSuggestion,
  Location,
  LocationRequest,
  LocationTree,
  OctopartApplyRequest,
  OctopartCredentialsRequest,
  OctopartCredentialsStatus,
  OctopartResult,
  OctopartUsage,
  AttachmentType,
  ConvertToNumberRequest,
  ConvertToNumberResult,
  Part,
  PartAttachment,
  PartRequest,
  PartSearchResult,
  QuickAddRequest,
  QuickAddResponse,
  SpecDefinition,
  SpecDefinitionRequest,
  StockAdjustRequest,
  StockEntry,
  StockEntryRequest,
  StockMoveRequest,
  StockMovement,
  StockThreshold,
  StockThresholdRequest,
  UnreadChanges,
  User,
  UserRequest,
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
export const getParts = (search?: string, categoryId?: number, sort?: string) => {
  const params: Record<string, string | number> = {};
  if (search) params.search = search;
  if (categoryId) params.categoryId = categoryId;
  if (sort) params.sort = sort;
  return client.get<Part[]>('/parts', { params }).then((r) => r.data);
};

export const getPart = (id: number) =>
  client.get<Part>(`/parts/${id}`).then((r) => r.data);

export const getPartStock = (id: number) =>
  client.get<StockEntry[]>(`/parts/${id}/stock`).then((r) => r.data);

export const getPartMovements = (id: number) =>
  client.get<StockMovement[]>(`/parts/${id}/movements`).then((r) => r.data);

export const createPart = (data: PartRequest) =>
  client.post<Part>('/parts', data).then((r) => r.data);

export const updatePart = (id: number, data: PartRequest) =>
  client.put<Part>(`/parts/${id}`, data).then((r) => r.data);

export const deletePart = (id: number) => client.delete(`/parts/${id}`);

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

// App-wide settings
export const getSettings = () =>
  client.get<AppSettings>('/settings').then((r) => r.data);

// Users
export const getUsers = () =>
  client.get<User[]>('/users').then((r) => r.data);

export const getUser = (id: number) =>
  client.get<User>(`/users/${id}`).then((r) => r.data);

export const createUser = (data: UserRequest) =>
  client.post<User>('/users', data).then((r) => r.data);

export const updateUser = (id: number, data: UserRequest) =>
  client.put<User>(`/users/${id}`, data).then((r) => r.data);

export const deleteUser = (id: number) =>
  client.delete(`/users/${id}`);

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

/** Quick Add: fuzzy-match existing parts by part number before searching the Internet. */
export const findLocalParts = (q: string) =>
  client.get<Part[]>('/parts/local-match', { params: { q } }).then((r) => r.data);

export const quickAddPart = (data: QuickAddRequest) =>
  client.post<QuickAddResponse>('/parts/quick-add', data).then((r) => r.data);

export const searchPartImages = (q: string) =>
  client.get<ImageSuggestion[]>('/parts-search/images', { params: { q } }).then((r) => r.data);

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

// Changelog
export const getUnreadChanges = () =>
  client.get<UnreadChanges>('/changes/unread').then((r) => r.data);

export const markChangesRead = (date: string) =>
  client.post('/changes/mark-read', { date });
