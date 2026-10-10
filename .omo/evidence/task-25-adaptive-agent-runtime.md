# T25 — Workload demo task set + fixtures — evidence

Repo: `/home/kirill/IdeaProjects/adaptive-agent-runtime`, branch `main`, base HEAD `48f66ac`.
Executor: delegated T25 prompt.

## Goal

Finish task T25 of the approved work plan:
1. Create `workload/src/main/resources/tasks/demo-tasks.json` with 12 research tasks adhering to the exact target distribution: 4 SOLO-band, 4 CHAIN-band, 2 FAN_OUT-band, 2 SUPERVISOR-band.
2. Record offline search and fetch fixtures per task under `fixtures/tasks/<task_id>/` and mirror them globally under `fixtures/search/` and `fixtures/pages/` so the real `FixtureSearchAdapter` and `FixtureFetchAdapter` load them with `WORKLOAD_LIVE` unset.
3. Document expected routing, scoring band rules, and the 13th QA contradiction edge case in `docs/TASKS.md`.
4. Create test suite `DemoTasksRoutingTest` in `topologies` module asserting:
   - 12/12 demo tasks route to their documented expected topology.
   - The contradicting-hints task lands in `Band.AMBIGUOUS` (and resolves to `Topology.CHAIN`).
   - Every task's recorded fixtures load through the real fixture adapter paths with non-empty results.
5. All affected modules (`workload`, `topologies`, `app`) build and pass tests cleanly.

## Grounding & Design Decisions

### 1. Classification Band Rules (`RuleScorer`)
From `core/src/main/java/io/adaptiveagents/core/classify/RuleScorer.java`:
- **Tokens Weight** (`prompt.length() / 4`):
  - $< 50$ tokens: $-10$
  - $50 \le \text{tokens} < 200$: $0$
  - $200 \le \text{tokens} \le 500$: $+10$
  - $> 500$ tokens: $+20$
- **Breadth Hint Weight** ($1 \dots 5$):
  - $\text{breadth} \le 1$: $-10$
  - $\text{breadth} = 2$: $+5$
  - $\text{breadth} = 3$: $+10$
  - $\text{breadth} \ge 4$: $+20$
- **Deadline Tightness**:
  - $\text{deadline\_ms} - \text{now\_ms} < 600{,}000\text{ ms}$: $+15$; absent: $0$
- **Band & Candidate Topology Rules**:
  - $\text{Score} \le 0 \rightarrow$ `Band.CLEAR_SIMPLE`, `candidateTopology = Topology.SOLO`
  - $1 \le \text{Score} < 20 \rightarrow$ `Band.AMBIGUOUS`, `candidateTopology = Topology.CHAIN` (LLM-leg resolution)
  - $\text{Score} \ge 20 \rightarrow$ `Band.CLEAR_COMPLEX`:
    - $\text{breadth} \ge 4 \rightarrow$ `Topology.SUPERVISOR`
    - $\text{breadth} < 4 \rightarrow$ `Topology.FAN_OUT`

All 12 demo task prompts and breadth hints were crafted to hit these exact boundaries through the real `RuleScorer` logic without any classifier modifications.

### 2. Fixture Loading Convention
From `workload/src/main/java/io/adaptiveagents/workload/tools/`:
- `FixtureSearchAdapter`: Loads `.json` files from `basePath` (default `fixtures/search/`) into a memory map keyed by `Slug.of(query)` (e.g. `single-agent-architecture.json`), with fallback to `ClassPathResource(basePath + slug + ".json")`.
- `FixtureFetchAdapter`: Loads `.json` files from `basePath` (default `fixtures/pages/`) containing `{"url":"...","content":"..."}` keyed by `Slug.of(url)`.
- Per-task directory convention:
  `workload/src/main/resources/fixtures/tasks/<task_id>/search/` and `.../pages/`.
  Each task's fixtures are loadable via `new FixtureSearchAdapter("fixtures/tasks/" + taskId + "/search")` and `new FixtureFetchAdapter("fixtures/tasks/" + taskId + "/pages")`.
  In addition, fixtures are mirrored under `fixtures/search/` and `fixtures/pages/` so the default application beans resolve without live network access.

## Task Distribution Matrix

