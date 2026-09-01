package com.baedang.market.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.dto.ExchangeRateHistoryResponse;
import com.baedang.market.dto.ExchangeRateLatestResponse;
import com.baedang.market.entity.ExchangeRate;
import com.baedang.market.repository.ExchangeRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.List;
import java.util.Locale;

import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;
import static com.baedang.global.formatter.FinancialDecimalFormatter.rate;

/**
 * 환율 조회 서비스. 랭킹 화면 환율 배너({@code GET /api/exchange-rates/latest})를 위한
 * 최신 환율 + 전일 대비 등락 계산을 담당한다.
 */
@Service
@Transactional(readOnly = true)
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CHANGE_RATE_SCALE = 6;
    private static final String DEFAULT_BASE_CURRENCY = "USD";
    private static final String DEFAULT_QUOTE_CURRENCY = "KRW";


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
     * 요청 전체를 실패시키지 않는다. 이 경우는 정상 운영 중이라면 사실상
     * 일어나면 안 되는 상황(매시 정각 적재)이라 WARN 로그를 남긴다.
     *
     * <p>base/quote는 대소문자를 가리지 않는다 — 저장은 항상 대문자(USD/KRW)라
     * 소문자로 들어와도 매치되도록 여기서 정규화한다.
     */
    public ExchangeRateLatestResponse getLatest(String baseCurrency, String quoteCurrency) {
        String base = baseCurrency.toUpperCase();
        String quote = quoteCurrency.toUpperCase();

        ExchangeRate latest = exchangeRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc(base, quote)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND));
        BigDecimal latestRate = displayRate(latest);

        OffsetDateTime todayMidnightKst = todayMidnightKst();
        BigDecimal referenceRate = exchangeRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyAndRateAtLessThanEqualOrderByRateAtDesc(base, quote, todayMidnightKst)
                .map(this::displayRate)
                .orElseGet(() -> {
                    log.warn("[{}/{}] 전일 자정({}) 이전 환율이 없어 등락을 0으로 처리합니다.", base, quote, todayMidnightKst);
                    return latestRate;
                });

        BigDecimal changeAmount = latestRate.subtract(referenceRate);
        BigDecimal changeRate = referenceRate.signum() == 0
                ? BigDecimal.ZERO
                : changeAmount.divide(referenceRate, CHANGE_RATE_SCALE, RoundingMode.HALF_UP);

        return new ExchangeRateLatestResponse(
                latest.getBaseCurrency(),
                latest.getQuoteCurrency(),
                rate(latestRate),
                plain(changeAmount),
                plain(changeRate),
                latest.getRateAt());
    }

    public ExchangeRateHistoryResponse getHistory(String period) {
        OffsetDateTime from = periodStart(period);

        List<ExchangeRateHistoryResponse.Item> items =
                exchangeRateRepository.findByBaseCurrencyAndQuoteCurrencyAndRateAtGreaterThanEqualOrderByRateAtAsc(
                        DEFAULT_BASE_CURRENCY, DEFAULT_QUOTE_CURRENCY, from
                )
                        .stream()
                        .map(exchangeRate -> new ExchangeRateHistoryResponse.Item(
                                exchangeRate.getRateAt(), rate(displayRate(exchangeRate)))
                        ).toList();
        return new ExchangeRateHistoryResponse(items);
    }

    /**
     * 화면 표시용 환율. mid_rate가 비어 있으면(nullable 컬럼) 매매기준율 대신
     * 실거래 환율(rate)로 대체한다 — {@code AccountService.displayRate()}와 같은 방어.
     */
    private BigDecimal displayRate(ExchangeRate exchangeRate) {
        return exchangeRate.getMidRate() != null ? exchangeRate.getMidRate() : exchangeRate.getRate();
    }

    private OffsetDateTime todayMidnightKst() {
        LocalDate today = OffsetDateTime.ofInstant(clock.instant(), KST).toLocalDate();
        return today.atStartOfDay(KST).toOffsetDateTime();
    }

    private OffsetDateTime periodStart(String period) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        String normalized = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);

        return switch (normalized){
            case "1d" -> now.minusDays(1);
            case "1w" -> now.minusWeeks(1);
            case "1m" -> now.minusMonths(1);
            case "3m" -> now.minusMonths(3);
            case "1y" -> now.minusYears(1);
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT,"period="+period);
        };
    }
}
