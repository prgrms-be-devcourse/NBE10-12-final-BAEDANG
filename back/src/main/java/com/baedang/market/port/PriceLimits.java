package com.baedang.market.port;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 단일 종목 요청의 상하한가. 미국의 두 가격 null은 정상적인 미제공 응답입니다. */
public record PriceLimits(OffsetDateTime timestamp, BigDecimal upperLimit,
                          BigDecimal lowerLimit, String currency) {}
