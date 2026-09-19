# RentalHub — Project log & study plan

Your single place to read **everything that has happened on this project**, **what you
need to do**, and **every YouTube topic to study**, phase by phase. A new entry is
added at the end of every phase.

**Contents**
1. [Where the project stands](#1-where-the-project-stands)
2. [Your to-do list](#2-your-to-do-list)
3. [Log](#3-log) (newest first)
4. [Decisions register](#4-decisions-register)
5. [Problems we hit and how they were fixed](#5-problems-we-hit-and-how-they-were-fixed)
6. [YouTube study plan — every phase](#6-youtube-study-plan--every-phase)
7. [Accounts you'll need, and when](#7-accounts-youll-need-and-when)
8. [Command cheat sheet (PowerShell)](#8-command-cheat-sheet-powershell)

---

## 1. Where the project stands

| Phase | What | Status | Commit |
|---|---|---|---|
| 1 | Foundation | ✅ done | `29081ff` |
| 2 | Caching (Caffeine + Redis) + listings REST API | ✅ done | `30f5d45` (+ docs `498df7c`), merged via PR #1 (`0ce70c6`) |
| 3 | Bookings & concurrency | ✅ done | `6c20a81`, merged via PR #2 (`5305753`) |
| 4 | Auditing, logging & scheduling (+ reviews) | ✅ done | `1fbd71a`, merged via PR #3 (`239a41f`) |
| 5 | Payments & money (+ currencies) | ✅ done | `95a52da`, merged via PR #4 (`744ef1c`) |
| 6 | AI / RAG (+ favourites) | ✅ done | `2471607`, merged via PR #5 (`7141989`) |
| 7 | S3 photos, i18n, GraphQL, OpenAPI, Postman | ✅ done | `ae3a50b`, merged via PR #6 (`5a7ba46`) |
| 8 | Frontend (Thymeleaf pages) | ✅ done | `01c7b9b`, PR #7 open (not merged yet) |
| 9 | Ship: seeder, Docker, Render | ✅ done | `Phase 9: …` on branch `phase-9-ship` (built on `phase-8-frontend`) |

**Numbers after Phase 9 — all nine phases built:** 401 automated tests (225 unit, 176
integration against real Postgres, Redis and MinIO), all passing. Twenty-three REST endpoints, a
GraphQL API and the website, in three languages, and now shippable:
- **It never opens blank:** an empty database gets a demo world (8 users, 16 listings of every
  type in 5 currencies, stays, reviews, favourites).
- **A container image** (`Dockerfile`), measured under Render's free limits: starts in about
  2.5 minutes on a tenth of a CPU, about 360 MB of its 512 MB after traffic.
- **A Render Blueprint** (`render.yaml`): the app, Postgres with pgvector and a Key Value store,
  wired together, free plans. **Not deployed yet** — that needs your account (hands-on Part 76).
- **The README** has the architecture diagram, every environment variable, both walkthroughs,
  the trade-offs and a CV paragraph.

Commit ids changed on 15 Sep 2026, when the history was rewritten (see the log). Older
notes may still mention the previous ids.

---

## 2. Your to-do list

### Now
- [ ] **Merge the two pull requests, in order.** PR #7 (Phase 8) first; then I open Phase 9's
      (its branch is built on Phase 8's). Tell me when, or merge #7 on GitHub yourself.
- [ ] **Deploy to Render** (hands-on Part 76 and the README's *Deploy to Render*): a free account
      at render.com (sign up with GitHub), then **New → Blueprint**. It deploys from `main`, so
      after the merges. About 15 minutes, most of it waiting. Tell me what you see if anything
      differs from the guide: it is the one part I couldn't run.
- [ ] Work through the **[hands-on guide](hands-on-guide.md)**, Parts 71–75 (about 30 minutes):
  - the demo world on an empty database, and the seeder leaving a full one alone;
  - the health check with Redis stopped (DEGRADED, still 200);
  - building the image, and running it with Render's 512 MB and 0.1 CPU;
  - the HTTPS addresses and Secure cookie behind a pretend proxy.
  - Parts 2–70 now start with `$env:DEMO_DATA_ENABLED = "false"` (Part 2), so their ids still hold.
- [ ] **Look at the pages yourself** (from Phase 8: I never saw them rendered).
- [ ] **Your CV:** the README ends with a project paragraph and three bullets. Put your live
      Render address in it once deployed.
- [ ] **Optional keys, on Render's Environment page:** a Gemini key (free, no card) to switch on
      search by meaning; AWS for photos; a Stripe test key if you ever get one.
- [ ] **Remember:** the free Render database expires 30 days after you create it (14 days' grace).

### Reading
- [ ] [09 — Shipping: demo data, a container image, and Render](09-ship.md), and answer its
      interview questions out loud. "How do you size the JVM for a container?" and "Why a
      multi-stage Docker build?" are the ones to get right.
- [ ] The Phase 9 videos in [section 6](#6-youtube-study-plan--every-phase)
- [ ] If not done yet: [08 — The web pages](08-frontend.md) and its videos.
- [ ] Then all nine docs once more, end to end, before interviews.

### Still open from Phase 1
- [ ] If not done yet: `docker compose down -v` once (old draft V1 in your local volume),
      reading 00 and 01, opening the project in IntelliJ.

---

## 3. Log

### Session 9 — Phase 9: demo data, the container image, Render (19 Sep 2026)

**Before it:** Phase 8 pushed and opened as PR #7 (not merged: you didn't ask). Phase 9 is on its
own branch, `phase-9-ship`, built on top of `phase-8-frontend`.

**Built**
- **Demo seeder** (`bootstrap/DemoDataSeeder`, data in `resources/demo/demo-data.json`):
  - runs at start-up only when the `users` table is empty; `DEMO_DATA_ENABLED=false` switches it
    off; failures are logged (`demo.failed`), never stop the app;
  - one transaction, all or nothing; recorded in the history as `system:demo-seeder`;
  - 8 users (4 hosts, 4 guests), 16 listings (all four types, INR/USD/EUR/GBP/AED), 13 stays
    (9 finished, 4 upcoming), 8 reviews (one in Hindi, two in Spanish), 8 favourites;
  - dates in days from today; listings, reviews and favourites through their services; bookings
    recorded directly, already paid through the simulator (past dates, no payment at start-up).
- **Health:** `cache/SharedCacheHealthIndicator` replaces Spring Boot's Redis check: Redis down
  is **DEGRADED** (HTTP 200), not DOWN. Closes the Phase 2 open item.
- **`Dockerfile`** (+ `.dockerignore`): two stages (Temurin 21 JDK Alpine → JRE Alpine), the
  dependency download in its own layer, the jar extracted into layers, non-root user, `JAVA_OPTS`
  for a 512 MB container.
- **`render.yaml`**: web service (Docker, free, Singapore, health check `/actuator/health`,
  deploy on commit), Postgres 16 (free), Key Value (free); `DB_*` and `REDIS_URL` wired in by the
  Blueprint; the keys `sync: false`.
- **`application-render.yml`**: forwarded headers believed, Secure session cookie, 40 Tomcat
  threads, 5 database connections, 10 s connection timeout.
- **My bookings** offers "Cancel" only when cancelling would be accepted (finished demo stays
  showed the button): `BookingRules.cancellableToday`.
- **Tests:** 12 more, 401 in all (225 unit, 176 integration): `DemoDataSeederTest`,
  `SharedCacheHealthIndicatorTest`, `HealthApiTest`, a rule test and a page test for cancelling.
  Every test context switches the seeder off.
- **Docs:** [09 — Shipping](09-ship.md); the README rewritten to the spec's deliverables
  (architecture diagram, environment-variable table, local and Render walkthroughs, trade-offs,
  CV paragraph); hands-on Parts 71–76, and `DEMO_DATA_ENABLED=false` in Part 2 for the earlier
  parts; CLAUDE.md; the learning README and glossary.

**Judgement calls (explained before coding):** D91–D99 below. The notable ones:
- free Render plans, with their limits written down (sleep, 30-day database);
- Singapore, the closest region to India;
- tests skipped inside the Docker build (they need Docker themselves);
- seeded bookings recorded as already paid through the simulator;
- Redis down = DEGRADED, not DOWN;
- no demo photos (no image storage by default).

**Research, from Render's documentation (19 Sep 2026):** Blueprint syntax (`keyvalue` replaced
`redis`; plan names such as `free`, `0.5c-512mb`), Postgres support for `vector`, `btree_gist` and
`hstore`, the free limits (512 MB and 0.1 CPU; sleep after 15 minutes; the database expires after
30 days), the automatically set variables (`PORT`=10000, `RENDER_*`), and the health-check rules
(2xx/3xx within 5 s; live once passing, within 15 minutes; restart after 60 s of failures).

**Verified by hand** on 19 Sep 2026, with throwaway containers (a Docker network of their own),
never the project's:
- the image built (198 s the first time, 20 s after a code change), 195 MB to download, layers
  135 MB of libraries and 475 kB of our code, running as `uid=100(rentalhub)`;
- run with `--memory=512m --cpus=0.1`, `PORT=10000` and the render profile: started in 148 s,
  seeded in 20 s, 339 MB after start and 363 MB after every page three times; warm pages in
  0.1–0.3 s; JSON logs;
- health UP; Redis stopped → DEGRADED with 200 and the pages still working; Redis back → UP;
  Postgres stopped → DOWN with 503, after 30 s (hence the 10 s timeout);
- behind a pretend proxy: `Location: https://rentalhub.onrender.com/api/properties/17`, and
  `Set-Cookie: …; Secure; HttpOnly; SameSite=Lax`;
- the local run (`spring-boot:run`): `demo.seeded users=8 listings=16 bookings=13 reviews=8
  favourites=8`, then `demo.skipped reason=database not empty` on restart and
  `reason=switched off` with the variable set; the README's booking example (27000.00 INR, then
  409); Redis stopped and back.
- **Not verified:** the deployment itself (it needs your Render account), whether Render's form
  accepts an empty value for a `sync: false` key, the dashboard's exact menu labels, and Part 74
  on `docker compose`'s own network (mine used a network of its own).

**Result:** 401/401 tests passing.

### Session 8 — Phase 8: the web pages (19 Sep 2026)

**Before it:** Phase 7 pushed, PR #6 opened and merged into `main` as `5a7ba46`, at your
request; the remote branch deleted.

**Built**
- **Thymeleaf pages** (`web/mvc/`, `templates/`), Bootstrap 5.3.8 from jsDelivr, pinned, with
  SRI hashes:
  - `/` search (city, guests, price ceiling, currency) with listing cards, 12 a page, the
    filters kept in the page links; ratings for the whole page in one query;
  - `/listings/{id}`: photo carousel, details (the type's own fields, generically), reviews and
    a review form, booking with Stripe's three test payment methods, prices in another
    currency, save/unsave; for the host, photo upload and removal;
  - `/host/listings/new`: the host's form, one fieldset per property type generated from the
    creators' `AttributeSpec`s, a small script that swaps them, and a no-JavaScript fallback;
  - `/bookings`: My bookings, totals in another currency, cancel with refund;
  - `/recommendations`: the Phase 6 answer and the listings it is about, with the AI-off notice;
  - error pages (404, 400, 403, 405, 413, 500) in the site's frame, in the reader's language.
- **"Sign in as"** (`web/DemoSession`, `web/mvc/SessionController`): a demo user kept in the
  session; a new session id on every switch; `returnTo` limited to paths on this site; the
  audit actor taken from the session when there is no header. Session cookie HttpOnly,
  SameSite=Lax, never in the URL.
- **Forms:** every POST redirects, refused ones too (the form and its errors carried as flash
  attributes); service refusals shown beside their field (`FormErrors`); one-line notices after
  each action (`PageNotices`); dates bound the ISO way for `<input type="date">`.
- **135 new message keys** in all three languages; the language menu names each language in
  itself.
- **Tests:** 33 more, 389 in all (222 unit, 167 integration).
  - Page tests (`web/mvc/`): `HomePageTest`, `ListingPageTest`, `HostListingPageTest`,
    `RecommendationPageTest`, `ListingPhotosPageTest` (against MinIO), `PageModelAdviceTest`.
    They post forms and follow redirects the way a browser does, and fail on any `??key??`.
  - `MessagesFilesTest` now checks every key the templates use, including those built from enum
    values; `PropertyFactoryTest` fails if a template or script names a property type or one of
    its attributes.
- **Docs:** [08 — The web pages](08-frontend.md); hands-on guide Parts 63–70; README, CLAUDE.md
  and the learning README.

**Judgement calls (explained before coding):** D79–D90 below. The notable ones:
- refused forms redirect too, not only successful ones (D82);
- no CSRF tokens: SameSite=Lax cookies instead, since Spring Security is ruled out (D81);
- a select of Stripe test payment methods instead of a card field (D85);
- Boot's own error page drawn with the site's template and navbar (D86).

**Verified by hand** on 19 Sep 2026, against throwaway containers (Postgres, Redis and MinIO on
other ports), never the project's own, with the app on port 8092:
- in the in-app browser: search and USD conversion; sign in as (no `;jsessionid`, same page);
  a refused booking at `/listings/1#book` with the error beside Check-out and the dates kept;
  Spanish from the language menu; booking, My bookings in USD, cancel with refund; the audit
  rows (`user:2`); a declined card; save; Ask for ideas (AI off) and a Hindi statistic; as the
  host, the cabin form (type switching by the script, two refusals one after the other, then
  published); an upload with no storage; 6 MB and 20 MB uploads; Boot's 404 page in Spanish
  with the navbar;
- with curl: the session cookie's flags, the open-redirect guard, the 405/400/404 pages
  (and the 405 page in Hindi, as a browser asks for it), and, with MinIO, an upload and a
  removal posted to the page's own form address, the photo then on the card and in the carousel
  and served with its one-year cache header;
- layout by measurement: no sideways scrolling at 375 px on all seven pages, Bootstrap's CSS and
  JavaScript loaded (so the SRI hashes are right).
- **Not seen:** how the pages *look*. The browser pane was hidden, so every screenshot timed out
  and the forms were submitted by script rather than clicked. The page with JavaScript switched
  off was checked by a test, not in a browser.

**Result:** 389/389 tests passing (clean build).

### Session 7 — Phase 7: photos, languages, GraphQL, OpenAPI, Postman (18–19 Sep 2026)

**Before it:** PR #5 (Phase 6) merged into `main` as `7141989`, at your request.

**Built**
- **Listing photos on S3** (`storage/`, `service/ListingImageService`):
  - JPEG, PNG or WebP only, recognised by the file's first bytes; a declared type that
    contradicts them is refused;
  - at most 5 MB (Spring's multipart limit and the service's own check, both translated) and
    10 per listing;
  - random keys (`listings/<id>/<uuid>.<ext>`), never the uploaded name;
  - served back by the app at `/images/listings/…`, cacheable for a year; the bucket stays
    private;
  - upload order: checks, then the file, then the row, with the file deleted again if the row
    fails; removal order: the row, then the file after the commit. Deleting a listing deletes
    its files too. Two uploads to one listing take turns (the Phase 3 version lock);
  - no `S3_BUCKET`: every upload is a 503 saying so, and nothing else changes. `S3_ENDPOINT` and
    `S3_PATH_STYLE` let any S3-compatible server stand in, such as MinIO, which
    `docker compose --profile photos up -d` now starts.
- **Hindi and Spanish**, every key (`messages_hi.properties`, `messages_es.properties`):
  - the language: `?lang=` (remembered in a cookie), then the cookie, then `Accept-Language`,
    then English; only the three are ever chosen;
  - Spring's own errors are translated too (`problemDetail.*` keys), with translated titles, and
    the 404/405 that happen before any controller is reached now get the same problem-detail
    shape (`ApiRoutingErrorHandler`); anything unexpected is a translated 500 with the request
    id, never a stack trace.
- **GraphQL** (`resources/graphql/schema.graphqls`, `web/graphql/`): search and detail queries,
  `createBooking`, `cancelBooking` and `createReview`; `Decimal`, `Date` and `DateTime` scalars
  (money as a string); `@BatchMapping` for hosts and photos; translated errors with
  classifications; GraphiQL at `/graphiql`.
- **OpenAPI** (springdoc 3.1.1): `@Tag`, `@Operation` and example payloads on all 23 endpoints,
  Swagger UI at `/swagger-ui.html`.
- **Postman**: `postman/RentalHub.postman_collection.json` (33 requests: every REST endpoint plus
  GraphQL, in an order that runs top to bottom on a fresh database) and
  `RentalHub.local.postman_environment.json` (`baseUrl`, `hostId`, `guestId`).
- **Cache key prefix `v3`**: a listing's photos gained their id in the cached record.
- **Tests:** 49 more, 356 in all (217 unit, 139 integration).
  - Unit: `ImageFormatTest`, `ListingImageServiceTest`, `MessagesFilesTest`.
  - Integration: `ListingImageApiTest` (against MinIO in Docker), `ImagesSwitchedOffTest`,
    `LanguageApiTest`, `FrameworkErrorsApiTest`, `GraphQlApiTest` (including the SQL-statement
    count), `OpenApiDocumentationTest`, `PostmanCollectionTest`.
  - A second shared test context, `ConnectedIntegrationTest`, has every optional service
    switched on without any account: fake AI models and MinIO. `AiRecommendationTest` moved into
    it.
- **Docs:** [07 — Photos on S3, three languages, GraphQL, OpenAPI and Postman](07-extras.md);
  hands-on guide Parts 53–62 and updates to Parts 9, 21 and the cache-key parts; photo and
  GraphQL samples; README, CLAUDE.md, the samples README and 00.

**Judgement calls (explained before coding):** D69–D78 below. The notable ones:
- no S3 means a clean 503, as the spec says, not the local-disk fallback I had floated after
  Phase 6 (Render wipes its disk on every deploy); MinIO covers trying it locally;
- photos served through the app rather than from a public bucket or with pre-signed links;
- a 10-photo limit per listing (the spec only asks for a size cap);
- `?lang=` remembered in a cookie, not the session;
- a `cancelBooking` mutation beside the two the spec names;
- tests that fail the build when an endpoint is missing from Swagger or Postman.

**Verified by hand** on 18–19 Sep 2026, against throwaway containers (Postgres, Redis and MinIO
on other ports), never the project's own:
- with no storage: the startup line and the 503 in English and Hindi;
- the language rules: Accept-Language, `?lang=` and its cookie, French falling back, Spring's
  own errors, bean validation and the AI statistics in Spanish;
- with MinIO: upload, the listing showing it, the bytes served back identical, the refusals
  (renamed PDF, PNG claiming to be JPEG, a 6 MB file answered 413 by a real Tomcat, a guest),
  a WebP accepted as `application/octet-stream`, removal, and deleting a listing deleting its
  file;
- GraphQL (Hindi and Spanish), GraphiQL, Swagger UI and the 23 documented operations;
- the Parts 53–61 commands run once more in PowerShell itself, which is where the
  `[Console]::OutputEncoding` line for Hindi came from;
- the Postman collection, replayed top to bottom by a small script that applies its requests
  and checks in order: 33 of 33. **Not tried in the Postman app itself**, so its
  working-directory setting for the upload is from Postman's documentation, not seen.

**Result:** 356/356 tests passing.

### Session 6 — Phase 6: AI, embeddings and RAG (18 Sep 2026)

**Built**
- **The vector index.** V4 adds `vector_store` (768-number embeddings, HNSW index on cosine
  distance) and `listing_embeddings` (listing → document, plus a SHA-256 content hash).
  Flyway owns both; Spring AI's own schema creation is off.
- **Indexing.** Each listing becomes one piece of text (type, city, title, description,
  capacity, price, and its type-specific attributes through `typeAttributes()`, so a new
  property type needs no change here). It is re-embedded only when that text changes, after
  the commit of the change, on the same event the caches use. A deleted or deactivated
  listing is removed from the index. A job every two minutes embeds, in batches of 20,
  anything that was missed — including everything created before a key was set.
- **Favourites API** (`PUT`/`DELETE /api/properties/{id}/favorite`, `GET /api/favorites`),
  because the preference profile and the taste vector are built from them.
- **Preference profile**, entirely from SQL: the cities they save, the price band (converted
  into the currency most of their favourites use), the party size they book for, the words
  that keep coming up, and their review count and average rating. The model sees only the
  one-line summary, never the rows.
- **Understanding the question with rules**, not an LLM: city (matched against cities that
  have listings, longest match wins), budget with symbol/code/word, party size ("for 3
  nights" is not a party size), "like my favourites", and statistics-vs-recommendation.
- **Hybrid search.** Vector similarity proposes up to 50 candidates; SQL over the live rows
  keeps only those still active, in the right city, within the per-currency ceiling
  (Phase 5's `PriceCeilings`), big enough, and not the asker's own; ranking is similarity
  plus three small profile boosts; the best five are returned.
- **The taste vector.** "Like my favourites" is the average of the saved listings'
  embeddings — `avg(embedding)` inside Postgres, no model call, and it never suggests back
  the listings they already saved.
- **Statistics mode.** "How much have I spent?", "how many have I saved?", "what do I
  usually pay?" are answered in SQL, in the caller's language, with money summed per
  currency and only totalled when every rate is there.
- **Grounded answers.** One Gemini call, with the candidate listings in the prompt as `[id]`.
  Afterwards the answer is checked: an id that was not offered is cut out, and an answer
  whose ids were all invented is thrown away and replaced by one the app writes.
- **`GET /api/recommendations?q=…&currency=…`** always answers 200, with `aiUsed` and
  `semantic` saying how much of the AI was actually used.
- **Degrading.** With no key, an `EnvironmentPostProcessor` switches off the chat model, the
  embedding model and the vector store before any bean exists, so the app starts normally.
  A wrong key, an empty index or an exhausted quota each fall back one step further, with a
  translated sentence saying which.
- **Tests:** 38 more, 307 in all (205 unit, 102 integration).
  - Unit: `QueryParserTest`, `ListingEmbeddingServiceTest`, `StatsServiceTest`,
    `RecommendationServiceTest` (the grounding check, with a model that misbehaves on
    purpose), `EmbeddingIndexJobScheduleTest`.
  - Integration: `AiSwitchedOffTest` (the whole app with no key — the spec's requirement, as
    a test), `AiRecommendationTest` (its own context: fake models, but the real
    PgVectorStore, the real pgvector container and the real SQL), `FavoriteApiTest`.
  - **No test needs a Gemini key**, and none calls the network.
- **Docs:** [06 — AI: embeddings, RAG and search that understands](06-ai-rag.md); hands-on
  guide Parts 46–52; three listing samples worded to be told apart by meaning; README,
  CLAUDE.md and the samples README.

**Judgement calls (explained before coding):** D56–D68 below. The notable ones:
- rules, not an LLM, for reading the question and routing the intent;
- statistics answered by SQL, never by the model;
- the taste vector computed by Postgres rather than by a model call;
- the favourites API brought into this phase, because the profile needs it;
- `/api/recommendations` answering 200 with `aiUsed: false` instead of an error when the AI
  is off.

**Verified by hand** on 18 Sep 2026, against throwaway containers (Postgres 55442, Redis
63801, the app on 8082), never the project's own containers:
- Parts 46–50 in full: the four-migration start with `ai.mode ready=false`, favourites
  (including saving twice and deleting twice), a question with no key, the budget filter in
  rupees and in dollars, the host not being offered their own listing, the four statistics
  answers, and the empty index;
- the wrong-key path end to end: the app starts, a listing is still created (201) with a
  warning, a question still answers in about two seconds, and the backfill job logs one
  warning per listing.
- **Not run:** Part 51, which needs a Gemini key. Its mechanics are covered by
  `AiRecommendationTest` against the real vector store.

**Result:** 307/307 tests passing.

### Session 5 — Phase 5: payments, money & currencies (15–16 Sep 2026)

**Built**
- **Paying for a booking, as a saga:**
  - the booking is inserted `PENDING`, which holds its dates;
  - a payment is created at the provider, its id recorded, then the payment confirmed;
  - the booking ends `CONFIRMED`/`PAID`, or `CANCELLED`/`FAILED` with its dates freed (the
    compensating action), or stays `PENDING` when the provider's answer was lost;
  - no external call inside a database transaction. Every provider call carries an
    idempotency key (booking id + creation time + step);
  - V3 adds `payment_status`, `payment_provider` and `refund_reference` (also to
    `bookings_aud`), and a partial index for the job.
- **Payment providers:** one `PaymentGateway` interface, two implementations:
  - `StripePaymentGateway`: stripe-java 33.4.2, PaymentIntents confirmed on the server,
    redirects off;
  - `SimulatedPaymentGateway`, used when there's no key: Stripe's test ids, plus
    `pm_sim_noAnswer` and `pm_sim_providerDown`.

  A live key is refused, and the key is never logged.
- **Answers:** 201 paid, 202 outcome unknown, 402 declined or 3-D Secure, 503 provider
  refused (with `Retry-After`).
- **Refunds:** cancelling a paid booking refunds it in full; a refund that fails stays owed.
- **PaymentReconciliationJob:** every 5 minutes it settles payments still undecided after
  10 minutes (it asks the provider), and retries refunds owed.
- **Money maths:** `Currency.toMinorUnits` (exact; never rounds) and `MoneyMathTest`.
- **Currencies:** `?currency=` adds `displayPrice`/`displayTotal`, for display only. The
  live rates come from ExchangeRate-API:
  - kept for an hour;
  - the last good set is used for up to 48 hours;
  - a failed fetch waits a minute before the next try;
  - one fetch at a time.
- **`maxPrice` fixed:**
  - converted into a ceiling per listing currency (rounded down), INR by default;
  - without rates, only same-currency listings match, and the page says
    `exchangeRatesUnavailable`;
  - the currency is part of the search cache key, and the key prefix is now `v2`.
- **Tests:** 107 more, 269 in all (183 unit, 86 integration).
  - Unit: `MoneyMathTest`, `ExchangeRatesTest`, `ExchangeRateApiSourceTest`,
    `CurrencyServiceTest`, `StripePaymentGatewayTest` (against a fake Stripe server),
    `SimulatedPaymentGatewayTest`, `StripeConfigTest`, `PaymentServiceTest`,
    `PaymentReconciliationJobScheduleTest`.
  - Integration: `BookingPaymentApiTest`, `CurrencyApiTest`, `PaymentReconciliationJobTest`.
- **Docs:**
  - [05 — Payments, money and currencies](05-payments.md);
  - hands-on guide Parts 36–43, and updates to earlier parts;
  - payment and currency samples;
  - README, CLAUDE.md, and notes in 00, 02 and 03.

**Judgement calls (explained before coding):** D46–D55 below. The notable ones:
- the simulator when there's no Stripe key, which replaces D31's "confirm without payment",
  because Stripe India is invite-only;
- refunds on cancellation, and the reconciliation job. The spec names neither, but without
  them "no half-created booking" wouldn't hold when things fail;
- `maxPrice` without a currency means INR.

**Verified by hand** on 16 Sep 2026, against throwaway containers (Postgres 55433, Redis
56380, the packaged jar on 8093):
- every step of the new Parts 36–43, with the job every 20 seconds;
- every earlier part whose output Phase 5 changed: Parts 4, 7–8, 10–11, 19, 21–25, 28
  and 31–34.

**Result:** 269/269 tests passing.

### Session 4 — Phase 4: auditing, logging & scheduling (15 Sep 2026)

**Built**
- **Auditing with Hibernate Envers:**
  - `@Audited` on Property (and every subtype), Booking and Review;
  - V2 creates `revinfo` and the `_aud` tables, with SQL copied from Hibernate's own
    generated schema;
  - a custom revision entity (`Revision`) records **who** made each change: `user:<id>`,
    `system:<job>` or `anonymous`. It comes from `AuditActor`, which `RequestIdFilter` sets
    per request and jobs set for their own work;
  - not audited: images, the version, `updatedAt`; users are referenced by id only.
- **Listing history:**
  - `GET /api/properties/{id}/history` (host only, readable after deletion);
  - consecutive Envers snapshots are compared field by field, prices by value;
  - an empty list for listings with no history.
- **Structured logging:**
  - every event log uses SLF4J's fluent API with key/value pairs;
  - the MDC carries `requestId` and `userId` (or `job`);
  - locally the console prints `key=value`, just as before; the `render` profile writes ECS
    JSON;
  - every response carries `X-Request-Id`, and a caller's id is kept only if it is safe
    (it can't forge log lines).
- **StaleListingJob:**
  - runs at 03:15 UTC daily (`STALE_LISTINGS_CRON`, `"-"` disables);
  - deactivates active listings whose last day has passed, one transaction per listing,
    through the new `PropertyService.deactivate`;
  - logs each listing and a summary.
- **Reviews API:**
  - create, list, read, edit and delete;
  - only guests whose stay has ended may review, once, which a unique constraint in V2
    backs up;
  - rating 1–5.
- **Refactors:**
  - `ConstraintViolations` recognises a named constraint in any wrapped exception, and the
    overlap check now uses it;
  - the job's repository query returns ids.
- **Tests:** 31 new.
  - Unit: `AuditActorTest`, `RequestIdFilterTest`, `ListingHistoryServiceTest`,
    `ConstraintViolationsTest`, `StaleListingJobScheduleTest`, and a new check in
    `PropertyFactoryTest`.
  - Integration: `ListingHistoryApiTest`, `StaleListingJobTest`, `ReviewApiTest`,
    `StructuredLoggingTest`.
- **Docs:**
  - [04 — Auditing, logging and scheduling](04-auditing.md);
  - hands-on guide Parts 28–34;
  - review samples in `samples/api/`;
  - README and CLAUDE.md.

**Judgement calls (explained before coding)**
- A reviews API arrived now, because the spec audits and logs reviews and nothing created
  them. The rules: finished stays only, one per guest.
- What isn't audited, and why (see D39).
- History is for the host only, and survives deletion.
- All event logs changed style, not just bookings and reviews.
- JSON logs only in the render profile.
- No distributed lock for the job (one instance; ShedLock documented).

**Verified by hand** against throwaway containers, every step of Parts 28–34:
- the job ran at the top of the minute, and its history entry names it;
- the JSON log line was captured from a second instance.

**Result:** 162/162 tests passing (94 unit, 68 integration).

### 15 Sep 2026 — GitHub housekeeping

- **Merged:** you merged PR #1 (Phase 2) and PR #2 (Phase 3) into `main`.
- **Claude removed from the contributors list.** You asked for it.
  - Why it was there: every commit message had ended with a `Co-Authored-By: Claude …` line,
    and GitHub credits co-authors of commits on the default branch.
  - How it was removed: that line was stripped from every commit message with
    `git filter-branch --msg-filter`, and `main` was force-pushed.
  - What changed: only the commit ids (for example, Phase 1 went from `739c2d0` to
    `29081ff`). The code is byte-for-byte identical.
  - The merged `phase-2-caching` and `phase-3-bookings` branches were deleted.
  - One thing couldn't change: the merged PR pages on GitHub still show the old commits,
    because GitHub doesn't allow a pull request's own history to be rewritten.
- **From now on,** commits and PR descriptions carry no AI attribution (D37).
- **Hit along the way:** problem 5.19. The rewrite was undone by accident and brought back
  from Git's reflog.

### Session 3 — Phase 3: bookings & concurrency (14 Sep 2026)

**Built**
- **Bookings API:**
  - `POST /api/bookings` — book a stay;
  - `GET /api/bookings` — my trips;
  - `GET /api/bookings/{id}` — for the guest or the host;
  - `POST /api/bookings/{id}/cancel`;
  - `GET /api/properties/{id}/bookings` — for the host.
- **Pricing.** `Booking.reserve()` charges the nightly price × nights, in the listing's own
  currency. `Currency.round()` uses half-even rounding.
- **Rules.** `BookingRules`, with an injected `Clock`, checks:
  - the dates;
  - at most 90 nights (configurable);
  - guests within the listing's limit;
  - the listing is active;
  - the stay ends by the listing's end date;
  - the cancellation window.

  Booking your own listing is a 403.
- **Concurrency, in layers:**
  - `BookingAttempt.place()` is one transaction per attempt. It loads the listing with
    `@Lock(OPTIMISTIC_FORCE_INCREMENT)`, so bookings and edits of one listing race on its
    version at commit;
  - `BookingService.book()` uses Spring Framework 7's `RetryTemplate`. It retries
    `ConcurrencyFailureException` with exponential backoff and jitter (50 → 100 → 200 ms,
    ±25 ms, 3 retries). The recover path answers 409 `booking.dates.justTaken`;
  - `OverlapConstraint` maps the exclusion-constraint violation (SQLState 23P01 plus the
    constraint's name) to the same 409, without retrying.
- **Cache.** A booking raises the listing's version, so `ListingBookedEvent` evicts that
  listing's cache entry after commit. Search pages are untouched.
- **Errors:**
  - `InvalidRequestException` (400 + field) is now the base of `PropertyValidationException`;
  - any `ConcurrencyFailureException` that reaches the API is a 409 `error.concurrentUpdate`.
    This closes the Phase 2 open item about concurrent PUTs.
- **Small refactor.** The `X-Demo-User-Id` constant moved to `ApiHeaders`.
- **Tests:**
  - unit: `BookingTest`, `BookingRulesTest`, `BookingServiceRetryTest`;
  - integration: `BookingConcurrencyTest` (the two-thread race, plus two staged races that use
    real row locks and `pg_stat_activity`) and `BookingApiTest`.
- **Docs:**
  - [03 — Bookings and concurrency](03-bookings.md);
  - [hands-on guide](hands-on-guide.md) Parts 18–26;
  - 14 booking request bodies in `samples/api/`;
  - README and CLAUDE.md.

**Judgement calls (explained before coding)**
- `RetryTemplate` plus a try/catch recover, instead of `@Retryable` plus `@Recover`, because
  Spring Framework 7 has no `@Recover`.
- The lock is on the **listing**: every booking raises its version.
  - Bonus: a booking is never charged a price that changed underneath it.
  - Cost: a cache eviction per booking, and a host's save can collide with a booking (409).
- Bookings are CONFIRMED immediately until Phase 5 adds payments.
- The booking rules above. Any user may book, hosts included.
- Cancel is an idempotent POST action, allowed until check-in day.

**Found by testing:** a real deadlock inside the exclusion constraint (problem 5.17).

**Verified by hand** against throwaway containers: every step of hands-on Parts 18–26,
including holding a row lock in psql to watch a booking retry and pay the new price.

**Result:** 131/131 tests passing (80 unit, 51 integration). Committed as `6c20a81` and
merged into `main` through PR #2.

### Session 2 — Phase 2: caching (13 Sep 2026)

**Built**
- **Two-tier listing cache.** `TwoLevelCache` puts Caffeine (30 s TTL, 10,000 entries)
  in front of Redis (10 min TTL). Reads fall through local → Redis → database; writes
  and evictions reach both tiers. Redis failures are caught, so the app degrades to local
  caching.
- **Search page cache** in Redis (5 min TTL), keyed on the normalised filter set, with the
  city first so each city is its own partition.
- **Invalidation after commit.**
  - `PropertyService` publishes `PropertyChangedEvent` on create, update and delete.
  - `PropertyCacheInvalidator` (`@TransactionalEventListener(AFTER_COMMIT)`) evicts the
    listing from both tiers and flushes only the old city's, new city's and no-city
    search pages.
  - Pattern deletes use SCAN, not KEYS.
- **Resilience:**
  - `LoggingCacheErrorHandler` turns Redis errors into cache misses;
  - 500 ms command timeout, 1 s connect timeout;
  - the invalidator never throws.
- **Updates.** The factory gained an update path using the same rules as create. The
  type-specific step became `applyTypeFields(entity, attributes)`. `CreatePropertyRequest`
  was renamed `PropertyRequest` (POST and PUT).
- **REST API** `/api/properties`: GET one, search, POST, PUT and DELETE. The acting user
  comes from the `X-Demo-User-Id` header. The rules: only hosts create, only the owner
  edits or deletes, and a listing with bookings can't be deleted (409).
- **Errors:** `GlobalExceptionHandler` now extends Spring's `ResponseEntityExceptionHandler`
  and maps 400/403/404/409. Bean-validation failures list every bad field.
- **Tests:** `IntegrationTest` base class (one shared context with Postgres + Redis
  containers, cleanup after each test), `TwoLevelCacheTest`, `SearchCacheKeysTest`,
  `PropertyCachingTest`, `PropertyApiTest`, `RedisDownTest`, and update tests in
  `PropertyFactoryTest`.
- **Docs:** [02 — Caching](02-caching.md), README "Trying the API", CLAUDE.md conventions.

**Judgement calls (explained before coding)**
- REST endpoints arrived in this phase, because caching needs something to cache.
- Listings are cached in **both** tiers; search pages only in Redis.
- Search invalidation is **partitioned by city**, not "flush everything".
- Cache writes wait for Redis (`immediateWrites`), trading about 1 ms for
  read-your-writes consistency.

**Result:** 86/86 tests passing.

### Session 1 — spec review and Phase 1 (12–13 Sep 2026)

#### Checking the spec before writing code
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

#### Your decisions
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

#### What Phase 1 built and changed
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
- **Result:** 52/52 tests passing (42 unit, 10 real-Postgres), the app boots in about
  10 s, health `UP`. Committed as `29081ff` (59 files).

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
| D14 | REST endpoints for listings in Phase 2 | caching needs real reads and writes to demonstrate and test |
| D15 | Listings cached in both tiers, search pages in Redis only | listings are few and very hot; search pages are many, moderately hot, and need pattern deletes |
| D16 | Cache immutable DTO records as typed JSON, never entities | entities are session-bound and mutable; typed JSON is readable and safe to deserialize |
| D17 | Invalidate after commit via an event listener | evicting before the commit lets a reader re-cache the old row |
| D18 | Flush search pages by city partition | precise invalidation keeps the hit ratio high; flushing everything is correct but wasteful |
| D19 | Immediate cache removal and writes (`evictIfPresent`, `invalidate`, `immediateWrites`) | Spring allows `evict`/`clear` to be deferred, and with Lettuce they are |
| D20 | Redis is optional at runtime | a cache outage should slow the app, not break it |
| D21 | No pub/sub invalidation between instances | single-instance deployment; documented as the multi-instance fix (YAGNI) |
| D22 | Updates reuse the factory; PUT is a full replacement; type can't change | an edit must never bypass a rule creation enforces |
| D23 | Deleting a listing with bookings is refused (409) | bookings are history and, from Phase 5, payment records |
| D24 | Bookings read the listing with `OPTIMISTIC_FORCE_INCREMENT` | bookings and edits of one listing take turns without holding a lock; a booking never commits a stale price |
| D25 | Spring Framework 7 `RetryTemplate` plus a try/catch recover, not `@Retryable`/`@Recover` | Framework 7 has no `@Recover`; "retry → transaction → recover" is visible in the code; unit-testable without Spring |
| D26 | The transactional attempt lives in its own bean (`BookingAttempt`) | a call to `this` skips Spring's proxy, so it would get no transaction and a retry could never be a fresh one |
| D27 | Retry `ConcurrencyFailureException` (lost version race, deadlock victim), nothing else | both fail only because of timing; "already booked" or a broken rule would fail again |
| D28 | The overlap-constraint violation becomes "just taken" straight away, not retried | a retry could only give the same answer, less precisely |
| D29 | Keep the "already booked" check before inserting | the cheap, precise answer for the everyday case; the guarantee comes after it |
| D30 | A booking evicts only its listing's cache entry | the cached view shows the version; search pages don't |
| D31 | Bookings are CONFIRMED immediately until Phase 5 | there's no payment step yet; the same as Phase 5 without a Stripe key *(superseded by D48: without a key, payments are simulated)* |
| D32 | Booking rules: ≤ 90 nights (configurable), guests ≤ the listing's limit, stay ends by `availableUntil`, not your own listing, any role may book | the spec didn't say; each rule is a judgement call, flagged before coding |
| D33 | Cancel is `POST /bookings/{id}/cancel`, idempotent, until check-in day | a cancelled booking stays on record; a repeated request is harmless |
| D34 | Any `ConcurrencyFailureException` reaching the API is a 409 `error.concurrentUpdate` | "the data changed under you, try again", not "the server is broken" |
| D35 | "Today" comes from an injected `Clock` (the JVM's zone) | rules can be tested with a fixed date; agrees with `@FutureOrPresent` |
| D36 | `InvalidRequestException` (400 + field) as the base for every rule violation | one handler for listing and booking rules |
| D37 | No AI co-author or attribution lines in commits or PR descriptions; history rewritten to remove the existing ones (15 Sep) | your choice: the repository's contributors should be you alone |
| D38 | A custom Envers revision entity with `changed_by`, filled from a ThreadLocal `AuditActor` | "who" is the first question a history answers; the listener isn't a Spring bean, and runs on the transaction's thread |
| D39 | Not audited: images, `version`, `updatedAt`; users are referenced by id | images arrive in Phase 7; the version is bookkeeping; the revision has the time; users have no history |
| D40 | The V2 audit SQL is copied from Hibernate's generated schema; history tables are permissive | validation must pass exactly; history outlives rows and old rules |
| D41 | The listing history is a diff of consecutive snapshots, host only, readable after deletion, empty (not 404) when never audited | readers want what changed; a deleted listing's owner still needs its history |
| D42 | A reviews API in Phase 4: finished stays only, one per guest (check + unique constraint) | the spec audits and logs reviews; the stay rule stops fake reviews |
| D43 | Structured logging with SLF4J key/value pairs and the MDC; `key=value` locally, ECS JSON on Render; every event log converted | one searchable style; the local look (and the hands-on guide) unchanged |
| D44 | A caller's `X-Request-Id` is kept only if it matches `[A-Za-z0-9._-]{1,64}`, otherwise 8 random hex characters | follow an id across systems, without letting a caller forge log lines |
| D45 | StaleListingJob: configurable cron (03:15 UTC), one transaction per listing through PropertyService, no distributed lock | failures isolated; caches and history stay correct; a single instance for now |
| D46 | Paying is a saga: hold the dates (PENDING), create the payment, record its id, confirm it, then confirm or release the booking. No external call inside a transaction | a rollback can't undo a charge; the id recorded before any money moves makes every failure recoverable |
| D47 | The booking request carries a `paymentMethodId` (Stripe test ids), confirmed on the server with redirects off; 3-D Secure gets a 402 for now | there's no browser until Phase 8; card numbers never reach the server |
| D48 | No Stripe key: a payment simulator answering to Stripe's test ids (plus `pm_sim_noAnswer`, `pm_sim_providerDown`), labelled SIMULATED; live keys refused | Stripe India is invite-only; every payment path must be seen without an account; a misplaced live key must never charge a card |
| D49 | 201 paid, 202 outcome unknown, 402 declined or 3-D Secure, 503 provider refused (`Retry-After: 60`) | each code tells the client what to do next |
| D50 | A failed payment keeps its booking (CANCELLED, payment FAILED); a separate `payment_status` beside the four booking statuses, plus `payment_provider` and `refund_reference` | bookings are records, and the provider's payment points at one; "is the stay on?" and "where's the money?" are different questions |
| D51 | Cancelling a paid booking refunds it in full (the cancel commits first, the refund follows); a PENDING booking can't be cancelled | free cancellation until check-in (Phase 3's rule); no race with an undecided payment |
| D52 | PaymentReconciliationJob every 5 minutes: settles payments undecided for 10 minutes by asking the provider, retries refunds owed | lost answers and crashes between steps are where half-made bookings come from |
| D53 | Idempotency key = booking id + creation time + step; the creation time cut to microseconds when saved | ids repeat after a development database is wiped; the time in memory must equal the time stored |
| D54 | Exchange rates from ExchangeRate-API's free endpoint, kept in memory (1 h, last good set up to 48 h, 1 min between failed tries, one fetch at a time); shown only on request, never stored or cached | free, no key, has AED (the ECB's rates don't); an outage must never slow every request |
| D55 | `maxPrice` becomes one ceiling per listing currency, rounded down; INR if no currency is given; without rates only same-currency listings, flagged and not cached; currency in the cache key; key prefix v2 | a fair comparison without storing converted prices; old searches keep their meaning; rounding never lets an over-budget listing in |
| D56 | A favourites API in Phase 6 (`PUT`/`DELETE`/`GET`), idempotent by design | the preference profile and the taste vector are built from favourites, and nothing created any; a `PUT` says "let this be saved", which stays true however often it is sent |
| D57 | Rules, not an LLM, read the question and route the intent | what needs reading is three crisp things; a parser is deterministic, unit-testable, instant and free, where a model would add a second network call and a second failure mode before any work started |
| D58 | "Like my favourites" is the average of their embeddings, computed in Postgres (`avg(embedding)`) | a taste for nothing: no model call, no vectors loaded into Java, and the listings they already saved are excluded |
| D59 | Questions about their own numbers are answered in SQL, never by the model | a sum has exactly one right answer; a model would cost quota and can be confidently wrong |
| D60 | Flyway creates the vector table (V4, 768 dimensions, HNSW, cosine); Spring AI schema init off | one owner for the schema, `ddl-auto: validate` keeps working, and 3,072 dimensions could not be indexed at all |
| D61 | A document id derived from the listing id, plus a SHA-256 hash of the embedded text; the price in that text rounded to the currency's decimals | one listing is always one document, an edit replaces it, and an unchanged listing costs no embedding call |
| D62 | Listings are indexed after commit on the existing `PropertyChangedEvent`, never throwing, with a backfill job every 2 minutes in batches of 20 | only a committed change is true; embedding is a network call; the free tier allows a few calls a minute, and everything created before a key existed still needs indexing |
| D63 | Every listing is shown to the model as `[id]`, and the answer is checked afterwards: invented ids removed, an all-invented answer discarded | a prompt is a request, a check is a rule; it holds whatever the model does next month |
| D64 | `/api/recommendations` always answers 200, with `aiUsed` and `semantic` flags and a translated sentence | the AI being off is not the caller's error, and a client can show the listings whatever happened |
| D65 | With no key, an `EnvironmentPostProcessor` switches off chat, embeddings and the vector store, and excludes the Gemini embedding connection auto-configuration | the vector store takes the embedding model as a constructor argument, so the app would not start at all; a missing credential may only cost its own feature |
| D66 | The tests fake only the two models; the vector store, pgvector, the SQL and the checks are real | a real key would make the build need a secret, cost quota and vary between runs |
| D67 | Ranking = similarity plus small profile boosts (0.05 city, 0.05 price band, 0.03 capacity), 50 candidates narrowed to 5 | the question in front of us beats habit, and a boost must never lift a listing that means nothing like it above one that does |
| D68 | A suggestion carries its `similarity`, and `displayPrice` only when `?currency=` was asked for | anyone reviewing the project can see why a listing was suggested; a converted price at a rate of 1 says nothing |
| D69 | No S3 settings: uploads are a clear 503 (no Retry-After); `S3_ENDPOINT`/`S3_PATH_STYLE` let MinIO stand in, started by a compose profile and used by the tests | the spec's "fail cleanly"; a local-disk fallback would lose photos on every Render deploy; MinIO tests the real S3 protocol with no account |
| D70 | Photos served by the app at `/images/listings/…` (private bucket), cacheable for a year | no public bucket to get wrong; works with any S3-compatible store; pre-signed links would expire inside cached listings |
| D71 | The file's first bytes decide its type; a contradicting declared type is refused; key and content type come from the bytes; random keys, never the uploaded name | a name and a header are claims; a signature is evidence; a client's text has no place in a storage path |
| D72 | Upload = checks, file, row (file deleted if the row fails); removal = row, then file after commit; listing delete removes its files | the payment saga's shape: the worst leftover is an unused file, never a listing pointing at a missing photo |
| D73 | 5 MB per photo, 10 per listing, the version lock of Phase 3 on photo changes | the spec asks for a size cap; a count cap keeps a listing and its cached view bounded; concurrent uploads can't both pass the limit |
| D74 | Language: `?lang=` (a filter, remembered in a cookie), then cookie, then Accept-Language, then English; only en/hi/es | works on every request including 404/405; stateless; a French locale with English text would still format numbers the French way |
| D75 | Spring's own errors translated through `problemDetail.*` keys, titles from `error.title.<status>`, a catch-all 500 with the request id | the spec says every error message; a client should never see English next to Hindi, or a stack trace |
| D76 | A test holds the three message files to the same keys and placeholders, Devanagari in Hindi, no English left in Spanish (three words excepted), and every key used in code present | "real translations" can't be judged by a test, but every mechanical failure can be caught |
| D77 | GraphQL: money as a `Decimal` string scalar, a `cancelBooking` mutation, custom CONFLICT/PAYMENT_FAILED/UNAVAILABLE classifications, a startup failure if a schema field has no fetcher | Float is a double; cancelling is the natural pair of booking; REST's 409/402/503 need a GraphQL equivalent; a missing fetcher should fail loudly |
| D78 | Tests fail when an endpoint lacks Swagger docs and examples, or a Postman request | documentation that isn't checked goes stale |
| D79 | A two-parameter Thymeleaf fragment as the layout; Bootstrap 5.3.8 from jsDelivr, pinned, with SRI; one small script, the pages working without it | no layout library for what a fragment already does; no build step; a CDN file cannot be swapped under us |
| D80 | "Sign in as" = the user id in the session; a new session id on every switch; `returnTo` only a path on this site; the audit actor from the session when there is no header | no Spring Security by the spec; session fixation and open redirects are the two classic holes of a hand-made sign-in; history must still say who |
| D81 | Session cookie HttpOnly and SameSite=Lax, cookie-only (never in the URL); no CSRF tokens | tokens come with Spring Security, which is ruled out; Lax stops other sites' form posts in current browsers; an id in a URL leaks through logs and Referer |
| D82 | Every POST ends in a redirect, refused forms included (the form and its errors carried as flash attributes) | reloading never posts again, and the address bar always shows a page that answers GET — links built from it (language, sign in as) would otherwise hit a POST-only address |
| D83 | A service's refusal is put on the form beside its field (`FormErrors`), from the API's own message keys | one set of rules and one set of texts; a page never re-implements a check |
| D84 | "List a place" is generated from the creators' `AttributeSpec`s: one fieldset per type, the unchosen ones disabled; Yes/No/not-stated selects; a no-JavaScript reload; a test fails if a template names a type | keeps "a new type = enum + entity + creator + migration + labels" true for the pages too |
| D85 | Paying on the page = a select of Stripe's test payment methods, no card-number field | card numbers must never reach our server (PCI DSS); Stripe Elements needs a publishable key and a browser flow |
| D86 | Page errors: a handler for page controllers, Spring Boot's own error page drawn with the same template and navbar (`PageErrorViewResolver`), type mismatches as translated 400s; without the database, the error page still renders | never a stack trace or a "Whitelabel" page; an error page must not fail itself |
| D87 | The templates' message keys are checked by a test (including keys built from enum values), and every page test fails on `??` | Thymeleaf prints a missing key instead of failing |
| D88 | Relative links (pages, languages); the home template named `home.html`; Tomcat's `max-swallow-size` 50 MB | all found by hand: an absolute link trusts the scheme behind Render's proxy; `index.html` made Spring Boot add a "welcome page" that rendered it without data; a browser showed "connection reset" for a too-large photo |
| D89 | Language names in their own language (English, हिन्दी, Español), written in the code, not the message files | a Hindi reader looks for "हिन्दी"; the names are the same in every language |
| D90 | A page's ratings come from one grouped query (`ReviewService.ratingsFor`) | twelve cards must not mean twelve queries (the N+1 problem, on a web page) |
| D91 | A demo seeder at start-up: only into an empty `users` table, one transaction, data in JSON with dates relative to today, through the services, recorded as `system:demo-seeder`, never failing start-up, `DEMO_DATA_ENABLED` to switch off | the spec: never blank, toggleable; it must never touch real data, leave half a world, or rot as the calendar moves |
| D92 | Seeded bookings recorded directly, already paid through the simulator (not through BookingService) | reviews need finished stays, which the booking rules rightly refuse; paying at start-up would make starting depend on Stripe; the simulator still refunds them |
| D93 | Redis down reports a custom DEGRADED status (HTTP 200) instead of Boot's DOWN | the app works without Redis; a platform that restarts or unroutes on DOWN would take a working site away. Closes the Phase 2 open item |
| D94 | Two-stage Dockerfile (Temurin 21 JDK Alpine → JRE Alpine), dependencies before source, jar extracted into layers, non-root user with root-owned files, `exec` start | small image with no build tools; code-only rebuilds in seconds; least privilege; clean shutdown on the platform's signal |
| D95 | Tests skipped inside the image build | they need Docker (Testcontainers), which a build container doesn't have; they run before pushing. A CI pipeline is the next step |
| D96 | JVM: heap 60% of the container, Serial GC, the quick compiler only, exit on out-of-memory | a 512 MB, 0.1-CPU box: memory outside the heap needs room (measured 363 MB used), and start-up speed matters more than peak speed |
| D97 | `render.yaml`: free plans, Singapore, the database as parts (`fromDatabase`), `REDIS_URL` from the Key Value, keys `sync: false`, no outside access to the database or cache | ₹0 to show it; closest region to India; Render's connection string is `postgres://`; secrets never in Git; nothing but the app needs to reach them |
| D98 | The render profile believes `X-Forwarded-*`, sends the session cookie only over HTTPS, and sizes threads (40), connections (5) and the connection wait (10 s) for the free machine | behind Render's TLS proxy the app must write `https://` addresses; a hung check beyond Render's 5 s is a failure anyway |
| D99 | Pages offer "Cancel" only when `BookingRules.cancellableToday` says so | the demo's finished stays showed a button the service would refuse; one rule, asked by both |

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
| 5.12 | Flushed search pages were still served | Spring's cache contract allows `clear()`/`evict()` to be deferred, and with Lettuce Spring Data Redis really does them asynchronously (confirmed in its bytecode) | invalidate with the immediate methods (`evictIfPresent`, writer `invalidate`) |
| 5.13 | A page just cached was not yet in Redis | cache *writes* are asynchronous with Lettuce too | `immediateWrites()` on the Redis cache writer |
| 5.14 | An API test expected the new listing to have id 1 | Postgres id counters are not rolled back with a transaction | the test accepts any id |
| 5.15 | Harmless Netty errors when the Redis-down test shut down | Lettuce was still retrying the dead port | that logger silenced in that test only |
| 5.16 | A GitHub push kept failing with "Repository not found" | the remote had been added with the placeholder `YOUR-USERNAME` | `git remote set-url origin` with the real address |
| 5.17 | The race test failed on its 2nd run: `CannotAcquireLockException … deadlock detected` (SQLState 40P01) | an exclusion constraint adds its index entry *first* and then checks for conflicts. Two overlapping inserts at the same instant each found the other's uncommitted entry and waited for it, and Postgres cancelled one | retry every `ConcurrencyFailureException` (version race **and** deadlock), not only optimistic failures; the retry sees the survivor and answers correctly. Pinned by `deadlockVictimIsRetried`; the race test has passed repeatedly since |
| 5.18 | The retry unit tests couldn't build their `RetryTemplate`: `Invalid maxDelay (0ms)` | Framework 7's `RetryPolicy` accepts a zero delay but requires a positive `maxDelay` | the tests use 1 ms |
| 5.20 | After V2, `PersistenceMappingTest` failed: expected schema version "1", but was "2" | the test hard-coded the latest migration | it now checks "no migration pending, the latest is current", which survives every future migration |
| 5.21 | A test comparing `reviews_aud.revtype` failed with the baffling "expected [0, 1, 2] but was [0, 1, 2]" | the test expected `Short` values; the Postgres driver returns `SMALLINT` as `Integer`, and AssertJ prints both the same | compare with ints. Lesson: equal-looking values can differ in type |
| 5.22 | The "readable history" PowerShell command printed empty fields | Windows PowerShell 5.1's `Invoke-RestMethod` passes a JSON array down the pipeline as one object | wrap the call in parentheses so the array is unrolled; the guide says why |
| 5.23 | The job's history entry also showed `availableUntil` changing (not a bug, a lesson) | the date had been set with plain SQL, which Envers never sees, so the next audited change swept it up | kept in the guide on purpose; production code changes data only through the services |
| 5.24 | A listing with no history rows answered "There is no listing with id N" | an empty history was treated as "no listing" | it now checks the listing exists, then returns an empty list (host only); tested |
| 5.25 | `PaymentServiceTest` failed with "No interactions wanted here" | building `PaymentGateways` asks each gateway for its provider, and that call to the mock counts as an interaction | removed the check. Lesson: `verifyNoInteractions` sees every call, including the ones your own test setup makes |
| 5.26 | No way to get Stripe keys (found while planning) | Stripe accounts in India are invite-only | the payment simulator (D48): every payment path works, and is tested, without an account |
| 5.27 | A wiped development database could make Stripe replay an old payment (caught in design) | booking ids start again at 1, and Stripe remembers idempotency keys for 24 hours | the key includes the booking's creation time, cut to microseconds when it's saved, so the time in memory equals the one stored |
| 5.28 | The hands-on guide's audit revision numbers stopped matching (Parts 31–33) | a booking now takes three transactions (held, payment recorded, paid), so it makes three revisions | re-ran Parts 28–34 and updated every number |
| 6.1 | With no key the app still tried to build Gemini and refused to start, although an `EnvironmentPostProcessor` was meant to switch it off | in Boot 4 those are discovered as `org.springframework.boot.EnvironmentPostProcessor` in `META-INF/spring.factories`; the older `org.springframework.boot.env` name, and its `.imports` file, are ignored without a word | registered under the new name. `AiSwitchedOffTest` now fails if it ever stops running |
| 6.2 | It still failed: "Google GenAI project-id must be set!" | the Gemini *embedding connection* auto-configuration has no property switch of its own, so switching the embedding model off did not stop it | excluded outright with `spring.autoconfigure.exclude` when there is no key |
| 6.3 | Switching off only the two models would not have been enough (caught while planning, by reading the class file) | `PgVectorStoreAutoConfiguration` takes the `EmbeddingModel` as a constructor argument, so it fails while the context is built | the vector store is switched off too; everything asks `AiAvailability` before using any of them |
| 6.4 | A listing was re-embedded on every run of the job, although nothing had changed | the embedded text carried the price as written: `9000.00` when built from the request, `9000.0000` when read back from `NUMERIC(19,4)`, so the content hash differed | the text rounds the price to the currency's own decimals; a unit test pins it. Lesson: anything hashed must be normalised first |
| 6.5 | With a deliberately wrong key, the backfill job died with a scheduler stack trace and abandoned the rest of the batch | the embedding failure escaped the loop | one listing at a time, each failure logged as `ai.listing.embedFailed`, like the other jobs. Found by hand, not by a test |
| 6.6 | In the AI integration test, only one of two listings ever came back from the vector store | the fake embedding model gave texts with no shared words vectors at exactly right angles, and a vector store drops similarity 0 | the fake adds a small constant direction to every vector, which is how real embeddings behave — two English sentences are never orthogonal |
| 6.7 | "What do I usually pay for my favourites?" was answered with listings instead of a number | the intent rules had no word for "usually" or "pay", and "rating" was not a subject | both lists widened, with tests for each phrasing. Found by hand while writing the guide |
| 6.8 | Every answer carried a `displayPrice` equal to the real price at a rate of 1 | the recommendation endpoint converted into the default currency even when none was asked for | it converts only when `?currency=` is given, as everywhere else since Phase 5 |
| 7.1 | The locked query for a photo change failed: "Entity User has no version and may not be locked at level OPTIMISTIC_FORCE_INCREMENT" | Hibernate applies a query's lock mode to every entity it loads, and the query also fetched the host and the photos | the locked query loads only the listing; host and photos are read lazily in the same transaction |
| 7.2 | `?lang=es` changed the error's text but not bean validation or the AI's answers | my resolver worked out the language once, when the request arrived, before `?lang=` had been read; Spring's own resolver is lazy for exactly this reason | the resolver's answer is computed when it is asked for. Lesson: a value a later step may change must be read late |
| 7.3 | `?lang=` was ignored on a 405 (found by hand) | Spring's LocaleChangeInterceptor only runs once a controller is chosen, and a routing error never gets that far | a servlet filter applies `?lang=` to every request; on an upload it reads only the query string, so Tomcat doesn't read the body early |
| 7.4 | Spring's own errors kept an English title ("Bad Request") next to a translated detail | the title was set before Spring built the body for most of its errors | set at the last step, `createResponseEntity`, where the body is final |
| 7.5 | A 405 or an unknown `/api/…` path came back in Spring Boot's own English JSON | those errors happen before a controller is chosen, and the error handler only covers REST controllers | `ApiRoutingErrorHandler`, ordered last, for `/api/` and `/images/` paths only, so the pages of Phase 8 still get normal error pages |
| 7.6 | MinIO's image could not be pulled: "repository does not exist" | MinIO stopped publishing on Docker Hub in 2025 | the pinned community release from quay.io, in the tests and in docker-compose.yml |
| 7.7 | The GraphQL tests failed: "asyncDispatch CountDownLatch was not set" | Spring for GraphQL answered these requests synchronously, so there was nothing to wait for | the test helper handles both a synchronous and an asynchronous answer |
| 7.8 | Hindi printed as `????` in the tests' failure messages and in a plain PowerShell window | the console's code page is not UTF-8 (the data itself was right) | the guide sets `[Console]::OutputEncoding` to UTF-8 and suggests Windows Terminal |
| 8.1 | Every booking from the page was refused, and the page showed no error at all | `BookingRequest`, shared with the API, requires `propertyId`; the form doesn't send it (it is in the address), and `@Valid` ran before the controller could set it — the error sat on a field the form doesn't show | set the id from the address, then validate explicitly. Lesson: a class shared by two front doors can carry a rule only one of them satisfies |
| 8.2 | On a refused form, the language menu and "sign in as" led to a 405 | the refused form was shown in answer to the POST, so "this page" was the POST-only address | refused forms redirect too, with the form and its errors as flash attributes (D82) |
| 8.3 | After "sign in as", the home page was a 500 (found in the browser) | Tomcat wrote `;jsessionid=…` into the redirect, not yet knowing the browser keeps cookies; `/;jsessionid=…` didn't match the home controller, so Spring Boot's "welcome page" rendered `index.html` with no data | `tracking-modes: cookie`, and the template renamed `home.html`, so there is no welcome page. MockMvc never rewrites URLs, so no test could see it |
| 8.4 | A 6 MB photo gave the browser "connection reset" (curl had got its 413 in Phase 7) | the upload is refused from its declared size before the body is read; Tomcat throws away at most 2 MB of an unread body, then closes the connection while the browser is still sending | `server.tomcat.max-swallow-size: 50MB` |
| 8.5 | `/listings/abc` on the pages would have been a 500 | Spring's type-mismatch error is not an `ErrorResponse`, so the handler's branch for Spring's errors missed it | its own branch, with the API's `problemDetail.*` key |
| 8.6 | The "no type names in templates" test failed on its first run | "Quiet garden villa" as sample text in a template | neutral sample text; the test stays strict |
| 8.7 | Page tests: the page links lacked the filters; a test's second past stay was refused by Postgres | MockMvc's `.param()` doesn't fill the query string, which the links are built from; two stays on one listing overlapped (the constraint applies across guests) | queries written into the test URL; different dates |
| 8.8 | Page links came out absolute (`http://localhost/?page=1`) | built from the current request's full URL | relative paths from the page's own address |
| 8.9 | A stray broken line (the tail of a Windows path) at the end of the guide's troubleshooting table since Phase 7 | a script passed through the shell lost the backslash in `C:\dev\rentalhub`, so `\r` became a line break | line removed; such scripts are now written to a file first |
| 9.1 | The first image would never have started: no `application.jar` in it | Spring Boot's extract keeps the jar's own name (`rentalhub-1.0.0.jar`); the Dockerfile's start command assumed `application.jar` | rename the jar before extracting, as Spring's own recipe does. Found by listing the image's `/app` before running it |
| 9.2 | `RedisDownTest` found three Goa villas instead of one | it builds its own application context, so the new seeder filled its empty database first | every test context switches the seeder off; the break doubled as proof that seeding works with Redis down |
| 9.3 | With the database stopped, `/actuator/health` took 30 s to say DOWN | the connection pool waits 30 s for a connection by default; Render gives up after 5 s, and the hung request holds a thread | `connection-timeout: 10s` in the render profile |
| 9.4 | The seeder's page test didn't find the Goa villa on the home page | results are newest first, and the villa is created first, so it is on page 2 | the test searches Goa |
| 9.5 | Finished demo stays showed "Cancel booking" in My bookings | the page offered cancel for every confirmed booking; the service refuses once the stay has begun | the page asks the service's own rule (`cancellableToday`) |
| 5.19 | The history rewrite was undone right after it ran | the recovery command (`git reset --hard refs/original/…`), meant only for when a check failed, was listed with a Run button among the steps and got run | `git reflog` still listed the rewritten `main` (`5305753`), so `git reset --hard 5305753` and a force-push restored it. Lesson: Git rarely loses a commit, because the reflog records every position a branch has had |

---

## 6. YouTube study plan — every phase

**How to use this list**
- Every item is a clickable **YouTube search**. Pick a video that is recent (ideally the
  last two years for anything Spring-specific), from a channel below, and that writes
  real code rather than only drawing slides.
- Spring Boot 3 videos are fine for concepts: Boot 4 mostly renamed packages and
  dependencies.
- Tick the box when you can explain the "you should be able to" line without notes.
- Watch **Foundations** and **Phases 1–5** now; each later phase's list just before or
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
- [ ] [cache stampede thundering herd](https://www.youtube.com/results?search_query=cache+stampede+thundering+herd) — what `sync = true` prevents
- [ ] [W-TinyLFU cache eviction](https://www.youtube.com/results?search_query=W-TinyLFU+cache+eviction) — optional: how Caffeine picks what to drop
- [ ] [redis scan vs keys](https://www.youtube.com/results?search_query=redis+scan+vs+keys) — why KEYS is banned in production
- [ ] [redis pub sub](https://www.youtube.com/results?search_query=redis+pub+sub+tutorial) — how multiple servers could clear local caches together
- [ ] [spring transactionaleventlistener](https://www.youtube.com/results?search_query=spring+transactionaleventlistener) — running code only after a commit
- [ ] [rest api status codes](https://www.youtube.com/results?search_query=rest+api+http+status+codes+explained) — 200, 201, 204, 400, 403, 404, 409
- [ ] [put vs patch rest api](https://www.youtube.com/results?search_query=put+vs+patch+rest+api) — full replacement vs partial change
- [ ] [mockmvc spring boot tutorial](https://www.youtube.com/results?search_query=mockmvc+spring+boot+tutorial) — testing the web layer without a server

### Phase 3 — Bookings & concurrency
- [ ] [database transactions acid explained](https://www.youtube.com/results?search_query=database+transactions+acid+explained) — atomicity, isolation
- [ ] [transaction isolation levels](https://www.youtube.com/results?search_query=transaction+isolation+levels+explained) — read committed, repeatable read, serializable
- [ ] [optimistic vs pessimistic locking](https://www.youtube.com/results?search_query=optimistic+vs+pessimistic+locking) — why optimistic wins for rare conflicts
- [ ] [spring transactional explained](https://www.youtube.com/results?search_query=spring+transactional+annotation+explained) — proxies, rollback rules
- [ ] [spring transactional pitfalls](https://www.youtube.com/results?search_query=spring+transactional+pitfalls+self+invocation) — self-invocation, why retry must wrap the transaction
- [ ] [race condition explained](https://www.youtube.com/results?search_query=race+condition+explained+web+application) — the double-booking race
- [ ] [jpa hibernate optimistic locking version](https://www.youtube.com/results?search_query=jpa+hibernate+optimistic+locking+version) — `@Version`, and what happens when it doesn't match
- [ ] [jpa lock modes optimistic force increment](https://www.youtube.com/results?search_query=jpa+lock+modes+optimistic+force+increment) — why plain `OPTIMISTIC` can't stop two bookings
- [ ] [postgresql row level locking](https://www.youtube.com/results?search_query=postgresql+row+level+locking+select+for+update) — `FOR UPDATE`, and what an UPDATE waits for
- [ ] [postgresql mvcc explained](https://www.youtube.com/results?search_query=postgresql+mvcc+explained) — why readers never wait for writers
- [ ] [postgresql deadlock explained](https://www.youtube.com/results?search_query=postgresql+deadlock+explained) — the bug the race test found (problem 5.17)
- [ ] [retry exponential backoff](https://www.youtube.com/results?search_query=retry+with+exponential+backoff+and+jitter) — why retries wait longer each time, and why add randomness
- [ ] [java concurrency executorservice cyclicbarrier](https://www.youtube.com/results?search_query=java+concurrency+executorservice+cyclicbarrier) — how the two-thread test releases both threads together
- [ ] [idempotency in rest apis](https://www.youtube.com/results?search_query=idempotency+in+rest+apis) — why cancelling twice isn't an error

### Phase 4 — Auditing, logging, scheduling
- [ ] [hibernate envers tutorial](https://www.youtube.com/results?search_query=hibernate+envers+tutorial) — audit tables and revisions
- [ ] [database audit trail design](https://www.youtube.com/results?search_query=database+audit+trail+design) — what to record, and what an audit trail misses
- [ ] [audit log vs event sourcing](https://www.youtube.com/results?search_query=audit+log+vs+event+sourcing) — history beside the data, or history *as* the data
- [ ] [spring boot scheduled tasks](https://www.youtube.com/results?search_query=spring+boot+scheduled+tasks+cron) — `@Scheduled`
- [ ] [cron expression explained](https://www.youtube.com/results?search_query=cron+expression+explained) — read `0 15 3 * * *` (Spring adds a seconds field)
- [ ] [shedlock spring boot](https://www.youtube.com/results?search_query=shedlock+spring+boot) — one instance runs the job when there are several
- [ ] [structured logging spring boot](https://www.youtube.com/results?search_query=structured+logging+spring+boot) — why key=value and JSON logs
- [ ] [slf4j logback tutorial](https://www.youtube.com/results?search_query=slf4j+logback+tutorial) — log levels, appenders, patterns
- [ ] [slf4j 2 fluent api key value](https://www.youtube.com/results?search_query=slf4j+2+fluent+api+key+value) — `log.atInfo().addKeyValue(...)`
- [ ] [mdc logging java](https://www.youtube.com/results?search_query=mdc+logging+java) — attaching a request id to every log line
- [ ] [correlation id logging](https://www.youtube.com/results?search_query=correlation+id+logging+microservices) — following one request across systems
- [ ] [log injection attack](https://www.youtube.com/results?search_query=log+injection+attack) — why the request-id filter refuses odd ids

### Phase 5 — Payments & money
- [ ] [stripe payment intents tutorial](https://www.youtube.com/results?search_query=stripe+payment+intents+tutorial) — the payment lifecycle
- [ ] [stripe test cards](https://www.youtube.com/results?search_query=stripe+test+mode+test+cards) — simulating success and failure
- [ ] [idempotency key payments](https://www.youtube.com/results?search_query=idempotency+key+payments) — never charging twice
- [ ] [floating point precision explained](https://www.youtube.com/results?search_query=floating+point+precision+explained) — the theory behind BigDecimal
- [ ] [saga pattern compensating transaction](https://www.youtube.com/results?search_query=saga+pattern+compensating+transaction) — undoing a booking when payment fails
- [ ] [webhooks explained](https://www.youtube.com/results?search_query=webhooks+explained) — how Stripe tells your app what happened
- [ ] [java bigdecimal tutorial](https://www.youtube.com/results?search_query=java+bigdecimal+tutorial) — scale, rounding modes, `compareTo`
- [ ] [distributed transactions two phase commit](https://www.youtube.com/results?search_query=distributed+transactions+two+phase+commit) — why the payment can't join our transaction
- [ ] [3d secure strong customer authentication](https://www.youtube.com/results?search_query=3d+secure+strong+customer+authentication) — why some cards need a browser
- [ ] [async request reply pattern 202 accepted](https://www.youtube.com/results?search_query=async+request+reply+pattern+202+accepted) — answering before the work is finished
- [ ] [payment reconciliation explained](https://www.youtube.com/results?search_query=payment+reconciliation+explained) — matching your records with the provider's
- [ ] [stale while revalidate caching](https://www.youtube.com/results?search_query=stale+while+revalidate+caching) — using slightly old data while fetching new
- [ ] [currency conversion api tutorial](https://www.youtube.com/results?search_query=currency+conversion+api+tutorial) — live exchange rates

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
- [ ] [minio tutorial](https://www.youtube.com/results?search_query=minio+tutorial+docker) — an S3-compatible server on your own machine, as used in the tests and the guide
- [ ] [spring boot file upload](https://www.youtube.com/results?search_query=spring+boot+file+upload+multipart) — multipart uploads, size limits
- [ ] [file type magic numbers](https://www.youtube.com/results?search_query=file+signature+magic+numbers) — why we check file bytes, not just the name
- [ ] [spring boot i18n locale resolver](https://www.youtube.com/results?search_query=spring+boot+i18n+locale+resolver) — `Accept-Language` and `?lang=`
- [ ] [graphql vs rest](https://www.youtube.com/results?search_query=graphql+vs+rest) — when each fits
- [ ] [spring graphql tutorial](https://www.youtube.com/results?search_query=spring+for+graphql+tutorial) — `@QueryMapping`, `@MutationMapping`
- [ ] [N+1 query problem explained](https://www.youtube.com/results?search_query=n%2B1+query+problem+explained) — why one query per item hurts
- [ ] [graphql N+1 batch mapping](https://www.youtube.com/results?search_query=spring+graphql+batchmapping+N%2B1) — `@BatchMapping`
- [ ] [springdoc openapi swagger](https://www.youtube.com/results?search_query=springdoc+openapi+swagger+spring+boot) — `/swagger-ui.html`
- [ ] [postman tutorial](https://www.youtube.com/results?search_query=postman+tutorial+for+beginners) — collections and environments

### Phase 8 — Frontend
- [ ] [thymeleaf tutorial](https://www.youtube.com/results?search_query=thymeleaf+spring+boot+tutorial) — templates, `th:each`, `th:if`
- [ ] [thymeleaf layout fragments](https://www.youtube.com/results?search_query=thymeleaf+fragments+layout) — shared navbar/layout
- [ ] [thymeleaf form validation](https://www.youtube.com/results?search_query=thymeleaf+form+validation+errors) — showing errors on the form
- [ ] [bootstrap 5 tutorial](https://www.youtube.com/results?search_query=bootstrap+5+tutorial) — grid, cards, forms
- [ ] [http session spring boot](https://www.youtube.com/results?search_query=http+session+spring+boot) — how the "sign in as" switcher remembers you
- [ ] [post redirect get pattern](https://www.youtube.com/results?search_query=post+redirect+get+pattern) — why every form ends in a redirect
- [ ] [spring mvc flash attributes](https://www.youtube.com/results?search_query=spring+mvc+redirectattributes+flash+attributes) — messages that survive the redirect
- [ ] [csrf attack explained](https://www.youtube.com/results?search_query=csrf+attack+explained) — cross-site request forgery
- [ ] [samesite cookie explained](https://www.youtube.com/results?search_query=samesite+cookie+attribute+explained) — the app's CSRF defence
- [ ] [session fixation attack](https://www.youtube.com/results?search_query=session+fixation+attack+explained) — why the session id changes on sign-in
- [ ] [open redirect vulnerability](https://www.youtube.com/results?search_query=open+redirect+vulnerability+explained) — why `returnTo` is checked
- [ ] [xss cross site scripting explained](https://www.youtube.com/results?search_query=xss+cross+site+scripting+explained) — why `th:text` escapes
- [ ] [subresource integrity](https://www.youtube.com/results?search_query=subresource+integrity+sri+explained) — the `integrity` hash on the CDN files

### Phase 9 — Ship
- [ ] [dockerfile spring boot multi stage](https://www.youtube.com/results?search_query=dockerfile+spring+boot+multi+stage) — build stage vs runtime stage
- [ ] [spring boot layered jar docker](https://www.youtube.com/results?search_query=spring+boot+layered+jar+docker) — faster rebuilds
- [ ] [jvm memory in containers](https://www.youtube.com/results?search_query=jvm+memory+docker+containers) — why `MaxRAMPercentage`
- [ ] [deploy spring boot to render](https://www.youtube.com/results?search_query=deploy+spring+boot+render.com) — the whole flow
- [ ] [render blueprint render yaml](https://www.youtube.com/results?search_query=render+blueprint+render.yaml) — infrastructure as code
- [ ] [twelve factor app](https://www.youtube.com/results?search_query=twelve+factor+app+explained) — why config lives in environment variables
- [ ] [docker tutorial for beginners](https://www.youtube.com/results?search_query=docker+tutorial+for+beginners) — images, containers, layers
- [ ] [spring boot actuator health check](https://www.youtube.com/results?search_query=spring+boot+actuator+health+check) — health indicators and statuses
- [ ] [docker container run as non root user](https://www.youtube.com/results?search_query=docker+container+run+as+non+root+user) — least privilege
- [ ] [reverse proxy x-forwarded-for headers](https://www.youtube.com/results?search_query=reverse+proxy+x-forwarded-proto+headers+explained) — why the render profile trusts them
- [ ] [github actions maven ci](https://www.youtube.com/results?search_query=github+actions+maven+java+ci) — the next step: tests on every push

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
| 5 | Stripe (test mode keys). Optional: invite-only in India, and the simulator covers every path | stripe.com | free |
| 6 | Gemini API key. Optional: the app boots, indexes nothing and still answers questions from ordinary search without it | aistudio.google.com | free tier, no card |
| 7 | AWS (S3 bucket + access keys). Optional: MinIO in Docker covers everything locally; only a deployed app needs real S3 for photos | aws.amazon.com | free tier, but signup needs a card |
| 9 | GitHub (for Render to deploy from) | github.com | free — ✅ already set up |
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
| Run one test class | `.\mvnw.cmd test "-Dtest=PropertyCachingTest"` |
| Use port 8081 (Oracle has 8080) | `$env:PORT = "8081"` |
| Run the payment job every 20 s (before starting the app) | `$env:PAYMENT_RECONCILIATION_CRON = "0/20 * * * * *"` |
| Pay with a real Stripe test key (optional) | `$env:STRIPE_SECRET_KEY = "sk_test_…"` |
| A listing's price in dollars too | `curl.exe -s "http://localhost:8081/api/properties/1?currency=USD"` |
| Start the app | `.\mvnw.cmd spring-boot:run` |
| Health check | `curl.exe http://localhost:8081/actuator/health` |
| Which caches exist | `curl.exe http://localhost:8081/actuator/caches` |
| Look inside the database | `docker exec -it rentalhub-postgres psql -U rentalhub -d rentalhub` |
| List tables (inside psql) | `\dt` |
| List cached keys | `docker exec -it rentalhub-redis redis-cli --scan --pattern "rentalhub:*"` |
| Watch Redis commands live | `docker exec -it rentalhub-redis redis-cli MONITOR` |
| Stop / start Redis | `docker stop rentalhub-redis` / `docker start rentalhub-redis` |
| See what's using a port | `Get-NetTCPConnection -LocalPort 8080 -State Listen` |
| Git history | `git log --oneline` |
| Push to GitHub | `git push` |
| Book a stay as user 2 | `curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking.json"` |
| Every booking in the database | `docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT id, property_id, guest_id, check_in, check_out, status, total_amount FROM bookings ORDER BY id;"` |
| Who is waiting for a lock (inside psql) | `SELECT pid, wait_event_type, wait_event, query FROM pg_stat_activity WHERE wait_event_type = 'Lock';` |
| Run only the concurrency tests | `.\mvnw.cmd test "-Dtest=BookingConcurrencyTest"` |
| Run the stale-listing job every minute (this window only; set before starting the app) | `$env:STALE_LISTINGS_CRON = "0 * * * * *"` |
| JSON logs instead of plain text (set before starting the app) | `$env:LOGGING_STRUCTURED_FORMAT_CONSOLE = "ecs"` |
| Undo either of the above | `Remove-Item Env:STALE_LISTINGS_CRON` / `Remove-Item Env:LOGGING_STRUCTURED_FORMAT_CONSOLE` |
| A listing's history, readable | `(Invoke-RestMethod http://localhost:8081/api/properties/1/history -Headers @{ "X-Demo-User-Id" = "1" }) \| Format-List` |
| Every audited transaction (inside psql) | `SELECT rev, to_timestamp(revtstmp / 1000.0) AS changed_at, changed_by FROM revinfo ORDER BY rev;` |
