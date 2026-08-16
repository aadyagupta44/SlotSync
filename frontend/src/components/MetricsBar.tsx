import type { Metrics } from '../types';

/**
 * The business case, in numbers.
 *
 * These are the figures the project is actually judged on: how much of the
 * cancelled capacity came back, and how quickly.
 *
 * <p>Tints follow the same domain rule as the rest of the console - green for
 * settled outcomes, amber for anything in flight, red only when a number means
 * something needs attention. The pipeline tile turns red when a dead letter
 * exists, because that is the one figure here that is a real alarm.
 */
export function MetricsBar({ metrics }: { metrics: Metrics | null }) {
  if (!metrics) {
    return null;
  }

  const seconds = (value: number | null) => (value == null ? '—' : `${Math.round(value)}s`);

  return (
    <div className="metrics">
      <Tile label="Cancellations" value={String(metrics.cancellations)} />
      <Tile
        label="Auto-refilled"
        value={String(metrics.autoRefilled)}
        note={`${metrics.refillRatePercent}% of cancellations`}
        tint="green"
      />
      <Tile
        label="Median refill"
        value={seconds(metrics.medianRefillSeconds)}
        note="cancel → claim"
        tint="cyan"
      />
      <Tile label="p90 refill" value={seconds(metrics.p90RefillSeconds)} tint="cyan" />
      <Tile
        label="Offers"
        value={String(metrics.offersMade)}
        note={`${metrics.offersExpired} expired · ${metrics.pendingOffers} live`}
        tint={metrics.pendingOffers > 0 ? 'amber' : undefined}
      />
      <Tile
        label="Needed a cascade"
        value={`${metrics.cascadeRate}%`}
        note="refills that took more than one try"
        tint="amber"
      />
      <Tile label="Waiting" value={String(metrics.waitlistWaiting)} note="people in the queue" />
      <Tile
        label="Pipeline"
        value={String(metrics.outboxPending)}
        note={`outbox backlog · ${metrics.deadLetters} dead letters`}
        tint={metrics.deadLetters > 0 ? 'red' : undefined}
      />
    </div>
  );
}

function Tile({
  label,
  value,
  note,
  tint,
}: {
  label: string;
  value: string;
  note?: string;
  tint?: 'cyan' | 'amber' | 'green' | 'red';
}) {
  return (
    <div className={`metric ${tint ? `tint-${tint}` : ''}`}>
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {note && <div className="note">{note}</div>}
    </div>
  );
}
