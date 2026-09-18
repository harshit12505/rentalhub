# RentalHub

Property listing, booking and host-management platform — an Airbnb-style portfolio project.

Java 21 · Spring Boot 4.1 · PostgreSQL 16 + pgvector · Flyway · Redis + Caffeine · Thymeleaf ·
Spring AI (Gemini)

One deployable Spring Boot application. No separate frontend build, no npm, no second service.

> **Learning the project?** Start with [`docs/learning/`](docs/learning/README.md): one teaching
> doc per phase, covering why each technology was chosen, how each technique works, likely
> interview questions, and what to watch on YouTube.

---

## Status

| Phase | What | Status |
|---|---|---|
| 1 | Foundation: build, schema, domain model, Factory pattern, tests | ✅ |
| 2 | Caching: Caffeine + Redis two-tier cache, after-commit invalidation, listings REST API | ✅ |
| 3 | Bookings: transactions, optimistic locking, retry and recover, concurrency tests | ✅ |
| 4 | Auditing (Envers) with listing history, structured logging, nightly stale-listing job, reviews | ✅ |
| 5 | Payments (Stripe test mode, or a built-in simulator), refunds, BigDecimal money maths, prices in other currencies | ✅ |
| 6 | AI: listings indexed as vectors (pgvector), hybrid search, a preference profile, grounded answers from Gemini, favourites | ✅ |
| 7–9 | Extras (S3, i18n, GraphQL, OpenAPI), frontend, deploy | not started |

