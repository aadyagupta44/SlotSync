package com.slotsync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SlotSync - a multi-tenant appointment platform whose waitlist engine
 * automatically refills cancelled slots.
 *
 * <p>Scheduling is enabled because two background loops keep the system alive:
 * the outbox relay (database -> Kafka) and the offer-expiry sweeper
 * (expired holds -> cascade to the next person on the waitlist).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SlotSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(SlotSyncApplication.class, args);
    }
}
