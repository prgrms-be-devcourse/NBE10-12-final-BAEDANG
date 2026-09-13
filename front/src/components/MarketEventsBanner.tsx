"use client";

import { useEffect, useState } from "react";
import { getMarketEvents, type KrMarket, type MarketEventItem } from "@/lib/api";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

const MARKETS: KrMarket[] = ["KOSPI", "KOSDAQ"];
// 시장조치는 발생 빈도가 아주 낮은 긴급 정보다 — 시세처럼 5초/1분 단위로 조를
// 필요는 없지만, 화면을 계속 켜두고 있으면 새 발동을 놓치지 않게 1분마다 확인한다.
const POLL_INTERVAL_MS = 60_000;

/** 오늘 날짜를 KST 기준 "yyyy-MM-dd"로. en-CA 로케일은 이 형식을 그대로 내어준다. */
function todayInKst(): string {
  return new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Seoul" });
}

type Row = MarketEventItem & { market: KrMarket };

/**
 * 랭킹 화면(국내 주식 탭)에 오늘의 KRX 시장조치(서킷브레이커·사이드카) 발동 이력을
 * 보여준다. `GET /api/market/events`(#166, 공개 API)를 KOSPI·KOSDAQ 두 시장에 대해
 * 오늘(KST) 날짜로 조회한다.
 *
 * <p>발동 이력이 없는 보통날이 대부분이라, 그럴 땐 배너 자체를 렌더링하지 않는다 —
 * 평소엔 화면에 아무 흔적도 남기지 않다가, 실제로 발동됐을 때만 눈에 띄는 경고
 * 색(--warnBg 계열)으로 나타난다. 아직 활성(`active`)인 항목이 하나라도 있으면
 * "지금 발동 중" 문구를 먼저 보여준다.
 */
export function MarketEventsBanner() {
  const [rows, setRows] = useState<Row[]>([]);
  const [loaded, setLoaded] = useState(false);

  function load() {
    const date = todayInKst();
    Promise.all(MARKETS.map((market) => getMarketEvents(market, date)))
      .then((responses) => {
        const merged = responses.flatMap((res, i) => res.items.map((item) => ({ ...item, market: MARKETS[i] })));
        // 최신 발동이 위로 오게, 같은 시각이면 이벤트ID 역순(서버와 같은 정렬 규칙).
        merged.sort((a, b) => {
          const byTime = new Date(b.triggeredAt).getTime() - new Date(a.triggeredAt).getTime();
          return byTime !== 0 ? byTime : b.eventId - a.eventId;
        });
        setRows(merged);
      })
      .catch(() => {}) // 조회 실패는 조용히 넘어간다 — 다음 폴링에서 다시 시도, 평소 상태(배너 없음)와 구분 안 해도 무방.
      .finally(() => setLoaded(true));
  }

  useEffect(() => {
    load();
  }, []);
  useVisiblePolling(load, POLL_INTERVAL_MS, true);

  if (!loaded || rows.length === 0) return null;

  const hasActive = rows.some((r) => r.active);

  return (
    <div
      className="mb-4 rounded-[14px] px-4.5 py-3.5 text-[13.5px]"
      style={{ background: "var(--warnBg)", border: `1px solid var(--warnBorder)`, color: "var(--warnText)" }}
    >
      <div className="mb-2 font-bold">
        {hasActive ? "지금 매매거래 일시중단 중이에요" : "오늘의 시장조치 이력"}
      </div>
      <div className="flex flex-col gap-1.5">
        {rows.map((row) => (
          <MarketEventRow key={`${row.market}-${row.eventId}`} row={row} />
        ))}
      </div>
    </div>
  );
}

function MarketEventRow({ row }: { row: Row }) {
  const time = new Date(row.triggeredAt).toLocaleTimeString("ko-KR", {
    timeZone: "Asia/Seoul",
    hour: "2-digit",
    minute: "2-digit",
  });
  const kind = row.eventType === "CIRCUIT_BREAKER"
    ? `서킷브레이커 ${row.stage}단계`
    : `사이드카 ${row.direction === "BUY" ? "매수" : "매도"}`;

  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <span
        className="rounded-md px-1.5 py-0.5 text-[11px] font-bold"
        style={{ background: "var(--card)", color: "var(--warnText)" }}
      >
        {row.market}
      </span>
      <span className="font-semibold">{kind}</span>
      <span>· {time}</span>
      {row.active && (
        <span className="rounded-full px-2 py-0.5 text-[11px] font-bold" style={{ background: "var(--warnText)", color: "var(--warnBg)" }}>
          발동 중
        </span>
      )}
      <a
        href={row.sourceUrl}
        target="_blank"
        rel="noreferrer"
        className="underline underline-offset-2"
        style={{ color: "var(--warnText)" }}
      >
        {row.title}
      </a>
    </div>
  );
}
