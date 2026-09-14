package com.baedang.stock.service;

import com.baedang.stock.dto.StockDetailResponse;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.StockWarnings;
import com.baedang.stock.port.SymbolInfoPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 종목 상세 화면의 매수 유의사항(거래유의종목)을 Toss에서 읽어 온다.
 *
 * <p>유의사항은 <b>정보성 데이터</b>다 — 거래정지·정리매매와 달리 주문을 막지 않으므로,
 * 이 조회가 실패해도 거래 가능 여부는 절대 바뀌지 않는다. 대신 "유의사항이 없다"와
 * "확인하지 못했다"를 구분해야 하므로 {@link StockDetailResponse.WarningStatus#UNAVAILABLE}을 함께 내려준다.
 * 화면이 실패를 "유의사항 없음"으로 오인하면 경고 배지가 조용히 사라진다.
 *
 * <p>상세 화면은 몇 초 간격으로 종목을 다시 조회하므로, 호출마다 외부 API를 부르면
 * Toss 한도를 순식간에 태운다. 종목별로 짧은 TTL 캐시를 두되 <b>원본 유의사항만</b>
 * 캐시한다 — 활성 기간 판정은 읽는 시점의 날짜로 다시 계산해야 자정을 넘겨 만료된
 * 유의사항이 캐시에 남지 않는다.
 */
@Service
public class StockWarningQueryService {

    private static final Logger log = LoggerFactory.getLogger(StockWarningQueryService.class);

    /**
     * 유의사항 종류별 화면 문구.
     *
     * <p>통합 문구 하나로 뭉치면 <b>왜 유의종목인지</b> 알 수 없다 — 과열종목과 투자경고는
     * 제약이 서로 다르므로(투자경고는 신용거래 제한·현금 전액 예치) 사용자가 구분할 수
     * 있어야 한다. 표기는 `tools/terms.md`의 사용자 용어를 따른다.
     *
     * <p>토스가 새 코드를 추가할 수 있으므로 미지정 코드는 일반 문구로 폴백한다 —
     * 원천 코드는 {@code type}에 그대로 남아 나중에 매핑을 채울 수 있다.
     */
    private static final Map<String, String> LABEL_BY_TYPE = Map.of(
            "OVERHEATED", "과열종목",
            "INVESTMENT_WARNING", "투자경고",
            "VI_STATIC", "변동성완화장치");

    private static final String FALLBACK_LABEL = "거래유의종목";

    /** 종목별 캐시 상한. 계속 늘어나면 오래된 종목부터 밀어낸다. */
    private static final int MAX_CACHE_SIZE = 1000;

    private final SymbolInfoPort symbolInfoPort;
    private final Clock clock;
    private final Duration ttl;
    private final Map<Long, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);

    public StockWarningQueryService(
            SymbolInfoPort symbolInfoPort,
            Clock clock,
            @Value("${trading.stock-warning-cache-ttl:5m}") Duration ttl
    ) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("유의사항 캐시 TTL은 양수여야 합니다");
        }
        this.symbolInfoPort = symbolInfoPort;
        this.clock = clock;
        this.ttl = ttl;
    }

    /** 종목의 현재 활성 유의사항과 조회 성공 여부를 반환한다. 예외를 던지지 않는다. */
    public WarningSnapshot currentWarnings(Stock stock) {
        LocalDate today = LocalDate.now(clock);
        Cached cached;
        synchronized (cache) {
            cached = cache.get(stock.getStockId());
        }
        if (cached != null && cached.isFresh(clock.instant(), ttl)) {
            return new WarningSnapshot(activeWarnings(cached.warnings(), today), StockDetailResponse.WarningStatus.AVAILABLE);
        }

        try {
            StockWarnings fetched = symbolInfoPort.fetchStockWarnings(stock.getSymbol());
            List<StockWarnings.StockWarning> raw = fetched == null || fetched.warnings() == null
                    ? List.of()
                    : List.copyOf(fetched.warnings());
            remember(stock.getStockId(), new Cached(clock.instant(), raw));
            return new WarningSnapshot(activeWarnings(raw, today), StockDetailResponse.WarningStatus.AVAILABLE);
        } catch (RuntimeException exception) {
            // 실패는 성공으로 승격하지 않는다. 이미 확인한 값이 있으면 그대로 보여주고,
            // 한 번도 확인한 적이 없으면 "유의사항 없음"이 아니라 UNAVAILABLE 로 알린다.
            // 심볼과 예외 유형만 남긴다 — 외부 응답 본문·자격증명은 로그로 흘리지 않는다.
            log.warn("종목 유의사항 조회 실패: symbol={}, cause={}",
                    stock.getSymbol(), exception.getClass().getSimpleName());
            if (cached != null) {
                return new WarningSnapshot(activeWarnings(cached.warnings(), today), StockDetailResponse.WarningStatus.AVAILABLE);
            }
            return new WarningSnapshot(List.of(), StockDetailResponse.WarningStatus.UNAVAILABLE);
        }
    }

    private void remember(Long stockId, Cached cached) {
        synchronized (cache) {
            cache.put(stockId, cached);
            while (cache.size() > MAX_CACHE_SIZE) {
                cache.remove(cache.keySet().iterator().next());
            }
        }
    }

    private List<StockDetailResponse.Warning> activeWarnings(
            List<StockWarnings.StockWarning> warnings, LocalDate today) {
        return warnings.stream()
                .filter(warning -> warning.warningType() != null && !warning.warningType().isBlank())
                .filter(warning -> isActive(warning, today))
                .map(warning -> new StockDetailResponse.Warning(
                        warning.warningType(),
                        LABEL_BY_TYPE.getOrDefault(warning.warningType(), FALLBACK_LABEL)))
                .toList();
    }

    /** 시작일·종료일은 모두 포함(inclusive) 경계다. 값이 없는 쪽은 제한 없음으로 본다. */
    private boolean isActive(StockWarnings.StockWarning warning, LocalDate today) {
        if (warning.startDate() != null && today.isBefore(warning.startDate())) return false;
        return warning.endDate() == null || !today.isAfter(warning.endDate());
    }

    private record Cached(java.time.Instant fetchedAt, List<StockWarnings.StockWarning> warnings) {
        private boolean isFresh(java.time.Instant now, Duration ttl) {
            return fetchedAt.plus(ttl).isAfter(now);
        }
    }

    /** 유의사항 조회 결과. {@code UNAVAILABLE}은 "유의사항 없음"이 아니라 "확인 실패"다. */
    public record WarningSnapshot(
            List<StockDetailResponse.Warning> warnings,
            StockDetailResponse.WarningStatus status) {
    }
}
