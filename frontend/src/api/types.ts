// Display grouping for specs. Order here drives the column order on the Part detail page.
export const MAJOR_TYPES = [
  { key: 'DIMENSIONS', label: 'Dimensions' },
  { key: 'TECHNICAL', label: 'Technical' },
  { key: 'PHYSICAL', label: 'Physical' },
] as const;

export interface SpecDefinition {
  id: number;
  jsonName: string; // machine key used inside part.specs
  name: string;
  dataType: string; // TEXT | NUMBER | BOOLEAN | SELECT
  unit?: string;
  options?: string[];
  displayOrder: number;
  majorType: string; // DIMENSIONS | TECHNICAL | PHYSICAL
}

export interface SpecDefinitionRequest {
  jsonName: string;
  name: string;
  dataType: string;
  unit?: string;
  options?: string[];
  displayOrder: number;
  majorType: string;
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
  name: string;
  description?: string;
  manufacturer?: string;
  datasheetUrl?: string;
  specs?: Record<string, string>;
  categoryId?: number;
  categoryName?: string;
  categoryBreadcrumb?: string;
  createdAt: string;
  updatedAt: string;
}

export interface PartRequest {
  partNumber: string;
  name: string;
  description?: string;
  manufacturer?: string;
  datasheetUrl?: string;
  specs?: Record<string, string>;
  categoryId?: number | null;
}

export interface Location {
  id: number;
  name: string;
  description?: string;
  ownerId?: number;
  ownerName?: string;
}

export interface LocationRequest {
  name: string;
  description?: string;
}

// Users & auth
export interface User {
  id: number;
  email: string;
  fullName?: string;
  phone?: string;
  permissions: string[];
  defaultLocationId?: number;
  defaultLocationName?: string;
}

export interface UserRequest {
  email: string;
  password?: string; // blank when editing keeps the existing password
  fullName?: string;
  phone?: string;
  permissions: string[];
  defaultLocationName?: string; // create: name of the default location to create
  defaultLocationId?: number; // edit: which owned location is the default
}

/** The currently authenticated user (same shape as User). */
export type AuthUser = User;

/** Known permission keys and their human-readable labels (shown in the user form). */
export const PERMISSIONS: { key: string; label: string }[] = [
  { key: 'PARTS_EDIT', label: 'Add/edit parts' },
  { key: 'USERS_EDIT', label: 'Add/edit users' },
];

export interface StockEntry {
  id: number;
  partId: number;
  partName: string;
  partNumber: string;
  locationId: number;
  locationName: string;
  quantity: number;
  minimumQuantity: number;
  lowStock: boolean;
  unitPrice?: number | null;
}

export interface StockMovement {
  id: number;
  partId: number;
  locationId: number;
  locationName: string;
  quantity: number;
  unitPrice?: number | null;
  currency?: string | null;
  comments?: string | null;
  movedAt: string;
  createdBy?: string | null;
}

export interface StockEntryRequest {
  partId: number;
  locationId: number;
  quantity: number;
  minimumQuantity: number;
  unitPrice?: number | null;
}

export interface PartSearchResult {
  mpn: string;
  manufacturer?: string;
  shortDescription?: string;
  datasheetUrl?: string;
  category?: string;
  specs: string[];
}

export interface QuickAddRequest {
  partNumber: string;
  name: string;
  description?: string;
  manufacturer?: string;
  datasheetUrl?: string;
  specs?: Record<string, string>;
  categoryId?: number | null;
  locationId: number;
  quantity: number;
  minimumQuantity: number;
  unitPrice?: number | null;
}

export interface QuickAddResponse {
  part: Part;
  stockEntry: StockEntry;
}

export interface ImageSuggestion {
  url: string;          // original image URL — used for saving to the part
  thumbnailUrl?: string; // smaller preview URL — used for display only
  description?: string;
}

export interface PartImage {
  id: number;
  partId: number;
  displayOrder: number;
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

export interface Dashboard {
  totalParts: number;
  totalLocations: number;
  totalCategories: number;
  lowStockCount: number;
  totalStockValue: number;
}
