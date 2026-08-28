"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";

type Props = {
  children: ReactNode;
  /** riseIn 애니메이션 시작 지연(초). 섹션마다 스태거를 주는 값. */
  delay?: number;
  /** riseIn 애니메이션 재생 시간(초). */
  duration?: number;
  className?: string;
  /** 등장 애니메이션 인라인 스타일과 병합할 추가 스타일(배경·테두리 등). */
  style?: React.CSSProperties;
  as?: "div" | "section";
};

/**
 * 뷰포트 진입 시 `riseIn`으로 등장하는 래퍼. design_handoff README의 판정 기준
 * `rect.top < viewportHeight * 0.92`을 그대로 쓴다 — 이미 스크롤을 지나친 섹션도
 * 반드시 표시되어야 하므로, 화면 하단 8%보다 위에 걸쳐 있기만 해도 즉시 보인다.
 * 컴포넌트가 다시 마운트될 때(페이지 재진입)마다 초기 상태로 돌아가 재생된다.
 */
export function Reveal({ children, delay = 0, duration = 0.6, className = "", style, as = "div" }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;

    const check = () => {
      const rect = el.getBoundingClientRect();
      if (rect.top < window.innerHeight * 0.92) {
        setVisible(true);
        return true;
      }
      return false;
    };

    if (check()) return;

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting || check()) {
            setVisible(true);
            observer.disconnect();
          }
        }
      },
      { threshold: 0 }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const Tag = as;
  return (
    <Tag
      ref={ref as never}
      className={className}
      style={
        visible
          ? { ...style, opacity: 0, animation: `riseIn ${duration}s cubic-bezier(.22,1,.36,1) ${delay}s forwards` }
          : { ...style, opacity: 0, transform: "translateY(18px)" }
      }
    >
      {children}
    </Tag>
  );
}
