package com.baedang.stock.port;

import com.baedang.stock.entity.MarketCountry;

public interface RankingPort {

    RankingSnapshot fetchRanking(MarketCountry market);
}
