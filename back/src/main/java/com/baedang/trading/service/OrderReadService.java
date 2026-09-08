package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.trading.dto.OrderDetailResponse;
import com.baedang.trading.dto.ExecutionResponse;
import com.baedang.trading.dto.OrderExecutionsResponse;
import com.baedang.trading.dto.OrderPageResponse;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.entity.LedgerEntry;
import com.baedang.trading.entity.TradeExecution;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.repository.LedgerEntryRepository;
import com.baedang.trading.repository.TradeExecutionRepository;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.baedang.global.formatter.FinancialDecimalFormatter.krw;
import static com.baedang.global.formatter.FinancialDecimalFormatter.currency;
import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;
import static com.baedang.global.formatter.FinancialDecimalFormatter.rate;

@Service
@Transactional(readOnly = true)
public class OrderReadService {

    private final TradeOrderRepository orders;
    private final AccountRepository accounts;
    private final TradeExecutionRepository executions;
    private final LedgerEntryRepository ledgers;
    private final StockRepository stocks;

    public OrderReadService(
            TradeOrderRepository orders,
            AccountRepository accounts,
            TradeExecutionRepository executions,
            LedgerEntryRepository ledgers,
            StockRepository stocks
    ) {
        this.orders = orders;
        this.accounts = accounts;
        this.executions = executions;
        this.ledgers = ledgers;
        this.stocks = stocks;
    }

    public TradeOrder owned(Long userId, Long orderId) {
        TradeOrder o = orders.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        accounts.findByAccountIdAndUserId(o.getAccountId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        return o;
    }

    public OrderDetailResponse detail(Long userId, Long id) {
        TradeOrder order = owned(userId, id);
        return OrderDetailResponse.from(order, stock(order.getStockId()));
    }

    public OrderPageResponse list(Long userId, String cursor, int size) {
        validateSize(size);
        Long accountId = accounts.findByUserIdAndStatus(userId, AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND)).getAccountId();
        List<TradeOrder> rows = orders.history(accountId, decode(cursor, "orders:" + accountId, Long.MAX_VALUE), PageRequest.of(0, size + 1));
        boolean more = rows.size() > size;
        List<TradeOrder> selected = rows.stream().limit(size).toList();
        Map<Long, Stock> byStock = new HashMap<>();
        if (!selected.isEmpty()) {
            stocks.findByStockIdIn(selected.stream().map(TradeOrder::getStockId).distinct().toList())
                    .forEach(s -> byStock.put(s.getStockId(), s));
        }
        List<OrderDetailResponse> items = selected.stream().map(o -> {
            Stock stock = byStock.get(o.getStockId());
            if (stock == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            return OrderDetailResponse.from(o, stock);
        }).toList();
        return new OrderPageResponse(items, more ? encode("orders:" + accountId, items.getLast().orderId()) : null, more);
    }

    public OrderExecutionsResponse executions(Long userId, Long id, String cursor, int size) {
        TradeOrder order = owned(userId, id);
        Stock stock = stock(order.getStockId());
        validateSize(size);
        long after = decode(cursor, "executions:" + id, 0);
        if (after > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_CURSOR);
        }
        List<TradeExecution> rows = executions.findByOrderIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(id, (int) after, PageRequest.of(0, size + 1));
        List<TradeExecution> selected = rows.stream().limit(size).toList();
        Map<Long, LedgerEntry> byExecution = new HashMap<>();
        if (!selected.isEmpty()) {
            ledgers.findByExecutionIdIn(selected.stream().map(TradeExecution::getExecutionId).toList())
                    .forEach(l -> byExecution.put(l.getExecutionId(), l));
        }
        List<ExecutionResponse> items = selected.stream().map(e -> {
            LedgerEntry l = byExecution.get(e.getExecutionId());
            if (l == null || !id.equals(l.getOrderId())) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            return new ExecutionResponse(
                    e.getExecutionId(),
                    e.getSequenceNo(),
                    plain(e.getQuantity()),
                    currency(e.getPrice(), stock.getMarketCountry().defaultCurrency()),
                    rate(e.getExchangeRate()),
                    krw(e.getGrossAmountKrw()),
                    krw(e.getFeeKrw()),
                    krw(e.getTaxKrw()),
                    krw(e.getNetAmountKrw()),
                    krw(l.getBalanceAfter()),
                    e.getExecutedAt()
            );
        }).toList();
        boolean more = rows.size() > size;
        return new OrderExecutionsResponse(id,
                new OrderExecutionsResponse.StockSummary(stock.getSymbol(), stock.getName(), stock.getMarketCountry()),
                items, more ? encode("executions:" + id, items.getLast().sequenceNo()) : null, more);
    }

    private Stock stock(Long stockId) {
        return stocks.findById(stockId).orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
    }

    private static void validateSize(int size) {
        if (size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private static String encode(String scope, long id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((scope + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static long decode(String cursor, String scope, long fallback) {
        if (cursor == null) {
            return fallback;
        }
        try {
            if (cursor.length() > 128) {
                throw new IllegalArgumentException();
            }
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!text.startsWith(scope + ":")) {
                throw new IllegalArgumentException();
            }
            long id = Long.parseLong(text.substring(scope.length() + 1));
            if (id <= 0) {
                throw new IllegalArgumentException();
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_CURSOR);
        }
    }
}
