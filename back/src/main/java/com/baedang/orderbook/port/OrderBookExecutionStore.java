package com.baedang.orderbook.port;

import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.model.LockedOrderBook;

import java.util.Optional;

public interface OrderBookExecutionStore {

    /**
     * 지정가 부분 체결 엔진(#122)이 동일 DB 트랜잭션에서 버전과 해당 방향의 레벨을 잠글 때 씁니다.
     * V2는 요청 방향의 실제 레벨(0~10개)을 잠급니다.
     * 당일 상하한가와 호가 단위로 계산한 가격 배열이 정확히 일치해야 합니다.
     *
     * <p>락 순서는 {@code order_book_version → order_book_level}이며,
     * 호출 전에 #122가 {@code account → trade_order}를 먼저 잠근 동일 트랜잭션이
     * 반드시 존재해야 합니다 ({@code Propagation.MANDATORY}).
     *
     * <p>기대한 version/revision과 실제 DB 상태가 다르거나 이미 종료된 버전이면 {@code empty}를 반환합니다.
     */
    Optional<LockedOrderBook> lockForExecution(
            Long stockId,
            Long expectedBookVersion,
            Long expectedRevision,
            OrderBookSide side
    );
}
