# T21 evidence — REST ingress + SSE + auth (adaptive-agent-runtime)

## Goal

Implement todo T21: the `ingress` module's REST surface on webmvc — `POST /tasks` (202 new /
200 duplicate), `GET /tasks/{id}`, `GET /tasks/{id}/events` SSE with `Last-Event-ID` replay +
live tail + terminal completion, and a fail-closed `X-API-Key` filter against `runtime.api-key`
with a seam for the T23 MCP exemption. All error bodies RFC 7807. webmvc only (no webflux), no
spring-security, no new dependencies beyond the module wiring.

## Design decisions

### 200/202 dedup semantics
`TaskIngressService.submit` calls `taskStore.submitOrGet` first. `created == true` →
`routingService.route(envelope, now)` (which internally resolves the just-inserted SUBMITTED row
and persists the decision + SUBMITTED→ROUTED) then fire-and-forget
`executionRunner.executeAsync(taskId)` with a `whenComplete` info/warn log. `created == false` →
nothing: the existing record is returned as-is — no re-route, no re-execution (T18 dedup
semantics mirrored; a duplicate in any state resolves to existing state). Controller maps
created → 202, duplicate → 200, both with body `{"task_id","status"}`. Dispatch rejection after
route is caught and logged (error); the HTTP request never fails after routing — a
routed-but-never-executed row stays ROUTED, an acceptable transient (documented in the service
javadoc; startup rescan/sweep keep the runtime convergent).

### SSE tail loop + emitter lifecycle
`TaskEventStreamer` (one component, shared 2-thread daemon `ScheduledExecutorService` — never a
caller thread): `scheduleWithFixedDelay(tick, initialDelay=0, delay=150ms)`. First tick replays
`progressService.sinceSeq(taskId, since)` — `since` = `Last-Event-ID` header when present, else
0 (full history) — and each tick tails new rows. Every event is one SSE frame: `id: <seq>`,
`event: <type>`, `data: <payload json>` (data sent with `MediaType.APPLICATION_JSON`). The
first terminal event (TASK_COMPLETED / TASK_FAILED / TASK_DEADLINE_EXCEEDED /
TASK_BUDGET_EXCEEDED) is sent, then `emitter.complete()` — the stream always ends on the task's
terminal event. Tick body never rethrows (fixed-delay suppression hazard) and double-completion
is guarded by an AtomicBoolean CAS. Cleanup: `onCompletion`/`onTimeout`/`onError` all cancel the
polling future — no leaked emitter slots. Emitter timeout = `0L` (no container timeout,
documented): completion is driven by the terminal event or client disconnect. Early send/
complete before MVC initializes the emitter are buffered by `SseEmitter` itself, so the
zero-delay first tick is safe. Unknown task 404s before the stream opens (controller checks the
store before creating the emitter). Non-numeric `Last-Event-ID` → 400 problem-details.

### Auth filter — fail-closed + T23 seam
`ApiKeyAuthFilter extends OncePerRequestFilter` (@Component, no security dependency — the plain
filter is the whole auth story; no basic auth/OAuth/multi-tenant anything). Guards the
table-driven `GUARDED_PREFIXES = ["/tasks"]` (segment-aware: exact path or sub-path, not
`/tasksfoo`); a later ingress (T23 MCP) exempts itself by mounting under a prefix absent from
this list (e.g. `/mcp/**`) — adding a guarded surface is a one-line change. Fail-closed: blank/
missing `runtime.api-key` property → every guarded request 401; missing or mismatched
`X-API-Key` header → 401. Comparison is constant-time (`MessageDigest.isEqual`). The 401 body is
written directly by the filter (chosen single path — not delegated to the advice) as
`application/problem+json` (Spring `ProblemDetail`). The header is AUTH ONLY — no identity or
topology headers exist anywhere (docs/ENVELOPE.md).

### Problem-details (RFC 7807) mapping table
| Case | Status | Source | Body |
|---|---|---|---|
| Missing/wrong `X-API-Key`, unconfigured key | 401 | `ApiKeyAuthFilter` (writes directly) | problem+json |
| Semantic validation failure (task_type ≠ research, blank prompt, breadth_hint ∉ 1..5, deadline_ms ≤ 0, budget_usd ≤ 0, non-numeric Last-Event-ID) | 400 | `TaskValidationException` → `ApiExceptionHandler` | problem+json |
| Jackson bind failure (malformed JSON, bad UUID, unknown `forced_topology` enum value — e.g. "BOGUS") | 400 | `HttpMessageNotReadableException` → advice (root cause message) | problem+json |
| Malformed path variable (non-UUID id) | 400 | `MethodArgumentTypeMismatchException` → advice | problem+json |
| Unknown task (GET /tasks/{id}, GET /tasks/{id}/events) | 404 | `TaskNotFoundException` → advice | problem+json |

### Validation rules (controller, manual — Jackson covers structure)
`task_id` required; `task_type` must equal `"research"` (only v1 type); `prompt` non-blank;
`breadth_hint` 1..5 (Jackson default 1 makes absence valid); `deadline_ms` null-or->0;
`budget_usd` null-or->0 (signum). GET /tasks/{id} always includes `result`/`error` keys — null
when absent (documented choice); values are the raw persisted JSON strings.

