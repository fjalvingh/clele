import { type PurchaseOrderStatus } from '../api/types';

export const ORDER_STATUS_LABELS: Record<PurchaseOrderStatus, string> = {
  DRAFT: 'Draft',
  ORDERED: 'Ordered',
  RECEIVED: 'Received',
  CANCELLED: 'Cancelled',
};

export const ORDER_STATUS_COLORS: Record<PurchaseOrderStatus, string> = {
  DRAFT: 'bg-blue-500/15 text-blue-700',
  ORDERED: 'bg-amber-500/15 text-amber-800',
  RECEIVED: 'bg-green-500/15 text-green-700',
  CANCELLED: 'bg-gray-500/15 text-gray-500',
};
