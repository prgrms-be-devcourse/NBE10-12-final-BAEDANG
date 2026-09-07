package com.baedang.trading.service;

import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.LimitOrderRequest;
import com.baedang.trading.dto.OrderDetailResponse;
import com.baedang.trading.entity.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LimitOrderServiceTest {

    @Test
    void 기존주문_존재시_재생결과를_반환하고_외부_시장세션을_조회하지_않는다() {
        var transactions = mock(LimitOrderTransactionService.class);
        var sessions = mock(MarketSessionProvider.class);
        var rates = mock(ExecutionExchangeRateProvider.class);
        var policy = new MarketOrderPolicy(15, 15, new BigDecimal("1000000"));
        var service = new LimitOrderService(
                policy,
                transactions,
                mock(LimitOrderPricing.class),
                sessions,
                rates,
                mock(StockRepository.class),
                mock(OrderReadService.class),
                mock(OrderQuoteQueryService.class),
                Clock.systemUTC()
        );
        var request = new LimitOrderRequest(1L, UUID.randomUUID().toString(), "AAPL", "US", "BUY", "1", "100", "USD");
        var response = mock(OrderDetailResponse.class);
        when(response.status()).thenReturn(OrderStatus.PENDING);
        when(transactions.existing(any(), any())).thenReturn(Optional.of(response));

        assertThat(service.place(1L, request)).isEqualTo(response);
        verifyNoInteractions(sessions, rates);
    }
}
