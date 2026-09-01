package com.baedang.account.dto;

import com.baedang.stock.entity.Stock;
import com.baedang.trading.entity.LedgerEntry;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.baedang.global.formatter.FinancialDecimalFormatter.krw;
import static com.baedang.global.formatter.FinancialDecimalFormatter.rate;

/**
 * 마이페이지 체결 내역. {@code GET /accounts/me/ledger} 의 응답입니다.
 *
 * <p>원장({@code ledger_entry})은 "돈이 어떻게 움직였는가"의 기록입니다.
 * 금액({@code amount}·{@code balanceAfter}·{@code exchangeRate})은 저장된 <b>숫자값을 바꾸지 않고</b>
 * 내려보냅니다 — 체결 시점에 확정된 원화 금액이라 재계산하지 않으며, 문자열의 불필요한 후행 0만 제거합니다.
 *
 * <p>금액은 문자열, {@code entryId}·{@code orderId} 는 숫자입니다.
 * {@code INITIAL_DEPOSIT} 은 주문이 없으므로 {@code orderId}·{@code symbol}·{@code name} 이
 * {@code null} 이고 응답에서 생략됩니다.
 */
public record LedgerResponse(
        List<Item> items,
        String nextCursor,
        boolean hasNext
) {

    public record Item(
            Long entryId,
            String entryType,
            String amount,
            String balanceAfter,
            String exchangeRate,
            String memo,
            Long orderId,
            String symbol,
            String name,
            OffsetDateTime occurredAt
    ) {

        /** {@code stock} 은 주문 조인 결과(초기금 지급이거나 데이터 누락 시 {@code null}). */
        public static Item of(LedgerEntry entry, Stock stock) {
            return new Item(
                    entry.getEntryId(),
                    entry.getEntryType().name(),
                    krw(entry.getAmount()),
                    krw(entry.getBalanceAfter()),
                    rate(entry.getExchangeRate()),
                    entry.getMemo(),
                    entry.getOrderId(),
                    stock != null ? stock.getSymbol() : null,
                    stock != null ? stock.getName() : null,
                    OffsetDateTime.ofInstant(entry.getOccurredAt().toInstant(), ZoneOffset.UTC)
            );
        }
    }
}
