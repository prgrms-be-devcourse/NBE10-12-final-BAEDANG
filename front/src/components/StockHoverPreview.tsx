"use client";

import { useEffect, useState } from "react";
import { getCandles, type Candle, type MarketCountry, type RankingItem } from "@/lib/api";
import { CATEGORY_BADGE_STYLE, categoryLabel } from "@/lib/category-badge";
import { formatNumber, formatPercent, formatSigned, formatUsd } from "@/lib/format";

const WIDTH = 240;
const MARGIN = 16;

// 같은 종목을 다시 호버할 때마다 캔들을 다시 조회하지 않도록 컴포넌트 바깥(모듈 스코프)에
// 캐시를 둔다. 값이 null이면 "조회했지만 실패/데이터 없음", 키가 아예 없으면 "아직 조회 전"이다.
const candleCache = new Map<string, Candle[] | null>();

/**
 * 랭킹 테이블 행에 마우스를 올렸을 때 뜨는 간단 정보 + 일봉 미니 차트 카드.
 * 테이블 컨테이너가 `overflow:hidden`(radius 20px)이라 안에 그냥 넣으면 잘리므로,
 * `position:fixed`로 커서 좌표를 따라다니게 해서 컨테이너 밖으로 그린다.
 *
 * 미니 차트는 `GET /api/stocks/{symbol}/candles`(일봉·최근 1개월)를 호출해 실제 종가로 그린다.
 */
export function StockHoverPreview({
  item,
  marketCountry,
  krwPrice,
  krwChange,
  x,
  y,
}: {
  item: RankingItem;
  marketCountry: MarketCountry;
  krwPrice: number;
  krwChange: number;
  x: number;
  y: number;
}) {
  const [viewport, setViewport] = useState({ w: 1280, h: 800 });
  const [closes, setCloses] = useState<number[] | null>(null);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setViewport({ w: window.innerWidth, h: window.innerHeight });
  }, []);

  useEffect(() => {
    const key = `${marketCountry}:${item.symbol}`;
    const cached = candleCache.get(key);
    if (cached !== undefined) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setCloses(cached ? cached.map((c) => Number(c.close)) : null);
      return;
    }
    let cancelled = false;
    getCandles(item.symbol, marketCountry, "1d", "1M")
      .then((data) => {
        if (cancelled) return;
        candleCache.set(key, data.items.length > 0 ? data.items : null);
        setCloses(data.items.length > 0 ? data.items.map((c) => Number(c.close)) : null);
      })
      .catch(() => {
        if (cancelled) return;
        candleCache.set(key, null);
        setCloses(null);
      });
    return () => {
      cancelled = true;
    };
  }, [item.symbol, marketCountry]);

  const isUp = krwChange >= 0;
  const badge = CATEGORY_BADGE_STYLE[categoryLabel(item.category, item.isDividend)];
  const isUsd = item.currency === "USD";
  const chartH = 56;
  const hasChart = closes !== null && closes.length >= 2;
  let polyline = "";
  if (hasChart) {
    const min = Math.min(...closes);
    const max = Math.max(...closes);
    const range = max - min || 1;
    polyline = closes
      .map((v, i) => `${(i / (closes.length - 1)) * 100},${chartH - ((v - min) / range) * chartH}`)
      .join(" ");
  }

  const left = Math.min(x + MARGIN, viewport.w - WIDTH - MARGIN);
  const top = Math.min(y + MARGIN, viewport.h - 200);

  return (
    <div
      className="pointer-events-none fixed z-[200] rounded-2xl p-4"
      style={{
        left,
        top,
        width: WIDTH,
        background: "var(--card)",
        border: "1px solid var(--line2)",
        boxShadow: "0 12px 32px rgba(8,14,26,.18)",
      }}
    >
      <div className="mb-1.5 flex items-center justify-between gap-2">
        <span className="text-[14px] font-bold" style={{ color: "var(--ink)" }}>
          {item.name}
        </span>
        <span className="w-fit rounded-md px-1.5 py-0.5 text-[10px] font-bold" style={badge}>
          {categoryLabel(item.category, item.isDividend)}
        </span>
      </div>
      <div className="mb-2.5 text-[11px]" style={{ color: "var(--mut2)" }}>
        {item.symbol} · {item.market}
      </div>

      <div className="text-[19px] font-extrabold tabular-nums" style={{ color: "var(--ink)" }}>
        {formatNumber(krwPrice)}
        {isUsd && <span className="ml-1 text-[11px] font-normal" style={{ color: "var(--mut2)" }}>{formatUsd(item.lastPrice)}</span>}
      </div>
      <div className="text-[12.5px] font-semibold tabular-nums" style={{ color: isUp ? "var(--up)" : "var(--down)" }}>
        {isUp ? "▲" : "▼"} {formatSigned(krwChange)} ({formatPercent(item.changeRate)})
      </div>

      <svg width="100%" height={chartH} viewBox={`0 0 100 ${chartH}`} preserveAspectRatio="none" className="mt-2.5">
        {hasChart && (
          <polyline points={polyline} fill="none" stroke={isUp ? "var(--up)" : "var(--down)"} strokeWidth={2} vectorEffect="non-scaling-stroke" />
        )}
      </svg>
      <div className="mt-1 text-[10px]" style={{ color: "var(--mut2)" }}>
        {hasChart ? "일봉 · 최근 1개월" : "차트 데이터를 불러오는 중이거나 아직 없어요"}
      </div>
    </div>
  );
}
