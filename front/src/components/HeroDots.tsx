"use client";

import { useEffect, useState } from "react";

type Dot = { style: React.CSSProperties };

/**
 * 히어로 우측의 원형 픽셀 도트 패턴. design_handoff의 `loadDotPattern()` 알고리즘을
 * 그대로 옮겼다 — 3.4% 간격 격자를 원형으로 잘라내고, 중심에 가까울수록 밀도를 높여
 * 랜덤 채움한 뒤, 각 도트가 바깥 임의의 지점에서 모여드는 `dotGather` 애니메이션을 재생한다.
 * `Math.random()`을 쓰므로 서버에서는 그리지 않고 마운트 후에만 생성한다 — 페이지를
 * 다시 찾아올 때마다(리마운트) 새로 계산되어 매번 다른 배치로 재생된다.
 */
export function HeroDots() {
  const [dots, setDots] = useState<Dot[] | null>(null);

  useEffect(() => {
    const cellPct = 3.4;
    const cols = Math.ceil(100 / cellPct);
    const cx = 50;
    const cy = 50;
    const maxR = 50;
    const points: { x: number; y: number; density: number }[] = [];

    for (let i = 0; i < cols; i++) {
      for (let j = 0; j < cols; j++) {
        const x = i * cellPct + cellPct / 2;
        const y = j * cellPct + cellPct / 2;
        const dx = x - cx;
        const dy = y - cy;
        const r = Math.sqrt(dx * dx + dy * dy) / maxR;
        if (r > 1) continue;
        const density = Math.pow(Math.max(0, 1 - r), 1.5);
        if (Math.random() > density * 0.96 + 0.02) continue;
        points.push({ x: +x.toFixed(2), y: +y.toFixed(2), density });
      }
    }

    const next = points.map((p): Dot => {
      const angle = Math.random() * Math.PI * 2;
      const dist = 240 + Math.random() * 260;
      const delay = +(Math.random() * 0.7).toFixed(2);
      const dx = Math.cos(angle) * dist;
      const dy = Math.sin(angle) * dist;
      const maxOpacity = +(0.45 + p.density * 0.55).toFixed(2);
      return {
        style: {
          position: "absolute",
          left: `${p.x}%`,
          top: `${p.y}%`,
          width: 5,
          height: 5,
          margin: "-2.5px 0 0 -2.5px",
          borderRadius: "30%",
          background: "var(--accent)",
          opacity: 0,
          ["--dx" as string]: `${dx.toFixed(1)}px`,
          ["--dy" as string]: `${dy.toFixed(1)}px`,
          ["--maxop" as string]: maxOpacity,
          animation: `dotGather 1.9s cubic-bezier(.16,1,.3,1) ${delay}s 1 both`,
        } as React.CSSProperties,
      };
    });

    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDots(next);
  }, []);

  return (
    <div className="relative aspect-square w-full max-w-[260px]">
      {dots?.map((d, i) => (
        <div key={i} style={d.style} />
      ))}
    </div>
  );
}
