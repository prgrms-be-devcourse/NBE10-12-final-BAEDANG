package com.baedang.market.dto;

import com.baedang.stock.entity.MarketCountry;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /api/market/status} 응답. 프론트가 <b>거래 버튼 활성/비활성</b>과
 * <b>"실시간 / 종가" 라벨</b>을 결정하는 데 쓴다.
 *
 * <p>{@code serverTime} 은 KST(+09:00) 로 내려준다 — api-spec 이 전 구간 +09:00 이고
 * {@code opensAt}/{@code closesAt} 도 시장 시각(KST) 이라 응답 내부 일관성을 위해서다.
 * (계좌·마이페이지 API 의 UTC 정규화 컨벤션에서 이 엔드포인트만 의식적으로 divergence.)
 */
public record MarketStatusResponse(
        List<Market> markets,
        OffsetDateTime serverTime
) {

    /**
     * 시장별 개장 상태.
     *
     * <p><b>{@code @JsonInclude(ALWAYS)}</b>: 전역 jackson 설정이 {@code NON_NULL} 이라
     * null 필드 키가 생략되지만, api-spec 은 {@code "opensAt": null}/{@code "nextOpensAt": null}
     * 을 <b>명시적 키로</b> 문서화한다. 프론트가 {@code res.opensAt === null} 로 비교해도
     * 안전하도록(키 부재 시 {@code undefined} 함정 회피) 이 DTO 는 null 도 그대로 emit 한다.
     *
     * @param open        지금 이 순간 정규장 운영 중인지(open-NOW). 거래일 여부(open-DAY) 와 다르다.
     * @param opensAt     {@code open=true} 면 오늘 정규장 시작 시각, 아니면 null.
     * @param closesAt    {@code open=true} 면 오늘 정규장 종료 시각, 아니면 null.
     * @param nextOpensAt {@code open=false} 면 다음 정규장 개장 시각, 열려 있으면 null.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Market(
            MarketCountry marketCountry,
            boolean open,
            OffsetDateTime opensAt,
            OffsetDateTime closesAt,
            OffsetDateTime nextOpensAt
    ) {
    }
}
