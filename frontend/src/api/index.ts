import client from './client';
import type {
  Category,
  CategoryRequest,
  CategoryTree,
  Dashboard,
  ImageSuggestion,
  Location,
  LocationRequest,
  Part,
  PartImage,
  PartRequest,
  PartSearchResult,
  QuickAddRequest,
  QuickAddResponse,
  StockEntry,
  StockEntryRequest,
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
export const getParts = (search?: string, categoryId?: number) => {
  const params: Record<string, string | number> = {};
  if (search) params.search = search;
  if (categoryId) params.categoryId = categoryId;
  return client.get<Part[]>('/parts', { params }).then((r) => r.data);
};

export const getPart = (id: number) =>
  client.get<Part>(`/parts/${id}`).then((r) => r.data);

export const getPartStock = (id: number) =>
  client.get<StockEntry[]>(`/parts/${id}/stock`).then((r) => r.data);

export const createPart = (data: PartRequest) =>
  client.post<Part>('/parts', data).then((r) => r.data);

export const updatePart = (id: number, data: PartRequest) =>
  client.put<Part>(`/parts/${id}`, data).then((r) => r.data);

export const deletePart = (id: number) => client.delete(`/parts/${id}`);

// Locations
export const getLocations = () =>
  client.get<Location[]>('/locations').then((r) => r.data);

export const getLocation = (id: number) =>
  client.get<Location>(`/locations/${id}`).then((r) => r.data);

export const createLocation = (data: LocationRequest) =>
  client.post<Location>('/locations', data).then((r) => r.data);

export const updateLocation = (id: number, data: LocationRequest) =>
  client.put<Location>(`/locations/${id}`, data).then((r) => r.data);

export const deleteLocation = (id: number) =>
  client.delete(`/locations/${id}`);

// Stock
export const getStock = () =>
  client.get<StockEntry[]>('/stock').then((r) => r.data);

export const getLowStock = () =>
  client.get<StockEntry[]>('/stock/low').then((r) => r.data);

export const getStockEntry = (id: number) =>
  client.get<StockEntry>(`/stock/${id}`).then((r) => r.data);

export const createStockEntry = (data: StockEntryRequest) =>
  client.post<StockEntry>('/stock', data).then((r) => r.data);

export const updateStockEntry = (id: number, data: StockEntryRequest) =>
  client.put<StockEntry>(`/stock/${id}`, data).then((r) => r.data);

export const deleteStockEntry = (id: number) =>
  client.delete(`/stock/${id}`);

// Dashboard
export const getDashboard = () =>
  client.get<Dashboard>('/dashboard').then((r) => r.data);

// Part images
export const getPartImages = (partId: number) =>
  client.get<PartImage[]>(`/parts/${partId}/images`).then((r) => r.data);

export const uploadPartImage = (partId: number, file: File) => {
  const form = new FormData();
  form.append('file', file);
  return client.post<PartImage>(`/parts/${partId}/images`, form).then((r) => r.data);
};

export const deletePartImage = (partId: number, imageId: number) =>
  client.delete(`/parts/${partId}/images/${imageId}`);

export const partImageUrl = (partId: number, imageId: number) =>
  `/api/parts/${partId}/images/${imageId}`;

export const addPartImageFromUrl = (partId: number, url: string) =>
  client.post<PartImage>(`/parts/${partId}/images/from-url`, { url }).then((r) => r.data);

// Part search (AI-powered)
export const searchPartsOnline = (q: string) =>
  client.get<PartSearchResult[]>('/parts-search', { params: { q } }).then((r) => r.data);

export const quickAddPart = (data: QuickAddRequest) =>
  client.post<QuickAddResponse>('/parts/quick-add', data).then((r) => r.data);

export const searchPartImages = (q: string) =>
  client.get<ImageSuggestion[]>('/parts-search/images', { params: { q } }).then((r) => r.data);
