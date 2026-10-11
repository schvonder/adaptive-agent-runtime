# T20 evidence — deadline guard (no zombies) — adaptive-agent-runtime

## Goal

Implement plan todo T20: runtime deadline enforcement that leaves no zombies. Boundary guards
(T16) only fire between nodes; a run hung *mid-node* (model call never returns) previously ran
forever with its future parked in `runAndMap`. This task adds the runtime analogue of
`StartupRescan`: an in-flight future registry + `cancel()` in `ExecutionRunner`, a
`CancellationException → DEADLINE_EXCEEDED` mapping, and a periodic `RunningTaskSweep`
(`@Scheduled`, topologies module) that cancels in-flight runs of expired RUNNING tasks — or
finalizes expired RUNNING rows that have no local run. Acceptance (plan): 200ms deadline on a
multi-node task → DEADLINE_EXCEEDED + thread/executor count back to baseline within 5s.

## Design decisions

### Cancellation semantics

- `ExecutionRunner` keeps a `ConcurrentHashMap<UUID, Future<Map<String,Object>>> inFlight`
  registry. `runAndMap` registers the graph run keyed by taskId **before** handing it to the
  executor, and removes the entry in a `finally` once `get()` returns/throws (also covers the
  cancel path: `cancel → get() throws CancellationException → finally removes`).
- Registration-before-execution is implemented as `FutureTask` + `VIRTUAL.execute(run)` instead
  of `submit()`-then-`put()`: `submit()` only returns the future *after* the task is already
  eligible to run, leaving a window in which a sweep tick would miss the run and finalize the
  row underneath it. `FutureTask` + `execute` is byte-for-byte what `AbstractExecutorService
  .submit` does internally, minus that window. The executor stays static, unpooled, virtual —
  untouched.
- The `finally` uses the two-arg `inFlight.remove(taskId, run)` so an older run never evicts a
  newer run registered for the same task id in the interim (resume/restart race).
- `cancel(UUID)` = `inFlight.remove(taskId)` then `future.cancel(true)` (`mayInterrupt = true`).
  Returns **whether an in-flight run was found** — a successful cancel and an already-completed
  race both count as found (the caller treats `true` as "the runner side settles the outcome").
- `inFlightCount()` exposes registry size — the plan's ThreadMXBean-style thread-baseline check,
  realized as an executor assert (accepted equivalent per plan).

### CancellationException before ExecutionException

- `runAndMap` catches `java.util.concurrent.CancellationException` **ahead of**
  `ExecutionException`. The two are unrelated types (`CancellationException` extends
  `IllegalStateException`, not `ExecutionException`), but the catch order documents and enforces
  which branch owns a cancelled run. The branch persists
  `completeTerminal(taskId, DEADLINE_EXCEEDED, {"message":"cancelled by deadline sweep"})`,
  publishes `TASK_DEADLINE_EXCEEDED`, restores the interrupt flag if the cause chain carries an
  `InterruptedException`, and returns `Outcome(DEADLINE_EXCEEDED, …)` — it never rethrows, so the
  caller's `executeAsync` future completes normally instead of leaking as a zombie.
- `mapOutcome` untouched; boundary-guard mapping (T16–T19) unchanged.

### Idempotency races

- Terminal transitions are optimistic-locked (`version` CAS in `TaskStore.completeTerminal`):
  exactly one writer wins RUNNING→terminal; every loser gets `IllegalTaskStateException`.
- The runner's cancellation branch absorbs `IllegalTaskStateException` at info ("finalized
  elsewhere") and still returns `Outcome(DEADLINE_EXCEEDED)`; the sweep absorbs it per-task at
  debug. Races covered: sweep-cancel vs boundary-guard, sweep-direct vs a concurrent finalize by
  a foreign process, and double sweep ticks (fixedDelay makes overlaps impossible for the sweep
  itself, but redundant with the lock anyway).

### Who-finalizes (3 paths)

| Path | Trigger | Finalizer | Persist + publish |
|------|---------|-----------|-------------------|
| Boundary guard | node boundary after expiry (T16) | runner `mapOutcome` | `completeTerminal(DEADLINE_EXCEEDED, {message, partial})` + `TASK_DEADLINE_EXCEEDED` |
| Runner-cancel | sweep cancels an in-flight run mid-node | runner `cancelledOutcome` (CancellationException branch) | `completeTerminal(DEADLINE_EXCEEDED, {message:"cancelled by deadline sweep"})` + `TASK_DEADLINE_EXCEEDED` |
| Sweep-direct | expired RUNNING row, no local run (abandoned/foreign) | sweep itself | `completeTerminal(DEADLINE_EXCEEDED, {message:"deadline expired; no local run"})` + `TASK_DEADLINE_EXCEEDED` reason `deadline <ts> expired with no local run (sweep)` |

### Sweep component

