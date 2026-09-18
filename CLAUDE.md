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
Spring AI 2.0.1, imported as a BOM: starters `spring-ai-starter-model-google-genai`,
`spring-ai-starter-model-google-genai-embedding` (the embedding model ships separately) and
`spring-ai-starter-vector-store-pgvector`. `PgVectorStoreAutoConfiguration` takes the
`EmbeddingModel` as a constructor argument, so with no key the vector store must be switched
off too, and the Gemini *embedding connection* auto-configuration has no property switch of
its own and must be excluded. In Boot 4 an `EnvironmentPostProcessor` is registered as
`org.springframework.boot.EnvironmentPostProcessor` in `META-INF/spring.factories`; the old
`org.springframework.boot.env` name and its `.imports` file are ignored silently.
Stripe: `com.stripe:stripe-java` 33.4.2 (not Boot-managed; pinned in the pom). Use
`StripeClient` and its `v1()` services (`client.v1().paymentIntents()`); the direct
accessors are deprecated. `StripeClient.builder().setApiBase(...)` points it at a fake
server in tests. Its exceptions are checked (`StripeException`).

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
  `rentalhub.cache.key-prefix` (now `rentalhub:v3:`; next change → `v4`).
- Payments (phase 5): a saga, never one transaction. `BookingAttempt` inserts the booking
  PENDING/UNPAID (dates held) → `PaymentService.collect`: create the payment (no money moves)
  → record its id (`BookingUpdates`, own transaction) → confirm → CONFIRMED/PAID, or
  CANCELLED/FAILED (dates freed, payment cancelled), or left PENDING when the outcome is
  unknown (`PaymentReconciliationJob` settles it). Booking state changes after the insert go
  through `BookingUpdates` (one short transaction each) and the entity's transition methods
  (`paymentStarted`, `paid`, `paymentFailed`, `cancel`, `refunded`). Every provider call
  carries an idempotency key `rentalhub-booking-<id>-<createdAtMillis>-<step>`. Providers sit
  behind `payment/PaymentGateway` (Stripe, or `SimulatedPaymentGateway` when
  `STRIPE_SECRET_KEY` is unset; live keys are refused); a booking stores its
  `payment_provider`, and refunds/lookups go back to that provider. 402 declined or
  3-D Secure, 503 provider refused (`Retry-After`), 202 outcome unknown.
- Currencies (phase 5): `?currency=` adds display-only `displayPrice`/`displayTotal` to a copy
  of the (cached) record via `CurrencyService.inCurrency`; never stored or cached. Rates:
  `fx/ExchangeRateApiSource` (open.er-api.com), kept in memory 1h, last good set kept up to
  48h, 1m between failed fetches. `maxPrice` is converted into per-currency ceilings
  (`PriceCeilings`, rounded DOWN) and the currency is part of the search cache key; default
  currency INR (`rentalhub.fx.default-currency`).
- Every listing write goes through `PropertyService`, which publishes `PropertyChangedEvent`;
  `PropertyCacheInvalidator` evicts after commit. Production code never changes listings
  via the repository directly (tests may, to bypass the caches on purpose). The one
  exception is the version bump every booking makes (`OPTIMISTIC_FORCE_INCREMENT`):
  `BookingAttempt` publishes `ListingBookedEvent`, which evicts only that listing's entry.
  Photos are the other listing write: they go through `ListingImageService`/`ListingImageUpdates`,
  which publish the same `PropertyChangedEvent`.
- Photos (phase 7): `storage/ImageStore` (S3 via the AWS SDK v2, or `UnconfiguredImageStore` when
  `S3_BUCKET` is unset: uploads answer 503 `image.storage.notConfigured`). The file's first bytes
  decide its type (`ImageFormat`); keys are `listings/<id>/<uuid>.<ext>`, never the uploaded
  name. Upload = checks, then put the file (no transaction), then insert the row, deleting the
  file if the insert fails; removal = row first, file after commit (`ListingImagesRemovedEvent`
  to `ImageObjectCleaner`), also on listing delete. Photo changes load the listing with
  `OPTIMISTIC_FORCE_INCREMENT` and NO entity graph (Hibernate locks every entity a query loads).
  Photos are served by `ImageController` at `/images/listings/**` from a private bucket.
