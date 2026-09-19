# 09 — Shipping: demo data, a container image, and Render

**What Phase 9 built:** everything between "it works on my machine" and "here's the link".
- **A demo seeder** (`bootstrap/DemoDataSeeder`): an empty database is filled with 8 users,
  16 listings of all four types in 5 currencies, past stays with reviews, upcoming bookings and
  favourites, so the site never opens blank.
- **An honest health check:** Redis down now reports **DEGRADED** (HTTP 200), not DOWN — the
  open question from Phase 2.
- **A `Dockerfile`:** a two-stage build into a small, non-root, layered image, sized for a
  512 MB container.
- **A `render.yaml` Blueprint:** the web service, Postgres with pgvector, and a Key Value
  store, wired together in one file.
- **A `render` profile** for life behind Render's HTTPS proxy on a small machine.
- **The README** the spec asks for: architecture diagram, every environment variable, local and
  Render walkthroughs, trade-offs, and a CV paragraph.

---

## 1. A demo world, safely

📄 `bootstrap/DemoDataSeeder.java`, `resources/demo/demo-data.json`

A **seeder** fills a database with starting data. Recruiters open a portfolio link once; if it
shows "0 places found", that's the impression. So on start-up, an `ApplicationRunner` (a bean
Spring Boot calls once the app has started) creates a small world — but only under strict rules:

| Rule | How | Why |
|---|---|---|
| Only an empty database | does nothing if the `users` table has any row | it must never mix fake data into real data |
| All or nothing | one transaction around everything | a failure halfway would leave a half-world, and the next start would see "not empty" and never finish it |
| Never stops the app | failures are logged (`demo.failed`), not thrown | a missing demo is not a reason for the site to be down |
| The same rules as users | listings through `PropertyService` (factory checks, bean validation, cache events, AI index), reviews through `ReviewService`, favourites through `FavoriteService` | demo data that breaks a rule would show pages that can't happen |
| Recorded as such | `AuditActor.system("demo-seeder")` | the history says who made each row |
| Switchable | `DEMO_DATA_ENABLED=false` | tests, and the hands-on guide, start from empty |

**The data is a JSON file, not Java code.** Adding a listing or a review means editing
`demo-data.json`; the listings in it are the API's own `PropertyRequest` shape.

**Dates count from today.** A stay is written as `"startsInDays": -60, "nights": 3`. Fixed dates
would rot: a booking "next week" written in September would be in the past by November.

**The one exception: bookings.** The booking service rightly refuses dates in the past, and a
review needs a *finished* stay. Paying at start-up would also make starting depend on Stripe. So
the seeder records bookings directly with the entity's own methods (`Booking.reserve`,
`paymentStarted`, `paid`), marked as paid through the payment simulator. Cancelling one later
still refunds it: the simulator stays available for its own bookings even when Stripe is on.

**Two copies starting at once** (not possible on one instance, but worth thinking through): both
see an empty table, both insert — and the second one's `INSERT` of `asha.menon@example.com`
breaks the unique email constraint, so its whole transaction rolls back. The all-or-nothing rule
makes the race harmless.

---

## 2. Health checks that tell the truth

📄 `cache/SharedCacheHealthIndicator.java`, `application.yml` (`management.*`)

A **health check** is an address a platform polls to ask "are you OK?". Render's rules for
`/actuator/health` (render.com/docs/health-checks):
- a new deploy goes live once it answers 2xx or 3xx; if that doesn't happen within 15 minutes,
  the deploy is cancelled;
- each check must answer within **5 seconds**;
- a running instance failing for 15 seconds stops getting traffic, and after 60 seconds is
  restarted.

Spring Boot builds the answer from **health indicators**, one per dependency (`db`, `redis`,
`diskSpace`…), and one DOWN makes the whole answer DOWN (HTTP 503). That was wrong here: since
Phase 2 the app works without Redis (reads go to Postgres, slower). With Boot's own Redis check,
a Redis outage would make Render take a working site away.

The fix is a custom status:

```java
public static final Status DEGRADED = new Status("DEGRADED", "Redis is unreachable: every read goes to the database");
```

```yaml
management.endpoint.health.status.order: down,out-of-service,degraded,up,unknown
management.health.redis.enabled: false     # Boot's own check, replaced
```

- The *order* says how to combine the parts: DOWN beats DEGRADED beats UP.
- Only DOWN and OUT_OF_SERVICE map to 503; DEGRADED answers 200.

Measured on the running container:

| Situation | `/actuator/health` |
|---|---|
| everything up | 200 `UP` |
| Redis stopped | 200 `DEGRADED` — and the pages still work, about a second slower |
| Redis back | 200 `UP` again, by itself |
| Postgres stopped | 503 `DOWN` — correct: nothing works without it |

That last row took **30 seconds** to answer: the connection pool waits 30 s for a connection by
default. Render gives up after 5 s anyway, and meanwhile the hanging check (or page) holds one of
the few threads a small machine has, so the render profile waits 10 s at most.

**Details stay private.** The public answer is only the status; which part failed is in the logs.
A health endpoint that names your database host is a gift to an attacker.

---

## 3. The container image

📄 `Dockerfile`, `.dockerignore`

An **image** is a packaged filesystem plus a start command; a **container** is a running copy of
it. Render runs our image; so can any other platform, or your laptop (`docker run`).

### Two stages

```
FROM eclipse-temurin:21-jdk-alpine AS build   ← JDK + Maven: compiles, packages
FROM eclipse-temurin:21-jre-alpine            ← only a Java runtime + the built app
```

A **multi-stage build** throws the first stage away. The shipped image has no compiler, no Maven,
no source code: smaller, and less for an attacker to use. *Alpine* is a tiny Linux; *JRE* is Java
without the development tools.

### Layers, and why the order of the lines matters

Every instruction makes a **layer**, and Docker reuses a layer if nothing it depends on changed.
So the Dockerfile goes from what changes least to what changes most:

1. `COPY pom.xml` + `dependency:go-offline` — the libraries: re-downloaded only when `pom.xml`
   changes;
2. `COPY src` + `package` — our code;
3. in the run stage, the jar **taken apart into layers** (`java -Djarmode=tools … extract --layers`):
   libraries (135 MB), Spring Boot's loader, snapshot libraries, and our own classes (475 KB).

Measured: the first build took **198 s** (125 s of it downloading libraries); after a code change,
**20 s**. Pushing a release uploads the 475 KB layer, not 135 MB.

**A bug found by running it:** the extracted jar keeps its original name (`rentalhub-1.0.0.jar`),
so an `ENTRYPOINT` starting `application.jar` found nothing. Spring's documentation renames the
jar before extracting; so does ours now. Reading a Dockerfile is not testing it.

### Security

- **Not root:** `USER rentalhub`. If the app were broken into, the intruder gets an account that
  owns nothing. The app's files stay owned by root, so the app can't rewrite its own code.
- **`.dockerignore`** keeps `.git`, `.env` files, `target/` and the docs out of the build.
- **No secrets in the image:** every key arrives as an environment variable at run time.

### Java in a 512 MB box

Java sizes its heap from the machine's memory. It reads the *container's* limit (since Java 10),
but the heap isn't everything: loaded classes, compiled code, threads and network buffers live
outside it. Give the heap too much and the **container is killed** for going over its limit,
instead of Java collecting garbage. So:

| Option | Why |
|---|---|
| `-XX:MaxRAMPercentage=60` | heap ≤ 60% of the container; the rest for everything else |
| `-XX:+UseSerialGC` | one garbage-collector thread: least memory, right for a fraction of a CPU |
| `-XX:TieredStopAtLevel=1` | only Java's quick compiler: much faster start on a slow CPU, less memory; a little slower at peak |
| `-XX:+ExitOnOutOfMemoryError` | out of memory: stop, and let the platform start a fresh copy |

