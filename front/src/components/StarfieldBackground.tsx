'use client';

import { useEffect, useRef, type CSSProperties } from 'react';

/**
 * "우주 속에서 별이 중심을 기준으로 방사형으로 퍼져나가는" 배경 효과
 * (toss.im/career/event/frontend-2026 참고) — 별마다 카메라와의 거리(z)를
 * 두고 매 프레임 z를 줄여(가까워지게) 화면에 원근 투영하면, 화면 중심에서
 * 별이 하나씩 태어나 바깥으로 퍼지면서 점점 빨라지고 길게 늘어나는
 * "하이퍼스페이스/워프" 느낌이 난다 — 별의 이전 위치→현재 위치를 잇는
 * 짧은 선으로 그려서 그 "늘어나는 궤적"을 표현한다.
 *
 * 참고한 화면은 이 효과를 Toss가 자체 제작해 올려둔 배경 비디오 파일로
 * 구현하고 있었다(자세한 내용은 InvestupIntro.tsx의 StarfieldBackground
 * 사용부 주석 참고) — 그 파일 자체를 가져다 쓰지 않고, 같은 시각 효과를
 * 캔버스로 새로 구현했다.
 */

export type StarfieldBackgroundProps = {
  className?: string;
  /** 기본 위치(position:absolute; inset:0 등)에 덧붙일 스타일 — 호출부에서
   * zIndex 등을 지정할 때 쓴다. */
  style?: CSSProperties;
  /** 별이 움직이기 시작할지 여부 — false면 애니메이션을 아예 시작하지
   * 않는다(화면에 아직 등장하지 않은 CTA 배경을 미리 그릴 필요가 없다). */
  active: boolean;
  /** 별 개수. */
  starCount?: number;
  /** 별이 퍼져나가는 기준 속도. 값을 키우면 더 빠르게, 줄이면 더 느리게
   * 스쳐 지나간다. 처음엔 60 → "좀 더 느리게" 요청으로 32 → "조금만 더
   * 느리게" 요청으로 22까지 낮췄다. */
  speed?: number;
};

type Star = {
  x: number; // 중심 기준 상대 좌표(원근 투영 전)
  y: number;
  z: number; // 카메라와의 거리 — 작을수록 가깝다(=화면 앞쪽, 크게 보임)
  pz: number; // 직전 프레임의 z(궤적 선을 그리기 위한 이전 위치 계산용)
};

// 브랜드 색상 시스템(investup-intro-data.ts의 T.dotInk/T.dotShimmerInk)과
// 어울리는 파랑-흰색 계열. 가까이(밝게) 보이는 별일수록 흰색에 가깝게,
// 멀리(어둡게) 있는 별일수록 dotInk 쪽 파랑에 가깝게 섞는다.
const STAR_RGB_NEAR: [number, number, number] = [235, 244, 255]; // 거의 흰색
const STAR_RGB_FAR: [number, number, number] = [127, 182, 227]; // T.dotInk

function lerp(a: number, b: number, t: number) {
  return a + (b - a) * t;
}

// 모션 블러(잔상)가 한 프레임마다 얼마나 옅어질지 — 낮을수록 잔상이 길게
// 남아 블러가 진해지고, 높을수록 잔상이 빨리 사라져 또렷해진다.
const TRAIL_FADE = 0.22;

