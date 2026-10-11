# F4 — Scope fidelity audit — Evidence

Worktree: `/home/kirill/IdeaProjects/adaptive-agent-runtime` (branch `main`, HEAD `725c9fa`).
Task: F4 of approved plan (final verification: scope fidelity). READ-ONLY audit — no repo file
modified, nothing committed; this evidence file lives on disk only (`.omo/evidence/` is gitignored
per plan commit strategy).

Method: every "Must NOT" clause from the plan's global `### Must NOT have (guardrails, anti-slop,
scope boundaries)` section **and** every per-todo `Must NOT:` line was extracted, then proven absent
by grep/inspection. Raw commands + hit counts below.

---

## Check (a) — Banned-pattern greps (command + hit count)

| # | Banned pattern | Command (run at repo root) | Hits | Verdict |
|---|---|---|---|---|
| A1 | Template engines / web-UI deps in poms | `grep -rniE 'thymeleaf\|freemarker\|mustache\|vaadin\|angular\|react\|vue\|jte\|pebble\|velocity' --include=pom.xml . \| grep -v target` | **0** | CLEAN |
| A1b | Web-UI source files | `find . -path ./.git -prune -o -type f \( -name '*.html' -o -name '*.vue' -o -name '*.jsx' -o -name '*.tsx' \) -print \| grep -v target` | **0** | CLEAN |
| A1c | static/templates/public resource dirs | `find . -type d \( -name static -o -name templates -o -name public \) \| grep -v target \| grep -v .git` | **0** | CLEAN |
| A2 | MCP client starter dep | `grep -rn 'mcp-client' --include=pom.xml .` | **1** — `ingress/pom.xml:41 spring-ai-starter-mcp-client` | COMPLIANT |
| A2b | MCP client in MAIN scope | `grep -rn 'mcp\.client\|McpClient\|McpSyncClient\|AsyncMcpClient' --include='*.java' */src/main` | **0** | CLEAN |
| A3 | Second datastore deps | `grep -rniE 'redis\|neo4j\|mongo\|cassandra\|elasticsearch\|couchbase\|dynamodb\|hazelcast\|ignite' --include=pom.xml . \| grep -v target` | **0** | CLEAN |
| A3b | Second datastore images | `grep -rniE 'redis\|mongo\|neo4j' compose.yaml Dockerfile` | **0** | CLEAN |
| A4 | K8s manifests dirs | `ls -d .github k8s kubernetes helm charts` | none exist | CLEAN |
| A4b | K8s object kinds | `grep -rnE '^kind: (Deployment\|Service\|Ingress\|ConfigMap\|Secret\|StatefulSet\|DaemonSet\|Job\|CronJob)' --include='*.yaml' --include='*.yml' .` (excl. target/.omo) | **0** | CLEAN |
| A4c | Full YAML inventory | `find . \( -prune .git/.omo/target \) -name '*.y*ml'` | exactly 4: `compose.yaml`, `topologies/.../budget-prices.yml`, `bench/.../bench-profile.yml`, `app/.../application.yml` — all app config, no manifests | CLEAN |
| A5 | Extra protocols: grpc/websocket/netty | poms: `grep -rniE 'grpc\|websocket\|netty' --include=pom.xml .`; java: `grep -rniE 'ServerEndpoint\|WebSocketHandler\|@EnableWebSocket\|GrpcServer' --include='*.java' .` | **0 / 0** | CLEAN |
| A5b | Extra brokers (rabbitmq/activemq/mqtt/rsocket/amqp/stomp) | `grep -rniE 'rabbitmq\|activemq\|mqtt\|rsocket\|amqp\|stomp' --include=pom.xml .` | **0** | CLEAN |

**A2 scope proof** (the single allowed shape — test-scope in ingress pom, matching the plan's T23
acceptance criterion "MCP client (Spring AI mcp client starter test-scope)"):

```xml
<!-- ingress/pom.xml:39-43 -->
<!-- MCP client used ONLY by the e2e test against the in-app server (no client in prod scope). -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
    <scope>test</scope>
