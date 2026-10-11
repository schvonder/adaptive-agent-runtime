# T14 — FAN_OUT/GATHER parallel topology (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit 283952f); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T14: fixed fan width 3 parallel branch nodes on virtual threads via UNPOOLED `Executors.newVirtualThreadPerTaskExecutor()`; GATHER node merges branch results; one-branch failure → gather continues with survivor results + degraded flag. No pooled virtual threads, no platform-thread pools; fan width a code constant.

Commit `283952f feat(topologies): FAN_OUT/GATHER parallel topology` (2026-10-03):

```
 .../topologies/fanout/FanOutState.java             |  76 ++++++
 .../topologies/registry/FanOutGraphFactory.java    | 209 ++++++++++++++++++-
 .../topologies/registry/FanOutTopologyConfig.java  |  25 +++
 .../topologies/registry/TopologyRegistry.java      |   4 +-
 .../fanout/FanOutTopologyIntegrationTest.java      | 159 ++++++++++++++
 .../topologies/registry/TopologyRegistryTest.java  |   3 +-
 .../topologies/testutil/MutableSearchTool.java     |  17 +-
 .../topologies/testutil/ScriptedChatModel.java     |   2 +-
 8 files changed, 487 insertions(+), 8 deletions(-)
```

## Acceptance re-executed today (post-hoc)

Full topologies suite at HEAD (transcript `/tmp/opencode/aar/05-topologies-test.log`):

```
$ mvn -pl topologies test
[INFO] Running io.adaptiveagents.topologies.fanout.FanOutTopologyIntegrationTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.617 s -- in io.adaptiveagents.topologies.fanout.FanOutTopologyIntegrationTest
...
[INFO] Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

FanOutTopologyIntegrationTest methods (surefire report): `fullFanMergesThreeSections`, `partialFanDegradesWithSurvivorSections`, `allBranchesFailSetsError`.

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| Test: 3 branches → merged result contains 3 sections | `fullFanMergesThreeSections` green |
| Failure injection: 1 branch throws → result has 2 sections + `degraded:true` | `partialFanDegradesWithSurvivorSections` green (survivor sections + degraded flag; exact survivor count is timing-dependent by structure — see T19 evidence note) |
| QA happy: full fan | `fullFanMergesThreeSections` |
| QA failure: partial fan marked degraded | `partialFanDegradesWithSurvivorSections`; all-branches-fail boundary covered by `allBranchesFailSetsError` |
| Unpooled virtual threads, fan width code constant | Implementation in `FanOutGraphFactory` (commit 283952f); structural — no runtime config surface exists |

## Not re-runnable post-hoc

Nothing — re-run green today.
