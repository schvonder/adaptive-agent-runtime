-- Note: Saver tables (LG4JThread, LG4JThreadTag, LG4JCheckpoint) are intentionally NOT here.
-- They are saver-managed at runtime via PostgresSaver.builder().createTables(true) per SPIKE.md decision.

CREATE TABLE tasks (
  task_id     uuid PRIMARY KEY,
  envelope    jsonb NOT NULL,
  status      text NOT NULL CHECK (status IN ('SUBMITTED','ROUTED','RUNNING','SUCCEEDED','FAILED','BUDGET_EXCEEDED','DEADLINE_EXCEEDED')),
  topology    text NULL,
  decision    jsonb NULL,
  result      jsonb NULL,
  error       jsonb NULL,
  budget_usd  numeric NULL,
  deadline_ms bigint NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  version     bigint NOT NULL DEFAULT 0
);
CREATE TABLE progress_events (
  seq        bigserial PRIMARY KEY,
  task_id    uuid NOT NULL REFERENCES tasks(task_id),
  type       text NOT NULL,
  payload    jsonb NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_progress_events_task_seq ON progress_events (task_id, seq);
