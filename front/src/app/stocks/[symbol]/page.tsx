"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { StockDetailClient } from "@/components/StockDetailClient";
import { getStockDetail, searchStocks, type MarketCountry, type StockDetail } from "@/lib/api";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

// 백엔드 시세 수집 자체가 5초 주기다(docs/erd.md) — 그보다 자주 재조회해도
// 더 신선한 값을 받을 수 없어 폴링 주기를 여기에 맞춘다.
const PRICE_POLL_INTERVAL_MS = 5000;

function isMarketCountry(value: string | null): value is MarketCountry {
  return value === "KR" || value === "US";
}

function StockDetailPageInner() {
  const params = useParams<{ symbol: string }>();
  const searchParams = useSearchParams();
  const rawSymbol = Array.isArray(params.symbol) ? params.symbol[0] : params.symbol;
  const symbol = decodeURIComponent(rawSymbol ?? "");
  const marketCountryParam = searchParams.get("marketCountry");

  const [detail, setDetail] = useState<StockDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  // 폴링은 최초 조회 때 확정된 marketCountry를 그대로 재사용한다 — 매번
  // searchStocks로 다시 추정할 필요가 없다.
  const [resolvedMarketCountry, setResolvedMarketCountry] = useState<MarketCountry | null>(null);

  useEffect(() => {
    if (!symbol) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDetail(null);
    setError(null);
    setResolvedMarketCountry(null);

    async function load() {
      try {
        let marketCountry: MarketCountry | null = isMarketCountry(marketCountryParam) ? marketCountryParam : null;
        if (!marketCountry) {
          // 랭킹/검색 링크는 항상 marketCountry를 함께 넘기지만, 직접 주소를 입력하는 등
          // 쿼리스트링 없이 들어온 경우엔 검색 API로 시장을 유추한다.
          const found = (await searchStocks(symbol, 5)).items.find(
            (item) => item.symbol.toUpperCase() === symbol.toUpperCase()
          );
          marketCountry = found?.marketCountry ?? null;
        }
        if (!marketCountry) {
          if (!cancelled) setError("종목을 찾을 수 없어요.");
          return;
        }
        const data = await getStockDetail(symbol, marketCountry);
        if (!cancelled) {
          setDetail(data);
          setResolvedMarketCountry(marketCountry);
        }
      } catch {
        if (!cancelled) setError("종목 정보를 불러오지 못했어요. 잠시 후 다시 시도해주세요.");
      }
    }

    load();
    return () => {
      cancelled = true;
    };
  }, [symbol, marketCountryParam]);

  // 5초마다 종목 상세(시세 포함)를 다시 조회해 갱신한다. 탭이 백그라운드면
  // useVisiblePolling이 알아서 멈춘다. 실패해도 화면을 에러로 덮지 않고 다음
  // 주기에 조용히 재시도한다 — 이미 보여주고 있는 값을 그대로 유지하는 편이 낫다.
  const pollInFlightRef = useRef(false);
  useVisiblePolling(
    () => {
      if (!symbol || !resolvedMarketCountry || pollInFlightRef.current) return;
      pollInFlightRef.current = true;
      getStockDetail(symbol, resolvedMarketCountry)
        .then((data) => setDetail(data))
        .catch(() => {})
        .finally(() => {
          pollInFlightRef.current = false;
        });
    },
    PRICE_POLL_INTERVAL_MS,
    !!resolvedMarketCountry
  );

  if (error) {
    return (
      <div className="py-16 text-center text-[14.5px]" style={{ color: "var(--mut2)" }}>
        {error}
      </div>
    );
  }
  if (!detail) {
    return (
      <div className="py-16 text-center text-[14.5px]" style={{ color: "var(--mut2)" }}>
        불러오는 중…
      </div>
    );
  }
  return <StockDetailClient detail={detail} />;
}

export default function StockDetailPage() {
  return (
    <Suspense fallback={null}>
      <StockDetailPageInner />
    </Suspense>
  );
}
