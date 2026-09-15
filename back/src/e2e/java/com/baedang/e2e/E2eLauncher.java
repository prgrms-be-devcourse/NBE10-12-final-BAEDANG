package com.baedang.e2e;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEvent;
import com.baedang.market.event.entity.MarketEventSource;
import com.baedang.market.event.repository.MarketEventRepository;
import com.baedang.market.service.MarketTradingDayPolicy;
import com.baedang.orderbook.scheduler.OrderBookRefreshScheduler;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.scheduler.LimitOrderExecutionWorker;
import com.baedang.trading.service.LimitOrderExpirationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Base64;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.Executors;

/** 단일 테스트 제어 서버. 운영 클래스패스에는 존재하지 않으며 루프백/실행별 키만 허용합니다. */
public final class E2eLauncher {
    private static final Logger log = LoggerFactory.getLogger(E2eLauncher.class);
    private ConfigurableApplicationContext app;
    private int haltSequence;
    private final String token = required("E2E_CONTROL_TOKEN");
    private final String jdbcUrl = required("E2E_DB_URL");
    private final String password = required("E2E_DB_PASSWORD");

    public static void main(String[] args) throws IOException {
        E2eLauncher launcher = new E2eLauncher();
        if (!launcher.jdbcUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/baedang_e2e")) {
            throw new IllegalArgumentException("E2E 전용 루프백 DB만 사용할 수 있습니다");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18089), 0);
        server.createContext("/", launcher::handle);
        server.setExecutor(Executors.newSingleThreadExecutor());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); launcher.close(); }));
        server.start();
        log.info("E2E 제어 서버 준비 완료");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("필수 E2E 환경변수 누락: " + name);
        return value;
    }

    private synchronized void close() {
        if (app != null) { app.close(); app = null; }
    }

    private void start(String market) {
        close();
        SpringApplication application = new SpringApplication(E2eApplication.class);
        application.setRegisterShutdownHook(false);
        app = application.run(
                "--server.address=127.0.0.1", "--server.port=18088",
                "--mail.enabled=false",
                "--spring.datasource.url=" + jdbcUrl, "--spring.datasource.username=baedang_e2e",
                "--spring.datasource.password=" + password,
                "--toss.enabled=true", "--kis.enabled=false", "--krx.market-events.enabled=false",
                "--toss.load-stock-master=false", "--toss.load-stock-master-detail=false", "--toss.load-stock-ranking=false",
                "--report.seed.enabled=false", "--cors.allowed-origins=http://127.0.0.1:13000",
                "--auth.jwt.secret=" + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)),
                "--logging.level.org.hibernate.SQL=OFF", "--logging.level.root=WARN",
                "--spring.main.banner-mode=off");
        clearData();
        haltSequence = 0;
        E2eClock clock = app.getBean(E2eClock.class);
        clock.reset("US".equals(market) ? MarketCountry.US : MarketCountry.KR);
        JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
        jdbc.update("INSERT INTO stock(symbol,market_country,market,name,currency,security_type,is_ranked,rank_no) VALUES ('005930','KR','KOSPI','테스트전자','KRW','STOCK',true,1),('AAPL','US','NASDAQ','테스트애플','USD','STOCK',true,1)");
        jdbc.update("UPDATE stock SET trading_amount=100000000");
        Instant now = clock.instant();
        MarketTradingDayPolicy tradingDays = app.getBean(MarketTradingDayPolicy.class);
        for (MarketCountry country : MarketCountry.values()) {
            LocalDate tradeDate = now.atZone(country.zoneId()).toLocalDate();
            LocalDate prevCloseDate = tradingDays.previousTradingDay(country, tradeDate)
                    .orElseThrow(() -> new IllegalStateException("E2E 전일 종가 거래일을 찾을 수 없습니다: " + country));
            jdbc.update("INSERT INTO quote_snapshot(stock_id,last_price,currency,quote_at,collected_at,prev_close,prev_close_date,lower_limit,upper_limit,price_limit_date) SELECT stock_id,CASE WHEN market_country='KR' THEN 10000 ELSE 100 END,currency,?,?,CASE WHEN market_country='KR' THEN 9900 ELSE 99 END,?::date,CASE WHEN market_country='KR' THEN 9000 END,CASE WHEN market_country='KR' THEN 11000 END,CASE WHEN market_country='KR' THEN ?::date END FROM stock WHERE market_country=?",
                    now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), prevCloseDate, tradeDate, country.name());
        }
        refreshEvidence();
    }

    private void clearData() {
        JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
        // 개발 DB에는 연결할 수 없으며 Flyway 이력과 정적 용어 데이터는 보존합니다.
        jdbc.execute("TRUNCATE users, order_book_version, market_event, leaderboard_run CASCADE");
        for (String table : new String[]{"stock_like", "stock_financial_period", "stock_financial_sync", "stock_industry",
                "stock_external_id", "quote_snapshot", "daily_candle", "minute_candle", "stock", "market_calendar", "exchange_rate"}) {
            jdbc.execute("DELETE FROM " + table);
        }
    }

    private void refreshEvidence() {
        JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
        Instant now = app.getBean(E2eClock.class).instant();
        jdbc.update("UPDATE quote_snapshot SET quote_at=?,collected_at=?", now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
        jdbc.execute("DELETE FROM exchange_rate");
        jdbc.update("INSERT INTO exchange_rate(base_currency,quote_currency,rate,mid_rate,valid_from,valid_until,collected_at) VALUES ('USD','KRW',1400,1400,?,?,?)",
                now.minusSeconds(60).atOffset(ZoneOffset.UTC), now.plusSeconds(3600).atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
    }

    private synchronized void handle(HttpExchange request) throws IOException {
        String action = request.getRequestURI().getPath();
        try {
            if (!token.equals(request.getRequestHeaders().getFirst("X-E2E-Key"))) { reply(request, 403, "forbidden"); return; }
            if (action.equals("/health") && request.getRequestMethod().equals("GET")) { reply(request, 200, "ready"); return; }
            if (!request.getRequestMethod().equals("POST")) { reply(request, 405, "method"); return; }
            Map<String, String> params;
            try {
                params = parseQuery(request.getRequestURI().getRawQuery());
            } catch (IllegalArgumentException exception) {
                // 잘못된 인코딩만 요청 오류로 분류하고 원본 쿼리는 기록하지 않습니다.
                log.warn("E2E 제어 명령 쿼리 인코딩 오류: action={}", action);
                reply(request, 400, "invalid query encoding");
                return;
            }
            if (action.equals("/reset")) start(params.getOrDefault("market", "KR"));
            else if (action.equals("/clear")) {
                if (app != null) {
                    try { clearData(); } finally { close(); }
                }
            } else {
                if (app == null) throw new IllegalStateException("시나리오를 먼저 시작하세요");
                JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
                E2eClock clock = app.getBean(E2eClock.class);
                switch (action) {
                    case "/publish" -> app.getBean(OrderBookRefreshScheduler.class).refreshOrderBooks();
                    case "/tick" -> app.getBean(LimitOrderExecutionWorker.class).tick();
                    case "/advance" -> {
                        long seconds = Long.parseLong(params.getOrDefault("seconds", "1"));
                        if (seconds < 0 || seconds > 86400) throw new IllegalArgumentException("허용 시간 초과");
                        clock.set(clock.instant().plusSeconds(seconds));
                        refreshEvidence();
                    }
                    case "/expire" -> app.getBean(LimitOrderExpirationService.class).expireDue();
                    case "/limits" -> {
                        boolean available = Boolean.parseBoolean(params.getOrDefault("available", "true"));
                        app.getBean(E2eMarketData.class).limitsAvailable = available;
                        jdbc.update("UPDATE quote_snapshot SET price_limit_date=? WHERE currency='KRW'",
                                available ? clock.instant().atZone(MarketCountry.KR.zoneId()).toLocalDate() : null);
                    }
                    case "/upper" -> jdbc.update("UPDATE quote_snapshot SET last_price=upper_limit WHERE currency='KRW'");
                    case "/liquidity" -> jdbc.update("UPDATE order_book_level SET remaining_quantity=1");
                    case "/halt" -> app.getBean(MarketEventRepository.class).saveAndFlush(MarketEvent.circuitBreaker(
                            MarketEventSource.KRX_KIND, String.format(Locale.ROOT, "%014d", ++haltSequence), KrMarket.KOSPI, 1,
                            clock.instant(), clock.instant().plusSeconds(1200), clock.instant(), clock.instant(),
                            "테스트 서킷브레이커", URI.create("https://kind.krx.co.kr/external/e2e")));
                    default -> { reply(request, 404, "unknown"); return; }
                }
            }
            reply(request, 200, "ok");
        } catch (Exception exception) {
            log.error("E2E 제어 명령 처리 실패: action={}", action, exception);
            reply(request, 500, exception.getClass().getSimpleName());
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        // 구분자를 먼저 분리해야 값에 포함된 %26과 %3D가 새 파라미터가 되지 않습니다.
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                params.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private void reply(HttpExchange request, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        request.sendResponseHeaders(status, body.length);
        try (request) { request.getResponseBody().write(body); }
    }
}
