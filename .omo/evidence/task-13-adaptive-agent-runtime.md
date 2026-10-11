# T13 — CHAIN topology: plan→search→summarize→cite (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit 12cd267); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T13: 4 sequential nodes with intermediate state in graph state (`ChainState`), each node wrapped with the guard interface; tests with stub model + search fixtures; no parallelism inside CHAIN.

Commit `12cd267 feat(topologies): CHAIN sequential topology` (2026-10-03):

```
 .../topologies/chain/ChainState.java               |  95 ++++++
 .../topologies/registry/ChainGraphFactory.java     | 244 ++++++++++++++++++-
 .../topologies/registry/ChainTopologyConfig.java   |  27 +++
 .../topologies/registry/TopologyRegistry.java      |   4 +-
 .../chain/ChainTopologyIntegrationTest.java        | 148 +++++++++++++
 .../topologies/registry/TopologyRegistryTest.java  |   4 +-
 .../solo/SoloTopologyIntegrationTest.java          |  76 +------
 .../topologies/testutil/MutableSearchTool.java     |  40 ++++
 .../topologies/testutil/ScriptedChatModel.java     |  57 +++++
 .../io/adaptiveagents/workload/tools/Citation.java |   5 +-
 .../workload/tools/SearchResult.java               |   5 +-
 11 files changed, 630 insertions(+), 75 deletions(-)
```

## Acceptance re-executed today (post-hoc)

Full topologies suite at HEAD (transcript `/tmp/opencode/aar/05-topologies-test.log`):

```
$ mvn -pl topologies test
[INFO] Running io.adaptiveagents.topologies.chain.ChainTopologyIntegrationTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.966 s -- in io.adaptiveagents.topologies.chain.ChainTopologyIntegrationTest
...
[INFO] Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

ChainTopologyIntegrationTest methods (surefire report): `happyPathRunsAllFourStages`, `emptyFixtureGraceful`, `summarizeModelErrorDegrades`.

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| Test asserts all 4 node outputs present in final result | `happyPathRunsAllFourStages` green |
| Fixture-empty search → graceful empty-section result (not crash) | `emptyFixtureGraceful` green |
| QA happy: full chain completes | `happyPathRunsAllFourStages` |
| QA failure: summarize node model error → FAILED with stage marker in error | `summarizeModelErrorDegrades` green |

## Not re-runnable post-hoc

Nothing — stub-model + fixture tests re-run green today.
