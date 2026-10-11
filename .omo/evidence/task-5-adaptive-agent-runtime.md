# T5 — TaskEnvelope contract v1 + per-protocol mapping spec (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit 6019b1f); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T5: `core` record `TaskEnvelope(taskId UUID, taskType "research", prompt, breadthHint int, deadlineMs Long, budgetUsd BigDecimal, forcedTopology enum null)` + JSON Schema resource `envelope-v1.schema.json` + `docs/ENVELOPE.md` with REST/MCP/Kafka mapping tables. Two single sources of truth, no identity/topology-carrying headers: `taskId` (JSON body canonical on all ingresses, THE idempotency key) and `forcedTopology` (envelope body field only).

Commit `6019b1f feat(core): TaskEnvelope v1 contract + protocol mapping doc` (2026-10-02):

```
 core/pom.xml                                       |  10 ++
 .../core/envelope/TaskEnvelope.java                |  38 +++++
 .../io/adaptiveagents/core/model/Topology.java     |   8 +
 core/src/main/resources/envelope-v1.schema.json    |  37 ++++++
 .../core/envelope/TaskEnvelopeTest.java            | 176 ++++++++++++++++++
 docs/ENVELOPE.md                                   |  48 ++++++
 6 files changed, 317 insertions(+)
```

Both artifacts present at HEAD: `core/src/main/resources/envelope-v1.schema.json`, `docs/ENVELOPE.md` (111 lines today — grown by later auth-matrix/MCP sections in T23).

## Acceptance re-executed today (post-hoc)

`mvn -pl core test` at HEAD (transcript `/tmp/opencode/aar/03-core-test.log`):

```
$ mvn -pl core test
[INFO] Running io.adaptiveagents.core.envelope.TaskEnvelopeTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.124 s -- in io.adaptiveagents.core.envelope.TaskEnvelopeTest
...
[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

TaskEnvelopeTest methods (surefire report): `jacksonRoundtripWithSnakeCaseAndDefaults`, `schemaValidationGreenPath`, `schemaValidationMissingTaskId`, `schemaValidationEmptyPrompt`, `schemaValidationBadTaskType`, `schemaValidationBadBreadthHint`, `schemaValidationNegativeBudget`, `schemaValidationInvalidTopology`, `ignoreUnknownFieldsOnDeserialization`.

Doc existence check:

```
$ ls docs/ENVELOPE.md core/src/main/resources/envelope-v1.schema.json
core/src/main/resources/envelope-v1.schema.json
docs/ENVELOPE.md
```

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| Unit test: JSON roundtrip + schema validation of valid/invalid envelopes (green/red) | `TaskEnvelopeTest` 9/9 green: roundtrip = `jacksonRoundtripWithSnakeCaseAndDefaults`; green = `schemaValidationGreenPath`; red = 6 negative cases incl. `schemaValidationMissingTaskId` |
| `docs/ENVELOPE.md` exists with all three mapping tables | `ls` output above; 111-line file containing REST/Kafka/MCP mapping sections (MCP section added T23) |
| QA happy: valid envelope validates | `schemaValidationGreenPath` |
| QA failure: missing taskId → schema validation error (test asserts) | `schemaValidationMissingTaskId` |
| No protocol-specific/identity-carrying headers in contract | Envelope record has no protocol fields; enforced downstream by T22/T24 parity tests (see their evidence) |

## Not re-runnable post-hoc

Nothing — pure unit/schema acceptance, fully re-run today.
