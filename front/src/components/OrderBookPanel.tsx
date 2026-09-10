"use client";

import { useEffect, useRef, useState } from "react";
import { getOrderBook, type MarketCountry, type OrderBook, type OrderBookLevel } from "@/lib/api";
import { useMarketStatus } from "./MarketStatusProvider";
import { useVisiblePolling } from "@/lib/useVisiblePolling";
import { formatNumber, formatUsd } from "@/lib/format";

// 백엔드 가상 호가 갱신 스케줄러 주기가 3초다 — 그 주기에 맞춰 폴링한다
// (팀원 공유 자료 "5. 폴링 및 에러(503) 처리 규칙" 참고).
const ORDER_BOOK_POLL_INTERVAL_MS = 3000;

/**
 * 종목 상세 화면의 세로 호가창. 전체 사용자가 공유하는 "가상" 호가를 보여준다 —
 * 실제 주문 호가가 아니라 현재가 기반으로 서버가 생성한 참고용 데이터다.
 *
 * <p>정상적인 상황에서도 503(`ORDER_BOOK_UNAVAILABLE`)이 흔하다(장 마감, 거래정지,
 * 시세 지연 등) — 그래서 실패해도 화면 전체를 에러로 덮지 않고 이 패널 안에만
 * 안내 문구를 띄운 뒤, 다음 폴링에서 조용히 재시도한다. 주문과 달리 이 에러엔
 * `retryPolicy`가 없다 — 사용자가 뭘 다시 눌러야 하는 종류의 에러가 아니라는 뜻이다.
 */
