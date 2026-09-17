# Hands-on guide: test RentalHub yourself

Everything built so far (Phases 1–5), tested by you, step by step. Each step has the
exact PowerShell command and what you should see. Every command and expected output here
was run and checked against a fresh database: Parts 0–17 on 13 Sep 2026, Parts 18–26 on
14 Sep 2026, Parts 28–34 on 15 Sep 2026, and Parts 36–44 on 16 Sep 2026. Phase 5 changed
what some earlier parts print (bookings are now paid for, cache keys gained a currency),
so those parts were run again on 16 Sep 2026 and updated.

**Time:** about 45 minutes for Parts 0–17 (Phases 1–2), 30 more for Parts 18–26
(Phase 3), 30 more for Parts 28–34 (Phase 4), and 30 more for Parts 36–44 (Phase 5).
**You'll use three PowerShell windows:**

| Window | Used for |
|---|---|
| **1 — App** | runs the application and shows its log |
| **2 — Commands** | everything you type in this guide |
| **3 — Watcher** | watches Redis live (Part 11); holds a database lock in psql (Parts 24–26) |

In every window, first go to the project folder:

```powershell
cd C:\dev\rentalhub
```

**How to read `curl.exe -i` output.** The first line is the HTTP status (`HTTP/1.1 201`);
then come headers such as `Location:`; then a blank line; then the body, the JSON on one
line. (Always type `curl.exe`: in Windows PowerShell, plain `curl` is a different command.)

---

## Part 0 — Start from a clean slate (recommended)

The ids in this guide (user 1, listing 1, …) assume an empty database. Yours currently
holds 1 user and 1 listing from trying the README. This wipes them:

```powershell
docker compose down -v
```

```powershell
docker compose up -d
```

```powershell
docker compose ps
```

✅ Both `rentalhub-postgres` and `rentalhub-redis` show `Up … (healthy)`.
(If you'd rather keep your data, you can: just use the ids the commands print instead of
the ones written here.)

---

## Part 1 — The automated tests

```powershell
.\mvnw.cmd test
```

✅ Near the end: `Tests run: 269, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.
It takes a minute or two: the test suite starts its own throwaway Postgres and Redis in
Docker.

Run just one test class:

```powershell
.\mvnw.cmd test "-Dtest=PropertyCachingTest"
```

✅ `Tests run: 8, Failures: 0`. (The quotes matter: PowerShell splits `-Dtest=…` otherwise.)

**In IntelliJ:** right-click `src/test/java` → **Run 'All Tests'**. The results are grouped
by the test names, e.g. "villa rules", "updates".

---

## Part 2 — Start the app (Window 1)

```powershell
$env:PORT = "8081"
```

```powershell
.\mvnw.cmd spring-boot:run
```

✅ After ~10 seconds: `Started RentalHubApplication in … seconds`.
A `WARN … Cannot find template location` line is expected (web pages arrive in Phase 8).
**Leave this window running.** Its log is part of the test: watch it.

---

## Part 3 — Is it alive? (Window 2)

```powershell
curl.exe -s http://localhost:8081/actuator/health
```

✅ `{"groups":["liveness","readiness"],"status":"UP"}`

```powershell
curl.exe -s http://localhost:8081/actuator/caches
```

✅ Two caches: `propertyById` (a `TwoLevelCache`, Caffeine + Redis) and `propertySearch`
(Redis).

---

## Part 4 — Look inside the database (Phase 1)

```powershell
docker exec -it rentalhub-postgres psql -U rentalhub -d rentalhub
```

You're now in `psql`, Postgres's own shell (the prompt is `rentalhub=#`). Type:

```
\dt
```

✅ 11 tables: `bookings`, `bookings_aud`, `favorites`, `flyway_schema_history`,
`properties`, `properties_aud`, `property_images`, `reviews`, `reviews_aud`, `revinfo`,
`users`. (The `_aud` tables and `revinfo` hold the audit history, from Phase 4.)

```
SELECT version, description, success FROM flyway_schema_history;
```

✅ Three rows, one per migration, each run exactly once:
```
 version |          description           | success
---------+--------------------------------+---------
 1       | initial schema                 | t
 2       | auditing and review uniqueness | t
 3       | payments                       | t
```

```
\d bookings
```

✅ At the bottom: `no_overlapping_bookings EXCLUDE USING gist (…)`, the double-booking
rule, plus the CHECK constraints. Leave psql with `\q`.

---

## Part 5 — Create users

There is no sign-up and no demo data yet (Phase 9), so create two hosts and a guest by hand:

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO users (full_name, email, role) VALUES ('Asha Menon','asha@example.com','HOST'), ('Ravi Kumar','ravi@example.com','GUEST'), ('Vikram Rao','vikram@example.com','HOST') RETURNING id, full_name, role;"
```

✅
```
 id | full_name  | role
----+------------+-------
  1 | Asha Menon | HOST
  2 | Ravi Kumar | GUEST
  3 | Vikram Rao | HOST
```

---

## Part 6 — Create listings (the factory at work)

The request bodies are ready-made files in `samples/api/` (open them to see what's sent).

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa.json"
```

✅ `HTTP/1.1 201`, then `Location: http://localhost:8081/api/properties/1`, then JSON with
`"type":"VILLA"`, `"host":{"id":1,"fullName":"Asha Menon"}`,
`"attributes":{"plotAreaSqm":450,"hasPool":true}`, `"version":0`.

Notice that the attributes were **sent as text** (`"450"`, `"true"`) but come back as a
number and a boolean: the villa's `AttributeSpec`s parsed them.

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/apartment.json"
```

✅ `201`, `Location: …/api/properties/2`, `"attributes":{"floorNumber":3,"hasElevator":null}`.
`null` means "not stated", which the apartment rules allow on floor 3.

**In Window 1** you'll see `listing.created propertyId=1 …` and
`cache.invalidated propertyId=1 searchPartitions=[city:|*, city:goa|*]`: a new Goa
listing flushes Goa searches and no-city searches.

---

## Part 7 — Read a listing twice (the cache at work)

```powershell
curl.exe -s http://localhost:8081/api/properties/1
```

```powershell
curl.exe -s http://localhost:8081/api/properties/1
```

✅ Both return the villa.
✅ **Window 1 logs `cache.miss cache=propertyById propertyId=1 action=load-from-database`
only once.** The second read never reached the database.

You'll notice the read shows `"pricePerNight":12000.0000` and `"plotAreaSqm":450.00`,
where creating it showed `12000` and `450`. Same values: the database stores 4 decimals
for money and 2 for areas, and `BigDecimal` keeps the number of decimals it was given.
The last field, `"displayPrice":null`, is the price converted into a currency you ask for,
and you didn't ask for one (Part 42).

---

## Part 8 — Search

Run each one; the expected result follows:

```powershell
curl.exe -s "http://localhost:8081/api/properties?city=goa"
```
✅ `"totalElements":1`, the villa.

```powershell
curl.exe -s "http://localhost:8081/api/properties?city=GOA"
```
✅ The same villa. **And no `cache.miss` in Window 1:** "GOA" and "goa" normalise to the
same cache key.

```powershell
curl.exe -s "http://localhost:8081/api/properties?guests=6"
```
✅ Only the villa (the apartment sleeps 2).

```powershell
curl.exe -s "http://localhost:8081/api/properties?maxPrice=3000"
```
✅ Only the apartment (₹2,500).

```powershell
curl.exe -s "http://localhost:8081/api/properties"
```
✅ `"totalElements":2`, newest first: apartment, then villa.

✅ Window 1 shows **4** search `cache.miss` lines for these **5** searches.

✅ The `maxPrice` search also logs
`fx.rates.loaded asOf=… currencies=[INR, USD, EUR, GBP, AED]`. Since Phase 5 a price limit
is compared with listings in every currency, so the app fetched today's exchange rates. It
keeps them for an hour.

---

## Part 9 — The rules say no

Every error comes back in the same standard shape (a "problem detail"), with a
human-readable `detail`, a machine-readable `messageKey` and, where it applies, the `field`.

| # | Try this | Expected status | Expected `messageKey` / detail |
|---|---|---|---|
| 1 | `curl.exe -s -i http://localhost:8081/api/properties/999` | 404 | `property.notFound` — "There is no listing with id 999." |
| 2 | POST `villa-two-guests.json` as user 1 | 400 | `property.villa.guests.min`, field `maxGuests` |
| 3 | POST `apartment-high-floor-no-lift.json` as user 1 | 400 | `property.apartment.elevator.required`, field `attributes[hasElevator]` |
| 4 | POST `apartment-bad-price.json` as user 1 | 400 | `property.price.precision` — "at most 2 decimal places in this currency" |
| 5 | POST `cabin-unknown-heating.json` as user 1 | 400 | `property.attribute.choice` — "Heating must be one of the listed options." |
| 6 | POST `invalid-blank-fields.json` as user 1 | 400 | an `errors` list: `city` and `title` — "This field is required." |
| 7 | POST `villa.json` as **user 2** (a guest) | 403 | `property.create.notHost` |
| 8 | POST `villa.json` with **no** `X-Demo-User-Id` header | 400 | "Required header 'X-Demo-User-Id' is not present." |
| 9 | PUT `villa-renamed.json` to `/1` as **user 3** (another host) | 403 | `property.notOwner` |
| 10 | PUT `studio.json` to `/1` as user 1 | 400 | `property.type.cannotChange` |
| 11 | `curl.exe -s -i http://localhost:8081/api/properties/abc` | 400 | "Failed to convert 'id' with value: 'abc'" |

