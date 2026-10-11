# T22 — Kafka ingress (request/reply + progress topic) — evidence

Repo: `/home/kirill/IdeaProjects/adaptive-agent-runtime`, branch `main`, base HEAD `b3b635f`.
Executor: delegated T22 prompt (`.omo/evidence/task-22-prompts/t22-prompt.md`).

## Goal

Kafka ingress behind `runtime.kafka.enabled`: request/reply on `adaptive-tasks-request` →
`adaptive-tasks-reply` with an IMMEDIATE post-submission ACK `{task_id, status, decision}`,
poison envelopes → `INVALID` error reply + DLT copy, `@Scheduled` outbox tail publishing
`progress_events` to `adaptive-tasks-progress` (live tail), ENVELOPE + bench docs, and a
Testcontainers (PG 17 + KRaft Kafka) integration test proving all five scenarios. Full root
`mvn -B clean verify` exit 0, one commit.

## Design decisions

- **Gating property** — everything Kafka lives in one `@Configuration`
  (`KafkaIngressConfig`) annotated `@ConditionalOnProperty(name="runtime.kafka.enabled",
  havingValue="true")` (no `matchIfMissing` → OFF by default). The listener beans are
  registered via `@Bean` methods (NOT `@Component`) so classpath component scan can never
  pick them up unconditionally. App e2e (SpawnedApp, no Kafka) stayed green — proof.
- **Immediate-ACK shape** — `@KafkaListener(adaptive-tasks-request, groupId=
  adaptive-runtime)` + `@SendTo(adaptive-tasks-reply)` (spring-messaging `@SendTo`; spring-kafka
  4.x dropped its own annotation). The listener calls `TaskIngressService.submit` (fire-and-forget
  dispatch) and returns the ACK built from the returned record: current status + `tasks.decision`
  JSON (or null). It NEVER blocks on task completion. No long reply timeout needed — the default
  short template timeout matches this pattern; the replying consumer in tests polls with 30s.
