# 03 — Bookings and concurrency

**What Phase 3 built:** guests can book, view and cancel stays, and hosts can see their
listings' bookings. The real subject is the moment two guests try to book overlapping dates at
the same instant. Four layers make sure exactly one of them wins, the loser gets a clear
answer, and nothing half-finished is ever left behind. Along the way, a real deadlock turned up
in testing and was fixed.

---

## 1. The problem: two guests, one set of dates

The obvious way to book:

1. Check the calendar: are 10–13 December free?
2. If yes, insert the booking.

Run by one person, it's fine. Run by two people at the same moment, it breaks:

```
         Ravi                                    Meera
 t1   check 10–13 Dec: free
 t2                                         check 12–15 Dec: free
 t3   insert booking 10–13 Dec
 t4                                         insert booking 12–15 Dec
 t5   commit ✔                               commit ✔          ← 12 Dec is double-booked
```

Each check was true *when it ran*, and false by the time it was acted on. This is a **race
condition**: the result depends on the timing of two things running at once. This shape, "check,
then act on what you checked", has a name: **check-then-act**, or **TOCTOU** (time of check to
time of use). You can't fix it by checking harder or faster, because the gap between checking and
acting never becomes zero.

Adding `@Transactional` alone doesn't fix it either. At Postgres's default isolation level
(§2), each transaction sees only data that others have *committed*. Neither sees the other's
uncommitted booking, so both checks pass.

---

## 2. Transactions, on one page

A **transaction** groups database changes so that they all happen or none do. The four promises
of a transaction are **ACID**:

| Letter | Promise | In this phase |
|---|---|---|
| **A**tomicity | all or nothing | a booking whose attempt fails leaves no row behind, not even half of one |
| **C**onsistency | constraints hold after every commit | the overlap constraint, CHECKs and foreign keys |
| **I**solation | concurrent transactions don't see each other's half-done work | this is exactly what makes the race possible *and* what the layers below manage |
| **D**urability | once committed, it stays | a confirmed booking survives a crash |

**Isolation levels** say how much concurrent transactions can see of each other:

| Level | What a transaction sees | Postgres |
|---|---|---|
| READ COMMITTED | each statement sees everything committed before *that statement* started | **the default, and what we use** |
| REPEATABLE READ | one snapshot for the whole transaction | conflicting writes fail with a serialization error |
| SERIALIZABLE | as if transactions ran one after another | Postgres aborts any transaction that would break that illusion (error 40001) |

**Why not just use SERIALIZABLE?** It would stop the double booking too, but:

- every transaction that loses gets aborted and needs a retry anyway;
- it applies to *every* query in the transaction, not just the one row that matters;
- the rule we care about, "no overlapping dates for one listing", can be stated exactly as a
  database constraint (§6), which is cheaper and clearer.

**`@Transactional` in Spring:** Spring wraps the bean in a **proxy**, an object that stands in
front of it. When a call enters a `@Transactional` method, the proxy begins a transaction. When
the method returns, it commits. If an unchecked exception escapes, it rolls back. Remember that
the commit happens *after your method has returned*; it matters a lot in §5.

---

## 3. The layers, from the outside in

📄 `service/BookingService.java`, `service/BookingAttempt.java`, `config/RetryConfig.java`,
`domain/repository/PropertyRepository.java`, V1's `no_overlapping_bookings`

```
 HTTP POST /api/bookings
   └─ BookingService.book()                      ① date rules (no database needed)
        └─ RetryTemplate.invoke()                ② retry, then recover
             └─ BookingAttempt.place()           ③ ONE transaction per attempt
                  ├─ read listing with OPTIMISTIC_FORCE_INCREMENT
                  ├─ listing rules, "already booked?" check
                  ├─ INSERT booking               ④ the exclusion constraint checks it here
                  └─ (commit) UPDATE listing version   ← the version race is decided here
```

