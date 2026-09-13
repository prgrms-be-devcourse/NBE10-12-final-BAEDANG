package com.baedang.market.event.service;

import com.baedang.market.event.dto.MarketEventListResponse;
import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEvent;
import com.baedang.market.event.repository.MarketEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * 저장된 시장조치 이력을 시장·KST 날짜로 조회한다.
 *
 * <p>날짜 경계는 KST 자정이고 범위는 {@code [from, to)}다. 저장은 UTC이므로 경계를 UTC로
 * 변환해 질의한다 — 서버 기본 타임존에 의존하지 않도록 {@code Clock}과 {@code ZoneId}를 명시한다.
 *
 * <p>활성 여부는 저장 값이 아니라 응답 시각 기준으로 계산한다. 만료된 CB는 이력으로 남지만
 * {@code active}는 false이고, 클라이언트가 별도 판정을 하지 않아도 된다.
 */
@Service
public class MarketEventQueryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_ITEMS = 100;

    private final MarketEventRepository repository;
    private final Clock clock;

    public MarketEventQueryService(MarketEventRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(readOnly = true)
    public MarketEventListResponse get(KrMarket market, LocalDate date) {
        Objects.requireNonNull(market, "market must not be null");
        Objects.requireNonNull(date, "date must not be null");

        Instant now = clock.instant();
        List<MarketEventListResponse.Item> items = repository.findHistory(
                        market,
                        date.atStartOfDay(KST).toInstant().atOffset(ZoneOffset.UTC),
                        date.plusDays(1).atStartOfDay(KST).toInstant().atOffset(ZoneOffset.UTC),
                        PageRequest.of(0, MAX_ITEMS))
                .stream()
                .map(event -> MarketEventListResponse.Item.from(event, active(event, now), KST))
                .toList();

        return new MarketEventListResponse(market, date, items);
    }

    private static boolean active(MarketEvent event, Instant now) {
        Instant triggeredAt = event.getTriggeredAt().toInstant();
        Instant haltUntil = event.getHaltUntil().toInstant();
        return !now.isBefore(triggeredAt) && now.isBefore(haltUntil);
    }
}
