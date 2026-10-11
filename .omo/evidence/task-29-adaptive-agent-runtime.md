# T29 — docker-compose demo stack + smoke script — Evidence

Worktree: `/home/kirill/IdeaProjects/adaptive-agent-runtime` (branch `main`, HEAD `f7450b2` at task start).
Task: T29 of approved plan (`docker-compose demo stack + smoke script`).

## Design Summary

1. **compose.yaml** (root; folds in and REPLACES the T28 `compose.jaeger.yml` fragment — file deleted):
   - `app` — built from the new root `Dockerfile`, image `adaptive-agent-runtime:0.1.0`, port 8080,
     env wired from `.env` (datasource, `RUNTIME_API_KEY`, `RUNTIME_KAFKA_ENABLED=true`,
     `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`, `SPRING_PROFILES_ACTIVE=fixture-model` default,
     OpenAI base-url/api-key refs for real-model runs, OTLP endpoints → jaeger).
     `depends_on`: postgres/kafka `service_healthy`, jaeger `service_started` (see deviation note).
     Healthcheck: bash `/dev/tcp` HTTP probe against `/actuator/health` (no curl in the JRE image).
   - `postgres:17-alpine` — healthcheck `pg_isready`, named volume `pgdata`, DB/user/pass from `.env`.
   - `kafka` — KRaft single-node, one node with `broker,controller` roles. **Deviation from the plan's
     "bitnami/kafka"**: every `bitnami/kafka` tag (`3.9`, `3.9.0`, `4.0`, `4.1`, `latest`) 404s from this
     environment's registry (2025 Bitnami catalog change; `bitnamilegacy/*` exists but is a deprecated
     namespace). Used the official `apache/kafka:3.9.0` (already proven locally by the Testcontainers
     suites) with its env contract (`KAFKA_*` without the `_CFG` infix) — same KRaft single-node layout.
     Healthcheck: `kafka-topics.sh --bootstrap-server 127.0.0.1:9092 --list`.
   - `jaeger` — `jaegertracing/all-in-one:1.62.0`, `COLLECTOR_OTLP_ENABLED=true`, ports 4317/4318/16686
     (verbatim from the folded fragment). **No healthcheck defined**: the all-in-one image ships no
     shell or probe binary; app depends on it with `service_started`, which is safe because tracing
     export is non-fatal by design (T28 `ExporterDownNonFatalTest`).
   - `ollama` — compose PROFILE `ollama` (`ollama/ollama:0.12.9`), OFF by default; documented in-file
     for local-model reproduction (base-url `http://ollama:11434`).
2. **Dockerfile** (root, multi-stage): `maven:3.9-eclipse-temurin-25` builds `mvn -pl app -am package
   -DskipTests` (module POMs copied first for reactor validation + cacheable `dependency:go-offline`
   layer; BuildKit cache mount on `/root/.m2`) → `eclipse-temurin:25-jre` runtime, non-root `appuser`,
   `EXPOSE 8080`, `ENTRYPOINT ["java","-jar","/app/app.jar"]`. `.dockerignore` keeps the context lean
   (no `.git`, `.omo`, `**/target`, `.env`).
3. **.env.example** — every variable compose/app consume, placeholders + comments only: required
   `RUNTIME_API_KEY`, `POSTGRES_PASSWORD`; Postgres DB/USER defaults; `SPRING_PROFILES_ACTIVE=fixture-model`
   smoke default with real-model instructions (commented `SPRING_AI_OPENAI_API_KEY` / `SPRING_AI_OPENAI_BASE_URL`,
   ollama profile pointer); `OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4318/v1/traces`. `RUNTIME_BENCH_*`
   intentionally absent (bench-harness-only). Real `.env` is gitignored.
4. **scripts/smoke.sh** (`set -euo pipefail`): fail-fast on missing `.env` or empty required vars →
   `docker compose up -d --wait` → POST `/tasks` (X-API-Key, SOLO-band envelope with fresh UUID +
   `forced_topology:"SOLO"`, expect 202/200) → SSE `GET /tasks/{id}/events` via `curl -N` (120s cap)
   until the terminal frame, asserted `TASK_COMPLETED` → GET `/tasks/{id}` asserted
   `"status":"SUCCEEDED"` + non-empty `result` → prints PASS; EXIT trap runs `docker compose down`
   and cleans the temp dir; any failure exits non-zero with diagnostics.

## Fixture-model gating decision (disclosed)

The mechanism ALREADY existed and is env-switchable: Spring profile `fixture-model`
(`FixtureModelConfig`, `FixedTextChatModel`) — no new property gate was needed for the model itself,
and workload search/fetch tools are fixture-backed unless `WORKLOAD_LIVE=1`. Compose simply defaults
`SPRING_PROFILES_ACTIVE=fixture-model`.

Two MINIMAL code changes were nonetheless required to make that profile sufficient for a real
deployment (all tests previously masked these gaps by running the Flyway API explicitly and/or the
`stub-classifier` profile):

