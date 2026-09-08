package com.baedang.orderbook.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.baedang.orderbook.scheduler.OrderBookRefreshScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderBookPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(OrderBookConfiguration.class);

    @Test
    void application_yaml의_V1_기본정책을_바인딩한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            OrderBookProperties properties = context.getBean(OrderBookProperties.class);

            assertThat(properties.enabled()).isFalse();
            assertThat(properties.policyVersion()).isEqualTo("V1");
            assertThat(properties.refreshInterval()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.refreshInitialDelay()).isEqualTo(Duration.ZERO);
            assertThat(properties.maxQuoteAge()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.krBaseNotional()).isEqualByComparingTo(new BigDecimal("20000000"));
            assertThat(properties.usBaseNotional()).isEqualByComparingTo(new BigDecimal("15000"));
            assertThat(properties.minQuantity()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(properties.maxQuantity()).isEqualByComparingTo(new BigDecimal("1000000"));
            assertThat(properties.noiseMinBps()).isEqualTo(8000);
            assertThat(properties.noiseMaxBps()).isEqualTo(12000);
            assertThat(properties.closedVersionRetention()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.retentionInitialDelay()).isEqualTo(Duration.ZERO);
        });
    }


    @Test
    void scheduler_initial_delay를_명시적으로_바인딩한다() {
        contextRunner
                .withPropertyValues(
                        "trading.orderbook.refresh-initial-delay=2s",
                        "trading.orderbook.retention-initial-delay=3s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OrderBookProperties properties = context.getBean(OrderBookProperties.class);
                    assertThat(properties.refreshInitialDelay()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.retentionInitialDelay()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    void 음수_scheduler_initial_delay는_시작을_거절한다() {
        contextRunner
                .withPropertyValues("trading.orderbook.refresh-initial-delay=-1s")
                .run(context -> assertThat(context).hasFailed());

        contextRunner
                .withPropertyValues("trading.orderbook.retention-initial-delay=-1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void scheduler는_검증된_initial_delay_설정키를_사용한다() throws NoSuchMethodException {
        Scheduled refresh = OrderBookRefreshScheduler.class
                .getMethod("refreshOrderBooks")
                .getAnnotation(Scheduled.class);
        Scheduled retention = OrderBookRefreshScheduler.class
                .getMethod("deleteExpiredClosedVersions")
                .getAnnotation(Scheduled.class);

        assertThat(refresh.initialDelayString())
                .isEqualTo("${trading.orderbook.refresh-initial-delay}");
        assertThat(retention.initialDelayString())
                .isEqualTo("${trading.orderbook.retention-initial-delay}");
    }

    @Test
    void 생성자_직접호출_유효성_검증() {
        assertThatThrownBy(() -> new OrderBookProperties(
                false, "", Duration.ofSeconds(3), Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                BigDecimal.ONE, new BigDecimal("1000000"), 8000, 12000, Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new OrderBookProperties(
                false, "V1", Duration.ZERO, Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                BigDecimal.ONE, new BigDecimal("1000000"), 8000, 12000, Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);


        assertThatThrownBy(() -> new OrderBookProperties(
                false, "V1", Duration.ofSeconds(3), Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                BigDecimal.ONE, new BigDecimal("1000000"), 12000, 8000, Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new OrderBookProperties(
                false, "V1", Duration.ofSeconds(3), Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                new BigDecimal("0.5"), new BigDecimal("1000000"), 8000, 12000, Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new OrderBookProperties(
                false, "V1", Duration.ofSeconds(3), Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                new BigDecimal("1.5"), new BigDecimal("1.5"), 8000, 12000, Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new OrderBookProperties(
                false, "V1", Duration.ofSeconds(3), Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                BigDecimal.ONE, new BigDecimal("10000000000000"), 8000, 12000, Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);

        // 1.000000처럼 정수지만 소수점 0이 붙은 형태는 정상 허용된다
        assertThat(new OrderBookProperties(
                false, "V1", Duration.ofSeconds(3), Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                new BigDecimal("1.000000"), new BigDecimal("1000000.00"), 8000, 12000, Duration.ofMinutes(1)
        )).isNotNull();
    }
}