- **Poison → error reply + DLT** (the plan's allowed alternative to DLT-only): malformed JSON /
  unbindable enum-UUID / failed semantic validation (validation mirrors `TaskController` rules)
  → reply `{"task_id":null,"status":"INVALID","error":reason}` AND the raw record (key, value,
  headers) copied to `adaptive-tasks-request-dlt`. Infra failures during submission reuse the
  error-reply shape (no DLT — the record was valid). Listener survives by construction: every
  path returns a String reply.
- **Live-tail contract + latestSeq initialization** — `ProgressTopicPublisher` anchors its
  in-memory cursor to `ProgressService.latestSeq()` (`SELECT COALESCE(MAX(seq),0)`) on the FIRST
  scheduled tick (not `@PostConstruct`) so the anchor provably runs after Flyway migration during
  context startup, then relays `sinceSeqGlobally(lastSeq)` in global seq order with record key =
  `task_id` (per-task ordering via shared key + idempotent producer). The topic carries events
  from app start onward only; replay belongs to REST SSE + `Last-Event-ID`. Tick never throws
  (warn + retry next tick; fixed-delay is serial by default).
- **String serialization rationale** — StringSerializer/StringDeserializer everywhere
  (explicitly configured on both factories): payloads are JSON strings serialized by the shared
  ObjectMapper, so no JSON component schemas / schema-registry coupling on the broker side.
- **DEVIATION from the plan wording (justified)**: the plan said to build the listener factory
  "from Boot's autoconfigured ConsumerFactory/KafkaTemplate". Boot 4.1.1 no longer ships any
  Kafka auto-configuration on this classpath (nothing Kafka in `spring-boot-autoconfigure`
  4.1.1; no `spring-boot-kafka` module exists in the 4.1.1 BOM — verified by jar/BOM
  inspection). Since the "no new deps beyond step 1" constraint is hard, `KafkaIngressConfig`
  declares the canonical `DefaultKafkaConsumerFactory` / `DefaultKafkaProducerFactory` /
  `KafkaTemplate` beans itself (still spring-kafka APIs only), binding
  `spring.kafka.bootstrap-servers` and `spring.kafka.consumer.auto-offset-reset` (default
  `earliest`, so downtime-missed request records are redelivered and absorbed by T18 dedup).
- **Test image**: `apache/kafka:4.1.0` via `org.testcontainers.kafka.KafkaContainer` (KRaft,
  org.testcontainers:kafka 1.21.4). 3.9.0 does NOT work with testcontainers' env template: its
  StorageTool rejects the `CONTROLLER://0.0.0.0` listener at format time (defaulted advertised
  address `0.0.0.0`) — reproduced manually with `docker run` before switching to 4.1.0.
- **Immediate-ACK proof (scenario e)**: stub ChatModel sleeps 300ms/call (CHAIN run ≥ ~0.9s);
  the test captures ACK receipt time + terminal time and asserts `ackStatus` pre-terminal OR
  `ackAt < terminalAt`.

## Changed files

- `ingress/pom.xml` — + compile `org.springframework.kafka:spring-kafka` (Boot BOM 4.1.1),
  + test `org.testcontainers:kafka` (testcontainers-bom 1.21.4). Nothing else.
- `core/src/main/java/io/adaptiveagents/core/progress/ProgressService.java` — + `latestSeq()`,
  + `sinceSeqGlobally(long)` (with javadocs). Nothing else in core.
- `ingress/src/main/java/io/adaptiveagents/ingress/kafka/KafkaIngressConfig.java` (new) —
  conditional assembly, consumer/producer/template beans, reply-capable
  `ConcurrentKafkaListenerContainerFactory` (bean name `kafkaListenerContainerFactory`),
  `@EnableKafka`.
- `ingress/src/main/java/io/adaptiveagents/ingress/kafka/KafkaTaskListener.java` (new) —
  listener, ACK, poison path, DLT, X-Task-Id log-only tracing.
- `ingress/src/main/java/io/adaptiveagents/ingress/kafka/ProgressTopicPublisher.java` (new) —
  `@Scheduled(fixedDelayString = "${runtime.kafka.progress-poll-ms:250}")` outbox tail.
- `docs/ENVELOPE.md` — Kafka section: ACK shape, progress frame shape, live-tail contract, DLT.
- `bench/README.md` (new, 41 lines) — the two final-result consumption patterns
  (get_task REST polling loop + kafka-console-consumer progress tail).
- `ingress/src/test/java/io/adaptiveagents/ingress/kafka/KafkaIngressIntegrationTest.java` (new).

## Tests added (KafkaIngressIntegrationTest, 4 methods = 5 scenarios)

1. `requestReplyRoundtrip` — ACK `{task_id, status ∈ ROUTED/RUNNING/SUCCEEDED, decision≠null}`,
   then SUCCEEDED with non-null result; folds scenario (e): ACK-before-terminal / pre-terminal
   status proof via 300ms/node delayed stub model.
2. `progressTopicOrdered` — subscriber attached before submit; NODE_START
   plan→search→summarize→cite in order, strictly ascending seq, TASK_COMPLETED last frame.
3. `redeliveredDuplicateSingleExecution` — same record twice → both replies same task_id,
   exactly ONE execution (`COUNT(*) progress_events ... NODE_START payload->>'node'='plan'` = 1).
4. `poisonEnvelopeYieldsErrorReplyAndSurvives` — `{"task_id":"not-a-uuid",` → INVALID reply
   with error + verbatim DLT record; then a valid CHAIN envelope reaches SUCCEEDED.

Wiring mirrors `RestIngressIntegrationTest` (inner `@SpringBootApplication` Config, inline
@Primary ChatModel/SearchTool doubles, stub-classifier profile, TRUNCATE + lg4j drop) plus
`@EnableScheduling`, KRaft `KafkaContainer`, `runtime.kafka.enabled=true`, both containers wired
via `@DynamicPropertySource`.

## Commands + result

- `mvn -B -pl ingress -Dtest=KafkaIngressIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
  → Tests run: 4, Failures: 0, Errors: 0 — BUILD SUCCESS.
- `mvn -B clean verify` (full root, includes app failsafe e2e group) → **exit 0**, BUILD
  SUCCESS, total 03:27 min. Last lines:

```
[INFO] Reactor Summary for adaptive-agent-runtime 0.1.0-SNAPSHOT:
[INFO] adaptive-agent-runtime ............................. SUCCESS [  0.131 s]
[INFO] spike .............................................. SUCCESS [ 13.154 s]
[INFO] core ............................................... SUCCESS [ 22.063 s]
[INFO] workload ........................................... SUCCESS [  2.139 s]
[INFO] topologies ......................................... SUCCESS [01:12 min]
[INFO] ingress ............................................ SUCCESS [ 55.582 s]
[INFO] bench .............................................. SUCCESS [  0.837 s]
[INFO] app ................................................ SUCCESS [ 41.298 s]
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  03:27 min
```

## Commit

- Message (exact): `feat(ingress): Kafka request/reply + progress topic`
- Hash: `42cc8cb`
- `git log --oneline | head -3`:

```
42cc8cb feat(ingress): Kafka request/reply + progress topic
b3b635f feat(ingress): REST+SSE ingress with API key auth
0b09120 feat(core): deadline guard + zombie-free sweep
```

No push (as instructed); `.omo/` and `target/` never staged; working tree clean after commit.
