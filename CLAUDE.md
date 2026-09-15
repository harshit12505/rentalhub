# RentalHub — project context

Airbnb-style property listing, booking and host-management platform. Portfolio project
for an intermediate SWE role, built by a final-year student who is learning: explain
each phase in plain language BEFORE coding it, define jargon on first use, and flag
every judgement call the spec doesn't cover, with the reason. The code itself must be
production quality. Every phase also gets a teaching doc in `docs/learning/`.

## Environment
- Windows 11, PowerShell, IntelliJ IDEA Community. Repo: `C:\dev\rentalhub`.
- Commands for the owner must be PowerShell-safe: `.\mvnw.cmd`, `curl.exe`, backslash
  paths, no `&&`. Quote `-D` args (`.\mvnw.cmd test "-Dtest=FooTest"`) — PowerShell
  splits unquoted `-Dx.y=z`.
- Docker Desktop (per-user install, `%LOCALAPPDATA%\Programs\DockerDesktop`) must be
  running for `docker compose` and for tests (Testcontainers).
- On the owner's machine an Oracle DB listener (TNSLSNR) holds port 8080. Run locally with
  `$env:PORT = "8081"`. Keep the app's default at 8080 (Render sets PORT anyway).

## Stack (fixed — propose changes and wait; never substitute silently)
Java 21 · Spring Boot 4.1.x · Spring AI 2.0.x · Maven 3.9 + wrapper · Thymeleaf +
Bootstrap 5 (CDN) · PostgreSQL 16 + pgvector · Flyway · Caffeine (local) + Redis
(distributed) · Gemini via Developer API key (NOT Vertex): chat `gemini-2.5-flash`,
embeddings `gemini-embedding-001` at 768 dims, normalised · Hibernate Envers · Stripe
test mode · AWS S3 · REST + Spring GraphQL · springdoc-openapi · Lombok.

Resolved versions worth knowing: Hibernate 7.4, Flyway 12.4, Testcontainers 2.0
(`org.testcontainers.postgresql.PostgreSQLContainer`, artifact `testcontainers-postgresql`),
Spring Data Redis 4.1 on Lettuce 7.5, Caffeine 3.2, Jackson 3.1 (Redis JSON via
`JacksonJsonRedisSerializer`). MockMvc: `@AutoConfigureMockMvc` is in
`org.springframework.boot.webmvc.test.autoconfigure` (test starter `spring-boot-starter-webmvc-test`).
With Lettuce, Spring Data Redis cache writes/clears are asynchronous unless `immediateWrites()`.
Boot 4 specifics: `spring-boot-starter-webmvc` (not `-web`), `spring-boot-starter-flyway`
is required, Jackson 3 (`tools.jackson`), Spring Retry is NOT managed — Spring
Framework 7 has its own retry: `RetryTemplate`/`RetryPolicy` (`org.springframework.core.retry`,
used for bookings, see `RetryConfig`) and `@Retryable` (`org.springframework.resilience.annotation`).
There is no `@Recover`: recover with a try/catch around `RetryTemplate.invoke()`, which
rethrows the last failure. `RetryPolicy` needs a positive `maxDelay`.

## Hard constraints
- ONE deployable Spring Boot container. No Python, LangChain, Node, npm, package.json,
  separate frontend, or second service of any kind.
- No Spring Security. Session-based "sign in as" demo switcher; `User` has no password.
- Boots and is fully usable with no AI, Stripe or S3 credentials. Missing credentials
  degrade only their own feature, with a clear message. Never throw at startup.
- No `if`/`switch` on property type anywhere. Type dispatch goes through `PropertyFactory`
  (EnumMap of `PropertyCreator` beans; fails at startup if a type has no creator).
  Type-specific fields are declared by each creator as `AttributeSpec`s, travel in
  `PropertyRequest.attributes`, and are exposed by `Property.typeAttributes()`,
  so forms/views/GraphQL render them generically.
- Adding a property type = enum value + entity + creator + migration + translated
  labels. Nothing else. `PropertyFactoryTest` enforces entity/creator/label agreement.
  The entity carries `@Audited`, and the migration adds its columns to `properties_aud`
  as well as `properties` (the test checks the annotation; startup validation the columns).

## Conventions
- Money: `BigDecimal` in Java, `NUMERIC(19,4)` in SQL. `double`/`float` banned for money,
  including intermediate steps. Compare with `compareTo`, never `equals`. Explicit
  `RoundingMode` on every `divide`/`setScale`. Prices may not have more decimals than
  their currency uses (`Currency.fractionDigits()`). Stored booking totals are always in
  the property's native currency; converted figures are display-only, never persisted.
