-- Preserve existing outbox IDs and the event_id field inside stored wire messages.
ALTER TABLE outbox_events RENAME COLUMN event_id TO outbox_id;
