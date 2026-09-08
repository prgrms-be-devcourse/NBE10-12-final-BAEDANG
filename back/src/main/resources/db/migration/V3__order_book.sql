-- V3__order_book.sql
-- 공유 가상 호가 버전·레벨 영속화 및 단위 체결의 소비 호가 참조 (#121)

CREATE TABLE order_book_version (
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

CREATE UNIQUE INDEX uq_order_book_active_version
    ON order_book_version (stock_id)
    WHERE is_active = TRUE;

CREATE INDEX ix_order_book_stock_generated
    ON order_book_version (stock_id, generated_at DESC);

CREATE INDEX ix_order_book_cleanup
    ON order_book_version (closed_at)
    WHERE is_active = FALSE;

-- ASK는 10개, KRW 종목 BID는 10개, 미국 종목 BID는 가능한 1~10개 행이다.
CREATE TABLE order_book_level (
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


-- 체결 행에는 소비 당시 level_id를 추적 값으로 남기되, 종료 호가의 수명과 분리해 FK는 두지 않는다.
CREATE INDEX ix_trade_execution_book_level
    ON trade_execution (book_level_id)
    WHERE book_level_id IS NOT NULL;
