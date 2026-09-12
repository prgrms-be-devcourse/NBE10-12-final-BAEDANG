'use client';

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type AnchorHTMLAttributes,
  type CSSProperties,
  type ReactNode,
} from 'react';

/**
 * "시작하기" 같은 CTA 버튼에 붙이는 "꼬임 → 복원 → Confetti" 호버 인터랙션.
 * toss의 그래픽 인터랙션 참고 — 버튼 자체가 하나의 그래픽 오브젝트처럼
 * 순간적으로 비틀렸다가 원래 pill 형태로 복원되고, 복원되는 바로 그 순간에
 * 주변으로 컬러 조각(Confetti)이 방사형으로 흩어진다.
 *
 * 구현 원칙:
 * - 버튼의 실제 레이아웃 크기(width/height/padding 등)는 절대 바꾸지 않는다.
 *   "꼬임"은 배경·글자를 포함한 시각적 형태 전체가 transform(rotate + skewX
 *   + scaleX/scaleY)으로 순간 일그러지는 것처럼 보이는 착시이고, 실제 박스
 *   크기·레이아웃은 그대로다(transform은 reflow를 일으키지 않는다).
 * - 꼬임 transform은 버튼(<a>) 자신이 아니라 그 안의 별도 래퍼
 *   (.iv-btn-twist-inner)에 건다 — <a> 자신에는 스크롤 등장 애니메이션의
 *   translateY transform이 이미 걸려 있을 수 있는데, 같은 엘리먼트에 두
 *   transform을 함께 걸면 하나가 다른 하나를 덮어써 버린다(CSS는 요소당
 *   transform 값이 하나뿐이다). 부모의 translateY와 자식의 rotate/skew는
 *   서로 다른 엘리먼트라 자연스럽게 함께 합성된다.
 * - 매 hover마다 처음부터 다시 재생돼야 하고, 빠르게 hover를 반복해도
 *   애니메이션이 꼬이거나 Confetti가 중첩되면 안 된다 — React `key`를 바꿔
 *   내부 래퍼를 통째로 새 엘리먼트로 교체하는 방식으로 해결한다(새로
 *   마운트된 엘리먼트의 CSS 애니메이션은 항상 처음부터 재생되고, 교체되며
 *   사라진 이전 엘리먼트는 onAnimationEnd가 더 이상 호출되지 않으므로
 *   중간에 끊긴 애니메이션이 뒤늦게 Confetti를 두 번 터뜨리는 일도 없다).
 * - Confetti는 "복원 완료" 시점을 타이머로 어림잡지 않고, 꼬임 애니메이션의
 *   실제 onAnimationEnd 이벤트에 맞춰 터뜨린다 — duration을 나중에 바꿔도
 *   항상 정확히 동기화된다.
 * - prefers-reduced-motion이면 꼬임 애니메이션 자체를 트리거하지 않는다
 *   (버튼은 항상 정상적으로 클릭 가능하고, CSS에서도 이중으로 막아둔다).
 */

export type AnimatedStartButtonProps = {
  children: ReactNode;
  href: string;
  className?: string;
  style?: CSSProperties;
  /** 꼬임 애니메이션 전체 길이(ms). 기본 620ms. */
  twistDurationMs?: number;
  /** Confetti 조각 개수. */
  confettiCount?: number;
  /** Confetti 한 조각이 사라지기까지 걸리는 시간(ms). */
  confettiDurationMs?: number;
  /** Confetti가 버튼 중심에서 퍼져나가는 대략적인 최대 거리(px). */
  confettiDistance?: number;
} & Omit<
  AnchorHTMLAttributes<HTMLAnchorElement>,
  'className' | 'style' | 'href' | 'children'
>;

type ConfettiPiece = {
  id: number;
  dx: number;
  dy: number;
  rz: number;
  rx: number;
  ry: number;
  width: number;
  height: number;
  borderRadius: string;
  color: string;
  durationMs: number;
  delayMs: number;
};

// 프로젝트 색상 시스템(investup-intro-data.ts의 T.accent/T.dot 계열) 기준
// 파랑을 축으로 하고, 산만해지지 않을 정도로만 보라·핑크·코랄을 소량 섞었다.
const CONFETTI_COLORS = [
  '#3b82f6',
  '#60a5fa',
  '#1b6da3',
  '#9fc8ea',
  '#a78bfa',
  '#f472b6',
  '#fb923c',
];

function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined') return false;
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

