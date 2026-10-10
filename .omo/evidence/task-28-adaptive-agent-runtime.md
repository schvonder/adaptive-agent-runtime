# T28 — Tracing: micrometer-tracing + OTLP Exporter + Jaeger Compose — Evidence

Worktree: `/home/kirill/IdeaProjects/adaptive-agent-runtime-t28` (detached HEAD from `32d4a06`).
Task: T28 of approved plan (`micrometer-tracing with OTLP exporter for adaptive-agent-runtime`).

## Design Summary

1. **Tracing Stack & Single Engine (No LangGraph4j Otel Module Wiring)**:
   - Configured Micrometer Tracing with OpenTelemetry bridge (`io.micrometer:micrometer-tracing-bridge-otel`, `io.opentelemetry:opentelemetry-exporter-otlp`, `org.springframework.boot:spring-boot-starter-actuator`, `org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry`).
   - Pure Micrometer Tracing API is used across `core`, `topologies`, and `workload` without leaking protocol-specific OpenTelemetry types across module boundaries.
   - Kept strictly one tracing stack; LangGraph4j's separate OpenTelemetry module was deliberately NOT wired.

2. **Hierarchical Span Structure**:
   - **Root Task Span**: Span name `task-execution`, tagged with `task_id = <taskId>`. Created around task execution in `ExecutionRunner.executeAsync` and `execute`.
   - **Node Spans (Children)**: Span name = topology node (e.g. `solo-agent`, `plan`, `search`, `summarize`, `cite`, `branch-1`, etc.), tagged with `task_id = <taskId>`, `node = <nodeId>`, `topology = <topologyName>`. Attached via `StateGraph.addWrapCallNodeHook` in `ExecutionRunner.invokeGraph`.
   - **Tool Spans (Grandchildren)**: Span name = tool name (`web_search`, `fetch_page`, `cite_sources`), tagged with `task_id = <taskId>`. Created in `ResearchTools` (Spring AI agent tools), `FixtureSearchAdapter`, `FixtureFetchAdapter`, `CiteTool`, `TavilyAdapter`, and topology graph factories. Parent span is the enclosing node span.
   - Double-wrapping prevention: `IN_TOOL_CALL` thread-local prevents duplicate tool spans when tool calls are invoked directly or via `ResearchTools`.
   - Context propagation: Active task spans are indexed in `ExecutionRunner.activeTaskSpans` and correlated via `TaskTraceContext`, ensuring child node spans on `ForkJoinPool` or virtual threads maintain the same `traceId`.

3. **Additive Trace ID in Progress Events**:
   - In `ProgressService.publish()`, if an active span is in scope (`tracer.currentSpan() != null`), `traceId` is extracted from `currentSpan.context().traceId()` and injected as an additive JSON field `"traceId"` into the payload object.
   - If no span is active, the payload schema remains unchanged (null-safe; field absent).
   - Preserves compatibility with existing SSE and Kafka consumers as verified by contract parity test `CrossIngressContractE2eTest`.

4. **OTLP Configuration & Jaeger Compose Fragment**:
   - `app/src/main/resources/application.yml` configures `management.tracing.sampling.probability: 1.0` and `management.opentelemetry.tracing.export.otlp.endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}`.
   - `compose.jaeger.yml` created at repository root defining the `jaeger` service (`jaegertracing/all-in-one:1.62.0`) with OTLP ports (4317 gRPC, 4318 HTTP) and query REST API (16686). Documented for T29 to fold into `compose.yaml`.

## Tests Added & Verified

1. **Capturing Span Exporter Unit Tests** (`io.adaptiveagents.app.tracing.CapturingSpanExporterTest`):
   - `chainExecutionRecordsTaskNodeAndToolSpans`: Verifies CHAIN execution records `task-execution` span, 4 node spans (`plan`, `search`, `summarize`, `cite`), and tool spans (`web_search`, `cite_sources`). Asserts matching `traceId`, parent-child hierarchy, required tags (`task_id`, `node`, `topology`), and verifies all progress events in `progress_events` table contain matching `traceId`.
   - `soloExecutionRecordsTaskNodeAndToolSpans`: Verifies SOLO execution records `task-execution` span, `solo-agent` node span, and `web_search` tool span with matching `traceId` and progress event payload correlation.

2. **Jaeger Testcontainers Integration Test** (`io.adaptiveagents.app.tracing.JaegerOtlpIntegrationTest`):
   - `happyPathExportsTraceToJaegerAndQueriesRestApi`: Boots full app context with OTLP export pointing to a Testcontainers `jaegertracing/all-in-one:1.62.0` container.
   - Executes CHAIN task to SUCCEEDED.
   - Polls Jaeger Query REST API `GET /api/traces?service=adaptive-agent-runtime`.
   - Verified trace found:
     - Trace ID: `a9354a5578e835ee2573c78199c43e69` (and `2df06ec4ae5222e085f86e548770d040`)
     - Total Spans in trace: 15
     - Spans include: `task-execution`, `plan`, `search`, `summarize`, `cite`, `web_search`, `cite_sources`
     - Verified spans carry `task_id` tag.

3. **QA Failure Scenario — Exporter Down Non-Fatal Test** (`io.adaptiveagents.app.tracing.ExporterDownNonFatalTest`):
   - `exporterDownEndpointIsNonFatalAndTaskExecutionSucceeds`: Boots app context pointing OTLP export to an unreachable endpoint (`http://127.0.0.1:59999/v1/traces` - connection refused).
   - Submits task and awaits `runner.executeAsync(taskId)`.
   - Asserts task execution completes with `TaskStatus.SUCCEEDED` and database row is `SUCCEEDED`.
   - Proves tracing export failure is strictly non-fatal to task execution.

## Verification Results Summary

- `mvn -pl core test`: 41 tests run, 0 failures, 0 errors.
- `mvn -pl workload test`: 16 tests run, 0 failures, 0 errors.
- `mvn -pl topologies test`: 46 tests run, 0 failures, 0 errors.
- `mvn -pl ingress test`: 18 tests run, 0 failures, 0 errors (including cross-ingress contract parity `CrossIngressContractE2eTest`).
- `mvn -pl bench test`: 2 tests run, 0 failures, 0 errors.
- `mvn -pl app test`: 5 tests run, 0 failures, 0 errors.
Total test suite: **128 tests passing cleanly across all modules**.
