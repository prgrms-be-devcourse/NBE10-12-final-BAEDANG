package com.baedang.market.event.config;

import com.baedang.global.config.TimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class KrxMarketEventPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TimeConfig.class, KindClientConfiguration.class);
    @Test
    void defaults_match_the_approved_contract() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            KrxMarketEventProperties p = context.getBean(KrxMarketEventProperties.class);
            assertThat(p.enabled()).isFalse();
            assertThat(p.baseUrl()).isEqualTo(URI.create("https://kind.krx.co.kr"));
            assertThat(p.pollInterval()).isEqualTo(Duration.ofSeconds(15));
            assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void custom_properties_bind_successfully() {
        contextRunner
                .withPropertyValues(
                        "krx.market-events.enabled=true",
                        "krx.market-events.base-url=https://custom.kind.krx.co.kr",
                        "krx.market-events.poll-interval=30s",
                        "krx.market-events.connect-timeout=10s",
                        "krx.market-events.read-timeout=20s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KrxMarketEventProperties p = context.getBean(KrxMarketEventProperties.class);
                    assertThat(p.enabled()).isTrue();
                    assertThat(p.baseUrl()).isEqualTo(URI.create("https://custom.kind.krx.co.kr"));
                    assertThat(p.pollInterval()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(20));
                });
    }

    @Test
    void non_https_base_url_is_rejected() {
        contextRunner
                .withPropertyValues("krx.market-events.base-url=http://kind.krx.co.kr")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void non_positive_durations_are_rejected() {
        contextRunner
                .withPropertyValues("krx.market-events.poll-interval=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("krx.market-events.connect-timeout=-1s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("krx.market-events.read-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }
}