The app has a REST API for listings, bookings (with payments), reviews, favourites and
recommendations (see [Trying the API](#trying-the-api-powershell)) and no web pages yet. The startup warning `Cannot find template location: classpath:/templates/` is
expected until pages arrive in phase 8.

---

## Running locally (Windows / PowerShell)

**Prerequisites:** JDK 21, Docker Desktop (running). Maven is optional — the wrapper
(`mvnw.cmd`) downloads the right version itself.

1. Start Postgres and Redis:

   ```powershell
   docker compose up -d
   ```

2. Confirm both show `healthy`:

   ```powershell
   docker compose ps
   ```

3. Run the tests. The database tests start their own throwaway Postgres in Docker, so
   Docker Desktop must be running:

   ```powershell
   .\mvnw.cmd test
   ```

4. Start the app. Flyway creates every table on first boot:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

5. Check it is alive — expect `{"status":"UP"}`:

   ```powershell
   curl.exe http://localhost:8080/actuator/health
   ```

6. Look at what Flyway built:

   ```powershell
   docker exec -it rentalhub-postgres psql -U rentalhub -d rentalhub -c "\dt"
   ```

To wipe the local database and start over:

```powershell
docker compose down -v
docker compose up -d
```

### Common problems

| Symptom | Cause | Fix |
|---|---|---|
| `Web server failed to start. Port 8080 was already in use` | Another program owns 8080 — on Windows often Oracle Database's listener (`TNSLSNR`) | Find it: `Get-NetTCPConnection -LocalPort 8080 -State Listen`. Then run on another port: `$env:PORT = "8081"` before `.\mvnw.cmd spring-boot:run`, and use `http://localhost:8081` |
| Tests fail with `Could not find a valid Docker environment` | Docker Desktop isn't running | Start Docker Desktop, wait for "Engine running", re-run |
| App fails with `Validate failed: Migration checksum mismatch for migration version 1` | Your local DB was created by an earlier draft of V1 | `docker compose down -v`, then `docker compose up -d` |
| `Connection refused` to `localhost:5432` | Containers aren't up | `docker compose up -d`, then `docker compose ps` |
| Log shows `cache.shared.unavailable` or `Unable to connect to Redis` | Redis isn't running | The app keeps working without the shared cache. Start it: `docker compose up -d` |
| A `-Dsomething=value` flag is ignored or errors | PowerShell splits unquoted `-D` args at the dot | Quote it: `.\mvnw.cmd test "-Dtest=PropertyFactoryTest"` |
| A booking is refused with `paymentMethodId`: "This field is required." | Since Phase 5 a booking says how it's paid | Add `"paymentMethodId": "pm_card_visa"` to the body |
| `"displayPrice": null` everywhere, or `"exchangeRatesUnavailable": true` | The exchange-rate API couldn't be reached (offline?) | The app tries again every minute; same-currency prices still work |
| `ai.mode ready=false` at startup, and `"aiUsed": false` in answers | No `GEMINI_API_KEY` — the expected state without one | Everything else works. Set the key in the window you start the app from to switch the AI on |
| `ai.listing.embedFailed` or `ai.search.failed` in the log | The key is wrong, revoked, or the free tier's rate limit was hit | Listings and answers keep working; the index job retries every two minutes |

---

## Trying the API (PowerShell)

> **Want to test everything yourself, step by step?** Follow
> [`docs/learning/hands-on-guide.md`](docs/learning/hands-on-guide.md): every feature, with
> the exact command and the expected result. Ready-made request bodies are in
> [`samples/api/`](samples/api/README.md).

| Method | Path | Who | What |
|---|---|---|---|
| `GET` | `/api/properties/{id}` | anyone | One listing. Cached: Caffeine → Redis → database |
| `GET` | `/api/properties?city=&guests=&maxPrice=&currency=&page=&size=` | anyone | One page of search results. `maxPrice` is in `currency` (INR if not given) and compared with each listing in its own currency. Cached in Redis |
| `POST` | `/api/properties` | a host | Create a listing |
| `PUT` | `/api/properties/{id}` | that listing's host | Replace a listing (full new state) |
| `DELETE` | `/api/properties/{id}` | that listing's host | Delete a listing (409 if it has bookings) |
| `POST` | `/api/bookings` | any user | Book and pay for a stay (not at your own listing). Safe against double booking. 201 paid, 202 payment outcome not known yet, 402 card declined, 503 payment provider unavailable |
| `GET` | `/api/bookings` | any user | Your own trips, latest first |
| `GET` | `/api/bookings/{id}` | its guest or the listing's host | One booking |
| `POST` | `/api/bookings/{id}/cancel` | its guest or the listing's host | Cancel, until check-in day. Frees the dates, and refunds a paid booking in full |
| `GET` | `/api/properties/{id}/bookings` | that listing's host | Every booking of the listing |
| `GET` | `/api/properties/{id}/history` | that listing's host | Every change to the listing: when, by whom, what changed (still readable after deletion) |
| `POST` | `/api/properties/{id}/reviews` | a guest whose stay there has ended | Review the listing (once) |
| `GET` | `/api/properties/{id}/reviews` | anyone | The listing's reviews, newest first |
| `GET` | `/api/reviews/{id}` | anyone | One review |
| `PUT` | `/api/reviews/{id}` | its author | Replace the rating and comment |
| `DELETE` | `/api/reviews/{id}` | its author | Delete the review (its history is kept) |
| `PUT` | `/api/properties/{id}/favorite` | any user | Save a listing. Sending it twice leaves one favourite |
| `DELETE` | `/api/properties/{id}/favorite` | any user | Unsave it. Removing one that is not saved is not an error |
| `GET` | `/api/favorites?currency=` | any user | Your saved listings, newest first |
| `GET` | `/api/recommendations?q=&currency=` | any user | Ask in plain English. Always 200, with or without AI |

Every response carries an `X-Request-Id` header. The same id appears on every log line
written while handling that request, so a problem report can be matched to the logs.

**Payments.** No Stripe account is needed.
- **Without `STRIPE_SECRET_KEY`**, payments go to a built-in simulator: bookings say
  `"provider":"SIMULATED"`, and nothing is charged. It answers to Stripe's test
  payment-method ids: `pm_card_visa` succeeds, and `pm_card_visa_chargeDeclined` is declined.
- **With a Stripe test key** (`sk_test_…`) in `STRIPE_SECRET_KEY`, the same requests go to
  Stripe.
- **A live key is refused.**

**Ask in plain English.** `GET /api/recommendations?q=somewhere quiet with a garden in Goa
for 2` reads the city, the party size and any budget with rules, applies them as real SQL
filters, ranks what is left by meaning, and has Gemini write the answer from those listings
only — checking afterwards that every listing it named was really offered.
- **Without `GEMINI_API_KEY`** there is no indexing and no search by meaning: the same
  endpoint answers 200 from an ordinary filtered search, with `"aiUsed": false` and a
  sentence saying so. Questions such as "how much have I spent on bookings?" are answered by
  SQL either way.
- **With a key** (free, no card: [aistudio.google.com/apikey](https://aistudio.google.com/apikey)),
  listings are embedded within a couple of minutes and answers come back with
  `"aiUsed": true`.

**Prices in your currency.** Add `?currency=USD` (or INR, EUR, GBP, AED) to any read.
- Rates come from [ExchangeRate-API](https://www.exchangerate-api.com) (Rates By Exchange
  Rate API), and the app keeps them for an hour.
- The converted figure is for display only. What is charged and stored is always the
  listing's own price.

There is no login: the acting user is sent in an `X-Demo-User-Id` header. There is no demo
data until phase 9, so first create a host by hand (note the `id` it prints):

```powershell
docker exec -it rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO users (full_name, email, role) VALUES ('Asha Menon', 'asha@example.com', 'HOST') RETURNING id;"
```

With the app running on port 8081, build a listing:

```powershell
$villa = @{ type = "VILLA"; title = "Sea breeze villa"; description = "Four bedrooms near the beach."; city = "Goa"; country = "India"; pricePerNight = 12000; currency = "INR"; maxGuests = 8; bedrooms = 4; bathrooms = 3; attributes = @{ plotAreaSqm = "450"; hasPool = "true" } } | ConvertTo-Json
```

Create it as user 1:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/properties -Headers @{ "X-Demo-User-Id" = "1" } -ContentType "application/json" -Body $villa
```

Read it back, then search:

```powershell
Invoke-RestMethod http://localhost:8081/api/properties/1
```

```powershell
Invoke-RestMethod "http://localhost:8081/api/properties?city=goa&guests=4"
```

See what is cached in Redis (the app log also prints `cache.miss` whenever the database is read):

```powershell
docker exec -it rentalhub-redis redis-cli --scan --pattern "rentalhub:*"
```

Now book the villa as a guest. Create one (note the `id`, 2 on a fresh database):

```powershell
docker exec -it rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO users (full_name, email, role) VALUES ('Ravi Kumar', 'ravi@example.com', 'GUEST') RETURNING id;"
```

```powershell
$stay = @{ propertyId = 1; checkIn = "2027-03-10"; checkOut = "2027-03-13"; guests = 2; paymentMethodId = "pm_card_visa" } | ConvertTo-Json
```

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/bookings -Headers @{ "X-Demo-User-Id" = "2" } -ContentType "application/json" -Body $stay
```

The booking comes back `CONFIRMED` and paid (`payment.status` `PAID`, provider `SIMULATED`):
3 nights, `totalAmount` 36000.00 INR. Send the same request again and you get a 409, because
those dates are now taken.

---

## Adding a new property type

1. Add a value to `PropertyType`.
2. Add an entity class extending `Property` with `@DiscriminatorValue` and `typeAttributes()`.
3. Add a `@Component` creator extending `AbstractPropertyCreator`, declaring its
   `AttributeSpec`s and rules.
4. Add a Flyway migration for the new columns.
5. Add translated labels (`property.type.X`, `property.attribute.*`) to the messages files.

No controller, service, form, view or existing creator changes. `PropertyFactoryTest`
fails if the entity, creator and labels disagree; the app refuses to start if a type has
no creator.
