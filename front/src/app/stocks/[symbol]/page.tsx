"use client";

import { useParams } from "next/navigation";
import { StockDetailClient } from "@/components/StockDetailClient";
import { getStockDetail } from "@/lib/mock-data";

/**
 * 서버 컴포넌트에서 클라이언트 컴포넌트로 전환했습니다 (호영님 리뷰 반영).
 *
 * <p>이전에는 서버에서 `params`를 await해서 목데이터를 조회한 뒤 내려줬는데,
 * 이 프로젝트가 배포할 플랫폼(Cloudflare Pages 등)에 따라 SSR이 유료이거나
 * 아예 지원되지 않을 수 있습니다. 지금은 어차피 목데이터(로컬 배열 조회)라
 * 서버에서 계산할 이유가 없어서, `useParams()`로 클라이언트에서 바로 읽도록
 * 바꿨습니다 — 이제 이 앱에는 데이터에 의존하는 서버 컴포넌트가 없습니다.
 *
 * <p>실제 백엔드 API로 교체할 때는 이 안에서 `useEffect` + `fetch`로 데이터를
 * 가져오면 됩니다 (또는 React Query 등 클라이언트 데이터 페칭 라이브러리).
 */
export default function StockDetailPage() {
  const params = useParams<{ symbol: string }>();
  const rawSymbol = Array.isArray(params.symbol) ? params.symbol[0] : params.symbol;
  const detail = getStockDetail(decodeURIComponent(rawSymbol ?? ""));
  return <StockDetailClient detail={detail} />;
}
