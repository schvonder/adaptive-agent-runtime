# T23 — MCP streamable-http ingress — evidence

Repo: `/home/kirill/IdeaProjects/adaptive-agent-runtime`, branch `main`, base HEAD `42cc8cb`.
Executor: delegated T23 prompt.

## Goal

Finish task T23 (MCP streamable-http ingress) of the approved work plan:
1. Streamable-HTTP MCP server mounted at `/mcp` on the same servlet port as REST/SSE (`spring.ai.mcp.server.protocol=STREAMABLE`).
2. Three MCP tools exposed:
   - `submit_task(task_id, prompt, breadth_hint?, deadline_ms?, budget_usd?, forced_topology?) -> {task_id, status}`
   - `get_task(task_id) -> {task_id, status, topology, result, error}`
   - `get_task_result(task_id) -> {task_id, status, result}`
3. Contract documentation in `docs/ENVELOPE.md` with explicit tool schemas, error semantics, and auth matrix.
4. Green end-to-end integration test suite (`McpIngressIntegrationTest`) using Spring AI MCP client starter in test scope.
5. Verification of tool-error semantics (`isError=true` vs JSON-RPC protocol error) and auth coexistence (`/mcp` auth-exempt, `/tasks` 401 without `X-API-Key`).
6. All ingress and app tests green.

## Design Decisions & Findings

- **Tool Error Semantics (Empirical Finding)**:
  In Spring AI 2.0.1 (`org.springframework.ai:spring-ai-mcp:2.0.1`), `McpToolUtils` wraps tool execution in a try-catch block catching `java.lang.Exception`. When `@Tool` methods throw exceptions (such as `TaskValidationException` from `EnvelopeValidator` or `McpTaskNotFoundException` from `McpTaskTools`), `MethodToolCallback` rethrows them wrapped in `ToolExecutionException`. `McpToolUtils` catches the exception and constructs a `CallToolResult` with `isError=true` containing the exception message in `TextContent`. Schema validation failures (e.g., unrecognized enum for `forced_topology`) also produce `CallToolResult` with `isError=true` on the client. Neither case surfaces as a JSON-RPC protocol error (`-32603` or similar) to the caller.
- **Shared Envelope Validation**:
  `TaskValidationException` was relocated to `io.adaptiveagents.ingress.common` and `EnvelopeValidator` was created as the single semantic validation authority for v1 `TaskEnvelope`. Both `TaskController` (REST) and `McpTaskTools` (MCP) validate through `EnvelopeValidator.validate(envelope)`.
- **Dependency Cycle Resolution**:
  `SoloTopologyConfig` builds `ChatClient` using `ChatClient.Builder`, which triggers `ToolCallingAutoConfiguration` to resolve all `ToolCallbackProvider` beans. `McpToolConfig` registers `McpTaskTools` as a `ToolCallbackProvider`, while `McpTaskTools` depends on `TaskIngressService`, which depends on `ExecutionRunner` and the topology graph factories. This circular dependency is broken cleanly by injecting `TaskIngressService` lazily (`@Lazy`) into `McpTaskTools`.
- **Integration Test Transport Configuration**:
  With `webEnvironment = RANDOM_PORT`, `local.server.port` is allocated only after Tomcat starts in `ServletWebServerApplicationContext.finishRefresh()`. By disabling eager tool callback federation (`spring.ai.mcp.client.toolcallback.enabled=false`) and configuring test MCP client beans as lazy via a test `BeanFactoryPostProcessor`, client transports evaluate `${local.server.port}` dynamically when first invoked by test methods.
- **Auth Matrix & Coexistence**:
  `ApiKeyAuthFilter` guards only path prefixes in `GUARDED_PREFIXES = List.of("/tasks")`. The MCP server mounts at `/mcp`, which is exempt from `ApiKeyAuthFilter`. The integration test asserts that raw HTTP GET to `/mcp` returns non-401 (HTTP 200/SSE/400 streamable handshake) and MCP client requests execute with no `X-API-Key`, whereas raw POST to `/tasks` without `X-API-Key` returns 401 Unauthorized (`application/problem+json`).

## Changed Files

