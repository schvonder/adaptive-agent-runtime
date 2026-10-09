# Degradation Matrix

How each topology degrades when a task-level guard rejects a node boundary: budget exhaustion
(`budget_usd`, T19) or a passed deadline (`deadline_ms`, T16). Guards fire at node boundaries
only — an in-flight model call is never interrupted; the cut takes effect at the next boundary.
A rejected run **never silently drops partial results**: the topology folds a
`partialResult` snapshot into the final state and the runner persists it in the `error` column as
`{"message": ..., "partial": ...}` alongside the terminal status.

## Budget accounting

- `CostTracker` (`topologies/budget`) records one row per model response in `task_cost_entries`
  (node, model, prompt/completion tokens, cost), priced via
  `topologies/src/main/resources/budget-prices.yml`: per-1M-token USD prices keyed by model id,
  with a mandatory `default` entry pricing unlisted or absent model ids.
- `TaskGuard` raises `BudgetExceededException` at a node boundary when the declared `budget_usd`
  is non-positive or the accumulated spend (`SUM(cost_usd)`) has reached it. Spend rows are
  persisted, so a task resumed after a crash does not re-arm its budget.
- Responses that report no usage (0/0 tokens — e.g. fixture models) cost nothing and are not
  recorded. Routing-time classification calls happen before the graph runs and are outside this
  accounting.
- The runner maps a budget rejection to `BUDGET_EXCEEDED` (`TASK_BUDGET_EXCEEDED` progress event);
  a deadline rejection maps to `DEADLINE_EXCEEDED`.

## Matrix

| Topology | Behavior on budget cut | Partial result kept |
|----------|------------------------|---------------------|
| SOLO | **stop** — the ReAct loop halts at the next iteration boundary instead of running to `MAX_ITERATIONS` | tool transcript of completed iterations |
| CHAIN | **stop-at-stage** — the rejected stage and every downstream stage are skipped (downstream nodes no-op on `degraded`) | completed stage outputs (`plan`, `summary`, search-result count) plus the `stoppedAt` stage |
| FAN_OUT | **reduce width / skip remaining** — a branch whose boundary is rejected is skipped and marked failed; branches that already produced sections keep them; the gather fold still runs | gathered `sections` + per-branch `branchStatus` |
| SUPERVISOR | **finish-with-partial** — the rejected subagent folds and the supervisor routes to `finalize`, which reports the partial artifact (a second rejection at `finalize` still folds the same partial) | `research` / `draft` / `finalCritique` snapshot |

## Guarantees and scope

- The first node boundary always passes when nothing has been spent yet, so a budget cut can only
  occur after at least one model response was consumed; at least that node's output is reflected
  in the partial.
- For FAN_OUT the branch boundaries run concurrently, so which branches are skipped vs. completed
  is timing-dependent; the invariant is that sections produced before the cut survive in the
  partial and skipped branches appear as `failed` in `branchStatus`.
- Tasks with `budget_usd = null` are never budget-rejected (unmetered).