</dependency>
```

`bench/pom.xml` contains **no** MCP dependency at all. Protocols present: webmvc REST+SSE
(`spring-boot-starter-web`), MCP server (`spring-ai-starter-mcp-server-webmvc`), Kafka
(`spring-kafka`) — exactly the plan's three.

---

## Check (b) — Deliverables present (10/10) + README links resolve

| Deliverable | Size | First line (head -1/-3) |
|---|---|---|
| `SPIKE.md` | 3757 B | `# Wave-0 Integration Spike` |
| `docs/ENVELOPE.md` | 6399 B | `# TaskEnvelope Contract v1` |
| `docs/DEGRADATION.md` | 3110 B | `# Degradation Matrix` |
| `docs/TASKS.md` | 6352 B | `# Workload Tasks & Routing Matrix` |
| `BENCHMARK.md` | 1122 B | `# Multi-Agent Topology Benchmark Results` — line 3: `> **STATUS: NOT RUN**` (→ **COMPLIANT** per plan T27 "commit template + mark NOT RUN and document the exact command"; repro cmd `mvn test -pl bench -Pbenchmark-real` present) |
| `compose.yaml` | 5150 B | `# adaptive-agent-runtime — docker-compose demo stack (T29).` |
| `scripts/smoke.sh` | 4175 B | `#!/usr/bin/env bash` (+exec bit, T29) |
| `scripts/demo.sh` | 11401 B | `#!/usr/bin/env bash` (+exec bit, T31) |
| `docs/demo.cast` | 159126 B | valid asciinema v2 header: `{"version": 2, "width": 80, ...}` |
| `README.md` | 10387 B | positioning: "adaptive, durable multi-agent runtime for the JVM" |

README relative-link check (same script family as T30 evidence; `/tmp/opencode/f4/link-check.sh`,
extracts `](target)` links, skips http/mailto, asserts file exists) — **exit 0, 11/11 OK**:

```
OK: BENCHMARK.md
OK: bench/README.md
OK: compose.yaml
OK: docs/DEGRADATION.md
OK: docs/demo.cast
OK: docs/ENVELOPE.md
OK: docs/TASKS.md
OK: .env.example
OK: scripts/demo.sh
OK: scripts/smoke.sh
OK: SPIKE.md
---
checked 11 relative link targets, fail=0
```

