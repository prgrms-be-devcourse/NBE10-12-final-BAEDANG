package com.baedang.global.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 트레이딩 도메인 상태를 Micrometer 지표로 내보내는 컴포넌트.
 *
 * <p>[왜 도메인 지표가 따로 필요한가]
 *   CPU·힙·커넥션풀이 전부 초록이어도 시세 폴링이 멈추거나 외부 시세 API 가 계속
 *   실패하면 사용자 입장에서는 완전한 장애다. 인프라 지표만으로는 이 "조용한 장애"를
 *   잡을 수 없어서, 앱만이 아는 상태를 gauge/timer 로 노출한다.
 *
 * <p>[정적 팩토리 대신 생성자 주입인 이유]
 *   이 클래스는 값 객체가 아니라 스프링 빈이다. MeterRegistry·Clock 는 컨테이너가
 *   주입하는 협력자라 생성자 주입이 프로젝트의 DI 관례에 맞는다(Lombok 미사용).
 *
 * <p>[!! 이 컴포넌트는 호출되기 전까지 아무 지표도 만들지 않는다]
 *   여기의 gauge/timer 는 전부 "지연 등록"이라, 스케줄러·외부 클라이언트가 실제로
 *   {@link #quoteUpdated}, {@link #recordExternalCall}, {@link #batchSucceeded} 등을
 *   호출해야 비로소 메트릭이 생긴다. 붙이는 예시는 infra/monitoring/README.md 참고.
 */
@Component
public class TradingMetrics {

    // ── 메트릭 이름 상수 ──────────────────────────────────────────────────────
    //   Prometheus 로 노출될 때의 이름을 역산해서 대시보드/알림과 정확히 맞춘다.
    //   Timer 는 기본 단위가 초라 "_seconds" 가 자동으로 붙는다(예: trading_external_api_seconds).
    //   Gauge 는 자동 단위 접미사가 없으므로, 이름에 직접 ".seconds" 를 박아
    //   trading_quote_staleness_seconds 처럼 예측 가능한 이름이 되도록 한다.
    static final String QUOTE_STALENESS = "trading.quote.staleness.seconds";
    static final String MARKET_OPEN = "trading.market.open";
    static final String EXTERNAL_API = "trading.external.api";
    static final String ORDER_EXECUTION = "trading.order.execution";
    static final String BATCH_LAST_SUCCESS = "trading.batch.last.success.timestamp.seconds";

    private final MeterRegistry registry;
    private final Clock clock;

    // 시세·배치의 마지막 시각을 보관한다. gauge 콜백이 이 참조를 읽어 스크레이프 시점에
    // 값을 계산하므로, 맵이 상태 객체를 강하게 붙잡아 GC 로 gauge 가 사라지지 않게 한다.
    private final ConcurrentHashMap<String, AtomicReference<Instant>> quoteLastUpdate = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicReference<Instant>> batchLastSuccess = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicReference<Boolean>> marketOpen = new ConcurrentHashMap<>();

    public TradingMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    /**
     * 시장(market)별로 관측한 <b>원본 시세 시각({@code quoteAt})</b>을 기록한다. gauge 는 스크레이프
     * 시점에 "그 원본 시각 이후 흐른 초"를 계산해 내보낸다.
     *
     * <p>[!! 저장 시각이 아니라 원본 quoteAt 을 재는 이유]
     *   우리 쪽 저장(collectedAt)이 갱신됐다고 원천 시세가 새로 나온 건 아니다. Toss 가 멈춰
     *   같은 quoteAt 을 반복 반환해도 저장은 계속 성공하므로, "저장 성공=신선"으로 재면 원천이
     *   멎어도 QuoteStale 이 침묵한다(false green). 그래서 원본 quoteAt 을 저장하고, 원천이 멈추면
     *   이 값이 고정돼 staleness 가 계속 커지도록 한다.
     *
     * <p>실패 카운터가 아니라 신선도(경과 시간)를 재는 이유: 폴링 루프가 통째로 멈추면
     * 아무 이벤트도 안 올라오므로, 값이 "올라가지 않는다"가 아니라 "계속 커진다"로
     * 장애가 드러나야 한다.
     */
    public void quoteUpdated(String market, Instant sourceQuoteAt) {
        AtomicReference<Instant> holder = quoteLastUpdate.computeIfAbsent(market, key -> {
            AtomicReference<Instant> ref = new AtomicReference<>(sourceQuoteAt);
            Gauge.builder(QUOTE_STALENESS, ref, this::elapsedSeconds)
                    .tag("market", key)
                    .description("원본 시세 시각(quoteAt) 이후 흐른 시간(초). 계속 커지면 폴링/원천이 멈춘 것")
                    .register(registry);
            return ref;
        });
        // 더 최신 원본 시각으로만 전진시킨다(뒤로 가는 값은 무시). 원천이 멈추면 값이 고정돼 staleness 가 커진다.
        holder.getAndUpdate(prev -> (prev == null || sourceQuoteAt.isAfter(prev)) ? sourceQuoteAt : prev);
    }

    /**
     * 시장의 개장 여부를 gauge 로 내보낸다(1=개장, 0=휴장). 시세 폴링 스케줄러가 매 tick 갱신한다.
     *
     * <p>[왜 필요한가 — QuoteStale 알림의 야간/주말 오탐 방지]
     *   {@link #quoteUpdated} 는 장이 열려 있을 때만 호출되므로, 장이 닫히면 신선도(staleness)가
     *   설계상 계속 커진다. 알림이 staleness 만 보면 매일 밤·주말마다 울린다. 그래서 알림은
     *   이 gauge 와 {@code and on(market)} 로 조인해 <b>개장 중인 시장의 staleness 만</b> 본다.
     *   개장 판정은 앱의 시장 캘린더(휴장·반장·서머타임 반영)가 이미 하고 있어 시각 하드코딩보다 견고하다.
     */
    public void marketOpen(String market, boolean open) {
        AtomicReference<Boolean> holder = marketOpen.computeIfAbsent(market, key -> {
            AtomicReference<Boolean> ref = new AtomicReference<>(open);
            Gauge.builder(MARKET_OPEN, ref, this::openValue)
                    .tag("market", key)
                    .description("시장 개장 여부(1=개장, 0=휴장). QuoteStale 가 개장 시장만 보도록 staleness 와 조인")
                    .register(registry);
            return ref;
        });
        holder.set(open);
    }

    /**
     * 외부 API 호출을 Timer 로 감싼다. 태그 {@code api} 로 대상을, {@code outcome} 으로
     * 성공/실패를 구분한다.
     *
     * <p>[!! 예외를 삼키지 않는다]
     *   예외/에러가 나면 outcome=FAILURE 로 기록만 하고 그대로 전파한다. 여기서 삼키면
     *   호출부는 실패를 모른 채 진행해 더 큰 사고가 난다. finally + success 플래그를 쓰는
     *   이유는 RuntimeException 뿐 아니라 Error 까지 실패로 집계하기 위해서다
     *   (catch(RuntimeException) 만 잡으면 Error 전파 시 outcome 이 SUCCESS 로 잘못 남는다).
     */
    public <T> T recordExternalCall(String api, Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        boolean success = false;
        try {
            T result = call.get();
            success = true;
            return result;
        } finally {
            sample.stop(externalTimer(api, success ? "SUCCESS" : "FAILURE"));
        }
    }

    /** 주문 처리 구간 측정을 시작한다. 반환한 Sample 을 {@link #stopOrderTimer} 에 넘긴다. */
    public Timer.Sample startOrderTimer() {
        return Timer.start(registry);
    }

    /**
     * 주문 처리 구간 측정을 끝낸다. 태그 {@code result} 로 체결/거절/실패 등 결과를 나눠,
     * "느린 건 어떤 결과의 주문인가"까지 볼 수 있게 한다.
     */
    public void stopOrderTimer(Timer.Sample sample, String result) {
        sample.stop(Timer.builder(ORDER_EXECUTION)
                .tag("result", result)
                // !! publishPercentileHistogram 이 없으면 _bucket 이 안 생겨
                //    Prometheus histogram_quantile() 이 빈 값을 낸다(p95 패널이 빈 화면).
                .publishPercentileHistogram()
                .description("주문 처리 소요 시간")
                .register(registry));
    }

    /**
     * 배치가 성공적으로 끝났음을 기록한다. jobName 별로 "마지막 성공 시각"(epoch 초)을 gauge 로 노출한다.
     *
     * <p>[왜 실패 횟수가 아니라 마지막 성공 시각인가]
     *   배치가 아예 안 돌면 실패 카운터도 안 올라간다. 그러면 실패 개수만 보는 사람은
     *   "평화롭다"와 "멈췄다"를 구분할 수 없다. {@code time() - 마지막성공시각} 이 임계를
     *   넘는지로 "제때 돌았는가"를 판정한다.
     *
     * <p>[!! 한계 — 재시작 후 한 번도 안 돈 배치는 이 gauge 자체가 없다]
     *   지연 등록이라 성공 이력이 없으면 시계열이 존재하지 않아, {@code time() - metric > 임계}
     *   알림이 침묵할 수 있다(바로 그 "멈춤" 상황인데도). job 이름이 동적이라 absent() 로
     *   깔끔히 못 막는다. 기동 시 각 배치의 gauge 를 0/과거값으로 시드하면 닫힌다.
     */
    /** 시장 구분이 없는 배치(예: 리더보드 스냅샷). {@code market="all"} 로 기록한다. */
    public void batchSucceeded(String jobName) {
        recordBatchSuccess(jobName, "all");
    }

    /**
     * 시장별로 실패 지점이 독립적인 배치(예: 국내/미국 일봉 수집)의 마지막 성공 시각을 {@code market}
     * 태그와 함께 기록한다. 하나의 job_name 에 성공 시각을 합치면 한 시장이 며칠 죽어도 다른 시장의
     * 성공이 시각을 갱신해 장애가 가려진다 — 시장별로 시계열을 나눠 그 정보를 잃지 않게 한다.
     */
    public void batchSucceeded(String jobName, String market) {
        recordBatchSuccess(jobName, market);
    }

    private void recordBatchSuccess(String jobName, String market) {
        // !! 같은 메트릭 이름의 모든 시계열은 태그 키 집합이 동일해야 한다. 일부는 market 을 달고
        //    일부는 안 달면 PrometheusMeterRegistry 가 태그 키 충돌로 경고하고, 먼저 등록된 형태만
        //    남아 다른 시계열이 scrape 에서 누락된다. 그래서 시장 구분이 없는 배치도 market="all" 로
        //    항상 두 태그(job_name, market)를 채운다.
        String key = jobName + "|" + market;
        AtomicReference<Instant> holder = batchLastSuccess.computeIfAbsent(key, ignored -> {
            AtomicReference<Instant> ref = new AtomicReference<>(clock.instant());
            Gauge.builder(BATCH_LAST_SUCCESS, ref, this::epochSeconds)
                    // Prometheus 의 스크레이프 job 라벨과 충돌하지 않도록 태그 키를 job_name 으로 둔다.
                    // 태그 키를 그냥 job 으로 두면 Prometheus 가 exported_job 으로 재라벨해 그룹핑이 깨진다.
                    .tag("job_name", jobName)
                    .tag("market", market)
                    .description("배치의 마지막 성공 시각(epoch 초). time()-이 값 이 커지면 미실행")
                    .register(registry);
            return ref;
        });
        holder.set(clock.instant());
    }

    private Timer externalTimer(String api, String outcome) {
        // register 는 동일 id 에 대해 멱등이라(같은 미터 반환) 매 호출 빌드해도 안전하다.
        return Timer.builder(EXTERNAL_API)
                .tag("api", api)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .description("외부 API 호출 소요 시간과 성공/실패")
                .register(registry);
    }

    /** 마지막 시각 이후 흐른 초(신선도). 시각이 없으면 NaN 으로 두어 오해를 부르는 0 을 피한다. */
    private double elapsedSeconds(AtomicReference<Instant> ref) {
        Instant last = ref.get();
        if (last == null) {
            return Double.NaN;
        }
        return (clock.millis() - last.toEpochMilli()) / 1000.0;
    }

    /** 개장 플래그를 gauge 값으로 낸다(개장 1, 휴장 0). */
    private double openValue(AtomicReference<Boolean> ref) {
        return Boolean.TRUE.equals(ref.get()) ? 1.0 : 0.0;
    }

    /** 저장된 시각을 epoch 초(소수 포함)로 낸다. */
    private double epochSeconds(AtomicReference<Instant> ref) {
        Instant instant = ref.get();
        if (instant == null) {
            return Double.NaN;
        }
        return instant.toEpochMilli() / 1000.0;
    }
}
