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
| [03 — Bookings and concurrency](03-bookings.md) | The double-booking race, ACID and isolation levels, optimistic locking and `OPTIMISTIC_FORCE_INCREMENT`, retry with backoff and jitter, recover, the self-invocation trap, the exclusion constraint under concurrency, the deadlock we found, testing races deterministically |
| [04 — Auditing, logging and scheduling](04-auditing.md) | Audit trails with Hibernate Envers, revisions and `_aud` tables, recording who made a change, the listing history view, what the audit trail can't see, structured logging with key/value pairs and the MDC, request ids and log injection, JSON logs, `@Scheduled` cron jobs, the reviews rules |
| [05 — Payments, money and currencies](05-payments.md) | BigDecimal vs double and minor units, Stripe PaymentIntents, why a payment can't be inside a transaction, the payment saga and its compensating action, idempotency keys, lost answers and the reconciliation job, refunds, the payment simulator, live exchange rates and how they're cached, `maxPrice` across currencies |
| [06 — AI: embeddings, RAG and search that understands](06-ai-rag.md) | What an embedding is, cosine similarity, pgvector and HNSW, keeping an index in step with the data, content hashes and quota, hybrid search (meaning + real SQL filters), a preference profile built from SQL, the taste vector as `avg(embedding)`, why the question is parsed with rules and statistics answered by SQL, the prompt and the grounding check that overrules a hallucination, degrading with no key, and testing AI without one |
| [07 — Photos on S3, three languages, GraphQL, OpenAPI and Postman](07-extras.md) | Object storage and why the bucket stays private, checking a file by its magic bytes, the upload saga and its compensating delete, i18n with a locale resolver and why it must be lazy, translating Spring's own errors, tests that keep translations real, GraphQL vs REST, the N+1 problem and `@BatchMapping`, money as a string scalar, OpenAPI and Swagger UI, Postman collections, and tests that keep documentation from rotting |

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
| **Minor units** | An amount in the currency's smallest unit, which is how payment providers take it: ₹75.00 is 7500 paise. |
| **Test mode** | Stripe's sandbox: the same API with fake cards, where no money moves. |
| **PaymentMethod** | A saved way to pay, such as a card, as an id (`pm_card_visa`), so card numbers never reach our server. |
| **PaymentIntent** | Stripe's record of one attempt to collect one amount: created, confirmed with a payment method, then succeeded, declined, waiting for the bank, or cancelled. |
| **Saga** | A sequence of steps, each committed on its own, where a failure part-way is repaired by a compensating action instead of a rollback. |
| **Compensating action** | A step that undoes the effect of an earlier one: cancelling the booking when its payment fails. |
| **Idempotency key** | A label on a request that makes a repeat of it return the first answer instead of acting twice. |
| **Reconciliation** | Comparing your records with another system's and fixing the differences: here, asking the payment provider what happened to payments whose outcome was lost. |
| **402 / 202 / 503** | Payment required (a declined card) / accepted but not finished (the payment's outcome isn't known yet) / temporarily unavailable (try again later). |
| **Exchange rate (FX)** | How much of one currency one unit of another buys. FX is short for foreign exchange. |
| **Stale-if-error** | Keeping on using slightly old cached data when a fresh copy can't be had, rather than failing. |
| **Embedding** | A list of numbers (768 here) standing for the meaning of a piece of text. Similar meanings get similar lists. |
| **Vector** | That list of numbers, treated as a direction, so two meanings can be compared by the angle between them. |
| **Cosine similarity** | How close two directions are: 1 the same, 0 unrelated. pgvector's `<=>` gives its opposite, the distance. |
| **Vector store** | A table that can answer "which rows are closest to this vector?" quickly. Here: `vector_store`, in Postgres. |
| **pgvector** | The Postgres extension that adds the `vector` column type and the operators and indexes that search it. |
| **HNSW** | An index that finds *almost* the nearest vectors by walking a graph of neighbours, instead of comparing every row. |
| **Semantic search** | Searching by meaning rather than by matching words. |
| **RAG** | Retrieval-Augmented Generation: find the facts yourself, then let the model put *those* facts into sentences. |
| **Grounding** | Making sure everything the model says comes from the facts you gave it — and checking afterwards that it did. |
| **Hallucination** | The model stating something plausible and false, such as a listing that does not exist. |
| **Prompt** | The text sent to the model: the instructions, the question, and the facts it may use. |
| **Token / quota** | Models bill by word fragments, and free tiers cap requests per minute: the reason this phase avoids calling one. |
| **Object storage (S3)** | A store for files ("objects") under keys, in buckets. No real folders: `listings/1/abc.jpg` is one key. |
| **Bucket** | A named container of objects in S3. Private unless deliberately opened up. |
| **MinIO** | A free server that speaks the S3 API, runnable in Docker: S3 on your own machine. |
| **Magic bytes / file signature** | The fixed first bytes every file format starts with (JPEG: `FF D8 FF`), which say what a file really is. |
| **Multipart upload** | An HTTP request carrying files as separate "parts" (`multipart/form-data`), which is what an HTML file input sends. |
| **i18n / l10n** | Internationalisation (making text swappable per language) / localisation (the actual translating). |
| **Locale** | A language, sometimes with a region (`hi-IN`), which decides the messages and how numbers are written. |
| **Accept-Language** | The header in which a browser lists the languages its user reads, most preferred first. |
| **GraphQL** | An API style with one endpoint and a schema, where the client asks for exactly the fields it wants. |
| **Schema (GraphQL)** | The typed description of everything a GraphQL API can return and accept. |
| **Query / mutation** | A GraphQL read / a GraphQL change. |
| **N+1 problem** | Loading a list and then one more query per item for something related: 1 + N queries instead of 2. |
| **OpenAPI / Swagger UI** | A standard JSON description of a REST API / the web page that shows it and lets you try each call. |
| **Postman collection** | A saved, shareable set of API requests; an *environment* holds the variables they use, such as the base URL. |
