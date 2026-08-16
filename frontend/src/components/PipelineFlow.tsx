import { useEffect, useMemo, useState } from 'react';
import type { EventEnvelope } from '../types';

/** How long a stage stays lit after its event arrives. */
const GLOW_MS = 2200;

/**
 * The stages an opening passes through, in order. `offer.expired` is drawn as
 * a return path rather than a forward step, because that is literally what it
 * is: expiry republishes `slot.freed` and the whole thing runs again for the
 * next candidate.
 */
const STAGES = [
  { key: 'booking.cancelled', label: 'cancelled', tint: 'red' },
  { key: 'slot.freed', label: 'slot.freed', tint: 'cyan' },
  { key: 'offer.created', label: 'offer.created', tint: 'amber' },
  { key: 'offer.claimed', label: 'claimed', tint: 'green' },
] as const;

/**
 * The message pipeline, drawn.
 *
 * <p>The event feed below proves the pipeline exists; this shows its *shape*.
 * Each node lights as its event lands, so cancelling a booking visibly pushes a
 * pulse left to right, and an expiry visibly kicks it back to `slot.freed` to
 * run again. That return arrow is the cascade — the single most important idea
 * in the system, and the hardest one to convey in words.
 *
 * <p>Derived entirely from the existing SSE stream. No extra endpoint, no
 * polling: it is the same data the feed renders, arranged so the architecture
 * is legible instead of implied.
 */
export function PipelineFlow({ events }: { events: EventEnvelope[] }) {
  const [now, setNow] = useState(() => Date.now());

  // Only tick while something is still glowing, so an idle dashboard is not
  // re-rendering four times a second for no reason.
  const latest = events[0]?.occurredAt;
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, []);

  /** Most recent arrival time per event type. */
  const lastSeen = useMemo(() => {
    const seen: Record<string, number> = {};
    for (const event of events) {
      const at = new Date(event.occurredAt).getTime();
      if (!seen[event.eventType] || at > seen[event.eventType]) {
        seen[event.eventType] = at;
      }
    }
    return seen;
  }, [events, latest]);

  const heat = (key: string) => {
    const at = lastSeen[key];
    if (!at) return 0;
    const age = now - at;
    return age < 0 || age > GLOW_MS ? 0 : 1 - age / GLOW_MS;
  };

  const cascading = heat('offer.expired') > 0;

  return (
    <div className="flow" role="img" aria-label="Live event pipeline">
      {STAGES.map((stage, i) => {
        const h = heat(stage.key);
        return (
          <div className="flow-step" key={stage.key}>
            <div
              className={`node tint-${stage.tint} ${h > 0 ? 'hot' : ''}`}
              style={{ '--heat': h } as React.CSSProperties}
            >
              <span className="node-dot" />
            </div>
            <span className={`node-label ${h > 0 ? 'hot' : ''}`}>{stage.label}</span>
            {i < STAGES.length - 1 && (
              <span className={`wire ${h > 0 ? 'hot' : ''}`}>
                <span className="packet" />
              </span>
            )}
          </div>
        );
      })}

      {/* The cascade: expiry sends the opening back to be offered again. */}
      <div className={`cascade-arc ${cascading ? 'hot' : ''}`}>
        <span className="arc-label">offer.expired → cascade</span>
      </div>
    </div>
  );
}
