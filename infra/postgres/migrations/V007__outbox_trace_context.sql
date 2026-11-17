ALTER TABLE outbox_events
    ADD COLUMN trace_parent VARCHAR(55),
    ADD COLUMN trace_state VARCHAR(512);

COMMENT ON COLUMN outbox_events.trace_parent IS
    'Optional W3C traceparent captured with the transactional outbox event';
COMMENT ON COLUMN outbox_events.trace_state IS
    'Optional W3C tracestate captured with the transactional outbox event';
