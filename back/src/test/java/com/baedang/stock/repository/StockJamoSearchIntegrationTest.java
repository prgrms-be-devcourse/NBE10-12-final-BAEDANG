package com.baedang.stock.repository;

import com.baedang.global.config.JpaConfig;
import com.baedang.stock.dto.StockSearchResponse;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.service.StockSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자모·초성 검색 (#148).
 *
 * <p>분해 로직이 {@code hangul_jamo} SQL 함수에만 있어 실제 DB 없이는 검증할 수 없습니다.
 */
@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StockSearchService.class, JpaConfig.class})
class StockJamoSearchIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18").asCompatibleSubstituteFor("postgres"));

    @Autowired
    StockRepository stocks;

    @Autowired
    StockSearchService search;

    @BeforeEach
    void setUp() {
        save("005930", "삼성전자");
        save("017390", "서울가스");
        save("003570", "성우하이텍");
        save("036460", "김천에너지");
    }

    /**
     * 순위는 완성형 축이 위치 축을 이깁니다 — '성우하이텍'(ㅅㅓㅇ…)은 '서' 에 종성이 붙은
     * 조합중 접두라 완성형 접두인 '서울가스'(ㅅㅓ^…) 아래지만, 중간에 걸린 '삼성전자' 보다는
     * 위입니다.
     */
    @Test
    @DisplayName("1자 검색 — 'ㅅㅓ' 는 '성' 의 접두이므로 삼성전자·성우하이텍도 함께 잡힌다")
    void 한글자_검색() {
        assertThat(names("서")).containsExactly("서울가스", "성우하이텍", "삼성전자");
    }

    @Test
    @DisplayName("독립 초성으로 검색할 수 있다")
    void 초성_검색() {
        assertThat(names("ㅅㅅㅈㅈ")).containsExactly("삼성전자");
    }

    @Test
    @DisplayName("미완성 자모(김ㅊ)와 조합 중인 음절(김처)이 모두 접두 매칭된다")
    void 미완성_자모_검색() {
        assertThat(names("김ㅊ")).containsExactly("김천에너지");
        assertThat(names("김처")).containsExactly("김천에너지");
    }

    @Test
    @DisplayName("종성이 확정된 검색어는 글자 경계를 넘지 않는다 — '성' 은 '서울가스' 를 잡지 않는다")
    void 경계_오탐_방지() {
        assertThat(names("성")).containsExactly("성우하이텍", "삼성전자").doesNotContain("서울가스");
    }

    /**
     * 컬럼은 종성 없는 글자에 {@code ^} 를 패딩하므로, 검색어도 같은 규칙
     * ({@code partialTail = false})으로 분해해야 완전일치가 잡힙니다.
     * 검색용 규칙({@code true})으로 순위를 매기면 '카카오' 가 rank 0 을 못 받아
     * '카카오뱅크' 와 동률이 됩니다.
     */
    @Test
    @DisplayName("종성 없이 끝나는 이름도 완전일치가 접두일치보다 위 (#148)")
    void 완전일치_우선() {
        save("035720", "카카오");
        save("323410", "카카오뱅크");

        assertThat(names("카카오")).containsExactly("카카오", "카카오뱅크");
    }

    @Test
    @DisplayName("미완성 입력('삼ㅅ')도 접두 일치가 부분 일치보다 위 (#148)")
    void 미완성_입력_접두_우선() {
        save("999002", "가나삼성");

        assertThat(names("삼ㅅ")).containsExactly("삼성전자", "가나삼성");
    }

    /**
     * 조합 중인 마지막 글자('처' → '천')는 완성될 글자를 추측한 것이라, 완성형 그대로의
     * 일치보다 아래입니다 — 위치(접두/부분)보다 완성형 여부가 먼저입니다.
     */
    @Test
    @DisplayName("조합 중인 음절('김처')은 완성형 일치보다 아래, 그 안에서 접두 우선 (#148)")
    void 조합중_음절_순위() {
        save("999003", "한김처머시기");
        save("999004", "가나김천");

        assertThat(names("김처")).containsExactly("한김처머시기", "김천에너지", "가나김천");
    }

    /**
     * 일치 정확도가 같으면 실제 랭킹 순입니다. 셋 다 '테크' 를 중간에 포함해 같은 순위라
     * 이름순이면 가·나·다 인데, rankNo 가 그걸 뒤집어야 검증이 됩니다.
     * 랭킹 밖(rankNo = null)은 맨 뒤로 갑니다.
     */
    @Test
    @DisplayName("일치 정확도가 같으면 실제 랭킹(rankNo) 순, 랭킹 밖은 뒤로")
    void 랭킹순_정렬() {
        save("999011", "가테크");
        saveRanked("999012", "나테크", 50);
        saveRanked("999013", "다테크", 3);

        assertThat(names("테크")).containsExactly("다테크", "나테크", "가테크");
    }

    /**
     * NFD 로 저장된 이름은 완성형 범위 밖이라 분해되지 않고 통과합니다.
     * 함수 입구의 {@code normalize(txt, NFC)} 가 빠지면 그 종목만 조용히 검색에서 사라집니다.
     */
    @Test
    @DisplayName("NFD 로 저장된 종목명도 NFC 검색어로 찾을 수 있다")
    void 유니코드_정규화() {
        save("999001", Normalizer.normalize("한화솔루션", Normalizer.Form.NFD));

        // name 컬럼은 원문(NFD)을 그대로 두므로 이름이 아니라 심볼로 확인합니다.
        assertThat(symbols("한화")).containsExactly("999001");
    }

    private List<String> symbols(String query) {
        return search.search(query, 10).items().stream().map(StockSearchResponse.Item::symbol).toList();
    }

    private List<String> names(String query) {
        return search.search(query, 10).items().stream().map(StockSearchResponse.Item::name).toList();
    }

    private void saveRanked(String symbol, String name, int rankNo) {
        Stock stock = Stock.create(symbol, MarketCountry.KR, "KOSPI", name, null, "KRW", "STOCK", true);
        stock.applyRanking(rankNo, new BigDecimal("1000"));
        stocks.saveAndFlush(stock);
    }

    private void save(String symbol, String name) {
        stocks.saveAndFlush(Stock.create(symbol, MarketCountry.KR, "KOSPI", name, null, "KRW", "STOCK", true));
    }
}
