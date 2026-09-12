'use client';

import { useEffect, useRef, useState, type ReactNode } from 'react';

type Props = {
  children: ReactNode;
  /** sweep 시작을 늦출 시간(초). 기본 0~0.2s 권장이지만, 이 텍스트가 이미
   * 다른 등장 애니메이션(blur-in 등) 뒤에 나온다면 그 시간만큼 더 크게 줘서
   * "텍스트가 먼저 자리 잡은 다음 빛이 훑고 지나가는" 순서를 만들 수 있다. */
  delay?: number;
  /** 외부에서 재생 시점을 직접 넘겨주고 싶을 때 쓴다(예: 이미 스크롤
   * 진행률로 노출 시점을 계산해둔 pinned/sticky 섹션 — 그런 곳은 요소가
   * 화면 좌표상 일찍부터 존재해서 IntersectionObserver만으로는 "실제로
   * 보이기 시작한 시점"을 알 수 없다). 생략하면 이 컴포넌트가 자체
   * IntersectionObserver로 뷰포트 진입을 감지해 한 번 재생한다 — 별도
   * 배선 없이 어디에나 바로 넣을 수 있는 기본 동작이다. */
  active?: boolean;
  className?: string;
};

/**
 * 텍스트 위로 파란색 계열 그라데이션 빛이 왼쪽에서 오른쪽으로 한 번 훑고
 * 지나가는 효과 — toss.im/simplicity/sessions/toss-graphic-2-0 참고("자연스럽게
 * 토스만의 그래픽적 차별점이 희미해졌죠" 문구의 효과).
 *
 * 텍스트 자체의 color를 바꾸는 게 아니라, 같은 텍스트를 하나 더 겹쳐두고
 * 그 복제본에만 파란 그라데이션을 background-clip: text로 씌운다 — 그래서
 * "글자 모양대로만 빛이 지나가는" 느낌이 나고, 원본 텍스트의 색·굵기·줄바꿈은
 * 전혀 건드리지 않는다. 뷰포트에 들어오면(또는 `active`가 true가 되면)
 * 한 번만 재생되고, 다시 스크롤해도 반복되지 않는다.
 */
export function GradientSweepText({ children, delay = 0, active, className }: Props) {
  const ref = useRef<HTMLSpanElement>(null);
  const [autoPlay, setAutoPlay] = useState(false);

  useEffect(() => {
    // active를 넘겨받았다면 그 값을 그대로 쓰므로 자체 관찰은 하지 않는다.
    if (active !== undefined) return;
    const el = ref.current;
    if (!el) return;

    const check = () => {
      const rect = el.getBoundingClientRect();
      if (rect.top < window.innerHeight * 0.92) {
        setAutoPlay(true);
        return true;
      }
      return false;
    };
    if (check()) return;

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting || check()) {
            setAutoPlay(true);
            observer.disconnect();
          }
        }
      },
      { threshold: 0 },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [active]);

  const play = active ?? autoPlay;

  return (
    <span ref={ref} className={className} style={{ position: 'relative', display: 'inline-block' }}>
      {/* 기본 텍스트 — 항상 원래 색 그대로 보이고, 스크린 리더가 읽는 실제
          콘텐츠다. 여러 줄로 줄바꿈되면 이 레이어의 줄바꿈이 기준이 된다. */}
      <span>{children}</span>
      {/* 파란 그라데이션 복제 텍스트 — 위 텍스트와 정확히 겹쳐지는 절대
          위치. display:block이라 여러 줄로 감싸지더라도 배경(그라데이션)이
          한 덩어리로 이어져서, 줄마다 따로 시작되지 않고 하나의 연속된
          sweep처럼 보인다. */}
      <span
        aria-hidden="true"
        className={`iv-sweep-gradient${play ? ' iv-sweep-play' : ''}`}
        style={{ animationDelay: `${delay}s` }}
      >
        {children}
      </span>
    </span>
  );
}