export function StarfieldBackground({
  className,
  style,
  active,
  starCount = 260,
  speed = 22,
}: StarfieldBackgroundProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    if (!active) return;
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);

    let width = 0;
    let height = 0;
    let cx = 0;
    let cy = 0;
    let maxDepth = 0;
    let stars: Star[] = [];

    function spawnStar(freshEntry: boolean): Star {
      const x = (Math.random() - 0.5) * width;
      const y = (Math.random() - 0.5) * height;
      // freshEntry면 화면 중심 부근(z가 큼 = 아직 멀리 있음)에서 새로 태어나고,
      // 최초 배치 때는 화면 전체에 이미 퍼져 있는 것처럼 z를 고르게 흩어둔다.
      const z = freshEntry ? maxDepth : Math.random() * maxDepth;
      return { x, y, z, pz: z };
    }

    function resize() {
      const rect = canvas!.getBoundingClientRect();
      width = Math.max(1, rect.width);
      height = Math.max(1, rect.height);
      cx = width / 2;
      cy = height / 2;
      maxDepth = Math.max(width, height);
      canvas!.width = width * dpr;
      canvas!.height = height * dpr;
      ctx!.setTransform(dpr, 0, 0, dpr, 0, 0);
    }

    resize();
    stars = new Array(starCount).fill(0).map(() => spawnStar(false));

    function project(s: Star, z: number) {
      // z가 작을수록(가까울수록) x/z, y/z가 커져서 화면 바깥쪽으로 밀려난다 —
      // 중심에서 태어나 바깥으로 퍼지는 원근 투영.
      const scale = maxDepth / Math.max(z, 1);
      return { sx: cx + s.x * scale * 0.5, sy: cy + s.y * scale * 0.5, scale };
    }

    // reduced-motion이면 정지 화면 한 장만 그리고 rAF 루프를 시작하지 않는다
    // — 움직임 없이도 별이 흩뿌려진 배경 느낌은 그대로 유지된다.
    if (reducedMotion) {
      ctx.clearRect(0, 0, width, height);
      for (const s of stars) {
        const { sx, sy, scale } = project(s, s.z);
        const depthT = 1 - s.z / maxDepth;
        const [r, g, b] = [
          lerp(STAR_RGB_FAR[0], STAR_RGB_NEAR[0], depthT),
          lerp(STAR_RGB_FAR[1], STAR_RGB_NEAR[1], depthT),
          lerp(STAR_RGB_FAR[2], STAR_RGB_NEAR[2], depthT),
        ];
        // 별 크기를 조금 더 크게 해달라는 요청으로 최소/배율을 함께
        // 키웠다(0.5→0.75, 0.35→0.48) — 아래 움직이는 별의 lineWidth와
        // 같은 비율로 맞췄다.
        const radius = Math.max(0.75, scale * 0.48);
        ctx.fillStyle = `rgba(${r.toFixed(0)}, ${g.toFixed(0)}, ${b.toFixed(0)}, ${(0.35 + depthT * 0.5).toFixed(2)})`;
        ctx.beginPath();
        ctx.arc(sx, sy, radius, 0, Math.PI * 2);
        ctx.fill();
      }
      return;
    }

    let raf = 0;
    let lastTime = performance.now();

    function frame(now: number) {
      raf = requestAnimationFrame(frame);
      // 60fps를 기준(1 "프레임분") 삼아 실제 경과 시간에 맞춰 속도를
      // 보정한다 — 프레임이 들쭉날쭉해도 별이 퍼지는 속도는 일정하게
      // 느껴진다.
      const dtFrames = Math.min(3, (now - lastTime) / (1000 / 60));
      lastTime = now;

      // 모션 블러 — 매 프레임을 완전히 지우는 대신, 이전 프레임에 그려진
      // 궤적의 alpha를 조금씩만 지운다(destination-out으로 알파만
      // 깎아내므로 캔버스 자체는 계속 투명하게 유지되어, 뒤에 있는 남색
      // 패널/그라데이션이 그대로 비쳐 보인다). 그래서 별의 이전 위치가
      // 한 프레임 만에 사라지지 않고 서서히 흐려지며 남아, 실제로 잔상이
      // 끌리는 듯한 블러 효과가 난다. TRAIL_FADE를 낮추면 잔상이 더 길게
      // 남고(더 진한 블러), 높이면 더 짧게 남는다(더 또렷한 점).
      ctx!.globalCompositeOperation = 'destination-out';
      ctx!.fillStyle = `rgba(0, 0, 0, ${TRAIL_FADE})`;
      ctx!.fillRect(0, 0, width, height);
      ctx!.globalCompositeOperation = 'source-over';

      for (const s of stars) {
        s.pz = s.z;
        s.z -= speed * dtFrames;
        if (s.z <= 1) {
          Object.assign(s, spawnStar(true));
          continue;
        }

        const depthT = 1 - s.z / maxDepth; // 0(멀리) ~ 1(카메라 바로 앞)
        const cur = project(s, s.z);
        const prev = project(s, s.pz);
        const [r, g, b] = [
          lerp(STAR_RGB_FAR[0], STAR_RGB_NEAR[0], depthT),
          lerp(STAR_RGB_FAR[1], STAR_RGB_NEAR[1], depthT),
          lerp(STAR_RGB_FAR[2], STAR_RGB_NEAR[2], depthT),
        ];
        const alpha = 0.25 + depthT * 0.65;
        ctx!.strokeStyle = `rgba(${r.toFixed(0)}, ${g.toFixed(0)}, ${b.toFixed(0)}, ${alpha.toFixed(2)})`;
        // 별 크기를 조금 더 크게 해달라는 요청 — 최소 굵기와 배율을 함께
        // 키웠다(0.6→0.9, 2.2→3.0).
        ctx!.lineWidth = Math.max(0.9, depthT * 3.0);
        // 끝이 뭉툭하게 잘리지 않고 둥글게 이어져야 잔상이 자연스럽게
        // 흐려 보인다.
        ctx!.lineCap = 'round';
        ctx!.beginPath();
        ctx!.moveTo(prev.sx, prev.sy);
        ctx!.lineTo(cur.sx, cur.sy);
        ctx!.stroke();
      }
    }

    raf = requestAnimationFrame(frame);

    function onResize() {
      resize();
    }
    window.addEventListener('resize', onResize);

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener('resize', onResize);
    };
  }, [active, starCount, speed]);

  return (
    <canvas
      ref={canvasRef}
      className={className}
      aria-hidden="true"
      style={{
        position: 'absolute',
        inset: 0,
        width: '100%',
        height: '100%',
        pointerEvents: 'none',
        opacity: active ? 1 : 0,
        transition: 'opacity 1.2s ease',
        ...style,
      }}
    />
  );
}
