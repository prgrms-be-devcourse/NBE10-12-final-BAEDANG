"use client";

import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";

/** translateY(110%) → translateY(0), cubic-bezier(.22,1,.36,1) — 이 앱 전체가
 * 이미 등장 애니메이션에 쓰고 있는 이징(riseIn 키프레임, SwapText의 iv-swap
 * 등)과 같은 값이라 다른 모션과 이질감이 없다. */
const EASING = "cubic-bezier(0.22, 1, 0.36, 1)";
const DEFAULT_DURATION_MS = 480;
const DEFAULT_STAGGER_MS = 45;

type RevealTextProps = {
  /**
   * 한 줄씩 순차 등장(stagger)시킬 "줄" 목록. 문자열뿐 아니라 굵은 글씨,
   * SwapText 같은 임의의 JSX도 한 줄로 넣을 수 있다 — 이 컴포넌트는 줄
   * 내용이 무엇이든 overflow:hidden 마스크 + translateY로 감싸기만 한다.
   * 화면 폭에 따라 자동으로 줄바꿈되는 지점까지 나누지는 않는다(별도
   * 텍스트 분할 라이브러리 없이는 실제 렌더 폭을 알 수 없기 때문) — 즉
   * 여기 배열의 각 항목은 항상 의도된 한 "덩어리"이고, 그 덩어리가 좁은
   * 화면에서 두 줄로 줄바꿈되면 그 두 줄이 한 마스크 안에서 함께
   * 올라온다.
   */
  lines: ReactNode[];
  /** 감싸는 태그. 기존 문구가 h1/p였다면 그대로 h1/p를 줘서 마크업 의미를 유지한다. */
  as?: "div" | "span" | "h1" | "h2" | "h4" | "p";
  /** 기존 요소에 있던 font-size/weight/line-height/color 등 클래스를 그대로 옮긴다. */
  className?: string;
  style?: CSSProperties;
  /** 줄과 줄 사이 시작 간격(ms). 기본 45ms — "아주 짧은 stagger". */
  staggerMs?: number;
  /** translateY(110%→0) 트랜지션 길이(ms). 기본 480ms — "빠르고 부드럽게". */
  durationMs?: number;
  /** 여러 RevealText를 나란히 놓고 서로 다른 시점에 시작시키고 싶을 때
   * 쓰는 시작 지연(ms). 줄 내부 stagger(staggerMs)와는 별개다. */
  baseDelayMs?: number;
  /** "block"(기본, 제목·문단처럼 세로로 쌓이는 줄)과 "inline-block"(버튼
   * 라벨·배지처럼 한 줄짜리를 다른 요소와 나란히 놓을 때)을 고른다. */
  display?: "block" | "inline-block";
};

/**
 * 토스인슈어런스(pd-recruit.tossinsu.com) 참고 — 텍스트가 스크롤로 뷰포트에
 * 들어오면 "아래에서 위로 빠르게 올라와 제자리에 정확히 안착"하는 Line
 * Reveal(Masked Text Reveal). 단순 fade-in이나 opacity+translateY만 쓰는
 * slide-up과는 다르다:
 *
 * - 바깥 span에 overflow:hidden을 줘서 "숨겨진 영역 안에서 텍스트가 아래에서
 *   올라와 잘려 보이다가 제자리에서 멈추는" 마스크를 만들고,
 * - 안쪽 span만 translateY(110%) → translateY(0)로 움직인다(바깥은 움직이지
 *   않는다 — 그래서 텍스트가 자기 영역 밖으로 튀어나오지 않는다).
 * - opacity는 보조 역할로만 아주 살짝(0→1) 같이 걸어 두되, transform과 정확히
 *   같은 시간·이징으로 동기화해서 opacity 자체가 "페이드인처럼" 보이지
 *   않게 했다 — 메인 모션은 어디까지나 translateY다.
 * - bounce/elastic/scale/rotation 없이 cubic-bezier(.22,1,.36,1) 하나로만
 *   빠르고 미니멀하게 움직인다.
 *
 * 트리거는 이 프로젝트에 이미 있는 Reveal.tsx와 같은 IntersectionObserver
 * 패턴(뷰포트 진입 시 1회 재생, threshold 92%)을 그대로 따른다 — 별도
 * 라이브러리(Framer Motion/GSAP)가 프로젝트에 없어 새로 설치하지 않았다.
 * prefers-reduced-motion이면 트랜지션 없이 바로 최종 상태로 보여준다.
 */
export function RevealText({
  lines,
  as = "div",
  className,
  style,
  staggerMs = DEFAULT_STAGGER_MS,
  durationMs = DEFAULT_DURATION_MS,
  baseDelayMs = 0,
  display = "block",
}: RevealTextProps) {
  const ref = useRef<HTMLElement>(null);
  const [revealed, setRevealed] = useState(false);
  const [reduceMotion, setReduceMotion] = useState(false);

  useEffect(() => {
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    // ThemeProvider.tsx와 같은 패턴 — 브라우저 전용 값(matchMedia)을 마운트
    // 시점에 한 번 읽어와 state로 고정하는 것이라 effect 밖에서는 알 수 없다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setReduceMotion(query.matches);

    const el = ref.current;
    if (!el) return;

    if (query.matches) {
      setRevealed(true);
      return;
    }

    // Reveal.tsx와 같은 기준 — 화면 하단 8%보다 위에 걸쳐 있으면(이미
    // 스크롤을 지나친 경우 포함) 바로 보이게 한다.
    const check = () => {
      const rect = el.getBoundingClientRect();
      if (rect.top < window.innerHeight * 0.92) {
        setRevealed(true);
        return true;
      }
      return false;
    };

    if (check()) return;

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting || check()) {
            setRevealed(true);
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
    <Tag ref={ref as never} className={className} style={style}>
      {lines.map((line, i) => {
        const delay = reduceMotion ? 0 : baseDelayMs + i * staggerMs;
        return (
          <span key={i} style={{ display, overflow: "hidden", verticalAlign: display === "inline-block" ? "top" : undefined }}>
            <span
              style={{
                display: "block",
                transform: reduceMotion || revealed ? "translateY(0)" : "translateY(110%)",
                opacity: reduceMotion || revealed ? 1 : 0,
                transition: reduceMotion
                  ? "none"
                  : `transform ${durationMs}ms ${EASING} ${delay}ms, opacity ${durationMs}ms ${EASING} ${delay}ms`,
                willChange: "transform",
              }}
            >
              {line}
            </span>
          </span>
        );
      })}
    </Tag>
  );
}