- Languages (phase 7): en/hi/es only. `web/LanguageParameterFilter` applies `?lang=` to every
  request (query string only on multipart, so uploads are not read early); `WebConfig`'s
  cookie-remembering resolver must stay lazy. Spring's own errors resolve through
  `problemDetail.<exception class>` keys; titles through `error.title.<status>`;
  `ApiRoutingErrorHandler` covers 404/405 on `/api/` and `/images/` paths.
  `MessagesFilesTest` enforces same keys/placeholders, real translations, and that every key
  used in code exists.
- GraphQL (phase 7): `resources/graphql/schema.graphqls`, controllers in `web/graphql/` call the
  same services as REST. Money is the `Decimal` scalar (a string), never Float. List fields that
  load related data use `@BatchMapping`. Errors go through `GraphQlErrorResolver` (same keys).
  A schema field with no data fetcher fails startup (`GraphQlConfig`).
- API docs (phase 7): every REST endpoint gets `@Operation` with a description and an example
  (JSON in `web/rest/ApiExamples`), and a request in `postman/RentalHub.postman_collection.json`.
  `OpenApiDocumentationTest` and `PostmanCollectionTest` fail the build otherwise.
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
- AI (phase 6): everything in `ai/` asks `AiAvailability` first and degrades instead of
  failing; `/api/recommendations` always answers 200 with `aiUsed`/`semantic` flags. A listing
  is embedded after commit (`ListingIndexUpdater` on `PropertyChangedEvent`, never throwing)
  and only when the SHA-256 of its text changed; `EmbeddingIndexJob` sweeps up what was missed,
  one listing per failure-logged iteration. The question is parsed and the intent routed by
  rules (`QueryParser`), never by a model; questions with one right answer go to `StatsService`
  (pure SQL); "like my favourites" is `avg(embedding)` inside Postgres. Vector candidates are
  always re-checked against live rows in SQL (`PropertySpecifications.search(..., ids)`), and
  every answer the model writes is checked for `[id]`s that were never offered.
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
  Redis: `REDIS_URL` (default `redis://localhost:6379`). Payments: `STRIPE_SECRET_KEY`
  (test key; unset → simulated), `STRIPE_API_BASE` (stripe-mock only). Rates: `FX_RATES_URL`.
  AI: `GEMINI_API_KEY` (unset → the AI is switched off entirely). Photos: `S3_BUCKET` (unset →
  uploads off), `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `S3_ENDPOINT` +
  `S3_PATH_STYLE` for an S3-compatible server (MinIO) only.
  Jobs: `STALE_LISTINGS_CRON`/`_ZONE`, `PAYMENT_RECONCILIATION_CRON`, `PAYMENT_STALE_AFTER`,
  `EMBEDDING_INDEX_CRON`.
  `spring.profiles.default: local`; Render sets `SPRING_PROFILES_ACTIVE=render`.
- Case conversion of identifiers uses `Locale.ROOT`.
- Javadoc explains *why*, not what.
- Tests: plain unit tests where possible. Anything touching SQL or Redis extends
  `support/IntegrationTest`: one shared context with Postgres (`pgvector/pgvector:pg16`)
  and Redis containers, `@AutoConfigureMockMvc`, and no optional credentials (it doubles as
  the proof that the app runs without them); after each test it truncates tables and
  invalidates caches. Tests needing the optional services on extend
  `support/ConnectedIntegrationTest` (fake AI models, MinIO for S3), a second shared context. Never H2. Tests of after-commit behaviour, and API tests, must NOT be
  `@Transactional` (a test-wide transaction hides lazy-loading bugs and never commits).

## Package layout (base `com.rentalhub`)
```
config/        CacheConfig, ClockConfig, RetryConfig, SchedulingConfig, StripeConfig
               (chooses the payment gateway), CurrencyConfig (rates client), WebConfig
               (language resolver), OpenApiConfig, S3Config (chooses the image store),
               GraphQlConfig (scalars), AiConfig (Envers is set in application.yml)
audit/         AuditActor (who is acting, per thread), Revision (revinfo), ActorRevisionListener
payment/       PaymentGateway (+ StripePaymentGateway, SimulatedPaymentGateway), PaymentGateways,
               PaymentOutcome, PaymentRequest, PaymentGatewayException, PaymentSettings
