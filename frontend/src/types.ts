export interface EventDto {
  id: string;
  name: string;
  description: string | null;
  startsAt: string;
  state: string;
}

export interface InventoryPool {
  id: string;
  section: string;
  total: number;
  available: number;
}

export interface Reservation {
  id: string;
  eventId: string;
  section: string;
  quantity: number;
  state: 'HELD' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED' | 'FAILED';
  createdAt: string;
  holdExpiresAt: string;
}

export interface Order {
  id: string;
  reservationId: string | null;
  state: 'PENDING_PAYMENT' | 'CONFIRMED' | 'FAILED' | 'CANCELLED' | 'EXPIRED';
  amountCents: number;
  currency: string;
  createdAt: string;
}

export interface ChargeResult {
  orderId: string;
  orderState: string;
  providerRef: string;
  outcome: 'SUCCESS' | 'FAILURE' | 'TIMEOUT';
  message: string;
}

export interface NotificationItem {
  id: number;
  userId: number;
  kind: string;
  body: string;
  createdAt: string;
}

export interface AdminMetrics {
  pools: Array<{
    id: string;
    eventId: number;
    section: string;
    total: number;
    available: number;
    held: number;
    sold: number;
  }>;
  reservations: Record<string, number>;
  outbox: Record<string, number>;
  deadLetters: number;
  auditedAt: string;
}
