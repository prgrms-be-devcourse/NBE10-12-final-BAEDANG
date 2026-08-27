"use client";

import { useEffect, useState } from "react";
import { getMockChartPoints, type RankingItem } from "@/lib/mock-data";
import { CATEGORY_BADGE_STYLE } from "@/lib/category-badge";
import { formatNumber, formatPercent, formatSigned, formatUsd } from "@/lib/format";

const WIDTH = 240;
const MARGIN = 16;

/**
 * 랭킹 테이블 행에 마우스를 올렸을 때 뜨는 간단 정보 + 일봉 미니 차트 카드.
 * 테이블 컨테이너가 `overflow:hidden`(radius 20px)이라 안에 그냥 넣으면 잘리므로,
 * `position:fixed`로 커서 좌표를 따라다니게 해서 컨테이너 밖으로 그린다.
 */
export function StockHoverPreview({
  item,
  krwPrice,
  krwChange,
  x,
  y,
}: {
  item: RankingItem;
  krwPrice: number;
  krwChange: number;
  x: number;
  y: number;
}) {
  const [viewport, setViewport] = useState({ w: 1280, h: 800 });

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setViewport({ w: window.innerWidth, h: window.innerHeight });
  }, []);

  const isUp = item.changeAmount >= 0;
  const isUsd = item.currency === "USD";
  const badge = CATEGORY_BADGE_STYLE[item.category];
  const points = getMockChartPoints(item.symbol + "일봉미리보기");
  const chartH = 56;
  const min = Math.min(...points);
  const max = Math.max(...points);
  const range = max - min || 1;
  const polyline = points
    .map((v, i) => `${(i / (points.length - 1)) * 100},${chartH - ((v - min) / range) * chartH}`)
    .join(" ");

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
          {item.category}
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
        <polyline points={polyline} fill="none" stroke={isUp ? "var(--up)" : "var(--down)"} strokeWidth={2} vectorEffect="non-scaling-stroke" />
      </svg>
      <div className="mt-1 text-[10px]" style={{ color: "var(--mut2)" }}>
        일봉 · 최근 1개월
      </div>
    </div>
  );
}
