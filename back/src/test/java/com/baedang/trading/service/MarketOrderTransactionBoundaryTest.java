package com.baedang.trading.service;

import com.baedang.trading.dto.PlaceOrderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

import static org.assertj.core.api.Assertions.assertThat;

class MarketOrderTransactionBoundaryTest {

    @Test
    void 시장가_주문_진입점은_외부_트랜잭션_참여를_금지한다() throws Exception {
        var attribute = new AnnotationTransactionAttributeSource().getTransactionAttribute(
                MarketOrderService.class.getMethod("place", Long.class, PlaceOrderRequest.class), MarketOrderService.class);

        assertThat(attribute).isNotNull();
        assertThat(attribute.getPropagationBehavior()).isEqualTo(Propagation.NEVER.value());
    }

    @Test
    void DB_변경_서비스의_트랜잭션_전파는_REQUIRED이다() throws Exception {
        var attribute = new AnnotationTransactionAttributeSource().getTransactionAttribute(
                MarketOrderTransactionService.class.getMethod(
                        "execute",
                        Long.class,
                        com.baedang.trading.model.MarketOrderCommand.class,
                        com.baedang.trading.model.MarketOrderExecutionContext.class), MarketOrderTransactionService.class);

        assertThat(attribute).isNotNull();
        assertThat(attribute.getPropagationBehavior()).isEqualTo(Propagation.REQUIRED.value());
    }
}
