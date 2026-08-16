package com.slotsync.booking;

import com.slotsync.common.ApiException;
import com.slotsync.domain.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain unit test - no database, no Docker, runs in milliseconds.
 *
 * <p>Slot alignment is worth guarding carefully. A booking at 10:07 would
 * overlap both the 10:00 and 10:30 slots, so a single sloppy request could
 * block two slots and quietly wreck a day's schedule.
 */
class SlotBoundaryTest {

    private AvailabilityService service;
    private Resource resource;

    @BeforeEach
    void setUp() {
        // The methods under test never touch the repositories.
        service = new AvailabilityService(null, null);

        resource = new Resource();
        resource.setId(UUID.randomUUID());
        resource.setTimezone("Asia/Kolkata");
        resource.setOpeningTime(LocalTime.of(9, 0));
        resource.setClosingTime(LocalTime.of(17, 0));
        resource.setSlotMinutes(30);
    }

    private Instant istToday(int hour, int minute) {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"))
                .atTime(LocalTime.of(hour, minute))
                .atZone(ZoneId.of("Asia/Kolkata"))
                .toInstant();
    }

    @Test
    @DisplayName("A valid boundary returns the slot end")
    void validBoundary() {
        assertThat(service.slotEndFor(resource, istToday(10, 30)))
                .isEqualTo(istToday(11, 0));
    }

    @Test
    @DisplayName("Opening time itself is a valid slot")
    void openingTimeIsValid() {
        assertThat(service.slotEndFor(resource, istToday(9, 0)))
                .isEqualTo(istToday(9, 30));
    }

    @Test
    @DisplayName("The last slot ends exactly at closing time")
    void lastSlotOfTheDay() {
        assertThat(service.slotEndFor(resource, istToday(16, 30)))
                .isEqualTo(istToday(17, 0));
    }

    @Test
    @DisplayName("Off-grid start times are rejected")
    void offGridIsRejected() {
        assertThatThrownBy(() -> service.slotEndFor(resource, istToday(10, 7)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("30-minute boundary");
    }

    @Test
    @DisplayName("Times outside opening hours are rejected")
    void outsideOpeningHoursIsRejected() {
        assertThatThrownBy(() -> service.slotEndFor(resource, istToday(8, 0)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("outside");

        assertThatThrownBy(() -> service.slotEndFor(resource, istToday(17, 0)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("outside");
    }
}
