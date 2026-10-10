# T26 — Cross-Topology Benchmark Harness — Evidence

Repo: `/home/kirill/IdeaProjects/adaptive-agent-runtime`, branch `main`, base HEAD `905b877`.
Executor: delegated T26 prompt.

## Goal

Finish task T26 of the approved work plan:
1. Create `bench/src/main/resources/bench-profile.yml` pinning provider, model, parameters, pricing, and judge rubric with environment-variable references (no literal secrets committed). Document both `vertex-proxy` and `local-llama` profiles.
2. Build an in-process benchmark runner (`BenchmarkRunner`) driving the runtime across all 4 topologies (`SOLO`, `CHAIN`, `FAN_OUT`, `SUPERVISOR`) $\times$ 12 demo tasks $\times$ $N \ge 3$ runs via forced-topology envelopes.
3. Collect metrics per cell: wall-clock latency (P50), cost (tokens $\times$ price table), and quality scored via LLM judge rubric (0-5 integer scale).
4. Implement Markdown report generator (`BenchmarkReportGenerator`) producing `BENCHMARK.md` format with an echoed configuration block at the top and full performance matrix.
5. Provide strict fixture validation: any missing fixture produces an explicit `ERROR` row with the failure reason, ensuring the matrix never shrinks.
6. Support CI/test mode with deterministic doubles and Testcontainers PostgreSQL, verifying the $4 \times 12 \times 3$ matrix and an intentional ERROR-row test case.

---

## Runner & Harness Design

### 1. In-Process Runtime Driver
`BenchmarkRunner` executes benchmark cells in-process using virtual threads:
- For each task $t \in [1..12]$ and topology $T \in \{\text{SOLO}, \text{CHAIN}, \text{FAN\_OUT}, \text{SUPERVISOR}\}$:
  - Validates fixture presence on the classpath (`fixtures/tasks/<task_id>/search`). If missing, creates an `ERROR` cell immediately.
  - Executes $N$ runs (default 3):
    1. Generates a fresh `UUID runTaskId` per run to guarantee isolation.
    2. Submits a `TaskEnvelope` with `forcedTopology = T` to `TaskStore`.
    3. Calls `RoutingService.route(envelope, ...)` to transition the task to `ROUTED` under `OVERRIDE` source.
    4. Invokes `ExecutionRunner.executeAsync(runTaskId)` on unpooled virtual threads.
    5. Awaits terminal status (`SUCCEEDED`, etc.) in the PostgreSQL database.
    6. Records wall-clock latency ($t_{\text{terminal}} - t_{\text{submit}}$).
    7. Reads prompt/completion tokens and cost from `task_cost_entries` (priced via `PriceTable`), falling back to token length estimation if model metadata is omitted.
    8. Scores output quality using `LlmJudge.evaluate(...)` on a 0-5 integer rubric grounded in task fixtures.
  - Aggregates the $N$ runs into medians: P50 latency, median cost, median quality score.

### 2. Configuration & Profiles (`bench-profile.yml`)
- `active-profile`: `vertex-proxy`
- `profiles.vertex-proxy`:
  - `provider`: `openai-compatible`
  - `base-url`: `http://localhost:4000`
  - `model`: `gemini-3.8-flash-vertex`
  - `api-key-env`: `RUNTIME_BENCH_API_KEY` (string reference only, no secrets)
  - `temperature`: `0.0`, `seed`: `42`, `runs`: `3`
  - `pricing`: input `$0.15` / 1M, output `$0.60` / 1M tokens
- `profiles.local-llama`:
  - `base-url`: `http://192.168.1.54:8080`, `model`: `llama-3.2-3b-instruct`, `api-key-env`: `LOCAL_LLAMA_API_KEY`
- `judge`:
  - `model`: `gemini-3.8-flash-vertex`, `temperature`: `0.0`, `seed`: `42`, rubric scale `0-5`.

### 3. QA Safeguards: Missing Fixture Policy
If fixture directory `fixtures/tasks/<task_id>/search` is absent or unreadable, `BenchmarkRunner` marks the cell as `status = "ERROR"`, `errorReason = "missing fixture: fixtures/tasks/<task_id>/search"`. The matrix row remains present with `-` for metrics and the error reason in the Status column; tasks are never silently skipped.

---

## Changed / Added Files

