'use client';

import { useMemo, type CSSProperties } from 'react';

/**
 * 화면 중앙에 잠깐 나타났다 사라지는 별(스타버스트) 픽셀 도트 패턴 —
 * 사용자가 첨부한 사진(길게 뻗은 세로·가로 선 + 짧은 대각선 4개가 중앙의
 * 발광 코어에서 뻗어나가는 8방향 별) 참고. 실제 선 대신, 이 프로젝트의
 * 도트 패턴 언어(작은 원형 점)로 각 축을 이루는 점들을 배치해 "점으로
 * 이루어진 별"을 만든다. 세로·가로 축은 길게, 대각선 4개는 짧게 뻗어
 * 사진 속 별의 비율을 그대로 따른다.
 *
 * "좀 더 세련된 형태로" 다듬어달라는 요청으로, 처음의 기계적으로 균일한
 * 점 배열 대신 다음을 더했다 — (1) 점 크기·불투명도가 직선이 아니라
 * ease-in 곡선으로 줄어들어(중심 근처는 오래 두꺼운 채로 유지되다 끝에서
 * 급격히 가늘어짐) 사진의 "굵은 대시 → 가는 점"으로 자연스럽게 이어지는
 * 인상에 더 가깝다. (2) 점 색이 중심의 살짝 더 짙은 톤에서 끝으로 갈수록
 * 더 옅은 톤으로 서서히 바뀌어(단색 점의 나열이 아니라) 은은한 빛 번짐
 * 느낌을 준다. (3) 중앙 코어를 밝은 속 코어 + 부드러운 겉 헤일로 2겹으로
 * 나눠 더 입체적인 발광감을 낸다. (4) 주변 반짝임 중 일부를 사진처럼
 * 작은 십자(+) 모양으로 바꿔 단조로운 점만의 나열에서 벗어났다.
 */

export type StarBurstFlashProps = {
  /** 한 번 켜지면(예: 페이지 진입 latch) 잠깐 나타났다 스스로 사라진다 —
   * CSS 쪽 1회성 keyframe(iv-starburst-flash)이 타이밍을 전부 담당하므로,
   * 이 prop이 다시 false가 되어도 이미 재생 중인 애니메이션은 끝까지
   * 재생된다. */
  active: boolean;
  /** 별 전체 지름(px). */
  size?: number;
  /** 점 색상(중심 쪽 톤 기준) — 지정하지 않으면 이 화면의 도트 패턴과
   * 같은 색을 쓴다. 끝으로 갈수록 이 색에서 흰색 쪽으로 서서히 옅어진다. */
  color?: string;
  style?: CSSProperties;
};

type Dot = { x: number; y: number; r: number; opacity: number; color: string };

function hexToRgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '');
  const n = parseInt(h.length === 3 ? h.replace(/./g, (c) => c + c) : h, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function lerp(a: number, b: number, t: number) {
  return a + (b - a) * t;
}

function blend(near: [number, number, number], far: [number, number, number], t: number) {
  const [r, g, b] = [lerp(near[0], far[0], t), lerp(near[1], far[1], t), lerp(near[2], far[2], t)];
  return `rgb(${r.toFixed(0)}, ${g.toFixed(0)}, ${b.toFixed(0)})`;
}

function axisDots(
  angleDeg: number,
  length: number,
  count: number,
  startT: number,
  maxR: number,
  nearRgb: [number, number, number],
  farRgb: [number, number, number],
): Dot[] {
  const rad = (angleDeg * Math.PI) / 180;
  const dots: Dot[] = [];
  for (let i = 0; i < count; i++) {
    const t = count === 1 ? startT : startT + (1 - startT) * (i / (count - 1));
    const r = t * length;
    // "좀 더 날카로운 형태로" 다듬어달라는 요청으로 ease-in 지수를
    // 1.7→2.6(크기)/1.3→1.9(불투명도)로 더 가파르게 올리고, 끝점의
    // 최소 크기·불투명도 하한도 0.5/0.22 → 0.3/0.08로 낮췄다 — 중심
    // 근처의 굵기는 비슷하게 유지하면서 끝으로 갈수록 훨씬 빠르게
    // 가늘어지고 옅어져, 뭉툭하게 남아있던 끝부분이 뾰족한 점으로
    // 사라지는 느낌을 낸다.
    const fall = Math.pow(1 - t, 2.6);
    const dotSize = Math.max(0.3, maxR * fall);
    const opacity = 0.08 + 0.92 * Math.pow(1 - t, 1.9);
    dots.push({ x: Math.cos(rad) * r, y: Math.sin(rad) * r, r: dotSize, opacity, color: blend(nearRgb, farRgb, t) });
  }
  return dots;
}

export function StarBurstFlash({ active, size = 260, color, style }: StarBurstFlashProps) {
  const dotColor = color ?? '#7fb6e3';

  const dots = useMemo(() => {
    const nearRgb = hexToRgb(dotColor);
    const farRgb: [number, number, number] = [255, 255, 255];
    const longAxes = [90, 270, 0, 180];
    const shortAxes = [45, 135, 225, 315];
    const all: Dot[] = [];
    // 점 개수는 늘리고 최대 굵기는 살짝 줄여서(더 촘촘하고 얇은 점들이
    // 이어지도록) 전체적으로 두툼한 느낌 대신 가늘고 곧은 바늘 같은
    // 인상을 준다 — 위 ease-in 지수와 함께 "날카로운" 느낌을 만든다.
    for (const a of longAxes) all.push(...axisDots(a, 100, 12, 0.12, 2.9, nearRgb, farRgb));
    for (const a of shortAxes) all.push(...axisDots(a, 58, 8, 0.18, 2.3, nearRgb, farRgb));
    return all;
  }, [dotColor]);

  // 사진 속에서 별 주변에 흩어져 있는 작은 반짝임 — 점과 작은 십자(+)를
  // 섞어서 사진의 다양한 마크 느낌을 살렸다.
  const sparkleDots = useMemo(
    () => [
      { x: -68, y: 50, r: 1.6, opacity: 0.75 },
      { x: 74, y: 34, r: 2.1, opacity: 0.7 },
      { x: -32, y: 74, r: 1.3, opacity: 0.65 },
    ],
    [],
  );
  const sparkleCrosses = useMemo(
    () => [
      { x: -76, y: -60, s: 5 },
      { x: 64, y: -72, s: 3.6 },
      { x: 40, y: -88, s: 3 },
    ],
    [],
  );

  return (
    <svg
      aria-hidden="true"
      viewBox="-110 -110 220 220"
      width={size}
      height={size}
      className={active ? 'iv-starburst iv-starburst-play' : 'iv-starburst'}
      style={style}
    >
      {/* 중앙 코어 — 밝은 속 코어 + 부드러운 겉 헤일로 2겹. */}
      <circle cx={0} cy={0} r={22} fill={dotColor} opacity={0.28} style={{ filter: 'blur(10px)' }} />
      <circle cx={0} cy={0} r={8} fill={dotColor} opacity={0.75} style={{ filter: 'blur(3px)' }} />
      {dots.map((d, i) => (
        <circle key={i} cx={d.x} cy={d.y} r={d.r} fill={d.color} opacity={d.opacity} />
      ))}
      {sparkleDots.map((s, i) => (
        <circle key={`sd${i}`} cx={s.x} cy={s.y} r={s.r} fill={dotColor} opacity={s.opacity} />
      ))}
      {sparkleCrosses.map((s, i) => (
        <path
          key={`sc${i}`}
          d={`M ${s.x - s.s} ${s.y} L ${s.x + s.s} ${s.y} M ${s.x} ${s.y - s.s} L ${s.x} ${s.y + s.s}`}
          stroke={dotColor}
          strokeWidth={0.9}
          strokeLinecap="round"
          opacity={0.7}
        />
      ))}
    </svg>
  );
}
