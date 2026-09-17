# 04 — Auditing, logging and scheduling

**What Phase 4 built:**
- **An audit trail.** Every change to a listing, booking or review is recorded: what it
  looked like afterwards, when, and who made it.
- **A history view** that turns the audit trail into "what changed" for a listing's host.
- **Structured logging.** Every event is logged as named fields, and every line carries
  the request it belongs to.
- **A nightly job** that takes listings off the market once their last day has passed.
- **A small reviews API.** The spec audits and logs reviews, and until now nothing
  created any.

---

## 1. Why an audit trail?

Logs tell you what the **program** did. An audit trail tells you what happened to the
**data**. Questions an audit trail answers, and nothing else can:

- "The price of my listing changed. Who changed it, and when?"
- "Why is this listing inactive? Did the host switch it off, or did the system?"
- "What did this review say before the guest edited it?"
- "What did this listing look like before it was deleted?"

Backups can't answer these either. A backup is a picture of *everything* at one moment.
An audit trail is a film of *each row*, frame by frame.

---

## 2. Hibernate Envers, on one page

📄 `domain/model/Property.java`, `Booking.java`, `Review.java` (`@Audited`)

**Envers** is Hibernate's auditing module. You put `@Audited` on an entity, and:

1. Whenever a transaction inserts, updates or deletes that entity, Envers notices. It
   hooks into Hibernate's own insert, update and delete events.
2. Just before the transaction commits, Envers writes one row per changed entity into its
   **history table**: `properties_aud` for `properties`, and so on. The row holds the
   entity's state *after* the change.
3. It also writes one row into `revinfo`, the **revision**. Every history row from that
   transaction points to this revision through the `rev` column.
4. `revtype` says what happened: **0 created, 1 updated, 2 deleted**.

One transaction means one revision, however many rows it changed. **A rolled-back
transaction leaves no history.** Envers writes inside the same transaction, so history and
data can never disagree.

Here's what it looked like in the hands-on run. The villa was created, renamed, then moved
to Mumbai:

```
 rev | revtype |      title       |  city  | price_per_night | active
-----+---------+------------------+--------+-----------------+--------
   1 |       0 | Sea breeze villa | Goa    |      12000.0000 | t
   3 |       1 | Sunset villa     | Goa    |      12000.0000 | t
   4 |       1 | Sunset villa     | Mumbai |      15000.0000 | t
```

Revision 2 is missing because it was the apartment's creation: revision numbers are shared
by every table.

### How our setup is configured

📄 `db/migration/V2__auditing_and_review_uniqueness.sql`, `application.yml`
(`spring.jpa.properties.org.hibernate.envers.*`)

- **Flyway creates the history tables.** Hibernate is only allowed to *validate* the
  schema, so V2 creates `revinfo`, `properties_aud`, `bookings_aud` and `reviews_aud`.
- **The SQL for them came from Hibernate itself.** I ran the tests once with schema
  generation switched on (`jakarta.persistence.schema-generation.scripts.action=create`)
  and copied the audit tables from the script it wrote. That guarantees startup
  validation passes. It's a trick worth knowing.
- **The history tables are deliberately permissive:**
  - no `NOT NULL` on business columns;
  - no `CHECK` lists;
  - no foreign keys to the live tables.

  History must outlive the rows it describes, and must be able to record values that later
  rules forbid. The only foreign key is `rev → revinfo`.
- **Settings:**
  - `audit_table_suffix: _aud`, for lower-case names the way Postgres stores them;
  - `store_data_at_delete: true`, so the "deleted" row keeps the entity's last state. A
    deleted listing's history can still say what it was, and whose.
- **One table for all listing types.** Listings use single-table inheritance, so there is
  one `properties_aud` with the `property_type` column. Every subtype carries `@Audited`,
  and `PropertyFactoryTest` fails if a new type forgets it.

### What is *not* audited, and why

| Field | Why not |
|---|---|
| `images` | they get their own storage in Phase 7 |
| `version` | Envers skips the optimistic-lock field by default; it's bookkeeping, not data |
| `updatedAt` | the revision already records when |
| the `host`, `guest` and `author` users | users have no history. The history row stores the user's **id** (`@Audited(targetAuditMode = NOT_AUDITED)`) |

A nice consequence: **a booking doesn't add to the listing's history.** A booking raises
the listing's version (Phase 3), but that bump is a direct SQL `UPDATE` that Envers never
sees, and the version isn't audited anyway. `ListingHistoryApiTest` checks this.

