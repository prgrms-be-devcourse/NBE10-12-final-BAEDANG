package com.baedang.stock.port;

import org.springframework.lang.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

public record RankingSnapshot(
        List<RankingEntry> entries,
        @Nullable OffsetDateTime rankedAt   // entries 가 비어 있으면 null (집계 결과 없음)
) {
}
