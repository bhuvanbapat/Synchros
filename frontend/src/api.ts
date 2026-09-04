import type { Reservation, InventoryPool, Order, ChargeResult, AdminMetrics, NotificationItem } from './types';

const BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8081';

export interface Auth {
  email: string;
  password: string;
}

function headers(auth: Auth, extra: Record<string, string> = {}): HeadersInit {
  return {
    Authorization: 'Basic ' + btoa(auth.email + ':' + auth.password),
    'Content-Type': 'application/json',
    ...extra,
  };
}

export class ApiError extends Error {
  readonly code: string;
  readonly status: number;

  constructor(code: string, message: string, status: number) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

async function parse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let code = 'HTTP_' + res.status;
    let message = res.statusText;
    try {
      const body = (await res.json()) as { code?: string; message?: string };
      if (body.code) code = body.code;
      if (body.message) message = body.message;
    } catch {
      /* non-JSON error */
    }
    throw new ApiError(code, message, res.status);
  }
  return (await res.json()) as T;
}

// ---- catalog ----

export interface EventDto {
  id: string;
  name: string;
  description: string | null;
  startsAt: string;
  state: string;
}

export function listEvents(auth: Auth): Promise<EventDto[]> {
  return fetch(`${BASE}/api/events`, { headers: headers(auth) }).then(parse<EventDto[]>);
}

export function fetchInventory(auth: Auth, eventId: string): Promise<InventoryPool[]> {
  return fetch(`${BASE}/api/inventory?eventId=${eventId}`, {
    headers: headers(auth),
  }).then(parse<InventoryPool[]>);
}

// ---- reservations ----

export function createReservation(
  auth: Auth,
  eventId: string,
  section: string,
  quantity: number,
  idempotencyKey: string,
): Promise<Reservation> {
  return fetch(`${BASE}/api/reservations`, {
    method: 'POST',
    headers: headers(auth, { 'Idempotency-Key': idempotencyKey }),
    body: JSON.stringify({ eventId, section, quantity }),
  }).then(parse<Reservation>);
}

export function getReservation(auth: Auth, id: string): Promise<Reservation> {
  return fetch(`${BASE}/api/reservations/${id}`, { headers: headers(auth) }).then(parse<Reservation>);
}

export function cancelReservation(auth: Auth, id: string): Promise<Reservation> {
  return fetch(`${BASE}/api/reservations/${id}/cancel`, {
    method: 'POST',
    headers: headers(auth),
  }).then(parse<Reservation>);
}

export function listMyNotifications(auth: Auth): Promise<NotificationItem[]> {
  return fetch(`${BASE}/api/users/me/notifications`, {
    headers: headers(auth),
  }).then(parse<NotificationItem[]>);
}

// ---- orders & payments ----

export function createOrder(auth: Auth, reservationId: string): Promise<Order> {
  return fetch(`${BASE}/api/orders`, {
    method: 'POST',
    headers: headers(auth),
    body: JSON.stringify({ reservationId }),
  }).then(parse<Order>);
}

export function payOrder(auth: Auth, orderId: string): Promise<ChargeResult> {
  return fetch(`${BASE}/api/orders/${orderId}/pay`, {
    method: 'POST',
    headers: headers(auth),
  }).then(parse<ChargeResult>);
}

// ---- admin ----

export function adminMetrics(auth: Auth): Promise<AdminMetrics> {
  return fetch(`${BASE}/api/admin/metrics`, { headers: headers(auth) }).then(parse<AdminMetrics>);
}

export function adminReconcile(auth: Auth): Promise<unknown> {
  return fetch(`${BASE}/api/admin/reconciliation`, {
    method: 'POST',
    headers: headers(auth),
  }).then(parse<unknown>);
}