---

## 3. Recording *who* made the change

📄 `audit/Revision.java`, `audit/ActorRevisionListener.java`, `audit/AuditActor.java`,
`web/RequestIdFilter.java`

Envers' default revision records only a number and a time. We replaced it with our own
**revision entity**, `Revision`, which maps to `revinfo` and adds a `changed_by` column:

| changed_by | means |
|---|---|
| `user:1` | a request made as user 1 |
| `system:stale-listing-job` | the nightly job |
| `anonymous` | nobody said who they were |

**How the name gets there:**

1. `RequestIdFilter` runs first for every request. It reads `X-Demo-User-Id` and makes
   `user:<id>` the current **AuditActor**.
2. When Envers opens a revision, it calls `ActorRevisionListener.newRevision()`, which
   copies `AuditActor.current()` into the revision.
3. The job sets its own actor with `try (var scope = AuditActor.as(AuditActor.system(NAME)))`.

**Why a ThreadLocal?** Envers creates the listener itself, so it isn't a Spring bean and
nothing can be injected into it. A **ThreadLocal**, a variable with a separate value per
thread, works because Envers calls the listener on the thread running the transaction.
That's the request's thread, or the scheduler's.

**The ThreadLocal trap.** Servlet and scheduler threads are **pooled**, meaning reused
for request after request. If a value weren't removed, the next request on that thread
would inherit it, and a change would be blamed on the wrong person. So every setter hands
back a `Scope` that restores the previous value, and it's always used in
try-with-resources or a `finally` block. `RequestIdFilterTest.cleansUp` checks it.

**One limit to know:** work handed to *another* thread, with `@Async` for example, would
not carry the actor with it. Nothing in RentalHub does that today.

---

## 4. The history view

📄 `service/ListingHistoryService.java`, `GET /api/properties/{id}/history`

Envers stores whole **snapshots**. People want to know **what changed**. So the service:

1. Asks Envers' **AuditReader** for every revision of the listing, oldest first:
   ```java
   AuditReaderFactory.get(entityManager).createQuery()
           .forRevisionsOfEntity(Property.class, false, true)   // rows of [entity, revision, type]; include deletions
           .add(AuditEntity.id().eq(propertyId))
           .addOrder(AuditEntity.revisionNumber().asc())
           .getResultList();
   ```
2. Turns each snapshot into a map of field → value. Type-specific fields come from
   `typeAttributes()`, so no property type is special-cased.
3. Compares each snapshot with the one before and reports only the differences. Prices
   are compared with `compareTo`, so `12000.00` and `12000.0000` count as the same.

From the hands-on run (the readable PowerShell view):

```
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

**The rules:**
- Only the listing's host may see its history.
- The host still can after deleting the listing: the deletion's history row keeps the last
  state, host included.
- A listing that has no history rows at all (created before V2) has an empty history. It
  is not a 404, because the listing exists.

---

## 5. What the audit trail can't see

This matters in interviews, because it's where audit trails quietly fail. Envers sees only
changes made **through Hibernate entities**:

| Change made by | Recorded? |
|---|---|
| a service loading an entity and changing it | ✅ |
| plain SQL (psql, a script, `jdbc.update`) | ❌ |
| a bulk JPQL `UPDATE Property p SET ...` (`@Modifying` query) | ❌, because it bypasses the entity events |
| Hibernate's forced version bump (Phase 3) | ❌, and that's what we want |

The hands-on guide shows this on purpose. You set the villa's last available day with
plain SQL, and when the job then deactivates the villa, its history entry shows
**both** changes: `active: True -> False; availableUntil: -> 2026-09-14`. The date change
had gone unrecorded, so the next audited change swept it up, and it was attributed to the
job. **Rule:** production code changes data only through the services. That's also why the
job goes through `PropertyService`.

---

## 6. Structured logging

📄 `application.yml` (`logging.pattern.console`), `application-render.yml`, every
`log.at…()` call, `web/RequestIdFilter.java`

### From sentences to fields

Before:
```java
log.info("booking.created bookingId={} propertyId={} ...", id, propertyId, ...);
```
The values are glued into one string, which is fine for a human and poor for a machine.

Now, with SLF4J 2's **fluent API**:
```java
log.atInfo().setMessage("booking.created")
        .addKeyValue("bookingId", booking.id())
        .addKeyValue("propertyId", booking.property().id())
        ...
        .log();
