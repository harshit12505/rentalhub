# RentalHub — Project log & study plan

Your single place to read **everything that has happened on this project**, **what you
need to do**, and **every YouTube topic to study**, phase by phase. A new entry is
added at the end of every phase.

**Contents**
1. [Where the project stands](#1-where-the-project-stands)
2. [Your to-do list](#2-your-to-do-list)
3. [Log: session 1 — spec review and Phase 1](#3-log-session-1--spec-review-and-phase-1-1213-sep-2026)
4. [Decisions register](#4-decisions-register)
5. [Problems we hit and how they were fixed](#5-problems-we-hit-and-how-they-were-fixed)
6. [YouTube study plan — every phase](#6-youtube-study-plan--every-phase)
7. [Accounts you'll need, and when](#7-accounts-youll-need-and-when)
8. [Command cheat sheet (PowerShell)](#8-command-cheat-sheet-powershell)

---

## 1. Where the project stands

| Phase | What | Status | Commit |
|---|---|---|---|
| 1 | Foundation | ✅ done | `739c2d0` |
| 2 | Caching (Caffeine + Redis) | next — waiting for your go-ahead | |
| 3 | Bookings & concurrency | | |
| 4 | Auditing & scheduling | | |
| 5 | Payments & money | | |
| 6 | AI / RAG | | |
| 7 | S3, i18n, GraphQL, OpenAPI, Postman | | |
| 8 | Frontend (Thymeleaf) | | |
| 9 | Ship: seeder, Docker, Render | | |

**Numbers after Phase 1:** 52 automated tests (42 unit, 10 against a real Postgres),
all passing. The app boots in about 10 seconds and `/actuator/health` returns `UP`.

---

## 2. Your to-do list

### Before Phase 2 (recommended)
- [ ] Start **Docker Desktop** and wait for "Engine running".
- [ ] Wipe the old local database, which still has the draft V1: `docker compose down -v`,
      then `docker compose up -d`.
- [ ] Run the tests yourself: `.\mvnw.cmd test` → expect `Tests run: 52, Failures: 0`.
- [ ] Run the app on port 8081 (Oracle has 8080): `$env:PORT = "8081"`, then
      `.\mvnw.cmd spring-boot:run`, then in another window
      `curl.exe http://localhost:8081/actuator/health` → expect `"status":"UP"`.
- [ ] Open the project in **IntelliJ** (File → Open → `C:\dev\rentalhub`), set the SDK
      to JDK 21, enable annotation processing if asked, run all tests from
      `src/test/java`.
- [ ] From now on, start Claude Code sessions **in `C:\dev\rentalhub`** so CLAUDE.md
      loads automatically.

### Reading
- [ ] [00 — Stack choices](00-stack-choices.md)
- [ ] [01 — Foundation](01-foundation.md), and answer its interview questions out loud
- [ ] The Foundations and Phase 1 videos in [section 6](#6-youtube-study-plan--every-phase)

### Optional
- [ ] Put the code on GitHub (needed for Render in Phase 9, and a free backup now):
      create an empty **private** repo `rentalhub` on github.com, then
      `git -C C:\dev\rentalhub remote add origin https://github.com/YOUR-USERNAME/rentalhub.git`
      and `git -C C:\dev\rentalhub push -u origin main`.
- [ ] Set your real name on commits if you want:
      `git -C C:\dev\rentalhub config user.name "Your Name"`.

---

## 3. Log: session 1 — spec review and Phase 1 (12–13 Sep 2026)

### 3.1 Checking the spec before writing code
- **Your machine:** JDK 21.0.12 (Temurin), Maven 3.9.16, Git 2.55, Docker 29.7 with
  Compose v5.4. Everything needed was installed.
- **Surprise 1: the folder wasn't empty.** `C:\dev\rentalhub` already had a partial
  Phase 1 from 5–8 September: 32 files, an entity model, a factory, a V1 migration and
  9 passing tests, but no git repo, no Maven wrapper and no CLAUDE.md.
- **Surprise 2: the stack didn't fit together.** The spec said Spring Boot 3.5 with
  Spring AI 2.0. Spring AI 2.0 (released June 2026) only runs on Spring Boot 4, and Boot
  3.5 stopped getting free security fixes on 30 June 2026.
- **Risks raised up front:**
  - Gemini's embedding model gives 3,072 numbers per text by default, but pgvector's fast
    index only supports up to 2,000. Plan: request 768.
  - Gemini's free-tier limits change often, and the daily cap will run out before the
    per-minute one.
  - Every booking will bump the listing's version number, so caches must store plain
    copies (DTOs), never live entities.
  - A retry must start a fresh transaction.
  - No Stripe calls inside a database transaction.
  - No Spring Security means no CSRF protection. That's acceptable for a demo, and the
    README will say so.
  - Render's free tier has limited memory and sleeps when idle.
  - Tests will need Docker running.

### 3.2 Your decisions
1. **Stack:** Spring Boot 4.1 + Spring AI 2.0.
2. **Python for the AI part?** You asked whether to use Python if it's easier. I
   recommended staying with Java. The spec requires one container, "RAG built in Java
   with Spring AI" is a rarer interview story, and the ideas are the same in any
   language: learn them from Python videos, apply them here. This can still be revisited
   before Phase 6.
3. **Existing code:** keep it and fix it.
4. **Factory design:** data-driven type-specific fields.
5. **No Stripe/S3 keys:** degrade gracefully, like AI, so you can deploy before creating
   any accounts.
6. **CLAUDE.md:** approved as proposed.
7. **Learning docs** for every phase, plus YouTube topics.
8. **Git:** your gmail, set for this repo only.

### 3.3 What Phase 1 built and changed
- **Build:** moved to Spring Boot 4.1.1. Added the Maven wrapper (`mvnw.cmd`), the
  Boot 4 Flyway starter, an explicit Lombok annotation processor, and Mockito loaded as
  a Java agent.
- **Config:** `application.yml` now reads `DB_HOST`, `DB_PORT`, `DB_NAME`,
  `DB_USERNAME` and `DB_PASSWORD` from the environment, with local defaults.
  `application-local.yml` is the default profile. Error messages come from
  `messages.properties`.
- **Schema (V1):**
  - kept the exclusion constraint that blocks double bookings;
  - added a CHECK that only allows known booking statuses;
  - changed `total_amount` to `> 0`;
  - added non-negative checks on bedrooms and bathrooms;
  - added the two missing foreign-key indexes;
  - removed the risky Flyway `baseline-on-migrate` setting.
- **Domain model:** id, version and timestamp fields no longer have setters. Each
  property subtype exposes `typeAttributes()`. `Currency` knows how many decimal places
  it uses.
- **Factory:**
  - creators declare their fields as `AttributeSpec`s, and `TypeAttributes` parses and
    checks them the same way for every type;
  - the factory refuses to start if a type has no creator (this replaced
    `UnsupportedPropertyTypeException`, which was deleted);
  - a new `enforceInvariants` hook fixed the studio bug;
  - prices can't have more decimals than their currency uses.
- **Errors:** `LocalizedException` carries a message key plus arguments.
  `GlobalExceptionHandler` turns it into standard RFC 9457 JSON in the caller's language.
  Errors say which field was wrong.
- **Search:** rebuilt with Spring Data Specifications (see problem 5.5).
- **Tests:**
  - `PropertyFactoryTest` (35 tests)
  - `TypeAttributesTest` (5)
  - `GlobalExceptionHandlerTest` (2)
  - `PersistenceMappingTest` (4, real Postgres)
  - `BookingOverlapConstraintTest` (6, real Postgres)
- **Docs:**
  - `CLAUDE.md` (project rules for future sessions);
  - `README.md` (Windows setup and troubleshooting);
  - `docs/learning/` (stack choices, the Phase 1 guide, this log);
  - `.gitattributes` keeps `mvnw` in Unix line endings for the Linux Docker build.
- **Result:** 52/52 tests passing, the app boots in about 10 s, health `UP`. Committed
  as `739c2d0` (59 files).

---

## 4. Decisions register

Why each non-obvious choice was made. Interviewers love "why".

| # | Decision | Why |
|---|---|---|
| D1 | Spring Boot 4.1 + Spring AI 2.0 (not Boot 3.5) | Spring AI 2 requires Boot 4; Boot 3.5 is end-of-life |
| D2 | AI feature in Java, not Python | one container; stronger interview story |
| D3 | Kept and fixed the existing code | it was solid; rewriting would waste the good parts |
| D4 | Edited V1 instead of adding V2 | V1 had never left your machine; the "never edit" rule starts at the first commit |
| D5 | Data-driven type fields | makes "new type = enum + entity + creator + migration + labels" true for forms and APIs too |
| D6 | Generic "{0} is required" messages with a translated label | far fewer message keys to translate |
| D7 | Factory fails at startup on a missing creator | a mistake should stop the boot, not surprise a user later |
| D8 | Stripe/S3 degrade gracefully | deployable and demo-able before creating accounts |
| D9 | Embeddings at 768 numbers | pgvector's fast index can't handle 3,072 |
| D10 | Database settings as five separate env vars | Render's ready-made URL uses `postgres://`, which Java can't read |
| D11 | Real Postgres (Testcontainers) in tests, never H2 | H2 doesn't understand our constraint, date ranges or pgvector |
| D12 | Search built with Specifications | the "IS NULL OR" query broke on Postgres and was index-unfriendly |
| D13 | App keeps default port 8080 | Render sets `PORT` itself; locally you use 8081 because of Oracle |

---

## 5. Problems we hit and how they were fixed

Each of these is a good "tell me about a problem you solved" story.

| # | Problem | Cause | Fix |
|---|---|---|---|
| 5.1 | Spec's stack wouldn't run | Spring AI 2.0 needs Boot 4 | moved to Boot 4.1 (your decision) |
| 5.2 | Validation messages like `Must be at most {max} characters` would crash | a setting that formats *every* message treats `{max}` as a bad placeholder | removed that setting; rule documented: write apostrophes as `''` in messages with `{0}` |
| 5.3 | Docker engine wouldn't stay up | the tool's shell closed Docker Desktop when the command ended | launched it through Windows Explorer instead |
| 5.4 | Build printed Mockito warnings | newer JDKs dislike agents attaching themselves at runtime | load Mockito as `-javaagent` in the Maven test config |
| 5.5 | Search crashed on real Postgres: `function lower(bytea) does not exist` | a null parameter has no type Postgres can infer, so it guessed "bytes" | search now adds only the filters actually given (Specifications) — also index-friendly |
| 5.6 | App failed: `Port 8080 was already in use` | your Oracle database's listener (TNSLSNR) owns 8080 | run locally with `$env:PORT = "8081"`; added to README troubleshooting |
| 5.7 | Old local database has the draft V1 | it was created before V1 was corrected | you run `docker compose down -v` once (not deleted for you: your data, your call) |
| 5.8 | First commit's subject started with an invisible character | Windows PowerShell 5.1 adds a byte-order mark when piping text | re-made the commit from a file; future commits use files |
| 5.9 | Studio creator changed the caller's request | shortcut to force bedrooms to 0 | `enforceInvariants` hook plus a test proving the request is untouched |
| 5.10 | Cabins would be rejected on Turkish-language machines | `toUpperCase()` follows the computer's language (i → İ) | `toUpperCase(Locale.ROOT)` plus a test run under a Turkish locale |
| 5.11 | Flyway setting could skip V1 entirely | `baseline-on-migrate` assumes existing tables are already at V1 | removed |

---

## 6. YouTube study plan — every phase

**How to use this list**
- Every item is a clickable **YouTube search**. Pick a video that is recent (ideally the
  last two years for anything Spring-specific), from a channel below, and that writes
  real code rather than only drawing slides.
- Spring Boot 3 videos are fine for concepts: Boot 4 mostly renamed packages and
  dependencies.
- Tick the box when you can explain the "you should be able to" line without notes.
- Watch **Foundations** and **Phase 1** now; each later phase's list just before or
  during that phase.

**Channels that cover these topics well:** Amigoscode · Java Brains · Dan Vega ·
Telusko · Marco Codes · SpringDeveloper (official) · Hussein Nasser (databases, backend) ·
ByteByteGo (system-design concepts) · Fireship (quick overviews) · TechWorld with Nana
(Docker/DevOps) · freeCodeCamp (long full courses).

### Foundations (fill any gaps first)
- [ ] [java 21 new features](https://www.youtube.com/results?search_query=java+21+new+features) — records, pattern matching, text blocks, all used in this code
- [ ] [java generics tutorial](https://www.youtube.com/results?search_query=java+generics+tutorial) — read `AbstractPropertyCreator<T extends Property>`
- [ ] [java streams tutorial](https://www.youtube.com/results?search_query=java+streams+lambdas+tutorial) — read `stream().map(...).toList()`
- [ ] [maven tutorial for beginners](https://www.youtube.com/results?search_query=maven+tutorial+for+beginners) — what `pom.xml`, dependencies and scopes are
- [ ] [git tutorial for beginners](https://www.youtube.com/results?search_query=git+tutorial+for+beginners) — commit, branch, push
- [ ] [docker tutorial for beginners](https://www.youtube.com/results?search_query=docker+tutorial+for+beginners) — images vs containers, volumes, ports
- [ ] [docker compose tutorial](https://www.youtube.com/results?search_query=docker+compose+tutorial) — read our `docker-compose.yml`
- [ ] [postgresql tutorial for beginners](https://www.youtube.com/results?search_query=postgresql+tutorial+for+beginners) — tables, keys, joins
- [ ] [rest api explained](https://www.youtube.com/results?search_query=rest+api+explained) — HTTP methods, status codes

### Phase 1 — Foundation
- [ ] [spring boot tutorial for beginners](https://www.youtube.com/results?search_query=spring+boot+tutorial+for+beginners) — what Spring Boot does for you
- [ ] [spring dependency injection explained](https://www.youtube.com/results?search_query=spring+dependency+injection+explained) — why `PropertyFactory` receives `List<PropertyCreator>`
- [ ] [spring boot auto configuration explained](https://www.youtube.com/results?search_query=spring+boot+auto+configuration+explained) — how adding a dependency configures a bean
- [ ] [spring boot profiles application yml](https://www.youtube.com/results?search_query=spring+boot+profiles+application.yml) — `local` vs `render`, `${ENV:default}`
- [ ] [spring data jpa tutorial](https://www.youtube.com/results?search_query=spring+data+jpa+tutorial) — entities, repositories, derived queries
- [ ] [hibernate N+1 problem](https://www.youtube.com/results?search_query=hibernate+N%2B1+problem) — lazy loading and why `open-in-view` is off
- [ ] [open session in view anti pattern](https://www.youtube.com/results?search_query=open+session+in+view+anti+pattern) — the trade-off in one sentence
- [ ] [hibernate inheritance mapping single table](https://www.youtube.com/results?search_query=hibernate+inheritance+mapping+single+table+joined) — SINGLE_TABLE vs JOINED vs TABLE_PER_CLASS
- [ ] [flyway spring boot tutorial](https://www.youtube.com/results?search_query=flyway+spring+boot+tutorial) — why migrations are never edited after commit
- [ ] [how database indexes work](https://www.youtube.com/results?search_query=how+database+indexes+work+b-tree) — B-trees; why foreign keys need indexes
- [ ] [postgresql exclusion constraint](https://www.youtube.com/results?search_query=postgresql+exclusion+constraint) — how the double-booking rule works
- [ ] [postgresql range types](https://www.youtube.com/results?search_query=postgresql+range+types+daterange) — what `[)` means
- [ ] [postgresql gist index](https://www.youtube.com/results?search_query=postgresql+gist+index) — why overlap needs GiST, not B-tree
- [ ] [bigdecimal vs double java](https://www.youtube.com/results?search_query=bigdecimal+vs+double+java+money) — why `0.1 + 0.2` isn't `0.3`; `compareTo` vs `equals`
- [ ] [factory design pattern java](https://www.youtube.com/results?search_query=factory+design+pattern+java) — our factory as a registry
- [ ] [template method design pattern](https://www.youtube.com/results?search_query=template+method+design+pattern+java) — the `final create()` skeleton
- [ ] [solid principles java](https://www.youtube.com/results?search_query=solid+principles+java) — especially Open/Closed
- [ ] [spring boot validation tutorial](https://www.youtube.com/results?search_query=spring+boot+bean+validation+tutorial) — `@NotBlank`, `@Size`, custom messages
- [ ] [spring boot internationalization](https://www.youtube.com/results?search_query=spring+boot+internationalization+messages.properties) — message keys and locales
- [ ] [spring boot problem details](https://www.youtube.com/results?search_query=spring+boot+problem+details+rfc+7807) — standard error JSON (RFC 7807 → 9457)
- [ ] [spring data jpa specifications](https://www.youtube.com/results?search_query=spring+data+jpa+specifications) — building queries from optional filters
- [ ] [junit 5 tutorial](https://www.youtube.com/results?search_query=junit+5+tutorial) — `@Test`, `@Nested`, `@DisplayName`
- [ ] [assertj tutorial](https://www.youtube.com/results?search_query=assertj+tutorial) — `assertThat(...)` style
- [ ] [testcontainers spring boot](https://www.youtube.com/results?search_query=testcontainers+spring+boot) — real Postgres in tests; why not H2

### Phase 2 — Caching
- [ ] [spring boot caching tutorial](https://www.youtube.com/results?search_query=spring+boot+caching+tutorial) — `@Cacheable`, `@CacheEvict`
- [ ] [redis crash course](https://www.youtube.com/results?search_query=redis+crash+course) — what Redis is and why it's fast
- [ ] [caffeine cache spring boot](https://www.youtube.com/results?search_query=caffeine+cache+spring+boot) — in-process cache, size and expiry limits
- [ ] [cache aside pattern](https://www.youtube.com/results?search_query=cache+aside+pattern) — read-through vs write-through vs cache-aside
- [ ] [cache invalidation strategies](https://www.youtube.com/results?search_query=cache+invalidation+strategies) — why "the hardest problem in computer science"
- [ ] [redis pub sub](https://www.youtube.com/results?search_query=redis+pub+sub+tutorial) — how multiple servers could clear local caches together

### Phase 3 — Bookings & concurrency
- [ ] [database transactions acid explained](https://www.youtube.com/results?search_query=database+transactions+acid+explained) — atomicity, isolation
- [ ] [transaction isolation levels](https://www.youtube.com/results?search_query=transaction+isolation+levels+explained) — read committed, repeatable read, serializable
- [ ] [optimistic vs pessimistic locking](https://www.youtube.com/results?search_query=optimistic+vs+pessimistic+locking) — why optimistic wins for rare conflicts
- [ ] [spring transactional explained](https://www.youtube.com/results?search_query=spring+transactional+annotation+explained) — proxies, rollback rules
- [ ] [spring transactional pitfalls](https://www.youtube.com/results?search_query=spring+transactional+pitfalls+self+invocation) — self-invocation, why retry must wrap the transaction
- [ ] [race condition explained](https://www.youtube.com/results?search_query=race+condition+explained+web+application) — the double-booking race
- [ ] [java concurrency countdownlatch executorservice](https://www.youtube.com/results?search_query=java+concurrency+countdownlatch+executorservice) — how the two-thread test works
- [ ] [retry exponential backoff](https://www.youtube.com/results?search_query=retry+with+exponential+backoff) — why retries wait longer each time

### Phase 4 — Auditing, logging, scheduling
- [ ] [hibernate envers tutorial](https://www.youtube.com/results?search_query=hibernate+envers+tutorial) — audit tables and revisions
- [ ] [spring boot scheduled tasks](https://www.youtube.com/results?search_query=spring+boot+scheduled+tasks+cron) — `@Scheduled`
- [ ] [cron expression explained](https://www.youtube.com/results?search_query=cron+expression+explained) — read `0 0 3 * * *`
- [ ] [structured logging spring boot](https://www.youtube.com/results?search_query=structured+logging+spring+boot) — why key=value/JSON logs
- [ ] [slf4j logback tutorial](https://www.youtube.com/results?search_query=slf4j+logback+tutorial) — log levels
- [ ] [mdc logging java](https://www.youtube.com/results?search_query=mdc+logging+java) — attaching a request id to every log line

### Phase 5 — Payments & money
- [ ] [stripe payment intents tutorial](https://www.youtube.com/results?search_query=stripe+payment+intents+tutorial) — the payment lifecycle
- [ ] [stripe test cards](https://www.youtube.com/results?search_query=stripe+test+mode+test+cards) — simulating success and failure
- [ ] [idempotency key payments](https://www.youtube.com/results?search_query=idempotency+key+payments) — never charging twice
- [ ] [floating point precision explained](https://www.youtube.com/results?search_query=floating+point+precision+explained) — the theory behind BigDecimal
- [ ] [saga pattern compensating transaction](https://www.youtube.com/results?search_query=saga+pattern+compensating+transaction) — undoing a booking when payment fails
- [ ] [webhooks explained](https://www.youtube.com/results?search_query=webhooks+explained) — how Stripe tells your app what happened

### Phase 6 — AI / RAG
- [ ] [what are embeddings](https://www.youtube.com/results?search_query=what+are+embeddings+machine+learning) — text as a list of numbers that captures meaning
- [ ] [cosine similarity explained](https://www.youtube.com/results?search_query=cosine+similarity+explained) — how "similar meaning" is measured
- [ ] [vector database explained](https://www.youtube.com/results?search_query=vector+database+explained) — why pgvector
- [ ] [RAG explained](https://www.youtube.com/results?search_query=retrieval+augmented+generation+explained) — retrieve, then generate
- [ ] [pgvector tutorial](https://www.youtube.com/results?search_query=pgvector+tutorial) — vector columns and similarity search in SQL
- [ ] [HNSW index explained](https://www.youtube.com/results?search_query=HNSW+index+explained) — how fast approximate search works
- [ ] [hybrid search vector keyword](https://www.youtube.com/results?search_query=hybrid+search+vector+keyword) — combining meaning with real filters
- [ ] [LLM hallucination grounding](https://www.youtube.com/results?search_query=llm+hallucination+grounding) — why we only recommend retrieved IDs
- [ ] [spring ai tutorial](https://www.youtube.com/results?search_query=spring+ai+tutorial) — ChatClient, EmbeddingModel, VectorStore
- [ ] [gemini api tutorial](https://www.youtube.com/results?search_query=gemini+api+tutorial+google+ai+studio) — getting and using a free key
- [ ] [prompt engineering basics](https://www.youtube.com/results?search_query=prompt+engineering+basics) — instructions, context, output format
- [ ] [rate limiting 429 retry](https://www.youtube.com/results?search_query=api+rate+limiting+429+retry) — living within the free tier
- Optional, since the concepts carry over: [RAG python tutorial](https://www.youtube.com/results?search_query=rag+tutorial+python) — the same ideas in the language most tutorials use

### Phase 7 — S3, i18n, GraphQL, OpenAPI, Postman
- [ ] [aws s3 tutorial for beginners](https://www.youtube.com/results?search_query=aws+s3+tutorial+for+beginners) — buckets, objects, keys
- [ ] [aws iam access keys](https://www.youtube.com/results?search_query=aws+iam+user+access+keys+tutorial) — least-privilege credentials
- [ ] [spring boot file upload](https://www.youtube.com/results?search_query=spring+boot+file+upload+multipart) — multipart uploads, size limits
- [ ] [file type magic numbers](https://www.youtube.com/results?search_query=file+signature+magic+numbers) — why we check file bytes, not just the name
- [ ] [spring boot i18n locale resolver](https://www.youtube.com/results?search_query=spring+boot+i18n+locale+resolver) — `Accept-Language` and `?lang=`
- [ ] [graphql vs rest](https://www.youtube.com/results?search_query=graphql+vs+rest) — when each fits
- [ ] [spring graphql tutorial](https://www.youtube.com/results?search_query=spring+for+graphql+tutorial) — `@QueryMapping`, `@MutationMapping`
- [ ] [graphql N+1 batch mapping](https://www.youtube.com/results?search_query=spring+graphql+batchmapping+N%2B1) — `@BatchMapping`
- [ ] [springdoc openapi swagger](https://www.youtube.com/results?search_query=springdoc+openapi+swagger+spring+boot) — `/swagger-ui.html`
- [ ] [postman tutorial](https://www.youtube.com/results?search_query=postman+tutorial+for+beginners) — collections and environments

### Phase 8 — Frontend
- [ ] [thymeleaf tutorial](https://www.youtube.com/results?search_query=thymeleaf+spring+boot+tutorial) — templates, `th:each`, `th:if`
- [ ] [thymeleaf layout fragments](https://www.youtube.com/results?search_query=thymeleaf+fragments+layout) — shared navbar/layout
- [ ] [thymeleaf form validation](https://www.youtube.com/results?search_query=thymeleaf+form+validation+errors) — showing errors on the form
- [ ] [bootstrap 5 tutorial](https://www.youtube.com/results?search_query=bootstrap+5+tutorial) — grid, cards, forms
- [ ] [http session spring boot](https://www.youtube.com/results?search_query=http+session+spring+boot) — how the "sign in as" switcher remembers you

### Phase 9 — Ship
- [ ] [dockerfile spring boot multi stage](https://www.youtube.com/results?search_query=dockerfile+spring+boot+multi+stage) — build stage vs runtime stage
- [ ] [spring boot layered jar docker](https://www.youtube.com/results?search_query=spring+boot+layered+jar+docker) — faster rebuilds
- [ ] [jvm memory in containers](https://www.youtube.com/results?search_query=jvm+memory+docker+containers) — why `MaxRAMPercentage`
- [ ] [deploy spring boot to render](https://www.youtube.com/results?search_query=deploy+spring+boot+render.com) — the whole flow
- [ ] [render blueprint render yaml](https://www.youtube.com/results?search_query=render+blueprint+render.yaml) — infrastructure as code
- [ ] [twelve factor app](https://www.youtube.com/results?search_query=twelve+factor+app+explained) — why config lives in environment variables

### Interview preparation (any time)
- [ ] [how to explain your project in an interview](https://www.youtube.com/results?search_query=how+to+explain+your+project+in+interview+software+engineer)
- [ ] [STAR method behavioral interview](https://www.youtube.com/results?search_query=STAR+method+behavioral+interview)
- [ ] [java backend interview questions](https://www.youtube.com/results?search_query=java+spring+boot+interview+questions)
- [ ] [system design basics](https://www.youtube.com/results?search_query=system+design+interview+basics) — caching, databases, scaling vocabulary

---

## 7. Accounts you'll need, and when

None are needed yet, and each is optional because the app works without it.

| Phase | Account | Where | Cost |
|---|---|---|---|
| 5 | Stripe (test mode keys) | stripe.com | free |
| 6 | Gemini API key | aistudio.google.com | free tier, no card |
| 7 | AWS (S3 bucket + access keys) | aws.amazon.com | free tier, but signup needs a card |
| 9 | GitHub (for Render to deploy from) | github.com | free |
| 9 | Render | render.com (sign in with GitHub) | free tier |

---

## 8. Command cheat sheet (PowerShell)

Run these from `C:\dev\rentalhub`.

| What | Command |
|---|---|
| Start Postgres + Redis | `docker compose up -d` |
| Check they're healthy | `docker compose ps` |
| Wipe the local database | `docker compose down -v` |
| Run all tests (Docker must be running) | `.\mvnw.cmd test` |
| Run one test class | `.\mvnw.cmd test "-Dtest=PropertyFactoryTest"` |
| Use port 8081 (Oracle has 8080) | `$env:PORT = "8081"` |
| Start the app | `.\mvnw.cmd spring-boot:run` |
| Health check | `curl.exe http://localhost:8081/actuator/health` |
| Look inside the database | `docker exec -it rentalhub-postgres psql -U rentalhub -d rentalhub` |
| List tables (inside psql) | `\dt` |
| See what's using a port | `Get-NetTCPConnection -LocalPort 8080 -State Listen` |
| Git history | `git log --oneline` |
