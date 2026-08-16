import { useEffect, useRef, useState } from 'react';
import { api } from './api';
import type { EventEnvelope } from './types';

const MAX_EVENTS = 60;

/**
 * Subscribes to the backend's Server-Sent Events stream.
 *
 * <p>Why SSE and not polling: the cascade is the whole point of the product and
 * it happens on the server's schedule, not the user's. Polling every few
 * seconds would either miss the moment or hammer the API. SSE pushes each event
 * the instant a consumer handles it.
 *
 * <p>Why SSE and not WebSockets: traffic here is strictly one-way. EventSource
 * also reconnects automatically when a connection drops, which would otherwise
 * be code we had to write and get right.
 *
 * @param onEvent called for every event, used to refresh affected data
 */
export function useEventStream(onEvent?: (event: EventEnvelope) => void) {
  const [events, setEvents] = useState<EventEnvelope[]>([]);
  const [connected, setConnected] = useState(false);

  // Kept in a ref so changing the callback does not tear down and rebuild the
  // connection on every render.
  const handlerRef = useRef(onEvent);
  handlerRef.current = onEvent;

  useEffect(() => {
    const source = new EventSource(api.streamUrl());

    source.addEventListener('connected', () => setConnected(true));

    source.onerror = () => setConnected(false);

    // Every domain event arrives with its own event name, so one generic
    // listener is not enough - we register the names we care about.
    const eventNames = [
      'booking.created',
      'booking.cancelled',
      'booking.confirmed',
      'slot.freed',
      'offer.created',
      'offer.expired',
      'offer.claimed',
      'waitlist.exhausted',
    ];

    const listener = (raw: MessageEvent) => {
      try {
        const envelope: EventEnvelope = JSON.parse(raw.data);
        setEvents((previous) => [envelope, ...previous].slice(0, MAX_EVENTS));
        handlerRef.current?.(envelope);
      } catch {
        // A malformed frame should never take the dashboard down.
      }
    };

    eventNames.forEach((name) => source.addEventListener(name, listener as EventListener));

    return () => {
      eventNames.forEach((name) => source.removeEventListener(name, listener as EventListener));
      source.close();
    };
  }, []);

  return { events, connected };
}
