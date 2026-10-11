# T10 — Topology Registry + routing decision persistence + override (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit e3df8c8); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T10: `topologies` module — `enum Topology {SOLO, CHAIN, FAN_OUT, SUPERVISOR}`; `TopologyRegistry` = static `Map<Topology, GraphFactory>` built at startup (code-defined); `RoutingService`: `forcedTopology` wins over classifier; writes `tasks.decision` = `{verdict, source: RULES|LLM|OVERRIDE, rationale}` and status→ROUTED. No dynamic/config-driven/runtime graph assembly.

Commit `e3df8c8 feat(topologies): topology registry + routing decision persistence` (2026-10-02):

```
 .../io/adaptiveagents/core/store/TaskStore.java    |  10 ++
 topologies/pom.xml                                 |  20 ++++
 .../topologies/registry/{Chain,FanOut,Solo,Supervisor}GraphFactory.java | 8 + each
 .../topologies/registry/GraphFactory.java          |   9 ++
 .../topologies/registry/TopologyRegistry.java      |  32 +++++
 .../topologies/routing/{DecisionSource,RoutingDecision,RoutingService}.java
 .../topologies/registry/TopologyRegistryTest.java  |  33 ++++++
 .../routing/RoutingServiceIntegrationTest.java     | 131 ++++++++++++++++++
 13 files changed, 357 insertions(+)
```

## Acceptance re-executed today (post-hoc)

Full topologies suite at HEAD (transcript `/tmp/opencode/aar/05-topologies-test.log`):

```
$ mvn -pl topologies test
[INFO] Running io.adaptiveagents.topologies.routing.RoutingServiceIntegrationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.200 s -- in io.adaptiveagents.topologies.routing.RoutingServiceIntegrationTest
[INFO] Running io.adaptiveagents.topologies.registry.TopologyRegistryTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.004 s -- in io.adaptiveagents.topologies.registry.TopologyRegistryTest
...
[INFO] Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

Methods (surefire reports): RoutingServiceIntegrationTest — `deterministicTopologyForAmbiguousEnvelopes`, `forcedTopologyOverridesClassifier`, `decisionPersistedToTaskRow`, `invalidForcedTopologyFailsDeserialization`; TopologyRegistryTest — `mapsEveryTopologyToItsFactory`, `factoriesAreDistinctPerTopology`, `unknownTopologyRejected`.

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| Same envelope ×10 → same topology | `deterministicTopologyForAmbiguousEnvelopes` green |
| forced_topology=SOLO on complex task → SOLO + source=OVERRIDE | `forcedTopologyOverridesClassifier` green |
| Decision row persisted (assert jsonb) | `decisionPersistedToTaskRow` green (`tasks.decision` with verdict/source/rationale) |
| QA failure: invalid forced_topology value → envelope validation error (not 500) | `invalidForcedTopologyFailsDeserialization` green |
| Registry is static code-defined map | `mapsEveryTopologyToItsFactory` + `unknownTopologyRejected` green (no dynamic loading paths exist) |

## Not re-runnable post-hoc

Nothing — re-run green today.
