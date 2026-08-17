package com.slotsync.web;

import com.slotsync.AbstractIntegrationTest;
import com.slotsync.common.GlobalExceptionHandler;
import com.slotsync.domain.Customer;
import com.slotsync.domain.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP error contract for a lost booking race.
 *
 * <p><b>Why this class exists.</b> {@code BookingConcurrencyIT} proves the
 * database guarantee - exactly one booking survives - but it asserts at the
 * *service* layer, counting exceptions thrown out of {@code BookingService}.
 * Nothing exercised the translation from exception to HTTP status, and a real
 * bug lived in that gap: under genuine concurrency Postgres aborts the losers
 * with a deadlock (SQLState 40P01) rather than an exclusion-constraint
 * violation (23P01), and only the second was handled. The guarantee held
 * perfectly while 39 of 40 callers received a 500.
 *
 * <p>The lesson generalises: a test that stops one layer below the one users
 * touch can be green while the user-visible behaviour is wrong.
 *
 * <p>Both SQLStates mean the same thing to a caller - somebody else got the
 * slot - so both must produce {@code 409 SLOT_TAKEN}.
 */
@AutoConfigureMockMvc
class BookingErrorContractIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired GlobalExceptionHandler exceptionHandler;

    private Resource resource;
    private Customer customer;
    private Instant slot;

    @BeforeEach
    void setUp() {
        resource = newResource("error-contract");
        customer = newCustomer("Racer");
        // Tomorrow at 10:00 UTC: a real slot boundary, clear of the seeded data.
        slot = Instant.now().plus(1, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS);
    }

    private String body(UUID resourceId, UUID customerId, Instant startsAt) {
        return """
                {"resourceId":"%s","customerId":"%s","startsAt":"%s"}
                """.formatted(resourceId, customerId, startsAt);
    }

    /**
     * The ordinary, low-contention path: the second booking loses to the
     * exclusion constraint and must come back as a clean 409, not a 500.
     */
    @Test
    void secondBookingForTheSameSlotIsRejectedWith409() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("X-Tenant-Slug", "demo-clinic")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(resource.getId(), customer.getId(), slot)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/bookings")
                        .header("X-Tenant-Slug", "demo-clinic")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(resource.getId(), customer.getId(), slot)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
    }

    /**
     * The regression guard for the bug itself.
     *
     * <p>A deadlock is timing-dependent, so this cannot *demand* that one
     * occurs. What it can demand - and what actually failed before the fix - is
     * that however Postgres chooses to reject the losers, no caller ever sees a
     * 5xx. Exactly one 201, everything else 409, nothing else at all.
     */
    @Test
    void aConcurrentStampedeNeverProducesA5xx() throws Exception {
        int racers = 24;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch startingGun = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(racers);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        List<Integer> unexpected = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < racers; i++) {
            pool.submit(() -> {
                try {
                    // Park every thread here so they are released together; a
                    // trickle of requests is not a race and would pass even
                    // against broken code.
                    startingGun.await();
                    int statusCode = mockMvc.perform(post("/api/v1/bookings")
                                    .header("X-Tenant-Slug", "demo-clinic")
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body(resource.getId(), customer.getId(), slot)))
                            .andReturn().getResponse().getStatus();

                    if (statusCode == HttpStatus.CREATED.value()) {
                        created.incrementAndGet();
                    } else if (statusCode == HttpStatus.CONFLICT.value()) {
                        conflicted.incrementAndGet();
                    } else {
                        unexpected.add(statusCode);
                    }
                } catch (Exception e) {
                    unexpected.add(-1);
                } finally {
                    finished.countDown();
                }
            });
        }

        startingGun.countDown();
        assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(unexpected)
                .as("no caller may receive a 5xx - a lost race is a 409, not a server error")
                .isEmpty();
        assertThat(created.get()).as("exactly one booking wins").isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(racers - 1);
    }

    /**
     * Deadlock cannot be summoned on demand, so the mapping that fixes it is
     * asserted directly. Without this, the fix would be covered only by a test
     * that has to get lucky to exercise it.
     */
    @Test
    void deadlockIsMappedToSlotTakenRatherThanA500() {
        var response = exceptionHandler.handleLockConflict(
                new CannotAcquireLockException("ERROR: deadlock detected"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("SLOT_TAKEN");
    }
}
