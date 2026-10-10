# TaskEnvelope Contract v1

This document outlines the protocol mappings for the `TaskEnvelope` contract across all supported ingresses.

## Core Schema
The core `TaskEnvelope` maps to JSON with standard snake_case fields.
* `task_id` (UUID, required)
* `task_type` (String, required - only "research" supported in v1)
* `prompt` (String, required)
* `breadth_hint` (int, default 1, range 1..5)
* `deadline_ms` (Long, optional, >0)
* `budget_usd` (BigDecimal, optional, >0)
* `forced_topology` (Topology Enum: SOLO, CHAIN, FAN_OUT, SUPERVISOR, optional)

*Note: The `Topology` enum is placed in the `core` module (rather than `topologies`) so it can be referenced universally by the envelope.*

## TWO SINGLE SOURCES OF TRUTH
The system honors **only two** single sources of truth, both located within the JSON body payload across **all three** ingresses:
1. `task_id` (The idempotency key and trace identifier)
2. `forced_topology`

**CRITICAL: NO header override mechanism exists on any ingress.** A header cannot override `task_id` or `forced_topology`.

## Ingress Auth Matrix
| Ingress | Endpoint / Surface | Auth Requirement | Notes |
|---------|-------------------|------------------|-------|
| REST | `/tasks/**` | `X-API-Key` required | Fail-closed: missing/invalid key or blank server key returns 401 problem+json. |
| MCP | `/mcp` | Auth-exempt | Streamable-HTTP on same servlet port; no API key required. |
| Kafka | `adaptive-tasks-*` | Local-only (broker auth) | No application-level auth header; secured at cluster/broker level. |

## Protocol Mappings

### 1. REST Mapping
| Element | Mapping |
|---------|---------|
| Headers | `X-API-Key` (AUTH ONLY) |
| Body | `TaskEnvelope` JSON |

*Note: There are no identity or topology-carrying headers.*

### 2. MCP Mapping
| Element | Mapping |
|---------|---------|
| Endpoint | POST/GET `/mcp` (streamable-http, same servlet port) |
| Headers | None (MCP is Auth-Exempt) |
| Transport / Protocol | Streamable HTTP (`spring.ai.mcp.server.protocol=STREAMABLE`) |
| Body / Tool Args | Mapped into `TaskEnvelope` (`task_id`, `prompt`, `breadth_hint`, `deadline_ms`, `budget_usd`, `forced_topology`) |

*Note: There are no identity or topology-carrying headers.*

The MCP server mounts at `/mcp` on the same servlet port as REST/SSE and exposes three tools over `TaskIngressService` and `TaskStore`:

1. `submit_task`
   - **Arguments:**
     - `task_id` (UUID, required): Client-supplied task id and idempotency key. Resubmitting an existing `task_id` returns current status without re-routing or re-executing.
     - `prompt` (String, required): Non-blank research prompt.
     - `breadth_hint` (Integer, optional, default: 1, range: 1..5): Breadth hint.
     - `deadline_ms` (Long, optional, >0): Deadline duration in milliseconds.
     - `budget_usd` (BigDecimal, optional, >0): Budget cap in USD.
     - `forced_topology` (Topology enum, optional: `SOLO`, `CHAIN`, `FAN_OUT`, `SUPERVISOR`): Forced topology override.
   - **Return Shape:**
     `{"task_id": "<uuid>", "status": "<status>"}`
   - **Behavior:** Validates envelope semantics via shared `EnvelopeValidator`. Dispatches asynchronously to execution engine. Immediate return with status at submission time.

2. `get_task`
   - **Arguments:**
     - `task_id` (UUID, required): The task id.
   - **Return Shape:**
     `{"task_id": "<uuid>", "status": "<status>", "topology": "<topology or null>", "result": "<raw json string or null>", "error": "<raw json string or null>"}`
   - **Behavior:** Fetches current task state. `result` and `error` are null while the task is in-flight.

3. `get_task_result`
   - **Arguments:**
     - `task_id` (UUID, required): The task id.
   - **Return Shape:**
     `{"task_id": "<uuid>", "status": "SUCCEEDED", "result": "<raw json string>"}`
   - **Behavior:** Fetches final result of a terminal `SUCCEEDED` task (including degraded partial results).

#### MCP Error Semantics
All client-side error conditions yield a **tool error result** (`isError=true` on `CallToolResult`), never an HTTP 500 or JSON-RPC protocol error:
- **Validation failures:** Violations of `EnvelopeValidator` (blank `prompt`, `breadth_hint` outside 1..5, non-positive `deadline_ms` or `budget_usd`, or unparseable `forced_topology` enum) surface as `isError=true` tool results citing the violated rule.
- **Unknown task ID:** In `get_task` or `get_task_result`, looking up an unrecorded `task_id` returns `isError=true` with `unknown task_id <uuid>`.
- **Non-terminal or non-SUCCEEDED task:** Calling `get_task_result` for a task still in flight or ended as `FAILED`, `DEADLINE_EXCEEDED`, or `BUDGET_EXCEEDED` returns `isError=true` detailing current status and error reason.

### 3. Kafka Mapping
| Element | Mapping |
|---------|---------|
| Headers | `X-Task-Id` (OPTIONAL, for tracing correlation only, NEVER identity) |
| Body | `TaskEnvelope` canonical JSON |

*Note: There are no identity or topology-carrying headers.*

Kafka ingress topics (enabled only when `runtime.kafka.enabled=true`):

* `adaptive-tasks-request` — consumed by group `adaptive-runtime`. The record body is the
  canonical `TaskEnvelope` JSON. An optional `X-Task-Id` record header may be present; it is
  logged as tracing correlation and never influences identity or topology.
* `adaptive-tasks-reply` — request/reply ACK. The listener replies IMMEDIATELY after
  submission (it never waits for task completion): `{"task_id": "<uuid>", "status":
  "<current status: ROUTED/RUNNING/…/existing status for duplicates>", "decision": <routing
  decision json or null>}`. Fetch the final result via `get_task` REST polling or the
  progress-topic tail (bench/README.md).
* `adaptive-tasks-progress` — LIVE TAIL of `progress_events`: frames
  `{"seq": <long>, "task_id": "<uuid>", "type": "<event type>", "payload": <payload json>}`,
  record key = `task_id` (per-task ordering follows the global `seq`). The topic carries only
  events that appear from app start onward (the publisher anchors its cursor at the current
  max `seq` on startup); historical replay belongs to the REST SSE endpoint with
  `Last-Event-ID`.
* `adaptive-tasks-request-dlt` — poison envelopes (malformed JSON, invalid enum, failed
  validation) are copied here verbatim for inspection; the requester simultaneously receives
  the error reply `{"task_id": null, "status": "INVALID", "error": "<reason>"}`.
