"use client";

import { Suspense, useEffect, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { StockDetailClient } from "@/components/StockDetailClient";
import { getStockDetail, searchStocks, type MarketCountry, type StockDetail } from "@/lib/api";

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

  useEffect(() => {
    if (!symbol) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDetail(null);
    setError(null);

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
        if (!cancelled) setDetail(data);
      } catch {
        if (!cancelled) setError("종목 정보를 불러오지 못했어요. 잠시 후 다시 시도해주세요.");
      }
    }

    load();
    return () => {
      cancelled = true;
    };
  }, [symbol, marketCountryParam]);

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
