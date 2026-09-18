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
- **What it actually looks like (built in phase 6):** three starters — chat, embedding (a
  separate artifact) and the pgvector store — and in the code two interfaces, `ChatModel`
  and `VectorStore`. Swapping provider means changing configuration and re-embedding
  everything, because embeddings from different models cannot be compared.
- **What Spring AI is *not* used for:** reading the question, deciding what kind of question
  it is, and answering anything with one right answer. Those are rules and SQL (see
  [06 — AI](06-ai-rag.md)), because they are cheaper, deterministic and testable.
- **The awkward part:** with no key, Spring AI's auto-configuration fails while the context
  is being built (the pgvector store takes the embedding model as a constructor argument), so
  an `EnvironmentPostProcessor` switches all three off. Worth knowing if you ever add an
  optional provider to a Spring Boot app.

## Hibernate Envers (phase 4)
- **Why:** automatic audit history — every change to a listing or booking is recorded in
  `_AUD` tables with who/when, without writing history code by hand.

## Stripe test mode (phase 5)
- **Why:** the industry-standard payments API, with a free test mode and fake card numbers.
  PaymentIntents model the real-world flow (authorise, confirm, fail) properly.
- **Library:** Stripe's official `stripe-java` (33.4.2). Spring Boot doesn't manage it, so its
  version is pinned in `pom.xml`. Each major version speaks one fixed Stripe API version.
- **Without a key:** a built-in simulator answers to Stripe's test ids, so the app works end
  to end with no account. That matters here, because
  [Stripe accounts in India are invite-only](https://support.stripe.com/questions/stripe-accounts-are-invite-only-in-india).
- **Say:** "Payments go through a small gateway interface with two implementations, Stripe
  and a simulator, so the booking saga is the same either way. The Stripe one is tested
  against a fake Stripe server."

## Exchange rates (phase 5)
- **Why ExchangeRate-API's open endpoint:** free, no key, and daily rates for about 160
  currencies, AED included. Frankfurter was the other candidate, but it publishes the
  European Central Bank's rates, and the ECB has no AED rate.
- **Cost:** rates change once a day, and the terms ask for a credit line and at most about
  one call an hour. The app keeps them for an hour, and uses them only for display and for
  comparing prices, never for what is charged.

## AWS S3 (phase 7)
- **Why:** the standard for storing user-uploaded files. Keeping images out of the
  database keeps it small and fast.
- **Library (built in phase 7):** the plain AWS SDK v2 (`software.amazon.awssdk:s3`, pinned
  through its BOM), not Spring Cloud AWS: one client, a few calls (put, get, delete), and no
  extra layer to keep compatible with Boot 4.
- **MinIO** stands in for S3 in the tests and on your machine: a free server that speaks the
  same protocol, so the real SDK is tested with no AWS account. MinIO stopped publishing on
  Docker Hub in 2025; the image is pinned from quay.io.

## REST + Spring GraphQL (phase 7)
- **Why both:** REST is universal and easy to cache; GraphQL lets a client ask for exactly
  the fields it needs in one request. Building both on the same service layer shows the
  business logic isn't tied to one API style.
- **Library:** Spring for GraphQL (Boot's `spring-boot-starter-graphql`), with graphql-java
  underneath: schema-first (`schema.graphqls`), annotated controllers, `@BatchMapping` for
  the N+1 problem. GraphiQL, a browser query editor, is switched on at `/graphiql`.

## springdoc-openapi, Lombok, Testcontainers, Docker
- **springdoc:** generates interactive API docs at `/swagger-ui.html` from the code.
  Version 3.x is the line built for Spring Boot 4 (3.1.1 here, pinned: Boot doesn't manage it).
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
