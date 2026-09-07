-- Existing-database rollout for the shared virtual order book.
--
-- This file is intentionally separate from infra/schema.sql: the bootstrap schema
-- only runs on an empty PostgreSQL data directory. Apply this file once to an
-- existing database with psql while the application is stopped.
--
-- The preflight checks refuse to add the FK when existing LIMIT execution rows
-- point at a missing level, or when duplicate active versions would violate the
-- per-stock invariant. No existing order, execution, or ledger row is rewritten.

BEGIN;

CREATE TABLE IF NOT EXISTS order_book_version (
    book_version_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stock_id        BIGINT        NOT NULL REFERENCES stock(stock_id),
    base_price      NUMERIC(19,4) NOT NULL,
    currency        VARCHAR(3)    NOT NULL CHECK (currency IN ('KRW','USD')),
    quote_at        TIMESTAMPTZ   NOT NULL,
    generated_at    TIMESTAMPTZ   NOT NULL,
    policy_version  VARCHAR(20)   NOT NULL,
    seed            BIGINT        NOT NULL,
    revision        BIGINT        NOT NULL DEFAULT 0,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    closed_at       TIMESTAMPTZ,
    CONSTRAINT ck_order_book_base_price_positive CHECK (base_price > 0),
    CONSTRAINT ck_order_book_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_order_book_version_lifecycle CHECK (
        (is_active = TRUE AND closed_at IS NULL)
        OR (is_active = FALSE AND closed_at IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS order_book_level (
    level_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    book_version_id    BIGINT        NOT NULL
                       REFERENCES order_book_version(book_version_id) ON DELETE CASCADE,
    side               VARCHAR(4)    NOT NULL CHECK (side IN ('BID','ASK')),
    level_depth        INT           NOT NULL CHECK (level_depth BETWEEN 1 AND 10),
    price              NUMERIC(19,4) NOT NULL,
    initial_quantity   NUMERIC(19,6) NOT NULL CHECK (initial_quantity > 0),
    remaining_quantity NUMERIC(19,6) NOT NULL,
    CONSTRAINT uq_order_book_level UNIQUE (book_version_id, side, level_depth),
    CONSTRAINT ck_order_book_level_price_positive CHECK (price > 0),
    CONSTRAINT ck_order_book_remaining_quantity CHECK (
        remaining_quantity >= 0
        AND remaining_quantity <= initial_quantity
    )
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM order_book_version
         WHERE is_active = TRUE
         GROUP BY stock_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'orderbook rollout aborted: duplicate active versions exist per stock';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM trade_execution e
          LEFT JOIN order_book_level l ON l.level_id = e.book_level_id
         WHERE e.book_level_id IS NOT NULL
           AND l.level_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'orderbook rollout aborted: trade_execution.book_level_id has orphan references';
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_order_book_active_version
    ON order_book_version (stock_id)
    WHERE is_active = TRUE;

CREATE INDEX IF NOT EXISTS ix_order_book_stock_generated
    ON order_book_version (stock_id, generated_at DESC);

CREATE INDEX IF NOT EXISTS ix_order_book_cleanup
    ON order_book_version (closed_at)
    WHERE is_active = FALSE AND revision = 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'fk_trade_execution_book_level'
           AND conrelid = 'trade_execution'::regclass
    ) THEN
        ALTER TABLE trade_execution
            ADD CONSTRAINT fk_trade_execution_book_level
            FOREIGN KEY (book_level_id)
            REFERENCES order_book_level(level_id)
            ON DELETE RESTRICT;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS ix_trade_execution_book_level
    ON trade_execution (book_level_id)
    WHERE book_level_id IS NOT NULL;

COMMIT;
