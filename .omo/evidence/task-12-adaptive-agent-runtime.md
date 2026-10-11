# T12 — SOLO topology, single ReAct agent (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit c9078d8); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T12: `topologies` StateGraph with one agent node (ReAct loop with workload tools), node-boundary guard via shared wrapper interface (no-op `NodeGuard` at this stage; real `TaskGuard` lands in T16); tests with stub model profile; no topology-specific persistence (durability via runner).

Commit `c9078d8 feat(topologies): SOLO ReAct topology` (2026-10-03):

```
 .../io/adaptiveagents/core/store/TaskStore.java    |  28 +++
 topologies/pom.xml                                 |  15 ++
 .../topologies/guard/NoOpGuard.java                |  18 ++
 .../topologies/guard/NodeGuard.java                |  21 ++
 .../topologies/registry/SoloGraphFactory.java      | 119 ++++++++++-
 .../topologies/registry/SoloTopologyConfig.java    |  25 +++
 .../topologies/registry/TopologyRegistry.java      |   4 +-
 .../adaptiveagents/topologies/solo/SoloState.java  |  64 ++++++
 .../solo/SoloTopologyIntegrationTest.java          | 237 ++++++++++++++++++
 10 files changed, 531 insertions(+), 5 deletions(-)
```

## Acceptance re-executed today (post-hoc)

Full topologies suite at HEAD (transcript `/tmp/opencode/aar/05-topologies-test.log`):

```
$ mvn -pl topologies test
[INFO] Running io.adaptiveagents.topologies.solo.SoloTopologyIntegrationTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.268 s -- in io.adaptiveagents.topologies.solo.SoloTopologyIntegrationTest
...
[INFO] Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

SoloTopologyIntegrationTest methods (surefire report): `happyPathProducesResultAndPersistsSuccess`, `toolFailureDegradesAndPersistsError`, `boundedLoopTerminatesWhenModelNeverFinalizes`.

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| Stub-model test: submit simple task → SUCCEEDED with non-empty result | `happyPathProducesResultAndPersistsSuccess` green |
| Error test: tool failure → FAILED + error jsonb populated | `toolFailureDegradesAndPersistsError` green |
| QA happy: end-to-end single-agent answer | `happyPathProducesResultAndPersistsSuccess` |
| QA failure: all tools error → FAILED persisted | `toolFailureDegradesAndPersistsError` |
| Bounded iteration (no infinite ReAct loop) | `boundedLoopTerminatesWhenModelNeverFinalizes` green |

## Not re-runnable post-hoc

Nothing — stub-model tests re-run green today.
