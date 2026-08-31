package com.baedang.market.service;

import com.baedang.market.model.PrevCloseUpdateResult;
import com.baedang.market.repository.PrevCloseUpdateRepository;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/** 캘린더 외부 호출과 분리된 전일 종가 DB 갱신 트랜잭션 경계입니다. */
@Service
public class PrevCloseUpdateTransactionService {

    private final PrevCloseUpdateRepository prevCloseUpdateRepository;

    public PrevCloseUpdateTransactionService(PrevCloseUpdateRepository prevCloseUpdateRepository) {
        this.prevCloseUpdateRepository = prevCloseUpdateRepository;
    }

    @Transactional
    public PrevCloseUpdateResult update(
            MarketCountry marketCountry,
            Optional<LocalDate> expectedTradeDate
    ) {
        return expectedTradeDate
                .map(date -> prevCloseUpdateRepository.updateForTradeDate(marketCountry, date))
                .orElseGet(() -> prevCloseUpdateRepository.updateFromLastPrice(marketCountry));
    }
}
