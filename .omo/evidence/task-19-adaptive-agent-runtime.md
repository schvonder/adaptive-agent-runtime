# T19 — Budget guard + degradation matrix (adaptive-agent-runtime)

Date: 2026-10-09 · Branch state: working tree on top of `c7d1f1d` (feat(core): taskId idempotency), uncommitted.

## What was implemented

Plan spec T19: "token/cost tracker (tokens per node from model responses × price table `budget-prices.yml`); on exhaustion: cancel pending nodes, persist partial result, status=BUDGET_EXCEEDED. Implement degradation matrix doc `docs/DEGRADATION.md` + code: SOLO=stop, CHAIN=stop-at-stage, FAN_OUT=reduce width/skip remaining, SUPERVISOR=finish-with-partial. Must NOT: silently drop partial results."

### Cost accounting
- `topologies/src/main/resources/budget-prices.yml` — per-1M-token USD prices by model id; mandatory `default` entry prices unlisted/absent model ids.
- `PriceTable` (`topologies/budget`) — immutable table, fail-fast parse (missing `models`, missing `default`, incomplete entries are startup errors). Cost = tokens/1M × price, half-up scale 12.
- `CostTracker` (`topologies/budget`, @Component) — `record(nodeName, taskId, ChatResponse)` inserts one row per response that reports usage into new table `task_cost_entries` (Flyway `V2__task_cost_entries.sql` in core); `spentUsd(taskId)` = `SUM(cost_usd)`. Responses with 0/0 tokens (Spring AI EmptyUsage — e.g. fixture/test doubles) are skipped. Spend is persisted → survives crash/resume (a resumed task does not re-arm its budget).
- Every model call site in the four graph factories now records usage (SOLO agent iterations, CHAIN plan/summarize, FAN_OUT branch summaries, SUPERVISOR researcher/writer/critic).

### Enforcement
- `TaskGuard` now injects `CostTracker` and raises the existing `BudgetExceededException` at a node boundary when `budgetUsd != null` and (`budgetUsd.signum() <= 0` OR `spentUsd >= budgetUsd`), with "spent $X of $Y" detail. Reuses the T16 guardRejected → ExecutionRunner → TaskStatus.BUDGET_EXCEEDED + TASK_BUDGET_EXCEEDED progress event path unchanged.
- `ExecutionRunner.mapOutcome` guard-rejection payload is now `{"message": ..., "partial": ...}` — the topology-folded `partialResult` JSON snapshot is embedded in the `error` column jsonb; non-JSON partials are embedded as a string. Nothing is dropped.

### Degradation matrix (docs/DEGRADATION.md + code)
| Topology | Behavior | Partial kept |
|---|---|---|
| SOLO | stop — loop halts at next iteration boundary | `{"toolTranscript": [...]}` |
| CHAIN | stop-at-stage — rejected + downstream stages skipped (downstream no-ops on `degraded`) | `{stoppedAt, plan?, summary?, searchResults, degraded}` |
| FAN_OUT | reduce width / skip remaining — guard-rejected branches → failure markers; gather fold keeps gathered sections | gathered `{sections, branchStatus, degraded}` |
| SUPERVISOR | finish-with-partial — guard fold routes via `degraded` → finalize, which folds the artifact | `{research, draft, finalCritique, criticRounds, degraded}` |

Each state class gained a `partialResult` key (`PARTIAL_RESULT` + accessor); each factory's `foldGuard` builds the snapshot.

## Tests added

- `BudgetDegradationIntegrationTest` (topologies, 4 tests; @Testcontainers postgres:17-alpine, forced topology + `budget_usd=0.001`, first scripted response priced ≈ $0.45 via gpt-4o-mini 1M/0.5M tokens):
  - `soloBudgetCutStopsLoopAndKeepsTranscript` — BUDGET_EXCEEDED; partial.toolTranscript has "iteration 0"; NODE_START solo-agent == 2 (< MAX_ITERATIONS 3) → stop.
  - `chainBudgetCutStopsAtStageAndKeepsCompletedStageOutputs` — BUDGET_EXCEEDED; partial `{stoppedAt=search, plan=<model output>, searchResults=0}`; NODE_START summarize/cite == 0 → stop-at-stage; exactly 1 cost row.
  - `fanOutBudgetCutKeepsGatheredSections` — BUDGET_EXCEEDED; partial sections ∈ [1, FAN_WIDTH] (first branch always survives — its boundary precedes any spend), branchStatus covers all 3 branches → reduce-width/keep-partials.
  - `supervisorBudgetCutFinishesWithPartial` — BUDGET_EXCEEDED; partial.research contains the researcher output, draft empty, degraded=true; NODE_START researcher/writer/finalize == 1 each → finish-with-partial.
  - Common assertions: `tasks.error` jsonb present with `message` containing "budget exhausted" and a `partial` node (must-not-drop acceptance), TASK_BUDGET_EXCEEDED progress == 1, cost rows recorded.
