# RoomKeeper: hotel booking backend

Java 25, Spring Boot 4, Postgres, Redis. Design: docs/Hotel Booking Backend Project Design.md. Progress: docs/roadmap.md.

## Workflow
- TDD: write the failing test first, commit it, then the code that passes it.
- Commit messages: `test: ...` for failing tests, `feat:` / `fix:` / `refactor:` / `build:` / `ci:` / `docs:` for the rest. Descriptive body when the why isn't obvious.
- Run `./mvnw verify` before saying a task is done. It needs Docker running (Testcontainers).
- Tick the task in docs/roadmap.md in the same commit as the work.

## Layout
- Package by feature under `com.roomkeeper`: auth, hotel, inventory, search, pricing, booking, payment, outbox, common.
- Migrations in src/main/resources/db/migration (Flyway). Never edit an applied migration.
- Unit tests are `*Test` (Surefire). Tests that need containers or the full app are `*IT` (Failsafe).

## Rules
- Postgres is the source of truth for inventory and bookings. Redis is for speed and protection only.
- Inject `Clock`; never call `now()` directly.
- Integration tests use Testcontainers. No H2.
- No `@Transactional` on concurrency tests.
