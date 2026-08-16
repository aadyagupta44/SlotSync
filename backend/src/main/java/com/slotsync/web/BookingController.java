package com.slotsync.web;

import com.slotsync.booking.BookingService;
import com.slotsync.common.TenantContext;
import com.slotsync.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Booking endpoints.
 *
 * <p>Both writes are wrapped in {@link IdempotencyService}, so a client that
 * retries after a timeout gets the original answer back instead of creating a
 * second booking or firing a second cancellation.
 */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final IdempotencyService idempotency;

    public BookingController(BookingService bookingService, IdempotencyService idempotency) {
        this.bookingService = bookingService;
        this.idempotency = idempotency;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.BookingView book(
            @Valid @RequestBody Dtos.CreateBookingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        UUID tenantId = TenantContext.require();
        return idempotency.run(tenantId, "POST /bookings", idempotencyKey, request,
                Dtos.BookingView.class,
                () -> Dtos.BookingView.of(bookingService.book(
                        tenantId, request.resourceId(), request.customerId(), request.startsAt())));
    }

    /**
     * Cancelling is the interesting one: it is what triggers the whole waitlist
     * cascade, several services away, via the {@code slot.freed} event.
     */
    @PostMapping("/{bookingId}/cancel")
    public Dtos.BookingView cancel(
            @PathVariable UUID bookingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        UUID tenantId = TenantContext.require();
        return idempotency.run(tenantId, "POST /bookings/cancel", idempotencyKey, bookingId,
                Dtos.BookingView.class,
                () -> Dtos.BookingView.of(bookingService.cancel(tenantId, bookingId)));
    }

    @GetMapping
    public List<Dtos.BookingView> list(@RequestParam(required = false) UUID resourceId) {
        return bookingService.list(TenantContext.require(), resourceId)
                .stream().map(Dtos.BookingView::of).toList();
    }

    @GetMapping("/{bookingId}")
    public Dtos.BookingView get(@PathVariable UUID bookingId) {
        return Dtos.BookingView.of(bookingService.get(TenantContext.require(), bookingId));
    }
}