// 네모 파티클 대신 타원/물방울/곡선 조각/비정형 조각을 섞어서 만든다 —
// width/height/border-radius 조합만으로 4가지 "느낌"을 낸다.
function makeConfettiPieces(count: number, distance: number, durationMs: number): ConfettiPiece[] {
  const pieces: ConfettiPiece[] = [];
  for (let i = 0; i < count; i += 1) {
    const baseAngle = (360 / count) * i;
    // 완벽한 대칭이 되지 않도록 각도에 랜덤한 편차를 준다.
    const angleDeg = baseAngle + (Math.random() * 26 - 13);
    const angleRad = (angleDeg * Math.PI) / 180;
    const dist = distance * (0.62 + Math.random() * 0.6);
    const shapeRoll = Math.random();
    let width: number;
    let height: number;
    let borderRadius: string;
    if (shapeRoll < 0.28) {
      // 얇고 긴 리본 / 길쭉한 타원
      width = 4 + Math.random() * 3;
      height = 15 + Math.random() * 11;
      borderRadius = '999px';
    } else if (shapeRoll < 0.54) {
      // 물방울 형태
      width = 8 + Math.random() * 4;
      height = 10 + Math.random() * 5;
      borderRadius = '50% 50% 50% 6px';
    } else if (shapeRoll < 0.8) {
      // 짧은 곡선형(휘어진 타원) 조각
      width = 10 + Math.random() * 5;
      height = 6 + Math.random() * 4;
      borderRadius = '60% 40% 55% 45%';
    } else {
      // 비정형 납작한 조각
      const r = () => 35 + Math.random() * 35;
      width = 7 + Math.random() * 5;
      height = 7 + Math.random() * 5;
      borderRadius = `${r()}% ${r()}% ${r()}% ${r()}%`;
    }
    pieces.push({
      id: i,
      dx: Math.cos(angleRad) * dist,
      dy: Math.sin(angleRad) * dist,
      rz: Math.random() * 340 - 170,
      rx: Math.random() * 50 - 25,
      ry: Math.random() * 50 - 25,
      width,
      height,
      borderRadius,
      color: CONFETTI_COLORS[Math.floor(Math.random() * CONFETTI_COLORS.length)],
      durationMs: durationMs * (0.82 + Math.random() * 0.32),
      delayMs: Math.random() * 30,
    });
  }
  return pieces;
}

export function AnimatedStartButton({
  children,
  href,
  className,
  style,
  twistDurationMs = 620,
  confettiCount = 22,
  confettiDurationMs = 950,
  confettiDistance = 78,
  ...anchorProps
}: AnimatedStartButtonProps) {
  const [twistToken, setTwistToken] = useState(0);
  const [burst, setBurst] = useState<{ id: number; pieces: ConfettiPiece[] } | null>(null);
  const burstIdRef = useRef(0);
  const clearTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(
    () => () => {
      if (clearTimerRef.current) clearTimeout(clearTimerRef.current);
    },
    [],
  );

  const playTwist = useCallback(() => {
    if (prefersReducedMotion()) return;
    setTwistToken((t) => t + 1);
  }, []);

  const handleTwistEnd = useCallback(() => {
    const pieces = makeConfettiPieces(confettiCount, confettiDistance, confettiDurationMs);
    burstIdRef.current += 1;
    const id = burstIdRef.current;
    setBurst({ id, pieces });
    if (clearTimerRef.current) clearTimeout(clearTimerRef.current);
    const maxLife = confettiDurationMs * 1.15 + 60;
    clearTimerRef.current = setTimeout(() => {
      setBurst((b) => (b && b.id === id ? null : b));
    }, maxLife);
  }, [confettiCount, confettiDistance, confettiDurationMs]);

  return (
    <span style={{ position: 'relative', display: 'inline-block' }}>
      <a
        {...anchorProps}
        href={href}
        className={className}
        style={style}
        onMouseEnter={(e) => {
          anchorProps.onMouseEnter?.(e);
          playTwist();
        }}
        onFocus={(e) => {
          anchorProps.onFocus?.(e);
          playTwist();
        }}
      >
        <span
          key={twistToken}
          className={twistToken > 0 ? 'iv-btn-twist-inner iv-btn-twist-play' : 'iv-btn-twist-inner'}
          style={{ animationDuration: `${twistDurationMs}ms` }}
          onAnimationEnd={handleTwistEnd}
        >
          {children}
        </span>
      </a>
      {/* Confetti는 이 컨테이너(버튼을 감싸는 span) 안에서만 나타난다 —
          position: absolute + transform/opacity만 쓰므로 버튼이나 페이지의
          레이아웃에는 전혀 영향을 주지 않는다. */}
      <span className="iv-confetti-container" aria-hidden="true">
        {burst?.pieces.map((p) => (
          <span
            key={p.id}
            className="iv-confetti-piece"
            style={
              {
                width: p.width,
                height: p.height,
                borderRadius: p.borderRadius,
                background: p.color,
                animationDuration: `${p.durationMs}ms`,
                animationDelay: `${p.delayMs}ms`,
                '--iv-cf-dx': `${p.dx}px`,
                '--iv-cf-dy': `${p.dy}px`,
                '--iv-cf-rz': `${p.rz}deg`,
                '--iv-cf-rx': `${p.rx}deg`,
                '--iv-cf-ry': `${p.ry}deg`,
              } as CSSProperties
            }
          />
        ))}
      </span>
    </span>
  );
}
