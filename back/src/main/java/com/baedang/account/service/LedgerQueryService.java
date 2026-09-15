package com.baedang.account.service;

import com.baedang.account.dto.LedgerResponse;
import com.baedang.account.support.LedgerCursor;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.normalizer.DomainNormalizer;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.entity.EntryType;
import com.baedang.trading.entity.LedgerEntry;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.repository.LedgerEntryRepository;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 체결 내역(원장) 조회 서비스.
 *
 * <p>계좌 평가(AccountService)와 관심사가 달라 별도로 둡니다 — 여기는 커서 페이지네이션과
 * 종목명 조인만 다루고, 금액은 원장에 저장된 값을 그대로 반환합니다(재계산 없음).
 */
@Service
@Transactional(readOnly = true)
public class LedgerQueryService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final StockRepository stockRepository;

    public LedgerQueryService(AccountRepository accountRepository,
                              LedgerEntryRepository ledgerEntryRepository,
                              TradeOrderRepository tradeOrderRepository,
                              StockRepository stockRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.tradeOrderRepository = tradeOrderRepository;
        this.stockRepository = stockRepository;
    }

    public LedgerResponse getLedger(Long userId, String cursor, Integer size, String entryType) {
        Account account = accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        int pageSize = clampSize(size);
        EntryType typeFilter = parseEntryType(entryType);
        Long cursorId = decodeCursor(cursor);

        // size + 1 을 요청해, 마지막 한 건의 존재로 다음 페이지 유무를 판단한다.
        List<LedgerEntry> rows = ledgerEntryRepository.findPage(
                account.getAccountId(), typeFilter, cursorId, PageRequest.of(0, pageSize + 1));

        boolean hasNext = rows.size() > pageSize;
        List<LedgerEntry> pageRows = hasNext ? rows.subList(0, pageSize) : rows;

        Map<Long, Stock> stockByOrderId = stocksByOrderId(pageRows);
        List<LedgerResponse.Item> items = pageRows.stream()
                .map(entry -> LedgerResponse.Item.of(entry, stockFor(entry, stockByOrderId)))
                .toList();

        // api-spec: nextCursor 는 "이 페이지 다음을 이어받을 지점"이라 마지막 페이지에도 채운다
        // (계속 조회할지는 hasNext 로 판단). 항목이 아예 없을 때만 null.
        String nextCursor = pageRows.isEmpty()
                ? null
                : LedgerCursor.encode(pageRows.get(pageRows.size() - 1).getEntryId());
        return new LedgerResponse(items, nextCursor, hasNext);
    }

    private int clampSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /** 잘못된 entryType 값은 잘못된 요청(400)이다 — enum 바인딩 실패로 500 이 나지 않게 문자열로 받아 검증한다. */
    private EntryType parseEntryType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EntryType.valueOf(DomainNormalizer.upperCode(raw));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지원하지 않는 entryType: " + raw);
        }
    }

    private Long decodeCursor(String cursor) {
        return (cursor == null || cursor.isBlank()) ? null : LedgerCursor.decode(cursor);
    }

    /** 초기금 지급(orderId null)은 종목이 없다. null 키로 맵을 조회하지 않도록 여기서 끊는다. */
    private Stock stockFor(LedgerEntry entry, Map<Long, Stock> stockByOrderId) {
        Long orderId = entry.getOrderId();
        return orderId == null ? null : stockByOrderId.get(orderId);
    }

    /**
     * 페이지 내 항목들의 종목을 orderId 기준으로 매핑합니다 (매수·매도만, 초기금 지급은 제외).
     * 원장 → 주문 → 종목을 각각 배치 조회해 N+1 을 피합니다.
     *
     * <p>주문·종목이 조회되지 않으면(상장폐지 정리 등) 해당 항목은 종목명 없이(null) 내려갑니다 —
     * 원장은 append-only 이력이므로, 조인 대상이 사라져도 <b>줄 자체는 반드시 보여야</b> 합니다.
     */
    private Map<Long, Stock> stocksByOrderId(List<LedgerEntry> entries) {
        List<Long> orderIds = entries.stream()
                .map(LedgerEntry::getOrderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }

        List<TradeOrder> orders = tradeOrderRepository.findByOrderIdIn(orderIds);
        List<Long> stockIds = orders.stream().map(TradeOrder::getStockId).distinct().toList();
        Map<Long, Stock> stockById = stockRepository.findByStockIdIn(stockIds).stream()
                .collect(Collectors.toMap(Stock::getStockId, Function.identity()));

        return orders.stream()
                .filter(order -> stockById.containsKey(order.getStockId()))
                .collect(Collectors.toMap(TradeOrder::getOrderId, order -> stockById.get(order.getStockId())));
    }
}
