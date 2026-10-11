# Multi-Agent Topology Benchmark Results

> **STATUS: NOT RUN** — no LLM backend with sustained throughput was available in this environment at commit time (2026-10-11). See .omo/evidence/task-27-adaptive-agent-runtime.md for the full diagnosis.

## Benchmark Configuration (pinned profile)

- **Provider**: openai-compatible
- **Base URL**: http://localhost:4000
- **Model**: `gemini-3-flash`
- **API Key Env**: `RUNTIME_BENCH_API_KEY`
- **Temperature**: 0.0
- **Seed**: 42
- **Runs**: 3
- **Pricing**: $0.15 / 1M input tokens, $0.60 / 1M output tokens
- **Judge**: `gemini-3-flash` (rubric 0-5)

## Results Matrix

The 4 topologies × 12 tasks × 3 runs (48 cells) matrix will be rendered here by the harness.

| Task ID | Task Title | Topology | P50 Latency (ms) | Median Cost ($) | Quality (0-5) | Status |
|---|---|---|---|---|---|---|

## Analysis

Pending real run.

## Reproduction Instructions

```bash
curl -s http://localhost:4000/v1/models | grep -q gemini-3-flash
export RUNTIME_BENCH_API_KEY="<your-api-key>"
mvn test -pl bench -Pbenchmark-real   # writes BENCHMARK.md to repo root (and bench/target/)
```
