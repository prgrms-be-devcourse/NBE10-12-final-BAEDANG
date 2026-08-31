package com.baedang.market.service;

import com.baedang.market.model.PrevCloseUpdateResult;
import com.baedang.market.repository.PrevCloseUpdateRepository;
import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 다음 정규장 시작 전에 상위 종목의 등락률 기준가를 갱신합니다. */
@Service
public class PrevCloseUpdateService {

    private static final Logger log = LoggerFactory.getLogger(PrevCloseUpdateService.class);

    private final PrevCloseUpdateRepository prevCloseUpdateRepository;

    public PrevCloseUpdateService(PrevCloseUpdateRepository prevCloseUpdateRepository) {
        this.prevCloseUpdateRepository = prevCloseUpdateRepository;
    }

    @Transactional
    public PrevCloseUpdateResult update(MarketCountry marketCountry) {
        Objects.requireNonNull(marketCountry, "marketCountry must not be null");

        PrevCloseUpdateResult result = prevCloseUpdateRepository.update(marketCountry);
        log.info(
                "[prev-close] 갱신 완료: market={} target={} updated={} fallback={} skipped={}",
                marketCountry,
                result.targetCount(),
                result.updatedCount(),
                result.fallbackCount(),
                result.skippedCount()
        );
        return result;
    }
}
