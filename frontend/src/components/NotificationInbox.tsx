import { dateTime } from '../format';
import type { CustomerView, NotificationView } from '../types';

/**
 * The pretend email inbox.
 *
 * <p>These rows are written by a Kafka consumer, not by the API that handled
 * the click. That separation is deliberate: adding SMS or push later means
 * adding another consumer, with no change to the booking code.
 */
export function NotificationInbox({
  notifications,
  customers,
}: {
  notifications: NotificationView[];
  customers: CustomerView[];
}) {
  const nameOf = (id: string) => customers.find((c) => c.id === id)?.name ?? 'unknown';

  if (notifications.length === 0) {
    return (
      <p className="empty">
        <strong>No messages yet</strong>
        A consumer writes these — not the request that caused them.
      </p>
    );
  }

  return (
    <div>
      {notifications.map((notification) => (
        <div className="inbox-item" key={notification.id}>
          <div className="subject">{notification.subject}</div>
          <div className="body">{notification.body}</div>
          <div className="to">
            → {nameOf(notification.customerId)} · {dateTime(notification.createdAt)}
          </div>
        </div>
      ))}
    </div>
  );
}
