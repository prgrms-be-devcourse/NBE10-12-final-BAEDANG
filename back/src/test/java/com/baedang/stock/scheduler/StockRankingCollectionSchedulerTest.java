package com.baedang.stock.scheduler;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.service.StockRankingLoadService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class StockRankingCollectionSchedulerTest {

    /** 2026-08-31 은 월요일. 자정 기준으로 잡아야 같은 날 트리거가 next() 로 잡힌다. */
    private static final LocalDate 월요일 = LocalDate.of(2026, 8, 31);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StockRankingLoadService stockRankingLoadService = mock(StockRankingLoadService.class);
    private final StockRankingCollectionScheduler scheduler =
            new StockRankingCollectionScheduler(stockRankingLoadService);

    @Test
    void 국내_트리거는_KR만_위임한다() {
        scheduler.loadKr();

        verify(stockRankingLoadService).load(MarketCountry.KR);
        verifyNoMoreInteractions(stockRankingLoadService);
    }

    @Test
    void 미국_트리거는_US만_위임한다() {
        scheduler.loadUs();

        verify(stockRankingLoadService).load(MarketCountry.US);
        verifyNoMoreInteractions(stockRankingLoadService);
    }

    @Test
    void 국내_갱신은_월요일_8시_KST에_발화한다() throws NoSuchMethodException {
        assertThat(다음_발화_시각("loadKr"))
                .isEqualTo(ZonedDateTime.of(월요일, LocalTime.of(8, 0), KST).toInstant());
    }

    @Test
    void 미국_갱신은_월요일_21시_KST에_발화한다() throws NoSuchMethodException {
        assertThat(다음_발화_시각("loadUs"))
                .isEqualTo(ZonedDateTime.of(월요일, LocalTime.of(21, 0), KST).toInstant());
    }

    @Test
    void 적재_실패는_전파되지_않는다() {
        doThrow(new BusinessException(ErrorCode.TOSS_API_ERROR, "랭킹 조회 실패"))
                .when(stockRankingLoadService).load(any(MarketCountry.class));

        assertThatCode(scheduler::loadKr).doesNotThrowAnyException();
        assertThatCode(scheduler::loadUs).doesNotThrowAnyException();
    }

    /**
     * 크론 문자열을 상수와 비교하는 대신 실제로 파싱해 <b>다음 발화 시각</b>을 구한다.
     * 스프링 크론은 6필드(초 포함)라, 5필드로 착각해 한 칸씩 밀려 써도 문자열 비교로는
     * 잡히지 않는다. zone 도 애너테이션에서 그대로 읽어 쓰므로 타임존이 바뀌면 함께 깨진다.
     */
    private java.time.Instant 다음_발화_시각(String methodName) throws NoSuchMethodException {
        Scheduled schedule = StockRankingCollectionScheduler.class
                .getMethod(methodName)
                .getAnnotation(Scheduled.class);

        ZoneId zone = ZoneId.of(schedule.zone());
        ZonedDateTime next = CronExpression
                .parse(schedule.cron())
                .next(ZonedDateTime.of(월요일, LocalTime.MIDNIGHT, zone));

        assertThat(next).isNotNull();
        return next.toInstant();
    }
}
