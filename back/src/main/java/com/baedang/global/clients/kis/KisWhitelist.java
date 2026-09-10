package com.baedang.global.clients.kis;

public enum KisWhitelist {
    SEARCH_STOCK_INFO(
            "/uapi/domestic-stock/v1/quotations/search-stock-info",
            "CTPF1002R"),
    BALANCE_SHEET(
            "/uapi/domestic-stock/v1/finance/balance-sheet",
            "FHKST66430100"),
    INCOME_STATEMENT(
            "/uapi/domestic-stock/v1/finance/income-statement",
            "FHKST66430200"),
    FINANCIAL_RATIO(
            "/uapi/domestic-stock/v1/finance/financial-ratio",
            "FHKST66430300"),
    PROFIT_RATIO(
            "/uapi/domestic-stock/v1/finance/profit-ratio",
            "FHKST66430400");

    private final String path;
    private final String trId;

    KisWhitelist(String path, String trId) {
        this.path = path;
        this.trId = trId;
    }

    public String path() {
        return path;
    }

    public String trId() {
        return trId;
    }

    public static KisWhitelist resolve(String path) {
        for (KisWhitelist endpoint : values()) {
            if (endpoint.path.equals(path)) {
                return endpoint;
            }
        }
        return null;
    }
}
