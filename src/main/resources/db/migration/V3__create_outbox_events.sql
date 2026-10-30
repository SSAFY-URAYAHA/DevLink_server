-- Shared by all domains; aggregate identifiers intentionally have no foreign key.
CREATE TABLE outbox_events (
    event_id        UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    destination     VARCHAR(255) NOT NULL,
    message_key     VARCHAR(255),
    message         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    available_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_until    TIMESTAMP WITH TIME ZONE,
    lock_token      UUID,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMP WITH TIME ZONE,
    last_error      TEXT,
    CONSTRAINT pk_outbox_events PRIMARY KEY (event_id),
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_events_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_events_message CHECK (jsonb_typeof(message) = 'object')
);

CREATE INDEX ix_outbox_events_pending ON outbox_events (status, available_at, created_at);
CREATE INDEX ix_outbox_events_lock ON outbox_events (status, locked_until);
CREATE INDEX ix_outbox_events_published ON outbox_events (status, published_at);
