import type {
  BookingView,
  CustomerView,
  Metrics,
  NotificationView,
  OfferView,
  ResourceView,
  SlotView,
  WaitlistView,
} from './types';

// Empty string means "same origin". In dev, Vite proxies /api to :8080;
// in Docker, nginx proxies it to the backend container.
const BASE = import.meta.env.VITE_API_BASE_URL ?? '';

/**
 * Which business we are acting as. Real deployments would take this from the
 * logged-in user's token; there is no auth here, so it is a constant.
 */
export const TENANT_SLUG = 'demo-clinic';

class ApiFailure extends Error {
  code: string;

  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${BASE}/api/v1${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-Tenant-Slug': TENANT_SLUG,
      // Every write carries a fresh idempotency key. If the browser retries
      // (or the user double-clicks), the server replays the first response
      // instead of doing the work twice.
      ...(init.method === 'POST' ? { 'Idempotency-Key': crypto.randomUUID() } : {}),
      ...(init.headers ?? {}),
    },
  });

  if (!response.ok) {
    const body = await response.json().catch(() => ({ code: 'UNKNOWN', message: response.statusText }));
    throw new ApiFailure(body.code ?? 'UNKNOWN', body.message ?? 'Request failed');
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

export const api = {
  resources: () => request<ResourceView[]>('/resources'),

  customers: () => request<CustomerView[]>('/customers'),

  availability: (resourceId: string, date: string) =>
    request<SlotView[]>(`/resources/${resourceId}/availability?date=${date}`),

  book: (resourceId: string, customerId: string, startsAt: string) =>
    request<BookingView>('/bookings', {
      method: 'POST',
      body: JSON.stringify({ resourceId, customerId, startsAt }),
    }),

  cancel: (bookingId: string) =>
    request<BookingView>(`/bookings/${bookingId}/cancel`, { method: 'POST' }),

  waitlist: () => request<WaitlistView[]>('/waitlist'),

  joinWaitlist: (body: {
    resourceId: string;
    customerId: string;
    windowStart: string;
    windowEnd: string;
    priority: number;
  }) => request<WaitlistView>('/waitlist', { method: 'POST', body: JSON.stringify(body) }),

  withdraw: (entryId: string) =>
    request<WaitlistView>(`/waitlist/${entryId}`, { method: 'DELETE' }),

  offers: () => request<OfferView[]>('/offers'),

  claim: (offerId: string) =>
    request<OfferView>(`/offers/${offerId}/claim`, { method: 'POST' }),

  notifications: () => request<NotificationView[]>('/notifications?limit=30'),

  metrics: () => request<Metrics>('/metrics'),

  /**
   * URL for the live event stream.
   *
   * The browser's EventSource cannot set custom headers, so the tenant travels
   * as a query parameter here instead of the usual X-Tenant-Slug header.
   */
  streamUrl: () => `${BASE}/api/v1/stream?tenant=${TENANT_SLUG}`,
};

export { ApiFailure };
