import { StockDetailClient } from "@/components/StockDetailClient";
import { getStockDetail } from "@/lib/mock-data";

export default async function StockDetailPage(props: PageProps<"/stocks/[symbol]">) {
  const { symbol } = await props.params;
  const detail = getStockDetail(decodeURIComponent(symbol));
  return <StockDetailClient detail={detail} />;
}
