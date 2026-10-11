# T4 — Flyway schema V1: tasks + progress_events (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit 74b94e5); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T4: `core/src/main/resources/db/migration/V1__app.sql` with `tasks(task_id uuid PK, envelope jsonb, status text check-in-enum, topology, decision/result/error jsonb, budget_usd numeric, deadline_ms bigint, created_at, updated_at, version bigint)` and `progress_events(seq bigserial PK, task_id uuid FK, type, payload jsonb, created_at)` + index `(task_id, seq)`. Saver tables left saver-managed per SPIKE.md decision (runtime `createTables(true)`); plain `JdbcTemplate`, no ORM, no runtime auto-DDL for app tables.

Commit `74b94e5 feat(core): Flyway V1 schema (tasks, progress_events)` (2026-10-02):

```
 core/pom.xml                                       |  27 ++++++
 .../adaptiveagents/core/CorePersistenceConfig.java |   9 ++
 core/src/main/resources/db/migration/V1__app.sql   |  25 ++++++
 .../core/FlywayMigrationTest.java                  |  98 ++++++++++++++++++
 4 files changed, 159 insertions(+)
```

`V1__app.sql` and `FlywayMigrationTest` still exist at HEAD unchanged in shape (V2 added later by T19).

## Acceptance re-executed today (post-hoc)

`mvn -pl core test` at HEAD (transcript `/tmp/opencode/aar/03-core-test.log`):

```
$ mvn -pl core test
[INFO] Running io.adaptiveagents.core.FlywayMigrationTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.215 s -- in io.adaptiveagents.core.FlywayMigrationTest
...
[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

FlywayMigrationTest methods (surefire report): `migrationAppliesCleanlyAndCreatesSchema`, `checksumTamperValidationThrows`, `constraintsSanityCheck`.

Additional runtime proof from the app verify run (`/tmp/opencode/aar/06-app-verify.log`, spawned app on singleton PG17):

```
o.f.core.internal.command.DbValidate     : Successfully validated 2 migrations (execution time 00:00.013s)
o.f.core.internal.command.DbMigrate      : Successfully applied 2 migrations to schema "public", now at version v2 (execution time 00:00.023s)
```

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| Migration applies on singleton PG17 (test asserts `flyway_schema_history` + tables exist) | `migrationAppliesCleanlyAndCreatesSchema` green; app verify log: `Successfully applied 2 migrations … now at version v2` on Postgres 17 testcontainer |
| `mvn -pl core test` green | `Tests run: 41, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS`, exit=0 |
| QA happy: clean apply | `migrationAppliesCleanlyAndCreatesSchema` |
| QA failure: checksum tamper → Flyway validation error surfaced in test (assert throws) | `checksumTamperValidationThrows` green |
| Saver tables per SPIKE decision | SPIKE.md `## Postgres Saver DDL Mechanism`: runtime `createTables(true)` → left saver-managed (no V2 saver vendoring; the later V2 is T19's `task_cost_entries`, unrelated) |

## Not re-runnable post-hoc

Nothing — schema acceptance is fully covered by today's re-run.
