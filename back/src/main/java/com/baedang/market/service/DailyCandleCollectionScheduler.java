package com.baedang.market.service;

import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 거래대금 상위 종목의 일봉을 장 마감 직후 자동 수집합니다.
 *
 * <p>토스 키가 없는 로컬 환경에서 매일 실제 API 호출이 실패하지 않도록,
 * {@code toss.enabled=true} 일 때만 빈으로 등록됩니다.
 *
 * <p>크론 시간 선택 근거:
 * <ul>
 *   <li><b>국내 15:40 KST</b> — 정규장 15:30 종료 고정(KR 은 DST 없음), 10분 여유.</li>
 *   <li><b>해외 06:10 KST</b> — 미국 정규장이 DST 에 따라 KST 05:00(여름) 또는 06:00(겨울)
 *       종료되며, 06:10 은 두 경우를 모두 커버합니다. 실제 미국 정규장 종료 시각은
 *       {@code MarketCalendarPort} 에서 조회하므로 여기서 하드코딩하지 않습니다(AGENTS.md).</li>
 *   <li>미국 시장 MON~FRI → KST 기준 TUE~SAT 에 마감됩니다.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "toss.enabled", havingValue = "true")
public class DailyCandleCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyCandleCollectionScheduler.class);

    private final DailyCandleCollectionService dailyCandleCollectionService;

    public DailyCandleCollectionScheduler(DailyCandleCollectionService dailyCandleCollectionService) {
        this.dailyCandleCollectionService = dailyCandleCollectionService;
    }

    /**
     * 국내 장 마감(15:30 KST) 후 10분 뒤 일봉 수집.
     * 국내 시장은 DST 가 없으므로 고정 크론을 사용합니다.
     */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectKr() {
        log.info("[daily-candle] KR 장 마감 수집 트리거");
        dailyCandleCollectionService.collect(MarketCountry.KR);
    }

    /**
     * 해외 장 마감(05:00~06:00 KST) 후 일봉 수집.
     * KST 06:10 은 미국 DST 여름·겨울 모두 마감 이후임을 보장합니다.
     * 미국 MON~FRI → KST TUE~SAT 06:10 에 트리거합니다.
     */
    @Scheduled(cron = "0 10 6 * * TUE-SAT", zone = "Asia/Seoul")
    public void collectUs() {
        log.info("[daily-candle] US 장 마감 수집 트리거");
        dailyCandleCollectionService.collect(MarketCountry.US);
    }
}
