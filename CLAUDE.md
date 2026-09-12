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
(`org.testcontainers.postgresql.PostgreSQLContainer`, artifact `testcontainers-postgresql`).
Boot 4 specifics: `spring-boot-starter-webmvc` (not `-web`), `spring-boot-starter-flyway`
is required, Jackson 3 (`tools.jackson`), Spring Retry is NOT managed — use Spring
Framework 7's built-in `@Retryable` (`org.springframework.resilience.annotation`).

## Hard constraints
- ONE deployable Spring Boot container. No Python, LangChain, Node, npm, package.json,
  separate frontend, or second service of any kind.
- No Spring Security. Session-based "sign in as" demo switcher; `User` has no password.
- Boots and is fully usable with no AI, Stripe or S3 credentials. Missing credentials
  degrade only their own feature, with a clear message. Never throw at startup.
- No `if`/`switch` on property type anywhere. Type dispatch goes through `PropertyFactory`
  (EnumMap of `PropertyCreator` beans; fails at startup if a type has no creator).
  Type-specific fields are declared by each creator as `AttributeSpec`s, travel in
  `CreatePropertyRequest.attributes`, and are exposed by `Property.typeAttributes()`,
  so forms/views/GraphQL render them generically.
- Adding a property type = enum value + entity + creator + migration + translated
  labels. Nothing else. `PropertyFactoryTest` enforces entity/creator/label agreement.

## Conventions
- Money: `BigDecimal` in Java, `NUMERIC(19,4)` in SQL. `double`/`float` banned for money,
  including intermediate steps. Compare with `compareTo`, never `equals`. Explicit
  `RoundingMode` on every `divide`/`setScale`. Prices may not have more decimals than
  their currency uses (`Currency.fractionDigits()`). Stored booking totals are always in
  the property's native currency; converted figures are display-only, never persisted.
- Flyway owns the schema; `ddl-auto: validate`. Never edit a committed migration — add
  `V{n+1}__description.sql`. Spring AI vector-store schema init is OFF; Flyway creates it.
- Exceptions carry i18n message keys + args, never English sentences. User-facing
  exceptions extend `LocalizedException` (a `MessageSourceResolvable`);
  `GlobalExceptionHandler` resolves them into RFC 9457 ProblemDetail for REST only.
  Every new key goes into messages.properties, _hi and _es with a real translation.
  Messages with `{0}` arguments go through MessageFormat: write apostrophes as `''`
  (better: avoid them). Bean-validation messages use `{key}` and `{max}`-style params.
- Entities: `@Getter`/`@Setter` only (no setters on id/version/timestamps). Never
  `@Data`, `@EqualsAndHashCode` or `@ToString`.
- `open-in-view: false`. Services load everything a view needs.
- Caches hold DTOs, never managed entities.
- Retry wraps the transaction from the outside: each attempt is a fresh transaction.
- No external calls (Stripe, S3, Gemini, FX API) inside a DB transaction.
- No secrets in the repo: `${ENV_VAR:local-default}` in YAML; `.env` is git-ignored.
  Database env vars: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`.
  `spring.profiles.default: local`; Render sets `SPRING_PROFILES_ACTIVE=render`.
- Case conversion of identifiers uses `Locale.ROOT`.
- Javadoc explains *why*, not what.
- Tests: plain unit tests where possible; anything touching SQL uses Testcontainers
  (`pgvector/pgvector:pg16`, via `support/TestcontainersConfiguration`), never H2.
  DB tests are `@Transactional` so they roll back.

## Package layout (base `com.rentalhub`)
```
config/        CacheConfig, WebConfig, OpenApiConfig, RetryConfig, SchedulingConfig,
               EnversConfig, S3Config, StripeConfig, AiConfig
domain/model/  Property (abstract, SINGLE_TABLE) + Apartment/Villa/Cabin/Studio, User,
               Booking, Review, Favorite, PropertyImage; enums/ PropertyType,
               BookingStatus, Currency, UserRole
domain/repository/  Spring Data JPA interfaces
factory/       PropertyCreator, AbstractPropertyCreator (template method), PropertyFactory,
               AttributeSpec, AttributeKind, TypeAttributes; impl/ one creator per type
service/       PropertyService, SearchService, BookingService, ReviewService,
               PaymentService, CurrencyService, ImageStorageService, DemoUserService
ai/            AiAvailability, ListingEmbeddingService, PreferenceProfileService,
               HybridRetriever, RecommendationService, StatsService
web/rest, web/graphql, web/mvc   controllers
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
5. commit `Phase N: <descriptive summary>` (message from a file: `git commit -F <file>`);
6. STOP and wait for the owner's confirmation.

## Phases
| # | Phase | Status |
|---|-------|--------|
| 1 | Foundation: pom, wrapper, compose, config, V1 schema, domain, factory, tests | done |
| 2 | Caching: Caffeine + Redis two-tier, invalidation | — |
| 3 | Bookings: transactions, optimistic locking, retry, concurrency test | — |
| 4 | Auditing & scheduling: Envers, structured logs, @Scheduled job | — |
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