```
The message is just the **event name**. Each value is a separate **key/value pair** that
travels with the log event. What happens to them depends on where the log goes:

- **Locally (your console),** the pattern ends with `%m %kvp{NONE}`, so a line looks
  exactly as before:
  ```
  … INFO [nio-8094-exec-1] 3547c014 com.rentalhub.service.BookingService : booking.created bookingId=1 propertyId=2 guestId=2 checkIn=2027-03-10 checkOut=2027-03-13 total=7500.00 currency=INR
  ```
- **On Render (the `render` profile),** Spring Boot's built-in structured logging writes
  one **JSON** object per line, in the Elastic Common Schema (ECS). Every pair is a field of
  its own:
  ```json
  {"@timestamp":"2026-09-15T15:40:54.813020100Z","log":{"level":"INFO","logger":"com.rentalhub.service.BookingService"},
   "message":"booking.created","userId":"4","requestId":"json-demo-1","bookingId":3,"propertyId":2,"guestId":4,
   "checkIn":"2027-03-13","checkOut":"2027-03-16","total":7500.00,"currency":"INR", ...}
  ```
  A log tool can now answer "every booking over ₹10,000 for listing 2 this week" without
  parsing text.

Every event log in the app moved to this style, not just bookings and reviews, so the JSON
logs are uniformly searchable.

### The MDC and request ids

The **MDC** (Mapped Diagnostic Context) is a per-thread map whose entries are added to
every log line written on that thread. `RequestIdFilter` puts two things in it:

- **`requestId`**: taken from the caller's `X-Request-Id` header if it looks like an id,
  otherwise 8 random hex characters. It's echoed back in the response's `X-Request-Id`, so
  a user reporting "I got an error" can quote it, and every log line of that request can be
  found. (It's the column after the thread name in the local log.)
- **`userId`**: from `X-Demo-User-Id`.

The job puts `job=stale-listing-job` in the MDC instead.

**Log injection.** The caller chooses the request id, and it's written into logs and a
response header. A caller could send `x\n2026-09-15 INFO booking.created forged=true` to
forge a fake log line. So an incoming id is accepted only if it matches `[A-Za-z0-9._-]{1,64}`;
anything else is replaced. `RequestIdFilterTest` tries exactly that forged line.

---

## 7. The nightly job

📄 `scheduling/StaleListingJob.java`, `config/SchedulingConfig.java`, `application.yml`
(`rentalhub.jobs.stale-listings`)

### Scheduling in Spring

`@EnableScheduling` switches the machinery on. `@Scheduled(cron = "...")` runs a method on
a schedule. Spring's cron has **six** fields (it adds seconds in front):

```
 ┌ second (0)
 │ ┌ minute (15)
 │ │  ┌ hour (3)
 │ │  │ ┌ day of month (* = every)
 │ │  │ │ ┌ month
 │ │  │ │ │ ┌ day of week
 0 15 3 * * *        → 03:15:00 every day
 0 *  * * * *        → the start of every minute (the hands-on guide uses this)
 -                   → never: the job is switched off (the integration tests do this)
