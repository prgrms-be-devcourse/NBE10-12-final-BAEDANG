package com.baedang.stock.port;

import org.springframework.lang.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

public record RankingSnapshot(
        List<RankingEntry> entries,
        @Nullable OffsetDateTime rankedAt
) {
}
