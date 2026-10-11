# T18 — Idempotency across ingresses (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit c7d1f1d); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T18: duplicate `taskId` (same payload) → 200/first result, no second execution; guard = tasks PK + status check under advisory lock `pg_advisory_xact_lock(hashtext(task_id))` in `TaskStore`/`RoutingService`; graph never executed before dedup check commits; Kafka at-least-once redelivery covered.

Commit `c7d1f1d feat(core): taskId idempotency with advisory lock` (2026-10-09):

```
 .../adaptiveagents/app/ResumeAfterKillE2eTest.java |   5 +-
 .../io/adaptiveagents/core/store/SubmitResult.java |  10 ++
 .../io/adaptiveagents/core/store/TaskStore.java    | 101 ++++++++++++-----
 .../core/progress/ProgressServiceTest.java         |   5 +-
 .../core/store/TaskStoreTest.java                  |  70 +++++++++++--
 .../topologies/routing/RoutingService.java         |  40 +++++++-
 .../runner/ExecutionRunnerIntegrationTest.java     |  88 ++++++++++++++++++
 7 files changed, 282 insertions(+), 37 deletions(-)
```

## Acceptance re-executed today (post-hoc)

Targeted signature runs at HEAD:

```
$ mvn -pl core test -Dtest=TaskStoreTest                          (/tmp/opencode/aar/07-taskstore.log)
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.714 s -- in io.adaptiveagents.core.store.TaskStoreTest
[INFO] BUILD SUCCESS  exit=0

$ mvn -pl topologies test -Dtest=ExecutionRunnerIntegrationTest   (/tmp/opencode/aar/10-runner.log)
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 9.561 s -- in io.adaptiveagents.topologies.runner.ExecutionRunnerIntegrationTest
[INFO] BUILD SUCCESS  exit=0
```

Idempotency-relevant methods (surefire reports): TaskStoreTest — `concurrentDuplicateSubmissionsYieldExactlyOneCreation`, `duplicateInsertResolvesToExisting`; ExecutionRunnerIntegrationTest — `concurrentDuplicateRoutesResolveToSingleRoutingAndExecution`, `duplicateRouteWhileRunningReturnsExistingDecision`.

Kafka at-least-once redelivery (from today's ingress suite, `/tmp/opencode/aar/04-ingress-test.log`):

```
$ mvn -pl ingress test
[INFO] Running io.adaptiveagents.ingress.kafka.KafkaIngressIntegrationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 16.72 s -- in io.adaptiveagents.ingress.kafka.KafkaIngressIntegrationTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

KafkaIngressIntegrationTest methods include `redeliveredDuplicateSingleExecution` (plus `requestReplyRoundtrip`, `progressTopicOrdered`, `poisonEnvelopeYieldsErrorReplyAndSurvives`).

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| 2 concurrent identical submits → exactly one RUNNING row | `concurrentDuplicateSubmissionsYieldExactlyOneCreation` + `concurrentDuplicateRoutesResolveToSingleRoutingAndExecution` green (advisory-lock serialized) |
| One set of node executions (count progress events) | Event-count assertion inside `concurrentDuplicateRoutesResolveToSingleRoutingAndExecution` |
| Both callers get same taskId result | `duplicateInsertResolvesToExisting` (200-with-existing semantics) + `duplicateRouteWhileRunningReturnsExistingDecision` |
| Kafka at-least-once redelivery covered | `KafkaIngressIntegrationTest.redeliveredDuplicateSingleExecution` green |
| Never execute graph before dedup check commits | Guard lives in TaskStore/RoutingService pre-execution path (commit c7d1f1d diff); violation would fail the concurrent tests above |

## Not re-runnable post-hoc

Nothing — concurrent DB tests and Kafka redelivery test all re-run green today.