export function OrderBookPanel({ symbol, marketCountry }: { symbol: string; marketCountry: MarketCountry }) {
  const { isOpen: isMarketOpen } = useMarketStatus();
  const [book, setBook] = useState<OrderBook | null>(null);
  const [loading, setLoading] = useState(true);
  const [unavailable, setUnavailable] = useState(false);
  const inFlightRef = useRef(false);
  // 이 패널이 지금 어떤 (symbol, marketCountry)를 보여줘야 하는지의 "세대" 번호.
  // symbol/marketCountry가 바뀌면 증가한다 — 응답이 도착했을 때 그 응답이 요청 당시
  // 세대와 같은지 비교해서, 늦게 도착한 이전 종목의 응답이 최신 상태를 덮어쓰는 걸
  // 막는다(리뷰 피드백, PR #147, SOL4R1S님). 지금은 부모(stocks/[symbol]/page.tsx)가
  // 심볼이 바뀔 때 detail을 null로 바꿔 이 패널 자체를 언마운트/리마운트하므로 실제
  // 버그로 이어지진 않지만, 컴포넌트 자체의 안전성을 위해 독립적으로 방어한다.
  const requestIdRef = useRef(0);

  // 503(ORDER_BOOK_UNAVAILABLE)이든 네트워크 에러든 이 패널에서는 구분하지 않고
  // 전부 "조회 불가" 안내로 뭉뚱그린다 — 원인을 세분화해서 알려줄 만큼 중요한
  // 영역이 아니고, 다음 폴링(또는 장이 다시 열릴 때)이 알아서 복구를 시도한다.
  function load() {
    if (inFlightRef.current) return;
    inFlightRef.current = true;
    const requestId = ++requestIdRef.current;
    getOrderBook(symbol, marketCountry)
      .then((data) => {
        if (requestIdRef.current !== requestId) return; // 그 사이 symbol/marketCountry가 바뀐 낡은 응답 — 버린다.
        setBook(data);
        setUnavailable(false);
      })
      .catch(() => {
        if (requestIdRef.current !== requestId) return;
        setUnavailable(true);
      })
      .finally(() => {
        // 이미 무효화된 요청이면 inFlightRef/loading을 건드리지 않는다 — 그 사이
        // 시작된 최신 요청이 이미 그 자리를 맡고 있을 수 있다.
        if (requestIdRef.current !== requestId) return;
        inFlightRef.current = false;
        setLoading(false);
      });
  }

  useEffect(() => {
    // symbol/marketCountry가 바뀌면 이전 요청의 in-flight 가드를 즉시 풀어준다 —
    // 안 그러면 이전 요청이 아직 안 끝났을 때 이 effect의 load() 호출이 가드에
    // 막혀 새 종목 조회 자체가 지연된다. (실제로 덮어쓰는 것을 막는 건 load()
    // 안의 requestId 비교다.)
    inFlightRef.current = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setBook(null);
    setUnavailable(false);
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [symbol, marketCountry]);

  useVisiblePolling(load, ORDER_BOOK_POLL_INTERVAL_MS, isMarketOpen(marketCountry));

  const asksTopDown = book ? [...book.asks].reverse() : []; // ASK 10(고가) → ASK 1(최우선) 순으로 위에서 아래로.
  const maxQuantity = book
    ? Math.max(1, ...book.asks.map((l) => Number(l.quantity)), ...book.bids.map((l) => Number(l.quantity)))
    : 1;

  return (
    <div className="mb-3.5 rounded-[20px] p-5.5" style={{ background: "var(--card)" }}>
      <div className="mb-3 flex items-center gap-2">
        <h4 className="text-[16px] font-bold" style={{ color: "var(--ink)" }}>
          호가
        </h4>
        {book?.virtual && (
          <span
            className="cursor-default rounded-md px-1.5 py-0.5 text-[10.5px] font-bold"
            style={{ background: "var(--accentSoft)", color: "var(--accentText)" }}
            title={book.description}
          >
            가상 호가
          </span>
        )}
      </div>

      {loading ? (
        <div className="py-10 text-center text-[13px]" style={{ color: "var(--mut2)" }}>
          호가 불러오는 중…
        </div>
      ) : unavailable || !book ? (
        <div className="py-10 text-center text-[13px]" style={{ color: "var(--mut2)" }}>
          현재 호가를 조회할 수 없어요 (장 마감 등)
        </div>
      ) : (
        <div>
          {asksTopDown.map((level) => (
            <OrderBookRow key={`ask-${level.level}`} level={level} side="ask" currency={book.currency} maxQuantity={maxQuantity} />
          ))}

          <div
            className="my-1 flex items-center justify-between rounded-md px-2.5 py-1.5 text-[12.5px] font-bold"
            style={{ background: "var(--fill)", color: "var(--ink)" }}
          >
            <span style={{ color: "var(--mut2)" }}>기준가</span>
            <span className="tabular-nums">
              {book.currency === "USD" ? formatUsd(book.basePrice) : formatNumber(book.basePrice)}
            </span>
          </div>

          {/* 국내는 항상 10개, 미국 저가 종목은 1~10개까지 올 수 있어(최소 호가 단위
              $0.01 근처) 배열 길이 그대로 렌더링한다 — 고정 인덱스로 접근하지 않는다. */}
          {book.bids.map((level) => (
            <OrderBookRow key={`bid-${level.level}`} level={level} side="bid" currency={book.currency} maxQuantity={maxQuantity} />
          ))}
        </div>
      )}
    </div>
  );
}

function OrderBookRow({
  level,
  side,
  currency,
  maxQuantity,
}: {
  level: OrderBookLevel;
  side: "ask" | "bid";
  currency: string;
  maxQuantity: number;
}) {
  const isAsk = side === "ask";
  const pct = Math.min(100, (Number(level.quantity) / maxQuantity) * 100);
  return (
    <div className="relative mb-0.5 grid grid-cols-2 overflow-hidden rounded-md px-2.5 py-1.5 text-[12.5px]">
      <div
        className="absolute inset-y-0 left-0"
        style={{ width: `${pct}%`, background: isAsk ? "var(--downBg)" : "var(--upBg)" }}
      />
      <span className="relative z-[1] text-right font-semibold tabular-nums" style={{ color: isAsk ? "var(--down)" : "var(--up)" }}>
        {currency === "USD" ? formatUsd(level.price) : formatNumber(level.price)}
      </span>
      <span className="relative z-[1] text-right tabular-nums" style={{ color: "var(--mut2)" }}>
        {formatNumber(level.quantity)}
      </span>
    </div>
  );
}