- `PriceTableTest` (topologies, 5 tests) — per-1M math, default-entry fallback for unknown/null/blank model, classpath table loads, missing-default and incomplete-entry fail fast.
- `ScriptedChatModel.textWithUsage(...)` / `ControlledChatModel.textWithUsage(...)` — responses carrying model id + `DefaultUsage` metadata.
- `TopologyRegistryTest` — updated for the factories' new `CostTracker` constructor arg (null in wiring-only context, same pattern as `ChatClient.Builder`).

## Commands run and results

```
mvn clean install -DskipTests                 → BUILD SUCCESS (8/8 modules; only pre-existing spike "JAR will be empty" warning)
mvn -pl core test                             → Tests run: 41, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
mvn -pl topologies test                       → Tests run: 38, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
mvn -pl app clean verify -DskipTests=false    → surefire ApplicationTest 1/1; failsafe ResumeAfterKillE2eTest 2/2 + SpawnedAppKillRestartTest 1/1 — BUILD SUCCESS
```

Topologies breakdown (38): BudgetDegradationIntegrationTest 4, PriceTableTest 5, ExecutionRunnerIntegrationTest 8, Solo 3, Chain 3, FanOut 3, Supervisor 4, RoutingServiceIntegrationTest 4, TopologyRegistryTest 3, MarkerTest 1 — all pre-existing tests green (no weakening).

App verify log confirms the spawned app migrates `V2 - task cost entries` and routes/executes CHAIN tasks with the new wiring. (First two app-verify attempts failed with a stale-jar artifact: `mvn -pl app verify` without `clean` reused the fat jar from an earlier `install` because maven-jar-plugin skipped regeneration for unchanged classes — the jar still embedded the pre-fix topologies snapshot. `mvn -pl app clean verify` repackaged from the current local-repo artifacts and passed. This is exactly the version-mix pitfall the plan warns about: install all modules consistently first.)

## Changed files

New: `core/src/main/resources/db/migration/V2__task_cost_entries.sql`, `topologies/src/main/resources/budget-prices.yml`, `topologies/src/main/java/io/adaptiveagents/topologies/budget/PriceTable.java`, `topologies/src/main/java/io/adaptiveagents/topologies/budget/CostTracker.java`, `topologies/src/test/java/io/adaptiveagents/topologies/budget/PriceTableTest.java`, `topologies/src/test/java/io/adaptiveagents/topologies/budget/BudgetDegradationIntegrationTest.java`, `docs/DEGRADATION.md`.

Modified (topologies): TaskGuard, BudgetExceededException (javadoc refresh), the four GraphFactories + four TopologyConfigs (CostTracker wiring + usage recording + partial folding), the four State classes (`PARTIAL_RESULT`), ExecutionRunner (guard payload embeds partial), topologies/pom.xml (jackson-dataformat-yaml, Boot-managed version), TopologyRegistryTest, ScriptedChatModel + ControlledChatModel (textWithUsage).

Untouched: ingress, bench, app, workload, TaskStore/submitOrGet semantics (T18 idempotency contract intact — graph execution still starts only after routing), spike.

## Deviations from plan spec

- Spend is persisted in Postgres (`task_cost_entries`, Flyway V2) rather than in-memory: crash/resume otherwise silently re-arms the budget, which would contradict the guard's purpose. V2 is additive (no column changes on `tasks`); `TRUNCATE tasks CASCADE` in existing tests cascades through the FK.
- Classification (routing-time LLM) calls happen before the graph and are not budget-accounted — noted in docs/DEGRADATION.md scope section.
- FAN_OUT branch boundaries run concurrently, so which branches get skipped vs. completed is timing-dependent (by structure); the test asserts the guaranteed invariant: at least one section survives, all branches appear in branchStatus. Documented in DEGRADATION.md.
