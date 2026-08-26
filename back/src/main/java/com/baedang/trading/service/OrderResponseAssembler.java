package com.baedang.trading.service;

import com.baedang.trading.dto.OrderResponse;
import com.baedang.trading.model.MarketOrderReceipt;
import org.springframework.stereotype.Component;

/** 체결 영수증을 HTTP 응답으로 변환하며 포트폴리오 평가는 수행하지 않습니다. */
@Component
public class OrderResponseAssembler {

    public OrderResponse assemble(MarketOrderReceipt receipt) {
        return OrderResponse.from(receipt);
    }
}
