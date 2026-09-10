-- KRX KIND circuit-breaker and sidecar event history.
-- Append-only: corrections arrive as a new source event ID.
CREATE TABLE market_event (
    market_event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source VARCHAR(20) NOT NULL,
    source_event_id VARCHAR(20) NOT NULL,
    market VARCHAR(10) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    circuit_breaker_stage SMALLINT,
    sidecar_direction VARCHAR(4),
    triggered_at TIMESTAMPTZ NOT NULL,
    halt_until TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    title VARCHAR(300) NOT NULL,
    source_url VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_market_event_source UNIQUE (source, source_event_id),
    CONSTRAINT ck_market_event_market CHECK (market IN ('KOSPI', 'KOSDAQ')),
    CONSTRAINT ck_market_event_type CHECK (event_type IN ('CIRCUIT_BREAKER', 'SIDECAR')),
    CONSTRAINT ck_market_event_time CHECK (triggered_at < halt_until),
    CONSTRAINT ck_market_event_payload CHECK (
        (event_type = 'CIRCUIT_BREAKER'
            AND circuit_breaker_stage BETWEEN 1 AND 3
            AND sidecar_direction IS NULL)
        OR
        (event_type = 'SIDECAR'
            AND circuit_breaker_stage IS NULL
            AND sidecar_direction IN ('BUY', 'SELL'))
    )
);

CREATE INDEX ix_market_event_active
    ON market_event (market, event_type, halt_until DESC, triggered_at DESC);

CREATE INDEX ix_market_event_history
    ON market_event (market, triggered_at DESC, market_event_id DESC);
