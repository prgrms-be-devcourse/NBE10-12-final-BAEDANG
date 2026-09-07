package com.baedang.orderbook.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
            assertThat(properties.levelsPerSide()).isEqualTo(10);
            assertThat(properties.spreadStepsPerSide()).isEqualTo(1);
            assertThat(properties.maxQuoteAge()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.krBaseNotional()).isEqualByComparingTo(new BigDecimal("20000000"));
            assertThat(properties.usBaseNotional()).isEqualByComparingTo(new BigDecimal("15000"));
            assertThat(properties.minQuantity()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(properties.maxQuantity()).isEqualByComparingTo(new BigDecimal("1000000"));
            assertThat(properties.noiseMinBps()).isEqualTo(8000);
            assertThat(properties.noiseMaxBps()).isEqualTo(12000);
            assertThat(properties.unconsumedRetention()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void V1_레벨_수가_10이_아니면_시작을_거절한다() {
        contextRunner
                .withPropertyValues("trading.orderbook.levels-per-side=9")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 생성자_직접호출_유효성_검증() {
        assertThatThrownBy(() -> new OrderBookProperties(
                false, "", Duration.ofSeconds(3), 10, 1, Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                BigDecimal.ONE, new BigDecimal("1000000"), 8000, 12000, Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new OrderBookProperties(
                false, "V1", Duration.ZERO, 10, 1, Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                BigDecimal.ONE, new BigDecimal("1000000"), 8000, 12000, Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new OrderBookProperties(
                false, "V1", Duration.ofSeconds(3), 10, 2, Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                BigDecimal.ONE, new BigDecimal("1000000"), 8000, 12000, Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new OrderBookProperties(
                false, "V1", Duration.ofSeconds(3), 10, 1, Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                BigDecimal.ONE, new BigDecimal("1000000"), 12000, 8000, Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new OrderBookProperties(
                false, "V1", Duration.ofSeconds(3), 10, 1, Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                new BigDecimal("0.5"), new BigDecimal("1000000"), 8000, 12000, Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new OrderBookProperties(
                false, "V1", Duration.ofSeconds(3), 10, 1, Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                new BigDecimal("1.5"), new BigDecimal("1.5"), 8000, 12000, Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new OrderBookProperties(
                false, "V1", Duration.ofSeconds(3), 10, 1, Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                BigDecimal.ONE, new BigDecimal("10000000000000"), 8000, 12000, Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);

        // 1.000000처럼 정수지만 소수점 0이 붙은 형태는 정상 허용된다
        assertThat(new OrderBookProperties(
                false, "V1", Duration.ofSeconds(3), 10, 1, Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                new BigDecimal("1.000000"), new BigDecimal("1000000.00"), 8000, 12000, Duration.ofMinutes(1)
        )).isNotNull();
    }
}