- `RunningTaskSweep` (`topologies/sweep`), `@Scheduled(fixedDelayString =
  "${runtime.sweep.interval-ms:1000}")`. Queries `findByStatus(RUNNING)`; touches only rows with
  `deadlineMs != null && now > deadlineMs`. `cancel(taskId) == true` → log info, runner
  finalizes. `false` → sweep finalizes directly. Whole tick wrapped: any exception → warn +
  return, never propagate (an escaping exception would kill the fixed-delay chain and silently
  disable all later sweeps). `IllegalTaskStateException` per task → debug. App wiring:
  `@EnableScheduling` on `Application` (no new properties files; 1000ms default inline).

## Changed files

- `topologies/src/main/java/io/adaptiveagents/topologies/runner/ExecutionRunner.java` — in-flight
  registry, `cancel`/`inFlightCount`, CancellationException branch (`cancelledOutcome`,
  `carriesInterrupt`), FutureTask-based registration, class javadoc rewritten (T19/T20 sentence
  replaced with the cancellation/sweep design).
- `topologies/src/main/java/io/adaptiveagents/topologies/sweep/RunningTaskSweep.java` — NEW.
- `topologies/src/test/java/io/adaptiveagents/topologies/sweep/DeadlineSweepIntegrationTest.java`
  — NEW (5 scenarios).
- `app/src/main/java/io/adaptiveagents/app/Application.java` — `@EnableScheduling` + import +
  javadoc sentence.

## Tests added (`DeadlineSweepIntegrationTest`)

1. `sweepCancelsMidNodeHang` — THE core T20 scenario: CHAIN, deadline now+200ms,
   `blockCall(2, never-released latch)` (mid-node hang — no boundary can ever fire), 2 scripted
   responses (never reached) → DEADLINE_EXCEEDED within poll ≤10s, `TASK_DEADLINE_EXCEEDED`
   present, `inFlightCount()==0` within 5s, `executeAsync` future completes via `get(20s)`.
2. `planAcceptanceDeadlineWithin5s` — plan acceptance mirrored: deadline now+200ms,
   `setDelayMs(300)` 4-node CHAIN, no hang → DEADLINE_EXCEEDED via boundary guard OR sweep
   (both valid), `inFlightCount()==0` within 5s.
3. `sweepLeavesUndeadlinedRunningTasks` — RUNNING blocked on never-released latch, NO deadline,
   ≥2 sweep ticks (300ms) → still RUNNING, `inFlightCount()==1` (cleanup cancel afterwards so
   the parked call cannot leak into later tests' script).
4. `sweepFinalizesRunningRowWithoutLocalRun` — seeded RUNNING with already-expired deadline
   (route → transition, never executed) → DEADLINE_EXCEEDED + event reason containing
   "no local run".
5. `happyWithinDeadline` — deadline now+10s, normal script → SUCCEEDED, registry drains.

## Commands + output

Commands run:

- `mvn -B -pl topologies -am test -Dtest=DeadlineSweepIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
  → `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` … `BUILD SUCCESS`
- `mvn -B -pl topologies -am test` → `Tests run: 43, Failures: 0, Errors: 0, Skipped: 0` …
  `BUILD SUCCESS`
- `mvn -B clean verify` (FULL ROOT) → exit code **0**

Last ~15 lines of the full root verify:

```
[INFO] Results:
[INFO]
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- failsafe:3.5.6:verify (default) @ app ---
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for adaptive-agent-runtime 0.1.0-SNAPSHOT:
[INFO]
[INFO] adaptive-agent-runtime ............................. SUCCESS [  0.126 s]
[INFO] spike .............................................. SUCCESS [ 12.975 s]
[INFO] core ............................................... SUCCESS [ 21.412 s]
[INFO] workload ............................................ SUCCESS [  2.033 s]
[INFO] topologies ......................................... SUCCESS [01:12 min]
[INFO] ingress ............................................ SUCCESS [  0.841 s]
[INFO] bench .............................................. SUCCESS [  0.768 s]
[INFO] app ................................................ SUCCESS [ 40.131 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:31 min
[INFO] Finished at: 2026-10-10T11:37:36 +03:00
EXIT_CODE=0
```

Notes from test observation: after the container is stopped, sweep ticks log
`Sweep tick failed (retried on the next interval): …` and scheduling continues — the
never-propagate wrapper was exercised live. The e2e `ResumeAfterKillE2eTest` (expired-while-down
scenario) still observes exactly one `TASK_DEADLINE_EXCEEDED`: the optimistic-locked terminal
transition guarantees rescan/sweep single-publish.

## Commit

- Commit hash: `0b09120` (`feat(core): deadline guard + zombie-free sweep`, branch `main`)

```
0b09120 feat(core): deadline guard + zombie-free sweep
0eb7179 feat(core): budget guard + degradation matrix
c7d1f1d feat(core): taskId idempotency with advisory lock
```

No push (repo rule: local git only). `.omo/` and `target/` never staged.
