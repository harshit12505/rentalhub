# Learning RentalHub

These docs teach you the project so you can explain and defend every part of it in an
interview. One doc per phase, written as each phase is built.

| Doc | Covers |
|---|---|
| [00 — Stack choices](00-stack-choices.md) | Why each technology, what the alternatives were, and what to say when asked |
| [01 — Foundation](01-foundation.md) | Maven, Spring Boot, JPA/Hibernate, Flyway, the schema, the double-booking constraint, BigDecimal, the Factory + Template Method patterns, i18n keys, testing |

## How to use them

1. **Read the phase doc before you look at the code.** Each doc links to the files it
   explains; open them side by side.
2. **Say the interview answers out loud.** If you can't explain something in two or three
   sentences without reading, go back to that section.
3. **Do the "try it yourself" exercises.** Breaking things on purpose is the fastest way
   to understand why they are built the way they are.
4. **Watch the YouTube topics when a section doesn't click.** Each doc ends with search
   terms, in the order that builds understanding.

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
| **Integration test** | A test that runs real parts together (here: the app plus a real Postgres), as opposed to a unit test of one class in isolation. |
| **Testcontainers** | A library that starts real services (Postgres, Redis) in Docker for the duration of a test run. |
| **i18n** | "Internationalisation" (18 letters between i and n): making the app able to speak several languages. |

## YouTube study plan (whole project)

Search these on YouTube. Channels that cover them well include **Amigoscode**, **Java
Brains**, **Dan Vega**, **Telusko**, **Marco Codes**, the official **SpringDeveloper**
channel, **Hussein Nasser** (databases), and **ByteByteGo** (system-design concepts).
Prefer videos from the last two years for anything Spring-specific; Spring Boot 3 videos
are fine for concepts, since Boot 4 changed mostly package and artifact names.

**Before / during Phase 1**
1. `Spring Boot tutorial for beginners` — the big picture: beans, DI, auto-configuration
2. `Spring dependency injection explained` — constructor injection specifically
3. `JPA Hibernate entity mapping tutorial` — entities, IDs, relationships, lazy loading
4. `Hibernate inheritance single table vs joined`
5. `Flyway database migrations Spring Boot`
6. `PostgreSQL indexes explained` (Hussein Nasser has good ones)
7. `PostgreSQL exclusion constraints` and `PostgreSQL range types`
8. `BigDecimal vs double Java money`
9. `Factory design pattern Java` and `Template method pattern Java`
10. `Testcontainers Spring Boot tutorial`

**Later phases** (topics listed here so you can get ahead)
- Phase 2: `Spring Boot caching Redis`, `Caffeine cache Spring Boot`, `cache invalidation strategies`
- Phase 3: `database transactions ACID explained`, `optimistic vs pessimistic locking`, `race conditions in web applications`
- Phase 4: `Hibernate Envers auditing`, `Spring Boot scheduled tasks cron`, `structured logging`
- Phase 5: `Stripe PaymentIntents tutorial`, `idempotency keys payments`
- Phase 6: `what are embeddings`, `vector databases explained`, `RAG retrieval augmented generation explained`, `pgvector tutorial`, `Spring AI tutorial`
- Phase 7: `GraphQL vs REST`, `Spring for GraphQL`, `N+1 query problem`, `AWS S3 presigned upload`
- Phase 8: `Thymeleaf Spring Boot forms validation`
- Phase 9: `Dockerfile for Spring Boot multi-stage`, `deploy Spring Boot to Render`
