# SlotSync — Setup Guide

Everything you need to get this running on your machine. Should take about 15 minutes, most of it
waiting for downloads.

> If you want to understand *how the project works* rather than how to run it, read **`HANDOFF.md`**
> instead — that is the deep explanation. This file is purely mechanical.

---

## 1. What you need installed

**For the Docker route (recommended — this is the only thing you need):**

| Software | Where | Notes |
|---|---|---|
| Docker Desktop | https://www.docker.com/products/docker-desktop/ | Windows/Mac. On Windows it will install WSL2 for you if needed. |

That is genuinely it. Docker brings its own Java, Node, PostgreSQL, Redis and Kafka.

**Only if you also want to run the tests or edit the code:**

| Software | Version | Where |
|---|---|---|
| JDK | 21 | https://adoptium.net/ (Temurin 21) |
| Node.js | 20 or newer | https://nodejs.org/ |
| Maven | 3.9+ | https://maven.apache.org/download.cgi (or use your IDE's bundled one) |

---

## 2. Add the credentials

This is the only configuration step. The project ships a template with every value blank or set to a
placeholder.

**Step 1** — in the project root (the folder with `docker-compose.yml`), copy the template:

Windows (PowerShell):
```powershell
Copy-Item .env.example .env
```

Mac/Linux:
```bash
cp .env.example .env
```

**Step 2** — open `.env` in any text editor and replace the three `CHANGE_ME_local_password` values.

They are all the *same* local database password, so put the identical string in all three:

```ini
POSTGRES_PASSWORD=pick_any_password_here
SPRING_DATASOURCE_PASSWORD=pick_any_password_here
```

> This is a throwaway password for a database that only exists inside Docker on your laptop. It does
> not need to be strong, and it never leaves your machine. Just make sure the two values match — if
> they don't, the backend cannot log in to its own database.

**Everything else in `.env` already has a working default.** Do not touch the Kafka, Redis or
timing settings unless you want to experiment.

**`.env` is git-ignored**, so it will never be committed if you push this to GitHub.

### Optional: make the demo faster

The default claim deadline is 5 minutes, which is realistic but slow to demo. To watch a cascade in
seconds, set this in `.env`:

```ini
SLOTSYNC_OFFER_TTL_SECONDS=20
```

---

## 3. Run it

From the project root:

```bash
docker compose up --build
```

The first run downloads images and compiles everything — expect **5–10 minutes**. Later runs take
about 20 seconds.

Wait until you see a line like:

```
backend-1  | ... Started SlotSyncApplication in 8.4 seconds
```

Then open:

| What | URL |
|---|---|
| **Dashboard** | http://localhost:5173 |
| API | http://localhost:8080/api/v1/resources |
| Health check | http://localhost:8080/actuator/health |

The database is created and seeded automatically on first start — one demo clinic, two
practitioners, six customers, three appointments today and three people already on the waitlist.

**To stop:** press `Ctrl+C`, then:

```bash
docker compose down
```

**To wipe the database and start fresh** (this deletes all data and re-seeds):

```bash
docker compose down -v
```

---

## 4. Check it actually works — the 60-second demo

1. Open http://localhost:5173.
2. The resource dropdown should show **Dr. Mehta - Physio Room A**. You should see a grid of time
   slots, three of them green (booked).
3. Click **Cancel** on the 10:00 slot.
4. Watch the **Live events** panel on the left. Within a second or two, three events appear:
   `booking.cancelled` → `slot.freed` → `offer.created`.
5. The 10:00 slot turns **amber** (held), and a **live offer with a countdown** appears in the
   *Live offers* panel — for Arjun, the priority customer.
6. Now either:
   - click **Claim** — the slot turns green and the metric tiles update; or
   - **do nothing** and let the timer run out — the offer expires and a new one appears
     automatically for the next person, marked *attempt #2*.

If you saw that, everything is working.

---

## 5. Run the tests

Docker must be running (the integration tests start their own PostgreSQL and Redis containers).

```bash
cd backend
mvn verify
```

First run takes 3–5 minutes (image downloads), then about a minute. Expect **12 tests, 0 failures**:

| Class | Tests | What it checks |
|---|---|---|
| `SlotBoundaryTest` | 5 | slot-alignment rules — **no Docker needed** |
| `BookingConcurrencyIT` | 2 | 60 threads racing for one slot → exactly 1 wins |
| `WaitlistCascadeIT` | 2 | cancel → offer → expiry → cascade → claim |
| `IdempotencyIT` | 3 | a retried POST does not create a second booking |

Run one class only:

```bash
cd backend && mvn test -Dtest=BookingConcurrencyIT
```

Run just the fast tests with no Docker at all:

```bash
cd backend && mvn test -Dtest=SlotBoundaryTest
```

### The load test (optional, but it makes a great screenshot)

With the app running (`docker compose up`), in a **second** terminal:

```bash
docker run --rm -i --network host grafana/k6 run - < loadtest/booking-race.js
```

500 virtual users all try to book the same slot simultaneously. Look for this line at the bottom:

```
✓ slot_won.....................: count==1
```

That green tick is the whole project's headline claim, proven.

---

## 6. Working on the code

You do not need to rebuild the Docker images while developing. Run the infrastructure in Docker and
the apps natively:

**Terminal 1 — infrastructure only:**
```bash
docker compose up postgres redis redpanda
```

**Terminal 2 — backend (hot reload on restart):**
```bash
cd backend && mvn spring-boot:run
```

**Terminal 3 — frontend (instant hot reload):**
```bash
cd frontend && npm install && npm run dev
```

Frontend on http://localhost:5173, backend on http://localhost:8080. Vite proxies `/api` to the
backend automatically, so nothing else needs configuring.

---

## 7. Troubleshooting

### `docker compose` says "Cannot connect to the Docker daemon"
Docker Desktop is not running, or has not finished starting. Open it from the Start menu / Launchpad
and wait for the bottom-left of its window to say **"Engine running"** in green. On a first launch it
may ask you to accept a service agreement — you can skip the sign-in prompt.

### Docker Desktop opens but the engine never starts (Windows)
Usually WSL2 needs updating. Open PowerShell **as Administrator** and run:
```powershell
wsl --update
```
then restart Docker Desktop. If it still hangs, restart the machine — this fixes it most of the time.

### `port is already allocated` / `bind: address already in use`
Something else on your machine is using 5432 (PostgreSQL), 6379 (Redis), 8080 or 5173. Either stop
that program, or edit `docker-compose.yml` and change the **left** number of the mapping, e.g.
`"5433:5432"`. Only the left side matters — it is the port on your machine.

### `mvn` fails with "JAVA_HOME is not defined correctly"
Your `JAVA_HOME` points at a JDK that is not there. Point it at your JDK 21 install:

Windows:
```powershell
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-21"
```
(then **open a new terminal**), Mac/Linux:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```
Check with `mvn -v` — it should report Java 21.

### Tests fail with "Could not find a valid Docker environment"
Docker Desktop is installed but the engine is not up. Same fix as above.

### Backend fails with "Unable to establish loopback connection"
Rare, and specific to some Windows machines: a firewall or antivirus is blocking Java from creating
a local socket pair. Try temporarily disabling third-party antivirus, or run the backend inside
Docker (`docker compose up`) instead of natively — the containerised version is unaffected.

### The dashboard loads but everything is empty
The backend is not reachable. Check http://localhost:8080/actuator/health returns
`{"status":"UP"}`. If not, look at the `backend-1` logs in your `docker compose up` terminal.

### The live event feed shows "disconnected"
Only affects live updates — the app still works, you just have to refresh manually. It means the
browser's event stream could not connect; check the backend is healthy and reload the page.

---

## 8. Later: deploying it

Nothing here is needed to run locally. This is for when you want a public URL.

The project is built for free tiers and everything is an environment variable — no code changes.

| Piece | Service | What you copy into the environment |
|---|---|---|
| PostgreSQL | [Neon](https://neon.tech) | `SPRING_DATASOURCE_URL` = `jdbc:postgresql://<host>.neon.tech/<db>?sslmode=require`, plus username and password |
| Redis | [Upstash](https://upstash.com) | `SPRING_DATA_REDIS_URL` = `rediss://default:<password>@<host>.upstash.io:6379` |
| Kafka | [Upstash Kafka](https://upstash.com) or Confluent Cloud | `SPRING_KAFKA_BOOTSTRAP_SERVERS`, plus `KAFKA_SECURITY_PROTOCOL=SASL_SSL`, `KAFKA_SASL_MECHANISM=SCRAM-SHA-256`, `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` |
| Backend | [Railway](https://railway.app) or [Fly.io](https://fly.io) | point it at `backend/Dockerfile`, paste the variables above |
| Frontend | [Vercel](https://vercel.com) | root directory `frontend`, and set `VITE_API_BASE_URL` to the backend's public URL |

Flyway creates the schema and seeds the demo data automatically on first boot, so there is no
database setup step.

**Never commit real credentials.** `.env` is already git-ignored; on the hosting platforms put the
values in their environment-variable settings page, not in a file.

---

## 9. What is in the zip

```
slotsync/
├── SETUP.md          ← you are here
├── HANDOFF.md        ← read this to understand the project (private, git-ignored)
├── README.md         ← the public-facing description
├── .env.example      ← copy to .env and fill in
├── docker-compose.yml
├── backend/          ← Java 21 / Spring Boot
├── frontend/         ← React + TypeScript
├── loadtest/         ← k6 concurrency proof
└── .github/          ← CI pipeline
```

If you push this to GitHub, `HANDOFF.md` and `.env` are both in `.gitignore` and will not be
uploaded. That is intentional.