| Layer | Catches | The guest sees |
|---|---|---|
| Date rules | impossible requests: dates in the past, check-out before check-in, over 90 nights | 400 with the field |
| Friendly check | dates someone booked earlier (committed) | 409 "Those dates are already booked." |
| Version race + retry | two bookings of one listing overlapping *in time*; a price changed mid-booking | usually nothing: the retry succeeds |
| Exclusion constraint | an overlapping row the check couldn't see yet | 409 "Those dates were just taken by another guest." |
| Recover | still losing after 4 attempts | 409 "Those dates were just taken by another guest." |

---

## 4. Optimistic locking, and forcing the version up

📄 `PropertyRepository.findForBookingById`

### 4.1 The version column, again

Since Phase 1, every listing has a `version` column. When Hibernate saves a change, it writes:

```sql
UPDATE properties SET title = ?, …, version = 6 WHERE id = 2 AND version = 5
```

If someone else committed a change first, the row's version is already 6. The `WHERE` matches no
row, and Hibernate throws an optimistic-locking error instead of silently overwriting their
work. This is **optimistic locking**: lock nothing, assume conflicts are rare, and detect them at
the end.

### 4.2 Why a plain version check isn't enough for bookings

A booking **doesn't change the listing's row**. It inserts into `bookings`. So nothing ever
writes the listing, and its version never moves. Two bookings of the same listing would never
notice each other.

`@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)` fixes that. It tells Hibernate: when this
transaction commits, raise this entity's version *even though I didn't change it*:

```sql
UPDATE properties SET version = 6 WHERE id = 2 AND version = 5
```

Now the listing's version works like a numbered ticket. Every booking of the listing must take
the next number, and only one transaction can take each one:

```
         Ravi (10–13 Dec)                        Meera (20–22 Dec, no overlap)
 t1   read listing: version 5                 read listing: version 5
 t2   dates free, insert booking              dates free, insert booking
 t3   commit: UPDATE … version = 6
          WHERE version = 5   → 1 row ✔
 t4                                           commit: UPDATE … version = 6
                                                  WHERE version = 5   → 0 rows ✘
                                              → optimistic-locking error, whole
                                                transaction rolled back (booking too)
 t5                                           retry, 50 ms later: reads version 6,
                                              dates free, insert, commit 6 → 7 ✔
```

In READ COMMITTED, Meera's UPDATE at t4 *waits* if Ravi's is still uncommitted, then re-reads the
row once Ravi commits. That is why it reliably sees version 6 and matches nothing.

### 4.3 The lock modes, compared

| JPA lock mode | What Hibernate does | Would it stop two bookings? |
|---|---|---|
| (none) | nothing | no |
| `OPTIMISTIC` | at commit, *reads* the version and checks it hasn't changed | **no**: neither booking changes it, so both checks pass |
| `OPTIMISTIC_FORCE_INCREMENT` | at commit, *raises* the version, failing if it moved | **yes**, and nothing is locked while the booking is checked |
| `PESSIMISTIC_WRITE` | `SELECT … FOR UPDATE`: locks the row until commit | yes, by making the second booking *wait* |
| `PESSIMISTIC_FORCE_INCREMENT` | locks the row *and* raises the version immediately | yes |

**Why optimistic here?** Two guests booking the same listing within the same fraction of a second
is rare. Optimistic locking costs nothing until that happens. Pessimistic locking makes every
booking queue behind a row lock for its whole transaction, conflict or not, and keeps readers of
that row waiting too. The rule of thumb: **optimistic when conflicts are rare, pessimistic
when they're common or a retry is expensive.**

### 4.4 The bonus: a booking can't use a stale price

The ticket also covers the host. Saving a listing edit raises the version too, so:

- if the host changes the price while a booking is in flight, the booking loses the race. Its
  retry reads the **new** price. A booking is never charged from a listing that changed underneath
  it. (`BookingConcurrencyTest` stages exactly this.)
- if the booking commits first, the host's save loses and gets a 409 (§11).

### 4.5 The cost, stated honestly

The listing's version now counts **edits and bookings**. Two consequences, both handled:

