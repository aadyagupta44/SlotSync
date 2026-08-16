import { useState } from 'react';
import { time } from '../format';
import type { CustomerView, WaitlistView } from '../types';

interface Props {
  entries: WaitlistView[];
  customers: CustomerView[];
  busy: boolean;
  onJoin: (customerId: string, priority: number) => void;
  onWithdraw: (entryId: string) => void;
}

/**
 * The queue.
 *
 * <p>Order shown here is the order the server will use:
 * priority first, then whoever has missed fewer offers, then join time.
 * "Missed" counts against you - ignore two offers and you drop out entirely.
 *
 * <p>The position number on the left is the point of the panel: it shows a
 * priority customer sitting above someone who joined earlier, which is the
 * ordering rule made visible before it is explained.
 */
export function WaitlistPanel({ entries, customers, busy, onJoin, onWithdraw }: Props) {
  const [customerId, setCustomerId] = useState('');
  const [priority, setPriority] = useState(0);

  const nameOf = (id: string) => customers.find((c) => c.id === id)?.name ?? 'unknown';

  const active = entries.filter((e) => e.status === 'WAITING' || e.status === 'OFFERED');
  const done = entries.filter((e) => e.status !== 'WAITING' && e.status !== 'OFFERED').slice(0, 5);

  return (
    <>
      <div className="controls">
        <select value={customerId} onChange={(e) => setCustomerId(e.target.value)}>
          <option value="">Add someone to the waitlist…</option>
          {customers.map((customer) => (
            <option key={customer.id} value={customer.id}>
              {customer.name}
            </option>
          ))}
        </select>
        <select value={priority} onChange={(e) => setPriority(Number(e.target.value))}>
          <option value={0}>Normal</option>
          <option value={5}>Priority</option>
        </select>
        <button
          disabled={!customerId || busy}
          onClick={() => {
            onJoin(customerId, priority);
            setCustomerId('');
          }}
        >
          Join
        </button>
      </div>

      {active.length === 0 && (
        <p className="empty">
          <strong>Queue is empty</strong>
          Add someone above, then cancel a booking to see them get offered it.
        </p>
      )}

      {active.map((entry, index) => (
        <div className="row" key={entry.id}>
          <span className="queue-pos">{index + 1}</span>
          <div className="grow">
            <div className="who">
              {nameOf(entry.customerId)}
              {entry.priority > 0 && <span className="prio">PRIORITY</span>}
            </div>
            <div className="meta">
              {time(entry.windowStart)}–{time(entry.windowEnd)}
              {entry.missedOffers > 0 && ` · ${entry.missedOffers} missed`}
            </div>
          </div>
          <span className={`badge ${entry.status.toLowerCase()}`}>{entry.status}</span>
          <button className="ghost" disabled={busy} onClick={() => onWithdraw(entry.id)}>
            Remove
          </button>
        </div>
      ))}

      {done.length > 0 && (
        <>
          <p className="section-label">Finished</p>
          {done.map((entry) => (
            <div className="row" key={entry.id}>
              <div className="grow">
                <div className="who">{nameOf(entry.customerId)}</div>
              </div>
              <span className={`badge ${entry.status.toLowerCase()}`}>{entry.status}</span>
            </div>
          ))}
        </>
      )}
    </>
  );
}