### Test wiring deviation (documented)
Boot 4.1.1 moved `@AutoConfigureMockMvc` out of `spring-boot-test-autoconfigure` into the
separate `spring-boot-starter-webmvc-test` artifact. Adding it would violate the hard
"no deps beyond the step-1 list" constraint, so `RestIngressIntegrationTest` builds MockMvc
manually: `MockMvcBuilders.webAppContextSetup(context).addFilters(apiKeyAuthFilter).build()` —
same surfaces (real controllers/advice + the real filter bean in the chain), zero new
dependencies.

### App e2e regression
Ingress now brings `spring-boot-starter-web` transitively into app, but every app test path
already forces non-web (`--spring.main.web-application-type=none` in SpawnedApp args /
ApplicationTest property), so no port binding occurs. Full-root verify ran the failsafe e2e
(`ResumeAfterKillE2eTest` 2/2, `SpawnedAppKillRestartTest` 1/1) green — per step 7, app tests
were NOT touched.

## Changed files
- `ingress/pom.xml` — added compile deps: `core`, `topologies`, `spring-boot-starter-web`; test deps: testcontainers `postgresql` + `junit-jupiter` (mirroring topologies/pom.xml; postgres driver arrives transitively at runtime scope from topologies, mirroring the existing convention). Nothing else.
- `ingress/src/main/java/io/adaptiveagents/ingress/rest/TaskIngressService.java` — POST pipeline orchestration (submit → route → fire-and-forget dispatch; dedup short-circuit).
- `ingress/src/main/java/io/adaptiveagents/ingress/rest/TaskController.java` — POST /tasks (202/200 + validation), GET /tasks/{id}, GET /tasks/{id}/events (SSE).
- `ingress/src/main/java/io/adaptiveagents/ingress/rest/TaskEventStreamer.java` — SSE replay + live tail + terminal completion + cleanup.
- `ingress/src/main/java/io/adaptiveagents/ingress/rest/ApiKeyAuthFilter.java` — fail-closed X-API-key filter, T23 seam, direct 401 problem+json.
- `ingress/src/main/java/io/adaptiveagents/ingress/rest/ApiExceptionHandler.java` — RFC 7807 mapping for 400/404.
- `ingress/src/main/java/io/adaptiveagents/ingress/rest/TaskValidationException.java`, `TaskNotFoundException.java` — mapped exception types.
- `ingress/src/test/java/io/adaptiveagents/ingress/rest/RestIngressIntegrationTest.java` — new integration test (inline FixedTextChatModel + @Primary SearchTool lambda stub).

No other module's sources were modified.

## Test list (new)
`io.adaptiveagents.ingress.rest.RestIngressIntegrationTest` (Testcontainers PG 17-alpine,
MockMvc, stub-classifier profile, `runtime.api-key=test-key`):
1. `authMissingOrWrongKeyIsRejected` — POST without key 401 problem+json; wrong key 401; GET events without key 401.
2. `submitLifecycleWithDedupAndUnknownTask404` — POST 202 (+task_id/status); duplicate POST 200 same task_id; poll GET until SUCCEEDED (topology CHAIN, result non-null, error null); unknown id GET 404 problem+json.
3. `sseOrderedWithReplay` — CHAIN task; full stream: NODE_START plan/search/summarize/cite in seq order then TASK_COMPLETED last; reconnect with `Last-Event-ID` = search event's seq → exactly summarize, cite, TASK_COMPLETED replay, in order.
4. `invalidTopologyAndTaskTypeAre400` — `forced_topology:"BOGUS"` → 400 problem+json (Jackson enum failure mapped); `task_type:"summarize"` → 400 problem+json.
5. `sseUnknownTaskIs404` — GET /tasks/{random}/events with key → 404 problem+json.

SSE assertions are on the collected frame list after the emitter completes on the terminal
event (deterministic; DB polled to SUCCEEDED before opening the stream).

## Commands + results
- `mvn -B -pl ingress -am test` → BUILD SUCCESS (core 41, workload 16, topologies 43, ingress 6 tests, 0 failures).
- `mvn -B clean verify` (full root) → BUILD SUCCESS, exit 0. Last lines:

```
[INFO] Reactor Summary for adaptive-agent-runtime 0.1.0-SNAPSHOT:
[INFO]
[INFO] adaptive-agent-runtime ............................. SUCCESS [  0.113 s]
[INFO] spike .............................................. SUCCESS [ 13.332 s]
[INFO] core ............................................... SUCCESS [ 21.773 s]
[INFO] workload ........................................... SUCCESS [  2.107 s]
[INFO] topologies ......................................... SUCCESS [01:13 min]
[INFO] ingress ............................................ SUCCESS [ 10.234 s]
[INFO] bench .............................................. SUCCESS [  0.885 s]
[INFO] app ................................................ SUCCESS [ 45.917 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:48 min
[INFO] Finished at: 2026-10-10T16:22:24 +0300
```

(app module includes surefire ApplicationTest + failsafe e2e group: ResumeAfterKillE2eTest 2/2, SpawnedAppKillRestartTest 1/1.)

## Commit
Hash: `b3b635f`
`git log --oneline | head -3`:
```
b3b635f feat(ingress): REST+SSE ingress with API key auth
0b09120 feat(core): deadline guard + zombie-free sweep
0eb7179 feat(core): budget guard + degradation matrix
```

Note: the repo's sole branch is `main` (no `master` exists); HEAD before this task was `0b09120`
as expected. Only the nine intended files were staged — no `.omo/`, no `target/`. No push.
