# Hands-on guide: test RentalHub yourself

Everything built so far (Phases 1–9), tested by you, step by step. Each step has the
exact PowerShell command and what you should see. Every command and expected output here
was run and checked against a fresh database: Parts 0–17 on 13 Sep 2026, Parts 18–26 on
14 Sep 2026, Parts 28–34 on 15 Sep 2026, Parts 36–44 on 16 Sep 2026, Parts 46–50 on
18 Sep 2026, Parts 53–61 on 18–19 Sep 2026, and Parts 63–75 on 19 Sep 2026. Phase 5 changed what some earlier parts
print (bookings are now paid for, cache keys gained a currency), so those parts were run
again on 16 Sep 2026 and updated. Phase 7 changed two things earlier parts show: Spring's
own error messages are now the app's (Part 9, rows 8 and 11; Part 21, row 8), and the
cache keys start `rentalhub:v3:` (a listing's photos gained an id); both were checked and
updated on 19 Sep 2026. Part 51 is the one exception: it needs a Gemini key, so it says
what to expect rather than what was seen. In Parts 63–70 the outputs are the real ones, but three
steps were checked another way than the one written: the forms were submitted by script in the
browser, the cookie flags read from the response rather than in DevTools, and the MinIO upload
(Part 69, step 3) posted with curl to the same form address. Part 68, step 6 (JavaScript
switched off) was checked by a test of the address it loads, not in a browser. Parts 73–75
were run on a Docker network of their own rather than `rentalhub_default`, with an empty
database (so the container seeded rather than skipped), and Part 76 was not run at all: it
needs your Render account.

**Time:** about 45 minutes for Parts 0–17 (Phases 1–2), 30 more for Parts 18–26
(Phase 3), 30 more for Parts 28–34 (Phase 4), 30 more for Parts 36–44 (Phase 5),
25 more for Parts 46–52 (Phase 6), 40 more for Parts 53–62 (Phase 7), 40 more for Parts 63–70 (Phase 8), and 30 more for
Parts 71–75 (Phase 9), plus the Render deploy (Part 76).
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

✅ Near the end: `Tests run: 401, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`
(the number as of Phase 9; it grows with every phase).
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

Since Phase 9 the app fills an empty database with demo data on start-up. Parts 2–70 build
their own data, with the ids written here, so switch that off in this window (Part 71 switches
it back on):

```powershell
$env:DEMO_DATA_ENABLED = "false"
```

```powershell
.\mvnw.cmd spring-boot:run
```

✅ After ~10 seconds: `Started RentalHubApplication in … seconds`.
(Before Phase 8 a `WARN … Cannot find template location` line appeared here too; the web
pages removed it.)
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
| 8 | POST `villa.json` with **no** `X-Demo-User-Id` header | 400 | "The X-Demo-User-Id header is required." |
| 9 | PUT `villa-renamed.json` to `/1` as **user 3** (another host) | 403 | `property.notOwner` |
| 10 | PUT `studio.json` to `/1` as user 1 | 400 | `property.type.cannotChange` |
| 11 | `curl.exe -s -i http://localhost:8081/api/properties/abc` | 400 | `"abc" is not a valid value for id.` |

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
rentalhub:v3:propertyById::1
rentalhub:v3:propertySearch::city:goa|guests:|maxPrice:|currency:|page:0|size:20
rentalhub:v3:propertySearch::city:|guests:6|maxPrice:|currency:|page:0|size:20
rentalhub:v3:propertySearch::city:|guests:|maxPrice:3000|currency:INR|page:0|size:20
rentalhub:v3:propertySearch::city:|guests:|maxPrice:|currency:|page:0|size:20
```
(Search pages expire after 5 minutes, so if you're slow some may already be gone. That's
the TTL doing its job.)

Two things in these keys are from Phase 5:
- **`v2`**: the cached records gained a field (`displayPrice`), so the key prefix moved on
  from `v1`. New code never reads old-shaped JSON.
- **`currency:INR`**: a price limit now has a currency. Without one it's read as rupees.

```powershell
docker exec rentalhub-redis redis-cli GET "rentalhub:v3:propertyById::1"
```

✅ The villa as JSON: this is literally what the cache holds.

```powershell
docker exec rentalhub-redis redis-cli TTL "rentalhub:v3:propertyById::1"
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
"DEL" "rentalhub:v3:propertyById::1"
"SCAN" "0" "MATCH" "rentalhub:v3:propertySearch::city:|*" "COUNT" "1000"
"DEL" "…city:|guests:6|…" "…city:|guests:|maxPrice:3000|currency:INR|…" "…city:|guests:|maxPrice:|…"
"SCAN" "0" "MATCH" "rentalhub:v3:propertySearch::city:goa|*" "COUNT" "1000"
"DEL" "rentalhub:v3:propertySearch::city:goa|guests:|maxPrice:|currency:|page:0|size:20"
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
| 8 | `booking.json` with **no** `X-Demo-User-Id` header | 400 | "The X-Demo-User-Id header is required." |

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
docker exec rentalhub-redis redis-cli --scan --pattern "rentalhub:v3:propertySearch*"
```

✅
```
rentalhub:v3:propertySearch::city:|guests:|maxPrice:10000|currency:INR|page:0|size:20
rentalhub:v3:propertySearch::city:|guests:|maxPrice:100|currency:USD|page:0|size:20
rentalhub:v3:propertySearch::city:|guests:|maxPrice:150|currency:USD|page:0|size:20
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

# Phase 6 — AI: favourites, search by meaning and grounded answers (Parts 46–52)

One more fresh start. Everything in Parts 46–50 runs **without any AI key**, because the rule
for this project is that a missing credential costs you its own feature and nothing else.
Part 51 is the only part that needs a Gemini key, and it is free to get.

## Part 46 — A fresh start, with the AI off

1. Stop the app: `Ctrl+C` in Window 1.
2. In Window 2, wipe the database and start the containers again:

   ```powershell
   docker compose down -v
   ```

   ```powershell
   docker compose up -d
   ```

3. In Window 1, start the app with the index job running every 20 seconds instead of every
   two minutes, so you don't wait around in Part 51:

   ```powershell
   $env:EMBEDDING_INDEX_CRON = "0,20,40 * * * * *"
   ```

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   ✅ Flyway now runs **four** migrations, and the AI reports itself off:
   ```
   Migrating schema "public" to version "4 - ai index"
   Successfully applied 4 migrations to schema "public", now at version v4
   ai.mode ready=false chatModel=false embeddingModel=false vectorStore=false
   ```
   `ready=false` and three `false`s: with no `GEMINI_API_KEY`, the chat model, the embedding
   model and the vector store beans are not created at all (see
   `ai/AiEnvironmentPostProcessor`). The application starts anyway, which is the point.

4. In Window 2, create two users and the three listings this phase uses. They are worded to
   be easy to tell apart by *meaning*: a calm garden villa, a noisy nightlife flat, a snowy
   cabin.

   ```powershell
   docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO users (full_name, email, role) VALUES ('Asha Menon','asha@example.com','HOST'), ('Ravi Kumar','ravi@example.com','GUEST') RETURNING id, full_name, role;"
   ```

   ```powershell
   curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa-quiet-garden.json"
   ```

   ```powershell
   curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/apartment-nightlife.json"
   ```

   ```powershell
   curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/cabin-snow-view.json"
   ```

   ✅ Users 1 (host) and 2 (guest), then `201` three times: listing 1 is the quiet villa in
   Goa (₹9,000), listing 2 the nightlife flat in Goa (₹4,000), listing 3 the snow cabin in
   Manali (₹6,000).

---

## Part 47 — Save a favourite (and save it twice)

Favourites arrived in this phase because the preference profile and the "taste vector" are
built from them. Saving is a `PUT`, not a `POST`: it says "let this listing be saved", which
stays true however many times you send it.

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X PUT http://localhost:8081/api/properties/1/favorite -H "X-Demo-User-Id: 2"
```

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X PUT http://localhost:8081/api/properties/3/favorite -H "X-Demo-User-Id: 2"
```

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X PUT http://localhost:8081/api/properties/1/favorite -H "X-Demo-User-Id: 2"
```

✅ `204` three times. The third one saved nothing new — check:

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT user_id, property_id FROM favorites ORDER BY property_id;"
```

✅ Two rows, not three:
```
 user_id | property_id
---------+-------------
       2 |           1
       2 |           3
```

Now read them back, with the prices also shown in dollars:

```powershell
curl.exe -s "http://localhost:8081/api/favorites?currency=USD" -H "X-Demo-User-Id: 2" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

✅ Newest first — the cabin, then the villa — each with a `displayPrice` alongside its real
price (your figures will differ; rates change daily):

```json
{
  "listing": {
    "id": 3,
    "title": "Snow view cabin",
    "pricePerNight": 6000.0,
    "currency": "INR",
    "displayPrice": { "amount": 62.53, "currency": "USD", "rate": 0.010421, "ratesAsOf": "2026-09-18T00:02:31Z" }
  },
  "savedAt": "2026-09-18T09:31:25.959849Z"
}
```

Take one away, twice:

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X DELETE http://localhost:8081/api/properties/3/favorite -H "X-Demo-User-Id: 2"
```

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X DELETE http://localhost:8081/api/properties/3/favorite -H "X-Demo-User-Id: 2"
```

✅ `204` both times: removing something that isn't saved is not an error. Put it back before
the next part:

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X PUT http://localhost:8081/api/properties/3/favorite -H "X-Demo-User-Id: 2"
```

---

## Part 48 — Ask a question with no AI key at all

```powershell
curl.exe -s "http://localhost:8081/api/recommendations?q=somewhere%20quiet%20with%20a%20garden%20in%20Goa%20for%202" -H "X-Demo-User-Id: 2" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

✅ **200**, with real listings and an honest explanation:

```
"answer": "Search by meaning is off because no Gemini key is configured. These 2 listing(s)
           come from an ordinary filtered search.",
"intent": "RECOMMEND",
"aiUsed": false,
"semantic": false,
```

Both Goa listings come back (the cabin in Manali does not — "in Goa" was read as a filter and
applied in SQL), and each `suggestion` has `"similarity": null`, because nothing was ranked by
meaning. Note that the *rules* still did their work with no model anywhere: city Goa, party
size 2.

**The budget is real SQL, in each listing's own currency.** Ask for something cheaper:

```powershell
curl.exe -s "http://localhost:8081/api/recommendations?q=somewhere%20in%20Goa%20under%205000" -H "X-Demo-User-Id: 2" | ConvertFrom-Json | Select-Object -ExpandProperty suggestions | ForEach-Object { $_.listing.id, $_.listing.title, $_.listing.pricePerNight }
```

✅ Only listing 2, the ₹4,000 flat. And in dollars, with the ceiling converted for you:

```powershell
curl.exe -s "http://localhost:8081/api/recommendations?q=anywhere%20under%20`$60%20a%20night&currency=USD" -H "X-Demo-User-Id: 2" | ConvertFrom-Json | Select-Object -ExpandProperty suggestions | ForEach-Object { "$($_.listing.title): $($_.listing.pricePerNight) $($_.listing.currency) = $($_.listing.displayPrice.amount) USD" }
```

✅ `Nightlife flat above the bars: 4000 INR = 41.69 USD` — and nothing else, because $60 is
about ₹5,757 today, so the ₹6,000 cabin and ₹9,000 villa are over the line. (In PowerShell the
backtick before `$60` stops it being read as a variable.)

**Nobody is recommended their own listing.** Ask as the host:

```powershell
curl.exe -s "http://localhost:8081/api/recommendations?q=anywhere%20in%20Goa" -H "X-Demo-User-Id: 1" | ConvertFrom-Json | Select-Object answer, @{n='suggestions';e={$_.suggestions.Count}}
```

✅ `Nothing on the site matches that. Try a wider budget, another city, or fewer guests.` with
`suggestions 0` — Asha owns all three.

**And nothing has been indexed**, because there is nothing to index with:

```powershell
docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT (SELECT count(*) FROM vector_store) AS documents, (SELECT count(*) FROM listing_embeddings) AS indexed, (SELECT count(*) FROM properties) AS listings;"
```

✅
```
 documents | indexed | listings
-----------+---------+----------
         0 |       0 |        3
```

---

## Part 49 — The questions a model must never answer

Some questions have exactly one right answer. Those are routed to SQL and never reach a model
— which is why they work perfectly here, with no key.

```powershell
"how many listings have I saved", "what do I usually pay for my favourites", "how much have I spent on bookings", "how many stays have I booked" | ForEach-Object { $q = [uri]::EscapeDataString($_); $a = (curl.exe -s "http://localhost:8081/api/recommendations?q=$q" -H "X-Demo-User-Id: 2" | ConvertFrom-Json); "{0,-42} {1,-10} {2}" -f $_, $a.intent, $a.answer }
```

✅ Four `STATS` answers, all exact:

```
how many listings have I saved             STATS      You have saved 2 listing(s), mostly in Manali, Goa.
what do I usually pay for my favourites    STATS      Your saved listings cost between 6,000.00 and 9,000.00 INR a night.
how much have I spent on bookings          STATS      You have not paid for any bookings yet.
how many stays have I booked               STATS      You have made 0 booking(s): 0 confirmed and 0 cancelled.
```

Now one that only *sounds* like a statistics question:

```powershell
curl.exe -s "http://localhost:8081/api/recommendations?q=how%20many%20guests%20can%20the%20villa%20sleep" -H "X-Demo-User-Id: 2" | ConvertFrom-Json | Select-Object intent
```

✅ `RECOMMEND`. "How many" alone isn't enough: the rule needs a measure **and** a subject about
the guest's own history (favourites, bookings, reviews).

---

## Part 50 — What a *wrong* key does

Credentials go stale, get revoked, and run out of quota. Prove it doesn't matter:

1. `Ctrl+C` in Window 1, then:

   ```powershell
   $env:GEMINI_API_KEY = "not-a-real-key"
   ```

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   ✅ It starts, and now believes it has AI (it cannot know a key is bad until it uses one):
   ```
   ai.mode ready=true chatModel=true embeddingModel=true vectorStore=true
   ```

2. In Window 2, create a listing:

   ```powershell
   curl.exe -s -o NUL -w "%{http_code}\n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/studio.json"
   ```

   ✅ `201` in about a second, and in Window 1 a **warning**, not an error:
   ```
   WARN ... ai.index.failed propertyId=4 error=400 . API key not valid. Please pass a valid API key.
   ```
   The listing is saved. Indexing it is a separate, optional step that failed.

3. Ask a question:

   ```powershell
   curl.exe -s "http://localhost:8081/api/recommendations?q=somewhere%20quiet%20with%20a%20garden%20in%20Goa" -H "X-Demo-User-Id: 2" | ConvertFrom-Json | Select-Object answer, aiUsed, semantic
   ```

   ✅ An answer in a couple of seconds, with the real listings:
   ```
   answer   : Here are the 2 closest matches. No listing has been indexed for search by
              meaning yet, so this is an ordinary filtered search.
   aiUsed   : False
   semantic : False
   ```
   and in Window 1, two warnings naming what failed:
   ```
   WARN ... ai.search.failed reason=ClientException error=400 . API key not valid. ...
   WARN ... ai.answer.failed reason=RuntimeException error=Failed to generate content
   ```

4. Watch the backfill job try, and fail, politely. Within 20 seconds Window 1 shows one line
   per listing:

   ```
   WARN ... ai.listing.embedFailed propertyId=1 error=400 . API key not valid. ...
   WARN ... ai.listing.embedFailed propertyId=2 error=400 . API key not valid. ...
   ```

   One failure doesn't abandon the batch, and nothing is lost: the listings are simply still
   on the job's list next time.

5. Undo it before the next part:

   ```powershell
   Remove-Item Env:GEMINI_API_KEY
   ```

---

## Part 51 — With a real Gemini key *(optional; this is the only part that needs one)*

> **Not run for this guide.** Everything above was run and its output pasted in; this part
> needs a key of your own, so the outputs below are what to expect rather than a transcript.
> The same behaviour is covered end to end by `AiRecommendationTest`, which runs the real
> vector store with fake models.

1. Get a free key at [aistudio.google.com/apikey](https://aistudio.google.com/apikey) →
   "Create API key". Then in Window 1 (`Ctrl+C` first):

   ```powershell
   $env:GEMINI_API_KEY = "AIza..."
   ```

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   ✅ `ai.mode ready=true chatModel=true embeddingModel=true vectorStore=true`, and within
   20 seconds the index job embeds the listings created earlier:
   ```
   ai.listing.embedded propertyId=1 characters=...
   job.embeddingIndex.finished embedded=4 propertyIds=[1, 2, 3, 4]
   ```

2. Look at what it stored:

   ```powershell
   docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT e.property_id, p.title, left(v.embedding::text, 40) AS first_numbers FROM listing_embeddings e JOIN vector_store v ON v.id = e.document_id JOIN properties p ON p.id = e.property_id ORDER BY e.property_id;"
   ```

   ✅ One row per listing, each with 768 numbers, of which you see the first few.

3. Ask for something by **meaning**, using none of the listing's own words:

   ```powershell
   curl.exe -s "http://localhost:8081/api/recommendations?q=a%20peaceful%20place%20to%20read%20a%20book%20outdoors" -H "X-Demo-User-Id: 2" | ConvertFrom-Json | Select-Object answer, aiUsed, semantic
   ```

   ✅ `aiUsed: True`, `semantic: True`, and an answer of a few sentences that names the quiet
   garden villa by its id, e.g. *"[1] Quiet garden villa would suit you: a shaded garden and
   no traffic, ₹9,000 a night for six."* The word "peaceful" appears nowhere in the listing.

4. Ask for more of what they save:

   ```powershell
   curl.exe -s "http://localhost:8081/api/recommendations?q=somewhere%20like%20my%20favourites" -H "X-Demo-User-Id: 2" | ConvertFrom-Json | Select-Object -ExpandProperty suggestions | ForEach-Object { "$($_.listing.id) $($_.listing.title) $([math]::Round($_.similarity,3))" }
   ```

   ✅ The listings they have **not** saved, ordered by how close they are to the average of
   the ones they have — computed by Postgres, with no model call (`avg(embedding)`).

5. To watch the grounding check work, you would need the model to misbehave; it usually
   doesn't. `RecommendationServiceTest` and `AiRecommendationTest` make it invent a listing on
   purpose and prove the invented id is cut out — and that an answer made entirely of invented
   ids is thrown away, leaving the real listings and a plain sentence.

---

## Part 52 — Clean up

- Stop the app: `Ctrl+C` in Window 1 (or ⏹ in IntelliJ).
- Clear the settings you set for this phase, if you want the defaults back:
  ```powershell
  Remove-Item Env:EMBEDDING_INDEX_CRON
  ```
- Stop the containers but keep the data:
  ```powershell
  docker compose stop
  ```
- Or remove them **and wipe the data**:
  ```powershell
  docker compose down -v
  ```

---

# Phase 7 — Languages, photos, GraphQL, Swagger and Postman (Parts 53–62)

Parts 53–55 need nothing new. Photos need an S3 bucket, and Parts 56–59 use **MinIO**, a free
S3-compatible server in Docker, instead of an AWS account. It's in `docker-compose.yml`, but
only starts when asked.

**Hindi in the console.** Windows PowerShell prints UTF-8 text wrongly unless told otherwise.
In Window 2, run this once (it lasts until the window closes):

```powershell
[Console]::OutputEncoding = [Text.Encoding]::UTF8
```

If Hindi still shows as boxes, the console font lacks Devanagari: use **Windows Terminal**
(it is on Windows 11 already) rather than the old console window.

## Part 53 — A fresh start

1. `Ctrl+C` in Window 1. In Window 2:

   ```powershell
   docker compose down -v
   ```

   ```powershell
   docker compose up -d
   ```

2. In Window 1, start the app. No new settings yet:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   ✅ Among the startup lines, a new one:
   ```
   images.mode storage=NONE reason=no S3_BUCKET: photo uploads are switched off
   ```

3. In Window 2, the two users and one listing:

   ```powershell
   docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO users (full_name, email, role) VALUES ('Asha Menon','asha@example.com','HOST'), ('Ravi Kumar','ravi@example.com','GUEST') RETURNING id, full_name, role;"
   ```

   ```powershell
   curl.exe -s -o NUL -w "%{http_code}`n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa-quiet-garden.json"
   ```

   ✅ Users 1 (host) and 2 (guest), then `201`: listing 1 is the quiet garden villa in Goa.

---

## Part 54 — One API, three languages

The browser's language (`Accept-Language`) chooses, region and all:

```powershell
curl.exe -s http://localhost:8081/api/properties/999 -H "Accept-Language: hi-IN,hi;q=0.9,en;q=0.8"
```

✅
```
{"detail":"आईडी 999 वाली कोई लिस्टिंग नहीं है।","instance":"/api/properties/999","status":404,"title":"नहीं मिला","messageKey":"property.notFound"}
```

`?lang=` beats it, and is remembered in a cookie (`-c` saves cookies to a file, `-b` sends them):

```powershell
curl.exe -s -c "$env:TEMP\lang.txt" "http://localhost:8081/api/properties/999?lang=es"
```

```powershell
curl.exe -s -b "$env:TEMP\lang.txt" http://localhost:8081/api/properties/999
```

✅ Both in Spanish — the second one with no `?lang=` at all:
```
{"detail":"No existe ningún anuncio con id 999.","instance":"/api/properties/999","status":404,"title":"No encontrado","messageKey":"property.notFound"}
```

A language the app doesn't speak gets English (`-H "Accept-Language: fr-FR"` → `There is no
listing with id 999.`), and `?lang=fr` isn't remembered.

**Every kind of message follows.** Spring's own error for a missing header, which used to be
Spring's English (Part 9, row 8):

```powershell
curl.exe -s -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "Accept-Language: hi" --data "@samples/api/villa.json"
```

✅ `{"detail":"X-Demo-User-Id हेडर ज़रूरी है।", ... "title":"अनुरोध मान्य नहीं था"}`

Bean validation, field by field:

```powershell
curl.exe -s -X POST "http://localhost:8081/api/properties?lang=es" -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/invalid-blank-fields.json"
```

✅
```
{"detail":"Algunos campos no son válidos.","instance":"/api/properties","status":400,"title":"La solicitud no es válida","errors":[{"field":"city","message":"Este campo es obligatorio."},{"field":"title","message":"Este campo es obligatorio."}]}
```

And the AI's answers from Phase 6:

```powershell
curl.exe -s -o NUL -X PUT http://localhost:8081/api/properties/1/favorite -H "X-Demo-User-Id: 2"
```

```powershell
curl.exe -s "http://localhost:8081/api/recommendations?q=how%20many%20listings%20have%20I%20saved&lang=es" -H "X-Demo-User-Id: 2"
```

✅ `"answer":"Has guardado 1 anuncio(s), sobre todo en Goa."`

---

## Part 55 — A photo, with no storage configured

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/properties/1/images -H "X-Demo-User-Id: 1" -F "file=@samples/api/photo.jpg"
```

✅ A clean **503**, with no `Retry-After` (waiting won't help), and the reason:
```
HTTP/1.1 503
{"detail":"Photo uploads are switched off, because no image storage is configured on this server.","instance":"/api/properties/1/images","status":503,"title":"Temporarily unavailable","messageKey":"image.storage.notConfigured"}
```

Add `?lang=hi` to the URL and the same answer comes in Hindi. Nothing else is affected: the
listing, bookings and everything before work as usual.

---

## Part 56 — MinIO: an S3 bucket on your machine

1. In Window 2, start MinIO (the `photos` profile is what starts it):

   ```powershell
   docker compose --profile photos up -d
   ```

2. Create the bucket, with MinIO's own `mc` tool inside the container:

   ```powershell
   docker exec rentalhub-minio mc alias set local http://localhost:9000 rentalhub rentalhub-secret
   ```

   ```powershell
   docker exec rentalhub-minio mc mb local/rentalhub-photos
   ```

   ✅
   ```
   Added `local` successfully.
   Bucket created successfully `local/rentalhub-photos`.
   ```
   (You can also look at it in a browser: http://localhost:9001, user `rentalhub`, password
   `rentalhub-secret`.)

3. In Window 1, `Ctrl+C`, set the storage settings, and start the app again. These are the
   same variables you'd set for real AWS, plus two that only an S3-compatible server needs
   (`S3_ENDPOINT`, `S3_PATH_STYLE`):

   ```powershell
   $env:S3_BUCKET = "rentalhub-photos"
   ```

   ```powershell
   $env:S3_ENDPOINT = "http://localhost:9000"
   ```

   ```powershell
   $env:S3_PATH_STYLE = "true"
   ```

   ```powershell
   $env:AWS_REGION = "us-east-1"
   ```

   ```powershell
   $env:AWS_ACCESS_KEY_ID = "rentalhub"
   ```

   ```powershell
   $env:AWS_SECRET_ACCESS_KEY = "rentalhub-secret"
   ```

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   ✅
   ```
   images.mode storage=S3 bucket=rentalhub-photos region=us-east-1 endpoint=http://localhost:9000 credentials=access key
   ```
   (The secret is never logged.)

---

## Part 57 — Upload a photo, and get it back

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/properties/1/images -H "X-Demo-User-Id: 1" -F "file=@samples/api/photo.jpg"
```

✅ `201`, and a `Location` that is the photo's own URL (your random part will differ):
```
HTTP/1.1 201
Location: http://localhost:8081/images/listings/1/6bb293d4-a000-4439-86c1-fff8129cba63.jpg

{"id":1,"url":"/images/listings/1/6bb293d4-a000-4439-86c1-fff8129cba63.jpg","sortOrder":0}
```

The listing now shows it:

```powershell
(Invoke-RestMethod http://localhost:8081/api/properties/1).images | Format-Table -AutoSize
```

✅
```
id url                                                         sortOrder
-- ---                                                         ---------
 1 /images/listings/1/6bb293d4-a000-4439-86c1-fff8129cba63.jpg         0
```

Fetch it back through the app and compare it with the original:

```powershell
$url = (Invoke-RestMethod http://localhost:8081/api/properties/1).images[0].url
```

```powershell
curl.exe -s -o "$env:TEMP\back.jpg" -w "%{http_code} %{content_type} %{size_download} bytes`n" "http://localhost:8081$url"
```

```powershell
(Get-FileHash "$env:TEMP\back.jpg").Hash -eq (Get-FileHash samples\api\photo.jpg).Hash
```

✅ `200 image/jpeg 7435 bytes`, then `True`: the same bytes. Open
`http://localhost:8081` + that URL in a browser to see it (a beach at sunset, drawn for these
samples).

And in the bucket itself:

```powershell
docker exec rentalhub-minio mc ls --recursive local/rentalhub-photos
```

✅ `7.3KiB STANDARD listings/1/6bb293d4-….jpg` — the key is random; the uploaded file's name
was never used.

---

## Part 58 — What gets refused

A PDF renamed to `.jpg` (its first bytes are `%PDF`):

```powershell
curl.exe -s -X POST http://localhost:8081/api/properties/1/images -H "X-Demo-User-Id: 1" -F "file=@samples/api/not-a-photo.jpg"
```

✅ `400`, `"messageKey":"image.type.unsupported"`, `"field":"file"`.

A real PNG that claims to be a JPEG (`;type=` sets the declared type):

```powershell
curl.exe -s -X POST http://localhost:8081/api/properties/1/images -H "X-Demo-User-Id: 1" -F "file=@samples/api/photo.png;type=image/jpeg"
```

✅
```
{"detail":"The file says it is image/jpeg, but its contents are not. Upload a real JPEG, PNG or WebP photo.", ... "messageKey":"image.type.mismatch","field":"file"}
```

A 6 MB file (over the 5 MB limit), in Hindi:

```powershell
$bytes = New-Object byte[] 6291456; $bytes[0] = 0xFF; $bytes[1] = 0xD8; $bytes[2] = 0xFF
```

```powershell
[IO.File]::WriteAllBytes("$env:TEMP\big.jpg", $bytes)
```

```powershell
curl.exe -s -X POST "http://localhost:8081/api/properties/1/images?lang=hi" -H "X-Demo-User-Id: 1" -F "file=@$env:TEMP\big.jpg"
```

✅ **413**, refused by the API's own error handling, translated:
```
{"detail":"अपलोड इस सर्वर की अनुमत सीमा से बड़ा है।","instance":"/api/properties/1/images","status":413,"title":"बहुत बड़ा"}
```

And the guest trying (`-H "X-Demo-User-Id: 2"` with `photo.jpg`): ✅ `403`,
`property.notOwner`. None of these left anything in the bucket. A WebP (`photo.webp`) is
accepted, even though curl declares it `application/octet-stream`: with no real type claimed,
the bytes decide.

---

## Part 59 — Remove a photo: the row first, then the file

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" -X DELETE http://localhost:8081/api/properties/1/images/1 -H "X-Demo-User-Id: 1"
```

```powershell
docker exec rentalhub-minio mc ls --recursive local/rentalhub-photos
```

✅ `204`, and the bucket listing is empty: the file was deleted after the row's transaction
committed. (Upload a photo to a listing with no bookings and delete the whole listing, and its
files go the same way.)

---

## Part 60 — GraphQL

The request bodies are in `samples/api/graphql-*.json` (typing JSON with nested quotes on the
PowerShell command line is fragile). Upload `photo.jpg` again first (Part 57's command), so the
search has a photo to show.

```powershell
curl.exe -s -X POST http://localhost:8081/graphql -H "Content-Type: application/json" -H "Accept-Language: hi" --data "@samples/api/graphql-search.json"
```

✅ Exactly the fields asked for, the type's name in Hindi, money as an exact string, and each
listing's host and photos (loaded for the whole page in one query each):
```
{"data":{"searchProperties":{"totalElements":1,"content":[{"id":"1","title":"Quiet garden villa","typeLabel":"विला","pricePerNight":"9000.0000","currency":"INR","host":{"fullName":"Asha Menon"},"images":[{"url":"/images/listings/1/….jpg"}]}]}}}
```

```powershell
curl.exe -s -X POST "http://localhost:8081/graphql?lang=es" -H "Content-Type: application/json" --data "@samples/api/graphql-detail.json"
```

✅ The listing's own attributes, labelled in Spanish, with a price in dollars too (your rate
will differ):
```
{"data":{"property":{"title":"Quiet garden villa","typeLabel":"Villa","pricePerNight":"9000.0000","currency":"INR","displayPrice":{"amount":"93.79","currency":"USD"},"attributes":[{"label":"Superficie de la parcela (m²)","value":"500.00","valueLabel":null},{"label":"Piscina privada","value":"true","valueLabel":"Sí"}],"reviews":[]}}}
```

A booking, as the guest — the same saga as REST:

```powershell
curl.exe -s -X POST http://localhost:8081/graphql -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/graphql-book.json"
```

✅ `{"data":{"createBooking":{"id":"1","status":"CONFIRMED","nights":2,"totalAmount":"18000.00","currency":"INR","payment":{"status":"PAID","provider":"SIMULATED"}}}}`

And an error, which in GraphQL is still status 200, with the failure listed:

```powershell
curl.exe -s -X POST http://localhost:8081/graphql -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/graphql-review.json"
```

✅
```
{"errors":[{"message":"You can review a listing only after a stay there has ended.", ... "extensions":{"messageKey":"review.notStayed","classification":"FORBIDDEN"}}],"data":null}
```

**In the browser:** open http://localhost:8081/graphiql, paste the query from
`samples/api/graphql-search.json`, and press ▶. `Ctrl+Space` inside a `{ }` lists the fields
you can ask for, read from the schema.

---

## Part 61 — Swagger UI and Postman

**Swagger UI:** open http://localhost:8081/swagger-ui.html. Every endpoint is there, grouped
(Listings, Bookings, Photos, …), each with a description and example payloads. Open *Listings →
GET /api/properties/{id}*, *Try it out*, type `1`, *Execute*.

**Postman** (the desktop app, free):
1. *Import* → both files in the `postman\` folder.
2. Top right, choose the environment **RentalHub local**. It has `baseUrl`
   (`http://localhost:8081`), `hostId` (1) and `guestId` (2).
3. *Settings → General → Working directory*: `C:\dev\rentalhub`, so the photo upload finds
   `samples\api\photo.jpg`.
4. Right-click the **RentalHub** collection → *Run collection* → *Run RentalHub*.

✅ All 33 requests pass their tests. Three of the review requests *expect* a 404 on a fresh
database (there is no review yet: the API refuses bookings in the past, so no stay can have
ended), and the review itself a 403; their descriptions say so.

---

## Part 62 — Clean up

- Stop the app: `Ctrl+C` in Window 1. Clear the storage settings if you want uploads switched
  off again:
  ```powershell
  Remove-Item Env:S3_BUCKET, Env:S3_ENDPOINT, Env:S3_PATH_STYLE, Env:AWS_REGION, Env:AWS_ACCESS_KEY_ID, Env:AWS_SECRET_ACCESS_KEY
  ```
- Stop MinIO (its photos are kept in the `rentalhub-photos` volume):
  ```powershell
  docker compose --profile photos stop
  ```
- Or remove everything, photos included:
  ```powershell
  docker compose --profile photos down -v
  ```

---

# Phase 8 — The web pages (Parts 63–70)

Until now everything went through `curl.exe`. This phase is the website, so most of it happens
in a browser (Chrome or Edge) at **http://localhost:8081**. Window 2 is still used for a few
commands. There is no login: the navbar's **Sign in as** menu picks a demo user.

**Signed out after a restart?** Sessions live in the app's memory, so restarting the app signs
everyone out. Just sign in again from the navbar.

## Part 63 — A fresh start: three users, five listings

1. `Ctrl+C` in Window 1. If you set the storage variables in Part 56, clear them (they come back
   in Part 69):

   ```powershell
   Remove-Item Env:S3_BUCKET, Env:S3_ENDPOINT, Env:S3_PATH_STYLE, Env:AWS_REGION, Env:AWS_ACCESS_KEY_ID, Env:AWS_SECRET_ACCESS_KEY
   ```

2. In Window 2:

   ```powershell
   docker compose down -v
   ```

   ```powershell
   docker compose up -d
   ```

3. In Window 1:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

4. In Window 2, three users (one host, two guests):

   ```powershell
   docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "INSERT INTO users (full_name, email, role) VALUES ('Asha Menon','asha@example.com','HOST'), ('Ravi Kumar','ravi@example.com','GUEST'), ('Meera Iyer','meera@example.com','GUEST') RETURNING id, full_name, role;"
   ```

   ✅
   ```
    id | full_name  | role
   ----+------------+-------
     1 | Asha Menon | HOST
     2 | Ravi Kumar | GUEST
     3 | Meera Iyer | GUEST
   ```

5. And Asha's five listings, one command:

   ```powershell
   foreach ($f in "villa-quiet-garden","apartment","cabin-snow-view","studio","apartment-nightlife") { curl.exe -s -o NUL -w "$f %{http_code}`n" -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/$f.json" }
   ```

   ✅
   ```
   villa-quiet-garden 201
   apartment 201
   cabin-snow-view 201
   studio 201
   apartment-nightlife 201
   ```

   Listing 1 is the Quiet garden villa in Goa, listing 2 the Marina view apartment in Chennai.

---

## Part 64 — The home page

Open **http://localhost:8081**.

✅ "Find a place to stay", a search form, **5 place(s) found**, and five cards. Each has a grey
"No photo yet" panel, the type and city, a price such as **9,000.00 INR a night**, and
"No reviews yet · Sleeps 6".

Now search: **City** `Goa`, **Currency** `USD`, **Max price a night** `100`, then **Search**.

✅ **2 place(s) found**: the Nightlife flat and the Quiet garden villa. Each shows its own price
and, under it, a converted one such as **≈ 93.76 USD** (the rate changes daily). The address
bar now holds the filters (`/?city=Goa&currency=USD&maxPrice=100`), so the search can be
bookmarked or shared.

Two things to look at:
- **Make the window narrow** (or press `F12` → the phone icon, *Toggle device toolbar*). The
  cards stack into one column, the navbar folds into a ☰ button, and nothing scrolls sideways.
- **View the page source** (`Ctrl+U`). The Bootstrap `<link>` carries an `integrity="sha384-…"`
  attribute: the browser checks the downloaded file against that hash (Subresource Integrity).

---

## Part 65 — "Sign in as"

1. Navbar → **Sign in as** → **Ravi Kumar**.

   ✅ You're back on the same search, with a green **You are now acting as Ravi Kumar.**, and
   the navbar says **Signed in as Ravi Kumar**. The address is exactly what it was, with no
   `;jsessionid=…` in it.

2. Press `F5`. ✅ The green message is gone: a *flash* message lives for one page only.

3. `F12` → **Application** → **Cookies** → `http://localhost:8081`. ✅ One cookie, `JSESSIONID`,
   with **HttpOnly** ticked and **SameSite** `Lax`.

4. The same from the command line, in Window 2:

   ```powershell
   curl.exe -s -i -X POST http://localhost:8081/session/user -d "userId=2&returnTo=/bookings" | Select-String "HTTP/|Set-Cookie|Location"
   ```

   ✅ (your session id will differ)
   ```
   HTTP/1.1 302
   Set-Cookie: JSESSIONID=C6749A60DE9F5D465DD543B17491B95D; Path=/; HttpOnly; SameSite=Lax
   Location: http://localhost:8081/bookings
   ```

5. The open-redirect guard: ask to be sent to another site afterwards.

   ```powershell
   curl.exe -s -i -X POST http://localhost:8081/session/user -d "userId=2&returnTo=//evil.example" | Select-String "Location"
   ```

   ✅ `Location: http://localhost:8081/` — the home page, not `evil.example`.

---

## Part 66 — Book a stay, in two languages

1. In the browser (still Ravi), open the **Quiet garden villa**.

   ✅ Its details include its own fields, **Plot area (m²) 500** and **Private pool Yes**. On the
   right: the price, and **Book your stay** with dates a week from today, 1 guest, and **Pay
   with** offering three Stripe test cards.

2. Set **Check-out** to the same day as **Check-in**, **Guests** to `2`, and press **Book and
   pay**.

   ✅ The address is `/listings/1#book` (the listing's own address, not the form's). Under
   Check-out, in red: **Check-out must be at least one day after check-in.** The dates you
   typed are still there.

3. Navbar → **Language** → **Español**.

   ✅ The same page in Spanish: **Reserva tu estancia**, **Superficie de la parcela (m²)**,
   **Piscina privada: Sí**, and the price as **9.000,00 INR** (Spanish groups thousands with a
   dot).

4. Set **Salida** two days after **Llegada**, **Huéspedes** `2`, and press **Reservar y pagar**.

   ✅ **Mis reservas**, with a green **Reservado: 2 noche(s), 18.000,00 INR pagados.** and the
   booking marked **Confirmada** and **Pagada**.

5. Who made it? In Window 2:

   ```powershell
   docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT b.id, b.status, b.payment_status, b.payment_provider, r.changed_by FROM bookings b JOIN bookings_aud a ON a.id = b.id JOIN revinfo r ON r.rev = a.rev ORDER BY a.rev;"
   ```

   ✅ Three history rows (held, payment started, paid), all by Ravi, as through the API:
   ```
    id |  status   | payment_status | payment_provider | changed_by
   ----+-----------+----------------+------------------+------------
     1 | CONFIRMED | PAID           | SIMULATED        | user:2
     1 | CONFIRMED | PAID           | SIMULATED        | user:2
     1 | CONFIRMED | PAID           | SIMULATED        | user:2
   ```
   (Each row is the booking's latest state joined to each revision; the history itself is in
   `bookings_aud`, as in Part 30.)

6. **Mostrar precios en…** → `USD` → **Mostrar**. ✅ Under the total: **≈ 187,52 USD**
   (display only, never stored — Part 43).

7. **Cancelar reserva**. ✅ **Reserva cancelada y reembolsada por completo.**, the booking
   **Cancelada** and **Reembolsada**, and no cancel button any more.

---

## Part 67 — A declined card, a favourite, and ideas

1. **Language** → **English**. Open the **Marina view apartment**. **Pay with** → **Test card
   that is declined** → **Book and pay**.

   ✅ Back at `/listings/2#book` with a red box: **The card was declined, and nothing was
   charged. Please try a different card.** The declined card is still selected.

2. Press **♡ Save** (top right of the listing). ✅ **Saved to your favourites.**, and the button
   is now a filled **♥ Saved**. Press it again: **Removed from your favourites.** Press it once
   more, so it stays saved.

3. Navbar → **Ask for ideas**. Type `somewhere like my favourites in Goa` and press **Ask**.

   ✅ A yellow notice first — **Search by meaning is switched off on this server (no Gemini
   key)…** — then the answer, **Written by the app itself.**, and the two Goa listings as cards.

4. In Hindi, straight from the address bar:

   `http://localhost:8081/recommendations?q=how%20many%20listings%20have%20I%20saved&lang=hi`

   ✅ **आपने 1 लिस्टिंग सहेजी हैं, ज़्यादातर Chennai में।** and **ऐप ने ख़ुद लिखा।** — the Phase 6
   statistics, answered by SQL.

---

## Part 68 — List a place, as the host

1. **Language** → **English**, then **Sign in as** → **Asha Menon**. ✅ A new navbar link, **List
   a place** (only hosts see it). Open it.

2. **Type of place** → **Cabin**. ✅ The apartment's fields disappear, and **Only for this type:
   Cabin** shows **Heating** and **Distance to nearest town (km)**, both starred. Every type's
   fields are on the page, generated from the factory; the chosen type's are shown and sent, the
   others are hidden and disabled.

3. Fill in: Title `Pine hut by the river`, Description `A small wooden hut, warm in winter.`,
   City `Manali`, Country `India`, Price per night `5500`, Sleeps `3`, Bedrooms `1`,
   Bathrooms `1`, Distance to nearest town `500`. Leave **Heating** on *Choose…*. Press
   **Publish listing**.

   ✅ **Heating is required for this type of property.** under Heating; everything you typed is
   still there, and Cabin is still chosen.

4. **Heating** → **Wood stove** → **Publish listing**. ✅ **Distance to town must be between 0 and
   200 km.** under Distance.

5. Distance `4.5` → **Publish listing**. ✅ The new listing's page (`/listings/6`): **Your listing
   is live.**, its details include **Heating: Wood stove** and **Distance to nearest town (km):
   4.5**, the booking card says **This is your listing.**, and a **Photos** section appears
   (only for the host).

6. *(Optional)* The form without JavaScript: `F12` → `Ctrl+Shift+P` → type **Disable
   JavaScript** → Enter, then reload **List a place**. Choose **Cabin** and press the button that
   appears, **Show the fields for this type**: the page reloads with the cabin's fields.
   Re-enable JavaScript the same way (**Enable JavaScript**).

7. **Sign in as** → **Ravi Kumar**, then open `http://localhost:8081/host/listings/new`. ✅ **Only
   hosts can list a place…** and no form.

---

## Part 69 — Photos, from the page

1. **Sign in as** → **Asha Menon**, open `http://localhost:8081/listings/6`, scroll to
   **Photos**, **Choose file** → `C:\dev\rentalhub\samples\api\photo.jpg` → **Upload**.

   ✅ Back at the Photos section with a red **Photo uploads are switched off, because no image
   storage is configured on this server.** — the page's version of Part 55's 503.

2. A file over the 5 MB limit. Make a 6 MB one in Window 2:

   ```powershell
   [IO.File]::WriteAllBytes("$env:TEMP\big.jpg", (New-Object byte[] 6291456))
   ```

   Upload `%TEMP%\big.jpg` (type `%TEMP%` in the file dialog's address bar). ✅ **The upload is
   larger than this server accepts.**, on the listing, not a browser error page. (Without the
   `max-swallow-size` setting, a browser shows "This site can't be reached — connection reset"
   here: see the teaching doc.)

3. With storage on: redo **Part 56** (MinIO, the bucket, the six variables, restart the app).
   Sign in as Asha again (the restart forgot the session), and upload `photo.jpg` to listing 6.

   ✅ **Photo added.**; the photo (a beach at sunset) is on the listing, and on its card on the
   home page. In Window 2:

   ```powershell
   docker exec rentalhub-minio mc ls --recursive local/rentalhub-photos
   ```

   ✅ `7.3KiB STANDARD listings/6/….jpg`

4. Under the photo, **Remove**. ✅ **Photo removed.**, the grey "No photo yet" panel is back, and
   the same `mc ls` prints nothing.

---

## Part 70 — Error pages, then clean up

Each of these is a page in the site's frame (navbar, language menu), never a stack trace:

| Open | ✅ You see |
|---|---|
| `http://localhost:8081/listings/999?lang=hi` | **404**, **नहीं मिला**, **आईडी 999 वाली कोई लिस्टिंग नहीं है।** |
| `http://localhost:8081/no-such-page?lang=es` | **404**, **No encontrado**, **Esa página no existe, o algo falló al mostrarla.**, and the navbar still knows who you are |
| `http://localhost:8081/listings/abc?lang=en` | **400**, **The request was not valid**, **"abc" is not a valid value for id.** |
| `http://localhost:8081/listings/1/book` | **405**, **Method not allowed** — that address only takes the booking form's POST |

The second and fourth come from Spring Boot's own error handling (no controller answers those
addresses); the first and third from the pages' own handler. Both render `error.html`.

**Clean up:** `Ctrl+C` in Window 1. If you started MinIO:
`docker compose --profile photos stop`, and clear the six variables as in Part 63, step 1.

---

# Phase 9 — Demo data, health, the container image, Render (Parts 71–76)

Parts 71–75 run on your machine; Part 76 is the real deployment and needs a Render account.

## Part 71 — The demo world

Until now Window 1 has had `DEMO_DATA_ENABLED` set to `false` (Part 2), because every part
built its own data. Now let the app build its demo world into an empty database.

1. `Ctrl+C` in Window 1, then switch the demo data back on (in Window 1):

   ```powershell
   Remove-Item Env:DEMO_DATA_ENABLED
   ```

2. In Window 2, an empty database:

   ```powershell
   docker compose down -v
   ```

   ```powershell
   docker compose up -d
   ```

3. In Window 1:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   ✅ Among the last lines (your duration will differ):
   ```
   payments.mode provider=SIMULATED reason=no STRIPE_SECRET_KEY: nothing is charged
   images.mode storage=NONE reason=no S3_BUCKET: photo uploads are switched off
   Started RentalHubApplication in 9.038 seconds (process running for 9.719)
   demo.seeded users=8 listings=16 bookings=13 reviews=8 favourites=8 durationMs=992
   ```

4. What was made, in Window 2:

   ```powershell
   docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT property_type, count(*), string_agg(DISTINCT currency, ', ') AS currencies FROM properties GROUP BY property_type ORDER BY 1;"
   ```

   ✅ All four types, in five currencies:
   ```
    property_type | count |       currencies
   ---------------+-------+-------------------------
    APARTMENT     |     6 | AED, EUR, GBP, INR, USD
    CABIN         |     3 | GBP, INR
    STUDIO        |     4 | AED, EUR, GBP, INR
    VILLA         |     3 | AED, EUR, INR
   ```

   ```powershell
   docker exec rentalhub-postgres psql -U rentalhub -d rentalhub -c "SELECT DISTINCT changed_by FROM revinfo;"
   ```

   ✅ `system:demo-seeder` — the history says who made it.

5. Open **http://localhost:8081**. ✅ **16 place(s) found**, **Page 1 of 2**, and cards with
   ratings such as "4 (1 review(s))". **Sign in as** lists Asha Menon, Daniel Brooks, Layla Haddad
   and Carmen Ruiz (hosts) and Ravi Kumar, Meera Iyer, Sofía García and Arjun Rao (guests).
   Sign in as **Ravi Kumar** and open **My bookings**: two past stays and one coming up (the snow
   view cabin). Only the one coming up has a **Cancel booking** button.

6. Stop and start the app again (`Ctrl+C`, then the same command). ✅ This time:
   ```
   demo.skipped reason=database not empty
   ```
   It never adds to a database that has data. (`$env:DEMO_DATA_ENABLED = "false"` gives
   `demo.skipped reason=switched off` instead.)

---

## Part 72 — The health check, with Redis gone

Render asks `/actuator/health` whether the app is well. Redis is only a cache, so losing it
must not make the app look dead.

```powershell
curl.exe -s http://localhost:8081/actuator/health
```

✅ `{"groups":["liveness","readiness"],"status":"UP"}`

Stop Redis:

```powershell
docker stop rentalhub-redis
```

```powershell
curl.exe -s -w "  <- HTTP %{http_code}`n" http://localhost:8081/actuator/health
```

✅
```
{"groups":["liveness","readiness"],"description":"Redis is unreachable: every read goes to the database","status":"DEGRADED"}  <- HTTP 200
```

Still 200, so Render keeps the site up, and the site still works: reload
http://localhost:8081 — slower, but all there. Now bring Redis back:

```powershell
docker start rentalhub-redis
```

✅ A few seconds later the same `curl.exe` says `"status":"UP"` again: the app reconnects by itself.

---

## Part 73 — Build the container image

This is exactly what Render will do with the `Dockerfile`. In Window 2:

```powershell
docker build -t rentalhub .
```

✅ About three minutes the first time (it downloads Java, Maven and every library), ending with
`naming to docker.io/library/rentalhub`. Now run the same command again: ✅ a few seconds,
nearly every step says `CACHED`. After a change to the code, only the last steps run again
(about 20 seconds).

Its layers, newest first:

```powershell
docker history rentalhub --format "{{.Size}}`t{{.CreatedBy}}" | Select-Object -First 9
```

✅ Our own code is a small layer (about 475 kB), the libraries a big one (135 MB) that a new
release doesn't touch:
```
0B      ENTRYPOINT ["sh" "-c" "exec java $JAVA_OPTS …
0B      EXPOSE [8080/tcp]
0B      ENV JAVA_OPTS=-XX:MaxRAMPercentage=60 -XX:+U…
0B      USER rentalhub
475kB   COPY /workspace/target/extracted/application…
4.1kB   COPY /workspace/target/extracted/snapshot-de…
4.1kB   COPY /workspace/target/extracted/spring-boot…
135MB   COPY /workspace/target/extracted/dependencie…
```

And it doesn't run as root:

```powershell
docker run --rm --entrypoint id rentalhub
```

✅ `uid=100(rentalhub) gid=101(rentalhub) groups=101(rentalhub)`

---

## Part 74 — Run it the way Render will

Render's free plan gives the app 512 MB of memory and a tenth of a CPU, sets `PORT=10000`, and
turns on the `render` profile. Docker can impose exactly that. The container joins the network
`docker compose` made (`rentalhub_default`), so it reaches Postgres and Redis by their names.

1. **Stop the app in Window 1** (`Ctrl+C`): the container will use the same database.

2. In Window 1:

   ```powershell
   docker run --rm --name rentalhub-container --network rentalhub_default --memory=512m --cpus=0.1 -p 8082:10000 -e PORT=10000 -e SPRING_PROFILES_ACTIVE=render -e DB_HOST=rentalhub-postgres -e REDIS_URL=redis://rentalhub-redis:6379 rentalhub
   ```

   ✅ The log is now JSON, one object per line (what Render's log view shows). Be patient: on a
   tenth of a CPU it takes about **two and a half minutes**, then:
   ```
   ..."message":"Started RentalHubApplication in 147.983 seconds (process running for 162.2)"...
   ```
   followed by a `"message":"demo.skipped"` line with `"reason":"database not empty"` (Part 71
   filled it).

3. In Window 2, its memory:

   ```powershell
   docker stats rentalhub-container --no-stream --format "{{.MemUsage}}"
   ```

   ✅ About `339MiB / 512MiB` after start; open a few pages at **http://localhost:8082** and it
   settles around `363MiB / 512MiB`. Once warm, pages answer in a fraction of a second.

4. Stop it: `Ctrl+C` in Window 1 (or `docker stop rentalhub-container`).

---

## Part 75 — Behind Render's proxy

Render ends HTTPS at its own proxy and passes the request on as plain HTTP, saying what the
original was in `X-Forwarded-*` headers. Start the container again as in Part 74, then pretend to
be that proxy:

```powershell
curl.exe -s -i -X POST http://localhost:8082/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" -H "X-Forwarded-Proto: https" -H "X-Forwarded-Host: rentalhub.onrender.com" --data "@samples/api/studio.json" | Select-String "HTTP/|Location"
```

✅ The new listing's address is `https://` and the public host, not `http://localhost`:
```
HTTP/1.1 201
Location: https://rentalhub.onrender.com/api/properties/17
```

And the session cookie is only ever sent over HTTPS:

```powershell
curl.exe -s -i -X POST http://localhost:8082/session/user -d "userId=5" | Select-String "Set-Cookie"
```

✅ `Set-Cookie: JSESSIONID=…; Path=/; Secure; HttpOnly; SameSite=Lax` (browsers treat
`localhost` as secure, so signing in still works on http://localhost:8082).

Stop the container (`Ctrl+C`).

---

## Part 76 — Deploy to Render

**Not run by me:** it needs your Render account. The steps follow Render's documentation and the
Blueprint was checked against it; what you'll see is described, not copied.

Follow the README's [Deploy to Render](../../README.md#deploy-to-render), steps 1–8. What to check
along the way:
- **Blueprint preview** (step 4): three resources, `rentalhub` (web service, Docker),
  `rentalhub-db` (PostgreSQL) and `rentalhub-cache` (Key Value), all free, in Singapore.
- **The build** (step 6): the `rentalhub` service's **Logs** show the same Docker steps as Part 73,
  then JSON lines as in Part 74. The first deploy takes a while: the image build (about 5 minutes),
  then the start-up on the free CPU (about 2.5 minutes).
- **Live:** `https://<your address>/actuator/health` answers `"status":"UP"`, the home page shows
  16 places, and the logs contain `"message":"demo.seeded"`.
- **After 15 minutes without visitors** the service sleeps; the next visit shows Render's
  "waking up" page for about a minute, then the app starts as above.

If any step differs from this description, tell me what you saw: this part is the one written
without a run.

---

## What you just proved

- [ ] All automated tests pass on your machine (see the project log for the current count)
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
- [ ] Saving a listing twice leaves one favourite, and removing one that isn't saved is not an error
- [ ] With no AI key the app starts, says so in one log line, and questions still answer 200 with real listings
- [ ] The rules read the city, the party size and the budget out of a sentence with no model involved
- [ ] "Under $60 a night" excludes a ₹6,000 listing, converted per currency as in Phase 5
- [ ] Nobody is recommended their own listing
- [ ] "How much have I spent?" is answered by SQL, exactly, with or without a key
- [ ] A wrong key doesn't break anything: listings are still created and questions still answered, with warnings naming the failure
- [ ] The backfill job logs one warning per listing instead of abandoning the batch
- [ ] The same error in English, Hindi and Spanish, chosen by Accept-Language, by `?lang=`, and by the remembered cookie
- [ ] Spring's own errors, bean validation and the AI's answers follow the language too
- [ ] With no storage configured, a photo upload is a clear 503 and nothing else changes
- [ ] With MinIO standing in for S3, a photo is stored under a random key and served back byte for byte
- [ ] A renamed PDF, a PNG claiming to be a JPEG, a 6 MB file and a guest's upload are all refused, and nothing is stored
- [ ] Removing a photo removes the row, then the file
- [ ] GraphQL returns exactly the fields asked for, with money as an exact string, and translated errors
- [ ] Every endpoint is in Swagger UI with an example, and the Postman collection runs top to bottom
- [ ] The pages search, book, pay, cancel, save, ask and list a place through the same services and rules as the API
- [ ] "Sign in as" gives a new session id; the cookie is HttpOnly and SameSite=Lax, never in the address; `returnTo` never leaves the site
- [ ] A refused form comes back at the page's own address, each error beside its field, what you typed kept
- [ ] The "list a place" form shows each type's own fields, generated from the factory, and works without JavaScript
- [ ] Every page in English, Hindi and Spanish, prices and dates written the reader's way
- [ ] 404, 400, 405 and a too-large photo are pages in the reader's language, never a stack trace
- [ ] An empty database gets a demo world (every type, five currencies, stays, reviews, favourites), recorded as `system:demo-seeder`; a database with data is never touched
- [ ] With Redis stopped the health check says DEGRADED with HTTP 200, and UP again when it's back
- [ ] The image builds in layers (our code ~475 kB, libraries 135 MB), rebuilds in seconds, and runs as a non-root user
- [ ] Under Render's free limits (512 MB, 0.1 CPU) it starts, and stays around 360 MB after traffic
- [ ] Behind a proxy it writes `https://` addresses and a `Secure` cookie

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
| Part 48: `"aiUsed": false` and `"semantic": false` | no `GEMINI_API_KEY` — the expected state for Parts 46–50 | Part 51 shows what changes with a key |
| Part 48: `The term '$60' is not recognized` | PowerShell read `$60` as a variable | keep the backtick: ``` `$60 ``` |
| Part 49: a question you expected to be `STATS` comes back `RECOMMEND` | the rules need a measure *and* a subject about your own history | phrase it like "how much have I spent on **bookings**" |
| Part 51: `ai.mode ready=false` although you set the key | the variable was set in a different window, or after the app started | set it in Window 1, then start the app |
| Part 51: `429 RESOURCE_EXHAUSTED` in the log | Gemini's free tier rate limit | it retries on the next job run; questions still answer without it |
| Part 51: `vector_store` stays empty | the index job is off (`-`) or the key is rejected | check Window 1 for `ai.listing.embedFailed` |
| Hindi shows as `????` or boxes | the console is not reading UTF-8, or its font has no Devanagari | `[Console]::OutputEncoding = [Text.Encoding]::UTF8`, and use Windows Terminal |
| Part 56: `service "minio" is not running` or `No such container: rentalhub-minio` | MinIO only starts with its profile | `docker compose --profile photos up -d` |
| Part 57: `503` with `image.storage.unavailable` | the bucket doesn't exist, or the credentials don't match MinIO's | redo Part 56, step 2; check the six variables were set in Window 1 *before* starting |
| Part 57: `images.mode storage=NONE` although you set S3_BUCKET | the variables were set in another window, or after starting | set them in Window 1, then start the app |
| Part 58: the 6 MB upload hangs or the connection resets | an old curl | `curl.exe --version` should be 8.x (Windows 11 ships it) |
| Part 61: Postman's upload says the file can't be found | Postman's working directory isn't the repository | *Settings → General → Working directory*: `C:\dev\rentalhub` |
| Parts 64–70: the pages look like plain text, with no colours or layout | no internet: Bootstrap comes from a CDN | connect; everything still works, just unstyled |
| Parts 65–70: suddenly "Sign in as" again, or a form says "Sign in first" | the app was restarted, and sessions live in its memory | sign in again from the navbar |
| Part 66: the date boxes show `dd-mm-yyyy` or `mm/dd/yyyy` | the browser draws its date picker in your Windows language | expected; it sends `2026-09-26` to the app either way |
| Part 69: the 6 MB upload shows "This site can't be reached" | the app was started before this phase's `application.yml` (`max-swallow-size`) | restart the app |
| Part 69, step 3: the upload says storage is switched off | the six variables were set in another window, or after the app started | set them in Window 1, then start the app |
| Parts 2–70: 16 listings you didn't create, or ids that don't match | the demo data is on (Phase 9) | `$env:DEMO_DATA_ENABLED = "false"` in Window 1, then Part 0 again |
| Part 71: `demo.skipped reason=database not empty` on the first start | the database wasn't empty | `docker compose down -v`, `docker compose up -d`, start again |
| Part 71: `demo.skipped reason=switched off` | `DEMO_DATA_ENABLED` is still `false` in this window | `Remove-Item Env:DEMO_DATA_ENABLED`, start again |
| Part 73: `failed to solve` or `Cannot connect to the Docker daemon` | Docker Desktop isn't running | start it, wait for "Engine running" |
| Part 74: `network rentalhub_default not found` | `docker compose up -d` wasn't run from this folder, or the project has another name | `docker network ls` shows the right name (ends in `_default`) |
| Part 74: nothing for minutes after `Starting RentalHubApplication` | a tenth of a CPU is slow: about 2.5 minutes is normal | wait; without `--cpus=0.1` it starts in about 10 seconds |
| Part 74: `Web server failed to start. Port 8082 was already in use` | something else uses 8082 | pick another: `-p 8083:10000`, and use that port |

**Tip:** to see a JSON response nicely indented, pipe it through PowerShell:
`curl.exe -s http://localhost:8081/api/properties/1 | ConvertFrom-Json | ConvertTo-Json -Depth 5`
