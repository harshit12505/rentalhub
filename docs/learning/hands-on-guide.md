# Hands-on guide: test RentalHub yourself

Everything built so far (Phases 1–3), tested by you, step by step. Each step has the
exact PowerShell command and what you should see. Every command and expected output here
was run and checked against a fresh database: Parts 0–17 on 13 Sep 2026, Parts 18–26 on
14 Sep 2026.

**Time:** about 45 minutes for Parts 0–17 (Phases 1–2), and 30 more for Parts 18–26
(Phase 3). **You'll use three PowerShell windows:**

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

✅ Near the end: `Tests run: 131, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.
It takes about a minute: the test suite starts its own throwaway Postgres and Redis in
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

✅ 7 tables: `bookings`, `favorites`, `flyway_schema_history`, `properties`,
`property_images`, `reviews`, `users`.

```
SELECT version, description, success FROM flyway_schema_history;
```

✅ One row: `1 | initial schema | t`. Flyway ran V1 exactly once.

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
Phase 5 formats money properly for display.

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

✅ One listing key and four search keys, one per *distinct* search from Part 8:
```
rentalhub:v1:propertyById::1
rentalhub:v1:propertySearch::city:goa|guests:|maxPrice:|page:0|size:20
rentalhub:v1:propertySearch::city:|guests:6|maxPrice:|page:0|size:20
rentalhub:v1:propertySearch::city:|guests:|maxPrice:3000|page:0|size:20
rentalhub:v1:propertySearch::city:|guests:|maxPrice:|page:0|size:20
```
(Search pages expire after 5 minutes, so if you're slow some may already be gone. That's
the TTL doing its job.)

```powershell
docker exec rentalhub-redis redis-cli GET "rentalhub:v1:propertyById::1"
```

✅ The villa as JSON: this is literally what the cache holds.

```powershell
docker exec rentalhub-redis redis-cli TTL "rentalhub:v1:propertyById::1"
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
"DEL" "rentalhub:v1:propertyById::1"
"SCAN" "0" "MATCH" "rentalhub:v1:propertySearch::city:|*" "COUNT" "1000"
"DEL" "…city:|guests:6|…" "…city:|guests:|maxPrice:3000|…" "…city:|guests:|maxPrice:|…"
"SCAN" "0" "MATCH" "rentalhub:v1:propertySearch::city:goa|*" "COUNT" "1000"
"DEL" "rentalhub:v1:propertySearch::city:goa|guests:|maxPrice:|page:0|size:20"
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
{"id":1,"property":{"id":2,"title":"Marina view apartment","city":"Chennai"},"guest":{"id":2,"fullName":"Ravi Kumar"},"checkIn":"2027-03-10","checkOut":"2027-03-13","nights":3,"guests":2,"totalAmount":7500.00,"currency":"INR","status":"CONFIRMED","createdAt":"…"}
```

- `"nights":3`: the 10th, 11th and 12th. The check-out morning isn't a night.
- `"totalAmount":7500.00`: 3 × 2,500.00, in the listing's own currency, to 2 decimals.
- `"CONFIRMED"` straight away: there's no payment step until Phase 5.

✅ Window 1 shows:
```
cache.invalidated propertyId=2 reason=booking
booking.created bookingId=1 propertyId=2 guestId=2 checkIn=2027-03-10 checkOut=2027-03-13 total=7500.00 currency=INR
```

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
| 5 | `booking-missing-fields.json` | 400 | an `errors` list: `checkIn` and `checkOut` "This field is required.", `guests` "At least one guest must stay." |
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

✅ `200`, the booking with `"status":"CANCELLED"`. Window 1:
`booking.cancelled bookingId=1 propertyId=2 byUserId=2`.

Run the same cancel again. ✅ `200` and the same result, not an error. Cancelling is
**idempotent**: a client whose connection dropped can simply send it again.

**Ravi's dates are free again, and Meera books exactly those (`booking.json`):**

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 4" --data "@samples/api/booking.json"
```

✅ `201`, booking 3. Meera's trips (`GET /api/bookings` as user 4) now list booking 2
(13 March), then booking 3 (10 March): latest check-in first.

**What the database holds:**

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT id, property_id AS listing, guest_id AS guest, check_in, check_out, status, total_amount FROM bookings ORDER BY id;"
```

✅
```
 id | listing | guest |  check_in  | check_out  |  status   | total_amount
----+---------+-------+------------+------------+-----------+--------------
  1 |       2 |     2 | 2027-03-10 | 2027-03-13 | CANCELLED |    7500.0000
  2 |       2 |     4 | 2027-03-13 | 2027-03-16 | CONFIRMED |    7500.0000
  3 |       2 |     4 | 2027-03-10 | 2027-03-13 | CONFIRMED |    7500.0000
```
The cancelled booking stays on record, as history. The database stores 4 decimals
(`NUMERIC(19,4)`); the API shows the currency's 2.

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
booking.created bookingId=… propertyId=2 guestId=2 checkIn=2027-06-10 checkOut=2027-06-13 total=9000.00 currency=INR
```

What happened, step by step:
1. Your COMMIT moved the version from 3 to 4.
2. The booking's `UPDATE … WHERE version = 3` then matched no row. It had lost the race, so its
   whole transaction was rolled back, booking row included.
3. About 50 ms later, the retry started a **new** transaction and read the apartment again
   (₹3,000, version 4).
4. The retry succeeded.

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
retried: a retry could only say "already booked".

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

## Part 27 — Clean up

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

- [ ] All 131 automated tests pass on your machine
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

**Tip:** to see a JSON response nicely indented, pipe it through PowerShell:
`curl.exe -s http://localhost:8081/api/properties/1 | ConvertFrom-Json | ConvertTo-Json -Depth 5`