1. **`app/fixture/FixtureModelConfig`**: added a `@Primary` rules-only `ComplexityClassifier` bean
   inside the existing `fixture-model` profile. Without it the app cannot boot when no
   `ChatClient.Builder` exists (no model endpoint configured): `LlmClassifier` is
   `@ConditionalOnBean(ChatClient.Builder.class)` and backs off entirely, leaving `RoutingService`
   without a classifier (container crash loop observed — see failed run #1 below). The bean simply
   delegates to `RuleScorer`, which is exactly the fallback path `LlmClassifier` itself takes when
   the LLM leg fails.
2. **`app/pom.xml`**: added `org.springframework.boot:spring-boot-flyway`. Boot 4 modularized
   autoconfiguration; with only `flyway-core` on the classpath the packaged jar never applies
   `db/migration` (tests run `Flyway.configure().load().migrate()` themselves, so `mvn verify` stayed
   green while the container booted against an empty schema — "bad SQL grammar … FROM tasks",
   observed in failed run #2). With the module present the Boot flyway initializer migrates on
   startup (verified: `Successfully validated 2 migrations` in app logs, tables present in the
   compose postgres).
   - Companion test fix: `ApplicationTest` (DB-less context smoke) now sets
     `spring.flyway.enabled=false` — the initializer would otherwise eagerly open a connection to a
     database that does not exist in the plain surefire phase.

## OTLP endpoint note

The generic `OTEL_EXPORTER_OTLP_ENDPOINT` env has base-URL semantics for the OTel SDK (it appends
`/v1/traces`), so feeding it the full path from `application.yml`'s default double-appended the path
(jaeger answered 404). Compose now additionally sets the signal-specific
`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://jaeger:4318/v1/traces` (used verbatim per OTel spec).
Verified end-to-end: after a compose-stack task, `GET localhost:16686/api/traces?service=adaptive-agent-runtime`
returns real traces (spans incl. `ProgressTopicPublisher` with `traceId` correlation).

## Commands + exit codes

| Command | Result |
| --- | --- |
| `docker compose config --quiet` | exit 0 (validates; `--profile ollama` also validated) |
| `docker compose build app` | exit 0 — image `adaptive-agent-runtime:0.1.0`, 250 MB content / 758 MB disk |
| `./scripts/smoke.sh` (run 3, final) | **exit 0, 24 s wall** — full transcript below |
| `./scripts/smoke.sh` with `.env` renamed away | exit 1, clear fail-fast message (below) |
| `./scripts/smoke.sh` with `RUNTIME_API_KEY=` emptied | exit 1, per-variable fail-fast message (below) |
| jaeger trace check via query API | real trace returned (`90406015d47d2ed96a60971293b61f27` + task trace) |
| `docker ps` after smoke teardown | zero `adaptive-agent-runtime*` containers |
| `mvn verify` (full repo, after changes) | **BUILD SUCCESS** (5:10 min; 0 failures, 0 errors; e2e suites included) |

Image pulls were unremarkable except `bitnami/kafka` (unavailable, see deviation) — no oversized
pulls worth recording beyond the app image size above.

## Smoke transcript (final run, trimmed only of duplicate compose progress lines)

```
$ ./scripts/smoke.sh
[smoke] starting the compose stack (docker compose up -d --wait)
 Network adaptive-agent-runtime_default Created
 Volume adaptive-agent-runtime_pgdata Created
 Container adaptive-agent-runtime-postgres-1  Healthy
 Container adaptive-agent-runtime-kafka-1     Healthy
 Container jaeger                              Healthy
 Container adaptive-agent-runtime-app-1        Healthy
[smoke] task_id: 9d813d27-8687-451d-a16f-28a136f2597d
[smoke] submitting task via POST http://localhost:8080/tasks
[smoke] submit accepted (HTTP 202): {"task_id":"9d813d27-8687-451d-a16f-28a136f2597d","status":"ROUTED"}
[smoke] following SSE http://localhost:8080/tasks/9d813d27-8687-451d-a16f-28a136f2597d/events (timeout 120s)
id:1
event:NODE_START
data:{"node": "solo-agent", "traceId": "003094d52f709c46cbf7a605ee111f0f"}

id:2
event:TASK_COMPLETED
data:{"reason": "\"adaptive agents\"", "traceId": "003094d52f709c46cbf7a605ee111f0f"}

[smoke] terminal SSE event: TASK_COMPLETED
[smoke] fetching final task state
[smoke] state: {"task_id":"9d813d27-8687-451d-a16f-28a136f2597d","status":"SUCCEEDED","topology":"SOLO","result":"\"adaptive agents\"","error":null}
[smoke] PASS — compose stack smoke succeeded (task 9d813d27-8687-451d-a16f-28a136f2597d)
[smoke] tearing the stack down (docker compose down)
 Network adaptive-agent-runtime_default Removed
SMOKE_EXIT=0 DURATION=24s
```

Hermetic proof points: model answers are the fixture's fixed text (`"adaptive agents"`), search tools
never left the docker network (`WORKLOAD_LIVE` unset), no `SPRING_AI_OPENAI_*` values set.

## QA failure scenarios (required by the task)

Missing `.env`:
```
$ mv .env .env.hidden-qa && ./scripts/smoke.sh
[smoke] FAIL: .env not found in /home/kirill/IdeaProjects/adaptive-agent-runtime — copy .env.example to .env and fill in the required values (cp .env.example .env)
QA_MISSING_ENV_EXIT=1
```

Empty required key:
```
$ sed -i 's/^RUNTIME_API_KEY=.*/RUNTIME_API_KEY=/' .env && ./scripts/smoke.sh  # (RUNTIME_API_KEY emptied)
[smoke] FAIL: required environment variable RUNTIME_API_KEY is missing or empty in .env
QA_EMPTY_KEY_EXIT=1
```

## Failed iterations (kept for the record)

1. Run #1: app container crash-looped `unhealthy` — `RoutingService` missing `ComplexityClassifier`
   (see fixture-gating disclosure). Exit 1 in 26 s.
2. Run #2: stack healthy, `POST /tasks` → HTTP 500 — schema never migrated (see spring-boot-flyway
   disclosure). Exit 1 in 24 s.
3. Jaeger 404 on OTLP export (double-appended path) — fixed via `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`.
4. smoke.sh trap flaw found in run #1: `STACK_UP=1` was set only after a successful `up --wait`, so a
   failed `up` left containers running; moved before the command (teardown now always runs).
