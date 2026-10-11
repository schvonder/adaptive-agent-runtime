# T6 — TaskStore: repository + status state machine (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit cc460bc); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T6: states `SUBMITTED→ROUTED→RUNNING→SUCCEEDED|FAILED|BUDGET_EXCEEDED|DEADLINE_EXCEEDED`; `JdbcTemplate` repository with optimistic transition guard (`version` column); duplicate `task_id` insert CAUGHT and resolved to existing state (idempotent 200-with-existing semantics for todos 18/21); no 409 path; terminal→any transition rejected.

Commit `cc460bc feat(core): TaskStore state machine + repository` (2026-10-02):

```
 .../io/adaptiveagents/core/model/TaskStatus.java   |  27 ++++
 .../core/store/IllegalTaskStateException.java      |   7 +
 .../io/adaptiveagents/core/store/TaskRecord.java   |  24 ++++++
 .../io/adaptiveagents/core/store/TaskStore.java    |  90 ++++++++++
 .../core/store/TaskStoreTest.java                  | 159 ++++++++++++++++++
 5 files changed, 307 insertions(+)
```

At HEAD `TaskStore` also carries T16/T18 additions (`SubmitResult`, advisory-lock submit path — commits 4e059d3, c7d1f1d); the state machine contract is unchanged.

## Acceptance re-executed today (post-hoc)

Targeted signature run (transcript `/tmp/opencode/aar/07-taskstore.log`):

```
$ mvn -pl core test -Dtest=TaskStoreTest
[INFO] Running io.adaptiveagents.core.store.TaskStoreTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.714 s -- in io.adaptiveagents.core.store.TaskStoreTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

TaskStoreTest methods (surefire report): `fullLifecycle`, `illegalTransitionRejected`, `duplicateInsertResolvesToExisting`, `concurrentDuplicateSubmissionsYieldExactlyOneCreation`, `optimisticConcurrency`, `unknownTask`.

Full core suite (transcript `/tmp/opencode/aar/03-core-test.log`): `Tests run: 41, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS` (includes TaskStoreTest on the PG17 singleton).

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| `mvn -pl core test` green incl. duplicate-insert and illegal-transition tests | Targeted: `TaskStoreTest` 6/6 `BUILD SUCCESS`; full suite 41/0/0 |
| QA happy: full lifecycle SUBMITTED→SUCCEEDED | `fullLifecycle` |
| QA failure: duplicate taskId insert throws (→ resolved to existing, no 409) | `duplicateInsertResolvesToExisting` |
| QA failure: SUCCEEDED→RUNNING rejected | `illegalTransitionRejected` (terminal→any rejected) |
| Optimistic transition guard (`version`) | `optimisticConcurrency` |
| Concurrent duplicate submits → exactly one creation | `concurrentDuplicateSubmissionsYieldExactlyOneCreation` (also feeds T18) |

## Not re-runnable post-hoc

Nothing — PG17-backed integration tests re-run green today.