fx/            ExchangeRates, ExchangeRateSource (+ ExchangeRateApiSource), FxSettings
storage/       ImageStore (+ S3ImageStore, UnconfiguredImageStore), ImageFormat (magic bytes),
               ImageStorageSettings, ImageStoreException
domain/model/  Property (abstract, SINGLE_TABLE) + Apartment/Villa/Cabin/Studio, User,
               Booking, Review, Favorite, PropertyImage; enums/ PropertyType,
               BookingStatus, PaymentStatus, PaymentProvider, Currency, UserRole
domain/repository/  Spring Data JPA interfaces
factory/       PropertyCreator, AbstractPropertyCreator (template method), PropertyFactory,
               AttributeSpec, AttributeKind, TypeAttributes; impl/ one creator per type
cache/         TwoLevelCache, PropertyCacheInvalidator, SearchCacheKeys(+KeyGenerator),
               CacheNames, CacheSettings (wired in config/CacheConfig)
service/       PropertyService, SearchService, ListingHistoryService, BookingService
               (+ BookingAttempt, BookingUpdates, BookingRules, BookingSettings,
               OverlapConstraint), PaymentService, CurrencyService (+ PriceCeilings),
               ReviewService, FavoriteService, ListingImageService (+ ListingImageUpdates,
               ImageObjectCleaner, ListingImagesRemovedEvent), ConstraintViolations,
               DemoUserService
ai/            AiAvailability, AiSettings, AiEnvironmentPostProcessor (no key → AI off),
               EmbeddingIndexStore, ListingEmbeddingService, ListingIndexUpdater,
               ParsedQuery + QueryParser (rules), PreferenceProfile(+Service),
               HybridRetriever, RecommendationService, StatsService
web/           RequestIdFilter (request id + user in the MDC, audit actor),
               LanguageParameterFilter (?lang=); rest/ (+ ApiExamples), graphql/ (controllers,
               GraphQlScalars, DemoUserInterceptor, GraphQlErrorResolver), mvc/ controllers
dto/, exception/ (GlobalExceptionHandler, ApiRoutingErrorHandler), scheduling/
               (StaleListingJob, PaymentReconciliationJob, EmbeddingIndexJob), bootstrap/
               (DemoDataSeeder)
resources/     application.yml (+ -local, -render), db/migration/, messages*.properties,
               graphql/schema.graphqls, templates/, static/css/app.css
docs/learning/ one teaching doc per phase
postman/       the collection (every endpoint) and the local environment
samples/api/   request bodies, sample photos and GraphQL documents for trying the API by hand
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
| 5 | Payments: Stripe, BigDecimal math, multi-currency display | done |
| 6 | AI / RAG: embeddings, preference profile, hybrid search, stats mode (+ favourites) | done |
| 7 | Extra mile: S3, i18n, GraphQL, OpenAPI, Postman | done |
| 8 | Frontend: Thymeleaf pages | — |
| 9 | Ship: seeder, Dockerfile, render.yaml, README, deploy guide | — |

