# bench — client consumption patterns

How a client gets the FINAL RESULT of a task submitted through the ingress. Both patterns
start by submitting (e.g. `POST /tasks` or a Kafka record to `adaptive-tasks-request`) and
holding on to the returned `task_id`.

## (a) `get_task` REST polling

Poll the task resource until a terminal status (`SUCCEEDED`, `FAILED`, `BUDGET_EXCEEDED`,
`DEADLINE_EXCEEDED`); `result` carries the final payload on `SUCCEEDED`, `error` the reason
otherwise.

```bash
ID=<task_id from the submission ACK>
while true; do
  STATE=$(curl -s -H "X-API-Key: $KEY" "http://localhost:8080/tasks/$ID")
  echo "$STATE"
  echo "$STATE" | grep -qE '"status":"(SUCCEEDED|FAILED|BUDGET_EXCEEDED|DEADLINE_EXCEEDED)"' && break
  sleep 1
done
```

## (b) tailing `adaptive-tasks-progress`

Consume the progress topic (a LIVE TAIL: frames from app start onward only) and stop at the
first terminal `TASK_*` event for your `task_id`. Frame shape:
`{"seq":…,"task_id":"…","type":"…","payload":{…}}`.

```bash
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic adaptive-tasks-progress \
  --from-beginning \
  --property print.key=true
# stop when a frame shows "type":"TASK_COMPLETED" (or TASK_FAILED / TASK_DEADLINE_EXCEEDED /
# TASK_BUDGET_EXCEEDED) with your task_id; node progress is NODE_START with payload {"node":…}
```

Historical replay of events emitted before your consumer attached is NOT served by the
topic — use the REST SSE stream `GET /tasks/{id}/events` with `Last-Event-ID` (see
docs/ENVELOPE.md).
