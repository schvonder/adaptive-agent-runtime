# T7 — Progress event log: PG as single source of truth (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit db41076); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T7: `ProgressService.publish(taskId, type, payload)` writes `progress_events`; `sinceSeq` query for replay (Last-Event-ID semantics). PG is authoritative — restart must not lose events (Kafka/SSE publishers built later on top, T21/T22).

Commit `db41076 feat(core): progress event log with sinceSeq replay` (2026-10-02):

```
 .../core/progress/ProgressEvent.java               |  12 ++
 .../core/progress/ProgressService.java             |  56 +++++++++
 .../core/progress/ProgressServiceTest.java         | 136 ++++++++++++++++++
 3 files changed, 204 insertions(+)
```

## Acceptance re-executed today (post-hoc)

Targeted signature run (transcript `/tmp/opencode/aar/08-progress.log`):

```
$ mvn -pl core test -Dtest=ProgressServiceTest
[INFO] Running io.adaptiveagents.core.progress.ProgressServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.528 s -- in io.adaptiveagents.core.progress.ProgressServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

ProgressServiceTest methods (surefire report): `orderedReplay`, `monotonicSeq`, `unknownTaskEmpty`, `payloadRoundtrip`.

Full core suite: `Tests run: 41, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS` (`/tmp/opencode/aar/03-core-test.log`). Durability-across-restart is additionally exercised today by the app-level ITs: `ResumeAfterKillE2eTest` 2/2 and `SpawnedAppKillRestartTest` 1/1 reuse the same PG container across a real process SIGKILL+relaunch (T3/T17 evidence).

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| Integration test: publish 3 events, `sinceSeq(2)` returns exactly events 3.. | `orderedReplay` green (targeted run above) |
| Monotonic seq per task | `monotonicSeq` green |
| QA happy: ordered replay | `orderedReplay` |
| QA failure: replay for unknown task returns empty (not throw) | `unknownTaskEmpty` green |
| PG authoritative (restart must not lose events) | Events stored in `progress_events` (V1 schema, T4); kill/relaunch ITs green on same container today — no event store outside PG |

## Not re-runnable post-hoc

Nothing — re-run green today.
