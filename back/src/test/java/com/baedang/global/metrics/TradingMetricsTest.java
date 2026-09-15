package com.baedang.global.metrics;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TradingMetrics} 단위 테스트.
 *
 * <p>!! 일부러 {@link PrometheusMeterRegistry} 를 쓴다. {@code SimpleMeterRegistry} 는 같은 메트릭
 * 이름에 태그 키가 서로 다른 시계열이 섞여도 관대하게 넘어가지만, Prometheus 는 엄격해 태그 키 충돌
 * 시 경고 후 일부 시계열을 드롭한다. PR #213 리뷰에서 잡힌 회귀(배치 지표 태그 키 불일치)를 재현·방지
 * 하려면 실제로 엄격한 레지스트리로 검증해야 한다.
 */
class TradingMetricsTest {

    private static final Instant NOW = Instant.parse("2026-09-15T02:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final TradingMetrics metrics = new TradingMetrics(registry, clock);

    @Test
    void 시장무관_배치와_시장별_배치가_같은_지표에_섞여도_모두_노출된다() {
        // 리더보드(job_name 만)와 일봉(job_name+market)을 같은 메트릭에 등록한다.
        metrics.batchSucceeded("leaderboard-snapshot");
        metrics.batchSucceeded("daily-candle", "KR");
        metrics.batchSucceeded("daily-candle", "US");

        String scrape = registry.scrape();

        // 태그 키가 일관돼야(모두 job_name+market) Prometheus 가 셋 다 노출한다.
        // 불일치였다면 먼저 등록된 형태만 남고 나머지는 scrape 에서 누락된다(리뷰가 잡은 회귀).
        assertThat(scrape).contains("job_name=\"leaderboard-snapshot\"");
        assertThat(scrape).contains("job_name=\"daily-candle\"");
        assertThat(scrape).contains("market=\"KR\"");
        assertThat(scrape).contains("market=\"US\"");
        assertThat(scrape).contains("market=\"all\"");
        // 시장별 시계열이 각각 살아있는지(마스킹 방지의 전제) 개수로 확인.
        assertThat(registry.get("trading.batch.last.success.timestamp.seconds").meters()).hasSize(3);
    }

    @Test
    void 시세_신선도는_저장시각이_아니라_원본_quoteAt_기준이다() {
        // 원천이 멈춰 2분 전 quoteAt 을 반복 반환하는 상황.
        Instant frozenSource = NOW.minusSeconds(120);
        metrics.quoteUpdated("KR", frozenSource);

        double staleness = registry.get("trading.quote.staleness.seconds").tag("market", "KR").gauge().value();

        // 저장 시각(now) 기준이면 ~0 이 나와 QuoteStale 을 못 잡는다. 원본 quoteAt 기준이면 ~120 이어야 한다.
        assertThat(staleness).isEqualTo(120.0);
    }

    @Test
    void 더_오래된_quoteAt_이_들어와도_신선도는_뒤로_가지_않는다() {
        metrics.quoteUpdated("KR", NOW.minusSeconds(10));
        metrics.quoteUpdated("KR", NOW.minusSeconds(300)); // 더 과거 값 — 무시돼야 함

        double staleness = registry.get("trading.quote.staleness.seconds").tag("market", "KR").gauge().value();

        assertThat(staleness).isEqualTo(10.0);
    }
}
