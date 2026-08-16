# SlotSync

**A multi-tenant appointment platform whose waitlist engine refills cancelled slots by itself.**

When somebody cancels an appointment, the freed slot is offered to **exactly one** waitlisted
customer at a time, with a short deadline to claim it. If they do not answer, the offer expires and
the slot **cascades** to the next person automatically — no staff phone calls, no lost revenue.

```
 cancel ──▶ slot.freed ──▶ offer to #1 ──┬── claimed ──▶ booking confirmed ✅
                ▲                        │
                └──── offer expired ─────┘   (cascades to #2, #3, …)
```

---

## Why it is interesting

The product is simple to describe. The hard part is that it is a **concurrency and timing problem**:

| Problem | How SlotSync solves it |
|---|---|
| Two people booking the same slot at the same millisecond | Postgres `EXCLUDE USING gist` over a time range — an overlapping booking is **impossible to store** |
| One freed slot must go to exactly one person | The offer creates a `HELD` booking, which the same exclusion constraint protects |
| Deadlines must survive restarts and run on many replicas | Deadlines live in a database column, swept with `SELECT … FOR UPDATE SKIP LOCKED` |
| "DB says cancelled but the message was lost" | Transactional outbox — business row and event commit together |
| Kafka delivers a message twice | Consumer de-duplication table, written in the same transaction as the work |
| The user double-taps "Book" | `Idempotency-Key` header; the stored response is replayed |
| One tenant floods the API | Redis token bucket (atomic Lua), shared across all replicas |

---

## Stack

| Layer | Choice |
|---|---|
| API | Java 21, Spring Boot 3.3 |
| Database | PostgreSQL 16 (Flyway migrations) |
| Cache / coordination | Redis 7 |
| Messaging | Kafka (Redpanda locally) |
| Frontend | React 18 + TypeScript (Vite), live updates over SSE |
| Tests | JUnit 5, Testcontainers (real Postgres + Redis), k6 |
| Delivery | Docker, docker-compose, GitHub Actions |

---

## Run it

```bash
cp .env.example .env      # then fill in the passwords
docker compose up --build
```

| What | Where |
|---|---|
| Dashboard | http://localhost:5173 |
| API | http://localhost:8080/api/v1 |
| Health | http://localhost:8080/actuator/health |

The database is seeded with a demo clinic, two practitioners, six customers, three appointments and
three people already on the waitlist — so there is something to cancel the moment it boots.

### Running the parts separately

```bash
docker compose up postgres redis redpanda
```

```bash
cd backend && mvn spring-boot:run
```

```bash
cd frontend && npm install && npm run dev
```

---

## See the engine work

1. Open the dashboard. Pick **Dr. Mehta – Physio Room A**.
2. **Cancel** the 10:00 booking.
3. Watch the *Live events* panel: `booking.cancelled` → `slot.freed` → `offer.created`.
4. The slot turns amber (**HELD**) and a live offer appears with a countdown — for the *priority*
   customer, even though somebody else joined the waitlist earlier.
5. Either **claim** it, or do nothing and let the timer run out. On expiry the offer cascades to the
   next person by itself, and the metric tiles at the top update.

---

## Tests

```bash
cd backend && mvn verify
```

Docker must be running — the integration tests start real Postgres and Redis containers.

| Test | What it proves |
|---|---|
| `BookingConcurrencyIT` | 60 threads racing for one slot → exactly **1** booking, 59 clean conflicts |
| `WaitlistCascadeIT` | cancel → offer → expiry → cascade → claim, end to end, driven only by events |
| `IdempotencyIT` | a retried POST creates one booking; a reused key with a different body is rejected |
| `SlotBoundaryTest` | off-grid times (10:07) are refused before they can corrupt a day's schedule |

### Load test

```bash
docker run --rm -i --network host grafana/k6 run - < loadtest/booking-race.js
```

500 virtual users, one slot, all at once. The threshold `slot_won: count==1` fails the run if a
single extra booking gets through.

---

## API

All requests carry `X-Tenant-Slug: demo-clinic`. Writes accept an optional `Idempotency-Key`.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/resources` | bookable resources |
| `GET` | `/api/v1/resources/{id}/availability?date=YYYY-MM-DD` | the day's slot grid |
| `POST` | `/api/v1/bookings` | book an open slot |
| `POST` | `/api/v1/bookings/{id}/cancel` | cancel — **this starts the engine** |
| `POST` | `/api/v1/waitlist` | join the queue for a time window |
| `GET` | `/api/v1/offers/pending` | live offers |
| `POST` | `/api/v1/offers/{id}/claim` | accept an offer before it expires |
| `GET` | `/api/v1/metrics` | refill rate, median refill time, pipeline health |
| `GET` | `/api/v1/stream?tenant=…` | Server-Sent Events feed |

Errors are uniform: `{ "code": "SLOT_TAKEN", "message": "...", "timestamp": "..." }`.

---

## Layout

```
backend/
  src/main/java/com/slotsync/
    booking/      direct bookings, cancellation, availability
    waitlist/     the engine: offers, claims, expiry cascade
    events/       outbox, transports, dispatcher, consumer de-duplication
    idempotency/  retry-safe POSTs
    ratelimit/    Redis token bucket
    lock/         Redis leader lock (an optimisation, not a correctness device)
    stream/       SSE hub + Redis pub/sub fan-out
    domain/ repo/ web/ config/ common/
  src/main/resources/db/migration/   Flyway: schema, then demo data
frontend/src/     React dashboard
loadtest/         k6 concurrency proof
```

---

## Deploying

Built for a free-tier split: **Neon** (Postgres) · **Upstash** (Redis + Kafka) · **Railway** or
**Fly.io** (backend) · **Vercel** (frontend). Everything is configured by environment variable —
see `.env.example`. The backend is stateless, so scaling it is changing the replica count; all
coordination already lives in Postgres and Redis.

---

## Deliberately out of scope

Authentication (the tenant comes from a header, not a JWT), payments, recurring appointments, and
real email/SMS — notifications are written to a table the dashboard renders as an inbox. These were
left out to keep the focus on the concurrency and messaging problems, which is where the interesting
work is.