(T30's 9 links + `docs/demo.cast` and `scripts/demo.sh` added by T31 — all resolve.)

---

## Check (c) — "Must NOT" sweep: clause → proof → PASS/FAIL

### Global guardrails (plan lines 28–37)

| # | Clause | Proof (grep/inspection, run at repo root) | Verdict |
|---|---|---|---|
| G1 | No web UI/console/editor; no visual workflow designer | A1/A1b/A1c above: 0 template-engine deps, 0 html/vue/jsx/tsx files, 0 static/templates/public dirs | **PASS** |
| G2 | No dynamic graph construction / config-driven assembly / runtime topology loading | `grep -rniE 'topolog\w*(fromConfig\|fromProperties)\|@ConfigurationProperties.*topolog\|Class\.forName.*[Tt]opolog\|graph.*from.*yaml' --include='*.java' */src` = **0**; `TopologyRegistry` builds `this.factories = Map.of(...)` (static, code-defined, line 16); no topology keys in any yml | **PASS** |
| G3 | No MCP client / external tool federation / MCP auth extensions; surface = exactly submit_task/get_task/get_task_result | A2/A2b: client dep test-scope-only, 0 main-scope client classes; `grep -rniE 'mcp.*auth\|McpAuth\|mcp.*security' */src/main` → only javadoc comments stating the negative; `@Tool` census in `ingress/.../mcp/McpTaskTools.java` = exactly 3: `submit_task` (:63), `get_task` (:91), `get_task_result` (:105) | **PASS** |
| G4 | No HITL approval flow; LangGraph4j interrupt API unused | `grep -rn 'langgraph4j' --include='*.java' */src \| grep -i interrupt` = **0** (all `interrupt` hits are JDK `Future.cancel(mayInterruptIfRunning)` / `Thread.interrupt()` worker cancellation in `ExecutionRunner` — node-boundary guard mechanism, not the LG4j interrupt/HITL API) | **PASS** |
| G5 | No multi-tenancy/auth beyond single X-API-Key; no OAuth | `grep -rn 'spring-security' --include=pom.xml .` = **0**; `grep -rniE 'oauth\|basic.?auth\|tenant' --include='*.java' --include='*.yml' */src app/src` → only javadoc comments in `ApiKeyAuthFilter` ("No spring-security, no basic auth, no OAuth: this plain filter is the whole auth story"); `X-API-Key` used only by `ApiKeyAuthFilter` against `runtime.api-key` (auth-only per `TaskController` javadoc); MCP exempt (coexistence test `McpIngressIntegrationTest:54`), Kafka local-only (compose internal) | **PASS** |
| G6 | No second datastore; Postgres only; no Redis/Neo4j; no message DB besides Kafka | A3/A3b: 0 datastore deps in poms, 0 datastore images in compose/Dockerfile; compose images = `adaptive-agent-runtime:0.1.0`, `postgres:17-alpine`, `apache/kafka:3.9.0`, `jaegertracer/all-in-one:1.62.0`, `ollama/ollama:0.12.9` (profile) — jaeger/ollama are observability/LLM services, not datastores | **PASS** |
| G7 | No K8s manifests, no nginx, no multi-replica | A4/A4b/A4c: no manifest dirs/kinds; `grep -rniE 'nginx\|replicas' compose.yaml Dockerfile README.md scripts/` = **0**; compose services = `['app','postgres','kafka','jaeger','ollama']`, single app service, `replicas` key absent | **PASS** |
| G8 | No new agent-framework abstraction layer — LangGraph4j APIs used directly | no `framework/ abstraction/ api/` packages (find = 0); LG4j `StateGraph`/`AsyncNodeAction`/saver imported directly in 10 topologies-module files (`ChainState`, `FanOutState`, 4× GraphFactory, `TopologyRegistry`, `ExecutionRunner`, `LeakFreePostgresSaver`, ...); the only interface, `GraphFactory`, is the registry pattern the plan itself mandates (T10 `Map<Topology, GraphFactory>`) | **PASS** |
| G9 | No live network in tests/bench (fixtures mandatory; live only behind env flags); no fabricated benchmark numbers | `TavilyAdapter` registered only when `WORKLOAD_LIVE=1` **and** `TAVILY_API_KEY` non-blank (`WorkloadToolsConfig:11,19` — SpEL double-gate); `WorkloadToolsContextTest:16-22` asserts fixture-default context and **skips if WORKLOAD_LIVE=1**; test `HttpClient` usages are localhost calls to the app under test (MCP e2e, Jaeger query) — no external network; `BENCHMARK.md` carries explicit `STATUS: NOT RUN` + repro command — nothing fabricated | **PASS** |

### Per-todo "Must NOT" clauses

| Todo | Clause | Proof | Verdict |
|---|---|---|---|
| T1 | No Wave-1 start before SPIKE.md committed | `git log --oneline --reverse \| head -1` → `9fa4450 chore(spike): wave-0 integration spike...` is the FIRST commit; SPIKE.md added there | **PASS** |
| T2 | No starters not referenced by later todos; no webflux (webmvc only) | `grep -rn 'webflux' --include=pom.xml .` = **0**; full 8-pom dep census = spring-boot-starter(-web/-jdbc/-test/-actuator), spring-kafka, spring-ai (openai model, mcp-server-webmvc), langgraph4j (spring-ai, postgres-saver, bom), flyway, postgresql, jackson, json-schema-validator, HikariCP, micrometer-tracing + otel exporter, testcontainers, wiremock, junit — every one maps to a plan todo; nothing extra | **PASS** |
| T3 | No @SpringBootTest-substitute restarts; no Ryuk disable hacks | `grep -rni 'ryuk\|TESTCONTAINERS_RYUK' --include='*.java' --include='*.yml' --include='*.sh' .` = **0** (harness `SpawnedApp` + ProcessHandle per plan) | **PASS** |
| T4 | No runtime auto-DDL; no ORM magic (JdbcTemplate chosen) | `grep -rniE 'ddl-auto\|ddl_generation\|hibernate\|data-jpa\|jooq\|eclipselink\|openjpa' --include=pom.xml --include='*.yml' .` = **0**; `JdbcTemplate` present in `core/.../store/TaskStore.java` + `core/.../progress/ProgressService.java` | **PASS** |
| T5 | No protocol-specific fields in envelope; no v2; no identity-carrying headers; no topology-carrying headers | `TaskEnvelope` record = exactly `task_id, task_type, prompt, breadth_hint, deadline_ms, budget_usd, forced_topology` (canonical v1, no protocol fields); `grep -rniE 'X-Topology\|X-Forced-Topology\|X-Agent-Topology'` = **0**; `X-API-Key` = auth only; Kafka `X-Task-Id` is `TRACE_HEADER` — logged as "tracing correlation only and never read for identity" (`KafkaTaskListener:32,47,69`) | **PASS** |
| T6 | No terminal→any transition; no error status for duplicates; NO 409 path anywhere | `grep -rnE '409\|CONFLICT' --include='*.java' */src` → 1 hit = SQL clause `INSERT ... ON CONFLICT DO NOTHING` (`app/.../HarnessProbeRunner.java:21`) — a Postgres upsert, NOT an HTTP 409; HTTP-level 409/CONFLICT = **0**; duplicate insert resolves to existing state under advisory lock (T18 proof) | **PASS** |
| T7 | Progress not published only to Kafka — PG authoritative | `progress_events` DDL in `V1__app.sql:18` + `ProgressService` INSERT/`sinceSeq` SELECT via JdbcTemplate (core, PG); Kafka/SSE publishers read from this log | **PASS** |
| T8 | RuleScorer: no LLM call, no I/O, no clock read | `grep -nE 'ChatClient\|chatModel\|Files\.\|Path\.\|Instant\.\|System\.currentTimeMillis\|Clock' core/.../classify/RuleScorer.java` = **0** (97-line pure scorer) | **PASS** |
| T9 | LLM only in AMBIGUOUS band; no temperature > 0 | every temperature literal in main scope = `0.0` (`LlmClassifier:37`, `bench-profile.yml` ×3); CLEAR-band-never-hits-LLM is a WireMock-verified test per T9 evidence | **PASS** |
| T10 | No dynamic/config-driven/runtime graph building | = G2 proof | **PASS** |
| T11 | No live network in tests | = G9 proof (fixture adapters + WORKLOAD_LIVE gate) | **PASS** |
| T12 | No topology-specific persistence logic | `grep -rn 'Saver\|checkpoint\|Checkpoint' --include='*.java' {Solo,Chain,FanOut,Supervisor}GraphFactory.java` = **0** — durability lives only in `ExecutionRunner`/`LeakFreePostgresSaver` | **PASS** |
| T13 | No parallelism inside CHAIN | ExecutorService census in topologies main: only `ExecutionRunner` (runner) + `FanOutGraphFactory` (by design); `ChainGraphFactory` has none — sequential edges | **PASS** |
| T14 | Never pool virtual threads / no platform pools for branches; fan width via code constant | 2× `Executors.newVirtualThreadPerTaskExecutor()` (static final, `FanOutGraphFactory:69`, `ExecutionRunner:105`); `newFixedThreadPool` = **0** hits; fan width is a compile-time constant in FanOutGraphFactory (not runtime config) | **PASS** |
| T15 | No unbounded iteration loops | `SupervisorGraphFactory`: `public static final int MAX_CRITIC_ROUNDS = 2;` with "Never unbounded" cap and forced finish (`:59,157,267`) | **PASS** |
| T16 | No reliance on LG4j interrupts for cancellation | = G4 proof; in-flight cancel via `Future.cancel(mayInterruptIfRunning=true)` in node wrapper (`ExecutionRunner:410-411`) | **PASS** |
| T17 | Never resume tasks whose deadline passed while down | `app/.../resume/StartupRescan.java:33-35`: "a deadline that has already passed completes the task as DEADLINE_EXCEEDED ... **the graph is never invoked**; otherwise ... resumes from the last checkpoint" | **PASS** |
| T18 | Never execute graph before dedup check commits | `TaskStore:32-43`: idempotent submit under transaction-scoped `pg_advisory_xact_lock(hashtext(task_id))` — existence check + insert inside the lock/tx; graph dispatch happens only after submission returns | **PASS** |
| T19 | Never silently drop partial results | `ExecutionRunner:96,597-599`: `PARTIAL_RESULT_KEY` — partial result "embedded as `partial` in the error payload" on guard rejection; BUDGET_EXCEEDED carries partials (T19 tests assert) | **PASS** |
| T20 | No background threads left after expiry | `RunningTaskSweep` finalizes expired RUNNING tasks + `ExecutionRunner.cancel()` removes and interrupts the worker entry; T20 evidence records thread-count-to-baseline assertion | **PASS** |
| T21 | No basic auth / OAuth / multi-tenant | = G5 proof | **PASS** |
| T22 | Never block the listener thread on task completion before replying | `KafkaTaskListener:64-80`: `@KafkaListener` + `@SendTo(REPLY_TOPIC)` returns `ack(submission.record())` immediately after submission — fire-and-forget execution | **PASS** |
| T23 | No MCP client / tool federation / MCP auth extensions | = G3 proof (server-only, 3 tools, /mcp auth-exempt per matrix) | **PASS** |
| T24 | No envelope-semantics drift between adapters (single mapper shared) | all three adapters import the SAME `io.adaptiveagents.ingress.common.EnvelopeValidator` and map into the SAME `core` `TaskEnvelope` record: REST `TaskController:99`, MCP `McpTaskTools:86`, Kafka `KafkaTaskListener:74`; parity asserted by `CrossIngressContractE2eTest` | **PASS** |
| T25 | No tasks requiring live network | `demo-tasks.json` = exactly 12 tasks (python json len = 12); fixture dirs `workload/src/{main,test}/resources/fixtures/{search,pages,tasks}` exist for every task | **PASS** |
| T26 | No live network in bench; no unseeded/unpinned params; no single-run numbers | `bench-profile.yml`: `temperature: 0.0`, `seed: 42`, `runs: 3` (both profiles + judge); WORKLOAD_LIVE unset mandatory; N≥3 enforced by profile | **PASS** |
| T27 | Never fabricate numbers | `BENCHMARK.md` line 3: `> **STATUS: NOT RUN** — no LLM backend ... See .omo/evidence/task-27...` + repro command — the sanctioned NOT-RUN path, not fabrication | **PASS** |
| T28 | No LG4j opentelemetry module wiring; no metrics dashboards | `grep -rn 'langgraph4j-opentelemetry' --include=pom.xml .` = **0** (all LG4j artifacts = `langgraph4j-spring-ai`, `langgraph4j-postgres-saver`, `langgraph4j-bom`); tracing stack is single: micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp (app pom); `grep -rniE 'grafana\|dashboard\|prometheus' compose.yaml application.yml` = **0** | **PASS** |
| T29 | No K8s manifests, nginx, multiple app replicas | = G7 proof; compose = 5 services, one app container | **PASS** |
| T30 | No marketing fluff / roadmap promises / "coming soon" | T30 evidence banned-tone greps (3× `grep -niE` + `grep -c '!'` = 0) re-verified there; README claims each map to read artifacts | **PASS** |
| T31 | No real API keys in the cast | `grep -cE 'sk-[A-Za-z0-9]{10,}\|tvly-[A-Za-z0-9]{10,}\|AIza[A-Za-z0-9_-]{10,}\|ghp_[A-Za-z0-9]{10,}\|RUNTIME_API_KEY=[^*]\|TAVILY_API_KEY=[A-Za-z0-9]' docs/demo.cast` = **0**; 4× `********` redaction markers present (`RUNTIME_API_KEY=******** (value hidden)`) | **PASS** |

**Clause tally: 9 global + 26 per-todo distinct = 35 clauses proven, 35 PASS, 0 FAIL.**

False positives ruled out during the sweep (documented so nobody re-flags them):
- `ON CONFLICT DO NOTHING` (HarnessProbeRunner) = SQL upsert clause, not HTTP 409.
- `mcp-client` pom hit = the explicitly sanctioned test-scope dependency.
- "oauth/basic auth/tenant" and "mcp auth" grep hits = javadoc comments asserting the negative.
- Java-test `HttpClient` imports = localhost app-under-test calls, not external live network.

---

## Accepted deviations (documented, NOT findings)

1. **`apache/kafka:3.9.0` instead of `bitnami/kafka`** (compose.yaml:75) — T29: bitnami image
   unavailable from the registry at execution time; apache/kafka official KRaft single-node used.
   No guardrail touched (still one Kafka, compose-only).
2. **`BENCHMARK.md` STATUS: NOT RUN** — T27: no LLM backend with sustained quota available via the
   proxy; plan T27 explicitly sanctions "commit template + mark NOT RUN and document the exact
   command" (done: `mvn test -pl bench -Pbenchmark-real`). Compliant deliverable per Success
   criteria ("BENCHMARK.md (or explicit NOT RUN)").
3. **Bench-profile model `gemini-3-flash`** (was `gemini-3.8-flash-vertex`) — T27: the proxy's
   gemini-3.8-flash-vertex model group began returning 403 PERMISSION_DENIED mid-run; switched to
   the verified-working equivalent on the same proxy, documented in `bench-profile.yml:2-5`.
   Pin/seed/temperature discipline unchanged.

---

## Verdict

- (a) banned-pattern greps: **0 violating hits** (1 compliant test-scope MCP client dep),
- (b) deliverables: **10/10 present**, README links **11/11 resolve**, BENCHMARK NOT RUN compliant,
- (c) Must NOT sweep: **35/35 clauses PASS** (0 FAIL), plus 3 accepted documented deviations.

## F4: APPROVE

## Commands + exit codes

| Command | Result |
| --- | --- |
| banned-pattern greps A1–A5b (12 commands) | 0 violating hits; only compliant test-scope MCP client |
| `/tmp/opencode/f4/link-check.sh` | exit 0 — 11/11 relative links resolve |
| deliverable `ls`+`head` (10 files) | all present, non-empty, correct headers |
| Must-NOT greps/inspections (c table) | 35/35 PASS |
| `git status` after audit | clean — no repo file modified, nothing staged/committed |
