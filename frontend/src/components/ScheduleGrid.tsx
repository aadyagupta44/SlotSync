import { time } from '../format';
import type { CustomerView, SlotView } from '../types';

interface Props {
  slots: SlotView[];
  customers: CustomerView[];
  busy: boolean;
  onBook: (startsAt: string) => void;
  onCancel: (bookingId: string) => void;
}

/**
 * The day's schedule for one resource.
 *
 * <p>A slot is one of three things:
 * <ul>
 *   <li><b>OPEN</b> - bookable now;</li>
 *   <li><b>BOOKED</b> - a confirmed appointment; cancelling it is what starts
 *       the waitlist engine;</li>
 *   <li><b>HELD</b> - reserved for a waitlisted customer who is deciding right
 *       now. Nobody else can take it, and if they do not answer in time it
 *       goes back to OPEN and cascades onward.</li>
 * </ul>
 *
 * <p>HELD is the only state that animates. That is deliberate: it is the only
 * state with a deadline attached, and the pulse is what makes a reviewer ask
 * "why is that one moving?" - which is the question the whole project answers.
 */
export function ScheduleGrid({ slots, customers, busy, onBook, onCancel }: Props) {
  const nameOf = (customerId: string | null) =>
    customers.find((c) => c.id === customerId)?.name ?? 'unknown';

  if (slots.length === 0) {
    return (
      <p className="empty">
        <strong>Nothing scheduled</strong>
        This resource has no slots on the selected day.
      </p>
    );
  }

  return (
    <div className="slots">
      {slots.map((slot) => (
        <div key={slot.startsAt} className={`slot ${slot.status.toLowerCase()}`}>
          <div className="range">
            {time(slot.startsAt)}–{time(slot.endsAt)}
          </div>
          <div className="state">
            {slot.status === 'OPEN' && 'available'}
            {slot.status === 'BOOKED' && nameOf(slot.customerId)}
            {slot.status === 'HELD' && `held · ${nameOf(slot.customerId)}`}
          </div>

          {slot.status === 'OPEN' && (
            <button disabled={busy} onClick={() => onBook(slot.startsAt)}>
              Book
            </button>
          )}
          {slot.status === 'BOOKED' && (
            <button
              className="danger"
              disabled={busy}
              onClick={() => onCancel(slot.bookingId!)}
            >
              Cancel
            </button>
          )}
          {slot.status === 'HELD' && <button disabled>Deciding…</button>}
        </div>
      ))}
    </div>
  );
}
