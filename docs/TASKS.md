# Workload Tasks & Routing Matrix

This document defines the 12 benchmark demo research tasks (`workload/src/main/resources/tasks/demo-tasks.json`) along with their expected routing behavior under the runtime's complexity classifier and the associated offline recorded fixtures. It also documents the 13th QA contradiction edge case.

## Classification Band Rules

The runtime evaluates incoming task envelopes deterministically using `RuleScorer`:

### Scoring Formula
$$\text{Score} = \text{Tokens Weight} + \text{Breadth Hint Weight} + \text{Deadline Tightness Weight}$$

1. **Tokens Weight** ($\text{length} / 4$):
   - $< 50$ tokens ($< 200$ characters): $-10$
   - $50 \le \text{tokens} < 200$ ($200 \dots 799$ characters): $0$
   - $200 \le \text{tokens} \le 500$ ($800 \dots 2000$ characters): $+10$
   - $> 500$ tokens ($> 2000$ characters): $+20$

2. **Breadth Hint Weight** ($1 \dots 5$):
   - $\text{breadth} \le 1$: $-10$
   - $\text{breadth} = 2$: $+5$
   - $\text{breadth} = 3$: $+10$
   - $\text{breadth} \ge 4$: $+20$

3. **Deadline Tightness Weight**:
   - $\text{deadline\_ms} - \text{now\_ms} < 600{,}000\text{ ms}$ ($< 10\text{ minutes}$): $+15$
   - Absent / non-tight: $0$

### Band & Candidate Topology Thresholds
- **Score $\le 0$** $\rightarrow$ `Band.CLEAR_SIMPLE` $\rightarrow$ `Topology.SOLO`
- **Score $1 \dots 19$** $\rightarrow$ `Band.AMBIGUOUS` $\rightarrow$ `Topology.CHAIN` (LLM-leg classification / rule fallback)
- **Score $\ge 20$** $\rightarrow$ `Band.CLEAR_COMPLEX`:
  - $\text{breadth} \ge 4$ $\rightarrow$ `Topology.SUPERVISOR`
  - $\text{breadth} < 4$ $\rightarrow$ `Topology.FAN_OUT`

---

## 12-Task Demo Set Distribution

The 12 tasks in `demo-tasks.json` strictly adhere to the target distribution:
- **4 SOLO-band** (Score $\le 0$, `CLEAR_SIMPLE`)
- **4 CHAIN-band** (Score $1 \dots 19$, `AMBIGUOUS`)
- **2 FAN_OUT-band** (Score $\ge 20$, $\text{breadth} < 4$, `CLEAR_COMPLEX`)
- **2 SUPERVISOR-band** (Score $\ge 20$, $\text{breadth} \ge 4$, `CLEAR_COMPLEX`)

