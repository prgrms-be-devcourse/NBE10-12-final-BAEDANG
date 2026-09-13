'use client';

import { useState, type CSSProperties, type ReactNode } from 'react';

/**
 * "Line Reveal / Masked Text Reveal" — toss인슈어런스(pd-recruit.tossinsu.com)
 * 참고. 텍스트(또는 로고 이미지 같은 한 덩어리 콘텐츠)가 자기 자리보다
 * 아래에 숨어 있다가, 트리거되는 순간 빠르게 위로 올라와 정확한 위치에서
 * 멈춘다 — 흔한 fade-in/scale-in과 달리 opacity는 애니메이션의 주인공이
 * 아니다: 각 줄을 overflow:hidden인 "창"으로 가리고, 그 안의 실제 콘텐츠를
 * translateY(110%)(창 아래로 숨은 상태)에서 translateY(0)(제자리)으로만
 * 옮긴다 — 창이 가려주기 때문에 opacity를 굳이 움직이지 않아도 "아래에서
 * 올라와 짠 하고 자리 잡는" 느낌이 정확히 난다.
 *
 * 여러 줄(lines)을 넘기면 각 줄이 아주 짧은 간격(staggerMs)을 두고 순서대로
 * 올라온다 — 한 줄씩 천천히 나오는 게 아니라 거의 동시에, 아주 살짝만
 * 어긋나게 움직이는 정도를 목표로 한다(기본 45ms).
 *
 * 이 컴포넌트는 구조(overflow:hidden 창 + translateY)와 타이밍만 담당하고,
 * font-size/font-weight/line-height/letter-spacing/color 같은 타이포그래피는
 * 전혀 건드리지 않는다 — 감싸는 부모 엘리먼트(h1, p 등)에 이미 있는 스타일을
 * 그대로 상속받아 보여줄 뿐이다.
 */

export type RevealLinesProps = {
  /** 실제로 올라오게 할지 여부 — 보통 스크롤/마운트 시 한 번만 true가 되는
   * 1회성 트리거(latch)를 그대로 넘기면 된다. */
  active: boolean;
  /** 각 줄의 콘텐츠. 로고 이미지처럼 통째로 한 덩어리를 다룰 때는 배열에
   * 항목을 하나만 넣으면 된다. */
  lines: ReactNode[];
  /** 바깥 컨테이너에 적용할 클래스/스타일 — 레이아웃(width, margin 등)은
   * 보통 이 prop으로 그대로 유지한 채 넘겨준다. */
  className?: string;
  style?: CSSProperties;
  /** 각 줄 창(overflow:hidden)에 적용할 클래스/스타일. */
  lineClassName?: string;
  lineStyle?: CSSProperties;
  /** 한 줄이 올라오는 데 걸리는 시간(ms). 토스인슈어런스 참고 기본값
   * 400~550ms 범위 안에서 480으로 시작한다 — 여기서 조절한다. */
  durationMs?: number;
  /** 줄 사이 시작 간격(ms) — 값을 키우면 한 줄씩 뚜렷하게 순서대로,
   * 줄이면 거의 동시에 올라오는 것처럼 보인다. 여기서 조절한다. */
  staggerMs?: number;
  /** 첫 줄이 시작되기 전 기본 지연(ms) — 예: 다른 요소가 등장한 뒤에
   * 이어서 재생하고 싶을 때 쓴다. */
  delayMs?: number;
};

function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined') return false;
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

export function RevealLines({
  active,
  lines,
  className,
  style,
  lineClassName,
  lineStyle,
  durationMs = 480,
  staggerMs = 45,
  delayMs = 0,
}: RevealLinesProps) {
  // prefers-reduced-motion은 스크롤 중 바뀔 일이 거의 없고, 이 컴포넌트는
  // 어차피 1회성 reveal이라 마운트 시점의 값 하나만 있으면 충분하다(다른
  // 컴포넌트들의 matchMedia 패턴과 동일하게 lazy init만 쓰고 별도 리스너는
  // 두지 않았다).
  const [reduced] = useState(prefersReducedMotion);

  return (
    <span className={className} style={{ display: 'block', ...style }}>
      {lines.map((line, i) => (
        <span
          key={i}
          className={lineClassName}
          style={{ display: 'block', overflow: 'hidden', ...lineStyle }}
        >
          <span
            style={{
              display: 'block',
              transform: reduced || active ? 'translateY(0)' : 'translateY(110%)',
              transition: reduced
                ? 'none'
                : `transform ${durationMs}ms cubic-bezier(0.22, 1, 0.36, 1) ${delayMs + i * staggerMs}ms`,
              willChange: reduced ? undefined : 'transform',
            }}
          >
            {line}
          </span>
        </span>
      ))}
    </span>
  );
}
