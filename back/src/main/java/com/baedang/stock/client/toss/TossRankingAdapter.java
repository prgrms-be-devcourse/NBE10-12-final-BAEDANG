package com.baedang.stock.client.toss;

import com.baedang.global.clients.toss.TossSecuritiesClient;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.client.toss.dto.TossRankingResponse;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.port.RankingEntry;
import com.baedang.stock.port.RankingPort;
import com.baedang.stock.port.RankingSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Component
public class TossRankingAdapter implements RankingPort {
    private final TossSecuritiesClient tossSecuritiesClient;

    public TossRankingAdapter(TossSecuritiesClient tossSecuritiesClient) {
        this.tossSecuritiesClient = tossSecuritiesClient;
    }

    private static final Logger logger = LoggerFactory.getLogger(TossRankingAdapter.class);

    @Override
    public RankingSnapshot fetchRanking(MarketCountry market) {
        if (market == null) {
            logger.error("TossRankingAdapter: market is null.");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        String marketName = market.name();

        TossRankingResponse response = requireNonNullOrThrow(tossSecuritiesClient.get(
                "/api/v1/rankings",
                Map.of(
                        "type", "MARKET_TRADING_AMOUNT",
                        "marketCountry", marketName,
                        "duration", "1w",
                        "count", String.valueOf(100),
                        "excludeInvestmentCaution", String.valueOf(true)
                ),
                TossRankingResponse.class
        ), "response", marketName);

        TossRankingResponse.Result result = requireNonNullOrThrow(response.result(), "response.result", marketName);

        List<TossRankingResponse.Ranking> rankings = requireNonNullOrThrow(
                result.rankings(),
                "response.result.rankings",
                marketName
        );
        OffsetDateTime rankedAt = result.rankedAt();

        if (rankings.isEmpty() && rankedAt == null)
            return new RankingSnapshot(List.of(), null);
        if (!rankings.isEmpty() && rankedAt != null)
            return new RankingSnapshot(
                    rankingsToEntriesOrThrow(rankings, marketName),
                    rankedAt
            );

        logger.error(
                "TossRankingAdapter: Unexpected response ({}{}, {}{})",
                "rankings.size()=", rankings.size(),
                "rankedAt=", rankedAt
        );
        throw new BusinessException(ErrorCode.TOSS_API_ERROR);
    }

    private List<RankingEntry> rankingsToEntriesOrThrow(List<TossRankingResponse.Ranking> rankings, String marketName) {
        return rankings
                .stream()
                .map(ranking -> rankingToEntryOrThrow(ranking, marketName))
                .toList();
    }

    private RankingEntry rankingToEntryOrThrow(TossRankingResponse.Ranking ranking, String marketName) {
        requireNonNullOrThrow(ranking, "ranking", marketName);

        String symbol = requireNonNullOrThrow(ranking.symbol(), "ranking.symbol", marketName);

        int rank = validateRankOrThrow(ranking.rank(), "ranking.rank", marketName, symbol);

        String currency = requireNonNullOrThrow(ranking.currency(), "ranking.currency", marketName, symbol);
        TossRankingResponse.Price price = requireNonNullOrThrow(ranking.price(), "ranking.price", marketName, symbol);
        String tradingVolume = requireNonNullOrThrow(ranking.tradingVolume(), "ranking.tradingVolume", marketName, symbol);
        String tradingAmount = requireNonNullOrThrow(ranking.tradingAmount(), "ranking.tradingAmount", marketName, symbol);

        String lastPrice = requireNonNullOrThrow(price.lastPrice(), "ranking.price.lastPrice", marketName, symbol);
        String basePrice = requireNonNullOrThrow(price.basePrice(), "ranking.price.basePrice", marketName, symbol);
        String changeRate = price.changeRate();

        return new RankingEntry(
                rank,
                symbol,
                currency,
                stringToBigDecimalOrThrow(lastPrice, "lastPrice", marketName, symbol),
                stringToBigDecimalOrThrow(basePrice, "basePrice", marketName, symbol),
                changeRate == null ? null : stringToBigDecimalOrThrow(changeRate, "changeRate", marketName, symbol),
                stringToBigDecimalOrThrow(tradingVolume, "tradingVolume", marketName, symbol),
                stringToBigDecimalOrThrow(tradingAmount, "tradingAmount", marketName, symbol)
        );
    }

    private <T> T requireNonNullOrThrow(T value, String valueName, String marketName) {
        return requireNonNullOrThrow(value, valueName, marketName, "");
    }

    private <T> T requireNonNullOrThrow(T value, String valueName, String marketName, String symbol) {
        if (value == null) {
            logger.error(
                    "TossRankingAdapter(market={}{}): {} is null.",
                    marketName,
                    symbol.isBlank() ? "" : ", symbol=" + symbol,
                    valueName
            );
            throw new BusinessException(ErrorCode.TOSS_API_ERROR);
        }
        return value;
    }

    private BigDecimal stringToBigDecimalOrThrow(String string, String stringName, String marketName, String symbol) {
        try {
            return new BigDecimal(string);
        } catch (NumberFormatException exception) {
            logger.error(
                    "TossRankingAdapter(market={}{}): {} is not a number format.",
                    marketName,
                    symbol.isBlank() ? "" : ", symbol=" + symbol,
                    stringName
            );
            throw new BusinessException(ErrorCode.TOSS_API_ERROR);
        }
    }

    private int validateRankOrThrow(int rank, String rankName, String marketName, String symbol) {
        if (rank < 1) {
            logger.error(
                    "TossRankingAdapter(market={}, symbol={}): {} < 1.",
                    marketName,
                    symbol,
                    rankName
            );
            throw new BusinessException(ErrorCode.TOSS_API_ERROR);
        }
        return rank;
    }
}
