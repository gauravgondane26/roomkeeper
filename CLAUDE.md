# RoomKeeper: hotel booking backend

Java 25, Spring Boot 4, Postgres, Redis. Design: docs/Hotel Booking Backend Project Design.md. Progress: docs/roadmap.md.

## Workflow
- TDD: write the test first and watch it fail before writing the code.
- One commit per finished task: its tests, code, config, and roadmap tick together. No single-file commits; fold small fixes into the task's commit.
- Exception: the design doc's signature tests get their failing test committed on its own (`test: ...`) before the code that passes it.
- Commit types: `feat:` / `fix:` / `refactor:` / `build:` / `ci:` / `docs:` / `test:`. Descriptive subject, and a body listing what's included.
- Run `./mvnw verify` before saying a task is done. It needs Docker running (Testcontainers).
- Tick the task in docs/roadmap.md in the same commit as the work.

## Layout
- Package by feature under `com.roomkeeper`: auth, user, hotel, inventory, search, pricing, booking, payment, outbox, common.
- Migrations in src/main/resources/db/migration (Flyway). Never edit an applied migration.
- Unit tests are `*Test` (Surefire). Tests that need containers or the full app are `*IT` (Failsafe).

## Rules
- Postgres is the source of truth for inventory and bookings. Redis is for speed and protection only.
- Inject `Clock`; never call `now()` directly.
- Integration tests use Testcontainers. No H2.
- No `@Transactional` on concurrency tests.