- Flyway owns the schema; `ddl-auto: validate`. Never edit a committed migration — add
  `V{n+1}__description.sql`. Spring AI vector-store schema init is OFF; Flyway creates it.
- Exceptions carry i18n message keys + args, never English sentences. User-facing
  exceptions extend `LocalizedException` (a `MessageSourceResolvable`); a broken business
  rule is an `InvalidRequestException` (400, optional `field`; `PropertyValidationException`
  extends it). `GlobalExceptionHandler` resolves them into RFC 9457 ProblemDetail for REST
  only, and maps any `ConcurrencyFailureException` to 409 `error.concurrentUpdate`.
  Every new key goes into messages.properties, _hi and _es with a real translation.
  Messages with `{0}` arguments go through MessageFormat: write apostrophes as `''`
  (better: avoid them). Bean-validation messages use `{key}` and `{max}`-style params.
- Entities: `@Getter`/`@Setter` only (no setters on id/version/timestamps). Never
  `@Data`, `@EqualsAndHashCode` or `@ToString`.
- `open-in-view: false`. Services load everything a view needs.
- Caches hold immutable DTO records, never managed entities (Caffeine hands the same
  instance to every caller). Changing a cached record's shape → bump
  `rentalhub.cache.key-prefix` (`rentalhub:v1:` → `v2`).
- Every listing write goes through `PropertyService`, which publishes `PropertyChangedEvent`;
  `PropertyCacheInvalidator` evicts after commit. Production code never changes listings
  via the repository directly (tests may, to bypass the caches on purpose). The one
  exception is the version bump every booking makes (`OPTIMISTIC_FORCE_INCREMENT`):
  `BookingAttempt` publishes `ListingBookedEvent`, which evicts only that listing's entry.
