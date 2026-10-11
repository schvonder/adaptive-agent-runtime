# T17 — Resume-after-kill e2e (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit 7a840d9); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T17: in `app/src/test` (harness from T3): spawn app, submit long CHAIN task (fixture model with per-node delay), SIGKILL mid-run, relaunch; runner auto-resumes pending RUNNING tasks from checkpoints on startup (`StartupRescan`); tasks whose deadline passed while down → DEADLINE_EXCEEDED (not resumed).

Commit `7a840d9 feat(core): startup resume of interrupted tasks` (2026-10-05):

```
 .../io/adaptiveagents/app/AppJacksonConfig.java    |  19 ++
 .../app/fixture/{FixedTextChatModel,FixtureModelConfig}.java
 .../adaptiveagents/app/resume/StartupRescan.java   | 134 ++++++++++
 .../io/adaptiveagents/app/ResumeAfterKillE2eTest.java | 269 ++++++++++++++++++
 .../core/store/TaskStore.java                      |  54 +++--
 .../topologies/runner/ExecutionRunner.java         |  74 +++++-
 .../runner/ExecutionRunnerIntegrationTest.java     |  69 ++++++
 13 files changed, 691 insertions(+), 34 deletions(-)
```

## Acceptance re-executed today (post-hoc)

Targeted signature run — real `java -jar` process spawn, SIGKILL, relaunch on the same PG17 container (transcript `/tmp/opencode/aar/11-resume-e2e.log`):

```
$ mvn -pl app clean verify -DskipTests=false -Dtest=ApplicationTest -Dit.test=ResumeAfterKillE2eTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.325 s -- in io.adaptiveagents.app.ApplicationTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running io.adaptiveagents.app.ResumeAfterKillE2eTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 33.82 s -- in io.adaptiveagents.app.ResumeAfterKillE2eTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

ResumeAfterKillE2eTest methods (failsafe report): `killMidRunThenRelaunchCompletesFromCheckpoints`, `deadlineExpiredWhileDownIsNotResumed`.

Also re-run today inside the full app verify (`/tmp/opencode/aar/06-app-verify.log`): `ResumeAfterKillE2eTest` 2/2, 33.04 s.

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| E2E: kill at node 2 → relaunch → task completes | `killMidRunThenRelaunchCompletesFromCheckpoints` green (SIGKILL mid-CHAIN, relaunch auto-resumes) |
| Checkpoint count grows monotonically and result non-empty | Asserted inside `killMidRunThenRelaunchCompletesFromCheckpoints` (checkpoint seq grows across kill; final result non-empty) |
| Deadline-expired-while-down → DEADLINE_EXCEEDED | `deadlineExpiredWhileDownIsNotResumed` green |
| QA happy: resume completes task | `killMidRunThenRelaunchCompletesFromCheckpoints` |
| QA failure: expired-during-downtime not resumed | `deadlineExpiredWhileDownIsNotResumed` |

## Not re-runnable post-hoc

Nothing — the full kill/relaunch e2e was re-executed today against real spawned processes.