- The cached listing view shows `version`, so after a booking it would be out of date.
  `BookingAttempt` publishes a `ListingBookedEvent`, and `PropertyCacheInvalidator` evicts that
  one cache entry after commit. Search pages don't show the version, so they stay cached.
- All bookings of *one* listing take turns at commit. For a holiday rental, with a handful of
  bookings per listing per day, that's nothing. A concert with 10,000 seats selling out in a
  minute would make its single row a **hot spot**, and it would be better to lock per seat or
  per date.

### 4.6 *When* the version is raised

Hibernate raises the version **as the transaction commits**. That is after `place()` has
returned, inside the transaction proxy. This one fact decides where the retry must go.

---

## 5. Retry, then recover

📄 `config/RetryConfig.java`, `service/BookingService.java`

### 5.1 Why retry at all?

Losing the version race doesn't mean the dates are taken. In the §4.2 timeline, Meera wanted
*different* dates; she only lost because Ravi booked the same listing a moment earlier. Running
her attempt again, from scratch, succeeds.

### 5.2 Why the retry must be outside the transaction

```java
booking = retry.invoke(() -> attempt.place(request, guestId));
```

- `attempt.place()` is `@Transactional`. Each call through the proxy is a **new** transaction,
  with a new database session that reads the listing again.
- The version race is decided when that transaction commits, which is when the proxy returns.
  Only code *outside* the proxy ever sees the failure.

If the retry were *inside* the transaction, it would wrap only the method body. It would finish,
happily, before the commit, and the commit's failure would go straight past it. There's also a
second reason: after a failure, a transaction and its session are unusable ("rollback-only"), so
re-running code inside the same transaction can't work anyway.

### 5.3 Why `BookingAttempt` is a separate class: the self-invocation trap

Why not put `place()` in `BookingService` as a private `@Transactional` method? Because
`@Transactional` works through the proxy, and **a call from inside the same object never goes
through its own proxy**. `this.place()` would run with no transaction at all. This is Spring's
**self-invocation** pitfall, and it's a very common interview question. Calling another bean
(`attempt.place()`) goes through that bean's proxy, so it works.

### 5.4 What is retried, and what isn't

The policy retries `ConcurrencyFailureException`: "this failed only because another transaction
was working on the same rows at the same moment". It covers two cases:

- a lost version race (`ObjectOptimisticLockingFailureException`);
- a deadlock victim (`CannotAcquireLockException`, §6.3).

Nothing else is retried: "already booked", "too many guests", "listing not found" would fail the
same way every time. Retrying them would only add delay and load.

### 5.5 Backoff and jitter

```yaml
rentalhub.booking.retry:
  max-retries: 3      # so at most 4 attempts
  delay: 50ms         # wait before the first retry
  multiplier: 2       # then 100 ms, then 200 ms …
  max-delay: 500ms    # … never more than this
  jitter: 25ms        # each wait shifted by a random amount up to 25 ms
```

- **Exponential backoff:** each wait is longer than the last. If the listing is busy, we back off
  rather than hammering it.
- **Jitter:** two losers that collided once, and then waited exactly 50 ms each, would collide
  again. A little randomness breaks the lockstep. It's the same idea as the "thundering herd" in
  Phase 2.

### 5.6 Recover: when the retries run out

Framework 7's retry, used through `invoke()`, rethrows the last failure once the attempts are
used up. `BookingService` catches it. That catch is the **recover** path:

```java
} catch (ConcurrencyFailureException lostEveryRace) {
    throw recover(request, guestId, lostEveryRace);    // → 409 booking.dates.justTaken
}
```

Four lost races in a row means others are booking this listing right now. The guest gets a clear
answer they can act on, instead of a 500.

### 5.7 Why a `RetryTemplate`, not `@Retryable` + `@Recover`

The spec asked for `@Retryable` and `@Recover`, which is the Spring Retry library's API. Spring Boot
4 no longer manages Spring Retry. Spring Framework 7 has its own retry support: the `@Retryable`
annotation and the `RetryTemplate` it runs on. **There is no `@Recover`.** So:

