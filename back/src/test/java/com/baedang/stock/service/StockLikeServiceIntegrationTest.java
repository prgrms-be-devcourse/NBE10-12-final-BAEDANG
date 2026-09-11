package com.baedang.stock.service;

import com.baedang.stock.dto.StockLikePageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// getLikes 가 propagation = NEVER 라 테스트 트랜잭션을 끄고, 데이터는 매 테스트 전에 비운다.
@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(StockLikeService.class)
class StockLikeServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    // Toss 를 부르지 않도록 대체한다. 기본 반환값 null = 온디맨드 시세 조회 실패.
    @MockitoBean StockOnDemandQuoteService stockOnDemandQuoteService;

    @Autowired StockLikeService service;
    @Autowired JdbcTemplate jdbc;

    private Long user;
    private Long otherUser;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE stock_like, users, stock RESTART IDENTITY CASCADE");
        user = createUser();
        otherUser = createUser();
    }

    @Test
    void 이미_등록된_종목을_다시_등록해도_한_행만_남고_같은_id를_돌려준다() {
        Long stock = createStock();

        Long firstId = service.like(user, stock);
        Long secondId = service.like(user, stock);

        assertThat(likeCount(user, stock)).isEqualTo(1);
        assertThat(secondId).isNotNull().isEqualTo(firstId);
    }

    @Test
    void 남의_관심_종목_id로는_지울_수_없다() {
        Long stock = createStock();
        service.like(otherUser, stock);
        Long othersLikeId = service.getLikes(otherUser, null, null).items().get(0).stockLikeId();

        service.unlike(user, othersLikeId);

        assertThat(likeCount(otherUser, stock)).isEqualTo(1);
    }

    @Test
    void 최신_등록순으로_커서_페이지를_나눈다() {
        Long first = createStock();
        Long second = createStock();
        Long third = createStock();
        service.like(user, first);
        service.like(user, second);
        service.like(user, third);

        StockLikePageResponse page1 = service.getLikes(user, null, 2);
        StockLikePageResponse page2 = service.getLikes(user, page1.nextCursor(), 2);

        assertThat(page1.items()).extracting(StockLikePageResponse.Item::stockId).containsExactly(third, second);
        assertThat(page1.hasNext()).isTrue();
        assertThat(page2.items()).extracting(StockLikePageResponse.Item::stockId).containsExactly(first);
        assertThat(page2.hasNext()).isFalse();
    }

    @Test
    void 온디맨드_시세_조회에_실패하면_가격만_null로_응답한다() {
        Long stock = createStock();
        service.like(user, stock);

        StockLikePageResponse.Item item = service.getLikes(user, null, null).items().get(0);

        assertThat(item.stockId()).isEqualTo(stock);
        assertThat(item.prevClose()).isNull();
        assertThat(item.lastPrice()).isNull();
        assertThat(item.changeRate()).isNull();
    }

    private int likeCount(Long userId, Long stockId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM stock_like WHERE user_id = ? AND stock_id = ?",
                Integer.class, userId, stockId);
    }

    private Long createUser() {
        String key = UUID.randomUUID().toString().replace("-", "");
        return jdbc.queryForObject(
                "INSERT INTO users(email, password_hash, nickname) VALUES (?, 'x', ?) RETURNING user_id",
                Long.class, key + "@test.com", key.substring(0, 16));
    }

    private Long createStock() {
        String symbol = UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject("""
                INSERT INTO stock(symbol, market_country, market, name, currency, security_type)
                VALUES (?, 'KR', 'KOSPI', 'test', 'KRW', 'STOCK') RETURNING stock_id
                """, Long.class, symbol);
    }
}
