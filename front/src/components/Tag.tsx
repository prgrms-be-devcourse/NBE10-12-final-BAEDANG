const VARIANTS: Record<string, { background: string; color: string }> = {
  neutral: { background: "var(--fill)", color: "var(--mut)" },
  dark: { background: "var(--highlightSoft)", color: "var(--onHighlightSoftText)" },
};

export function Tag({
  children,
  variant = "neutral",
  // Tailwind는 클래스 문자열 순서가 아니라 생성된 스타일시트 순서로 우선순위를 정하므로,
  // font-medium을 기본 클래스에 항상 넣어두면 호출부에서 font-bold를 얹어도 무게가
  // 안 바뀔 수 있다. 그래서 굵기는 별도 prop으로 받아 기본값(font-medium)과
  // 덮어쓰기(font-bold 등)를 하나만 렌더링한다.
  weightClassName = "font-medium",
  className = "",
}: {
  children: React.ReactNode;
  variant?: keyof typeof VARIANTS;
  weightClassName?: string;
  className?: string;
}) {
  return (
    <span
      className={`inline-block rounded-md px-1.5 py-0.5 align-middle text-[10.5px] ${weightClassName} ${className}`}
      style={VARIANTS[variant]}
    >
      {children}
    </span>
  );
}
