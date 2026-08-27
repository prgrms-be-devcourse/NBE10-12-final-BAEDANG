"use client";

import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";
import { Tag } from "@/components/Tag";
import { PillTabs } from "@/components/PillTabs";
import { useExchangeRate } from "@/components/ExchangeRateProvider";
import { D } from "@/lib/decimal";
import { KR_RANKINGS, US_RANKINGS, SEARCHABLE_STOCKS, type MarketCountry, type StockCategory } from "@/lib/mock-data";
import { formatKoreanAmount, formatNumber, formatPercent, formatSigned, formatUsd } from "@/lib/format";

const PAGE_SIZE = 20;

const CATEGORY_BADGE: Record<StockCategory, { bg: string; text: string }> = {
  개별주: { bg: "var(--purpleBg)", text: "var(--purpleText)" },
  ETF: { bg: "var(--accentSoft)", text: "var(--accentText)" },
  배당주: { bg: "var(--greenBg)", text: "var(--greenText)" },
};

export default function RankingsPage() {
  const { rate, updatedAt, isLoading: rateLoading } = useExchangeRate();
  const [market, setMarket] = useState<MarketCountry>("KR");
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);
  const [wishlist, setWishlist] = useState<Record<string, boolean>>({});
  const searchBoxRef = useRef<HTMLDivElement>(null);

  const rankings = market === "KR" ? KR_RANKINGS : US_RANKINGS;
  const shown = rankings.slice(0, visibleCount);
  const hasNext = visibleCount < rankings.length;

  const searchResults = useMemo(() => {
    if (query.trim().length < 2) return [];
    const q = query.trim().toLowerCase();
    return SEARCHABLE_STOCKS.filter(
      (s) => s.name.toLowerCase().includes(q) || s.symbol.toLowerCase().includes(q)
    ).slice(0, 8);
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

  function switchMarket(next: string) {
    setMarket(next as MarketCountry);
    setVisibleCount(PAGE_SIZE);
  }

  function loadMore() {
    if (loading || !hasNext) return;
    setLoading(true);
    // 실제 API 응답 지연을 흉내내기 위한 목 딜레이. 실제 연동 시 fetch로 교체.
    setTimeout(() => {
      setVisibleCount((v) => Math.min(rankings.length, v + PAGE_SIZE));
      setLoading(false);
    }, 500);
  }

  function toggleWishlist(symbol: string) {
    setWishlist((w) => ({ ...w, [symbol]: !w[symbol] }));
  }

  return (
    <div>
      <h2 className="text-[22px] font-extrabold" style={{ color: "var(--ink)" }}>
        주식 종목 랭킹
      </h2>
      <p className="mb-4.5 text-[13.5px]" style={{ color: "var(--mut)" }}>
        거래대금 기준 상위 100개 · 무엇을 살지 모르겠다면 여기서 시작하세요
      </p>

      {/* 환율 배너 — 정책상 거래는 원화로만 제공되어, 미국 종목 표시는 이 환율로 환산합니다 */}
      <div
        className="mb-4 flex items-center gap-2.5 rounded-[14px] px-4.5 py-2.5 text-[12.5px]"
        style={{ border: "1px solid var(--line2)", background: "var(--card)" }}
      >
        <span className="font-bold" style={{ color: "var(--ink)" }}>
          USD / KRW
        </span>
        <span className="text-[14px] font-bold tabular-nums" style={{ color: "var(--ink)" }}>
          {rateLoading ? "불러오는 중…" : formatNumber(rate)}
        </span>
        <span style={{ color: "var(--mut2)" }}>
          {updatedAt.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })} 기준 · 1시간마다 갱신
        </span>
        <span className="ml-auto cursor-default" style={{ color: "var(--mut2)" }} title="환율 추이 그래프는 2주차 MVP 예정입니다">
          환율 추이 그래프 →
        </span>
      </div>

      {/* 검색 */}
      <div ref={searchBoxRef} className="relative z-30 mb-5 max-w-[480px]">
        <input
          className="w-full rounded-xl px-4 py-2.5 text-[13.5px] outline-none"
          style={{ background: "var(--fill)", color: "var(--ink)" }}
          placeholder="티커 또는 종목명으로 검색 (2자 이상)"
          value={query}
          onFocus={() => setSearchOpen(true)}
          onChange={(e) => {
            setQuery(e.target.value);
            setSearchOpen(true);
          }}
        />
        {searchOpen && (
          <div
            className="absolute top-full z-[31] mt-1.5 w-full origin-top overflow-hidden rounded-[14px] text-[13px]"
            style={{
              background: "var(--card)",
              boxShadow: "0 8px 24px rgba(8,14,26,.12)",
              animation: "searchPanelOpen .22s cubic-bezier(.2,.9,.3,1) both",
            }}
          >
            {searchResults.length > 0 ? (
              searchResults.map((s) => (
                <Link
                  key={`${s.market}-${s.symbol}`}
                  href={`/stocks/${s.symbol}`}
                  className="flex items-center gap-1.5 px-4 py-2.5"
                  style={{ borderBottom: "1px solid var(--line2)" }}
                >
                  {s.name} <Tag>{s.symbol}</Tag> <Tag>{s.market}</Tag> <Tag variant="dark">{s.category}</Tag>
                </Link>
              ))
            ) : (
              <div className="px-4 py-3.5" style={{ color: "var(--mut2)" }}>
                {query.trim().length === 0 ? "종목명 또는 티커를 입력해보세요" : "2자 이상 입력해보세요"}
              </div>
            )}
          </div>
        )}
      </div>

      {/* 탭 */}
      <PillTabs
        options={[
          { value: "KR", label: "국내 주식" },
          { value: "US", label: "해외 주식" },
        ]}
        value={market}
        onChange={switchMarket}
        trackClassName="mb-3 w-[340px] gap-0.5 rounded-full p-[3px]"
        trackStyle={{ background: "rgba(15,56,104,.06)", border: "0.1px solid rgba(15,56,104,.12)" }}
        buttonClassName="rounded-full py-2.5 text-[13.5px] font-bold"
        inactiveTextStyle={{ color: "var(--mut)" }}
      />

      <div className="mb-3 flex items-center">
        <span className="text-[11.5px]" style={{ color: "var(--mut2)" }}>
          최근 <b style={{ color: "var(--mut)" }}>1주 거래대금</b> 상위 100개 · 개별주 · 배당주 · ETF 모두
          포함 · 매주 월요일 갱신
        </span>
        <span className="ml-auto text-[11.5px]" style={{ color: "var(--mut2)" }}>
          12:36:59 기준 · 5초마다 갱신
        </span>
      </div>

      <div className="overflow-hidden rounded-[20px]" style={{ background: "var(--card)" }}>
        <div
          className="grid items-center px-5 py-2.5 text-[12px] font-medium"
          style={{
            gridTemplateColumns: "26px 36px 1.9fr 70px 1fr 1.2fr 1fr",
            borderBottom: "1px solid var(--line2)",
            color: "var(--mut2)",
          }}
        >
          <span />
          <span>순위</span>
          <span>종목명</span>
          <span />
          <span className="text-right">현재가</span>
          <span className="text-right">전일대비</span>
          <span className="text-right">거래대금</span>
        </div>

        {shown.map((item) => {
          const isUp = item.changeAmount >= 0;
          const isUsd = item.currency === "USD";
          // 정책상 원화만 거래에 쓰이므로, 미국 종목은 원화 환산액을 우선 표시하고
          // 원래 달러 값은 보조 텍스트로 같이 보여준다. 부동소수점 오차를 피하려고
          // decimal.js(D)로 계산한다 (다훈님 리뷰, PR #17).
          const krwPrice = (isUsd ? new D(item.lastPrice).times(rate) : new D(item.lastPrice)).round().toNumber();
          const krwChange = (isUsd ? new D(item.changeAmount).times(rate) : new D(item.changeAmount))
            .round()
            .toNumber();
          const badge = CATEGORY_BADGE[item.category];
          const liked = wishlist[item.symbol];

          return (
            <Link
              key={item.symbol}
              href={`/stocks/${item.symbol}`}
              className="grid items-center px-5 py-3 text-[13.5px] transition-[background] duration-150"
              style={{ gridTemplateColumns: "26px 36px 1.9fr 70px 1fr 1.2fr 1fr", borderBottom: "1px solid var(--line2)" }}
              onMouseEnter={(e) => (e.currentTarget.style.background = "var(--fill)")}
              onMouseLeave={(e) => (e.currentTarget.style.background = "transparent")}
            >
              <button
                type="button"
                onClick={(e) => {
                  e.preventDefault();
                  toggleWishlist(item.symbol);
                }}
                className="text-[16px] leading-none"
                style={{
                  color: liked ? "var(--heartActive)" : "var(--mut2)",
                  WebkitTextStroke: "1.3px",
                }}
                aria-label="찜하기"
              >
                ♥
              </button>
              <span style={{ color: "var(--mut2)" }}>{item.rank}</span>
              <span style={{ color: "var(--ink)" }}>
                {item.name} <Tag>{item.symbol}</Tag>
              </span>
              <span
                className="w-fit rounded-lg px-1.5 py-0.5 text-[10.5px] font-bold"
                style={{ background: badge.bg, color: badge.text }}
              >
                {item.category}
              </span>
              <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>
                {formatNumber(krwPrice)}
                {isUsd && <div className="text-[10.5px]" style={{ color: "var(--mut2)" }}>{formatUsd(item.lastPrice)}</div>}
              </span>
              <span className="flex justify-end">
                <span
                  className="rounded-lg px-1.5 py-0.5 text-right text-[12.5px] font-semibold tabular-nums"
                  style={{ background: isUp ? "var(--upBg)" : "var(--downBg)", color: isUp ? "var(--up)" : "var(--down)" }}
                >
                  {isUp ? "▲" : "▼"} {formatSigned(krwChange)} ({formatPercent(item.changeRate)})
                </span>
              </span>
              <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>
                {formatKoreanAmount(item.tradingAmount)}
              </span>
            </Link>
          );
        })}
      </div>

      <div className="mt-5 text-center">
        {hasNext ? (
          <button
            onClick={loadMore}
            disabled={loading}
            className="rounded-full px-8 py-2.5 text-[13px] font-semibold disabled:cursor-not-allowed disabled:opacity-50"
            style={{ background: "var(--fill)", color: "var(--ink)" }}
          >
            {loading ? "불러오는 중…" : `더 보기 (${shown.length} / ${rankings.length})`}
          </button>
        ) : (
          <span className="text-[13px]" style={{ color: "var(--mut2)" }}>
            모든 종목을 불러왔어요
          </span>
        )}
      </div>
    </div>
  );
}
