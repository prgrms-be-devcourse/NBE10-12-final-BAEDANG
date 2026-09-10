package com.baedang.account.support;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.trading.entity.Holding;
import com.baedang.user.entity.Account;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 활성 계좌 한 개의 평가 결과 묶음. 계좌 요약(#1)·보유 목록(#2)·투자 성향 리포트가
 * 공유하는 조회·평가 패스({@code AccountValuationService})의 산출물이다.
 *
 * <p>평가 금액 계산의 진실은 오직 이 패스 하나다 — 세 화면이 같은 시세·환율·반올림으로
 * 떨어지도록, 각자 다시 계산하지 않고 이 결과를 재사용한다.
 */
public record AccountValuation(
        Account account,
        List<Holding> holdings,
        Map<Long, QuoteSnapshot> quotes,
        List<HoldingValuation> valuations,
        BigDecimal usdKrwRate
) {
}
