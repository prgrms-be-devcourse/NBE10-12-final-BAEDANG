"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { Tag } from "@/components/Tag";
import { PillTabs } from "@/components/PillTabs";
import { Reveal } from "@/components/Reveal";
import { StockHoverPreview } from "@/components/StockHoverPreview";
import { ExchangeRateTrendModal } from "@/components/ExchangeRateTrendModal";
import { useExchangeRate } from "@/components/ExchangeRateProvider";
import { useMarketStatus } from "@/components/MarketStatusProvider";
import { useTheme } from "@/components/ThemeProvider";
import { getRankings, searchStocks, type MarketCountry, type RankingItem, type StockSearchItem } from "@/lib/api";
import { CATEGORY_BADGE_STYLE, categoryLabel } from "@/lib/category-badge";
import { pickDefaultMarket } from "@/lib/default-market";
import { formatAbsolute, formatKoreanAmount, formatNumber, formatPercent, formatSigned, formatUsd, toDecimal, toKrw } from "@/lib/format";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

const PAGE_SIZE = 20;
// 백엔드 시세 수집 자체가 5초 주기다(docs/erd.md) — 그보다 자주 재조회해도
// 더 신선한 값을 받을 수 없어 폴링 주기를 여기에 맞춘다.
const PRICE_POLL_INTERVAL_MS = 5000;
// 검색창을 열었을 때(입력 전) 기본으로 보여주는 큐레이션 목록 — design_handoff 원본의
// 하드코딩된 예시 그대로다. 산업은 연결할 실제 화면이 없어 장식용으로만 둔다.
const POPULAR_STOCKS: { symbol: string; name: string; marketCountry: MarketCountry }[] = [
  { symbol: "005930", name: "삼성전자", marketCountry: "KR" },
  { symbol: "NVDA", name: "엔비디아", marketCountry: "US" },
  { symbol: "000660", name: "SK하이닉스", marketCountry: "KR" },
  { symbol: "TSLA", name: "테슬라", marketCountry: "US" },
  { symbol: "069500", name: "KODEX 200", marketCountry: "KR" },
];
const TRENDING_INDUSTRIES = ["AI · 반도체", "2차전지", "바이오", "우주항공", "로봇"];

