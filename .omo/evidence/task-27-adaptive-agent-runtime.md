# T27 — Real Cross-Topology Benchmark Run — Evidence

Repo: `/home/kirill/IdeaProjects/adaptive-agent-runtime`, branch `main`, base HEAD `ce50031`.
Executor: delegated T27 prompt (todo 27 NOT RUN path).

---

## Verdict: NOT RUN

The plan's sanctioned fallback for todo 27 was taken: *"if no model available in environment, commit template + mark NOT RUN and document the exact command"*. No LLM backend with benchmark-grade sustained throughput was available in this environment at commit time (2026-10-11), so no real results matrix could be produced. A template `BENCHMARK.md` was committed at the repo root (STATUS: NOT RUN) with the exact reproduction command; the diagnosis follows.

## Environment Diagnosis Timeline

All times ~2026-10-11, verbatim facts:

- **~01:51** — First real run on `gemini-3.8-flash-vertex` completed but **TAINTED**: mid-run proxy degradation made FAN_OUT cells show ~60 ms latency + quality 0 (fast-failed LLM calls were recorded as OK; impossible vs SOLO ~22 s on the same tasks) → run discarded.
- **~02:30** — Probes: `gemini-3.8-flash-vertex` and `gpt-4o-mini` → 403 PERMISSION_DENIED; `gemini-3-flash` single calls OK.
- **~03:06** — Rerun on `gemini-3-flash` hit Vertex quota exhaustion with `retryDelay 86018s` (~24 h) — the runner's 120 s per-run await yielded TIMEOUT cells (`Task 1 [CHAIN] run 2/3 -> 120019 ms, quality: 0, status: TIMEOUT`).
- **~03:20** — Sustained-load probes: `gemini-3.7-flash` 15-call loop exceeded 30 s/call; `gemini-3.6-flash` single OK (sustained untested); `claude-3-5-sonnet` upstream BadRequestError; llama-server `192.168.1.54:8080` connection refused.
- **Conclusion** — No backend with benchmark-grade sustained throughput → the plan's NOT RUN path was taken.

## Model-Switch Disclosure

The profile was repinned `gemini-3.8-flash-vertex` → `gemini-3-flash` (single-call verified on the proxy after the 403s). This is documented as a `NOTE` in `bench/src/main/resources/bench-profile.yml`.

## Reproduction

```bash
curl -s http://localhost:4000/v1/models | grep -q gemini-3-flash
export RUNTIME_BENCH_API_KEY="<your-api-key>"
mvn test -pl bench -Pbenchmark-real   # writes BENCHMARK.md to repo root (and bench/target/)
```

## Follow-Up

After quota reset (~2026-10-12 03:00 +03:00 per `retryDelay`), the same command regenerates a real `BENCHMARK.md` to replace this template.
