-- Per-node model token/cost accounting (T19). One row per model response that
-- reports usage; SUM(cost_usd) per task is the accumulated spend the budget guard
-- compares against tasks.budget_usd. Persisted so spend survives crash/resume.
CREATE TABLE task_cost_entries (
  task_id          uuid    NOT NULL REFERENCES tasks(task_id),
  node_name        text    NOT NULL,
  model            text    NOT NULL,
  prompt_tokens    bigint  NOT NULL,
  completion_tokens bigint NOT NULL,
  cost_usd         numeric(18,8) NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_cost_entries_task_id ON task_cost_entries (task_id);