```

The cron and time zone come from config (`STALE_LISTINGS_CRON`, `STALE_LISTINGS_ZONE`), so
production and your laptop can differ without a code change. The zone matters: without
one, "03:15" means the server's time zone, and a server on the other side of the world
would run the job at a strange local hour.

### How the job is built

1. Ask for the **ids** of active listings whose `availableUntil` is before today. (The
   last day is still bookable: check-out may fall on it.)
2. Deactivate each one **in its own transaction, through `PropertyService.deactivate`.**
   - The cached listing and its search pages are invalidated, as for any edit.
   - The history records `system:stale-listing-job`.
   - If one listing fails (its host saving an edit at that instant, say), the job logs it
     and carries on. The next run retries it. One bad row never undoes the others.
3. Log each deactivation (`listing.deactivated propertyId=1 availableUntil=... reason=availability-ended`)
   and a summary (`job.staleListings.finished ... deactivated=1 failed=0 propertyIds=[1] durationMs=73`).

It's **idempotent**: running it twice changes nothing the second time.

**Testing it.** The spec asked for a test that calls the method directly, so the scheduled
method is a one-line wrapper around a public `deactivateExpiredListings()`, which returns
a `Report`. `StaleListingJobTest` calls it and checks:
- exactly the right listings change;
- the cache follows;
- the log lines appear;
- the history names the job.

`StaleListingJobScheduleTest` checks that the configured cron really means 03:15 daily.
The integration tests switch the schedule off, so without it a typo in the cron would first
show up in production.

**More than one app instance?** Each instance would run the job. That's harmless here,
because the second run finds nothing to do, but wasteful. The standard fix is a shared lock
such as **ShedLock**, so that one instance wins each run.

---

## 8. Reviews

📄 `service/ReviewService.java`, `web/rest/ReviewController.java`

- **Endpoints:**
  - `POST /api/properties/{id}/reviews`;
  - `GET /api/properties/{id}/reviews` (public);
  - `GET`, `PUT` and `DELETE /api/reviews/{id}` (changes by the author only).
- **Who may review:** a guest with a **confirmed (or completed) stay whose check-out day
  has come**. Cancelled stays don't count. That's what stops fake reviews, and it rules out
  hosts too, since they can't book their own listings.
- **One review per guest per listing.** The service checks first, for a friendly 409.
  The database's unique constraint `uq_review_author_property` (V2) makes it true even for
  two simultaneous requests, and its violation is mapped to the same 409. The pattern is
  the same as bookings: a check for the answer, a constraint for the guarantee.
- Reviews are audited: `reviews_aud` kept both the 5-star original and the 4-star edit in
  the hands-on run.

---

## 9. How it's tested

| Test class | Kind | Proves |
|---|---|---|
| `AuditActorTest` | unit | anonymous by default; scopes nest and restore; user ids read back |
| `RequestIdFilterTest` | unit | a fresh id, echoed, and tagged on the MDC with the user; a safe supplied id kept; a log-forging id replaced; nothing left on the thread |
| `ListingHistoryServiceTest` | unit | only real changes are reported, and decimals don't count; a creation lists every field that was set |
| `ConstraintViolationsTest` | unit | the right constraint is found through wrapped exceptions, and no false matches |
| `StaleListingJobScheduleTest` | unit | the configured cron is valid, and means 03:15 daily |
| `PropertyFactoryTest` (new test) | unit | every property entity is `@Audited` |
| `ListingHistoryApiTest` | integration | history in order, with who and field-by-field changes; bookings stay out; the job is named; host only, even after deletion; empty history for never-audited listings |
| `StaleListingJobTest` | integration | exactly the expired active listings are deactivated; the cache follows; the log lines appear; the history names the job; a second run does nothing |
| `ReviewApiTest` | integration | the stay rule, including check-out today and cancelled stays; one per guest; the rating range; author-only edits and deletes; the review's history |
| `StructuredLoggingTest` | integration | `booking.created` carries its key/value pairs and the MDC's request id and user, and prints as `key=value` |
| `PersistenceMappingTest` | integration | every migration applied and validated, including the Envers tables |

**Total after Phase 4: 162 tests (94 unit, 68 integration), all passing.**

---

## Interview questions — practise answering these aloud

**Q: How do you audit changes in your app?**
Hibernate Envers. I annotate entities with `@Audited`, and whenever a transaction changes
one, Envers writes its new state to a history table in the same transaction, plus a
revision row with the time and, through a custom revision entity, who did it. History and
data commit or roll back together.

**Q: How do you record who made a change?**
A custom revision entity with a `changed_by` column, filled in by a revision listener. The
listener reads the current actor from a ThreadLocal, which a servlet filter sets from the
request's user and a scheduled job sets to `system:<job>`. It's always cleared in a
`finally` block, because threads are pooled and a leftover value would blame the wrong
person.

**Q: What can't Envers see?**
Anything that doesn't go through Hibernate entity events: plain SQL, bulk JPQL updates, and
direct version bumps. So production code changes data only through the services, and I
know that a manual database fix leaves a gap in the history.

**Q: How does your history endpoint work?**
AuditReader returns the listing's snapshot at every revision, oldest first. I turn each
snapshot into a field map, compare it with the previous one, and report only the fields
that changed, comparing prices by value. It's host-only, and it survives deletion because
Envers keeps the last state in the delete row.

**Q: Why are your history tables permissive, with no NOT NULL and no foreign keys?**
Because history outlives the rows it describes. A deleted listing's history must stay
readable, and a row recorded under old rules must still fit after the rules change. The
only foreign key is to the revision table.

**Q: Why did you create the audit tables with Flyway rather than letting Hibernate create them?**
Flyway owns the schema, and Hibernate only validates it. That makes every environment's
schema reproducible and reviewable. I generated the DDL once with Hibernate's schema
export, so the migration matches exactly what validation expects.

**Q: What is structured logging, and why bother?**
Logging events as named fields instead of sentences. With JSON logs you can filter and
aggregate by field (all failed bookings for listing 2, the 95th-percentile job duration)
without regular expressions. I use SLF4J's fluent API with key/value pairs. Locally they
print as `key=value`; in production Spring Boot writes ECS JSON.

**Q: What is the MDC?**
A per-thread map that the logging framework adds to every log line. A filter puts the
request id and user id there at the start of a request and removes them at the end, so
every line of that request carries them, even lines from code that knows nothing about
requests.

**Q: Why return the request id in a response header?**
So a problem report can quote it and you can find every log line of that request. If a
proxy already assigned an id, I keep it, so the id follows the request across systems.
That's the start of distributed tracing.

**Q: What's log injection, and how do you prevent it?**
Putting a line break into data that gets logged, to forge log lines. I only accept a
caller's request id if it matches a strict pattern, and otherwise generate my own.
Structured JSON logging escapes values too, which helps.

**Q: How is your scheduled job built so it's safe?**
Idempotent, one transaction per item, and through the service layer. A failure in one item
is logged and retried on the next run without undoing the others. The cache and history
stay correct because the job uses the same service methods as users. The cron is config,
disabled in tests, and checked by its own test.

**Q: What happens with two app instances?**
Both would run the job. It's harmless here, because it's idempotent, but wasteful. I'd add
ShedLock, a lock row in the database, so one instance runs each execution.

**Q: How do you test a scheduled job?**
I don't wait for the clock. The scheduled method delegates to a public method, the test
calls that directly and checks the data, the cache, the log lines and the history. A
separate unit test checks that the cron expression itself is valid and means what I think.

**Q: How do you stop two reviews by the same guest?**
A check in the service for a clear message, and a unique constraint in the database for the
guarantee. The constraint's violation is mapped to the same 409, the same pattern as the
booking overlap constraint.

**Q: Audit trail vs event sourcing?**
An audit trail records state *after* each change, alongside the normal tables, which stay
the source of truth. Event sourcing makes the events themselves the source of truth and
derives state from them. Envers gives most of the "who changed what" value at a fraction of
the complexity.

---

## Honest limitations

- **Changes made outside the app aren't audited:** SQL, scripts, bulk updates. §5.
- **History tables only grow.** A real system would add retention (delete or archive after
  N years), or partition `*_aud` by date.
- **"Who" is only as trustworthy as authentication.** Anyone can send
  `X-Demo-User-Id: 1`, so the audit trail believes them. That's by design in this demo.
- **No history endpoint for bookings or reviews.** Their history is recorded, but the spec
  asked for listing history only.
- **History isn't paginated.** That's fine for listings with dozens of changes, and not for
  thousands.
- **Work on another thread (`@Async`) would lose the actor and the request id.** You'd have
  to copy them across, for example with a task decorator.
- **The job has no distributed lock.** One instance only, as §7 explains.

---

## Try it yourself

1. **Forget to audit a type.** Remove `@Audited` from `Studio` and run
   `.\mvnw.cmd test "-Dtest=PropertyFactoryTest"`. The new test names the problem. Put it
   back.
2. **Break the schema.** In a *new* migration `V3__try.sql`, drop `properties_aud.has_pool`
   and start the app. Hibernate's validation stops it. Delete the file, run
   `docker compose down -v`, and start again.
3. **Lose the actor.** In `RequestIdFilter`, remove the `try (AuditActor.Scope …)` and run
   `ListingHistoryApiTest`. Changes are now recorded as `anonymous`. Put it back.
4. **Bypass the audit trail.** In psql, `UPDATE properties SET title = 'Hacked' WHERE id = 1;`.
   Read the history (nothing), then make any change through the API and read it again. The
   next entry shows the hacked title as if the API had changed it.
5. **Change the schedule.** Start the app with `$env:STALE_LISTINGS_CRON = "*/10 * * * * *"`
   and watch `job.staleListings.finished` every 10 seconds.

---

## YouTube for this phase (in order)

1. `hibernate envers tutorial`
2. `database audit trail design`
3. `spring boot structured logging` and `json logging`
4. `slf4j logback tutorial` and `slf4j fluent api key value`
5. `mdc logging java` and `correlation id logging`
6. `log injection attack`
7. `spring boot scheduled tasks cron` and `cron expression explained`
8. `shedlock spring boot`
9. `audit log vs event sourcing`

Clickable versions are in the [project log](project-log.md#6-youtube-study-plan--every-phase).
