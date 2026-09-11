CREATE TABLE stock_like
(
    stock_like_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES users (user_id),
    stock_id      BIGINT      NOT NULL REFERENCES stock (stock_id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_stock_like_user_stock UNIQUE (user_id, stock_id)
);