| # | Task ID | Short Title | Breadth | Tokens | Score | Band | Expected Topology | Fixture Dir |
|---|---------|-------------|---------|--------|-------|------|-------------------|-------------|
| 1 | `00000000-0000-4000-8000-000000000001` | Single Agent Architecture Overview | 1 (-10) | 24 (-10) | -20 | `CLEAR_SIMPLE` | `SOLO` | `fixtures/tasks/00000000-0000-4000-8000-000000000001` |
| 2 | `00000000-0000-4000-8000-000000000002` | Agent Tool Calling Protocols | 1 (-10) | 27 (-10) | -20 | `CLEAR_SIMPLE` | `SOLO` | `fixtures/tasks/00000000-0000-4000-8000-000000000002` |
| 3 | `00000000-0000-4000-8000-000000000003` | Memory Persistence in Agent Systems | 1 (-10) | 79 (0) | -10 | `CLEAR_SIMPLE` | `SOLO` | `fixtures/tasks/00000000-0000-4000-8000-000000000003` |
| 4 | `00000000-0000-4000-8000-000000000004` | Agent Guardrails and Safety Boundaries | 1 (-10) | 222 (+10) | 0 | `CLEAR_SIMPLE` | `SOLO` | `fixtures/tasks/00000000-0000-4000-8000-000000000004` |
| 5 | `00000000-0000-4000-8000-000000000005` | Sequential Verification Pipeline | 2 (+5) | 74 (0) | +5 | `AMBIGUOUS` | `CHAIN` | `fixtures/tasks/00000000-0000-4000-8000-000000000005` |
| 6 | `00000000-0000-4000-8000-000000000006` | Stepwise Literature Synthesis | 3 (+10) | 77 (0) | +10 | `AMBIGUOUS` | `CHAIN` | `fixtures/tasks/00000000-0000-4000-8000-000000000006` |
| 7 | `00000000-0000-4000-8000-000000000007` | Iterative Code Generation and Review | 2 (+5) | 201 (+10) | +15 | `AMBIGUOUS` | `CHAIN` | `fixtures/tasks/00000000-0000-4000-8000-000000000007` |
| 8 | `00000000-0000-4000-8000-000000000008` | Data Pipeline Diagnostics Chain | 2 (+5) | 71 (0) | +5 | `AMBIGUOUS` | `CHAIN` | `fixtures/tasks/00000000-0000-4000-8000-000000000008` |
| 9 | `00000000-0000-4000-8000-000000000009` | Distributed Consensus Comparative Analysis | 3 (+10) | 212 (+10) | +20 | `CLEAR_COMPLEX` | `FAN_OUT` | `fixtures/tasks/00000000-0000-4000-8000-000000000009` |
| 10 | `00000000-0000-4000-8000-000000000010` | Multi-Perspective LLM Evaluation Framework | 2 (+5) | 537 (+20) | +25 | `CLEAR_COMPLEX` | `FAN_OUT` | `fixtures/tasks/00000000-0000-4000-8000-000000000010` |
| 11 | `00000000-0000-4000-8000-000000000011` | Autonomous Systems Governance Framework | 4 (+20) | 77 (0) | +20 | `CLEAR_COMPLEX` | `SUPERVISOR` | `fixtures/tasks/00000000-0000-4000-8000-000000000011` |
| 12 | `00000000-0000-4000-8000-000000000012` | Enterprise Multi-Agent Orchestration Architecture | 5 (+20) | 206 (+10) | +30 | `CLEAR_COMPLEX` | `SUPERVISOR` | `fixtures/tasks/00000000-0000-4000-8000-000000000012` |

---

## QA Contradicting-Hints Case (Task 13)

To prove that conflicting complexity indicators do not misroute silently, Task 13 pairs a trivial query with high breadth:

| Task ID | Short Title | Breadth | Tokens | Score | Band | Expected Topology | Fixture Dir |
|---------|-------------|---------|--------|-------|------|-------------------|-------------|
| `00000000-0000-4000-8000-000000000099` | Contradicting Hints Edge Case | 4 (+20) | 8 (-10) | +10 | `AMBIGUOUS` | `CHAIN` | `fixtures/tasks/00000000-0000-4000-8000-000000000099` |

- **Contradiction explanation**: The prompt is very short (`"Briefly define what an agent is."`, 33 chars / 8 tokens, score $-10$), implying a simple lookup. However, the client requests $\text{breadth} = 4$ ($+20$), implying a wide comparative search.
- **Outcome**: The opposing signals yield a net score of $+10$, which falls within $1 \dots 19$, routing to `Band.AMBIGUOUS`. The rule rationale explicitly captures both opposing weights: `"Short prompt (<50 tokens) [-10], Breadth >= 4 [+20]"`. In production and test doubles, ambiguous tasks route through the LLM classification leg (defaulting or falling back to `Topology.CHAIN`).

---

## Fixture Directory Structure

Each task has an offline recorded fixture directory located under `workload/src/main/resources/fixtures/tasks/<task_id>/`:
- `search/`: Contains JSON arrays of `SearchResult` (`[{"title":"...","url":"...","snippet":"..."}]`) keyed by query slug.
- `pages/`: Contains JSON objects of `PageFixture` (`{"url":"...","content":"..."}`) keyed by URL slug.

Global fallbacks for queries and pages are also mirrored under `fixtures/search/` and `fixtures/pages/` to allow resolution through the default `FixtureSearchAdapter` and `FixtureFetchAdapter` instances without live network access.