- `docs/ENVELOPE.md` — Added Ingress Auth Matrix and concrete MCP Mapping section detailing endpoints, tool arguments, return shapes, and error semantics.
- `ingress/pom.xml` — Added `spring-ai-starter-mcp-server-webmvc` (compile) and `spring-ai-starter-mcp-client` (test scope).
- `app/src/main/resources/application.yml` — Configured `spring.ai.mcp.server` with name `adaptive-agent-runtime`, version `0.1.0`, and protocol `STREAMABLE`.
- `ingress/src/main/java/io/adaptiveagents/ingress/common/TaskValidationException.java` (new) — Moved from `rest` package to `common` package.
- `ingress/src/main/java/io/adaptiveagents/ingress/common/EnvelopeValidator.java` (new) — Shared semantic envelope validation truth.
- `ingress/src/main/java/io/adaptiveagents/ingress/mcp/McpTaskTools.java` (new) — `submit_task`, `get_task`, `get_task_result` tool definitions over `TaskIngressService` and `TaskStore`.
- `ingress/src/main/java/io/adaptiveagents/ingress/mcp/McpTaskNotFoundException.java` (new) — Runtime lookup exception for unknown task ID or non-terminal task.
- `ingress/src/main/java/io/adaptiveagents/ingress/mcp/McpToolConfig.java` (new) — `ToolCallbackProvider` bean registration.
- `ingress/src/main/java/io/adaptiveagents/ingress/rest/TaskController.java` — Delegates envelope validation to `EnvelopeValidator`.
- `ingress/src/main/java/io/adaptiveagents/ingress/rest/ApiExceptionHandler.java` — Imports `TaskValidationException` from `common` package.
- `ingress/src/main/java/io/adaptiveagents/ingress/rest/TaskValidationException.java` — Deleted (replaced by `common.TaskValidationException`).
- `ingress/src/test/java/io/adaptiveagents/ingress/mcp/McpIngressIntegrationTest.java` (new) — Testcontainers PostgreSQL + random-port streamable-http MCP test suite.
- `.omo/evidence/task-23-adaptive-agent-runtime.md` (new) — This evidence file.

## Tests Added & Verified

`io.adaptiveagents.ingress.mcp.McpIngressIntegrationTest` (5 test methods):
1. `fullRoundtripSubmitAndPoll` — Calls `submit_task` with `forced_topology=CHAIN` → receives `{task_id, status}`, polls `get_task` until `SUCCEEDED` (checks `topology=CHAIN`, non-empty result, null error), calls `get_task_result` for final payload. Also verifies early `get_task_result` before completion returns tool error `task is not finished yet`.
2. `idempotentDuplicateSubmitDoesNotReexecute` — Resubmits identical `task_id` → receives identical `task_id`, asserts exactly 1 database row in `tasks`.
3. `coexistenceAndAuthExemption` — Verifies raw GET `/mcp` returns != 401, raw POST `/tasks` returns 401 without `X-API-Key`, and MCP client completes tool invocation without `X-API-Key`.
4. `unknownTaskIdReturnsToolErrorResult` — Calls `get_task` and `get_task_result` with random unrecorded UUID → returns `CallToolResult` with `isError=true` containing `"unknown task_id"`.
5. `invalidArgumentsYieldToolErrorResult` — Verifies `breadth_hint=9` returns `isError=true` citing `"breadth_hint must be between 1 and 5"`, blank prompt returns `isError=true` citing `"prompt must not be blank"`, and invalid enum for `forced_topology` returns `isError=true`.

## Test Execution Results

- `mvn -pl ingress -Dtest=McpIngressIntegrationTest test`
  - Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 — **BUILD SUCCESS**
- `mvn -pl ingress test`
  - Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 — **BUILD SUCCESS**
  - Includes `McpIngressIntegrationTest` (5), `RestIngressIntegrationTest` (5), `KafkaIngressIntegrationTest` (4), `MarkerTest` (1).
- `mvn -pl app test`
  - Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 — **BUILD SUCCESS**
  - Verifies `ApplicationTest` boots and loads context with `application.yml` streamable MCP configuration.

Exit codes for all maven test invocations: **0**.