How to send them (swap the file name, user id or method as the table says):

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa-two-guests.json"
```

```powershell
curl.exe -s -i -X PUT http://localhost:8081/api/properties/1 -H "Content-Type: application/json" -H "X-Demo-User-Id: 3" --data "@samples/api/villa-renamed.json"
```

Case 8 is the first command with the `-H "X-Demo-User-Id: 1"` part removed.

---

## Part 10 — Look inside Redis

```powershell
docker exec rentalhub-redis redis-cli --scan --pattern "rentalhub:*"
```

✅ One listing key and four search keys, one per *distinct* search from Part 8 (Redis
lists them in no particular order):
```
rentalhub:v2:propertyById::1
rentalhub:v2:propertySearch::city:goa|guests:|maxPrice:|currency:|page:0|size:20
rentalhub:v2:propertySearch::city:|guests:6|maxPrice:|currency:|page:0|size:20
rentalhub:v2:propertySearch::city:|guests:|maxPrice:3000|currency:INR|page:0|size:20
rentalhub:v2:propertySearch::city:|guests:|maxPrice:|currency:|page:0|size:20
```
(Search pages expire after 5 minutes, so if you're slow some may already be gone. That's
the TTL doing its job.)

Two things in these keys are from Phase 5:
- **`v2`**: the cached records gained a field (`displayPrice`), so the key prefix moved on
  from `v1`. New code never reads old-shaped JSON.
- **`currency:INR`**: a price limit now has a currency. Without one it's read as rupees.

```powershell
docker exec rentalhub-redis redis-cli GET "rentalhub:v2:propertyById::1"
```

✅ The villa as JSON: this is literally what the cache holds.

```powershell
docker exec rentalhub-redis redis-cli TTL "rentalhub:v2:propertyById::1"
```

✅ A number just under `600`: seconds until it expires by itself.

---

## Part 11 — Watch invalidation live (Window 3)

In **Window 3**, start watching every command Redis receives:

```powershell
docker exec -it rentalhub-redis redis-cli MONITOR
```

✅ It prints `OK` and waits. Each line after that is one command, prefixed with a
timestamp and the client's address.

First, re-run the four distinct Part 8 searches in Window 2, so there are search pages to
flush. Then, in **Window 2**, read the villa twice, quickly:

```powershell
curl.exe -s http://localhost:8081/api/properties/1
```

✅ The second read shows **nothing** in Window 3: it was answered from Caffeine, inside the
app, without asking Redis. (If more than 30 seconds had passed since the last read, the
first read shows a Redis `GET`: the local copy had expired, but Redis still had it.)

Now rename the villa:

```powershell
curl.exe -s -i -X PUT http://localhost:8081/api/properties/1 -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa-renamed.json"
```

✅ `HTTP/1.1 200`, `"title":"Sunset villa"`, `"version":1`.
✅ Window 3 shows the invalidation, in this order:
```
"DEL" "rentalhub:v2:propertyById::1"
"SCAN" "0" "MATCH" "rentalhub:v2:propertySearch::city:|*" "COUNT" "1000"
"DEL" "…city:|guests:6|…" "…city:|guests:|maxPrice:3000|currency:INR|…" "…city:|guests:|maxPrice:|…"
"SCAN" "0" "MATCH" "rentalhub:v2:propertySearch::city:goa|*" "COUNT" "1000"
"DEL" "rentalhub:v2:propertySearch::city:goa|guests:|maxPrice:|currency:|page:0|size:20"
```
That is the listing evicted, the no-city partition flushed, then the Goa partition flushed
(found with SCAN, never KEYS). Pages for any other city would have been left alone.

Read it once more:

```powershell
curl.exe -s http://localhost:8081/api/properties/1
```

✅ `"title":"Sunset villa"`. Window 3 shows a `GET` (a miss) then a `SET … "PX" "600000"`:
the fresh copy stored for 600,000 ms, i.e. 10 minutes.
✅ Window 1 shows `listing.updated propertyId=1 version=1`, `cache.invalidated …` and one
new `cache.miss`.

Stop MONITOR with `Ctrl+C`. You can close Window 3.

---

## Part 12 — Move a listing to another city

```powershell
curl.exe -s "http://localhost:8081/api/properties?city=goa"
```
✅ The villa (this also caches the Goa page again).

```powershell
curl.exe -s -i -X PUT http://localhost:8081/api/properties/1 -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa-moved-to-mumbai.json"
```
✅ `200`, `"city":"Mumbai"`, `"version":2`.

```powershell
curl.exe -s "http://localhost:8081/api/properties?city=goa"
```
✅ `"totalElements":0`. The old city's cached page was flushed, not served stale.

```powershell
curl.exe -s "http://localhost:8081/api/properties?city=mumbai"
```
✅ `"totalElements":1`.

---

## Part 13 — Delete a listing

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/cabin.json"
```
✅ `201`, `Location: …/api/properties/3`, `"heatingType":"WOOD_STOVE"`. You sent `wood_stove`;
the cabin's rules normalised it to upper case.

```powershell
curl.exe -s -i -X DELETE http://localhost:8081/api/properties/3 -H "X-Demo-User-Id: 1"
```
✅ `HTTP/1.1 204`, with no body.

```powershell
curl.exe -s -i http://localhost:8081/api/properties/3
```
✅ `404`, "There is no listing with id 3."

---

## Part 14 — The database's own rules (Phase 1)

These go straight to Postgres with plain SQL, bypassing the app entirely, to prove the
database protects itself. They all use listing 2 (the apartment) and guest 2.

**A confirmed booking, 10th to 15th December:**
```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status) VALUES (2, 2, '2026-12-10', '2026-12-15', 2, 12500, 'INR', 'CONFIRMED');"
```
✅ `INSERT 0 1`

**An overlapping one, 14th to 18th:**
```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status) VALUES (2, 2, '2026-12-14', '2026-12-18', 2, 10000, 'INR', 'PENDING');"
```
✅ `ERROR: conflicting key value violates exclusion constraint "no_overlapping_bookings"`,
and the detail names both date ranges.

