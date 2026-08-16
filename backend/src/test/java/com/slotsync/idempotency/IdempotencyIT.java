package com.slotsync.idempotency;

import com.slotsync.AbstractIntegrationTest;
import com.slotsync.booking.BookingService;
import com.slotsync.domain.BookingStatus;
import com.slotsync.domain.Customer;
import com.slotsync.domain.Resource;
import com.slotsync.repo.BookingRepository;
import com.slotsync.web.Dtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A retried POST must not create a second booking.
 *
 * <p>This is the "user tapped Book twice on a flaky connection" scenario, and
 * it is the difference between a demo and something you could actually run.
 */
class IdempotencyIT extends AbstractIntegrationTest {

    @Autowired IdempotencyService idempotency;
    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepository;

    @Test
    @DisplayName("Same Idempotency-Key twice: one booking, and the stored response is replayed")
    void repeatedRequestReplaysTheFirstResponse() {
        UUID tenantId = demoTenantId();
        Resource resource = newResource("idem");
        Customer customer = newCustomer("retrier");
        Instant slot = LocalDate.now(ZoneOffset.UTC).plusDays(2)
                .atTime(LocalTime.of(9, 30)).toInstant(ZoneOffset.UTC);

        Dtos.CreateBookingRequest request =
                new Dtos.CreateBookingRequest(resource.getId(), customer.getId(), slot);
        String key = UUID.randomUUID().toString();

        Dtos.BookingView first = idempotency.run(tenantId, "POST /bookings", key, request,
                Dtos.BookingView.class,
                () -> Dtos.BookingView.of(bookingService.book(
                        tenantId, request.resourceId(), request.customerId(), request.startsAt())));

        Dtos.BookingView replay = idempotency.run(tenantId, "POST /bookings", key, request,
                Dtos.BookingView.class,
                () -> Dtos.BookingView.of(bookingService.book(
                        tenantId, request.resourceId(), request.customerId(), request.startsAt())));

        assertThat(replay.id()).as("same booking returned").isEqualTo(first.id());
        assertThat(bookingRepository.findOverlapping(resource.getId(), slot, slot.plusSeconds(1800),
                List.of(BookingStatus.CONFIRMED))).hasSize(1);
    }

    @Test
    @DisplayName("Reusing a key with a different body is rejected instead of silently replaying")
    void keyReuseWithDifferentBodyIsAnError() {
        UUID tenantId = demoTenantId();
        Resource resource = newResource("idem-reuse");
        Customer customer = newCustomer("confused-client");
        Instant slot = LocalDate.now(ZoneOffset.UTC).plusDays(2)
                .atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC);

        String key = UUID.randomUUID().toString();
        Dtos.CreateBookingRequest original =
                new Dtos.CreateBookingRequest(resource.getId(), customer.getId(), slot);
        Dtos.CreateBookingRequest different =
                new Dtos.CreateBookingRequest(resource.getId(), customer.getId(), slot.plusSeconds(1800));

        idempotency.run(tenantId, "POST /bookings", key, original, Dtos.BookingView.class,
                () -> Dtos.BookingView.of(bookingService.book(
                        tenantId, original.resourceId(), original.customerId(), original.startsAt())));

        assertThatThrownBy(() ->
                idempotency.run(tenantId, "POST /bookings", key, different, Dtos.BookingView.class,
                        () -> Dtos.BookingView.of(bookingService.book(
                                tenantId, different.resourceId(), different.customerId(), different.startsAt()))))
                .hasMessageContaining("already used with a different request body");
    }

    @Test
    @DisplayName("A failed request releases its key so an honest retry can still succeed")
    void failureDoesNotWedgeTheKey() {
        UUID tenantId = demoTenantId();
        Resource resource = newResource("idem-fail");
        Customer customer = newCustomer("unlucky");
        // 10:07 is not a 30-minute boundary, so the first call is rejected.
        Instant badSlot = LocalDate.now(ZoneOffset.UTC).plusDays(2)
                .atTime(LocalTime.of(10, 7)).toInstant(ZoneOffset.UTC);
        Instant goodSlot = LocalDate.now(ZoneOffset.UTC).plusDays(2)
                .atTime(LocalTime.of(10, 30)).toInstant(ZoneOffset.UTC);

        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> idempotency.run(tenantId, "POST /bookings", key,
                new Dtos.CreateBookingRequest(resource.getId(), customer.getId(), badSlot),
                Dtos.BookingView.class,
                () -> Dtos.BookingView.of(bookingService.book(
                        tenantId, resource.getId(), customer.getId(), badSlot))))
                .hasMessageContaining("30-minute boundary");

        Dtos.BookingView retry = idempotency.run(tenantId, "POST /bookings", key,
                new Dtos.CreateBookingRequest(resource.getId(), customer.getId(), goodSlot),
                Dtos.BookingView.class,
                () -> Dtos.BookingView.of(bookingService.book(
                        tenantId, resource.getId(), customer.getId(), goodSlot)));

        assertThat(retry.id()).isNotNull();
    }
}