- `RetryTemplate` is the same engine, called from code, with an explicit try/catch as the recover
  step.
- The order "retry → transaction → recover" is visible in the code. It doesn't depend on which of
  two proxies Spring happens to apply first, which is exactly what can go wrong with `@Retryable`
  and `@Transactional` on one method.
- It can be unit-tested with no Spring at all (`BookingServiceRetryTest`).

---

## 6. The database's last word: the exclusion constraint

📄 V1 (`no_overlapping_bookings`), `service/OverlapConstraint.java`

### 6.1 What it does under concurrency

The constraint (Phase 1) refuses two live bookings of one listing with overlapping date ranges.
When a transaction inserts a row that overlaps another transaction's **uncommitted** row, Postgres
can't decide yet, because the other transaction might roll back. So it **waits**. When the other
one commits, the insert fails with SQLState **23P01** (exclusion violation). If it rolls back, the
insert goes ahead.

```
         Ravi (10–13 Dec)                         Meera (12–15 Dec)
 t1   check: free                              check: free
 t2   insert booking
 t3                                            insert booking → WAITS: Ravi's row
                                               overlaps and isn't committed yet
 t4   commit ✔
 t5                                            refused: 23P01 → "Those dates were just
                                               taken by another guest."
```

### 6.2 Turning the database's error into a message

`OverlapConstraint.violatedBy()` walks down the exception's *cause chain*. Spring wraps
Hibernate's exception, which wraps the driver's `SQLException`. It looks for SQLState `23P01`
**and** the constraint's name in the message, so a different exclusion constraint added one day
can't be mistaken for this one. Only JDK types are used, because the Postgres driver is a
runtime-only dependency.

Why not retry it? A retry would re-run the check, see the committed winner, and say "already
booked". That's the same answer, one database round-trip later, and a less precise message.

### 6.3 The deadlock we found

The first run of the race test failed with:

```
CannotAcquireLockException: could not execute statement [ERROR: deadlock detected]
```

A **deadlock** is two transactions each waiting for the other. Neither can ever continue, so
Postgres's deadlock detector cancels one of them (SQLState **40P01**).

How can two inserts deadlock? A primary key's B-tree index checks for a duplicate *before* adding
its entry. An exclusion constraint does it the other way round: it **adds its own entry first, then
looks for conflicts**. When two overlapping inserts land in the same instant, each one adds its
entry and then finds the other's. Both are uncommitted, so each waits for the other: deadlock.
Postgres cancels one. The other continues and commits.

The cancelled transaction did nothing wrong. It lost a race, exactly like a lost version check.
So the fix was to retry `ConcurrencyFailureException` (the parent of both) instead of only
`OptimisticLockingFailureException`. The retry then sees the survivor's booking and gives the
right answer. The unit test `deadlockVictimIsRetried` pins this down, and the race test has run
green many times since. In later runs you can still see `deadlock detected` in the log, handled.

**Lesson:** a concurrency test that only ever passes on your laptop proves little. This bug
appeared on the second run, in a test that was built to shake exactly this kind of thing out.

---

## 7. The "friendly" check: why keep it?

📄 `BookingRepository.countOverlapping`

If the constraint is the guarantee, why check first at all?

- **It gives the everyday answer cheaply.** Nearly every "those dates are taken" happens because
  someone booked *earlier*, not at the same instant. Answering that with a query is cheaper and
  clearer than inserting, failing and decoding an exception.
- **It gives a precise message.** "Already booked" (someone booked earlier) and "just taken" (you
  lost a race) are different situations for the guest.

The overlap test: two stays overlap when **each starts before the other ends**
(`b.checkIn < :checkOut AND b.checkOut > :checkIn`). Check-out day isn't a night, so a stay that
starts on another's check-out day doesn't overlap. That's the same rule as the constraint's `[)`
range.

---

## 8. One booking, step by step

