package com.baedang.global.clients.kis;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import com.baedang.global.config.TimeConfig;
import com.baedang.stock.client.kis.KisStockFinancialInfoAdapter;

class KisPropertiesTest {

    private static final String BASE_URL = "kis.base-url=https://example.test";
    private static final String[] VALID_DEFAULTS = {
        "kis.enabled=false",
        BASE_URL,
        "kis.app-key=",
        "kis.app-secret=",
        "kis.requests-per-second=18",
        "kis.connect-timeout=3s",
        "kis.read-timeout=5s",
        "kis.financial-cache-ttl=7d",
        "kis.industry-cache-ttl=30d",
        "kis.load-financials=false"
    };
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TimeConfig.class, KisClientConfiguration.class);

    private ApplicationContextRunner withDefaults(String... overrides) {
        String[] properties = Stream.concat(Arrays.stream(VALID_DEFAULTS), Arrays.stream(overrides))
                .toArray(String[]::new);
        return contextRunner.withPropertyValues(properties);
    }
    @Test
    void disabled_with_empty_credentials_is_valid() {
        withDefaults().run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void disabled_kis_does_not_register_external_call_beans() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        TimeConfig.class,
                        KisClientConfiguration.class,
                        KisStockFinancialInfoAdapter.class)
                .withPropertyValues(VALID_DEFAULTS)
                .run(context -> {
                    assertThat(context).hasSingleBean(KisProperties.class);
                    assertThat(context).doesNotHaveBean(RestClient.class);
                    assertThat(context).doesNotHaveBean(KisRateLimiter.class);
                    assertThat(context).doesNotHaveBean(KisTokenProvider.class);
                    assertThat(context).doesNotHaveBean(KisSecuritiesClient.class);
                    assertThat(context).doesNotHaveBean(KisStockFinancialInfoAdapter.class);
                });
    }

    @Test
    void documented_defaults_bind_as_expected() {
        withDefaults().run(context -> {
            KisProperties properties = context.getBean(KisProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.requestsPerSecond()).isEqualTo(18);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.financialCacheTtl()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.industryCacheTtl()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.loadFinancials()).isFalse();
        });
    }

    @Test
    void enabled_with_blank_app_key_is_rejected() {
        withDefaults("kis.enabled=true", "kis.app-secret=secret")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabled_with_blank_app_secret_is_rejected() {
        withDefaults("kis.enabled=true", "kis.app-key=app-key")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void requests_per_second_must_be_between_one_and_eighteen() {
        withDefaults("kis.requests-per-second=0")
                .run(context -> assertThat(context).hasFailed());
        withDefaults("kis.requests-per-second=19")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void all_configured_durations_must_be_positive() {
        for (String property : List.of(
                "kis.connect-timeout=0s",
                "kis.read-timeout=0s",
                "kis.financial-cache-ttl=0s",
                "kis.industry-cache-ttl=0s")) {
            withDefaults(property).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void kis_configuration_reuses_the_application_clock() {
        new ApplicationContextRunner()
                .withUserConfiguration(TimeConfig.class, KisClientConfiguration.class)
                .withPropertyValues(VALID_DEFAULTS)
                .run(context -> assertThat(context).hasSingleBean(Clock.class));
    }

    @Test
    void loading_financials_requires_enabled_kis() {
        withDefaults("kis.load-financials=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void valid_properties_bind_as_expected() {
        contextRunner.withPropertyValues(
                        "kis.enabled=true",
                        BASE_URL,
                        "kis.app-key=app-key",
                        "kis.app-secret=app-secret",
                        "kis.requests-per-second=3",
                        "kis.connect-timeout=4s",
                        "kis.read-timeout=6s",
                        "kis.financial-cache-ttl=8d",
                        "kis.industry-cache-ttl=31d",
                        "kis.load-financials=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RestClient.class);
                    assertThat(context).hasNotFailed();
                    KisProperties properties = context.getBean(KisProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.baseUrl()).isEqualTo(URI.create("https://example.test"));
                    assertThat(properties.appKey()).isEqualTo("app-key");
                    assertThat(properties.appSecret()).isEqualTo("app-secret");
                    assertThat(properties.requestsPerSecond()).isEqualTo(3);
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(4));
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(6));
                    assertThat(properties.financialCacheTtl()).isEqualTo(Duration.ofDays(8));
                    assertThat(properties.industryCacheTtl()).isEqualTo(Duration.ofDays(31));
                    assertThat(properties.loadFinancials()).isTrue();
                });
    }
}
