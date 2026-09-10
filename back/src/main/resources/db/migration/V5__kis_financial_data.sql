CREATE TABLE stock_industry (
    stock_id BIGINT PRIMARY KEY REFERENCES stock(stock_id) ON DELETE CASCADE,
    standard_industry_code VARCHAR(10),
    standard_industry_name VARCHAR(100),
    index_industry_large_code VARCHAR(10),
    index_industry_large_name VARCHAR(100),
    index_industry_medium_code VARCHAR(10),
    index_industry_medium_name VARCHAR(100),
    index_industry_small_code VARCHAR(10),
    index_industry_small_name VARCHAR(100),
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE stock_financial_period (
    stock_id BIGINT NOT NULL REFERENCES stock(stock_id) ON DELETE CASCADE,
    period_type VARCHAR(10) NOT NULL,
    statement_year_month CHAR(6) NOT NULL,
    current_assets NUMERIC(30,6),
    fixed_assets NUMERIC(30,6),
    total_assets NUMERIC(30,6),
    current_liabilities NUMERIC(30,6),
    fixed_liabilities NUMERIC(30,6),
    total_liabilities NUMERIC(30,6),
    capital_stock NUMERIC(30,6),
    capital_surplus NUMERIC(30,6),
    retained_earnings NUMERIC(30,6),
    total_equity NUMERIC(30,6),
    sales NUMERIC(30,6),
    operating_profit NUMERIC(30,6),
    net_income NUMERIC(30,6),
    sales_growth_rate NUMERIC(30,6),
    operating_profit_growth_rate NUMERIC(30,6),
    net_income_growth_rate NUMERIC(30,6),
    roe NUMERIC(30,6),
    eps NUMERIC(30,6),
    sales_per_share NUMERIC(30,6),
    bps NUMERIC(30,6),
    reserve_ratio NUMERIC(30,6),
    debt_ratio NUMERIC(30,6),
    net_profit_margin NUMERIC(30,6),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (stock_id, period_type, statement_year_month),
    CONSTRAINT ck_stock_financial_period_type
        CHECK (period_type IN ('ANNUAL', 'QUARTERLY')),
    CONSTRAINT ck_stock_financial_statement_month
        CHECK (statement_year_month ~ '^[0-9]{6}$')
);

CREATE TABLE stock_financial_sync (
    stock_id BIGINT PRIMARY KEY REFERENCES stock(stock_id) ON DELETE CASCADE,
    industry_synced_at TIMESTAMPTZ,
    annual_synced_at TIMESTAMPTZ,
    quarterly_synced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
