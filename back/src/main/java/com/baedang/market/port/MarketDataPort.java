package com.baedang.market.port;

import java.util.List;

public interface MarketDataPort {
    List<PriceQuote> fetchPrice(List<String> symbols);
}
