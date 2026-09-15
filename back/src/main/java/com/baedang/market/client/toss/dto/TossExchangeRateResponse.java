package com.baedang.market.client.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * {@code GET /api/v1/exchange-rate} 의 원본 응답 형태. {@code dto} 패키지가 Adapter
 * 패키지와 분리돼 있어 {@code public} 이 필요하지만, <b>{@code market.client.toss}
 * 밖(Service, Controller)에서는 절대 이 클래스를 import 하지 않는다는 규칙</b>으로
 * package-private 과 같은 효과를 의도합니다. Adapter 가 항상 도메인 모델
 * ({@code ExchangeRateQuote})로 변환해서 돌려줍니다.
 *
 * <p><b>실제 응답은 최상위가 {@code result} 로 한 번 더 감싸져 있습니다.</b>
 * (2026-08-26 실제 호출 캡처로 확인 — 호영님 공유. 처음 스펙 조회 때는 이 래핑을
 * 놓쳤었습니다.) 민호님의 {@code TossPriceResponse}도 같은 패턴({@code result} 필드)을
 * 쓰고 있어서 Toss API 공통 응답 포맷으로 보입니다.
 *
 * <p>{@code rate}/{@code midRate} 는 Toss 가 문자열로 내려주지만, 유효한 십진수
 * 문자열이면 Jackson 이 {@link BigDecimal} 필드로 바로 변환해줍니다.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} — Toss 가 필드를 추가해도
 * (예: {@code basisPoint}, {@code rateChangeType}) 우리가 안 쓰는 필드면 역직렬화가
 * 깨지지 않도록 방어합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossExchangeRateResponse(Result result) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            String baseCurrency,
            String quoteCurrency,
            BigDecimal rate,
            BigDecimal midRate,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil
    ) {
    }
}
