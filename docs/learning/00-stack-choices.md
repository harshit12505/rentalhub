# 00 — Stack choices

"Why did you use X?" is the most common interview question about a portfolio project.
A good answer has three parts: **what problem it solves here**, **what you compared it
with**, and **what it costs you**. Every entry below is in that shape.

---

## Java 21
- **Why:** the current long-term-support (LTS) release most companies run. Gives us
  records (compact immutable data classes, e.g. `AttributeSpec`), pattern matching
  (`if (cause instanceof SQLException sql)`), text blocks (multi-line SQL in tests) and
  virtual threads.
- **Alternatives:** Kotlin (nicer syntax, smaller hiring pool for juniors); Java 25 (newer
  LTS, but libraries and hosting images catch up later).
- **Cost:** more verbose than Kotlin. Lombok trims some of that.

## Spring Boot 4.1
- **Why:** the dominant Java framework for web backends. It gives dependency injection,
  web MVC, data access, validation and configuration, all wired together by
  *auto-configuration*: add a library, and Boot sets it up with sensible defaults.
- **Why 4 and not 3.5:** the original plan said Boot 3.5, but Spring AI 2.0 only runs on
  Boot 4, and Boot 3.5 stopped receiving free security patches on 30 June 2026. Starting
  a new project on an end-of-life framework is hard to defend.
- **Cost:** Boot 4 is newer, so fewer tutorials exist. Most Boot 3 material still applies;
  the differences are mainly renamed starters (`starter-webmvc`), Jackson 3, and retry
  support moving from Spring Retry into Spring Framework itself.
- **Say in an interview:** "I picked Boot 4 because Spring AI 2 requires it, and 3.5 was
  already end-of-life. The migration cost was mostly renamed dependencies."

## Maven (with the wrapper)
- **Why:** the most common Java build tool; its XML is verbose but predictable, and every
  IDE and CI system understands it. The **wrapper** (`mvnw.cmd`) pins the exact Maven
  version and downloads it on first use, so anyone can build without installing Maven.
- **Alternative:** Gradle — faster and more flexible, but its flexibility makes builds
  harder for beginners to read.

## Thymeleaf + Bootstrap from a CDN
- **Why:** server-rendered HTML keeps the whole app in one deployable unit with no
  JavaScript build step — a hard requirement of this project. Bootstrap from a CDN gives
  a decent-looking responsive UI with zero tooling.
- **Alternative:** a React/Angular single-page app. More interactive, but it means a second
  project, npm, a build pipeline and CORS configuration.
- **Cost:** less interactivity. Fine for forms and listings.
- **Say:** "The project's focus is backend depth. Server-side rendering kept it one
  container and let me spend the effort on concurrency, caching and the AI feature."

## PostgreSQL 16
- **Why:** it has three features this project depends on that MySQL lacks or does worse:
  1. **Exclusion constraints + range types** — the database itself refuses overlapping
     bookings (see 01-foundation).
  2. **pgvector** — stores AI embeddings and runs similarity search in the same database.
  3. Strong `CHECK` constraints, expression indexes (`lower(city)`), and `NUMERIC`.
- **Alternative:** MySQL — also a good relational database, but it has no exclusion
  constraints, so overlap protection would have to live only in application code.

## pgvector (instead of a separate vector database)
- **Why:** the AI feature needs to find listings "similar in meaning" to a query. pgvector
  adds a `vector` column type and similarity operators to Postgres. Keeping vectors next
  to the listings means one database, one backup, and hybrid queries (vector similarity
  AND `price <= 5000`) in a single SQL statement.
- **Alternative:** Pinecone, Weaviate, Qdrant — purpose-built and faster at huge scale,
  but a second service to run, pay for and keep in sync.

## Flyway
- **Why:** the schema lives in versioned SQL files (`V1__initial_schema.sql`, `V2__...`).
  Every environment — your laptop, the test container, production — runs the same scripts
  in the same order, so they can't drift. Hibernate is set to `validate` only.
- **Alternatives:** Liquibase (same idea, XML/YAML changelogs); Hibernate `ddl-auto=update`
  (lets the ORM alter tables itself — convenient, but it can't rename columns, never
  drops anything, and you can't review what it will do to production).

## Caffeine + Redis (phase 2)
- **Why two caches:** Caffeine lives inside the Java process — nanosecond reads, but each
  server has its own copy. Redis is a separate in-memory store shared by all servers —
  about a millisecond per read, but consistent. Hot single-listing reads go to Caffeine;
  search result pages go to Redis.

## Spring AI + Google Gemini (phase 6)
- **Why Spring AI:** a Spring-native way to call LLMs and vector stores, so the AI feature
  lives in the same Java app with the same config, testing and deployment.
- **Why Gemini:** a free tier with a plain API key from Google AI Studio, no credit card.
- **Why Java and not Python for RAG:** Python has more tutorials, but a Python service
  would mean two deployables, two languages and a network hop. "I built RAG in Java with
  Spring AI inside a single deployable" is also a rarer, more interesting story. The RAG
  concepts are the same in any language.

## Hibernate Envers (phase 4)
- **Why:** automatic audit history — every change to a listing or booking is recorded in
  `_AUD` tables with who/when, without writing history code by hand.

## Stripe test mode (phase 5)
- **Why:** the industry-standard payments API, with a free test mode and fake card numbers.
  PaymentIntents model the real-world flow (authorise, confirm, fail) properly.

## AWS S3 (phase 7)
- **Why:** the standard for storing user-uploaded files. Keeping images out of the
  database keeps it small and fast.

## REST + Spring GraphQL (phase 7)
- **Why both:** REST is universal and easy to cache; GraphQL lets a client ask for exactly
  the fields it needs in one request. Building both on the same service layer shows the
  business logic isn't tied to one API style.

## springdoc-openapi, Lombok, Testcontainers, Docker
- **springdoc:** generates interactive API docs at `/swagger-ui.html` from the code.
- **Lombok:** generates getters/setters at compile time. Used carefully: never `@Data` on
  entities (see 01-foundation for why).
- **Testcontainers:** real Postgres in tests instead of a fake in-memory database, because
  H2 doesn't understand exclusion constraints, `daterange` or pgvector.
- **Docker:** runs Postgres and Redis locally with one command, and packages the app for
  Render.

## Render.com (phase 9)
- **Why:** deploys straight from a GitHub repo using a `render.yaml` blueprint, with
  managed Postgres (pgvector supported) and a managed Redis-compatible Key Value store.
  Simpler than AWS for a single-service app.