Measured with Render's free limits (`docker run --memory=512m --cpus=0.1`): started in **148 s**
(a tenth of a CPU is slow), seeded in 20 s, **339 MB** after start, **363 MB** after every page
three times. Warm pages answered in 0.1–0.3 s.

`ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar application.jar"]`: the shell expands
`$JAVA_OPTS`, then `exec` *replaces* the shell with Java, so Java receives the platform's stop
signal and shuts down cleanly (finishing requests, closing connections).

**Tests are skipped in the image build** (`-DskipTests`): they start Docker containers, and a
build container has no Docker. They run on your machine before a push.

---

## 4. Render, as a Blueprint

📄 `render.yaml`, `application-render.yml`

**Infrastructure as code:** instead of clicking three services together in a dashboard, one file
describes them, lives in Git, and is reviewed like code. Render calls it a **Blueprint**.

| Resource | Type | What wires it in |
|---|---|---|
| `rentalhub` | `web`, `runtime: docker` | built from the Dockerfile; `healthCheckPath: /actuator/health` |
| `rentalhub-db` | Postgres 16 | `fromDatabase` fills `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` |
| `rentalhub-cache` | `keyvalue` (Render's Redis; renamed from `redis`) | `fromService` fills `REDIS_URL` |

- **The private network.** All three are in one region, and the database and Key Value have
  `ipAllowList: []`: nothing outside Render can reach them; the app reaches them by internal
  names.
- **Secrets are `sync: false`:** Render asks for them once, when the Blueprint is created, and
  they never appear in the file.
- **Why the database as parts, not one URL?** Render's ready-made connection string starts
  `postgres://`; Java's driver wants `jdbc:postgresql://`. The app assembles its own from the
  parts (a Phase 1 decision that paid off here).
- **pgvector on Render:** the managed Postgres supports `vector`, `btree_gist` and `hstore`, and
  Flyway's migrations create them with `CREATE EXTENSION IF NOT EXISTS`.

### Behind a proxy

Render ends HTTPS at its own proxy and forwards plain HTTP to the container, adding
`X-Forwarded-Proto: https` and `X-Forwarded-Host`. The render profile:

- `server.forward-headers-strategy: framework` believes those headers, so addresses the app
  writes are right. Measured: a 201's `Location` became `https://rentalhub.onrender.com/api/properties/17`
  instead of `http://localhost:…`. (Safe only because the container is reachable solely through
  the proxy; otherwise anyone could claim to be HTTPS.)
- `server.servlet.session.cookie.secure: true`: the session cookie is only ever sent over HTTPS.
- 40 Tomcat threads instead of 200, a pool of 5 database connections: more only queue for the
  same tenth of a CPU and cost memory.

### Free, with conditions

| Free plan | Limit |
|---|---|
| Web service | 512 MB, 0.1 CPU; sleeps after 15 minutes idle; about a minute to wake, plus our start-up |
| Postgres | 1 GB; **expires 30 days** after creation (14 days' grace); no backups |
| Key Value | 25 MB; nothing survives a restart (fine: it's a cache) |

The seeder makes the database expiry survivable for a demo: a new database is refilled on the
next start.

---

## 5. Configuration through the environment

Everything that differs between your laptop and Render — addresses, passwords, keys — is an
**environment variable** read with a default: `${DB_HOST:localhost}`. The code and the image are
the same everywhere; only the environment changes. (This is one of the "twelve-factor app" rules,
a well-known checklist for deployable services.)

The README's table lists every variable: what it's for, where to get it, whether it's required,
and who sets it on Render — Render itself (`PORT`), the Blueprint (`DB_*`, `REDIS_URL`), or you
(the keys).

---

## 6. How it's tested

| Test | Covers |
|---|---|
| `DemoDataSeederTest` | counts; every type and currency; stays that ended have reviews, upcoming ones are in the future; the home page isn't blank; never twice, never over real data; the audit actor; a seeded booking cancels and refunds like a real one |
| `SharedCacheHealthIndicatorTest` | Redis answering → UP; unreachable → DEGRADED, never DOWN |
| `HealthApiTest` | `/actuator/health` is 200 UP with no details; Redis is checked by our indicator, not Boot's |
| By hand (hands-on Parts 71–75) | the image built, run with 512 MB / 0.1 CPU, `PORT=10000` and the render profile: start-up, seeding, memory, every page, HTTPS addresses, the secure cookie, Redis and Postgres stopped |

One existing test broke, usefully: `RedisDownTest` has its own application context, so the seeder
filled its empty database and the test found three Goa villas instead of one. Every test context
now switches the seeder off, and the break doubled as proof that seeding works with Redis down.

---

## Interview questions — practise answering these aloud

**"Why a multi-stage Docker build?"**
The build needs a JDK and Maven; running needs only a JRE. The final image copies just the built
app from the first stage, so it's smaller and has no compiler or source for an attacker to use.

**"What are image layers, and how did you use them?"**
Each instruction is a cached layer, reused if its inputs didn't change. Dependencies come before
source, and the jar is extracted into library and application layers, so a code change rebuilds
in 20 s and pushes a 475 KB layer instead of 135 MB.

**"How do you size the JVM for a container?"**
Java reads the container's memory limit, but the heap is only part of its memory. I cap the heap
at 60% (`MaxRAMPercentage`) and measure: 363 MB of 512 after traffic. Too big a heap gets the
container killed instead of collecting garbage.

**"Why run as non-root?"**
Least privilege: a compromise gets an account that owns nothing, and the app can't modify its
own files.

**"What's a health check for, and why DEGRADED?"**
The platform uses it to decide whether a deploy goes live and whether to restart an instance.
The cache is optional, so losing it shouldn't make the platform kill a working app: DEGRADED is
worse than UP but still 200. The database is required, so it's DOWN.

**"How do you seed demo data safely?"**
Only into an empty database, in one transaction, through the same services as users, recorded
as a system actor, switchable, and never failing start-up. Dates relative to today.

**"What is infrastructure as code?"**
Describing servers, databases and wiring in a versioned file (here `render.yaml`) instead of
dashboard clicks: reviewable, repeatable, and recreated identically.

**"How do secrets reach your app?"**
As environment variables set on the platform, never in Git or the image. The Blueprint marks them
`sync: false`, so Render asks for them and the file never holds them.

**"What does `X-Forwarded-Proto` do?"**
A proxy that ends HTTPS tells the app the original scheme, so the app builds `https://` links and
can mark cookies Secure. Trusting it is only safe when nothing can reach the app except that proxy.

---

## Honest limitations

- **I haven't deployed it.** Render needs your account: the Blueprint, image and settings were
  checked against Render's documentation and by running the image locally under the same limits,
  not on Render itself. Part 76 of the hands-on guide is the real deploy.
- **Tests don't run on Render.** Render builds with `-DskipTests`; a CI pipeline (GitHub Actions
  running `mvnw test` on every push) is the usual next step.
- **Slow start on the free CPU** (about 2.5 minutes, after a minute of waking). Paid instances
  start in seconds; Class Data Sharing (a start-up cache Spring Boot supports) would help, but its
  training run needs a database at build time.
- **One instance only.** Sessions, the Caffeine cache and the scheduled jobs all assume one copy;
  a second needs Spring Session in Redis, cache-eviction broadcasts, and a job lock (ShedLock).
- **The free database has no backups and expires**; fine for a demo, not for real data.
- **Demo photos:** none — without image storage the cards show their placeholder.

---

## Try it yourself

The [hands-on guide](hands-on-guide.md), Parts 71–76: the demo world on an empty database, the
health check with Redis stopped, building the image, running it with Render's limits, and the
Render deploy itself.

---

## YouTube for this phase (in order)

1. `docker tutorial for beginners`
2. `docker multi stage build`
3. `spring boot docker layered jar`
4. `jvm memory in docker containers`
5. `spring boot actuator health check`
6. `infrastructure as code explained`
7. `deploy spring boot to render`
8. `twelve factor app`

Clickable versions are in the [project log](project-log.md#6-youtube-study-plan--every-phase).
