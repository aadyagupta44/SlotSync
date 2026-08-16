package com.slotsync.booking;

import com.slotsync.AbstractIntegrationTest;
import com.slotsync.domain.Booking;
import com.slotsync.domain.BookingStatus;
import com.slotsync.domain.Customer;
import com.slotsync.domain.Resource;
import com.slotsync.repo.BookingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headline guarantee: <b>one slot, one booking, no matter what.</b>
 *
 * <p>This is the test to open first when someone asks "how do you know it
 * works?". It does not mock anything - it fires real concurrent requests at a
 * real Postgres and counts what survived.
 */
class BookingConcurrencyIT extends AbstractIntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepository;

    @Test
    @DisplayName("60 people racing for the same slot: exactly one wins, the rest get a clean conflict")
    void onlyOneBookingSurvivesTheRace() throws Exception {
        Resource resource = newResource("race");
        int contenders = 60;

        List<Customer> customers = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            customers.add(newCustomer("racer-" + i));
        }

        // Tomorrow 10:00 UTC - a valid 30-minute boundary for this resource.
        Instant slot = LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC);

        // A latch so every thread is already parked at the starting line and
        // they are released together. Without it the threads trickle in and the
        // "race" never actually happens.
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(contenders);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            for (Customer customer : customers) {
                pool.submit(() -> {
                    try {
                        startGun.await();
                        bookingService.book(demoTenantId(), resource.getId(), customer.getId(), slot);
                        succeeded.incrementAndGet();
                    } catch (org.springframework.dao.DataIntegrityViolationException expected) {
                        // Postgres rejected the overlap. This is the system working.
                        rejected.incrementAndGet();
                    } catch (Throwable t) {
                        synchronized (unexpected) {
                            unexpected.add(t);
                        }
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startGun.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS))
                    .as("all threads finished").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected).as("no unexpected failures").isEmpty();
        assertThat(succeeded.get()).as("exactly one booking committed").isEqualTo(1);
        assertThat(rejected.get()).as("everyone else was rejected").isEqualTo(contenders - 1);

        List<Booking> stored = bookingRepository.findOverlapping(
                resource.getId(), slot, slot.plusSeconds(1800), List.of(BookingStatus.CONFIRMED));
        assertThat(stored).as("exactly one row in the database").hasSize(1);
    }

    @Test
    @DisplayName("Back-to-back slots do not count as overlapping")
    void adjacentSlotsAreBothBookable() {
        Resource resource = newResource("adjacent");
        Customer first = newCustomer("first");
        Customer second = newCustomer("second");

        Instant ten = LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC);

        bookingService.book(demoTenantId(), resource.getId(), first.getId(), ten);
        bookingService.book(demoTenantId(), resource.getId(), second.getId(), ten.plusSeconds(1800));

        // 10:00-10:30 and 10:30-11:00 share an instant at the boundary. The
        // '[)' half-open range in the generated `during` column is what stops
        // that from being treated as an overlap.
        assertThat(bookingRepository.findOverlapping(resource.getId(), ten, ten.plusSeconds(3600),
                List.of(BookingStatus.CONFIRMED))).hasSize(2);
    }
}
