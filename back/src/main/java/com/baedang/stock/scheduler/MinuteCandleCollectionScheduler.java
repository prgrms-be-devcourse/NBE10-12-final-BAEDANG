package com.baedang.stock.scheduler;

import com.baedang.stock.service.MinuteCandleCollectionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 상위 100종목 1분봉 수집을 매분 정각에 트리거한다 (docs/erd.md·docs/api-spec.md 참고).
 * 실제 수집 로직은 {@link MinuteCandleCollectionService}에 있다 — 이 클래스는
 * 스케줄링 배선만 담당해서, 로직 자체는 스프링 스케줄러 없이도 단위 테스트할 수 있다.
 *
 * <p>{@code toss.enabled=true}일 때만 등록된다. {@code application.yaml}의 안내대로
 * ("토스 키가 비어 있으면 수집기를 끄고 Fake 어댑터로 돕니다") 토스 키가 없는
 * 팀원의 로컬 환경에서는 이 스케줄러 자체가 아예 뜨지 않는다 — {@code MarketDataPort}
 * 에는 Fake 구현체가 없어서, 켜진 채로 두면 매분 실제 Toss 호출을 시도하다 실패한다.
 */
@Component
@ConditionalOnProperty(prefix = "toss", name = "enabled", havingValue = "true")
public class MinuteCandleCollectionScheduler {

    private final MinuteCandleCollectionService collectionService;

    public MinuteCandleCollectionScheduler(MinuteCandleCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @Scheduled(cron = "0 * * * * *")
    public void collect() {
        collectionService.collectOpenMarkets();
    }
}
