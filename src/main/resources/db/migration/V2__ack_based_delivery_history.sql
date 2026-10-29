-- Socket writes alone were previously treated as acknowledged delivery.
UPDATE tasks t
SET delivered_at = NULL, version = version + 1
WHERE delivered_at IS NOT NULL
  AND EXISTS (SELECT 1 FROM task_events e WHERE e.task_id = t.id
              AND e.event_type = 'DELIVERED' AND e.content = 'SENT')
  AND NOT EXISTS (SELECT 1 FROM task_events e WHERE e.task_id = t.id
                  AND (e.event_type = 'ACK_RECEIVED'
                       OR (e.event_type = 'DELIVERED' AND e.content IS DISTINCT FROM 'SENT')));

UPDATE task_events
SET event_type = 'DELIVERY_UNKNOWN', content = 'LEGACY_SENT_WITHOUT_ACK'
WHERE event_type = 'DELIVERED' AND content = 'SENT';

UPDATE task_events SET event_type = 'ACK_RECEIVED'
WHERE event_type = 'DELIVERED';

-- Historical waits cannot be resumed by the in-memory ACK tracker after restart.
UPDATE task_events
SET event_type = 'DELIVERY_UNKNOWN', content = 'LEGACY_ACK_UNCONFIRMED'
WHERE event_type IN ('DELIVERING', 'SENT');

UPDATE task_events SET event_type = 'DELIVERY_UNKNOWN'
WHERE event_type = 'UNKNOWN'
   OR (event_type = 'DELIVERY_FAILED' AND content = 'TRANSPORT_ERROR');

UPDATE task_events SET event_type = 'DELIVERY_NOT_SENT'
WHERE event_type IN ('DELIVERY_FAILED', 'DELIVERY_SKIPPED');
