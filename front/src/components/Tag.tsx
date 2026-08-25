const VARIANTS = {
  neutral: "border-gray-300 text-gray-500",
  dark: "border-gray-400 text-gray-700 bg-gray-100",
} as const;

export function Tag({
  children,
  variant = "neutral",
}: {
  children: React.ReactNode;
  variant?: keyof typeof VARIANTS;
}) {
  return (
    <span
      className={`inline-block rounded border px-1.5 py-0.5 align-middle text-[10.5px] ${VARIANTS[variant]}`}
    >
      {children}
    </span>
  );
}
