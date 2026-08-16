import { useState } from 'react';
import { api, ApiFailure } from '../api';
import { time } from '../format';
import type { CustomerView, SlotView } from '../types';

interface Props {
  resourceId: string;
  slots: SlotView[];
  customers: CustomerView[];
  onSettled: () => void;
}

/** One racer's fate. */
type Cell = 'idle' | 'inflight' | 'won' | 'taken' | 'error';

const PRESETS = [20, 40, 80];

/**
 * The headline claim, made clickable.
 *
 * <p>Everything else in this dashboard demonstrates the *waitlist*. This panel
 * demonstrates the thing underneath it: that a Postgres exclusion constraint —
 * not application code — is what makes double-booking impossible. It fires N
 * simultaneous bookings at a single open slot and shows exactly one survive.
 *
 * <p>Honest caveat, and worth saying out loud rather than hiding: a browser
 * will not open 80 truly parallel connections to one origin, so these requests
 * overlap heavily but are not the brutal starting-gun release that
 * `BookingConcurrencyIT` (60 threads on a CountDownLatch) or the k6 load test
 * (500 virtual users) produce. Those are the real proof. This is the same
 * guarantee, made visible without leaving the page.
 */
export function RacePanel({ resourceId, slots, customers, onSettled }: Props) {
  const [count, setCount] = useState(40);
  const [cells, setCells] = useState<Cell[]>([]);
  const [running, setRunning] = useState(false);
  const [elapsed, setElapsed] = useState<number | null>(null);

  const open = slots.filter((s) => s.status === 'OPEN');
  const target = open[0];

  const won = cells.filter((c) => c === 'won').length;
  const taken = cells.filter((c) => c === 'taken').length;
  const errored = cells.filter((c) => c === 'error').length;
  const done = cells.length > 0 && !cells.some((c) => c === 'inflight');

  const race = async () => {
    if (!target || customers.length === 0) {
      return;
    }
    setRunning(true);
    setElapsed(null);
    setCells(Array.from({ length: count }, () => 'inflight' as Cell));

    const settle = (index: number, state: Cell) =>
      setCells((previous) => {
        const next = [...previous];
        next[index] = state;
        return next;
      });

    const startedAt = performance.now();

    // Build every request first, then let them fly together. Creating the
    // promises in one synchronous pass is what makes them overlap - awaiting
    // inside the loop would serialise them and prove nothing.
    const flights = Array.from({ length: count }, (_, i) =>
      api
        .book(resourceId, customers[i % customers.length].id, target.startsAt)
        .then(() => settle(i, 'won'))
        .catch((e) =>
          settle(i, e instanceof ApiFailure && e.code === 'SLOT_TAKEN' ? 'taken' : 'error'),
        ),
    );

    await Promise.allSettled(flights);
    setElapsed(Math.round(performance.now() - startedAt));
    setRunning(false);
    onSettled();
  };

  if (!target) {
    return (
      <p className="empty">
        <strong>No open slot to race for</strong>
        Cancel a booking first, then every racer will fight over it.
      </p>
    );
  }

  return (
    <>
      <div className="controls">
        <div className="seg">
          {PRESETS.map((n) => (
            <button
              key={n}
              className={count === n ? 'on' : ''}
              disabled={running}
              onClick={() => setCount(n)}
            >
              {n}
            </button>
          ))}
        </div>
        <button className="primary" disabled={running} onClick={race}>
          {running ? 'Racing…' : `Release ${count} racers`}
        </button>
        <span className="race-target">
          all fighting for {time(target.startsAt)}–{time(target.endsAt)}
        </span>
      </div>

      {cells.length > 0 && (
        <>
          <div className="grid-race" style={{ '--n': count } as React.CSSProperties}>
            {cells.map((cell, i) => (
              <span key={i} className={`racer ${cell}`} />
            ))}
          </div>

          <div className={`verdict ${done ? (won === 1 ? 'pass' : 'fail') : ''}`}>
            {!done && <span className="v-run">{count - won - taken - errored} in flight…</span>}
            {done && (
              <>
                <span className="v-headline">
                  {won === 1 ? 'Exactly one booking survived.' : `${won} bookings survived.`}
                </span>
                <span className="v-detail">
                  <b className="ok">{won}</b> confirmed ·{' '}
                  <b className="rej">{taken}</b> rejected&nbsp;409&nbsp;SLOT_TAKEN
                  {errored > 0 && (
                    <>
                      {' '}
                      · <b className="err">{errored}</b> other
                    </>
                  )}
                  {elapsed != null && <> · {elapsed}ms</>}
                </span>
              </>
            )}
          </div>
        </>
      )}

      {cells.length === 0 && (
        <p className="race-note">
          Every racer POSTs the same slot at the same moment. The database decides — no lock, no
          check-then-insert, just <code>EXCLUDE USING gist</code>.
        </p>
      )}
    </>
  );
}