`POST /api/bookings` with Ravi's request for listing 2, 10–13 March:

1. **Bean validation** (`@Valid BookingRequest`): all fields present, guests ≥ 1. Otherwise 400
   with an `errors` list.
2. **`BookingService.book()`**, not transactional:
   - `rules.checkDates()`: check-in not in the past (using the injected `Clock`), check-out after
     check-in, at most 90 nights. Otherwise 400 with the field.
   - `retry.invoke(...)`, and for each attempt:
3. **`BookingAttempt.place()`** — the transaction begins:
   - `SELECT` the listing (`findForBookingById`): Hibernate notes "raise its version at commit";
   - `SELECT` the guest;
   - own listing? → 403;
   - `rules.checkListing()`: active (else 409), guests within the limit, and the stay ends by
     `availableUntil` (else 400);
   - `countOverlapping` > 0 → 409 "already booked";
   - `Booking.reserve()` prices it: 3 nights × ₹2,500.00 = ₹7,500.00, in the listing's currency;
   - status CONFIRMED (no payments until Phase 5);
   - `INSERT` the booking. The constraint checks it now (it may wait, §6.1);
   - publish `ListingBookedEvent`;
   - build the `BookingView` while the session is still open.
4. **Commit** (inside the proxy): `UPDATE properties SET version = v+1 WHERE … AND version = v`,
   then `COMMIT`. After the commit, the listing's cache entry is evicted.
5. Back in `book()`: log `booking.created …` (only now, because before the commit it might still
   have failed) and return. The controller answers **201** with a `Location` header.

---

## 9. Money in a booking

📄 `Booking.reserve`, `Currency.round`

- **Total = nightly price × nights**, always in the **listing's own currency**. Converted figures
  (Phase 5) are for display only and never stored.
