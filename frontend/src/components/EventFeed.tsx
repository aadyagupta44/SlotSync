import { clock } from '../format';
import type { EventEnvelope } from '../types';

/**
 * Raw domain events as they happen, straight off the SSE stream.
 *
 * <p>This is the most useful panel for explaining the system: cancel a booking
 * and you watch {@code booking.cancelled} then {@code slot.freed} then
 * {@code offer.created} arrive in order - the message pipeline made visible.
 *
 * <p>Styled as a tape rather than a list. Each line flashes as it lands, so a
 * viewer sees the pipeline react rather than just finding new text on screen.
 */
export function EventFeed({ events }: { events: EventEnvelope[] }) {
  if (events.length === 0) {
    return (
      <p className="empty">
        <strong>Listening…</strong>
        Cancel a booking and three events land here in order.
      </p>
    );
  }

  return (
    <div className="feed">
      {events.map((event) => {
        // "offer.created" -> "offer", which drives both the dot and the colour.
        const family = event.eventType.split('.')[0];
        return (
          <div className={`line ${family}`} key={event.eventId}>
            <span className="ts">{clock(event.occurredAt)}</span>
            <span className="glyph" />
            <span className={`type type-${family}`}>{event.eventType}</span>
          </div>
        );
      })}
    </div>
  );
}
