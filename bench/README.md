# bench — client consumption patterns

How a client gets the FINAL RESULT of a task submitted through the ingress. Both patterns
start by submitting (e.g. `POST /tasks` or a Kafka record to `adaptive-tasks-request`) and
holding on to the returned `task_id`.

## (a) `get_task` REST polling

Poll the task resource until a terminal status (`SUCCEEDED`, `FAILED`, `BUDGET_EXCEEDED`,
`DEADLINE_EXCEEDED`); `result` carries the final payload on `SUCCEEDED`, `error` the reason
otherwise.

```bash
ID=<task_id from the submission ACK>
while true; do
  STATE=$(curl -s -H "X-API-Key: $KEY" "http://localhost:8080/tasks/$ID")
  echo "$STATE"
  echo "$STATE" | grep -qE '"status":"(SUCCEEDED|FAILED|BUDGET_EXCEEDED|DEADLINE_EXCEEDED)"' && break
  sleep 1
done
```

## (b) tailing `adaptive-tasks-progress`

Consume the progress topic (a LIVE TAIL: frames from app start onward only) and stop at the
first terminal `TASK_*` event for your `task_id`. Frame shape:
`{"seq":…,"task_id":"…","type":"…","payload":{…}}`.

```bash
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic adaptive-tasks-progress \
  --from-beginning \
  --property print.key=true
# stop when a frame shows "type":"TASK_COMPLETED" (or TASK_FAILED / TASK_DEADLINE_EXCEEDED /
# TASK_BUDGET_EXCEEDED) with your task_id; node progress is NODE_START with payload {"node":…}
```

Historical replay of events emitted before your consumer attached is NOT served by the
topic — use the REST SSE stream `GET /tasks/{id}/events` with `Last-Event-ID` (see
docs/ENVELOPE.md).

---

# Benchmark Harness (T26 / T27)

The `bench` module provides an automated harness (`BenchmarkRunner`) that executes the 12 demo research tasks (`workload/src/main/resources/tasks/demo-tasks.json`) across all four topologies (`SOLO`, `CHAIN`, `FAN_OUT`, `SUPERVISOR`) across $N \ge 3$ runs, reporting medians for latency, cost, and quality.

## Execution

### Test Mode (Stub / CI)
Executes the full $4 \times 12 \times 3$ matrix using in-process deterministic doubles and Testcontainers PostgreSQL:

```bash
mvn -pl bench test -Pbenchmark
```

Generates `target/BENCHMARK.md` capturing benchmark configuration, per-cell medians, and status.

### Real Model Run (T27)
Configured via `bench/src/main/resources/bench-profile.yml` — two branches, no profiles:

- **`model`** (the agent-facing endpoint under benchmark):
  - Provider: OpenAI-compatible
  - Base URL: `http://192.168.1.54:8080`
  - Model: `qwen3.6-35b-a3b` (a model NAME — llama-server must run with `--alias qwen3.6-35b-a3b`; see the yml NOTE)
  - API Key: loaded from the env var named by `api-key-env` (`RUNTIME_BENCH_API_KEY`; no literal secret committed)
  - Pricing: 0.00 / 1M (self-hosted)
- **`judge`** (quality scoring):
  - Base URL, model, and `api-key-env` configured independently — the judge may point at a different endpoint than the model branch.
- **Database Configuration**:
  - Self-contained via Testcontainers PostgreSQL in test runs.
  - For external deployments, configure via environment variables:
    - `SPRING_DATASOURCE_URL`
    - `SPRING_DATASOURCE_USERNAME`
    - `SPRING_DATASOURCE_PASSWORD`
- **Metrics Collected**:
  - **P50 Latency**: Wall-clock duration from task submission to terminal status (`SUCCEEDED`, etc.).
  - **Median Cost**: Computed from token usage recorded in `task_cost_entries` (or prompt/completion length) multiplied by the profile price table.
  - **Quality (0-5)**: Scored by `LlmJudge` using the 0-5 rubric with reference grounding.
- **Fixture Requirement & Error Rows**:
  - Workload runs in offline fixture mode (`WORKLOAD_LIVE` unset).
  - Tasks require recorded fixtures under `fixtures/tasks/<task_id>/`.
  - If a fixture is missing, the harness generates an `ERROR (missing fixture: ...)` row in the benchmark table, ensuring the matrix never shrinks.
