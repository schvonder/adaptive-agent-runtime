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
| Headers | None (MCP is Auth-Exempt) |
| Body / Tool Args | Mapped identically into `TaskEnvelope` JSON (`task_id`, `prompt`, `breadth_hint`, `deadline_ms`, `budget_usd`, `forced_topology`) |

*Note: There are no identity or topology-carrying headers.*

### 3. Kafka Mapping
| Element | Mapping |
|---------|---------|
| Headers | `X-Task-Id` (OPTIONAL, for tracing correlation only, NEVER identity) |
| Body | `TaskEnvelope` canonical JSON |

*Note: There are no identity or topology-carrying headers.*
