'use client';

import { useMemo, type CSSProperties } from 'react';

/**
 * 화면 중앙에 잠깐 나타났다 사라지는 별(스타버스트) 픽셀 도트 패턴 —
 * 사용자가 첨부한 사진(길게 뻗은 세로·가로 선 + 짧은 대각선 4개가 중앙의
 * 발광 코어에서 뻗어나가는 8방향 별) 참고. 실제 선 대신, 이 프로젝트의
 * 도트 패턴 언어(작은 원형 점)로 각 축을 이루는 점들을 중심에서 바깥으로
 * 갈수록 점점 작아지게 배치해 "점으로 이루어진 별"을 만든다. 세로·가로
 * 축은 길게, 대각선 4개는 짧게 뻗어 사진 속 별의 비율을 그대로 따른다.
 * 중앙에는 사진의 어두운 발광 코어에 대응하는 부드러운 블러 코어를,
 * 별 주변에는 사진처럼 흩어진 작은 반짝임 점 몇 개를 더했다.
 */

export type StarBurstFlashProps = {
  /** 한 번 켜지면(예: 페이지 진입 latch) 잠깐 나타났다 스스로 사라진다 —
   * CSS 쪽 1회성 keyframe(iv-starburst-flash)이 타이밍을 전부 담당하므로,
   * 이 prop이 다시 false가 되어도 이미 재생 중인 애니메이션은 끝까지
   * 재생된다. */
  active: boolean;
  /** 별 전체 지름(px). */
  size?: number;
  /** 점 색상 — 지정하지 않으면 이 화면의 도트 패턴과 같은 색을 쓴다. */
  color?: string;
  style?: CSSProperties;
};

type Dot = { x: number; y: number; r: number };

function axisDots(angleDeg: number, length: number, count: number, startT: number, maxR: number): Dot[] {
  const rad = (angleDeg * Math.PI) / 180;
  const dots: Dot[] = [];
  for (let i = 0; i < count; i++) {
    const t = count === 1 ? startT : startT + (1 - startT) * (i / (count - 1));
    const r = t * length;
    const size = Math.max(0.6, maxR - (maxR - 0.6) * t);
    dots.push({ x: Math.cos(rad) * r, y: Math.sin(rad) * r, r: size });
  }
  return dots;
}

export function StarBurstFlash({ active, size = 260, color, style }: StarBurstFlashProps) {
  const dots = useMemo(() => {
    // 사진 속 별처럼 세로(90/270)·가로(0/180) 축은 길게, 대각선 4개
    // (45/135/225/315)는 짧게 — 각 축은 중심에서 끝으로 갈수록 점이
    // 작아진다(사진의 "굵은 대시 → 가는 대시 → 점"으로 가늘어지는
    // 느낌을 도트 크기 테이퍼로 표현했다).
    const longAxes = [90, 270, 0, 180];
    const shortAxes = [45, 135, 225, 315];
    const all: Dot[] = [];
    for (const a of longAxes) all.push(...axisDots(a, 100, 8, 0.16, 3.2));
    for (const a of shortAxes) all.push(...axisDots(a, 58, 5, 0.22, 2.6));
    return all;
  }, []);

  // 사진 속에서 별 주변에 흩어져 있는 작은 반짝임(십자/점) 몇 개.
  const sparkles = useMemo(
    () => [
      { x: -76, y: -60, r: 2.6 },
      { x: 64, y: -72, r: 1.8 },
      { x: -68, y: 50, r: 1.6 },
      { x: 74, y: 34, r: 2.2 },
      { x: -32, y: 74, r: 1.4 },
      { x: 40, y: -88, r: 1.2 },
    ],
    [],
  );

  const dotColor = color ?? '#7fb6e3';

  return (
    <svg
      aria-hidden="true"
      viewBox="-110 -110 220 220"
      width={size}
      height={size}
      className={active ? 'iv-starburst iv-starburst-play' : 'iv-starburst'}
      style={style}
    >
      <circle cx={0} cy={0} r={11} fill={dotColor} opacity={0.55} style={{ filter: 'blur(6px)' }} />
      {dots.map((d, i) => (
        <circle key={i} cx={d.x} cy={d.y} r={d.r} fill={dotColor} />
      ))}
      {sparkles.map((s, i) => (
        <circle key={`s${i}`} cx={s.x} cy={s.y} r={s.r} fill={dotColor} opacity={0.85} />
      ))}
    </svg>
  );
}
