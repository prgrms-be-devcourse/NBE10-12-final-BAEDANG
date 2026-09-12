package com.baedang.market.event.service;

import com.baedang.market.event.dto.MarketEventListResponse;
import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEvent;
import com.baedang.market.event.entity.MarketEventSource;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.entity.SidecarDirection;
import com.baedang.market.event.repository.MarketEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 조회 경계는 KST 자정이다. 저장은 UTC로 하지만 "7월 13일"이라는 질문의 범위는 KST로 정해지므로,
 * 변환을 서비스가 책임진다. 응답 시각도 같은 이유로 {@code +09:00}으로 통일한다.
 */
class MarketEventQueryServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-07-13T04:48:32Z");

    private final MarketEventRepository repository = mock(MarketEventRepository.class);

    private MarketEventQueryService service;

    @BeforeEach
    void setUp() {
        service = new MarketEventQueryService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void queries_the_kst_day_as_a_half_open_utc_range() {
        when(repository.findHistory(any(), any(), any(), any())).thenReturn(List.of());

        service.get(KrMarket.KOSPI, LocalDate.of(2026, 7, 13));

        verify(repository).findHistory(
                eq(KrMarket.KOSPI),
                eq(OffsetDateTime.parse("2026-07-12T15:00:00Z")),
                eq(OffsetDateTime.parse("2026-07-13T15:00:00Z")),
                eq(PageRequest.of(0, 100)));
    }

    @Test
    void active_is_false_exactly_at_halt_until_and_true_one_second_before() {
        MarketEvent event = circuitBreaker(
                "20260713000658", "2026-07-13T04:28:32Z", "2026-07-13T04:48:32Z");
        when(repository.findHistory(any(), any(), any(), any())).thenReturn(List.of(event));

        MarketEventListResponse response = service.get(KrMarket.KOSPI, LocalDate.of(2026, 7, 13));

        // NOW == haltUntil -> 이미 해제
        assertThat(response.items().getFirst().active()).isFalse();

        service = new MarketEventQueryService(repository,
                Clock.fixed(Instant.parse("2026-07-13T04:48:31Z"), ZoneOffset.UTC));
        when(repository.findHistory(any(), any(), any(), any())).thenReturn(List.of(event));

        assertThat(service.get(KrMarket.KOSPI, LocalDate.of(2026, 7, 13))
                .items().getFirst().active()).isTrue();
    }

    @Test
    void public_timestamps_use_kst_offset() {
        MarketEvent event = circuitBreaker(
                "20260713000658", "2026-07-13T04:28:32Z", "2026-07-13T04:48:32Z");
        when(repository.findHistory(any(), any(), any(), any())).thenReturn(List.of(event));

        var item = service.get(KrMarket.KOSPI, LocalDate.of(2026, 7, 13)).items().getFirst();

        assertThat(item.triggeredAt()).isEqualTo(
                java.time.OffsetDateTime.parse("2026-07-13T13:28:32+09:00"));
        assertThat(item.triggeredAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(item.haltUntil().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(item.publishedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(item.receivedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }

    @Test
    void sidecar_exposes_direction_and_leaves_stage_null() {
        MarketEvent event = MarketEvent.sidecar(
                MarketEventSource.KRX_KIND, "20260715000125", KrMarket.KOSPI, SidecarDirection.BUY,
                Instant.parse("2026-07-15T00:06:41Z"), Instant.parse("2026-07-15T00:11:41Z"),
                Instant.parse("2026-07-15T00:07:00Z"), Instant.parse("2026-07-15T00:07:05Z"),
                "유가증권시장 매수 사이드카(Side car) 발동", SOURCE_URL);
        when(repository.findHistory(any(), any(), any(), any())).thenReturn(List.of(event));

        var item = service.get(KrMarket.KOSPI, LocalDate.of(2026, 7, 13)).items().getFirst();

        assertThat(item.direction()).isEqualTo(SidecarDirection.BUY);
        assertThat(item.stage()).isNull();
    }

    @Test
    void empty_repository_result_yields_no_items_without_error() {
        when(repository.findHistory(any(), any(), any(), any())).thenReturn(List.of());

        MarketEventListResponse response = service.get(KrMarket.KOSDAQ, LocalDate.of(2026, 8, 5));

        assertThat(response.items()).isEmpty();
        assertThat(response.market()).isEqualTo(KrMarket.KOSDAQ);
        assertThat(response.date()).isEqualTo(LocalDate.of(2026, 8, 5));
    }

    private static MarketEvent circuitBreaker(String sourceEventId, String triggeredAt, String haltUntil) {
        return MarketEvent.circuitBreaker(
                MarketEventSource.KRX_KIND,
                sourceEventId,
                KrMarket.KOSPI,
                1,
                Instant.parse(triggeredAt),
                Instant.parse(haltUntil),
                Instant.parse("2026-07-13T04:29:00Z"),
                Instant.parse("2026-07-13T04:29:07Z"),
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                SOURCE_URL);
    }

    private static final URI SOURCE_URL =
            URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");
}