export default function RankingsPage() {
  const { rate, changeAmount, changeRate, updatedAt, isLoading: rateLoading } = useExchangeRate();
  const { isOpen: isMarketOpen, isLoading: marketStatusLoading } = useMarketStatus();
  const { theme } = useTheme();
  const [market, setMarket] = useState<MarketCountry>("KR");
  // 화면 진입 시 지금 장중인 시장을 기본 탭으로 보여준다 — 국내장 시간이면 국내,
  // 해외장 시간이면 해외가 기본. 장 상태를 가져오기 전(marketStatusLoading)엔
  // 아직 판단할 수 없어 일단 국내(KR) 기본값을 유지하다가, 로딩이 끝나는 순간
  // 딱 한 번만 반영한다 — 이후 60초 주기 재조회로 개장 상태가 실제로 바뀌어도
  // (예: 화면을 계속 보고 있는 사이 장이 열림) 이미 보고 있는 탭을 임의로
  // 바꾸면 오히려 사용자를 헷갈리게 하므로 다시 적용하지 않는다. 사용자가 직접
  // 탭을 고르면(switchMarket) 그 이후로는 자동 전환을 아예 하지 않는다.
  const autoMarketAppliedRef = useRef(false);
  useEffect(() => {
    if (autoMarketAppliedRef.current || marketStatusLoading) return;
    autoMarketAppliedRef.current = true;
    const defaultMarket = pickDefaultMarket(isMarketOpen("KR"), isMarketOpen("US"));
    if (defaultMarket !== "KR") {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setMarket(defaultMarket);
    }
  }, [marketStatusLoading, isMarketOpen]);
  // 해외 주식 탭에서 현재가를 원화 환산가/달러 원가 중 뭘로 볼지. 예전엔 원화가 아래에
  // 달러가를 항상 같이(두 줄로) 보여줬는데, 그러면 국내 주식(한 줄) 행보다 칸이 길어져서
  // (팀원 제보) 국내와 높이를 맞추려고 글자를 눌러 넣었더니 이번엔 잘 안 보이는 문제가
  // 생겼다. 대신 토글로 한 줄만 보여주고 사용자가 원하는 통화를 고르게 한다.
  const [priceDisplay, setPriceDisplay] = useState<"KRW" | "USD">("KRW");
  const [items, setItems] = useState<RankingItem[]>([]);
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [hasNext, setHasNext] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [query, setQuery] = useState("");
  const [searchResults, setSearchResults] = useState<StockSearchItem[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  // 검색 팝업은 열기/닫기 애니메이션이 서로 달라(searchPanelOpen .22s / searchPanelClose .18s)
  // "지금 열려 있어야 하는가"(searchOpen)와 "지금 DOM에 있어야 하는가"(searchMounted)를
  // 분리해서 관리한다 — 닫힐 때도 닫힘 애니메이션이 끝날 때까지는 DOM에 남아 있어야 한다.
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchMounted, setSearchMounted] = useState(false);
  const [wishlist, setWishlist] = useState<Record<string, boolean>>({});
  const [rateChartOpen, setRateChartOpen] = useState(false);
  const searchBoxRef = useRef<HTMLDivElement>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);
  // 종목에 마우스를 올렸을 때 뜨는 간단 정보 + 일봉 미리보기 카드의 상태.
  // 좌표(x,y)는 커서를 따라다니게 하려고 mousemove마다 갱신한다.
  const [hover, setHover] = useState<{ item: RankingItem; krwPrice: number | null; krwChange: number | null; x: number; y: number } | null>(null);

  // 탭(시장)이 바뀌면 첫 페이지부터 다시 불러온다.
  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setLoadError(false);
    getRankings(market, PAGE_SIZE)
      .then((page) => {
        if (cancelled) return;
        setItems(page.items);
        setCursor(page.nextCursor ?? undefined);
        setHasNext(page.hasNext);
      })
      .catch(() => {
        if (!cancelled) setLoadError(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [market]);

  // 폴링 콜백이 매번 최신 items 길이를 읽을 수 있도록 ref로 따로 들고 있는다 —
  // 이걸 useVisiblePolling의 의존성으로 직접 넣으면 폴링으로 items가 바뀔 때마다
  // 인터벌이 매번 재생성된다.
  const itemsRef = useRef(items);
  useEffect(() => {
    itemsRef.current = items;
  }, [items]);
  const pollInFlightRef = useRef(false);

  // 5초마다 지금까지 로드된 만큼(더보기로 추가 로드했으면 그만큼도 포함)을
  // 처음부터 다시 조회해서 시세를 갱신한다. 탭이 백그라운드면 useVisiblePolling이
  // 알아서 멈춘다. 실패해도 화면을 에러로 덮지 않고 다음 주기에 조용히 재시도한다.
  // 지금 보고 있는 시장이 장 마감 중이면 quote_snapshot 자체가 5초 주기로
  // 갱신되지 않으므로(docs/erd.md), 폴링해도 더 신선한 값을 받을 수 없어
  // 장 시간대에만 폴링한다.
  useVisiblePolling(
    () => {
      if (pollInFlightRef.current) return;
      pollInFlightRef.current = true;
      getRankings(market, itemsRef.current.length || PAGE_SIZE)
        .then((page) => {
          // items·nextCursor·hasNext는 항상 같은 응답에서 나온 값이라 서로
          // 일관돼 있다 — 응답이 이전보다 짧다고 걸러내면(예: 요청한 만큼보다
          // 적게 옴), 랭킹이 실제로 줄어든 경우(종목 상장폐지 등)에는 그 뒤로
          // 영원히 최신 상태를 반영하지 못하는 문제가 생긴다(코드 리뷰, PR #124,
          // SOL4R1S님 — 100개 표시 후 백엔드가 계속 99개를 반환하면 모든 폴링
          // 응답을 영구히 버리게 됨). 응답을 그대로 통째로 받아들인다.
          setItems(page.items);
          setCursor(page.nextCursor ?? undefined);
          setHasNext(page.hasNext);
        })
        .catch(() => {})
        .finally(() => {
          pollInFlightRef.current = false;
        });
    },
    PRICE_POLL_INTERVAL_MS,
    isMarketOpen(market)
  );

  // 2자 이상 입력되면 실제 검색 API를 호출한다. 타이핑마다 바로 쏘지 않도록 살짝 디바운스한다.
  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSearchResults([]);
      setSearchLoading(false);
      return;
    }
    let cancelled = false;
    setSearchLoading(true);
    const timer = setTimeout(() => {
      searchStocks(q, 8)
        .then((res) => {
          if (!cancelled) setSearchResults(res.items);
        })
        .catch(() => {
          if (!cancelled) setSearchResults([]);
        })
        .finally(() => {
          if (!cancelled) setSearchLoading(false);
        });
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query]);

  useEffect(() => {
    function onClickOutside(e: MouseEvent) {
      if (searchBoxRef.current && !searchBoxRef.current.contains(e.target as Node)) {
        setSearchOpen(false);
      }
    }
    document.addEventListener("mousedown", onClickOutside);
    return () => document.removeEventListener("mousedown", onClickOutside);
  }, []);

  // 열고 닫힘에 따라 DOM 마운트 여부를 지연시켜 닫힘 애니메이션(searchPanelClose)이
  // 끝까지 재생되게 한다. AuthProvider와 마찬가지로 외부 트리거(searchOpen)에 반응해
  // 타이머를 거는 것이라 effect 안 setState가 맞는 자리다.
  useEffect(() => {
    if (searchOpen) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSearchMounted(true);
      return;
    }
    if (!searchMounted) return;
    const t = setTimeout(() => setSearchMounted(false), 180); // searchPanelClose 재생 시간
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchOpen]);

  function switchMarket(next: string) {
    // 사용자가 직접 탭을 고른 뒤로는 장 상태 기반 자동 전환을 하지 않는다.
    autoMarketAppliedRef.current = true;
    setMarket(next as MarketCountry);
  }

  const loadMore = useCallback(() => {
    if (loading || !hasNext) return;
    setLoading(true);
    getRankings(market, PAGE_SIZE, cursor)
      .then((page) => {
        setItems((prev) => [...prev, ...page.items]);
        setCursor(page.nextCursor ?? undefined);
        setHasNext(page.hasNext);
      })
      .catch(() => setLoadError(true))
      .finally(() => setLoading(false));
  }, [loading, hasNext, cursor, market]);

  // 무한 스크롤 — 목록 맨 아래 sentinel이 뷰포트 300px 이내로 들어오면 다음 페이지를 미리 불러온다.
  // hasNext/loading/cursor/market이 바뀔 때마다 loadMore의 참조가 바뀌므로 옵저버도 그때마다 새로 건다.
  useEffect(() => {
    const el = sentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) loadMore();
      },
      { rootMargin: "300px" }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [loadMore]);

  function toggleWishlist(symbol: string) {
    setWishlist((w) => ({ ...w, [symbol]: !w[symbol] }));
  }

  return (
    <div>
      <Reveal delay={0}>
        <h2 className="text-[28px] font-extrabold" style={{ color: "var(--ink)" }}>
          주식 종목 랭킹
        </h2>
        <p className="mt-2 mb-4.5 text-[15px]" style={{ color: "var(--mut)" }}>
          거래대금 기준 상위 100개 · 무엇을 살지 모르겠다면 여기서 시작하세요
        </p>
      </Reveal>

      {/* 환율 배너 — 정책상 거래는 원화로만 제공되어, 미국 종목 표시는 이 환율로 환산합니다 */}
      <Reveal
        delay={0.12}
        className="mb-4 flex items-center gap-2.5 rounded-[14px] px-4.5 py-2.5 text-[14px]"
        style={{ border: "1px solid var(--line2)", background: "var(--card)" }}
      >
        <span className="font-bold" style={{ color: "var(--ink)" }}>
          USD / KRW
        </span>
        <span className="text-[16px] font-bold tabular-nums" style={{ color: "var(--ink)" }}>
          {rateLoading ? "불러오는 중…" : formatNumber(rate)}
        </span>
        {!rateLoading && (
          <span className="font-semibold tabular-nums" style={{ color: "var(--up)" }}>
            {changeAmount >= 0 ? "▲" : "▼"} {formatAbsolute(changeAmount)} ({formatPercent(changeRate)})
          </span>
        )}
        <span style={{ color: "var(--mut2)" }}>
          {updatedAt.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })} 기준 · 1시간마다 갱신
        </span>
        <button
          type="button"
          onClick={() => setRateChartOpen(true)}
          className="ml-auto cursor-pointer font-semibold"
          style={{ color: "var(--accent)" }}
        >
          환율 추이 그래프 →
        </button>
      </Reveal>

      {/* 검색 */}
      {/* Reveal 자체가 등장 애니메이션에 opacity/transform을 쓰기 때문에 각 Reveal은 저마다
          새 스태킹 컨텍스트를 만든다 — 안쪽 요소에 아무리 높은 z-index를 줘도 형제 Reveal
          "바깥"으로는 못 나간다(호버 카드 관련 주석 참고). 검색 팝업이 아래 탭·목록 Reveal에
          가려지지 않으려면 이 Reveal 자체에 z-index를 줘야 한다. */}
      <Reveal delay={0.24} className="relative z-30">
      <div ref={searchBoxRef} className="relative mb-5 max-w-[480px]">
        <div className="relative">
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            className="pointer-events-none absolute top-1/2 left-4 -translate-y-1/2"
          >
            <circle cx="11" cy="11" r="7" stroke="var(--mut2)" strokeWidth="2" />
            <path d="M21 21l-4.3-4.3" stroke="var(--mut2)" strokeWidth="2" strokeLinecap="round" />
          </svg>
          <input
            className="w-full rounded-xl py-2.5 pr-4 pl-10.5 text-[15px] outline-none"
            style={{ background: theme === "dark" ? "var(--card)" : "#ffffff", color: "var(--ink)" }}
            placeholder="티커 또는 종목명으로 검색 (2자 이상)"
            value={query}
            onFocus={() => setSearchOpen(true)}
            onChange={(e) => {
              setQuery(e.target.value);
              setSearchOpen(true);
            }}
          />
        </div>
        {searchMounted && (
          <div
            className="absolute top-full z-[31] mt-1.5 w-full origin-top overflow-hidden rounded-[14px] text-[13px]"
            style={{
              background: "var(--card)",
              boxShadow: "0 8px 24px rgba(8,14,26,.12)",
              animation: searchOpen
                ? "searchPanelOpen .22s cubic-bezier(.2,.9,.3,1) both"
                : "searchPanelClose .18s cubic-bezier(.2,.9,.3,1) both",
            }}
          >
            {query.trim().length === 0 ? (
              <>
                <div className="px-4.5 pt-4 pb-2.5 text-[12.5px] font-bold" style={{ color: "var(--mut2)" }}>
                  지금 인기 있는 종목
                </div>
                <div className="flex flex-col px-1.5 pb-2.5">
                  {POPULAR_STOCKS.map((s, i) => (
                    <Link
                      key={s.symbol}
                      href={`/stocks/${s.symbol}?marketCountry=${s.marketCountry}`}
                      className="flex items-center gap-2.5 rounded-[10px] px-3 py-2"
                      onMouseEnter={(e) => (e.currentTarget.style.background = "var(--fill)")}
                      onMouseLeave={(e) => (e.currentTarget.style.background = "transparent")}
                    >
                      <span className="w-4 text-[13px] font-extrabold" style={{ color: "var(--accentText)" }}>
                        {i + 1}
                      </span>
                      <span className="text-[13.5px] font-semibold" style={{ color: "var(--ink)" }}>
                        {s.name}
                      </span>
                    </Link>
                  ))}
                </div>
                <div
                  className="px-4.5 pt-3.5 pb-2.5 text-[12.5px] font-bold"
                  style={{ color: "var(--mut2)", borderTop: "1px solid var(--line2)" }}
                >
                  요즘 뜨는 산업
                </div>
                <div className="flex flex-col px-1.5 pb-2.5">
                  {TRENDING_INDUSTRIES.map((name, i) => (
                    <div key={name} className="flex items-center gap-2.5 rounded-[10px] px-3 py-2">
                      <span className="w-4 text-[13px] font-extrabold" style={{ color: "var(--accentText)" }}>
                        {i + 1}
                      </span>
                      <span className="text-[13.5px] font-semibold" style={{ color: "var(--ink)" }}>
                        {name}
                      </span>
                    </div>
                  ))}
                </div>
              </>
            ) : query.trim().length < 2 ? (
              <div className="px-4 py-3.5" style={{ color: "var(--mut2)" }}>
                2자 이상 입력해보세요
              </div>
            ) : searchLoading ? (
              <div className="px-4 py-3.5" style={{ color: "var(--mut2)" }}>
                검색 중…
              </div>
            ) : searchResults.length > 0 ? (
              searchResults.map((s) => (
                <Link
                  key={`${s.marketCountry}-${s.symbol}`}
                  href={`/stocks/${s.symbol}?marketCountry=${s.marketCountry}`}
                  className="flex items-center gap-1.5 px-4 py-2.5"
                  style={{ borderBottom: "1px solid var(--line2)" }}
                >
                  {s.name} <Tag>{s.symbol}</Tag> <Tag>{s.market}</Tag>{" "}
                  <span
                    className="inline-block rounded-md px-1.5 py-0.5 align-middle text-[10.5px] font-medium"
                    style={CATEGORY_BADGE_STYLE[categoryLabel(s.category)]}
                  >
                    {categoryLabel(s.category)}
                  </span>
                </Link>
              ))
            ) : (
              <div className="px-4 py-3.5" style={{ color: "var(--mut2)" }}>
                검색 결과가 없어요
              </div>
            )}
          </div>
        )}
      </div>
      </Reveal>

      {/* 탭 */}
      <Reveal delay={0.36}>
      <PillTabs
        options={[
          { value: "KR", label: "국내 주식" },
          { value: "US", label: "해외 주식" },
        ]}
        value={market}
        onChange={switchMarket}
        trackClassName="mb-3 w-[200px] gap-0.5 rounded-full p-[3px]"
        trackStyle={{
          background: theme === "dark" ? "rgba(255,255,255,.03)" : "rgba(15,56,104,.06)",
          border: theme === "dark" ? "1px solid rgba(255,255,255,.06)" : "1px solid rgba(15,56,104,.12)",
        }}
        buttonClassName="rounded-full py-2 text-[13px] font-bold"
        inactiveTextStyle={{ color: "var(--mut)" }}
      />

      <div className="mb-3 flex items-center">
        <span className="text-[13px]" style={{ color: "var(--mut2)" }}>
          최근 <b style={{ color: "var(--mut)" }}>1주 거래대금</b> 상위 100개 · 개별주 · 배당주 · ETF 모두
          포함 · 매주 월요일 갱신
        </span>
      </div>
      </Reveal>

      <Reveal delay={0.48}>
      <div className="overflow-hidden rounded-[20px]" style={{ background: "var(--card)" }}>
        {/* 해외 주식 탭에서는 "현재가" 헤더에 원/$ 토글이 들어가서 국내 탭보다
            헤더 행이 더 길어진다(제보) — min-height로 두 탭이 항상 같은 높이가
            되게 고정한다(토글이 필요로 하는 높이에 맞춘 값). */}
        <div
          className="grid items-center px-5 py-2.5 text-[12px] font-bold"
          style={{
            gridTemplateColumns: "26px 36px 1.9fr 70px 1fr 1.2fr 1fr",
            borderBottom: "1px solid var(--line2)",
            color: "var(--mut2)",
            minHeight: 47,
          }}
        >
          {/* 하트 아이콘이 관심 종목 찜하기 용도라는 게 라벨 없이는 안 드러난다(제보) —
              "관심" 헤더를 추가한다. */}
          <span className="text-center">관심</span>
          {/* 순위는 자릿수가 들쭉날쭉한 짧은 숫자라 왼쪽 정렬이면 헤더 라벨과
              시각적으로 어긋나 보인다(제보) — 좁은 숫자 칸은 가운데 정렬이 관례다. */}
          <span className="text-center">순위</span>
          <span>종목명</span>
          {/* 개별주/ETF 배지 칸에 헤더 라벨이 없었다(제보) — "구분"을 추가하고,
              배지도 칸 가운데로 옮겨서 짧은 라벨과 나란히 보이게 한다. */}
          <span className="text-center">구분</span>
          <span className="flex items-center justify-end gap-2">
            현재가
            {market === "US" && (
              <PillTabs
                options={[
                  { value: "KRW", label: "원" },
                  { value: "USD", label: "$" },
                ]}
                value={priceDisplay}
                onChange={(v) => setPriceDisplay(v as "KRW" | "USD")}
                trackClassName="w-fit gap-0.5 rounded-full p-[2px]"
                trackStyle={{
                  background: theme === "dark" ? "rgba(255,255,255,.06)" : "rgba(15,56,104,.08)",
                  border: theme === "dark" ? "1px solid rgba(255,255,255,.08)" : "1px solid rgba(15,56,104,.14)",
                }}
                buttonClassName="rounded-full px-2 py-0.5 text-[10.5px] font-bold"
                inactiveTextStyle={{ color: "var(--mut2)" }}
              />
            )}
          </span>
          <span className="text-right">전일대비</span>
          <span className="text-right">거래대금</span>
        </div>

        {items.length === 0 && loading && (
          <div className="px-5 py-10 text-center text-[13.5px]" style={{ color: "var(--mut2)" }}>
            불러오는 중…
          </div>
        )}
        {items.length === 0 && !loading && loadError && (
          <div className="px-5 py-10 text-center text-[13.5px]" style={{ color: "var(--mut2)" }}>
            랭킹 정보를 불러오지 못했어요. 잠시 후 다시 시도해주세요.
          </div>
        )}

        {items.map((item) => {
          const isUsd = item.currency === "USD";
          // 정책상 원화만 거래에 쓰이므로, 미국 종목은 원화 환산액을 우선 표시하고
          // 원래 달러 값은 보조 텍스트로 같이 보여준다. 부동소수점 오차를 피하려고
          // decimal.js(D)로 계산한다 (다훈님 리뷰, PR #17).
          //
          // lastPrice/changeAmount는 시세가 아직 수집되지 않았으면(장 마감 중 등)
          // 응답에서 통째로 빠질 수 있다 — toKrw/toDecimal이 null을 돌려주면 그대로
          // "표시할 값 없음"으로 다룬다(마이페이지 보유 종목과 같은 패턴).
          const krwPriceDecimal = toKrw(item.lastPrice, item.currency, rate);
          const krwChangeDecimal = isUsd ? toDecimal(item.changeAmount)?.times(rate) ?? null : toDecimal(item.changeAmount);
          const krwPrice = krwPriceDecimal ? krwPriceDecimal.round().toNumber() : null;
          const krwChange = krwChangeDecimal ? krwChangeDecimal.round().toNumber() : null;
          const isUp = krwChange === null || krwChange >= 0;
          const label = categoryLabel(item.category, item.isDividend);
          const badge = CATEGORY_BADGE_STYLE[label];
          const liked = wishlist[item.symbol];

          return (
            <Link
              key={item.symbol}
              href={`/stocks/${item.symbol}?marketCountry=${market}`}
              className="grid items-center px-5 py-3 text-[15px] transition-[background] duration-150"
              style={{ gridTemplateColumns: "26px 36px 1.9fr 70px 1fr 1.2fr 1fr", borderBottom: "1px solid var(--line2)" }}
              onMouseEnter={(e) => {
                e.currentTarget.style.background = "var(--fill)";
                setHover({ item, krwPrice, krwChange, x: e.clientX, y: e.clientY });
              }}
              onMouseMove={(e) => setHover((h) => (h && h.item.symbol === item.symbol ? { ...h, x: e.clientX, y: e.clientY } : h))}
              onMouseLeave={(e) => {
                e.currentTarget.style.background = "transparent";
                setHover((h) => (h?.item.symbol === item.symbol ? null : h));
              }}
            >
              <button
                type="button"
                onClick={(e) => {
                  e.preventDefault();
                  toggleWishlist(item.symbol);
                }}
                className="cursor-pointer text-[16px] leading-none"
                style={{
                  color: liked ? "var(--heartActive)" : "var(--mut2)",
                  WebkitTextStroke: "1.3px",
                }}
                aria-label="찜하기"
              >
                ♥
              </button>
              <span className="text-center" style={{ color: "var(--mut2)" }}>{item.rank}</span>
              <span style={{ color: "var(--ink)" }}>
                {item.name} <Tag>{item.symbol}</Tag>
              </span>
              <span
                className="mx-auto w-fit rounded-lg px-1.5 py-0.5 text-[10.5px] font-bold"
                style={badge}
              >
                {label}
              </span>
              {/* 원화·달러를 늘 같이(두 줄로) 보여주면 국내 주식(한 줄) 행보다 칸이 길어져서
                  (팀원 제보) 헤더의 원/$ 토글로 뭘 볼지 고르게 하고 한 줄만 보여준다 —
                  국내 주식 행과 자연스럽게 같은 높이가 된다. */}
              <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>
                {isUsd && priceDisplay === "USD" ? formatUsd(item.lastPrice) : formatNumber(krwPrice)}
              </span>
              <span className="flex justify-end">
                {krwChange === null ? (
                  <span className="text-[12.5px]" style={{ color: "var(--mut2)" }}>
                    시세 정보 없음
                  </span>
                ) : (
                  <span
                    className="rounded-lg px-1.5 py-0.5 text-right text-[12.5px] font-semibold tabular-nums"
                    style={{ background: isUp ? "var(--upBg)" : "var(--downBg)", color: isUp ? "var(--up)" : "var(--down)" }}
                  >
                    {isUp ? "▲" : "▼"} {formatSigned(krwChange)} ({formatPercent(item.changeRate)})
                  </span>
                )}
              </span>
              <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>
                {formatKoreanAmount(item.tradingAmount)}
              </span>
            </Link>
          );
        })}
      </div>

      {items.length > 0 && (
        <div className="mt-5 text-center">
          {hasNext ? (
            // 화면에 보이지 않는 sentinel — 스크롤로 이 지점에 가까워지면 다음 페이지를 불러온다.
            <div ref={sentinelRef} className="py-2.5 text-[13px]" style={{ color: "var(--mut2)" }}>
              {loading ? "불러오는 중…" : ""}
            </div>
          ) : (
            <span className="text-[13px]" style={{ color: "var(--mut2)" }}>
              모든 종목을 불러왔어요
            </span>
          )}
        </div>
      )}
      </Reveal>

      {/* Reveal은 등장 애니메이션에 transform을 쓰는데, transform이 걸린 조상 안에서는
          position:fixed가 뷰포트가 아니라 그 조상 기준으로 계산돼버린다(CSS 스펙상
          transform이 새 containing block을 만든다). 그래서 호버 카드는 반드시
          모든 Reveal 바깥, 최상위에 렌더링해야 실제 커서 좌표에 제대로 뜬다. */}
      {hover && (
        <StockHoverPreview item={hover.item} marketCountry={market} krwPrice={hover.krwPrice} krwChange={hover.krwChange} x={hover.x} y={hover.y} />
      )}
      {rateChartOpen && <ExchangeRateTrendModal onClose={() => setRateChartOpen(false)} />}
    </div>
  );
}
