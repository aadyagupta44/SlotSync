/**
 * A live offer's deadline, drawn as a depleting arc.
 *
 * <p>Text alone ("2:14") makes a deadline something you read. An arc makes it
 * something you feel — which is the point of the demo, because the whole
 * cascade hangs on this timer running out.
 *
 * <p>Cosmetic only. The deadline that decides anything is re-checked on the
 * server against the database clock (`OfferService.claim`), so a customer whose
 * laptop clock is wrong cannot claim an offer that has already expired. This
 * component deliberately has no authority.
 */
interface Props {
  /** Seconds still on the clock. */
  remaining: number;
  /** The offer's full TTL, used as the denominator for the arc. */
  total: number;
}

const RADIUS = 20;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

export function CountdownRing({ remaining, total }: Props) {
  const fraction = total > 0 ? Math.max(0, Math.min(1, remaining / total)) : 0;
  const urgent = remaining <= 10;

  const minutes = Math.floor(remaining / 60);
  const seconds = remaining % 60;
  const label =
    remaining <= 0
      ? '0:00'
      : minutes > 0
        ? `${minutes}:${String(seconds).padStart(2, '0')}`
        : `0:${String(seconds).padStart(2, '0')}`;

  return (
    <div
      className={`ring ${urgent ? 'urgent' : ''}`}
      role="timer"
      aria-label={`${remaining} seconds remaining to claim`}
    >
      <svg viewBox="0 0 46 46" aria-hidden="true">
        <circle className="track" cx="23" cy="23" r={RADIUS} />
        <circle
          className="arc"
          cx="23"
          cy="23"
          r={RADIUS}
          strokeDasharray={CIRCUMFERENCE}
          strokeDashoffset={CIRCUMFERENCE * (1 - fraction)}
        />
      </svg>
      <span className="digits">{label}</span>
    </div>
  );
}