- Removal after a change uses the *immediate* cache methods (`evictIfPresent`, `invalidate`,
  the Redis writer's `invalidate`), never `evict`/`clear`, which may be deferred.
- REST: the acting user is the `X-Demo-User-Id` header (`ApiHeaders.DEMO_USER_ID`) until
  phase 8's session switcher. Controllers are thin; rules live in services.
- Retry wraps the transaction from the outside: each attempt is a fresh transaction, in a
  separate bean (a call to `this` skips the `@Transactional` proxy). Retry only
  `ConcurrencyFailureException` (lost version race, deadlock victim), never business refusals.
- "Today" comes from the `Clock` bean (`ClockConfig`), never `LocalDate.now()` in production code.
- Auditing: Envers `@Audited` on Property (and every subtype), Booking and Review. Flyway
  creates the history tables (`*_aud`, `revinfo`); a new audited column needs it in the
  `_aud` table too. `revinfo.changed_by` comes from `AuditActor`: set per request by
  `RequestIdFilter`, by jobs with `try (var s = AuditActor.as(AuditActor.system(...)))`.
  Envers sees only changes made through Hibernate entities: plain SQL and bulk JPQL
  updates leave no history, so production code changes data through the services.
- Logging: an event name plus key/value pairs with SLF4J's fluent API
  (`log.atInfo().setMessage("booking.created").addKeyValue("bookingId", id).log()`), never
  values glued into the message. The MDC carries `requestId` and `userId` (web requests)
  or `job` (scheduled work). Locally the console prints `key=value` (`%kvp{NONE}`); the
  render profile logs ECS JSON.
- Scheduled jobs live in `scheduling/`, take their cron from config (`"-"` disables it; the
  integration tests disable every job), change data through services one item per
  transaction, and expose a public method that tests call directly.
- No external calls (Stripe, S3, Gemini, FX API) inside a DB transaction.
- No secrets in the repo: `${ENV_VAR:local-default}` in YAML; `.env` is git-ignored.
  Database env vars: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`.
  Redis: `REDIS_URL` (default `redis://localhost:6379`).
  `spring.profiles.default: local`; Render sets `SPRING_PROFILES_ACTIVE=render`.
- Case conversion of identifiers uses `Locale.ROOT`.
- Javadoc explains *why*, not what.
- Tests: plain unit tests where possible. Anything touching SQL or Redis extends
  `support/IntegrationTest`: one shared context with Postgres (`pgvector/pgvector:pg16`)
  and Redis containers, `@AutoConfigureMockMvc`; after each test it truncates tables and
  invalidates caches. Never H2. Tests of after-commit behaviour, and API tests, must NOT be
  `@Transactional` (a test-wide transaction hides lazy-loading bugs and never commits).

## Package layout (base `com.rentalhub`)
```
config/        CacheConfig, ClockConfig, RetryConfig, SchedulingConfig, WebConfig,
               OpenApiConfig, S3Config, StripeConfig, AiConfig (Envers is set in application.yml)
audit/         AuditActor (who is acting, per thread), Revision (revinfo), ActorRevisionListener
domain/model/  Property (abstract, SINGLE_TABLE) + Apartment/Villa/Cabin/Studio, User,
               Booking, Review, Favorite, PropertyImage; enums/ PropertyType,
               BookingStatus, Currency, UserRole
domain/repository/  Spring Data JPA interfaces
factory/       PropertyCreator, AbstractPropertyCreator (template method), PropertyFactory,
               AttributeSpec, AttributeKind, TypeAttributes; impl/ one creator per type
cache/         TwoLevelCache, PropertyCacheInvalidator, SearchCacheKeys(+KeyGenerator),
               CacheNames, CacheSettings (wired in config/CacheConfig)
service/       PropertyService, SearchService, ListingHistoryService, BookingService
               (+ BookingAttempt, BookingRules, BookingSettings, OverlapConstraint),
               ReviewService, ConstraintViolations, PaymentService, CurrencyService,
               ImageStorageService, DemoUserService
ai/            AiAvailability, ListingEmbeddingService, PreferenceProfileService,
               HybridRetriever, RecommendationService, StatsService
web/           RequestIdFilter (request id + user in the MDC, audit actor);
               rest/, graphql/, mvc/ controllers
dto/, exception/, scheduling/ (StaleListingJob), bootstrap/ (DemoDataSeeder)
resources/     application.yml (+ -local, -render), db/migration/, messages*.properties,
               graphql/schema.graphqls, templates/, static/css/app.css
docs/learning/ one teaching doc per phase
```

## Working method
One phase at a time, in order. Never scaffold a later phase early. After each phase:
1. explain what was built and why, in plain language;
2. `.\mvnw.cmd test` passes;
3. write/update `docs/learning/NN-<phase>.md` (why this tech, how each technique works,
   interview Q&A, YouTube topics);
4. append a dated entry to `docs/learning/project-log.md`: what was done, decisions
   (add to the register), problems hit and fixes, the owner's to-do list, status table
   and commit hash; keep its YouTube study plan complete (clickable YouTube *search*
   links, never invented video URLs);
5. extend `docs/learning/hands-on-guide.md` with the phase's manual checks (exact
   PowerShell commands + expected output), and add request bodies to `samples/api/`.
   Run every step first against throwaway containers (different names/ports, `--rm`;
   never touch the owner's `rentalhub-*` containers) and write down the real output;
6. commit `Phase N: <descriptive summary>` (message from a file: `git commit -F <file>`).
   No AI co-author trailer or attribution line in commit messages or PR descriptions
   (the owner's choice; the history was rewritten on 2026-09-15 to remove them);
7. STOP and wait for the owner's confirmation.

## Phases
| # | Phase | Status |
|---|-------|--------|
| 1 | Foundation: pom, wrapper, compose, config, V1 schema, domain, factory, tests | done |
| 2 | Caching: Caffeine + Redis two-tier, invalidation (+ listings REST API) | done |
| 3 | Bookings: transactions, optimistic locking, retry, concurrency test | done |
| 4 | Auditing & scheduling: Envers, structured logs, @Scheduled job | done |
| 5 | Payments: Stripe, BigDecimal math, multi-currency display | — |
| 6 | AI / RAG: embeddings, preference profile, hybrid search, stats mode | — |
| 7 | Extra mile: S3, i18n, GraphQL, OpenAPI, Postman | — |
| 8 | Frontend: Thymeleaf pages | — |
| 9 | Ship: seeder, Dockerfile, render.yaml, README, deploy guide | — |

## Commands
```powershell
docker compose up -d                        # Postgres (pgvector) + Redis
.\mvnw.cmd test                             # needs Docker running
.\mvnw.cmd spring-boot:run                  # local profile by default
curl.exe http://localhost:8080/actuator/health
docker compose down -v                      # wipe local DB volume (fresh schema)
docker exec -it rentalhub-redis redis-cli --scan --pattern "rentalhub:*"   # cached keys
```

## Decision log (date — decision — why)
- 2026-09-12 — Boot 4.1 + Spring AI 2.0 instead of Boot 3.5 — Spring AI 2.0 requires Boot 4; 3.5 is EOL (2026-06-30).
- 2026-09-12 — RAG stays in Java/Spring AI (owner asked about Python) — one-container rule; stronger interview story.
- 2026-09-12 — Kept pre-existing partial Phase 1 code and fixed it.
- 2026-09-12 — V1 amended before first commit — it had never left the dev machine.
- 2026-09-12 — Data-driven type attributes — makes the "new type = 4 things" acceptance test true for forms/APIs too.
- 2026-09-12 — Generic attribute checks (required/format/choice/unknown) use shared keys with a translated label arg — fewer keys to translate than one "required" key per field.
- 2026-09-12 — Stripe and S3 degrade gracefully without keys, like AI — deployable before creating accounts.
- 2026-09-12 — Embeddings at 768 dims — pgvector index limit is 2,000; 001 defaults to 3,072.
- 2026-09-12 — DB configured via DB_HOST/PORT/NAME/USERNAME/PASSWORD — Render's connection string is postgres://, not JDBC.
- 2026-09-12 — Factory fails fast at startup on missing/duplicate creators — replaces UnsupportedPropertyTypeException.
- 2026-09-12 — Removed Flyway baseline-on-migrate — it would skip V1 on a non-empty database.
- 2026-09-13 — Listings REST API added in phase 2 — caching needs real reads/writes to show and test.
- 2026-09-13 — Listing by id cached in Caffeine (30s) + Redis (10m); search pages Redis-only (5m) — hot/few vs many/shared.
- 2026-09-13 — Invalidate after commit via @TransactionalEventListener — pre-commit eviction lets readers re-cache old rows.
- 2026-09-13 — Search cache partitioned by city (key starts `city:<encoded>|`) — targeted flush keeps hit ratio.
- 2026-09-13 — Immediate removal/writes (evictIfPresent, invalidate, immediateWrites) — Lettuce makes evict/clear/put async.
- 2026-09-13 — Redis optional at runtime (errors logged, treated as misses; 500ms timeout) — outage slows, never breaks.
- 2026-09-13 — No Redis pub/sub L1 invalidation — single instance; documented as the multi-instance fix.
- 2026-09-13 — Factory update path; PUT = full replacement; type immutable; `CreatePropertyRequest` → `PropertyRequest`.
- 2026-09-13 — Deleting a listing with bookings → 409 — bookings are history/payment records.
- Open: /actuator/health goes DOWN when Redis is down (app still works) — decide in phase 9.
- 2026-09-14 — Bookings read the listing with OPTIMISTIC_FORCE_INCREMENT — bookings/edits of one listing take turns; a booking never commits a stale price; cost: one cache eviction per booking.
- 2026-09-14 — Framework 7 RetryTemplate + try/catch recover, not @Retryable/@Recover — no @Recover in Framework 7; nesting explicit; unit-testable.
- 2026-09-14 — Retry ConcurrencyFailureException (version race + deadlock) — the race test hit a real 40P01: overlapping inserts deadlock inside the exclusion constraint.
- 2026-09-14 — Overlap-constraint violation → 409 booking.dates.justTaken, not retried — a retry could only answer the same.
- 2026-09-14 — Bookings CONFIRMED immediately until phase 5 — no payment step yet.
- 2026-09-14 — Booking rules: ≤ 90 nights (config), guests ≤ max, check-out ≤ availableUntil, not own listing, any role may book; cancel is an idempotent POST action until check-in day.
- 2026-09-14 — Any ConcurrencyFailureException reaching REST → 409 error.concurrentUpdate — closed the phase 2 open item on concurrent PUTs.
- Open: a host deleting a listing at the instant it is booked → the guest may get a 500 (FK violation) — map to 404 if it matters.
- 2026-09-15 — Custom Envers revision entity with `changed_by` (AuditActor ThreadLocal, set by RequestIdFilter and jobs) — "who" is the first question a history answers; the listener isn't a Spring bean, and runs on the transaction's thread.
- 2026-09-15 — Not audited: listing images (phase 7), `version` (Envers default), `updatedAt` (revision has the time); user relations store the id only — keeps history meaningful and lean.
- 2026-09-15 — V2 audit DDL taken from Hibernate's own generated schema; history tables permissive (no NOT NULL/CHECK/FK to live tables) — validation must pass; history outlives rows.
- 2026-09-15 — Listing history = diff of consecutive Envers snapshots, host only, readable after deletion (store_data_at_delete) — readers want what changed, not whole rows.
- 2026-09-15 — Reviews API added in phase 4 (spec audits and logs reviews; nothing created them): only guests whose confirmed stay has ended; one per guest per listing (check + unique constraint).
- 2026-09-15 — Structured logging via SLF4J fluent key/value pairs + MDC; local `%kvp{NONE}` (same look as before), ECS JSON in the render profile; all event logs converted, not only bookings/reviews — one style.
- 2026-09-15 — StaleListingJob: 03:15 UTC daily (env-overridable, "-" disables), one transaction per listing via PropertyService, no distributed lock (single instance; ShedLock documented).
- Open: maxPrice filter ignores currency — fix in phase 5.
