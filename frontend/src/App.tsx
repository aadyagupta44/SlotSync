import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiFailure } from './api';
import { todayIso } from './format';
import { useEventStream } from './useEventStream';
import { EventFeed } from './components/EventFeed';
import { MetricsBar } from './components/MetricsBar';
import { NotificationInbox } from './components/NotificationInbox';
import { OfferInbox } from './components/OfferInbox';
import { PipelineFlow } from './components/PipelineFlow';
import { RacePanel } from './components/RacePanel';
import { ScheduleGrid } from './components/ScheduleGrid';
import { WaitlistPanel } from './components/WaitlistPanel';
import type {
  CustomerView,
  Metrics,
  NotificationView,
  OfferView,
  ResourceView,
  SlotView,
  WaitlistView,
} from './types';

export default function App() {
  const [resources, setResources] = useState<ResourceView[]>([]);
  const [customers, setCustomers] = useState<CustomerView[]>([]);
  const [resourceId, setResourceId] = useState('');
  const [date, setDate] = useState(todayIso());

  const [slots, setSlots] = useState<SlotView[]>([]);
  const [waitlist, setWaitlist] = useState<WaitlistView[]>([]);
  const [offers, setOffers] = useState<OfferView[]>([]);
  const [notifications, setNotifications] = useState<NotificationView[]>([]);
  const [metrics, setMetrics] = useState<Metrics | null>(null);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Which customer new bookings are made for.
  const [actingAs, setActingAs] = useState('');

  // Refs so the SSE callback always reads the current selection without having
  // to re-subscribe every time the user changes the dropdown.
  const selection = useRef({ resourceId, date });
  selection.current = { resourceId, date };

  const show = (message: string) => {
    setError(message);
    setTimeout(() => setError(null), 4000);
  };

  /** Reload everything the dashboard shows. */
  const refresh = useCallback(async () => {
    const current = selection.current;
    if (!current.resourceId) {
      return;
    }
    try {
      const [slotData, waitlistData, offerData, notificationData, metricData] = await Promise.all([
        api.availability(current.resourceId, current.date),
        api.waitlist(),
        api.offers(),
        api.notifications(),
        api.metrics(),
      ]);
      setSlots(slotData);
      setWaitlist(waitlistData.filter((entry) => entry.resourceId === current.resourceId));
      setOffers(offerData.filter((offer) => offer.resourceId === current.resourceId));
      setNotifications(notificationData);
      setMetrics(metricData);
    } catch (e) {
      show(e instanceof Error ? e.message : 'Could not load data');
    }
  }, []);

  // Any server-side event means something on screen is now stale. Rather than
  // patching individual bits of state from the payload, we refetch - simpler,
  // and the dataset here is tiny.
  const { events, connected } = useEventStream(() => {
    void refresh();
  });

  useEffect(() => {
    (async () => {
      try {
        const [resourceData, customerData] = await Promise.all([api.resources(), api.customers()]);
        setResources(resourceData);
        setCustomers(customerData);
        if (resourceData.length > 0) {
          setResourceId(resourceData[0].id);
        }
        if (customerData.length > 0) {
          setActingAs(customerData[0].id);
        }
      } catch (e) {
        show(e instanceof Error ? e.message : 'Backend unreachable');
      }
    })();
  }, []);

  useEffect(() => {
    void refresh();
  }, [resourceId, date, refresh]);

  /** Wraps a write so the UI cannot fire two at once and errors surface. */
  const act = async (operation: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await operation();
      await refresh();
    } catch (e) {
      if (e instanceof ApiFailure) {
        show(`${e.code}: ${e.message}`);
      } else {
        show(e instanceof Error ? e.message : 'Something went wrong');
      }
    } finally {
      setBusy(false);
    }
  };

  const selectedResource = resources.find((r) => r.id === resourceId);

  return (
    <div className="app">
      <header className="top">
        <div className="brand">
          <span className="mark" aria-hidden="true">
            <i />
            <i />
            <i />
          </span>
          <h1>SlotSync</h1>
        </div>
        <span className="tag">booking + auto-recovery waitlist engine</span>
        <div className={`live ${connected ? 'on' : ''}`}>
          <span className="dot" />
          {connected ? 'live' : 'disconnected'}
        </div>
      </header>

      <MetricsBar metrics={metrics} />

      <div className="columns">
        <div className="col">
          <section className="panel">
            <header>
              <h2>Schedule</h2>
              <span className="count">{slots.length} slots</span>
            </header>
            <p className="hint">
              Cancel a booked slot and watch it get offered to the waitlist automatically.
            </p>

            <div className="body">
              <div className="controls">
                <select value={resourceId} onChange={(e) => setResourceId(e.target.value)}>
                  {resources.map((resource) => (
                    <option key={resource.id} value={resource.id}>
                      {resource.name}
                    </option>
                  ))}
                </select>
                <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
                <select value={actingAs} onChange={(e) => setActingAs(e.target.value)}>
                  {customers.map((customer) => (
                    <option key={customer.id} value={customer.id}>
                      book as {customer.name}
                    </option>
                  ))}
                </select>
              </div>

              <ScheduleGrid
                slots={slots}
                customers={customers}
                busy={busy}
                onBook={(startsAt) => act(() => api.book(resourceId, actingAs, startsAt))}
                onCancel={(bookingId) => act(() => api.cancel(bookingId))}
              />
            </div>
          </section>

          <section className="panel">
            <header>
              <h2>Prove it</h2>
              <span className="count">exclusion constraint</span>
            </header>
            <p className="hint">
              Fire N simultaneous bookings at one open slot. Exactly one wins — decided by Postgres,
              not by application code.
            </p>
            <div className="body">
              <RacePanel
                resourceId={resourceId}
                slots={slots}
                customers={customers}
                onSettled={() => void refresh()}
              />
            </div>
          </section>

          <section className="panel">
            <header>
              <h2>Live events</h2>
              <span className="count">{events.length}</span>
            </header>
            <p className="hint">
              Streamed over SSE as each consumer handles them. This is the message pipeline, visible.
            </p>
            <div className="body">
              <PipelineFlow events={events} />
              <EventFeed events={events} />
            </div>
          </section>
        </div>

        <div className="col">
          <section className="panel">
            <header>
              <h2>Live offers</h2>
              <span className="count">{offers.filter((o) => o.status === 'PENDING').length} live</span>
            </header>
            <p className="hint">
              One freed slot is offered to exactly one person at a time. Let the timer run out and it
              cascades to the next.
            </p>
            <div className="body">
              <OfferInbox
                offers={offers}
                customers={customers}
                busy={busy}
                onClaim={(offerId) => act(() => api.claim(offerId))}
              />
            </div>
          </section>

          <section className="panel">
            <header>
              <h2>Waitlist</h2>
              <span className="count">
                {waitlist.filter((e) => e.status === 'WAITING' || e.status === 'OFFERED').length}{' '}
                waiting
              </span>
            </header>
            <p className="hint">
              Ordered by priority, then by who actually responds, then by join time.
            </p>
            <div className="body">
            <WaitlistPanel
              entries={waitlist}
              customers={customers}
              busy={busy}
              onJoin={(customerId, priority) =>
                act(() => {
                  // Default window: this resource's whole working day, in the
                  // browser's local time (which is what the user picked on the
                  // date input). It is sent to the server as UTC.
                  const start = new Date(`${date}T${selectedResource?.openingTime ?? '09:00:00'}`);
                  const end = new Date(`${date}T${selectedResource?.closingTime ?? '17:00:00'}`);
                  return api.joinWaitlist({
                    resourceId,
                    customerId,
                    windowStart: start.toISOString(),
                    windowEnd: end.toISOString(),
                    priority,
                  });
                })
              }
              onWithdraw={(entryId) => act(() => api.withdraw(entryId))}
            />
            </div>
          </section>

          <section className="panel">
            <header>
              <h2>Notifications</h2>
              <span className="count">{notifications.length}</span>
            </header>
            <p className="hint">Written by a consumer, not by the request that caused them.</p>
            <div className="body">
              <NotificationInbox notifications={notifications} customers={customers} />
            </div>
          </section>
        </div>
      </div>

      {error && <div className="toast">{error}</div>}
    </div>
  );
}