| # | Task ID | Short Title | Breadth | Tokens | Score | Band | Expected Topology | Fixture Dir |
|---|---------|-------------|---------|--------|-------|------|-------------------|-------------|
| 1 | `00000000-0000-4000-8000-000000000001` | Single Agent Architecture Overview | 1 | 24 | -20 | `CLEAR_SIMPLE` | `SOLO` | `fixtures/tasks/00000000-0000-4000-8000-000000000001` |
| 2 | `00000000-0000-4000-8000-000000000002` | Agent Tool Calling Protocols | 1 | 27 | -20 | `CLEAR_SIMPLE` | `SOLO` | `fixtures/tasks/00000000-0000-4000-8000-000000000002` |
| 3 | `00000000-0000-4000-8000-000000000003` | Memory Persistence in Agent Systems | 1 | 79 | -10 | `CLEAR_SIMPLE` | `SOLO` | `fixtures/tasks/00000000-0000-4000-8000-000000000003` |
| 4 | `00000000-0000-4000-8000-000000000004` | Agent Guardrails and Safety Boundaries | 1 | 222 | 0 | `CLEAR_SIMPLE` | `SOLO` | `fixtures/tasks/00000000-0000-4000-8000-000000000004` |
| 5 | `00000000-0000-4000-8000-000000000005` | Sequential Verification Pipeline | 2 | 74 | +5 | `AMBIGUOUS` | `CHAIN` | `fixtures/tasks/00000000-0000-4000-8000-000000000005` |
| 6 | `00000000-0000-4000-8000-000000000006` | Stepwise Literature Synthesis | 3 | 77 | +10 | `AMBIGUOUS` | `CHAIN` | `fixtures/tasks/00000000-0000-4000-8000-000000000006` |
| 7 | `00000000-0000-4000-8000-000000000007` | Iterative Code Generation and Review | 2 | 201 | +15 | `AMBIGUOUS` | `CHAIN` | `fixtures/tasks/00000000-0000-4000-8000-000000000007` |
| 8 | `00000000-0000-4000-8000-000000000008` | Data Pipeline Diagnostics Chain | 2 | 71 | +5 | `AMBIGUOUS` | `CHAIN` | `fixtures/tasks/00000000-0000-4000-8000-000000000008` |
| 9 | `00000000-0000-4000-8000-000000000009` | Distributed Consensus Comparative Analysis | 3 | 212 | +20 | `CLEAR_COMPLEX` | `FAN_OUT` | `fixtures/tasks/00000000-0000-4000-8000-000000000009` |
| 10 | `00000000-0000-4000-8000-000000000010` | Multi-Perspective LLM Evaluation Framework | 2 | 537 | +25 | `CLEAR_COMPLEX` | `FAN_OUT` | `fixtures/tasks/00000000-0000-4000-8000-000000000010` |
| 11 | `00000000-0000-4000-8000-000000000011` | Autonomous Systems Governance Framework | 4 | 77 | +20 | `CLEAR_COMPLEX` | `SUPERVISOR` | `fixtures/tasks/00000000-0000-4000-8000-000000000011` |
| 12 | `00000000-0000-4000-8000-000000000012` | Enterprise Multi-Agent Orchestration Architecture | 5 | 206 | +30 | `CLEAR_COMPLEX` | `SUPERVISOR` | `fixtures/tasks/00000000-0000-4000-8000-000000000012` |
| 13 (QA) | `00000000-0000-4000-8000-000000000099` | Contradicting Hints Edge Case | 4 | 8 | +10 | `AMBIGUOUS` | `CHAIN` | `fixtures/tasks/00000000-0000-4000-8000-000000000099` |

- **QA Contradiction Case (Task 13)**: Pairs a short prompt ($<50$ tokens $\rightarrow -10$) with high breadth ($4 \rightarrow +20$). Net score is $10$ ($1 \dots 19$), routing to `Band.AMBIGUOUS`. The rationale reflects both: `"Short prompt (<50 tokens) [-10], Breadth >= 4 [+20]"`.

## Changed / Added Files

- `workload/src/main/resources/tasks/demo-tasks.json` — 12 demo research tasks with stable UUIDs, task_type research, prompt, breadth_hint.
- `workload/src/main/resources/fixtures/tasks/` — 13 per-task fixture directories containing `search/` and `pages/`.
- `workload/src/main/resources/fixtures/search/` and `pages/` — mirrored global fixtures for default adapter lookups.
- `docs/TASKS.md` — Complete documentation of classification band rules, 12-task distribution, and the QA contradiction edge case.
- `topologies/src/test/java/io/adaptiveagents/topologies/routing/DemoTasksRoutingTest.java` — Test suite verifying 12/12 task routing, the 13th AMBIGUOUS case, and fixture loading per task.
- `.omo/evidence/task-25-adaptive-agent-runtime.md` — This evidence ledger.

## Test Results

- `mvn -pl topologies -Dtest=DemoTasksRoutingTest test`:
  - Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 — **BUILD SUCCESS**
- `mvn -pl workload -am test`:
  - Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 — **BUILD SUCCESS**
- `mvn test -pl workload,topologies,app`:
  - Tests run: 30, Failures: 0, Errors: 0, Skipped: 0 — **BUILD SUCCESS**
  - Breakdown: `workload` (6), `topologies` (23), `app` (1).
