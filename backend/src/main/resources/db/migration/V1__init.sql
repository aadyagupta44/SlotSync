-- ===========================================================================
-- SlotSync - initial schema
--
-- The single most important line in this whole project is the EXCLUDE
-- constraint on `bookings`. It makes an overlapping booking *impossible to
-- store*, so correctness does not depend on application code being careful.
-- ===========================================================================

-- btree_gist lets a GiST index mix a plain-equality column (resource_id, a uuid)
-- with a range-overlap column (during). Without it the EXCLUDE below cannot
-- be created.
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ---------------------------------------------------------------------------
-- Tenants: one row per business using the platform (multi-tenant SaaS).
-- Every other table carries tenant_id so one query can never leak across
-- tenants.
-- ---------------------------------------------------------------------------
CREATE TABLE tenants (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        TEXT        NOT NULL UNIQUE,
    name        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Resources: the bookable thing. A doctor, a chair, a turf, a room.
-- Opening hours + slot length are all we need to generate availability, so
-- there is no giant pre-materialised "slots" table to keep in sync.
-- ---------------------------------------------------------------------------
CREATE TABLE resources (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name          TEXT        NOT NULL,
    timezone      TEXT        NOT NULL DEFAULT 'Asia/Kolkata',
    opening_time  TIME        NOT NULL DEFAULT '09:00',
    closing_time  TIME        NOT NULL DEFAULT '17:00',
    slot_minutes  INT         NOT NULL DEFAULT 30 CHECK (slot_minutes BETWEEN 5 AND 480),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_resources_tenant ON resources (tenant_id);

-- ---------------------------------------------------------------------------
-- Customers: the people who book. Kept deliberately thin - no auth here.
-- ---------------------------------------------------------------------------
CREATE TABLE customers (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        TEXT        NOT NULL,
    email       TEXT        NOT NULL,
    phone       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, email)
);

-- ---------------------------------------------------------------------------
-- Bookings.
--
-- A booking is a single row that moves through a small state machine:
--
--     HELD ---claim---> CONFIRMED ---cancel---> CANCELLED
--       |
--       +---expire---> EXPIRED
--
--   HELD       a temporary 5-minute reservation created when we offer a freed
--              slot to somebody on the waitlist. It occupies the slot so
--              nobody else can take it while that person decides.
--   CONFIRMED  a real booking.
--   CANCELLED  customer cancelled - this is what frees a slot.
--   EXPIRED    the hold ran out; the person did not claim in time.
--
-- `during` is a GENERATED column: Postgres derives it from starts_at/ends_at,
-- so Java never has to know about range types. '[)' means start inclusive,
-- end exclusive - 10:00-10:30 and 10:30-11:00 therefore do NOT overlap.
-- ---------------------------------------------------------------------------
CREATE TABLE bookings (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    resource_id  UUID        NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    customer_id  UUID        NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    starts_at    TIMESTAMPTZ NOT NULL,
    ends_at      TIMESTAMPTZ NOT NULL,
    during       TSTZRANGE   GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,
    status       TEXT        NOT NULL CHECK (status IN ('HELD','CONFIRMED','CANCELLED','EXPIRED')),
    origin       TEXT        NOT NULL CHECK (origin IN ('DIRECT','WAITLIST')),
    hold_expires_at TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    confirmed_at    TIMESTAMPTZ,
    version      BIGINT      NOT NULL DEFAULT 0,   -- JPA optimistic locking
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT bookings_time_order CHECK (ends_at > starts_at),

    -- ***********************************************************************
    -- THE constraint. Two rows for the same resource whose time ranges overlap
    -- cannot both exist while either is HELD or CONFIRMED. Postgres enforces
    -- this under full concurrency; the loser of a race gets an error, not a
    -- double booking. Cancelled/expired rows drop out of the predicate, which
    -- is exactly what "the slot is free again" means.
    -- ***********************************************************************
    CONSTRAINT bookings_no_overlap EXCLUDE USING gist (
        resource_id WITH =,
        during      WITH &&
    ) WHERE (status IN ('HELD','CONFIRMED'))
);

CREATE INDEX idx_bookings_resource_time ON bookings (resource_id, starts_at);
CREATE INDEX idx_bookings_tenant_status ON bookings (tenant_id, status);
CREATE INDEX idx_bookings_customer       ON bookings (customer_id);

-- ---------------------------------------------------------------------------
-- Waitlist entries: "if anything opens up on this resource between X and Y,
-- call me". `priority` lets a business bump VIPs; `missed_offers` demotes
-- people who keep ignoring offers.
-- ---------------------------------------------------------------------------
CREATE TABLE waitlist_entries (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    resource_id   UUID        NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    customer_id   UUID        NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    window_start  TIMESTAMPTZ NOT NULL,
    window_end    TIMESTAMPTZ NOT NULL,
    priority      INT         NOT NULL DEFAULT 0,
    missed_offers INT         NOT NULL DEFAULT 0,
    status        TEXT        NOT NULL DEFAULT 'WAITING'
                              CHECK (status IN ('WAITING','OFFERED','CONVERTED','WITHDRAWN','EXHAUSTED')),
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT waitlist_window_order CHECK (window_end > window_start)
);

-- The queue lookup: "waiting entries for this resource, best candidate first".
CREATE INDEX idx_waitlist_queue
    ON waitlist_entries (resource_id, priority DESC, missed_offers ASC, created_at ASC)
    WHERE status = 'WAITING';

-- ---------------------------------------------------------------------------
-- Offers: one attempt to give one freed slot to one waitlisted person.
--
--   PENDING ---claim---> CLAIMED
--      |
--      +---ttl elapsed---> EXPIRED   (and the slot is re-offered to the next person)
--
-- `freed_at` is carried all the way from the cancellation that created the
-- opening, so "how fast did we refill it" is a subtraction, not a guess.
-- ---------------------------------------------------------------------------
CREATE TABLE offers (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    resource_id       UUID        NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    waitlist_entry_id UUID        NOT NULL REFERENCES waitlist_entries(id) ON DELETE CASCADE,
    customer_id       UUID        NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    booking_id        UUID        NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    starts_at         TIMESTAMPTZ NOT NULL,
    ends_at           TIMESTAMPTZ NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'PENDING'
                                  CHECK (status IN ('PENDING','CLAIMED','EXPIRED','CANCELLED')),
    attempt           INT         NOT NULL DEFAULT 1,   -- 1st, 2nd, 3rd person tried
    freed_at          TIMESTAMPTZ NOT NULL,             -- when the slot became free
    expires_at        TIMESTAMPTZ NOT NULL,             -- claim deadline
    claimed_at        TIMESTAMPTZ,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The sweeper's hot path: "pending offers whose deadline has passed".
CREATE INDEX idx_offers_due ON offers (expires_at) WHERE status = 'PENDING';
CREATE INDEX idx_offers_tenant ON offers (tenant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Transactional outbox.
--
-- Business rows and the events describing them are written in ONE database
-- transaction. A background relay then pushes rows to Kafka. That removes the
-- classic dual-write bug: "DB committed but the message was lost", or
-- "message sent but the DB rolled back".
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id              BIGSERIAL   PRIMARY KEY,
    event_id        UUID        NOT NULL UNIQUE,
    tenant_id       UUID        NOT NULL,
    aggregate_type  TEXT        NOT NULL,      -- 'booking' | 'offer'
    aggregate_id    UUID        NOT NULL,
    partition_key   TEXT        NOT NULL,      -- resource id: keeps per-resource order
    event_type      TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_due ON outbox_events (next_attempt_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- Consumer-side de-duplication.
--
-- Kafka gives at-least-once delivery, so the same event CAN arrive twice.
-- A consumer inserts into this table inside the same transaction as its work;
-- the primary key makes the second delivery a no-op. That is how
-- "at-least-once delivery" becomes "exactly-once effect".
-- ---------------------------------------------------------------------------
CREATE TABLE processed_events (
    event_id     UUID        NOT NULL,
    consumer     TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

-- ---------------------------------------------------------------------------
-- Dead letters: events that failed every retry. Kept so nothing silently
-- disappears and an operator can replay them.
-- ---------------------------------------------------------------------------
CREATE TABLE dead_letter_events (
    id         BIGSERIAL   PRIMARY KEY,
    event_id   UUID,
    source     TEXT        NOT NULL,   -- 'outbox-relay' | 'event-consumer'
    event_type TEXT,
    payload    JSONB,
    error      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Idempotency keys.
--
-- The client sends `Idempotency-Key: <uuid>` on POSTs. A retry (double click,
-- flaky network, mobile app auto-retry) replays the stored response instead of
-- creating a second booking.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    id              BIGSERIAL   PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    idem_key        TEXT        NOT NULL,
    endpoint        TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,   -- same key + different body = client bug
    state           TEXT        NOT NULL DEFAULT 'IN_PROGRESS'
                                CHECK (state IN ('IN_PROGRESS','COMPLETED')),
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    UNIQUE (tenant_id, idem_key)
);

-- ---------------------------------------------------------------------------
-- Notifications: stand-in for email/SMS. Writing to a table instead of calling
-- a real provider keeps the demo self-contained and reviewable.
-- ---------------------------------------------------------------------------
CREATE TABLE notifications (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    customer_id UUID        NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    channel     TEXT        NOT NULL DEFAULT 'EMAIL',
    subject     TEXT        NOT NULL,
    body        TEXT        NOT NULL,
    offer_id    UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_tenant ON notifications (tenant_id, created_at DESC);