- `Currency.round()` sets the amount to the currency's number of decimals, with **half-even**
  ("banker's") rounding: an exact half goes to the even neighbour, so rounding errors cancel out
  over many amounts. Here it never actually rounds: a price can't have more decimals than its
  currency (Phase 1's rule), and multiplying by a whole number of nights adds none. It fixes the
  *scale*: `NUMERIC(19,4)` hands back `2500.0000`, and the view shows `7500.00`.
- Tests compare amounts with `isEqualByComparingTo`, never `equals`. For `BigDecimal`, `equals` says
  `7500.00` ≠ `7500.0000`.

---

## 10. The rules, and which status code each gets

📄 `service/BookingRules.java`

| Rule | Status | Key (field) | Why that status |
|---|---|---|---|
| a field missing, guests < 1 | 400 | an `errors` list | the request itself is malformed |
| check-in in the past | 400 | `booking.checkIn.past` (`checkIn`) | the request can never succeed as written |
| check-out not after check-in | 400 | `booking.checkOut.beforeCheckIn` (`checkOut`) | same |
| more than 90 nights | 400 | `booking.nights.max` (`checkOut`) | same; configurable |
| more guests than the listing sleeps | 400 | `booking.guests.tooMany` (`guests`) | fixable by changing the request |
| stay ends after `availableUntil` | 400 | `booking.checkOut.beyondAvailability` (`checkOut`) | same |
| booking your own listing | 403 | `booking.ownListing` | *who* is asking is the problem |
| no such listing / booking | 404 | `property.notFound` / `booking.notFound` | |
| someone else's booking | 403 | `booking.notYours` | |
| listing deactivated | 409 | `booking.property.inactive` | a valid request; the listing's *state* forbids it |
| dates already booked | 409 | `booking.dates.unavailable` | the calendar's state |
| lost the race | 409 | `booking.dates.justTaken` | the calendar's state, a moment ago |
| cancelling after the stay started | 409 | `booking.cancel.tooLate` | the booking's state |

"Today" comes from a `Clock` bean (`config/ClockConfig`), never from `LocalDate.now()` scattered
through the code, so `BookingRulesTest` can pin today to 14 Sep 2026.

**Cancelling:** `POST /api/bookings/{id}/cancel`, by the guest or the listing's host, up to and
including check-in day.

- It's a POST to an action, not a DELETE, because a cancelled booking stays on record as history
  (and, from Phase 5, perhaps a refund).
- Cancelling twice returns the same cancelled booking, not an error. A request that is safe to
  repeat is called **idempotent**, which matters when a network hiccup makes the client resend.
- Cancelling doesn't lock the listing, because nothing it does can break a rule. It frees the
  dates immediately: the constraint only counts PENDING and CONFIRMED.

---

## 11. The open item from Phase 2: 409, not 500

📄 `GlobalExceptionHandler.handleConcurrentUpdate`

Phase 2 left this open: two saves of one listing at the same moment made the loser's
optimistic-lock failure a **500**. Bookings make it more likely, since each one raises the
version. Now every `ConcurrencyFailureException` that reaches the API is a **409** with the key
`error.concurrentUpdate`: "Someone else changed this at the same moment. Reload it and try again."

A 500 says "the server is broken". A 409 says "your request was fine, but the data changed under
you". Nothing was saved, so trying again is safe. The hands-on guide stages this with psql.

---

## 12. How it's tested

| Test class | Kind | Proves |
|---|---|---|
| `BookingTest` | unit | total = price × nights, in the listing's currency, at the currency's decimals; check-out day not charged |
| `BookingRulesTest` | unit (fixed clock) | every date, guest, availability and cancellation rule; the messages render |
| `BookingServiceRetryTest` | unit (mocked attempt) | retry after a lost race; deadlock victims retried; recover after 4 losses; constraint mapped, not retried; other errors untouched; bad dates never reach the database |
| `BookingConcurrencyTest` | integration, real Postgres | **two threads, overlapping dates: exactly one wins** (5 runs); two threads, different dates: both win; **staged** version race → retry at the new price; **staged** constraint race → "just taken" |
| `BookingApiTest` | integration (MockMvc) | every endpoint, status code and error; cancelling frees dates; the cached listing's version follows bookings |
| `GlobalExceptionHandlerTest` | unit | booking rules render as 400 with a field; optimistic-lock failures are 409 |

**Total after Phase 3: 131 tests (80 unit, 51 integration), all passing.**

### Two ways to test a race

**1. Race it for real.** Two threads, released together:

```java
CyclicBarrier bothReady = new CyclicBarrier(2);    // the second to arrive releases both
Future<Outcome> a = executor.submit(() -> book(bothReady, first, firstGuest));
Future<Outcome> b = executor.submit(() -> book(bothReady, second, secondGuest));
```

The assertion must hold **whatever the interleaving**: exactly one success, and the loser gets
"just taken" or "already booked". `@RepeatedTest(5)` runs it five times per build. This proves the
outcome, but not *which path* produced it; that depends on luck.

**2. Stage it.** To test one exact path every time, take the luck out:

1. A second, raw JDBC connection starts a transaction and changes the listing's price, without
   committing. It now holds the listing's row lock.
2. The booking runs on another thread. It reads the last committed listing (MVCC readers never
   wait), inserts its row, and then **blocks** at commit, trying to raise the version.
3. The test polls `pg_stat_activity` (Postgres's live view of every session) with Awaitility until
   some query shows `wait_event_type = 'Lock'`. Now it *knows* the booking is stuck at exactly
   that step.
4. The test commits the price change. The booking's `UPDATE … WHERE version = 0` finds version
   1, loses, rolls back, and is retried. The retry reads the new price: 3 × ₹3,000 = ₹9,000.00.

Polling for a condition, instead of `Thread.sleep(500)` and hoping, is what keeps concurrency
tests from being **flaky** (passing or failing at random).

**Other techniques:** `ExecutorService` in try-with-resources, declared *before* the connection
so the connection closes (and frees its lock) first if the test fails. A mocked `BookingAttempt`
that throws on cue, to drive the retry logic in milliseconds.

---

## Interview questions — practise answering these aloud

**Q: How do you prevent double bookings?**
In layers. Cheap rules first. Each booking attempt is one transaction that reads the listing with
`OPTIMISTIC_FORCE_INCREMENT`, so bookings of one listing race on its version number and the loser
rolls back. The loser is retried with backoff, and gets a clear 409 if it keeps losing. Underneath
all that, a Postgres exclusion constraint refuses overlapping date ranges outright, whatever the
code does.

**Q: If the database constraint already prevents overlaps, why the version bump?**
Three reasons. The constraint can only express "no overlap". The version race also makes a booking
retry when the listing's *price* changed mid-booking, so it's never charged from stale data. It
serialises bookings of one listing, which future rules the constraint can't express (a minimum
gap between stays, a cleaning day) would rely on. And it's defence in depth: on a database without
exclusion constraints, the version race alone would stop double bookings.

**Q: What does `OPTIMISTIC_FORCE_INCREMENT` do? Why not plain `OPTIMISTIC`?**
It makes Hibernate raise the entity's version at commit even though nothing changed:
`UPDATE … SET version = v+1 WHERE id = ? AND version = v`. Plain `OPTIMISTIC` only *reads* the
version at commit. Two bookings that each read version 5 would both see 5 and both commit, because
neither changes it.

**Q: Optimistic or pessimistic locking, and why?**
Optimistic, because conflicts are rare: two bookings of one listing within milliseconds. It costs
nothing until a conflict. Pessimistic (`SELECT … FOR UPDATE`) would make every booking wait on a
row lock for its whole transaction. I'd choose pessimistic if conflicts were frequent or a retry
were expensive, for example a ticket drop with thousands of buyers per second.

**Q: Why must the retry be outside the transaction?**
Two reasons. The version is raised when the transaction commits, which is after the method has
returned, inside the proxy; only code outside the proxy sees that failure. And a failed transaction
is rollback-only, so each retry needs a fresh transaction and a fresh session, which re-reads the
listing.

**Q: Why is the transactional code in a separate bean?**
Spring's `@Transactional` works through a proxy, and a call from inside the same object (`this.x()`)
bypasses its own proxy, so it gets no transaction. That's the self-invocation pitfall. Calling a
different bean goes through its proxy.

**Q: What do you retry and what don't you?**
Only `ConcurrencyFailureException`: a lost version race or a deadlock victim. Both failed purely
because of timing, and a retry can succeed. "Already booked" or a broken rule would fail
identically, so retrying would only add load.

**Q: Why exponential backoff with jitter?**
Backoff means later retries wait longer, so a busy listing isn't hammered. Jitter adds randomness
so two losers don't retry in lockstep and collide again.

**Q: Tell me about a bug you found.**
The race test failed on its second run with `deadlock detected`. An exclusion constraint adds its
index entry first and then looks for conflicts. So two overlapping inserts at the same instant can
each find the other's uncommitted entry and wait for each other, and Postgres cancels one. That
transaction only lost a race, so I retry deadlock victims too, via Spring's
`ConcurrencyFailureException`, and the retry gives the correct answer. It's pinned down by a unit
test, and the race test has passed repeatedly since.

**Q: What isolation level do you use? Why not SERIALIZABLE?**
Postgres's default, READ COMMITTED. SERIALIZABLE would also prevent the anomaly, but it aborts
losers across every query in the transaction, which would still need retries. The one rule that
matters is expressed exactly, and cheaply, by the constraint and the version race.

**Q: How do you test concurrency without flaky tests?**
Two kinds. Real races (threads released by a `CyclicBarrier`), with assertions that hold under any
interleaving, repeated. And staged tests: a second connection holds a real lock, the test polls
`pg_stat_activity` until the booking is visibly blocked, then releases the lock. That forces one
exact path every run. Polling a condition instead of sleeping is what keeps them deterministic.

**Q: Why keep the "already booked" check if it isn't safe on its own?**
It gives the common answer (someone booked earlier) cheaply and with a precise message. The
guarantee comes from the layers after it.

**Q: What happens when the retries run out?**
The recover step turns the last failure into a 409, "Those dates were just taken by another guest",
and logs `booking.retry.exhausted` with the cause. It's never a 500.

**Q: Why `POST /bookings/{id}/cancel` instead of `DELETE`?**
Cancelling changes a booking's state; the booking stays on record, and later may carry a refund.
It's also idempotent: cancelling twice returns the same result.

**Q: 400, 403, 404, 409: how do you choose?**
400: the request itself is wrong (check-out before check-in). 403: *who* is asking is the problem
(booking your own listing). 404: the thing doesn't exist. 409: a valid request that the data's
current state forbids (dates taken, listing inactive, a concurrent change).

**Q: What if a host saves an edit just as a guest books?**
Both raise the listing's version, so one of them loses. If the booking loses, it's retried and
charged the new price. If the host loses, the save gets a 409 "someone else changed this at the
same moment", and nothing is saved, so reloading and saving again is safe.

**Q: How would this scale to a huge event with thousands of seats?**
One row per event would become a hot spot, because every purchase would race on its version.
I'd make the contended unit smaller: lock per seat or per date. Or put a queue in front of
purchases and process them in order.

---

## Honest limitations

- **Deleting a listing in the same instant it's booked.** If the host's delete commits first, the
  guest's insert fails its foreign key, which becomes a 500. (If the booking commits first, the
  host's delete gets a clean 409.) The window is milliseconds; the fix would be to map that
  foreign-key violation to 404.
- **"Today" is the server's date**, not the listing's local date. Around midnight a guest far from
  the server's time zone could be a day off. The proper fix is a time zone per listing.
- **Bookings are confirmed immediately**, because there are no payments yet. Phase 5 adds PENDING
  (awaiting payment).
- **Nothing marks stays COMPLETED yet.** A scheduled job could (Phase 4 adds scheduling).
- **Contention adds latency.** A booking that loses repeatedly waits up to about 350 ms before the
  recover step answers.
- **`X-Demo-User-Id` can be spoofed**: there is no authentication, by design.

---

## Try it yourself

1. **Remove the ticket.** Delete the `@Lock(...)` line on `findForBookingById` and run
   `.\mvnw.cmd test "-Dtest=BookingConcurrencyTest"`. The staged price test fails: the booking
   never blocks, and would be charged the old price. The overlap races still pass, because the
   constraint still holds. That's defence in depth. Put the line back.
2. **Put the retry inside the transaction.** Add `@Transactional` to `BookingService.book()` and run
   the same test. `place()` now joins the outer transaction, the commit happens after the retry
   loop has finished, and the lost race escapes as a generic 409. Remove it.
3. **Retry only optimistic failures.** In `RetryConfig`, change `ConcurrencyFailureException` back
   to `OptimisticLockingFailureException` and run the concurrency test a few times. Sooner or later
   a deadlock slips through. Change it back.
4. **Watch a transaction wait for another**, by hand, in the hands-on guide (Parts 24–26).
5. **Change a rule.** Set `max-nights: 7` in `application.yml`, restart, and book 10 nights.

---

## YouTube for this phase (in order)

1. `database transactions acid explained`
2. `transaction isolation levels explained` — read committed vs repeatable read vs serializable
3. `race condition explained web application`
4. `optimistic vs pessimistic locking`
5. `jpa hibernate optimistic locking version` — `@Version`, `OptimisticLockException`
6. `jpa lock modes optimistic force increment`
7. `spring transactional annotation explained` and `spring transactional self invocation`
8. `retry with exponential backoff and jitter`
9. `postgresql deadlock explained`
10. `postgresql row level locking select for update`
11. `postgresql exclusion constraint` (revisit from Phase 1)
12. `java concurrency executorservice cyclicbarrier`
13. `idempotency in rest apis`

Clickable versions are in the [project log](project-log.md#6-youtube-study-plan--every-phase).
