-- Request keys are generated and retried per Agent, so idempotency is scoped to the requesting Agent.
-- Safe without data cleanup: an Agent has one owner, so any (agent, key) duplicate would already
-- violate the previous (user, key) constraint.
ALTER TABLE tasks DROP CONSTRAINT uk_tasks_request_key;
ALTER TABLE tasks ADD CONSTRAINT uk_tasks_request_key UNIQUE (requester_agent_id, request_key);