**Back-to-back, 15th to 18th (check-in on the previous guest's check-out day):**
```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status) VALUES (2, 2, '2026-12-15', '2026-12-18', 2, 7500, 'INR', 'PENDING');"
```
✅ `INSERT 0 1`. That's the `[)` range at work.

**A zero-night stay:**
```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status) VALUES (2, 2, '2026-12-20', '2026-12-20', 2, 2500, 'INR', 'PENDING');"
```
✅ `ERROR: … violates check constraint "chk_booking_dates"`

**A misspelt status:**
```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status) VALUES (2, 2, '2026-12-22', '2026-12-24', 2, 5000, 'INR', 'CONFIRMD');"
```
✅ `ERROR: … violates check constraint "bookings_status_check"`

**A six-star review:**
```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO reviews (property_id, author_id, rating, comment) VALUES (2, 2, 6, 'Six stars!');"
```
✅ `ERROR: … violates check constraint "reviews_rating_check"`

**What actually got stored:**
```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT id, check_in, check_out, status FROM bookings ORDER BY id;"
```
✅ Two rows: 10th→15th CONFIRMED and 15th→18th PENDING. (The ids may skip numbers:
Postgres used ids for the rejected rows. Id counters never go backwards.)

---

## Part 15 — A listing with bookings can't be deleted

```powershell
curl.exe -s -i -X DELETE http://localhost:8081/api/properties/2 -H "X-Demo-User-Id: 1"
```
✅ `HTTP/1.1 409`, `property.delete.hasBookings`: "This listing has bookings, so it cannot
be deleted."

---

## Part 16 — Pull the plug on Redis

```powershell
docker stop rentalhub-redis
```

```powershell
curl.exe -s -i http://localhost:8081/api/properties/2
```
✅ Still `200` with the apartment.

```powershell
curl.exe -s "http://localhost:8081/api/properties?city=chennai"
```
✅ Still works: `"totalElements":1`.
✅ Window 1 shows `WARN … LoggingCacheErrorHandler : Cache 'propertySearch' failed to get entry …`:
the error is logged, and the request quietly reads the database instead.

```powershell
curl.exe -s -i http://localhost:8081/actuator/health
```
✅ `HTTP/1.1 503`, `"status":"DOWN"`. This is a **known open item**: the app works fine, but
the health check counts Redis. Phase 9 decides what the health check should include,
because Render restarts apps whose health check fails.

Bring Redis back:

```powershell
docker start rentalhub-redis
```

```powershell
curl.exe -s http://localhost:8081/actuator/health
```
✅ `"status":"UP"` again (give it a few seconds).

---

## Part 17 — Watch the cache skip your code (IntelliJ debugger)

This shows the most important idea of Phase 2 with your own eyes.

1. Stop the app in Window 1 (`Ctrl+C`), because IntelliJ will run it instead.
2. In IntelliJ, open `RentalHubApplication.java`, click the green ▶ next to `main` and
   choose **Modify Run Configuration…**. Under **Environment variables**, add
   `PORT=8081`, then click OK.
3. Open `PropertyService.java` and click in the left margin beside the first line inside
   `getListing` (the `log.debug(...)` line). A red dot appears: that's a **breakpoint**.
4. Start the app with the 🐞 **Debug** button.
5. In Window 2, read a listing that isn't cached yet (restart = empty local cache):
   `curl.exe -s http://localhost:8081/api/properties/2`
   ➜ IntelliJ stops on your breakpoint. Look at the **Debugger** panel: you can see `id = 2`.
   Press **Resume** (F9).
6. Run the same `curl.exe` again.
   ➜ **IntelliJ does not stop.** Your method never ran: Spring's cache proxy answered
   before your code was reached. That's what `@Cacheable` does.

---

# Phase 3 — Bookings (Parts 18–26)

These parts need a known starting point: the ids and the listings' version numbers matter.
So they begin by wiping the database.

## Part 18 — A fresh start for bookings

1. Stop the app: `Ctrl+C` in Window 1 (or ⏹ in IntelliJ, if you ran it there in Part 17).
2. In Window 2, wipe the database and start the containers again:

   ```powershell
   docker compose down -v
   ```

   ```powershell
   docker compose up -d
   ```

3. In Window 1, start the app again. The `$env:PORT = "8081"` you set in Part 2 still
   applies in that window; if you opened a new window, set it again first.

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   ✅ `Started RentalHubApplication`. Flyway has rebuilt every table.

4. In Window 2, create two hosts and two guests:

   ```powershell
   docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO users (full_name, email, role) VALUES ('Asha Menon','asha@example.com','HOST'), ('Ravi Kumar','ravi@example.com','GUEST'), ('Vikram Rao','vikram@example.com','HOST'), ('Meera Iyer','meera@example.com','GUEST') RETURNING id, full_name, role;"
   ```

   ✅
   ```
    id | full_name  | role
   ----+------------+-------
     1 | Asha Menon | HOST
     2 | Ravi Kumar | GUEST
     3 | Vikram Rao | HOST
     4 | Meera Iyer | GUEST
   ```

5. Asha lists the villa (listing 1) and the apartment (listing 2):

   ```powershell
   curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa.json"
   ```

   ```powershell
   curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/apartment.json"
   ```

   ✅ `201` each time. (`-o NUL -w "%{http_code}\n"` throws the body away and prints just
   the status.)

---

## Part 19 — Book a stay

Look at the apartment first, and note two things: the price and the version.

```powershell
curl.exe -s http://localhost:8081/api/properties/2
```

✅ `…"pricePerNight":2500.0000,…,"version":0,…`

Ravi (user 2) books it for 10–13 March 2027 (open `samples/api/booking.json` to see what's
sent):

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking.json"
```

✅ `HTTP/1.1 201`, `Location: http://localhost:8081/api/bookings/1`, and:
```
{"id":1,"property":{"id":2,"title":"Marina view apartment","city":"Chennai"},"guest":{"id":2,"fullName":"Ravi Kumar"},"checkIn":"2027-03-10","checkOut":"2027-03-13","nights":3,"guests":2,"totalAmount":7500.00,"currency":"INR","displayTotal":null,"status":"CONFIRMED","payment":{"status":"PAID","provider":"SIMULATED","reference":"sim_pi_…","refundReference":null},"createdAt":"…"}
```

- `"nights":3`: the 10th, 11th and 12th. The check-out morning isn't a night.
- `"totalAmount":7500.00`: 3 × 2,500.00, in the listing's own currency, to 2 decimals.
- `"CONFIRMED"` and `"payment":{"status":"PAID",…}`: the booking was paid for (Phase 5).
  `booking.json` names Stripe's test card `pm_card_visa`. With no Stripe key, the app's
  payment simulator takes the payment, so `provider` is `SIMULATED` and nothing is really
  charged. Part 37 looks at payments properly.

✅ Window 1 shows:
```
cache.invalidated propertyId=2 reason=booking
booking.created bookingId=1 propertyId=2 guestId=2 checkIn=2027-03-10 checkOut=2027-03-13 total=7500.00 currency=INR status=PENDING
payment.started bookingId=1 provider=SIMULATED reference=sim_pi_… amount=7500.00 currency=INR
payment.succeeded bookingId=1 provider=SIMULATED reference=sim_pi_… detail=succeeded
```
The booking is created `PENDING`, which already holds the dates, and becomes `CONFIRMED`
once the payment succeeds.

Now read the apartment again:

```powershell
curl.exe -s http://localhost:8081/api/properties/2
```

✅ `"version":1`. Nothing about the apartment changed, yet its version went up. That's
`OPTIMISTIC_FORCE_INCREMENT` at work. Every booking raises the listing's version, so two
bookings can never both commit from the same starting point.

You see the new version even though the first read had cached version 0. That's because
the booking evicted the cached copy (`reason=booking` in the log).

---

## Part 20 — Who can see a booking

```powershell
curl.exe -s -i http://localhost:8081/api/bookings/1 -H "X-Demo-User-Id: 2"
```

| # | Request | Expected |
|---|---|---|
| 1 | `GET /api/bookings/1` as user 2 (Ravi, the guest): the command above | `200`, the booking |
| 2 | the same, as user 1 (Asha, the listing's host) | `200` |
| 3 | the same, as user 4 (Meera, nothing to do with it) | `403`, `booking.notYours`: "Only the guest who made this booking, or the host of the listing, can see or change it." |
| 4 | `GET /api/bookings` as user 2: Ravi's trips | a list holding booking 1 |
| 5 | `GET /api/bookings` as user 4 | `[]` |
| 6 | `GET /api/properties/2/bookings` as user 1: the host's view of listing 2 | a list holding booking 1 |
| 7 | the same, as user 2 | `403`, `booking.listing.notHost`: "Only the host of this listing can see its bookings." |

```powershell
curl.exe -s http://localhost:8081/api/bookings -H "X-Demo-User-Id: 2"
```

```powershell
curl.exe -s -i http://localhost:8081/api/properties/2/bookings -H "X-Demo-User-Id: 1"
```

---

## Part 21 — The booking rules say no

None of these creates a booking. Send each file as user 2 unless the table says otherwise:

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking-too-many-guests.json"
```

| # | File (user) | Status | `messageKey` / detail |
|---|---|---|---|
| 1 | `booking-too-many-guests.json` | 400 | `booking.guests.tooMany`, field `guests`: "This listing sleeps at most 2 guests." |
| 2 | `booking-checkout-before-checkin.json` | 400 | `booking.checkOut.beforeCheckIn`, field `checkOut` |
| 3 | `booking-past-checkin.json` | 400 | `booking.checkIn.past`, field `checkIn` |
| 4 | `booking-too-long.json` (151 nights) | 400 | `booking.nights.max`: "A single booking can be at most 90 nights." |
| 5 | `booking-missing-fields.json` | 400 | an `errors` list: `checkIn`, `checkOut` and `paymentMethodId` "This field is required.", `guests` "At least one guest must stay." |
| 6 | `booking-villa.json` as **user 1** (Asha owns the villa) | 403 | `booking.ownListing`: "You cannot book your own listing." |
| 7 | `booking-unknown-listing.json` | 404 | `property.notFound`: "There is no listing with id 999." |
| 8 | `booking.json` with **no** `X-Demo-User-Id` header | 400 | "Required header 'X-Demo-User-Id' is not present." |

**A listing that isn't taking bookings.** Deactivate the villa, as Phase 4's scheduled job
will do to expired listings:

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "UPDATE properties SET active = false WHERE id = 1;"
```

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking-villa.json"
```

✅ `409`, `booking.property.inactive`: "This listing is not taking bookings at the moment."
It's a 409, not a 400, because the request itself is fine; the listing's *state* forbids
it. Switch the villa back on:

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "UPDATE properties SET active = true WHERE id = 1;"
```

---

## Part 22 — Already booked, back-to-back, and cancelling

**Meera (user 4) wants 12–15 March, which overlaps Ravi's 10–13:**

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 4" --data "@samples/api/booking-overlap.json"
```

✅ `409`, `booking.dates.unavailable`: "Those dates are already booked. Please choose
different dates." This is the everyday case: Ravi booked *earlier*, and the app's check
saw it.

**Meera takes 13–16 March instead, arriving the morning Ravi leaves:**

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 4" --data "@samples/api/booking-back-to-back.json"
```

✅ `201`, booking 2. Back-to-back stays don't overlap, because check-out day isn't a night
(the `[)` range from Phase 1).

**Meera tries to cancel Ravi's booking:**

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings/1/cancel -H "X-Demo-User-Id: 4"
```

✅ `403`, `booking.notYours`.

**Ravi cancels it himself:**

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings/1/cancel -H "X-Demo-User-Id: 2"
```

✅ `200`, the booking with `"status":"CANCELLED"` and
`"payment":{"status":"REFUNDED",…,"refundReference":"sim_re_…"}`. Since Phase 5, cancelling a
paid booking refunds it in full. Window 1:
```
booking.cancelled bookingId=1 propertyId=2 byUserId=2 refundOwed=true
payment.refunded bookingId=1 provider=SIMULATED refundReference=sim_re_… amount=7500.00 currency=INR
```

Run the same cancel again. ✅ `200` and the same result, with the same `refundReference`:
not an error, and not a second refund. Cancelling is **idempotent**: a client whose
connection dropped can simply send it again.

**Ravi's dates are free again, and Meera books exactly those (`booking.json`):**

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 4" --data "@samples/api/booking.json"
```

✅ `201`, booking 3. Meera's trips (`GET /api/bookings` as user 4) now list booking 2
(13 March), then booking 3 (10 March): latest check-in first.

**What the database holds:**

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT id, property_id AS listing, guest_id AS guest, check_in, check_out, status, payment_status, total_amount FROM bookings ORDER BY id;"
```

✅
```
 id | listing | guest |  check_in  | check_out  |  status   | payment_status | total_amount
----+---------+-------+------------+------------+-----------+----------------+--------------
  1 |       2 |     2 | 2027-03-10 | 2027-03-13 | CANCELLED | REFUNDED       |    7500.0000
  2 |       2 |     4 | 2027-03-13 | 2027-03-16 | CONFIRMED | PAID           |    7500.0000
  3 |       2 |     4 | 2027-03-10 | 2027-03-13 | CONFIRMED | PAID           |    7500.0000
```
The cancelled booking stays on record, as history, and says where its money went. The
database stores 4 decimals (`NUMERIC(19,4)`); the API shows the currency's 2.

---

## Part 23 — Race two bookings yourself

Ravi wants the villa for 1–6 May, Meera for 4–9 May. Send both **at the same moment**:

```powershell
curl.exe -s -Z -w "\n%{http_code}\n" -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking-race-a.json" --next -s -w "\n%{http_code}\n" -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 4" --data "@samples/api/booking-race-b.json"
```

`-Z` (`--parallel`) makes curl send the requests together. `--next` separates the first
request's options from the second's.

✅ **Exactly one `201`** (a 5-night villa booking, `"totalAmount":60000.00`) **and one
`409`.** Never two 201s.

Which message the loser gets depends on timing:
- "Those dates are already booked." means the winner had already committed when the loser
  ran its check. Over HTTP on one laptop this is by far the most common, because one
  request usually finishes a few milliseconds ahead of the other.
- "Those dates were just taken by another guest." means the two really overlapped in time,
  and the database refused the loser's row.

The automated tests force the truly simultaneous case on every run. Parts 24 and 25 let you
force it by hand.

To race again, cancel the winning booking as the host, then rerun the command. Take the id
from the 201, and send `POST /api/bookings/<id>/cancel` with `X-Demo-User-Id: 1`.

---

## Part 24 — Watch a retry happen (you hold the lock)

This is the staged test from `BookingConcurrencyTest`, done by hand. Window 3 plays a host
saving a new price very slowly. You'll open a transaction, change the price, and **not
commit yet**.

**Window 3:** open psql, and keep it open until Part 26:

```powershell
docker exec -it rentalhub-postgres psql -U rentalhub -d rentalhub
```

Type these two lines:

```sql
BEGIN;
UPDATE properties SET price_per_night = 3000, version = version + 1 WHERE id = 2;
```

✅ `BEGIN`, then `UPDATE 1`, and the prompt becomes `rentalhub=*#`. The `*` means you're
inside a transaction. The new price isn't visible to anyone else yet, and your session now
holds the apartment's row lock.

**Window 2:** Ravi books the apartment for 10–13 June:

```powershell
curl.exe -s -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking-price-race.json"
```

✅ **It hangs.** Nothing is broken. The booking has already done its work: it read the
apartment as last committed (₹2,500, version 3) and inserted its row. Now it's
committing, which means raising the version, and your session holds the row.

**Window 3:** ask Postgres what's waiting:

```sql
SELECT wait_event_type, wait_event, query FROM pg_stat_activity WHERE wait_event_type = 'Lock';
```

✅ One row: `Lock | transactionid | update properties set version=$1 where id=$2 and version=$3`.
That's Hibernate's forced version increment, stuck behind your transaction.

**Window 3:** let go:

```sql
COMMIT;
```

✅ **Window 2** returns at once: `201` with `"totalAmount":9000.00`, which is 3 nights at the
**new** price.

✅ **Window 1** shows why:
```
retry.attempt operation=booking retry=1 cause=ObjectOptimisticLockingFailureException
cache.invalidated propertyId=2 reason=booking
booking.created bookingId=… propertyId=2 guestId=2 checkIn=2027-06-10 checkOut=2027-06-13 total=9000.00 currency=INR status=PENDING
payment.started bookingId=… provider=SIMULATED reference=sim_pi_… amount=9000.00 currency=INR
payment.succeeded bookingId=… provider=SIMULATED reference=sim_pi_… detail=succeeded
```

What happened, step by step:
1. Your COMMIT moved the version from 3 to 4.
2. The booking's `UPDATE … WHERE version = 3` then matched no row. It had lost the race, so its
   whole transaction was rolled back, booking row included.
3. About 50 ms later, the retry started a **new** transaction and read the apartment again
   (₹3,000, version 4).
4. The retry succeeded.
5. Only then was the payment taken (Phase 5): once, at the new price.

A booking is never charged from a price that changed underneath it.

Check the apartment with `curl.exe -s http://localhost:8081/api/properties/2`.
✅ `"pricePerNight":3000.0000` and `"version":5`. (In the bookings table this booking's id
skips one number. The rolled-back first attempt used that id, and ids are never reused.)

---

## Part 25 — Watch the database catch what the check can't

Now you'll be Meera, booking 10–13 July in a transaction you haven't committed yet. The
app's "already booked?" check only sees committed bookings, so it can't see yours.

**Window 3** (your COMMIT above ended the last transaction):

```sql
BEGIN;
INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status) VALUES (2, 4, '2027-07-10', '2027-07-13', 2, 9000, 'INR', 'CONFIRMED');
```

✅ `INSERT 0 1`, and the prompt shows `rentalhub=*#`.

**Window 2:** Ravi asks for the same dates:

```powershell
curl.exe -s -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking-constraint-race.json"
```

✅ It hangs. The app's check found the dates free, because it can't see your row, so the
app inserted. The exclusion constraint *can* see your uncommitted row, but it can't decide
until it knows whether you'll commit. So it waits.

**Window 3:** the same `SELECT … FROM pg_stat_activity …` as in Part 24 now shows
`Lock | transactionid | insert into bookings (check_in,check_out,…) values ($1,$2,…) RETURNING *`.

**Window 3:**

```sql
COMMIT;
```

✅ **Window 2:** `409`, `booking.dates.justTaken`: "Those dates were just taken by another
guest. Please choose different dates."

✅ **Window 1:**
```
WARN  … SQLState: 23P01
WARN  … ERROR: conflicting key value violates exclusion constraint "no_overlapping_bookings"
INFO  … booking.race.lost propertyId=2 guestId=2 checkIn=2027-07-10 checkOut=2027-07-13 reason=overlap-constraint
```

The database's own error (SQLState 23P01) became a message the guest can act on. It isn't
retried: a retry could only say "already booked". And no card was touched: the payment only
starts once the dates are safely held.

**The other ending.** In Window 3, start a booking for 10–13 September, then change your mind:

```sql
BEGIN;
INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status) VALUES (2, 4, '2027-09-10', '2027-09-13', 2, 9000, 'INR', 'CONFIRMED');
```

In Window 2, Ravi asks for those dates, and his request hangs:

```powershell
curl.exe -s -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking-rollback-race.json"
```

In Window 3:

```sql
ROLLBACK;
```

✅ Window 2: `201` for Ravi, `"totalAmount":9000.00`. Your row never existed, so the
constraint let his through. That's why it had to wait: until you decided, nobody could
know the answer.

---

## Part 26 — Two edits at once: 409, not 500

In Phase 2, two saves of one listing at the same moment gave the loser a `500`. Now:

**Window 3:** hold the villa's row:

```sql
BEGIN;
UPDATE properties SET version = version + 1 WHERE id = 1;
```

**Window 2:** Asha renames the villa, and her request hangs:

```powershell
curl.exe -s -i -X PUT http://localhost:8081/api/properties/1 -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa-renamed.json"
```

**Window 3:**

```sql
COMMIT;
```

✅ **Window 2:** `HTTP/1.1 409`, `error.concurrentUpdate`: "Someone else changed this at the
same moment. Reload it and try again."

✅ **Window 1:**
`request.conflict reason=concurrent-update error="Unexpected row count (expected row count 1 but was 0) [update properties set … where id=? and version=?] …"`.
Her `UPDATE … WHERE version = …` matched no row.

Send the same PUT again. ✅ `200`, `"title":"Sunset villa"`. Nothing was saved the first time,
so trying again is safe. That's what a 409 tells a client.

Leave psql with `\q`. You can close Window 3.

---

## Part 27 — (moved)

Clean-up is now at the very end, in Part 35.

---

# Phase 4 — Auditing, logging and the nightly job (Parts 28–34)

Another fresh start, so that every listing's history begins at its creation.

## Part 28 — A fresh start, with the nightly job running every minute

1. Stop the app: `Ctrl+C` in Window 1.
2. In Window 2, wipe the database and start the containers again:

   ```powershell
   docker compose down -v
   ```

   ```powershell
   docker compose up -d
   ```

3. In Window 1, make the nightly job run at the start of every minute instead of at
   03:15, so you can watch it in Part 33. The setting lasts only as long as this window.

   ```powershell
   $env:STALE_LISTINGS_CRON = "0 * * * * *"
   ```

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   ✅ Among the startup lines, Flyway runs every migration (V3 is Phase 5's):
   ```
   Migrating schema "public" to version "1 - initial schema"
   Migrating schema "public" to version "2 - auditing and review uniqueness"
   Migrating schema "public" to version "3 - payments"
   Successfully applied 3 migrations to schema "public", now at version v3
   ```
   ✅ Then, at the start of every minute (with today's date):
   ```
   … [   scheduling-1]          c.rentalhub.scheduling.StaleListingJob   : job.staleListings.finished availableUntilBefore=2026-09-15 deactivated=0 failed=0 propertyIds=[] durationMs=98
   ```
   The blank column after the thread name is where a request id goes. The job isn't
   answering a request, so it has none.

4. In Window 2, create the same four users and two listings as in Part 18:

   ```powershell
   docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO users (full_name, email, role) VALUES ('Asha Menon','asha@example.com','HOST'), ('Ravi Kumar','ravi@example.com','GUEST'), ('Vikram Rao','vikram@example.com','HOST'), ('Meera Iyer','meera@example.com','GUEST') RETURNING id, full_name, role;"
   ```

   ```powershell
   curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa.json"
   ```

   ```powershell
   curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/apartment.json"
   ```

   ✅ Users 1–4 as in Part 18, then `201` twice: listing 1 is the villa, listing 2 the
   apartment.

---

## Part 29 — Every request gets an id

```powershell
curl.exe -s -i http://localhost:8081/api/properties/1
```

✅ Among the headers there's `X-Request-Id: e62054f9`. Yours will differ: it's 8 random hex
characters.

✅ In Window 1, the line logged while answering the request carries the same id, right
after the thread name:
```
… DEBUG [nio-8081-exec-6] e62054f9 com.rentalhub.service.PropertyService    : cache.miss cache=propertyById propertyId=1 action=load-from-database
```
Every log line of a request carries its id, so when someone reports "I got an error", the
id in their response finds every line of that request.

Now send an id of your own:

```powershell
curl.exe -s -i http://localhost:8081/api/properties/1 -H "X-Request-Id: my-test-1"
```

✅ `X-Request-Id: my-test-1`. It was kept because it looks like an id. Window 1 stays quiet
this time: the answer came from the cache, so none of our code ran. An id with odd
characters, such as a line break someone hopes will forge a fake log line, is replaced by a
fresh one ([04 — Auditing](04-auditing.md), §6).

---

## Part 30 — A listing's history

Asha renames the villa, then moves it to Mumbai at a higher price:

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X PUT http://localhost:8081/api/properties/1 -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa-renamed.json"
```

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X PUT http://localhost:8081/api/properties/1 -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa-moved-to-mumbai.json"
```

✅ `200` twice. Now read the villa's history, in a readable form:

```powershell
(Invoke-RestMethod http://localhost:8081/api/properties/1/history -Headers @{ "X-Demo-User-Id" = "1" }) | Select-Object revision, type, changedByName, @{ n = "changes"; e = { ($_.changes | ForEach-Object { "$($_.field): $($_.from) -> $($_.to)" }) -join "; " } } | Format-List
```

✅
```
revision      : 1
type          : CREATED
changedByName : Asha Menon
changes       : type:  -> VILLA; title:  -> Sea breeze villa; description:  -> Four bedrooms, two minutes from the
                beach.; city:  -> Goa; country:  -> India; pricePerNight:  -> 12000.0000; currency:  -> INR;
                maxGuests:  -> 8; bedrooms:  -> 4; bathrooms:  -> 3; active:  -> True; hostId:  -> 1;
                attributes[plotAreaSqm]:  -> 450.00; attributes[hasPool]:  -> True

revision      : 3
type          : UPDATED
changedByName : Asha Menon
changes       : title: Sea breeze villa -> Sunset villa

revision      : 4
type          : UPDATED
changedByName : Asha Menon
changes       : description: Four bedrooms, two minutes from the beach. -> Four bedrooms, now by the sea in Mumbai.;
                city: Goa -> Mumbai; pricePerNight: 12000.0000 -> 15000.0000
```

- Each entry says when and who, and lists only the fields that actually changed.
- Revision 2 is missing because it was the apartment's creation: revision numbers are shared
  by every audited table.
- **The parentheses around `Invoke-RestMethod` matter.** Windows PowerShell 5.1 passes a JSON
  list down the pipeline as one single object unless the call is wrapped in `( … )`, and
  then every field comes out empty.

The same history as raw JSON:

```powershell
curl.exe -s http://localhost:8081/api/properties/1/history -H "X-Demo-User-Id: 1"
```

✅ `[{"revision":1,"changedAt":"2026-09-15T15:37:32.092Z","changedBy":"user:1","changedByName":"Asha Menon","type":"CREATED","changes":[{"field":"type","from":null,"to":"VILLA"},…`

Who else may read it:

| Try | Expected |
|---|---|
| the same `curl.exe` with `X-Demo-User-Id: 3` (Vikram, another host) | `403`, `property.history.notHost`: "Only the host of this listing can see its history." |
| `/api/properties/999/history` as user 1 | `404`, `property.notFound` |

---

## Part 31 — Inside the audit tables

Ravi (user 2) books the apartment:

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking.json"
```

✅ `201`. The apartment's history still has just one entry, its creation:

```powershell
(Invoke-RestMethod http://localhost:8081/api/properties/2/history -Headers @{ "X-Demo-User-Id" = "1" }) | Measure-Object | Select-Object -ExpandProperty Count
```

✅ `1`. A booking raises the listing's version (Phase 3), but it isn't a change to the
listing, so it isn't in the listing's history.

**Every audited transaction so far** (Envers calls each one a revision):

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT rev, to_timestamp(revtstmp / 1000.0) AS changed_at, changed_by FROM revinfo ORDER BY rev;"
```

✅ (your times will differ)
```
 rev |         changed_at         | changed_by
-----+----------------------------+------------
   1 | 2026-09-15 20:41:25.726+00 | user:1
   2 | 2026-09-15 20:41:26.419+00 | user:1
   3 | 2026-09-15 20:41:26.669+00 | user:1
   4 | 2026-09-15 20:41:26.727+00 | user:1
   5 | 2026-09-15 20:41:27.262+00 | user:2
   6 | 2026-09-15 20:41:27.287+00 | user:2
   7 | 2026-09-15 20:41:27.305+00 | user:2
```
Revisions 5–7 are Ravi's one booking: since Phase 5 it's held, its payment recorded, then
confirmed, each in a transaction of its own (Part 37).

**The villa's history rows, as Envers stores them.** Whole snapshots; the history view
works out the differences.

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT rev, revtype, title, city, price_per_night, active FROM properties_aud WHERE id = 1 ORDER BY rev;"
```

✅
```
 rev | revtype |      title       |  city  | price_per_night | active
-----+---------+------------------+--------+-----------------+--------
   1 |       0 | Sea breeze villa | Goa    |      12000.0000 | t
   3 |       1 | Sunset villa     | Goa    |      12000.0000 | t
   4 |       1 | Sunset villa     | Mumbai |      15000.0000 | t
```
`revtype` 0 means created, 1 updated, 2 deleted.

**The booking has a history of its own:**

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT a.id, a.rev, a.revtype, a.status, a.payment_status, r.changed_by FROM bookings_aud a JOIN revinfo r ON r.rev = a.rev ORDER BY a.rev;"
```

✅
```
 id | rev | revtype |  status   | payment_status | changed_by
----+-----+---------+-----------+----------------+------------
  1 |   5 |       0 | PENDING   | UNPAID         | user:2
  1 |   6 |       1 | PENDING   | UNPAID         | user:2
  1 |   7 |       1 | CONFIRMED | PAID           | user:2
```
Created, then changed twice: the payment's id was recorded (revision 6), then it was paid.

---

## Part 32 — Reviews

**Ravi tries to review the apartment.** His booking is next year, so he hasn't stayed yet:

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/properties/2/reviews -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/review.json"
```

✅ `403`, `review.notStayed`: "You can review a listing only after a stay there has ended."

**A stay that ended two days ago.** This uses SQL, because the booking API rightly refuses
past dates:

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status) VALUES (2, 2, CURRENT_DATE - 5, CURRENT_DATE - 2, 2, 7500, 'INR', 'CONFIRMED');"
```

✅ `INSERT 0 1`. Now run the same review command again.

✅ `HTTP/1.1 201`, `Location: http://localhost:8081/api/reviews/1`, and:
```
{"id":1,"propertyId":2,"author":{"id":2,"fullName":"Ravi Kumar"},"rating":5,"comment":"Bright flat, spotless, and the host was lovely.","createdAt":"…"}
```

**The rules say no:**

| # | Try (the same command, changed as shown) | Status | `messageKey` / detail |
|---|---|---|---|
| 1 | Ravi reviews again | 409 | `review.alreadyReviewed`: "You have already reviewed this listing. Edit your review instead." |
| 2 | Meera (`X-Demo-User-Id: 4`), who never stayed | 403 | `review.notStayed` |
| 3 | `review-bad-rating.json` (6 stars) as Ravi | 400 | an `errors` list: `rating`, "Rating must be a whole number from 1 to 5." |

**Editing:** only the author may.

```powershell
curl.exe -s -i -X PUT http://localhost:8081/api/reviews/1 -H "Content-Type: application/json" -H "X-Demo-User-Id: 4" --data "@samples/api/review-edit.json"
```

✅ `403`, `review.notAuthor`: "Only the guest who wrote this review can change it." Now the
same command with `X-Demo-User-Id: 2`:

✅ `200`, with `"rating":4` and `"comment":"Bright flat and a lovely host, but the street is noisy at night."`

✅ Window 1 shows both events:
```
review.created reviewId=1 propertyId=2 authorId=2 rating=5
review.updated reviewId=1 propertyId=2 authorId=2 previousRating=5 rating=4
```

**Anyone can read a listing's reviews:**

```powershell
curl.exe -s http://localhost:8081/api/properties/2/reviews
```

✅ A list with the one review, now 4 stars.

**The review's history keeps both versions:**

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT a.id, a.rev, a.revtype, a.rating, r.changed_by FROM reviews_aud a JOIN revinfo r ON r.rev = a.rev ORDER BY a.rev;"
```

✅
```
 id | rev | revtype | rating | changed_by
----+-----+---------+--------+------------
  1 |   8 |       0 |      5 | user:2
  1 |   9 |       1 |      4 | user:2
```

---

## Part 33 — Watch the nightly job

Make yesterday the villa's last available day. This uses SQL, because the API refuses a date
in the past, and a date that has passed is exactly what the job looks for:

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "UPDATE properties SET available_until = CURRENT_DATE - 1 WHERE id = 1;"
```

✅ `UPDATE 1`. Now watch Window 1 until the next minute starts (at most 60 seconds):

✅ With yesterday's and today's dates in place of these:
```
… [   scheduling-1]          com.rentalhub.service.PropertyService    : listing.deactivated propertyId=1 availableUntil=2026-09-14 reason=availability-ended
… [   scheduling-1]          c.rentalhub.scheduling.StaleListingJob   : job.staleListings.finished availableUntilBefore=2026-09-15 deactivated=1 failed=0 propertyIds=[1] durationMs=73
```

The villa is off the market:

```powershell
(Invoke-RestMethod http://localhost:8081/api/properties/1).active
```

✅ `False`. The cached copy was evicted, just as for a host's edit, because the job goes
through `PropertyService` too.

Look at the last entry in the villa's history:

```powershell
(Invoke-RestMethod http://localhost:8081/api/properties/1/history -Headers @{ "X-Demo-User-Id" = "1" }) | Select-Object -Last 1 | Select-Object revision, type, changedBy, changedByName, @{ n = "changes"; e = { ($_.changes | ForEach-Object { "$($_.field): $($_.from) -> $($_.to)" }) -join "; " } } | Format-List
```

✅
```
revision      : 10
type          : UPDATED
changedBy     : system:stale-listing-job
changedByName :
changes       : active: True -> False; availableUntil:  -> 2026-09-14
```

- **The job is named as the one who made the change,** not a person.
- **Why does `availableUntil` show up in the job's entry?** You set it with plain SQL, which
  Envers never sees. The next audited change (the job's) found the date different from the
  last snapshot and reported it. **The audit trail only sees changes made through the
  application.** That's why production code never changes data behind its back
  ([04 — Auditing](04-auditing.md), §5).

At the next minute: `deactivated=0` again. Nothing is left to do, so running the job again
changes nothing.

---

## Part 34 — Logs as JSON

On Render, the logs are JSON: one object per line, which log tools can filter by field. You
can switch your console to the same format.

1. Stop the app (`Ctrl+C` in Window 1), then:

   ```powershell
   $env:LOGGING_STRUCTURED_FORMAT_CONSOLE = "ecs"
   ```

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   ✅ Every log line is now a JSON object, including the job's, once a minute.

2. In Window 2, Meera books 13–16 March, with a request id you choose:

   ```powershell
   curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 4" -H "X-Request-Id: json-demo-1" --data "@samples/api/booking-back-to-back.json"
   ```

   ✅ `201` and `X-Request-Id: json-demo-1`. In Window 1, among that request's lines, this
   one (shown here broken up to fit):
   ```json
   {"@timestamp":"2026-09-15T20:44:02.718347600Z","log":{"level":"INFO","logger":"com.rentalhub.service.BookingService"},
    "process":{"pid":21672,"thread":{"name":"http-nio-8081-exec-1"}},"service":{"name":"rentalhub","version":"1.0.0","node":{}},
    "message":"booking.created","userId":"4","requestId":"json-demo-1","bookingId":3,"propertyId":2,"guestId":4,
    "checkIn":"2027-03-13","checkOut":"2027-03-16","total":7500.00,"currency":"INR","status":"PENDING","ecs":{"version":"8.11"}}
   ```
   The message is just the event's name. Every detail is a field of its own (`bookingId`,
   `total`, …), and so are the request id and user from the MDC. Booking 3, because the
   past stay you created in Part 32 is booking 2. The next two lines, `payment.started` and
   `payment.succeeded`, carry the same `requestId`: one id follows the whole booking,
   payment included.

3. Back to normal. Stop the app, clear both settings, and start it again:

   ```powershell
   Remove-Item Env:LOGGING_STRUCTURED_FORMAT_CONSOLE
   ```

   ```powershell
   Remove-Item Env:STALE_LISTINGS_CRON
   ```

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   ✅ Plain log lines again, and no job run every minute. It's back to 03:15.

---

## Part 35 — (moved)

Clean-up is now at the very end, in Part 45.

---

# Phase 5 — Payments, refunds and currencies (Parts 36–44)

One more fresh start. The payment reconciliation job normally runs every 5 minutes and
leaves a payment alone for 10 minutes; here you'll make it run every 20 seconds and give up
waiting after 30, so Part 40 doesn't take a coffee break.

## Part 36 — A fresh start, with payments

1. Stop the app: `Ctrl+C` in Window 1.
2. In Window 2, wipe the database and start the containers again:

   ```powershell
   docker compose down -v
   ```

   ```powershell
   docker compose up -d
   ```

3. In Window 1, set the two payment settings, then start the app. They last only as long as
   this window.

   ```powershell
   $env:PAYMENT_RECONCILIATION_CRON = "0,20,40 * * * * *"
   ```

   ```powershell
   $env:PAYMENT_STALE_AFTER = "30s"
   ```

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   ✅ Flyway runs all three migrations, and then, among the startup lines:
   ```
   payments.mode provider=SIMULATED reason=no STRIPE_SECRET_KEY: nothing is charged
   ```
   **No Stripe account is needed.** Without a key, payments go to the app's own simulator,
   which answers to Stripe's test card ids and charges nobody. Every booking it handles says
   `SIMULATED`, so you can always tell. (With a real test key, the same requests go to
   Stripe: see [05 — Payments](05-payments.md), "If you get a Stripe test key".)

4. In Window 2, create the same four users and two listings as in Part 18:

   ```powershell
   docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO users (full_name, email, role) VALUES ('Asha Menon','asha@example.com','HOST'), ('Ravi Kumar','ravi@example.com','GUEST'), ('Vikram Rao','vikram@example.com','HOST'), ('Meera Iyer','meera@example.com','GUEST') RETURNING id, full_name, role;"
   ```

   ```powershell
   curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa.json"
   ```

   ```powershell
   curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/apartment.json"
   ```

   ✅ Users 1–4, then `201` twice: listing 1 is the villa, listing 2 the apartment.

---

## Part 37 — Pay for a booking

`samples/api/booking.json` now also says which card to pay with:
`"paymentMethodId": "pm_card_visa"`. That's Stripe's test card that always succeeds, and the
simulator answers to it too.

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking.json"
```

✅ `HTTP/1.1 201`, `Location: …/api/bookings/1`, and:
```
{"id":1,"property":{"id":2,…},"guest":{"id":2,"fullName":"Ravi Kumar"},"checkIn":"2027-03-10","checkOut":"2027-03-13","nights":3,"guests":2,"totalAmount":7500.00,"currency":"INR","displayTotal":null,"status":"CONFIRMED","payment":{"status":"PAID","provider":"SIMULATED","reference":"sim_pi_177cdd90cf024873b5de6836","refundReference":null},"createdAt":"…"}
```

- `"status":"CONFIRMED"` **and** `"payment":{"status":"PAID"}`: the stay is on, and the money
  is in. They're two different questions, so they're two fields.
- `"reference":"sim_pi_…"` is the payment's id at the provider. With a real Stripe key it
  would be `pi_…`, and you could find it in the Stripe dashboard.

✅ Window 1 shows the three steps of the payment:
```
booking.created bookingId=1 propertyId=2 guestId=2 checkIn=2027-03-10 checkOut=2027-03-13 total=7500.00 currency=INR status=PENDING
payment.started bookingId=1 provider=SIMULATED reference=sim_pi_… amount=7500.00 currency=INR
payment.succeeded bookingId=1 provider=SIMULATED reference=sim_pi_… detail=succeeded
```

**What the row holds:**

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT id, status, payment_status, payment_provider, payment_reference, total_amount, currency FROM bookings;"
```

✅
```
 id |  status   | payment_status | payment_provider |        payment_reference        | total_amount | currency
----+-----------+----------------+------------------+---------------------------------+--------------+----------
  1 | CONFIRMED | PAID           | SIMULATED        | sim_pi_177cdd90cf024873b5de6836 |    7500.0000 | INR
```

**And its history, one row per transaction:**

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT a.rev, a.status, a.payment_status, a.payment_reference IS NOT NULL AS has_payment, r.changed_by FROM bookings_aud a JOIN revinfo r ON r.rev = a.rev WHERE a.id = 1 ORDER BY a.rev;"
```

✅
```
 rev |  status   | payment_status | has_payment | changed_by
-----+-----------+----------------+-------------+------------
   3 | PENDING   | UNPAID         | f           | user:2
   4 | PENDING   | UNPAID         | t           | user:2
   5 | CONFIRMED | PAID           | t           | user:2
```

Read it downwards: the booking was **held** (its dates are now blocked, nothing paid), then
the payment's id was **recorded**, then it was **paid**. Recording the id before any money
can move is what makes Part 40 possible.

---

## Part 38 — A card that's declined

`booking-declined.json` asks for 20–23 March with `pm_card_visa_chargeDeclined`.

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking-declined.json"
```

✅ `HTTP/1.1 402`:
```
{"detail":"The card was declined, and nothing was charged. Please try a different card.","instance":"/api/bookings","status":402,"title":"Payment failed","messageKey":"payment.declined"}
```
402 Payment Required is the status Stripe itself uses for card errors.

✅ Window 1: `payment.declined bookingId=2 provider=SIMULATED reference=sim_pi_… detail=card_declined`

**The attempt stays on record, cancelled:**

```powershell
(Invoke-RestMethod http://localhost:8081/api/bookings -Headers @{ "X-Demo-User-Id" = "2" }) | Select-Object id, checkIn, status, @{ n = "payment"; e = { $_.payment.status } } | Format-Table
```

✅
```
id checkIn    status    payment
-- -------    ------    -------
 2 2027-03-20 CANCELLED FAILED
 1 2027-03-10 CONFIRMED PAID
```

Why keep booking 2 at all? Because the provider has a payment attempt that names it, and
because "why was I not charged?" deserves an answer. It's cancelled, so it holds no dates.

**And the dates are free at once.** Meera takes them:

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 4" --data "@samples/api/booking-after-decline.json"
```

✅ `201` (booking 3), for the very dates the declined attempt had held a second earlier.

---

## Part 39 — Three more ways a payment can fail

Send each as user 2, the same way as above:

| # | File | Expected |
|---|---|---|
| 1 | `booking-3ds.json` | `402`, `payment.authenticationRequired`: "This card needs the bank to approve the payment (3-D Secure), which is not supported yet." |
| 2 | `booking-provider-down.json` | `503` with a `Retry-After: 60` header, `payment.unavailable`: "The payment could not be taken right now, and nothing was charged." |
| 3 | `booking-card-number.json` | `400`, field `paymentMethodId`: "Give a payment method id, such as pm_card_visa." |

- **3-D Secure** is the bank asking the cardholder to approve the payment. That needs a
  browser, which this API doesn't have, so the payment is refused cleanly instead of hanging.
- **The 503** is "not your fault, try again shortly": the provider refused the request, and
  nothing was charged. `Retry-After` says when.
- **Case 3 never reaches the provider.** A card number is not a payment-method id; a real
  client gets its id from Stripe's own form, so card numbers never touch this server.

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT id, check_in, status, payment_status FROM bookings ORDER BY id;"
```

✅
```
 id |  check_in  |  status   | payment_status
----+------------+-----------+----------------
  1 | 2027-03-10 | CONFIRMED | PAID
  2 | 2027-03-20 | CANCELLED | FAILED
  3 | 2027-03-20 | CONFIRMED | PAID
  4 | 2027-03-26 | CANCELLED | FAILED
  5 | 2027-03-26 | CANCELLED | FAILED
```

Bookings 4 and 5 are the **same dates**: the 3-D Secure attempt released them, so the next
attempt could have them. No failed payment ever leaves dates blocked.

---

## Part 40 — The answer that never came

The worst case in payments isn't "declined", it's **no answer**: the request timed out, and
the card may or may not have been charged. The simulator does this on demand with
`pm_sim_noAnswer` (`booking-no-answer.json`, the villa for 1–6 April): it takes the money and
"loses" the reply.

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking-no-answer.json"
```

✅ `HTTP/1.1 202` — **Accepted**, not Created:
```
{"id":6,…,"totalAmount":60000.00,"currency":"INR","displayTotal":null,"status":"PENDING","payment":{"status":"UNPAID","provider":"SIMULATED","reference":"sim_pi_…","refundReference":null},"createdAt":"…"}
```
✅ Window 1: `payment.undecided bookingId=6 … detail=simulated: the answer was lost`

**The dates stay held**, because the guest may well have paid. Meera tries them:

```powershell
curl.exe -s -w "\n%{http_code}\n" -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 4" --data "@samples/api/booking-villa.json"
```

✅ `409`, `booking.dates.unavailable`.

**And Ravi can't cancel it yet**, because that would race the payment's own outcome:

```powershell
curl.exe -s -w "\n%{http_code}\n" -X POST http://localhost:8081/api/bookings/6/cancel -H "X-Demo-User-Id: 2"
```

✅ `409`, `booking.cancel.paymentPending`: "The payment for this booking is still being
processed. Try again in a few minutes."

**Now wait about a minute** (30 seconds before the booking counts as stuck, then the job's
next run).

✅ Window 1:
```
payment.reconciled bookingId=6 settlement=CONFIRMED detail=succeeded
job.paymentReconciliation.finished createdBefore=… confirmed=[6] released=[] stillPending=[] refunded=[] refundsStillOwed=[] failed=[] durationMs=44
```
The job asked the provider what had happened to `sim_pi_…`, was told "succeeded", and
finished the booking off.

```powershell
curl.exe -s http://localhost:8081/api/bookings/6 -H "X-Demo-User-Id: 2"
```

✅ `"status":"CONFIRMED"`, `"payment":{"status":"PAID",…}`.

**And the history says who did it:**

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT a.rev, a.status, a.payment_status, r.changed_by FROM bookings_aud a JOIN revinfo r ON r.rev = a.rev WHERE a.id = 6 ORDER BY a.rev;"
```

✅
```
 rev |  status   | payment_status |            changed_by
-----+-----------+----------------+-----------------------------------
  18 | PENDING   | UNPAID         | user:2
  19 | PENDING   | UNPAID         | user:2
  20 | CONFIRMED | PAID           | system:payment-reconciliation-job
```

In production the job runs every 5 minutes and leaves a payment alone for 10, which is far
longer than any live payment can take. Its quiet runs (nothing to do) are logged at `DEBUG`,
so you'll see them in this window but not on a real server.

---

## Part 41 — Cancel, and get your money back

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings/1/cancel -H "X-Demo-User-Id: 2"
```

✅ `200`, with `"status":"CANCELLED"`, `"payment":{"status":"REFUNDED",…,"refundReference":"sim_re_…"}`.

✅ Window 1:
```
booking.cancelled bookingId=1 propertyId=2 byUserId=2 refundOwed=true
payment.refunded bookingId=1 provider=SIMULATED refundReference=sim_re_… amount=7500.00 currency=INR
```

The cancellation is committed first (the dates are free immediately), and the refund is asked
for after it. If the refund had failed, the booking would stay `CANCELLED` with its payment
still `PAID` — a refund owed — and the reconciliation job would try again.

Run the same cancel again:

```powershell
curl.exe -s -X POST http://localhost:8081/api/bookings/1/cancel -H "X-Demo-User-Id: 2"
```

✅ The same answer, with the **same** `refundReference`. No second refund: each step of a
payment carries an idempotency key, so repeating it is safe.

---

## Part 42 — Prices in another currency

```powershell
curl.exe -s "http://localhost:8081/api/properties/2?currency=USD"
```

✅ The apartment, with its own price untouched and a converted one beside it:
```
…"pricePerNight":2500.0000,"currency":"INR",…,"displayPrice":{"amount":26.04,"currency":"USD","rate":0.010414,"ratesAsOf":"2026-09-17T00:02:31Z"}
```
Your numbers will differ: these are real rates, updated once a day.

✅ Window 1, the first time only:
`fx.rates.loaded asOf=… currencies=[INR, USD, EUR, GBP, AED]`. The rates are then kept for an
hour.

**Two listings priced abroad**, by Vikram (user 3):

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 3" --data "@samples/api/studio-dubai.json"
```

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 3" --data "@samples/api/apartment-london.json"
```

✅ `201` twice: listing 3 is a studio at **AED 300**, listing 4 a flat at **£95**.

**"Under $100 a night", wherever they're priced:**

```powershell
(Invoke-RestMethod "http://localhost:8081/api/properties?maxPrice=100&currency=USD").content | Select-Object id, title, pricePerNight, currency, @{ n = "inUSD"; e = { $_.displayPrice.amount } } | Format-Table
```

✅
```
id title                 pricePerNight currency inUSD
-- -----                 ------------- -------- -----
 3 Marina studio              300.0000 AED      81.69
 2 Marina view apartment     2500.0000 INR      26.04
```
The villa (₹12,000 ≈ $125) and the London flat (£95 ≈ $128) are over the limit. Before
Phase 5 this filter compared the bare numbers, so "under 100" meant 100 of whatever each
listing was priced in.

**Raise it to $150:**

```powershell
(Invoke-RestMethod "http://localhost:8081/api/properties?maxPrice=150&currency=USD").content | Select-Object id, title, pricePerNight, currency, @{ n = "inUSD"; e = { $_.displayPrice.amount } } | Format-Table
```

✅ All four, newest first: the London flat ($127.53), the studio ($81.69), the apartment
($26.04) and the villa ($124.97).

**Without a currency, `maxPrice` is in rupees**, and nothing is converted for display:

```powershell
(Invoke-RestMethod "http://localhost:8081/api/properties?maxPrice=10000").content | Select-Object id, title, pricePerNight, currency, displayPrice | Format-Table
```

✅ The studio (AED 300 ≈ ₹7,800) and the apartment, with an empty `displayPrice` column. Old
searches keep meaning exactly what they meant.

**The currency is part of the cache key**, so "100 dollars" and "100 rupees" are different
searches:

```powershell
docker exec rentalhub-redis redis-cli --scan --pattern "rentalhub:v2:propertySearch*"
```

✅
```
rentalhub:v2:propertySearch::city:|guests:|maxPrice:10000|currency:INR|page:0|size:20
rentalhub:v2:propertySearch::city:|guests:|maxPrice:100|currency:USD|page:0|size:20
rentalhub:v2:propertySearch::city:|guests:|maxPrice:150|currency:USD|page:0|size:20
```

---

## Part 43 — A converted total is shown, never stored

Ravi books the London flat and asks to see the total in rupees as well:

```powershell
curl.exe -s -i -X POST "http://localhost:8081/api/bookings?currency=INR" -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking-london.json"
```

✅ `201`:
```
…"totalAmount":285.00,"currency":"GBP","displayTotal":{"amount":36736.59,"currency":"INR","rate":128.900327,"ratesAsOf":"…"},…
```
3 nights at £95. The charge is £285; the rupee figure is only for reading.

Ask for the same booking in dollars:

```powershell
curl.exe -s "http://localhost:8081/api/bookings/7?currency=USD" -H "X-Demo-User-Id: 2"
```

✅ `"totalAmount":285.00,"currency":"GBP"` again, with `"displayTotal":{"amount":382.59,"currency":"USD",…}`.
Same stored amount, a different view of it, worked out for this request.

**What the database holds:**

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT id, total_amount, currency FROM bookings WHERE id = 7;"
```

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT DISTINCT total_amount, currency FROM bookings_aud WHERE id = 7;"
```

✅ Both say `285.0000 | GBP`. Not a rupee or a dollar figure anywhere, in the row or in its
history. Exchange rates move; what was charged doesn't.

---

## Part 44 — The money maths, in one test

```powershell
.\mvnw.cmd test "-Dtest=MoneyMathTest"
```

✅ `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

Open `src/test/java/com/rentalhub/domain/model/MoneyMathTest.java`. Each test is a way
`double` gets money wrong, next to the exact answer the app uses:

- three nights at ₹2,499.99 add up to `7499.969999999999`, not ₹7,499.97;
- 30 nights at ₹1,299.90 come to `38997.00000000002`;
- `(long) (2499.99 * 100)` is `249998` paise: **the guest is charged a paisa less than they
  were shown**. `Currency.toMinorUnits` gives 249999, and refuses to round;
- `new BigDecimal(0.1)` is `0.1000000000000000055511…`, which is why money is built from
  strings;
- `equals` says `2500.0000` ≠ `2500.00`, which is why money is compared with `compareTo`.

---

## Part 45 — Clean up

- Stop the app: `Ctrl+C` in Window 1 (or ⏹ in IntelliJ).
- Stop the containers but keep the data:
  ```powershell
  docker compose stop
  ```
- Or remove them **and wipe the data**:
  ```powershell
  docker compose down -v
  ```

---

## What you just proved

- [ ] All 269 automated tests pass on your machine
- [ ] Flyway built the schema; the double-booking rule and CHECK constraints are in Postgres
- [ ] The factory builds each type and enforces each type's rules (400s with the field)
- [ ] Permissions: guests can't create; only the owner edits (403s)
- [ ] A listing read twice hits the database once; equivalent searches share a cache entry
- [ ] Updates evict the listing and flush exactly the affected search pages (seen live in MONITOR)
- [ ] Moving city flushes the old city's pages
- [ ] Postgres itself refuses overlapping bookings, zero-night stays, bad statuses and 6-star reviews
- [ ] The app keeps working with Redis down
- [ ] A cache hit never runs your method (seen in the debugger)
- [ ] A booking is priced nightly price × nights, in the listing's currency, and confirmed
- [ ] Booking raises the listing's version, and the cached listing follows
- [ ] Only the guest and the host can see or cancel a booking; hosts see their listing's bookings
- [ ] Every booking rule refuses with the right status and field
- [ ] Overlaps are refused, back-to-back stays are allowed, and cancelling frees the dates
- [ ] Two bookings sent at the same moment: exactly one wins
- [ ] A booking that loses the version race is retried and charged the fresh price (seen with a lock you held)
- [ ] The database refuses an overlap the app's check couldn't see, and the guest gets "just taken"
- [ ] Two edits at once: a 409, not a 500
- [ ] Every response has an `X-Request-Id`, and the same id is on its log lines
- [ ] A listing's history lists each change: when, by whom, and exactly what changed
- [ ] Only the host can read it, and bookings don't appear in it
- [ ] Envers keeps whole snapshots in the `_aud` tables, and who made each revision in `revinfo`
- [ ] Only a guest whose stay has ended can review, once; edits are kept in `reviews_aud`
- [ ] The nightly job deactivated an expired listing, and the history names the job
- [ ] A change made with plain SQL is invisible to the audit trail until the next audited change
- [ ] The same events logged as JSON, each detail its own field
- [ ] A booking is held first, its payment's id recorded, and only then is it paid and confirmed
- [ ] A declined card gives the dates back at once, and the attempt stays on record
- [ ] A card needing 3-D Secure, and a provider that refuses, are answered cleanly (402, 503 with `Retry-After`), with nothing charged
- [ ] A payment whose answer was lost leaves the booking pending and its dates held, until the job settles it, recorded as the job
- [ ] Cancelling a paid booking refunds it in full, and cancelling twice doesn't refund twice
- [ ] "Under $100" compares listings priced in rupees, dirhams and pounds, each in its own currency
- [ ] A converted total is shown but never stored: the row and its history stay in the listing's currency
- [ ] `double` gets a three-night total wrong, and loses a paisa converting to the smallest unit; `BigDecimal` doesn't

---

## If something doesn't match

| Symptom | Likely cause | Fix |
|---|---|---|
| `curl: (7) Failed to connect to localhost port 8081` | app not running, or started without `$env:PORT = "8081"` | check Window 1; set the variable in *that* window before starting |
| Ids aren't 1, 2, 3 | the database wasn't empty | use the ids the commands print, or redo Part 0 |
| `Error response from daemon: … No such container: rentalhub-redis` | containers not started | `docker compose up -d` |
| `Invoke-WebRequest : A parameter cannot be found…` | typed `curl` instead of `curl.exe` | use `curl.exe` |
| `Warning: Couldn't read data from file "samples/api/…"` | not in the project folder | `cd C:\dev\rentalhub` |
| `Port 8080 was already in use` in Window 1 | forgot `$env:PORT = "8081"` (your Oracle uses 8080) | set it and start again |
| Part 11 shows no `DEL` for search pages | the search pages had already expired (5 min TTL) | re-run the Part 8 searches, then the PUT |
| Tests fail with `Could not find a valid Docker environment` | Docker Desktop isn't running | start it, wait for "Engine running" |
| Part 19 shows `"version":1` before any booking, or the ids aren't 1–4 | the database wasn't wiped | redo Part 18 |
| `booking.checkIn.past` for `booking.json` | you're doing this after 10 March 2027 | move the dates in `samples/api/booking*.json` into the future |
| A curl in Parts 24–26 never returns | the transaction in Window 3 is still open (that's the point, until you end it) | type `COMMIT;` (or `ROLLBACK;`) in Window 3 |
| Part 24's booking returns at once, at `7500.00` | the `UPDATE` in Window 3 wasn't run, or was already committed | check that it printed `UPDATE 1` and the prompt shows `*`, then redo the step |
| `curl: option -Z: is unknown` (Part 23) | a very old curl | `curl.exe --version` should be 7.66 or newer; Windows 11 ships 8.x |
| The readable history (Parts 30, 33) shows empty fields | the `Invoke-RestMethod` call isn't wrapped in parentheses | copy the command exactly, `( … )` included |
| Part 33: nothing happens at the start of the minute | `STALE_LISTINGS_CRON` wasn't set in Window 1 before the app started | `Ctrl+C`, set it (Part 28, step 3), start again |
| A listing's history is `[]` | the listing was created before Phase 4 and hasn't changed since | redo Part 28, or change the listing once |
| `Remove-Item : Cannot find path 'Env:…'` (Part 34) | that setting wasn't set in this window | harmless; carry on |
| A booking is refused with `paymentMethodId` "This field is required." | an older copy of the `samples/api/booking*.json` files | they all carry `"paymentMethodId": "pm_card_visa"` since Phase 5; add it if yours doesn't |
| Part 40: the booking is still `PENDING` after a minute | `PAYMENT_RECONCILIATION_CRON` and `PAYMENT_STALE_AFTER` weren't set in Window 1 *before* the app started | `Ctrl+C`, set them (Part 36, step 3), start again |
| Part 42: `"displayPrice":null` and `"exchangeRatesUnavailable":true` | the app couldn't reach the exchange-rate provider (no internet, or its rate limit) | it tries again a minute later; prices in their own currency still work |
| Part 42–43: your converted figures differ from the guide's | exchange rates change every day | expected: only the shape of the answer matters |
| A reference starts `sim_pi_` where you expected `pi_` | no `STRIPE_SECRET_KEY`, so the simulator took the payment | that's the default; [05 — Payments](05-payments.md) shows how to use a real test key |

**Tip:** to see a JSON response nicely indented, pipe it through PowerShell:
`curl.exe -s http://localhost:8081/api/properties/1 | ConvertFrom-Json | ConvertTo-Json -Depth 5`
