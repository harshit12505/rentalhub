# Learning RentalHub

These docs teach you the project so you can explain and defend every part of it in an
interview. One doc per phase, written as each phase is built.

| Doc | Covers |
|---|---|
| [📒 Project log & study plan](project-log.md) | **Start here.** Everything done so far, every decision and problem, your to-do list, and the full YouTube study plan with tick-boxes |
| [00 — Stack choices](00-stack-choices.md) | Why each technology, what the alternatives were, and what to say when asked |
| [01 — Foundation](01-foundation.md) | Maven, Spring Boot, JPA/Hibernate, Flyway, the schema, the double-booking constraint, BigDecimal, the Factory + Template Method patterns, i18n keys, testing |

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
