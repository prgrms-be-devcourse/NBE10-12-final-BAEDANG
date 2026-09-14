import type { StatusBadge } from "@/lib/stock-status";

/**
 * 종목명 옆의 상태 배지 줄. 주문을 막지 않는 정보성 상태(거래유의종목·사이드카)만 온다.
 *
 * 배지가 없으면 아무것도 렌더링하지 않는다 — 평소 화면에 빈 자리를 남기지 않는다.
 * 색만으로 의미를 전달하지 않도록 항상 문구를 함께 보여준다.
 */
export function StockStatusBadges({ badges }: { badges: StatusBadge[] }) {
  if (badges.length === 0) return null;

  return (
    <span className="mt-1.5 flex flex-wrap items-center gap-1.5">
      {badges.map((badge) => (
        <span
          key={badge.key}
          className="inline-block rounded-md px-2 py-0.5 text-[11.5px] font-bold"
          style={
            badge.tone === "warn"
              ? { background: "var(--warnBg)", border: "1px solid var(--warnBorder)", color: "var(--warnText)" }
              : { background: "var(--fill)", border: "1px solid var(--line2)", color: "var(--mut2)" }
          }
        >
          {badge.label}
        </span>
      ))}
    </span>
  );
}