## Commands
```powershell
docker compose up -d                        # Postgres (pgvector) + Redis
docker compose --profile photos up -d       # + MinIO, an S3 stand-in, for trying photo uploads
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
- 2026-09-14 — Bookings CONFIRMED immediately until phase 5 — no payment step yet. (Superseded 2026-09-16: PENDING until paid; no key → simulated payments.)
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
- ~~Open: maxPrice filter ignores currency~~ — fixed 2026-09-16 (per-currency ceilings).
- 2026-09-16 — Payment saga (hold dates PENDING → create PaymentIntent → record its id → confirm → settle); no external call in a transaction — a rollback can't undo a charge; the recorded id makes every failure recoverable.
- 2026-09-16 — Server-side confirmation with a `paymentMethodId` in the booking request (Stripe test ids), redirects off; 3-D Secure → 402 for now — no browser until phase 8; card numbers never reach the server.
- 2026-09-16 — No Stripe key → SimulatedPaymentGateway (Stripe's test ids + pm_sim_noAnswer/pm_sim_providerDown), labelled SIMULATED; live keys refused — Stripe India is invite-only; the saga must be demonstrable without an account.
- 2026-09-16 — 201 paid, 202 outcome unknown, 402 declined/3-D Secure, 503 provider refused (Retry-After 60) — each tells the client what to do next.
- 2026-09-16 — Failed payment keeps its row (CANCELLED + payment FAILED); separate `payment_status` (NONE/UNPAID/PAID/FAILED/REFUNDED), `payment_provider`, `refund_reference` (V3) — bookings are records; the provider's payment points at them; the spec's four statuses stay.
- 2026-09-16 — Cancelling a paid booking refunds in full (cancel commits first, refund after); PENDING can't be cancelled (409) — free cancellation until check-in; no race with an undecided payment.
- 2026-09-16 — PaymentReconciliationJob every 5 min: PENDING > 10 min settled via lookUp, refunds owed retried — covers lost answers and crashes between saga steps.
- 2026-09-16 — Idempotency key = booking id + createdAt millis + step; createdAt truncated to micros on insert — ids repeat after a dev DB wipe; the in-memory and stored times must match.
- 2026-09-16 — FX from ExchangeRate-API's open endpoint, in memory (1h refresh, last good up to 48h, 1m retry delay, one fetch at a time); display via `?currency=` only, never stored/cached — free, keyless, has AED (Frankfurter/ECB doesn't).
- 2026-09-16 — maxPrice converted to per-currency ceilings rounded DOWN; default currency INR; without rates same-currency only + `exchangeRatesUnavailable` (not cached); currency in the search key; cache prefix v2 — fair comparison without stored conversions; old searches keep their meaning.
- 2026-09-18 — Favourites API added in phase 6 (idempotent PUT/DELETE) — the preference profile and the taste vector are built from favourites, and nothing created any.
- 2026-09-18 — Rules, not an LLM, parse the question and route the intent; statistics answered by pure SQL — deterministic, testable, free, and no second failure mode before any work starts.
- 2026-09-18 — "Like my favourites" is the average of their embeddings computed in Postgres — a taste with no model call; the saved listings themselves are excluded.
- 2026-09-18 — Flyway owns `vector_store` (V4, 768 dims, HNSW, cosine); Spring AI schema init off; document id derived from the listing id, content hashed, price rounded to the currency's decimals before hashing — one owner for the schema, one document per listing, no re-embedding of unchanged text.
- 2026-09-18 — The model is shown only the retrieved listings as `[id]`, and the answer is checked afterwards (invented ids removed, all-invented answers discarded) — a prompt is a request; a check is a rule.
- 2026-09-18 — No key: `AiEnvironmentPostProcessor` switches chat, embeddings and the vector store off and excludes the Gemini embedding connection auto-config — the app must start without credentials.
- 2026-09-18 — Tests fake only the two models (`support/FakeAiModels`); the vector store, pgvector and the SQL are real — no build depends on a key, quota or the network.
- 2026-09-19 — No S3 settings → uploads are a clean 503; `S3_ENDPOINT`/`S3_PATH_STYLE` + MinIO (compose profile, tests) instead of a local-disk fallback — the spec says fail cleanly; Render's disk is wiped on every deploy.
- 2026-09-19 — Photos served by the app from a private bucket, cacheable for a year; random keys; type from magic bytes; upload file then row (compensating delete), removal row then file after commit; 5 MB, 10 per listing — no public bucket to misconfigure; pre-signed links would expire inside cached listings; the worst leftover is an unused file.
- 2026-09-19 — Language: `?lang=` via a servlet filter + cookie, then Accept-Language, then English; en/hi/es only; Spring's own errors translated via `problemDetail.*` keys; a test keeps the three files in step — works on 404/405 too; stateless; "real translations" enforced mechanically.
- 2026-09-19 — GraphQL shares the REST services; money as a string `Decimal` scalar; `@BatchMapping` for hosts/photos; a `cancelBooking` mutation; custom CONFLICT/PAYMENT_FAILED/UNAVAILABLE classifications — Float is a double; N+1 proven away by a statement-count test.
- 2026-09-19 — Tests fail when an endpoint lacks Swagger docs/examples or a Postman request — documentation that is not checked goes stale.
- 2026-09-19 — Cache key prefix `rentalhub:v3:` — a listing's photos gained their id in the cached record.
