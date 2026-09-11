package com.baedang.market.event.port;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.model.ConfirmedMarketEvent;
import com.baedang.market.event.model.KindRssBatch;
import com.baedang.market.event.model.MarketEventCandidate;

import java.util.Optional;

public interface MarketEventSourcePort {

    KindRssBatch fetchCandidates(KrMarket market);

    Optional<ConfirmedMarketEvent> fetchConfirmed(MarketEventCandidate candidate);
}