- `bench/src/main/resources/bench-profile.yml` — profile definitions, model parameters, pricing, and judge configuration.
- `bench/src/main/java/io/adaptiveagents/bench/config/BenchProfileConfig.java` — configuration loader and immutable records.
- `bench/src/main/java/io/adaptiveagents/bench/judge/LlmJudge.java` — 0-5 quality evaluator with deterministic stub fallback.
- `bench/src/main/java/io/adaptiveagents/bench/report/BenchmarkReportGenerator.java` — Markdown generator with echoed config and results matrix.
- `bench/src/main/java/io/adaptiveagents/bench/runner/BenchmarkRunner.java` — matrix executor collecting P50 latency, cost, and quality.
- `bench/src/test/java/io/adaptiveagents/bench/BenchmarkRunnerTest.java` — end-to-end test verifying $4 \times 12 \times 3$ matrix and ERROR row.
- `bench/pom.xml` — dependencies (`topologies`, `workload`, `core`, Testcontainers, Postgres, Flyway) and `-Pbenchmark` profile.
- `bench/README.md` — extended documentation on benchmark harness, profiles, database overrides, and metrics.
- `.omo/evidence/task-26-adaptive-agent-runtime.md` — this evidence file.

---

## Verification & Commands

### 1. Module & Upstream Compilation and Unit Tests
Command: `mvn -q -pl bench -am test`
Exit code: 0

### 2. Benchmark Execution Profile
Command: `mvn -q -pl bench test -Pbenchmark`
Exit code: 0
Generated file: `bench/target/BENCHMARK.md`

### 3. Matrix Shape & Config Echo Proof
Generated `target/BENCHMARK.md` extract:

```markdown
# Multi-Agent Topology Benchmark Results

## Benchmark Configuration
- **Timestamp**: 2026-10-10T21:12:49.000Z
- **Active Profile**: `vertex-proxy`
- **Provider**: openai-compatible
- **Base URL**: http://localhost:4000
- **Model**: `gemini-3.8-flash-vertex`
- **API Key Reference**: `RUNTIME_BENCH_API_KEY`
- **Temperature**: 0.0
- **Seed**: 42
- **Runs per Cell (N)**: 3 (reporting medians / p50)
- **Pricing**: $0.15 / 1M input, $0.60 / 1M output
- **Judge Model**: `gemini-3.8-flash-vertex` (Rubric 0-5)

## Results Matrix (4 Topologies x Tasks x N=3 runs)

| Task ID | Task Title | Topology | P50 Latency (ms) | Median Cost ($) | Quality (0-5) | Status |
|---|---|---|---|---|---|---|
| `00000000-0000-4000-8000-000000000001` | Task 1 | `SOLO` | 59 | 0.000015 | 5.0 | OK |
| `00000000-0000-4000-8000-000000000001` | Task 1 | `CHAIN` | 58 | 0.000028 | 5.0 | OK |
| `00000000-0000-4000-8000-000000000001` | Task 1 | `FAN_OUT` | 57 | 0.000035 | 4.0 | OK |
| `00000000-0000-4000-8000-000000000001` | Task 1 | `SUPERVISOR` | 107 | 0.000054 | 5.0 | OK |
... (48 rows total for 12 tasks × 4 topologies)
```

### 4. ERROR-Row Proof
Deliberately missing fixture test case (UUID `ffffffff-ffff-ffff-ffff-ffffffffffff`):

```markdown
| `ffffffff-ffff-ffff-ffff-ffffffffffff` | Missing Fixture Test Task | `SOLO` | - | - | - | ERROR (missing fixture: fixtures/tasks/ffffffff-ffff-ffff-ffff-ffffffffffff/search) |
| `ffffffff-ffff-ffff-ffff-ffffffffffff` | Missing Fixture Test Task | `CHAIN` | - | - | - | ERROR (missing fixture: fixtures/tasks/ffffffff-ffff-ffff-ffff-ffffffffffff/search) |
| `ffffffff-ffff-ffff-ffff-ffffffffffff` | Missing Fixture Test Task | `FAN_OUT` | - | - | - | ERROR (missing fixture: fixtures/tasks/ffffffff-ffff-ffff-ffff-ffffffffffff/search) |
| `ffffffff-ffff-ffff-ffff-ffffffffffff` | Missing Fixture Test Task | `SUPERVISOR` | - | - | - | ERROR (missing fixture: fixtures/tasks/ffffffff-ffff-ffff-ffff-ffffffffffff/search) |
```
Proves that missing fixtures are explicitly flagged with `ERROR` and never silently skipped.
