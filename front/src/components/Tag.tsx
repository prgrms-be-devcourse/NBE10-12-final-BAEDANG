const VARIANTS: Record<string, { background: string; color: string }> = {
  neutral: { background: "var(--fill)", color: "var(--mut)" },
  dark: { background: "var(--highlightSoft)", color: "var(--onHighlightSoftText)" },
};

export function Tag({
  children,
  variant = "neutral",
}: {
  children: React.ReactNode;
  variant?: keyof typeof VARIANTS;
}) {
  return (
    <span
      className="inline-block rounded-md px-1.5 py-0.5 align-middle text-[10.5px] font-medium"
      style={VARIANTS[variant]}
    >
      {children}
    </span>
  );
}
