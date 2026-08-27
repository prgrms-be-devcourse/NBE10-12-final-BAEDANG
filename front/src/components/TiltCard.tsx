"use client";

import { useRef, type ReactNode } from "react";

/**
 * 메인 화면 STEP 카드의 마우스 위치 기반 3D 틸트. 커서 상대좌표(−0.5~0.5)를 구해
 * `perspective(900px) rotateY(px*14deg) rotateX(-py*8deg) translateY(-4px) scale(1.02)`를
 * 적용한다. 매 프레임 리렌더를 피하려고 React state가 아니라 DOM 스타일을 직접 건드린다
 * (design_handoff README: "그림자는 넣지 않습니다").
 */
export function TiltCard({
  children,
  className = "",
  style,
}: {
  children: ReactNode;
  className?: string;
  style?: React.CSSProperties;
}) {
  const ref = useRef<HTMLDivElement>(null);

  function handleMouseMove(e: React.MouseEvent<HTMLDivElement>) {
    const el = ref.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const px = (e.clientX - rect.left) / rect.width - 0.5;
    const py = (e.clientY - rect.top) / rect.height - 0.5;
    el.style.transition = "transform .08s linear";
    el.style.transform = `perspective(900px) rotateY(${(px * 14).toFixed(2)}deg) rotateX(${(-py * 8).toFixed(2)}deg) translateY(-4px) scale(1.02)`;
  }

  function handleMouseLeave() {
    const el = ref.current;
    if (!el) return;
    el.style.transition = "transform .5s cubic-bezier(.22,1,.36,1)";
    el.style.transform = "perspective(900px) rotateY(0deg) rotateX(0deg) translateY(0) scale(1)";
  }

  return (
    <div ref={ref} onMouseMove={handleMouseMove} onMouseLeave={handleMouseLeave} className={className} style={style}>
      {children}
    </div>
  );
}
