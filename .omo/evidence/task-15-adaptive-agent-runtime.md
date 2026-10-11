# T15 — SUPERVISOR_TEAM topology: researcher/writer/critic (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit 18949de); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T15: supervisor node routes between three subagent nodes (researcher/writer/critic); critic loop max 2 iterations then forced finish; no unbounded iteration loops.

Commit `18949de feat(topologies): SUPERVISOR_TEAM topology` (2026-10-03):

```
 .../registry/SupervisorGraphFactory.java           | 296 ++++++++++++++++++-
 .../registry/SupervisorTopologyConfig.java         |  25 +++
 .../topologies/registry/TopologyRegistry.java      |   4 +-
 .../topologies/supervisor/SupervisorState.java     | 106 ++++++
 .../SupervisorTopologyIntegrationTest.java          | 192 +++++++++++++
 6 files changed, 621 insertions(+)
```

## Acceptance re-executed today (post-hoc)

Full topologies suite at HEAD (transcript `/tmp/opencode/aar/05-topologies-test.log`):

```
$ mvn -pl topologies test
[INFO] Running io.adaptiveagents.topologies.supervisor.SupervisorTopologyIntegrationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.854 s -- in io.adaptiveagents.topologies.supervisor.SupervisorTopologyIntegrationTest
...
[INFO] Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

SupervisorTopologyIntegrationTest methods (surefire report): `acceptWithinCapFinishesCleanly`, `neverSatisfiedForcedFinishDegrades`, `boundedTrajectoryNeverExceedsCap`, `malformedCriticOutputTreatedAsRevise`.

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| Stub trajectory reaches FINISH within 2 critic rounds | `acceptWithinCapFinishesCleanly` green |
| Max-iteration test: critic never satisfied → forced finish + degraded flag | `neverSatisfiedForcedFinishDegrades` green |
| QA happy: supervised report | `acceptWithinCapFinishesCleanly` |
| QA failure: iteration cap triggers degraded finish | `neverSatisfiedForcedFinishDegrades`; hard bound asserted by `boundedTrajectoryNeverExceedsCap` |
| No unbounded loops (robustness) | `malformedCriticOutputTreatedAsRevise` — malformed critic output cannot break the loop accounting |

## Not re-runnable post-hoc

Nothing — re-run green today.
