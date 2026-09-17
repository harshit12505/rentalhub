# Learning RentalHub

These docs teach you the project so you can explain and defend every part of it in an
interview. One doc per phase, written as each phase is built.

| Doc | Covers |
|---|---|
| [📒 Project log & study plan](project-log.md) | **Start here.** Everything done so far, every decision and problem, your to-do list, and the full YouTube study plan with tick-boxes |
| [🧪 Hands-on guide](hands-on-guide.md) | Test every feature yourself: exact PowerShell commands and the expected result for each |
| [00 — Stack choices](00-stack-choices.md) | Why each technology, what the alternatives were, and what to say when asked |
| [01 — Foundation](01-foundation.md) | Maven, Spring Boot, JPA/Hibernate, Flyway, the schema, the double-booking constraint, BigDecimal, the Factory + Template Method patterns, i18n keys, testing |
| [02 — Caching](02-caching.md) | Cache-aside, Caffeine + Redis tiers, W-TinyLFU, cache stampedes, search keys and partitions, after-commit invalidation, SCAN vs KEYS, deferred vs immediate removal, Redis outages, the REST API, N+1 |
| [04 — Auditing, logging and scheduling](04-auditing.md) | Audit trails with Hibernate Envers, revisions and `_aud` tables, recording who made a change, the listing history view, what the audit trail can't see, structured logging with key/value pairs and the MDC, request ids and log injection, JSON logs, `@Scheduled` cron jobs, the reviews rules |
| [03 — Bookings and concurrency](03-bookings.md) | The double-booking race, ACID and isolation levels, optimistic locking and `OPTIMISTIC_FORCE_INCREMENT`, retry with backoff and jitter, recover, the self-invocation trap, the exclusion constraint under concurrency, the deadlock we found, testing races deterministically |

## How to use them

1. **Read the phase doc before you look at the code.** Each doc links to the files it
   explains; open them side by side.
2. **Say the interview answers out loud.** If you can't explain something in two or three
   sentences without reading, go back to that section.
3. **Do the "try it yourself" exercises.** Breaking things on purpose is the fastest way
   to understand why they are built the way they are.
4. **Watch the YouTube topics when a section doesn't click.** The complete, phase-by-phase
   list with clickable searches is in the [project log](project-log.md#6-youtube-study-plan--every-phase).

## Glossary (grows with each phase)

| Term | Plain meaning |
|---|---|
| **Bean** | An object that Spring creates and manages for you. |
| **Dependency injection (DI)** | Instead of a class creating the objects it needs (`new EmailSender()`), it lists them in its constructor and Spring hands them in. Makes classes easy to test and swap. |
| **Auto-configuration** | Spring Boot sees what's on the classpath (e.g. the Postgres driver) and configures sensible beans automatically. |
| **ORM** | Object-Relational Mapping: code that translates between Java objects and database rows, so you rarely write SQL for simple reads and writes. Hibernate is the ORM here; JPA is the standard API it implements. |
| **Entity** | A Java class mapped to a database table (`@Entity`). |
| **Migration** | A versioned SQL script that changes the schema. Run once, in order, on every database. |
| **Constraint** | A rule the database itself enforces (NOT NULL, UNIQUE, CHECK, foreign key, exclusion). |
| **Index** | A lookup structure that lets the database find rows without scanning the whole table, like the index at the back of a book. |
| **Transaction** | A group of database changes that either all happen or none happen. |
| **Optimistic locking** | Don't lock anything; instead keep a version number, and refuse a write if the version changed since you read it. |
| **Specification** | A small, reusable piece of a query ("city is X"), combined with others at runtime so the SQL contains only the filters actually used. |
| **Integration test** | A test that runs real parts together (here: the app plus a real Postgres), as opposed to a unit test of one class in isolation. |
| **Testcontainers** | A library that starts real services (Postgres, Redis) in Docker for the duration of a test run. |
| **i18n** | "Internationalisation" (18 letters between i and n): making the app able to speak several languages. |
| **DTO** | Data Transfer Object: a plain class that carries data between layers or over the network, with no database behaviour attached. |
| **Cache** | A copy of an answer kept somewhere faster than its source. A **hit** means the cache had it; a **miss** means it didn't. |
| **TTL** | Time to live: how long a cache entry may exist before it deletes itself. |
| **Invalidation** | Removing a cached entry because the data behind it changed. |
| **Cache-aside** | The app checks the cache, reads the database on a miss and stores the result, and removes cached copies when data changes. |
| **Cache stampede** | Many requests missing the same cache entry at once and all hitting the database together. |
| **Redis** | An in-memory key–value server, used here as a shared cache. |
| **Pub/sub** | Publish/subscribe messaging: a message sent to a channel reaches everyone listening at that moment. |
| **Event listener** | Code that runs when something announces an event; `@TransactionalEventListener` can wait until the transaction commits. |
| **HTTP status codes** | 200 OK, 201 Created, 204 No Content, 400 bad request, 403 not allowed, 404 not found, 409 conflict with the current state. |
| **Race condition** | A bug where the result depends on the timing of two things running at once. |
| **Check-then-act** | Checking a condition and then acting on it, when the condition can change in between. The classic race (also called TOCTOU). |
| **ACID** | A transaction's promises: Atomic (all or nothing), Consistent (constraints hold), Isolated (no half-done work seen), Durable (committed means kept). |
| **Isolation level** | How much a transaction sees of others running at the same time. Postgres's default is READ COMMITTED: each statement sees what was committed before it started. |
| **MVCC** | Multi-version concurrency control: readers see a snapshot of committed data and never wait for writers. |
| **Pessimistic locking** | Lock the row while you work (`SELECT … FOR UPDATE`); anyone else who wants it waits. |
| **Force increment** | Raising an entity's version on commit even though it didn't change, so concurrent transactions on it collide. |
| **Deadlock** | Two transactions each waiting for the other, forever. The database detects it and cancels one. |
| **Retry with backoff** | Trying a failed operation again after a wait that grows each time. |
| **Jitter** | A random amount added to each retry wait, so two clients don't retry in lockstep. |
| **Recover** | What to do when the retries run out: here, a clear 409 instead of an error. |
| **Idempotent** | Safe to repeat: doing it twice has the same effect as doing it once. |
| **Proxy (Spring)** | An object Spring puts in front of your bean to add behaviour around its method calls: transactions, caching, retry. |
| **Self-invocation** | A bean calling its own method through `this`. The call skips the proxy, so `@Transactional` doesn't apply. |
| **Flaky test** | A test that passes or fails at random, usually because it depends on timing. |
| **Audit trail** | A permanent record of every change: what it was, when, and who made it. |
| **Envers** | Hibernate's auditing module: it copies each changed row into a history table automatically. |
| **Revision** | One audited transaction. Every history row points to the revision that produced it. |
| **Structured logging** | Logging an event as named fields (`bookingId=7`), not as a sentence, so tools can search and count by field. |
| **MDC** | Mapped Diagnostic Context: a per-thread map of values (such as the request id) that every log line on that thread includes. |
| **Request id** | A short id given to each request, returned in `X-Request-Id` and printed on every log line of that request. |
| **Log injection** | Sneaking a line break into logged input to forge a fake log line. The request-id filter refuses such ids. |
| **Cron expression** | A schedule written as fields (second, minute, hour, day, month, weekday): `0 15 3 * * *` is "03:15 every day". |
| **Idempotent job** | A job that is safe to run twice: the second run finds nothing left to do. |
