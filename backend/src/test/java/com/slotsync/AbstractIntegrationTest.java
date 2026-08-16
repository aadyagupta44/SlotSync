package com.slotsync;

import com.slotsync.domain.Customer;
import com.slotsync.domain.Resource;
import com.slotsync.repo.CustomerRepository;
import com.slotsync.repo.ResourceRepository;
import com.slotsync.repo.TenantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalTime;
import java.util.UUID;

/**
 * Base class for the integration tests.
 *
 * <p>Real Postgres and real Redis in Docker, started once and shared by every
 * test class (the "singleton container" pattern). An in-memory database would
 * be useless here: the whole system leans on Postgres-specific features -
 * {@code EXCLUDE USING gist}, {@code FOR UPDATE SKIP LOCKED}, generated range
 * columns - so testing against H2 would test something that does not exist.
 *
 * <p>Kafka is swapped for the in-memory transport. It keeps the same
 * asynchronous, separate-transaction semantics without a broker container, so
 * these tests stay fast enough to run on every push.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));

        // No broker in tests.
        registry.add("slotsync.events.transport", () -> "inmemory");
        // Short deadlines so a cascade completes in seconds, not minutes.
        registry.add("slotsync.offer.ttl-seconds", () -> "2");
        registry.add("slotsync.sweeper.interval-ms", () -> "300");
        registry.add("slotsync.outbox.interval-ms", () -> "150");
        // Rate limiting would reject the concurrency test's own load.
        registry.add("slotsync.ratelimit.enabled", () -> "false");
    }

    @Autowired protected TenantRepository tenantRepository;
    @Autowired protected ResourceRepository resourceRepository;
    @Autowired protected CustomerRepository customerRepository;

    /** The tenant created by the seed migration. */
    protected UUID demoTenantId() {
        return tenantRepository.findBySlug("demo-clinic").orElseThrow().getId();
    }

    /**
     * Every test makes its own resource so tests never compete for the same
     * slots. UTC keeps the arithmetic in the assertions obvious.
     */
    protected Resource newResource(String name) {
        Resource resource = new Resource();
        resource.setTenantId(demoTenantId());
        resource.setName(name + "-" + UUID.randomUUID());
        resource.setTimezone("UTC");
        resource.setOpeningTime(LocalTime.of(9, 0));
        resource.setClosingTime(LocalTime.of(17, 0));
        resource.setSlotMinutes(30);
        return resourceRepository.save(resource);
    }

    protected Customer newCustomer(String name) {
        Customer customer = new Customer();
        customer.setTenantId(demoTenantId());
        customer.setName(name);
        customer.setEmail(UUID.randomUUID() + "@example.com");
        return customerRepository.save(customer);
    }
}
