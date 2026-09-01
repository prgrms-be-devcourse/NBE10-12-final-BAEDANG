package com.baedang.global.clients.toss;

/**
 * Toss Open API 요청 그룹. 그룹별 TPS는 이 enum에서만 관리한다.
 * 서비스에 TPS 상수를 중복 선언하지 않는다.
 */
public enum TossApiGroup {
    AUTH(5),
    MARKET_INFO(3),
    MARKET_DATA(15),
    MARKET_DATA_CHART(20),
    STOCK(5);

    private final int tps;

    TossApiGroup(int tps) {
        this.tps = tps;
    }

    public int tps(){
        return tps;
    }
}
