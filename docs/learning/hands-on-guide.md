# Hands-on guide: test RentalHub yourself

Everything built so far (Phases 1 and 2), tested by you, step by step. Each step has
the exact PowerShell command and what you should see. Every command and expected
output here was run and checked on 13 Sep 2026 against a fresh database.

**Time:** about 45 minutes. **You'll use three PowerShell windows:**

| Window | Used for |
|---|---|
| **1 — App** | runs the application and shows its log |
| **2 — Commands** | everything you type in this guide |
| **3 — Redis** | (Part 11 only) watches Redis live |

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

✅ Near the end: `Tests run: 86, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.
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

## Part 18 — Clean up

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

- [ ] All 86 automated tests pass on your machine
- [ ] Flyway built the schema; the double-booking rule and CHECK constraints are in Postgres
- [ ] The factory builds each type and enforces each type's rules (400s with the field)
- [ ] Permissions: guests can't create; only the owner edits (403s)
- [ ] A listing read twice hits the database once; equivalent searches share a cache entry
- [ ] Updates evict the listing and flush exactly the affected search pages (seen live in MONITOR)
- [ ] Moving city flushes the old city's pages
- [ ] Postgres itself refuses overlapping bookings, zero-night stays, bad statuses and 6-star reviews
- [ ] The app keeps working with Redis down
- [ ] A cache hit never runs your method (seen in the debugger)

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

**Tip:** to see a JSON response nicely indented, pipe it through PowerShell:
`curl.exe -s http://localhost:8081/api/properties/1 | ConvertFrom-Json | ConvertTo-Json -Depth 5`
