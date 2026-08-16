/** Mirrors the backend DTOs in `com.slotsync.web.Dtos`. */

export interface ResourceView {
  id: string;
  name: string;
  timezone: string;
  openingTime: string;
  closingTime: string;
  slotMinutes: number;
}

export interface CustomerView {
  id: string;
  name: string;
  email: string;
  phone: string | null;
}

export type SlotStatus = 'OPEN' | 'HELD' | 'BOOKED';

export interface SlotView {
  startsAt: string;
  endsAt: string;
  status: SlotStatus;
  bookingId: string | null;
  customerId: string | null;
}

export interface BookingView {
  id: string;
  resourceId: string;
  customerId: string;
  startsAt: string;
  endsAt: string;
  status: 'HELD' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED';
  origin: 'DIRECT' | 'WAITLIST';
  holdExpiresAt: string | null;
  cancelledAt: string | null;
  confirmedAt: string | null;
}

export interface WaitlistView {
  id: string;
  resourceId: string;
  customerId: string;
  windowStart: string;
  windowEnd: string;
  priority: number;
  missedOffers: number;
  status: 'WAITING' | 'OFFERED' | 'CONVERTED' | 'WITHDRAWN' | 'EXHAUSTED';
  createdAt: string;
}

export interface OfferView {
  id: string;
  resourceId: string;
  customerId: string;
  waitlistEntryId: string;
  bookingId: string;
  startsAt: string;
  endsAt: string;
  status: 'PENDING' | 'CLAIMED' | 'EXPIRED' | 'CANCELLED';
  attempt: number;
  freedAt: string;
  expiresAt: string;
  claimedAt: string | null;
  secondsRemaining: number;
}

export interface NotificationView {
  id: string;
  customerId: string;
  channel: string;
  subject: string;
  body: string;
  offerId: string | null;
  createdAt: string;
}

export interface Metrics {
  cancellations: number;
  autoRefilled: number;
  refillRatePercent: number;
  medianRefillSeconds: number | null;
  p90RefillSeconds: number | null;
  offersMade: number;
  offersExpired: number;
  pendingOffers: number;
  waitlistWaiting: number;
  cascadeRate: number;
  outboxPending: number;
  deadLetters: number;
}

/** What arrives on the SSE stream. */
export interface EventEnvelope {
  eventId: string;
  tenantId: string;
  eventType: string;
  aggregateType: string;
  aggregateId: string;
  partitionKey: string;
  payload: string;
  occurredAt: string;
}

export interface ApiError {
  code: string;
  message: string;
}
