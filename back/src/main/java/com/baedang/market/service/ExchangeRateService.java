package com.baedang.market.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.dto.ExchangeRateLatestResponse;
import com.baedang.market.entity.ExchangeRate;
import com.baedang.market.repository.ExchangeRateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 환율 조회 서비스. 랭킹 화면 환율 배너({@code GET /api/exchange-rates/latest})를 위한
 * 최신 환율 + 전일 대비 등락 계산을 담당한다.
 */
@Service
public class ExchangeRateService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CHANGE_RATE_SCALE = 6;

    private final ExchangeRateRepository exchangeRateRepository;
    private final Clock clock;

    public ExchangeRateService(ExchangeRateRepository exchangeRateRepository, Clock clock) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.clock = clock;
    }

    /**
     * 최신 환율과 전일 대비 등락을 조회한다.
     *
     * <p>등락 기준은 "전일 자정(00:00 KST)" 시점의 환율이다 — 종목의 changeRate가
     * 하루 내내 고정된 prev_close 대비이듯, 환율도 하루 동안 같은 기준값을 쓴다
     * (조회 시점마다 24시간 전 값을 다시 구하는 rolling 방식이 아니라, 하루 동안
     * 고정되는 기준점 방식). 서비스 초기 등 그 시점 이전 데이터가 아직 없으면
     * 등락을 0으로 보고 최신 환율 자체는 그대로 내려준다 — 기준값이 없다고
     * 요청 전체를 실패시키지 않는다.
     */
    public ExchangeRateLatestResponse getLatest(String baseCurrency, String quoteCurrency) {
        ExchangeRate latest = exchangeRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc(baseCurrency, quoteCurrency)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND));

        OffsetDateTime todayMidnightKst = OffsetDateTime.ofInstant(clock.instant(), KST)
                .toLocalDate()
                .atStartOfDay(KST)
                .toOffsetDateTime();

        BigDecimal referenceRate = exchangeRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyAndRateAtLessThanEqualOrderByRateAtDesc(
                        baseCurrency, quoteCurrency, todayMidnightKst)
                .map(ExchangeRate::getMidRate)
                .orElse(latest.getMidRate());

        BigDecimal changeAmount = latest.getMidRate().subtract(referenceRate);
        BigDecimal changeRate = referenceRate.signum() == 0
                ? BigDecimal.ZERO
                : changeAmount.divide(referenceRate, CHANGE_RATE_SCALE, RoundingMode.HALF_UP);

        return new ExchangeRateLatestResponse(
                latest.getBaseCurrency(),
                latest.getQuoteCurrency(),
                latest.getMidRate().toPlainString(),
                changeAmount.toPlainString(),
                changeRate.toPlainString(),
                latest.getRateAt());
    }
}
