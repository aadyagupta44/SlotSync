package com.slotsync.notify;

import com.slotsync.domain.Notification;
import com.slotsync.events.Payloads;
import com.slotsync.repo.NotificationRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Stands in for email/SMS by writing rows the UI renders as an inbox.
 *
 * <p>Notifications are produced by the <b>consumer</b>, not by the code that
 * cancels or claims. That separation is the point of the event pipeline: adding
 * WhatsApp, a push notification or an analytics sink later means adding another
 * consumer, with no change to {@code BookingService} or {@code OfferService}.
 */
@Service
public class NotificationService {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Kolkata"));
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("EEE d MMM").withZone(ZoneId.of("Asia/Kolkata"));

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public void notifyOfferCreated(Payloads.OfferChanged offer) {
        if (offer.customerId() == null) {
            return;
        }
        long minutes = Duration.between(Instant.now(), offer.expiresAt()).toMinutes() + 1;
        save(offer.tenantId(), offer.customerId(), offer.offerId(),
                "A slot just opened up - " + DAY.format(offer.startsAt())
                        + " at " + TIME.format(offer.startsAt()),
                "Someone cancelled. You are first in line for "
                        + TIME.format(offer.startsAt()) + "-" + TIME.format(offer.endsAt())
                        + ". Claim it within " + minutes + " minute(s) or it goes to the next person.");
    }

    @Transactional
    public void notifyOfferExpired(Payloads.OfferChanged offer) {
        if (offer.customerId() == null) {
            return;
        }
        save(offer.tenantId(), offer.customerId(), offer.offerId(),
                "Your slot offer expired",
                "The " + TIME.format(offer.startsAt()) + " slot has been passed to the next "
                        + "person on the waitlist. You are still in the queue.");
    }

    @Transactional
    public void notifyOfferClaimed(Payloads.OfferChanged offer) {
        if (offer.customerId() == null) {
            return;
        }
        save(offer.tenantId(), offer.customerId(), offer.offerId(),
                "Booking confirmed - " + DAY.format(offer.startsAt())
                        + " at " + TIME.format(offer.startsAt()),
                "You claimed the freed slot. See you at " + TIME.format(offer.startsAt()) + ".");
    }

    @Transactional(readOnly = true)
    public List<Notification> recent(UUID tenantId, int limit) {
        return notificationRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, Limit.of(limit));
    }

    private void save(UUID tenantId, UUID customerId, UUID offerId, String subject, String body) {
        Notification notification = new Notification();
        notification.setTenantId(tenantId);
        notification.setCustomerId(customerId);
        notification.setChannel("EMAIL");
        notification.setSubject(subject);
        notification.setBody(body);
        notification.setOfferId(offerId);
        notificationRepository.save(notification);
    }
}
