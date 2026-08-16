import { useEffect, useState } from 'react';
import { time } from '../format';
import { CountdownRing } from './CountdownRing';
import type { CustomerView, OfferView } from '../types';

interface Props {
  offers: OfferView[];
  customers: CustomerView[];
  busy: boolean;
  onClaim: (offerId: string) => void;
}

/**
 * What the waitlisted customer sees: "a slot opened up, claim it before the
 * timer runs out".
 *
 * <p>In a real product this arrives as an SMS with a signed link. Showing it in
 * the dashboard makes the cascade watchable - you can see the timer run out on
 * one person and a fresh offer appear for the next.
 *
 * <p>The countdown is cosmetic. The deadline that matters is checked on the
 * server against the database clock, so a customer whose laptop clock is wrong
 * cannot claim an offer that has already expired.
 */
export function OfferInbox({ offers, customers, busy, onClaim }: Props) {
  const [, forceTick] = useState(0);

  // Re-render once a second so the countdowns move.
  useEffect(() => {
    const timer = setInterval(() => forceTick((n) => n + 1), 1000);
    return () => clearInterval(timer);
  }, []);

  const nameOf = (customerId: string) =>
    customers.find((c) => c.id === customerId)?.name ?? 'unknown';

  const secondsLeft = (offer: OfferView) =>
    Math.max(0, Math.round((new Date(offer.expiresAt).getTime() - Date.now()) / 1000));

  /**
   * The arc needs a denominator. `secondsRemaining` is what the server said the
   * offer had left when it was fetched, so it is the closest thing to the
   * configured TTL the client can see without a second endpoint. Falling back
   * to the elapsed value keeps the arc sane if the row arrives already stale.
   */
  const ttlFor = (offer: OfferView) => Math.max(offer.secondsRemaining, secondsLeft(offer), 1);

  const pending = offers.filter((o) => o.status === 'PENDING');
  const recent = offers.filter((o) => o.status !== 'PENDING').slice(0, 6);

  return (
    <>
      {pending.length === 0 && (
        <p className="empty">
          <strong>No live offers</strong>
          Cancel a booked slot and one appears here within a second.
        </p>
      )}

      {pending.map((offer) => (
        <div className="row live-offer" key={offer.id}>
          <CountdownRing remaining={secondsLeft(offer)} total={ttlFor(offer)} />
          <div className="grow">
            <div className="who">{nameOf(offer.customerId)}</div>
            <div className="meta">
              {time(offer.startsAt)}–{time(offer.endsAt)}
            </div>
          </div>
          <span className={`attempt ${offer.attempt === 1 ? 'first' : ''}`}>
            try {offer.attempt}
          </span>
          <button className="claim" disabled={busy} onClick={() => onClaim(offer.id)}>
            Claim
          </button>
        </div>
      ))}

      {recent.length > 0 && (
        <>
          <p className="section-label">Recent offers</p>
          {recent.map((offer) => (
            <div className="row" key={offer.id}>
              <div className="grow">
                <div className="who">{nameOf(offer.customerId)}</div>
                <div className="meta">
                  {time(offer.startsAt)} · try {offer.attempt}
                </div>
              </div>
              <span className={`badge ${offer.status.toLowerCase()}`}>{offer.status}</span>
            </div>
          ))}
        </>
      )}
    </>
  );
}
