package com.baedang.report.service;

import com.baedang.account.service.AccountValuationService;
import com.baedang.account.support.AccountValuation;
import com.baedang.account.support.HoldingValuation;
import com.baedang.account.support.ReturnRateCalculator;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.report.dto.PersonalityReportResponse;
import com.baedang.report.support.InvestmentProfile;
import com.baedang.report.support.InvestmentTypeClassifier;
import com.baedang.report.support.InvestmentTypeClassifier.HoldingSlice;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.user.entity.Account;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 투자 성향 리포트 조회 서비스(현재 활성 계좌=라운드 기준, 온디맨드).
 *
 * <p>평가금액은 계좌 요약·보유 목록과 <b>같은 {@link AccountValuationService}</b> 로만 구한다
 * (단일 평가 지점). 계좌 수익률은 <b>초기자본 대비 총손익</b>이다 —
 * {@code (cash + stockValue − initial_cash)/initial_cash}, 실현+미실현을 모두 포함한다.
 * (계좌 요약의 미실현/원가 손익률과는 다른 지표라 재사용하지 않는다.)
 *
 * <p>4주 이상 보유 종목 성과 섹션은 {@code trade_order} 이력 재생이 필요해 후속(1b)으로 분리한다.
 */
@Service
@Transactional(readOnly = true)
public class PersonalityReportService {

    private final AccountValuationService accountValuationService;
    private final StockRepository stockRepository;
    private final InvestmentTypeClassifier classifier;
    private final Clock clock;

    public PersonalityReportService(AccountValuationService accountValuationService,
                                    StockRepository stockRepository,
                                    InvestmentTypeClassifier classifier,
                                    Clock clock) {
        this.accountValuationService = accountValuationService;
        this.stockRepository = stockRepository;
        this.classifier = classifier;
        this.clock = clock;
    }

    public PersonalityReportResponse getReport(Long userId) {
        AccountValuation valued = accountValuationService.valuateActiveAccount(userId);
        Account account = valued.account();

        BigDecimal stockValue = valued.valuations().stream()
                .map(HoldingValuation::evalWon)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAsset = account.getCashBalance().add(stockValue);
        BigDecimal totalPnl = totalAsset.subtract(account.getInitialCash());
        // 초기자본은 계좌별 저장 컬럼이라 항상 양수 → 라운드 내 고정 분모.
        BigDecimal returnRate = ReturnRateCalculator.calculate(totalPnl, account.getInitialCash());

        InvestmentProfile profile = classifier.classify(toSlices(valued));

        OffsetDateTime asOf = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return PersonalityReportResponse.of(
                account, stockValue, totalAsset, totalPnl, returnRate, profile, asOf);
    }

    /** 평가 결과 + 종목 마스터를 합쳐 분류기 입력으로 변환한다. */
    private List<HoldingSlice> toSlices(AccountValuation valued) {
        if (valued.valuations().isEmpty()) {
            return List.of();
        }
        List<Long> stockIds = valued.valuations().stream().map(HoldingValuation::stockId).toList();
        Map<Long, Stock> stocks = stockRepository.findByStockIdIn(stockIds).stream()
                .collect(Collectors.toMap(Stock::getStockId, Function.identity()));

        return valued.valuations().stream()
                .map(v -> {
                    Stock stock = requireStock(stocks, v.stockId());
                    return new HoldingSlice(
                            v.evalWon(),
                            stock.getStockCategory(),
                            stock.getMarketCountry(),
                            stock.getLeverageFactor());
                })
                .toList();
    }

    private Stock requireStock(Map<Long, Stock> stocks, Long stockId) {
        Stock stock = stocks.get(stockId);
        if (stock == null) {
            throw new BusinessException(
                    ErrorCode.STOCK_NOT_FOUND, "보유 종목 stockId=" + stockId + " 의 종목 마스터가 없습니다");
        }
        return stock;
    }
}
