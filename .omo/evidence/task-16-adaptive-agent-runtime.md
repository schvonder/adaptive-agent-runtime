# T16 — Checkpointed execution runner + guards (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit 4e059d3); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T16: runner builds graph via registry, `compile(CompileConfig.builder().checkpointSaver(postgresSaver))`, threadId=taskId; node wrapper (`TaskGuard`) checks budget/deadline at every node BOUNDARY; transitions tasks status RUNNING→terminal; publishes progress events per node; no LangGraph4j interrupts; in-flight LLM calls cancelled via future-timeout in node wrapper. `LeakFreePostgresSaver` wraps the saver for leak-free per-run usage.

Commit `4e059d3 feat(core): checkpointed execution runner with budget/deadline guards` (2026-10-04) — 30 files, highlights:

```
 .../guard/{GuardException,BudgetExceededException,DeadlineExceededException,TaskGuard}.java
 .../topologies/runner/ExecutionRunner.java          | 253 ++++++++++++++++++
 .../topologies/runner/LeakFreePostgresSaver.java    |  64 +++++
 .../registry/{Solo,Chain,FanOut,Supervisor}GraphFactory.java  (guard wiring)
 .../io/adaptiveagents/core/store/TaskStore.java     |  43 ++--
 30 files changed (see git show 4e059d3)
```

## Acceptance re-executed today (post-hoc)

Targeted signature run (transcript `/tmp/opencode/aar/10-runner.log`):

```
$ mvn -pl topologies test -Dtest=ExecutionRunnerIntegrationTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 9.561 s -- in io.adaptiveagents.topologies.runner.ExecutionRunnerIntegrationTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

ExecutionRunnerIntegrationTest methods (surefire report): `durableChainRunPersistsCheckpointsAndCompletes`, `resumeAfterAbandonSkipsCompletedNodes`, `startOrResumeOnCheckpointedRunningTaskResumes`, `plainResumeOnZeroCheckpointThreadFailsWithNoValidCheckpoint`, `startOrResumeOnZeroCheckpointRunningTaskRestartsFresh`, `deadlineExceededMidRunCompletesRunFuture`, `duplicateRouteWhileRunningReturnsExistingDecision`, `concurrentDuplicateRoutesResolveToSingleRoutingAndExecution`.

Full topologies suite: `Tests run: 46, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS` (`/tmp/opencode/aar/05-topologies-test.log`).

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| Integration test: run CHAIN task → checkpoint rows exist in PG mid-run (poll assert) → SUCCEEDED | `durableChainRunPersistsCheckpointsAndCompletes` green |
| Progress events per node present | Asserted inside `durableChainRunPersistsCheckpointsAndCompletes` (per-node NODE_* events via ProgressService) |
| QA happy: durable run | same test + `startOrResumeOnCheckpointedRunningTaskResumes` |
| QA failure: node throws → FAILED + last checkpoint retained | `resumeAfterAbandonSkipsCompletedNodes` (abandoned mid-run task resumes/skips completed nodes from retained checkpoint); guard-rejection outcomes → BUDGET/DEADLINE asserted in T19/T20 tests reusing this runner |
| Deadline guard mid-run without hanging the run future | `deadlineExceededMidRunCompletesRunFuture` green |

## Not re-runnable post-hoc

Nothing — PG17-backed integration tests re-run green today.
