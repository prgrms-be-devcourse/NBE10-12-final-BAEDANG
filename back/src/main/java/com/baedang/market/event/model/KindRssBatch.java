package com.baedang.market.event.model;

import java.util.List;
import java.util.Objects;

public record KindRssBatch(
        List<MarketEventCandidate> candidates,
        int parseErrorCount
) {
    public KindRssBatch {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
        if (parseErrorCount < 0) {
            throw new IllegalArgumentException("parseErrorCount must not be negative: " + parseErrorCount);
        }
    }
}
