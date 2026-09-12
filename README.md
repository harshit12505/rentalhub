# RentalHub

Property listing, booking and host-management platform — an Airbnb-style portfolio project.

Java 21 · Spring Boot 4.1 · PostgreSQL 16 + pgvector · Flyway · Redis + Caffeine · Thymeleaf ·
Spring AI (Gemini)

One deployable Spring Boot application. No separate frontend build, no npm, no second service.

> **Learning the project?** Start with [`docs/learning/`](docs/learning/README.md): one teaching
> doc per phase, covering why each technology was chosen, how each technique works, likely
> interview questions, and what to watch on YouTube.

---

## Status

| Phase | What | Status |
|---|---|---|
| 1 | Foundation: build, schema, domain model, Factory pattern, tests | ✅ |
| 2–9 | Caching, bookings, auditing, payments, AI/RAG, extras, frontend, deploy | not started |

There are no HTTP endpoints yet, so the running app serves only `/actuator/health`.
That is expected at this stage, as is the startup warning
`Cannot find template location: classpath:/templates/` (pages arrive in phase 8).

---

## Running locally (Windows / PowerShell)

**Prerequisites:** JDK 21, Docker Desktop (running). Maven is optional — the wrapper
(`mvnw.cmd`) downloads the right version itself.

1. Start Postgres and Redis:

   ```powershell
   docker compose up -d
   ```

2. Confirm both show `healthy`:

   ```powershell
   docker compose ps
   ```

3. Run the tests. The database tests start their own throwaway Postgres in Docker, so
   Docker Desktop must be running:

   ```powershell
   .\mvnw.cmd test
   ```

4. Start the app. Flyway creates every table on first boot:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

5. Check it is alive — expect `{"status":"UP"}`:

   ```powershell
   curl.exe http://localhost:8080/actuator/health
   ```

6. Look at what Flyway built:

   ```powershell
   docker exec -it rentalhub-postgres psql -U rentalhub -d rentalhub -c "\dt"
   ```

To wipe the local database and start over:

```powershell
docker compose down -v
docker compose up -d
```

### Common problems

| Symptom | Cause | Fix |
|---|---|---|
| `Web server failed to start. Port 8080 was already in use` | Another program owns 8080 — on Windows often Oracle Database's listener (`TNSLSNR`) | Find it: `Get-NetTCPConnection -LocalPort 8080 -State Listen`. Then run on another port: `$env:PORT = "8081"` before `.\mvnw.cmd spring-boot:run`, and use `http://localhost:8081` |
| Tests fail with `Could not find a valid Docker environment` | Docker Desktop isn't running | Start Docker Desktop, wait for "Engine running", re-run |
| App fails with `Validate failed: Migration checksum mismatch for migration version 1` | Your local DB was created by an earlier draft of V1 | `docker compose down -v`, then `docker compose up -d` |
| `Connection refused` to `localhost:5432` | Containers aren't up | `docker compose up -d`, then `docker compose ps` |
| A `-Dsomething=value` flag is ignored or errors | PowerShell splits unquoted `-D` args at the dot | Quote it: `.\mvnw.cmd test "-Dtest=PropertyFactoryTest"` |

---

## Adding a new property type

1. Add a value to `PropertyType`.
2. Add an entity class extending `Property` with `@DiscriminatorValue` and `typeAttributes()`.
3. Add a `@Component` creator extending `AbstractPropertyCreator`, declaring its
   `AttributeSpec`s and rules.
4. Add a Flyway migration for the new columns.
5. Add translated labels (`property.type.X`, `property.attribute.*`) to the messages files.

No controller, service, form, view or existing creator changes. `PropertyFactoryTest`
fails if the entity, creator and labels disagree; the app refuses to start if a type has
no creator.
