package com.baedang.trading.service;

import com.baedang.trading.dto.PlaceOrderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class MarketOrderTransactionBoundaryTest {

    @Test
    void 시장가_주문_진입점은_외부_트랜잭션_참여를_금지한다() throws Exception {
        Transactional annotation = MarketOrderService.class
                .getMethod("place", Long.class, PlaceOrderRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.NEVER);
    }

    @Test
    void DB_변경_서비스는_최상위_REQUIRED_트랜잭션을_시작한다() throws Exception {
        Transactional annotation = MarketOrderTransactionService.class
                .getMethod(
                        "execute",
                        Long.class,
                        com.baedang.trading.model.MarketOrderCommand.class,
                        com.baedang.trading.model.MarketOrderExecutionContext.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRED);
    }
}
