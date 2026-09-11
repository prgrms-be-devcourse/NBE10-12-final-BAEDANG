'use client';

/**
 * Investup 인트로 (풀 스크롤 시퀀스) — 홈페이지 첫 방문 시 등장하는 서비스 소개 화면.
 * 팀원이 전달한 디자인 패키지("Investup 인트로 화면 디자인.zip")의
 * `components/InvestupIntro.tsx`를 프로젝트 경로 별칭(`@/lib/...`)에 맞춰 옮긴 것 외에는
 * 그대로다 — 로직·수치·마크업을 임의로 바꾸지 않았다.
 *
 * 01 Hero      로고 + 태그라인 페이드인 → 위로 이동 → 도트 지구본 등장 (핀 호버 팝업)
 * 02 Steps     3단계 사용법 — 메인 화면("이렇게 사용해요") 섹션과 같은 문구·디자인·효과
 * 03 Compare   제목 슬라이드업 → 분석 카드 등장 → 4행 순차 펼침 / 스크롤 방향에 따라 접힘
 * 04 Zoom      "첫 거래는 오늘, 첫 손실은 0원" → 세로 직선 → 정사각형 확대 → CTA
 */

import { Fragment, useCallback, useEffect, useRef, useState } from 'react';
import {
  T,
  TIMING,
  STEPS,
  STEPS_TITLE_WORDS,
  COMPARE_TITLE_WORDS,
  COMPARE_SUBTITLE_WORDS,
  CMP,
  GLOBE_DOTS,
  PINS,
  DEG,
  clamp01,
} from '@/lib/investup-intro-data';
import { Reveal } from './Reveal';
import { TiltCard } from './TiltCard';
import './investup-intro.css';

// 분석 중 문구("N가지 확인 중…")가 1→2→...→CMP.length로 한 단계씩 오르는 간격.
// 정확히 CMP.length 걸음으로 나눠 분석 소요 시간(2400ms)에 딱 맞춘다.
const CMP_STEP_MS = 2400 / CMP.length;

// 비교 섹션 제목/부제 단어별 등장 효과에서, 단어마다 시작을 얼마나 늦출지(초).
// 제목과 부제가 "동일한 애니메이션 효과"이려면 이 값도 똑같이 써야 한다.
const WORD_STAGGER_S = 0.07;

/* 비교 카드 4개 행("목적"/"실수했을 때"/"수수료·세금"/"사용법 안내")의 스크롤 접기/
 * 펼침 리빌 — toss insurance 채용 페이지(pd-recruit.tossinsu.com) 참고 요청에 맞춰
 * "빠르고 가볍고 자연스러운" 느낌으로 다시 만들었다. width/blur로 모양이 바뀌는
 * 대신 opacity + transform(scaleX, 왼쪽 축으로 펼쳐지고 접힘)만 쓴다(레이아웃에
 * 영향을 주는 속성을 피해 리플로우 없이 합성 레이어에서만 처리되므로 더 가볍다).
 * 처음엔 훨씬 빠르게(480ms) 했는데 너무 빠르다는 피드백을 반영해 살짝 늦췄다. */
const CMP_ROW_DURATION_MS = 640;
const CMP_ROW_EASE = 'cubic-bezier(0.22, 1, 0.36, 1)'; // 초반 반응이 빠른 ease-out
const CMP_ROW_STAGGER_MS = 55; // 4행이 위(펼칠 때)/아래(접을 때)부터 순서대로

type PinState = { on: boolean; i: number; x: number; y: number; boxW: number; boxH: number };
type CmpState = { phase: 0 | 1 | 2; val: number; open: boolean };

export function InvestupIntro({
  logoSrc = '/investup-wordmark-light.png',
  ctaHref = '#hero',
}: {
  logoSrc?: string;
  ctaHref?: string;
}) {
  const [heroIn, setHeroIn] = useState(false);
  const [step, setStep] = useState(3);
  const [pin, setPin] = useState<PinState>({ on: false, i: 0, x: 0, y: 0, boxW: 0, boxH: 0 });
  const [cmp, setCmp] = useState<CmpState>({ phase: 0, val: 0.31, open: true });

  const lifted = step >= 4;
  const charted = step >= 5;

  const globeRef = useRef<HTMLCanvasElement | null>(null);
  const stepsTitleRef = useRef<HTMLSpanElement | null>(null);
  const titleRef = useRef<HTMLSpanElement | null>(null);
  const subtitleRef = useRef<HTMLSpanElement | null>(null);
  const cardRef = useRef<HTMLDivElement | null>(null);
  const zoomSecRef = useRef<HTMLElement | null>(null);
  const zoomCardRef = useRef<HTMLDivElement | null>(null);
  const lineRef = useRef<HTMLDivElement | null>(null);
  const wordARef = useRef<HTMLSpanElement | null>(null);
  const wordBRef = useRef<HTMLSpanElement | null>(null);
  const gapRef = useRef<HTMLSpanElement | null>(null);
  const ctaRef = useRef<HTMLDivElement | null>(null);
  const ctaLineRef = useRef<HTMLParagraphElement | null>(null);
  const ctaBtnRef = useRef<HTMLAnchorElement | null>(null);

  /** 리렌더와 무관하게 유지되는 애니메이션 상태 */
  const A = useRef({
    spin: 0,
    last: 0,
    drag: 0,
    dragEase: 0,
    over: false,
    pinDrawn: [] as Array<{ i: number; x: number; y: number }>,
    geom: { cx: 0, cy: 0, R: 1 },
    cw: null as number | null,
    ch: null as number | null,
    cardOn: false,
    titleShown: false,
    subShown: false,
    lastY: null as number | null,
  });

  // 비교 섹션 제목/부제 — 화면에 들어오면 한 번에 전체가 나타나는 방식(요청:
  // "스크롤을 한 번만 내려도 문구 속 내용이 다 등장"). 스크롤 위치의 연속
  // 함수로 매 프레임 다시 그리는 대신, 한 번 트리거되면 끝나는 React 상태로
  // 관리한다 — 이 파일의 다른 "1회성 전환"들(cmp, step, heroIn 등)과 같은
  // 패턴이다(계속 값이 바뀌는 연속 스크롤 값만 직접 DOM 쓰기로 처리한다).
  const [titleRevealed, setTitleRevealed] = useState(false);
  const [subRevealed, setSubRevealed] = useState(false);

  // prefers-reduced-motion: 켜져 있으면 비교 카드 4행 리빌의 이동·시차를 없애고
  // 거의 즉시 전환되게 한다(요청 16번). 마운트 후 실제 값으로 갱신하고, 사용자가
  // 설정을 바꾸는 경우까지 반영하도록 change 이벤트도 구독한다.
  const [reducedMotion, setReducedMotion] = useState(
    () => typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches,
  );
  useEffect(() => {
    // 초기값은 위 lazy initializer에서 이미 구했다 — 이 effect는 이후 사용자가
    // 설정을 바꾸는 경우에만 구독한다(effect 본문에서 곧바로 setState를 호출하면
    // react-hooks/set-state-in-effect에 걸린다).
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onChange = (e: MediaQueryListEvent) => setReducedMotion(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  // rAF 루프/이벤트 핸들러 안에서 최신 값을 읽기 위한 ref. 렌더 중 직접 대입하면
  // "Cannot access refs during render" 린트 규칙에 걸리므로, 커밋 이후(effect)에
  // 동기화한다 — 이 ref들은 어차피 비동기 콜백에서만 읽으므로 동작은 그대로다.
  const pinRef = useRef(pin);
  useEffect(() => {
    pinRef.current = pin;
  }, [pin]);
  const cmpRef = useRef(cmp);
  useEffect(() => {
    cmpRef.current = cmp;
  }, [cmp]);

  /* ── 인트로 타임라인 ───────────────────────── */
  useEffect(() => {
    const r1 = requestAnimationFrame(() => requestAnimationFrame(() => setHeroIn(true)));
    const t1 = setTimeout(() => setStep(4), TIMING.holdBeforeLift + 500);
    const t2 = setTimeout(() => setStep(5), TIMING.holdBeforeLift + 500 + TIMING.chartDelay);
    return () => {
      cancelAnimationFrame(r1);
      clearTimeout(t1);
      clearTimeout(t2);
    };
  }, []);

  /* ── 비교 카드: 분석 시작 / 접기 ───────────── */
  const cmpTimers = useRef<{ tick?: number; idle?: number; done?: number }>({});

  useEffect(() => {
    const timers = cmpTimers.current;
    return () => {
      window.clearInterval(timers.tick);
      window.clearInterval(timers.idle);
      window.clearTimeout(timers.done);
    };
  }, []);

  // 분석 중 문구("N가지 확인 중…")의 N — 0.12~0.46 사이를 130ms마다 무작위로
  // 오가던 예전 방식은 숫자가 계속 들쭉날쭉 튀어서 산만했다. 대신 실제 비교
  // 항목 수(CMP.length)만큼 1→2→3→4로 차분하게 한 단계씩만 올라가게 했다 —
  // 정확히 CMP.length 걸음으로 나눠 딱 완료 시점(2400ms)에 맞춰 끝난다.
  // useCallback으로 감싸 참조가 안정적으로 유지되게 했다 — 프레임 루프
  // effect(아래, deps: [])가 이 함수들을 호출하므로 exhaustive-deps 규칙을
  // 만족시키려면 안정적인 참조가 필요하다.
  // tick() 안의 스크롤 방향 판정은 cmpRef.current.open/.phase를 읽는데, 이 ref는
  // 원래 `useEffect(() => { cmpRef.current = cmp }, [cmp])`로만 동기화됐다 —
  // 즉 setCmp를 부른 뒤 "렌더 → 커밋 → effect 실행"이 끝나야 ref가 갱신된다.
  // 사용자가 스크롤을 맨 밑까지 내렸다가 빠르게 다시 올릴 때(트랙패드 관성
  // 스크롤 등)는 scroll 이벤트가 그 렌더 사이클보다 훨씬 빨리 여러 번 연달아
  // 들어올 수 있어서, tick()이 몇 프레임 동안 "아직 갱신되지 않은" open 값을
  // 읽게 되고 그 사이에 방향이 다시 바뀌면 재전개(collapseCmp(false)) 판정을
  // 통째로 놓칠 수 있었다 — 그러면 카드가 접힌 채로 영영 안 풀렸다("분석완료"/
  // 문장/4행이 다시 안 나타나는 버그). 그래서 setCmp를 부르는 바로 그 자리에서
  // cmpRef.current도 함께 동기로 갱신한다 — 렌더를 기다리지 않으므로 다음
  // tick()이 항상 최신 값을 본다(effect의 동기화는 안전망으로 남겨둔다).
  const startCmp = useCallback(() => {
    const timers = cmpTimers.current;
    window.clearInterval(timers.tick);
    window.clearTimeout(timers.done);
    let step = 1;
    const initial: CmpState = { phase: 1, val: step / CMP.length, open: true };
    cmpRef.current = initial;
    setCmp(initial);
    timers.tick = window.setInterval(() => {
      step = Math.min(CMP.length, step + 1);
      cmpRef.current = { ...cmpRef.current, val: step / CMP.length };
      setCmp((s) => ({ ...s, val: step / CMP.length }));
      if (step >= CMP.length) window.clearInterval(timers.tick);
    }, CMP_STEP_MS);
    timers.done = window.setTimeout(() => {
      window.clearInterval(timers.tick);
      cmpRef.current = { ...cmpRef.current, phase: 2 };
      setCmp((s) => ({ ...s, phase: 2 }));
    }, 2400);
  }, []);

  const collapseCmp = useCallback((collapse: boolean) => {
    const timers = cmpTimers.current;
    window.clearInterval(timers.idle);
    cmpRef.current = { ...cmpRef.current, open: !collapse };
    setCmp((s) => ({ ...s, open: !collapse }));
    if (collapse) {
      let step = 0;
      timers.idle = window.setInterval(() => {
        step = (step % CMP.length) + 1;
        cmpRef.current = { ...cmpRef.current, val: step / CMP.length };
        setCmp((s) => ({ ...s, val: step / CMP.length }));
      }, CMP_STEP_MS);
    }
  }, []);

  /* ── 프레임 루프: 지구본 페인트 + 스크롤 구동 ── */
  useEffect(() => {
    let raf = 0;
    let mounted = true;

    const fit = (cv: HTMLCanvasElement | null) => {
      if (!cv || !cv.clientWidth || !cv.clientHeight) return null;
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const w = cv.clientWidth;
      const h = cv.clientHeight;
      if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(h * dpr)) {
        cv.width = Math.round(w * dpr);
        cv.height = Math.round(h * dpr);
      }
      const ctx = cv.getContext('2d');
      if (!ctx) return null;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, w, h);
      return { ctx, w, h };
    };

    const project = (lat: number, lon: number, g: { cx: number; cy: number; R: number }) => {
      const tilt = 34 * DEG;
      const p = lat * DEG;
      const l = lon * DEG + A.current.spin;
      const x = Math.cos(p) * Math.sin(l);
      const y = Math.sin(p);
      const z = Math.cos(p) * Math.cos(l);
      const y2 = y * Math.cos(tilt) - z * Math.sin(tilt);
      const z2 = y * Math.sin(tilt) + z * Math.cos(tilt);
      return { x: g.cx + g.R * x, y: g.cy - g.R * y2, z: z2 };
    };

    const paintGlobe = (dt: number, now: number) => {
      const f = fit(globeRef.current);
      if (!f) return;
      const { ctx } = f;
      const a = A.current;

      const pinned = pinRef.current.on;
      const drag = pinned ? 0 : a.drag;
      a.dragEase += (drag - a.dragEase) * 0.16;
      if (!pinned) a.spin += dt * 0.000105 * (a.over ? 0.06 : 1) + a.dragEase * dt * 0.0016;

      const g = { cx: f.w / 2, cy: f.h * 1.16, R: Math.min(f.w * 0.52, f.h * 1.02) };
      a.geom = g;

      // 경위선
      ctx.lineWidth = 1;
      ctx.strokeStyle = 'rgba(35,69,111,0.34)';
      for (let lon = -180; lon < 180; lon += 15) {
        ctx.beginPath();
        let started = false;
        for (let lat = -90; lat <= 90; lat += 3) {
          const q = project(lat, lon, g);
          if (q.z <= 0) {
            started = false;
            continue;
          }
          if (started) ctx.lineTo(q.x, q.y);
          else {
            ctx.moveTo(q.x, q.y);
            started = true;
          }
        }
        ctx.stroke();
      }
      for (let lat = -75; lat <= 75; lat += 15) {
        ctx.beginPath();
        let started = false;
        for (let lon = -180; lon <= 180; lon += 3) {
          const q = project(lat, lon, g);
          if (q.z <= 0) {
            started = false;
            continue;
          }
          if (started) ctx.lineTo(q.x, q.y);
          else {
            ctx.moveTo(q.x, q.y);
            started = true;
          }
        }
        ctx.stroke();
      }

      // 세계지도 도트
      const rBase = Math.max(0.95, g.R * 0.0038);
      for (let i = 0; i < GLOBE_DOTS.length; i++) {
        const d = GLOBE_DOTS[i];
        const q = project(d[0], d[1], g);
        if (q.z <= 0.02) continue;
        const depth = Math.pow(q.z, 0.9);
        const edge = d[2] === 1;
        const alpha = (0.42 + 0.58 * depth) * (edge ? 1 : 0.62);
        ctx.fillStyle = `rgba(15,56,104,${alpha.toFixed(3)})`;
        ctx.beginPath();
        ctx.arc(q.x, q.y, rBase * (0.5 + 0.55 * depth) * (edge ? 1.12 : 0.86), 0, 6.2832);
        ctx.fill();
      }

      // 거래 핀 (주기적 점멸, 호버 시 고정)
      const hi = pinRef.current.on ? pinRef.current.i : -1;
      const t = now / 1000;
      const drawn: Array<{ i: number; x: number; y: number }> = [];
      PINS.forEach((p, i) => {
        const q = project(p.lat, p.lon, g);
        if (q.z <= 0.05) return;
        drawn.push({ i, x: q.x, y: q.y });
        const col = p.up ? '198,74,74' : '58,110,168';
        const cyc = (t / 5.2 + i * 0.37) % 1;
        const pulse = Math.max(0, Math.sin(Math.min(1, cyc / 0.62) * Math.PI));
        const life = i === hi ? 1 : Math.pow(pulse, 0.7);
        if (life < 0.02) return;
        const r = (i === hi ? 6 : 4.8) * (0.75 + 0.25 * life);
        const outer = r * (i === hi ? 5 : 4);
        const halo = ctx.createRadialGradient(q.x, q.y, 0, q.x, q.y, outer);
        halo.addColorStop(0, `rgba(${col},${(0.34 * life).toFixed(3)})`);
        halo.addColorStop(0.55, `rgba(${col},${(0.12 * life).toFixed(3)})`);
        halo.addColorStop(1, `rgba(${col},0)`);
        ctx.fillStyle = halo;
        ctx.beginPath();
        ctx.arc(q.x, q.y, outer, 0, 6.2832);
        ctx.fill();
        const core = ctx.createRadialGradient(q.x - r * 0.25, q.y - r * 0.25, 0, q.x, q.y, r);
        core.addColorStop(0, `rgba(${col},${(0.98 * life).toFixed(3)})`);
        core.addColorStop(0.7, `rgba(${col},${(0.85 * life).toFixed(3)})`);
        core.addColorStop(1, `rgba(${col},${(0.1 * life).toFixed(3)})`);
        ctx.fillStyle = core;
        ctx.beginPath();
        ctx.arc(q.x, q.y, r, 0, 6.2832);
        ctx.fill();
      });
      a.pinDrawn = drawn;
    };

    /** 04 Zoom: 스크롤 진행도를 직접 DOM에 씀 (리렌더 없음) */
    const paintZoom = () => {
      const sec = zoomSecRef.current;
      const card = zoomCardRef.current;
      if (!sec || !card) return;
      const r = sec.getBoundingClientRect();
      const span = Math.max(1, r.height - window.innerHeight);
      const p = clamp01(-r.top / span);
      const vw = window.innerWidth;
      const vh = window.innerHeight;

      // 두 문구가 각각 아래에서 올라옴
      [wordARef.current, wordBRef.current].forEach((el, i) => {
        if (!el) return;
        const k = 1 - Math.pow(1 - clamp01((p - i * 0.04) / 0.1), 3);
        el.style.opacity = k.toFixed(3);
        el.style.transform = `translateY(${((1 - k) * 56).toFixed(1)}px)`;
      });

      const bar = clamp01((p - 0.2) / 0.26); // 세로 직선이 길어지는 구간
      const eb = 1 - Math.pow(1 - bar, 4);
      const grow = clamp01((p - 0.5) / 0.38); // 정사각형으로 확대되는 구간
      const eg = grow < 0.5 ? 2.6 * grow * grow * grow : 1 - Math.pow(1 - grow, 2.1);
      const q = clamp01((p - 0.72) / 0.2); // CTA 등장

      const barW = Math.max(9, vw * 0.0085);
      const tw = barW + eg * (vw * 2.05 - barW);
      const th = eb * vh * 0.42 + eg * (vh * 2.4 - vh * 0.42);

      const a = A.current;
      if (a.cw == null || a.ch == null) {
        a.cw = tw;
        a.ch = th;
      }
      a.cw += (tw - a.cw) * 0.24; // 스크롤 입력 스무딩
      a.ch += (th - a.ch) * 0.24;
      const w = Math.abs(tw - a.cw) < 0.4 ? tw : a.cw;
      const h = Math.abs(th - a.ch) < 0.4 ? th : a.ch;

      card.style.opacity = bar > 0 ? '1' : '0';
      card.style.width = `${w.toFixed(1)}px`;
      card.style.height = `${h.toFixed(1)}px`;
      card.style.borderRadius = `${Math.min(Math.min(w, h) * 0.2, 8 + eg * 56).toFixed(1)}px`;

      if (gapRef.current) {
        gapRef.current.style.width = `${(w + Math.min(vw * 0.09, 110) * (1 - eg)).toFixed(1)}px`;
      }
      if (lineRef.current) {
        const fade = clamp01((p - 0.58) / 0.18);
        lineRef.current.style.opacity = (1 - fade).toFixed(3);
        lineRef.current.style.filter = fade > 0 ? `blur(${(fade * 16).toFixed(1)}px)` : 'none';
      }

      const cta = ctaRef.current;
      if (cta) {
        cta.style.opacity = q.toFixed(3);
        cta.style.pointerEvents = q > 0.5 ? 'auto' : 'none';
        const stage = (from: number) => 1 - Math.pow(1 - clamp01((q - from) / (1 - from || 1)), 3);
        const rise = (el: HTMLElement | null, from: number) => {
          if (!el) return;
          const k = stage(from);
          el.style.opacity = k.toFixed(3);
          el.style.transform = `translateY(${((1 - k) * 44).toFixed(1)}px)`;
        };
        rise(ctaLineRef.current, 0.08);
        rise(ctaBtnRef.current, 0.3);
      }
    };

    /**
     * 02 "이렇게 사용해요" 제목: 단어별 스크롤 연동 등장(블러+상승) — toss.im/#assets의
     * "공부할 필요 없이 누구나 금융 전문가로" 문구와 같은 방식. 한 번 트리거되고
     * 끝나는 CSS 트랜지션이 아니라, 스크롤 위치의 연속 함수로 매 프레임 직접 써서
     * 스크롤을 올리면 다시 흐려지며 가라앉는다(실제로 확인한 toss.im의 동작과 동일).
     * 단어마다 시작 시점을 살짝씩 늦춰서 왼쪽부터 순서대로 나타나게 한다.
     *
     * 03 "증권사 앱과 무엇이 다른가요?" 제목/부제는 더 이상 이 함수를 쓰지 않는다 —
     * "스크롤을 한 번만 내려도 다 등장"하도록 tick() 안에서 화면 진입 여부만
     * 한 번 검사해 titleRevealed/subRevealed React 상태를 한 번 켜고, 단어별
     * 등장은 CSS transition-delay로 재생한다(아래 JSX 참고).
     */
    const updateWordsReveal = (container: HTMLElement | null): number => {
      if (!container) return 0;
      const rect = container.getBoundingClientRect();
      const start = window.innerHeight * 0.92;
      const end = window.innerHeight * 0.52;
      const p = clamp01((start - rect.top) / (start - end));
      const words = container.querySelectorAll<HTMLElement>(':scope > .iv-word');
      const n = words.length;
      words.forEach((w, i) => {
        const localStart = n > 1 ? (i / n) * 0.6 : 0;
        const localP = clamp01((p - localStart) / 0.5);
        const eased = 1 - Math.pow(1 - localP, 3);
        w.style.opacity = eased.toFixed(3);
        w.style.filter = `blur(${(16 * (1 - eased)).toFixed(1)}px)`;
        w.style.transform = `translateY(${(24 * (1 - eased)).toFixed(1)}px)`;
      });
      return p;
    };

    const tick = () => {
      const a = A.current;

      updateWordsReveal(stepsTitleRef.current);

      // 비교 제목/부제: 연속 스크럽이 아니라 화면에 들어오면(rect.top이 임계값
      // 아래로 내려오면) 한 번만 트리거되는 전체 등장 — 짧은 스크롤 한 번으로도
      // 문구 전체가 나타난다. 부제는 제목이 이미 나타난 뒤에만 검사하므로,
      // 제목이 보인 다음 스크롤을 한 번 더 내려야 나타난다(둘 사이 간격을
      // 넉넉히 띄워둬서 한 번의 스크롤에 둘 다 동시에 걸리지 않게 했다).
      const titleEl = titleRef.current;
      if (titleEl && !a.titleShown && titleEl.getBoundingClientRect().top < window.innerHeight * 0.92) {
        a.titleShown = true;
        setTitleRevealed(true);
      }
      const subEl = subtitleRef.current;
      if (subEl && a.titleShown && !a.subShown && subEl.getBoundingClientRect().top < window.innerHeight * 0.92) {
        a.subShown = true;
        setSubRevealed(true);
      }

      const c = cardRef.current;
      if (c && !a.cardOn && c.getBoundingClientRect().top < window.innerHeight * 0.72) {
        a.cardOn = true;
        c.style.opacity = '1';
        c.style.transform = 'translateY(0px)';
        if (cmpRef.current.phase === 0) startCmp();
      }

      // 분석이 끝난 뒤에는 스크롤 방향에 따라 표가 접히고/펼쳐짐
      const y = window.scrollY;
      if (a.lastY == null) a.lastY = y;
      if (cmpRef.current.phase === 2 && Math.abs(y - a.lastY) > 3) {
        const down = y > a.lastY;
        if (down && cmpRef.current.open) collapseCmp(true);
        if (!down && !cmpRef.current.open) collapseCmp(false);
      }
      a.lastY = y;

      paintZoom();

      const now = performance.now();
      const dt = Math.min(60, now - (a.last || now - 16));
      a.last = now;
      paintGlobe(dt, now);
    };

    const loop = () => {
      if (!mounted) return;
      raf = requestAnimationFrame(loop);
      tick();
    };

    const onScroll = () => {
      if (mounted) tick();
    };

    tick();
    raf = requestAnimationFrame(loop);
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    document.addEventListener('visibilitychange', onScroll);

    return () => {
      mounted = false;
      cancelAnimationFrame(raf);
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
      document.removeEventListener('visibilitychange', onScroll);
    };
    // startCmp/collapseCmp는 useCallback(..., [])로 참조가 고정돼 있어
    // 여기 추가해도 이 effect는 여전히 마운트 시 한 번만 실행된다.
  }, [startCmp, collapseCmp]);

  /* ── 지구본 마우스 인터랙션 ────────────────── */
  const onGlobeMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    const mx = e.clientX - r.left;
    const my = e.clientY - r.top;
    const a = A.current;
    a.over = true;
    const off = Math.max(-1, Math.min(1, (mx / (r.width || 1) - 0.5) * 2.4));
    a.drag = Math.sign(off) * Math.pow(Math.abs(off), 1.5);

    let hit: { i: number; x: number; y: number } | null = null;
    let best = 44;
    a.pinDrawn.forEach((q) => {
      const d = Math.hypot(q.x - mx, q.y - my);
      if (d < best) {
        best = d;
        hit = q;
      }
    });
    if (!hit) {
      if (pinRef.current.on) setPin((s) => ({ ...s, on: false }));
      return;
    }
    const h = hit as { i: number; x: number; y: number };
    setPin({ on: true, i: h.i, x: h.x, y: h.y, boxW: r.width, boxH: r.height });
  };

  const onGlobeLeave = () => {
    A.current.drag = 0;
    A.current.over = false;
    if (pinRef.current.on) setPin((s) => ({ ...s, on: false }));
  };

  /* ── 파생 값 ──────────────────────────────── */
  const cmpOpen = cmp.phase === 2 && cmp.open;
  const activePin = PINS[pin.i];
  const pinLeft = Math.round(Math.max(8, Math.min((pin.boxW || 800) - 260, pin.x + 22)));
  const pinTop = Math.round(
    Math.max(8, Math.min((pin.boxH || 500) - 160, pin.y > 176 ? pin.y - 168 : pin.y + 22)),
  );

  const dotLayer = (side: 'left' | 'right'): React.CSSProperties => ({
    position: 'absolute',
    [side]: 0,
    top: 0,
    width: 'min(26vw, 360px)',
    height: 'min(26vw, 360px)',
    zIndex: 2,
    pointerEvents: 'none',
    backgroundImage: `radial-gradient(${T.dotInk} 32%, rgba(0,0,0,0) 33%)`,
    backgroundSize: '13px 13px',
    backgroundPosition: side === 'left' ? '0 0' : '100% 0',
    opacity: lifted ? T.dotMax : 0,
    transition: 'opacity .8s ease',
    animation: charted ? 'iv-dot-breathe 6.5s ease-in-out 1s infinite' : 'none',
    maskImage: `radial-gradient(115% 115% at ${side === 'left' ? '0% 0%' : '100% 0%'}, #000 0%, rgba(0,0,0,.6) 46%, rgba(0,0,0,0) 90%)`,
    WebkitMaskImage: `radial-gradient(115% 115% at ${side === 'left' ? '0% 0%' : '100% 0%'}, #000 0%, rgba(0,0,0,.6) 46%, rgba(0,0,0,0) 90%)`,
  });

  const shimmer = (side: 'left' | 'right'): React.CSSProperties => ({
    position: 'absolute',
    inset: 0,
    background: `radial-gradient(48% 48% at 50% 50%, ${T.dotInk} 0%, rgba(0,0,0,0) 78%)`,
    mixBlendMode: 'screen',
    opacity: 0.5,
    backgroundRepeat: 'no-repeat',
    backgroundSize: '190% 190%',
    backgroundPosition: side === 'left' ? '0% 0%' : '100% 0%',
    animation: charted
      ? side === 'left'
        ? 'iv-dot-shimmer-l 5.5s linear 1.2s infinite'
        : 'iv-dot-shimmer-r 5.5s linear 2.2s infinite'
      : 'none',
  });

  return (
    <div
      className="iv-root"
      style={{
        position: 'relative',
        width: '100%',
        background: T.pageBg,
        fontFamily: "'Pretendard', 'Pretendard Variable', -apple-system, sans-serif",
      }}
    >
      {/* ══ 01 Hero ══════════════════════════════ */}
      <section
        id="hero"
        style={{
          position: 'relative',
          height: '100vh',
          minHeight: 560,
          overflow: 'hidden',
          background: T.heroBase,
        }}
      >
        <div
          style={{
            position: 'absolute',
            inset: '-25%',
            opacity: lifted ? 0.55 : 1,
            filter: 'blur(80px) saturate(1.05)',
            transition: 'opacity 1.6s cubic-bezier(.16,1,.3,1)',
          }}
        >
          <div
            style={{
              position: 'absolute',
              inset: '-10%',
              animation: 'iv-drift 24s ease-in-out infinite',
              backgroundImage: T.meshImage,
            }}
          />
        </div>

        <div
          style={{
            position: 'absolute',
            inset: 0,
            background: T.veilBg,
            opacity: lifted ? 0.2 : 0.12,
            transition: 'opacity 1.4s cubic-bezier(.16,1,.3,1)',
          }}
        />

        {/* 좌·우 상단 도트 패턴 + 발광 하이라이트 */}
        <div style={dotLayer('left')}>
          <div style={shimmer('left')} />
        </div>
        <div style={dotLayer('right')}>
          <div style={shimmer('right')} />
        </div>

        {/* 로고 + 태그라인 (등장 후 위로 이동) */}
        <div
          style={{
            position: 'absolute',
            left: 0,
            right: 0,
            top: '50%',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 10,
            zIndex: 4,
            padding: '0 24px',
            transform: `translate3d(0, ${lifted ? '-38vh' : '0vh'}, 0) scale(${lifted ? 0.54 : 1})`,
            transformOrigin: '50% 0%',
            transition: 'transform 1.5s cubic-bezier(.2,.9,.24,1)',
          }}
        >
          <h1
            style={{
              margin: '-0.3em 0 0',
              display: 'block',
              width: 'min(62vw, 760px)',
              opacity: heroIn ? 1 : 0,
              filter: `blur(${heroIn ? 0 : 14}px)`,
              transform: `scale(${heroIn ? 1 : 0.9})`,
              transition:
                'opacity 2.2s cubic-bezier(.32,0,.3,1), filter 2.4s cubic-bezier(.32,0,.3,1), transform 2.6s cubic-bezier(.16,1,.3,1)',
            }}
          >
            {/* eslint-disable-next-line @next/next/no-img-element -- 워드마크는 고정비 PNG 하나뿐이라 next/image 최적화 이점이 없다 */}
            <img src={logoSrc} alt="Investup" style={{ display: 'block', width: '100%', height: 'auto' }} />
          </h1>
          <p
            style={{
              margin: 0,
              fontSize: 'clamp(16px, 2vw, 28px)',
              fontWeight: 500,
              letterSpacing: '-.02em',
              color: T.taglineInk,
              opacity: heroIn ? 1 : 0,
              filter: `blur(${heroIn ? 0 : 14}px)`,
              transform: `translateY(${heroIn ? 0 : 22}px) scale(${lifted ? 1.85 : 1})`,
              transformOrigin: '50% 0%',
              transition:
                'opacity 2.2s cubic-bezier(.32,0,.3,1) .18s, filter 2.4s cubic-bezier(.32,0,.3,1) .18s, transform 2.6s cubic-bezier(.16,1,.3,1) .18s',
              textAlign: 'center',
              wordBreak: 'keep-all',
            }}
          >
            위험 없이 시작하는 진짜 투자 감각
          </p>
        </div>

        {/* 도트 지구본 + 핀 팝업 */}
        <div
          style={{
            position: 'absolute',
            left: '50%',
            bottom: 0,
            width: 'min(1180px, 92vw)',
            height: '58vh',
            marginLeft: 'calc(min(1180px, 92vw) / -2)',
            zIndex: 3,
            opacity: charted ? 1 : 0,
            transform: `translateY(${charted ? 0 : 70}px)`,
            transition: 'opacity 1s ease, transform 1.2s cubic-bezier(.2,.9,.24,1)',
          }}
        >
          <canvas
            ref={globeRef}
            onMouseMove={onGlobeMove}
            onMouseLeave={onGlobeLeave}
            style={{
              position: 'absolute',
              inset: 0,
              width: '100%',
              height: '100%',
              display: 'block',
              cursor: 'crosshair',
            }}
          />
          {pin.on && (
            <div
              style={{
                position: 'absolute',
                left: pinLeft,
                top: pinTop,
                width: 252,
                padding: '20px 22px 18px',
                borderRadius: 20,
                background:
                  'linear-gradient(155deg, rgba(255,255,255,0.58) 0%, rgba(239,246,252,0.4) 100%)',
                border: '1px solid rgba(255,255,255,0.72)',
                boxShadow:
                  '0 24px 60px rgba(15,56,104,0.18), inset 0 1px 0 rgba(255,255,255,0.7)',
                backdropFilter: 'blur(18px) saturate(1.3)',
                WebkitBackdropFilter: 'blur(18px) saturate(1.3)',
                pointerEvents: 'none',
                zIndex: 4,
              }}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 12,
                }}
              >
                <span style={{ fontSize: 15, fontWeight: 500, color: '#5f7f99', letterSpacing: '-.01em' }}>
                  {activePin.title}
                </span>
                <span
                  style={{
                    fontSize: 14,
                    fontWeight: 500,
                    color: activePin.up ? '#c64a4a' : '#3a6ea8',
                  }}
                >
                  {activePin.delta}
                </span>
              </div>
              <div style={{ height: 1, margin: '15px -22px 14px', background: 'rgba(255,255,255,0.72)' }} />
              <div
                style={{
                  display: 'flex',
                  alignItems: 'baseline',
                  justifyContent: 'space-between',
                  gap: 16,
                  marginBottom: 7,
                }}
              >
                <span style={{ fontSize: 15, color: '#5f7f99' }}>Price</span>
                <span style={{ fontSize: 16, fontWeight: 500, letterSpacing: '-.01em', color: '#071829' }}>
                  {activePin.price}
                </span>
              </div>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'baseline',
                  justifyContent: 'space-between',
                  gap: 16,
                }}
              >
                <span style={{ fontSize: 15, color: '#5f7f99' }}>주당가</span>
                <span style={{ fontSize: 16, fontWeight: 500, letterSpacing: '-.01em', color: '#071829' }}>
                  {activePin.sub}
                </span>
              </div>
            </div>
          )}
        </div>

        <a
          href="#practices"
          style={{
            position: 'absolute',
            left: '50%',
            bottom: 6,
            marginLeft: -26,
            width: 52,
            height: 52,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 5,
            opacity: charted ? 1 : 0,
            transition: 'opacity 1s ease',
          }}
          aria-label="다음 섹션으로"
        >
          <svg width="42" height="20" viewBox="0 0 46 22" fill="none" style={{ animation: 'iv-bob 2.6s ease-in-out infinite' }}>
            <path d="M2 2L23 19L44 2" stroke={T.chevronInk} strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </a>
      </section>

      {/* ══ 02 Steps (메인 화면 "이렇게 사용해요" 섹션과 동일한 문구·디자인·효과) ══ */}
      <section
        id="practices"
        style={{
          position: 'relative',
          padding: 'clamp(80px, 12vh, 160px) clamp(24px, 5vw, 96px) clamp(96px, 14vh, 180px)',
          background: T.practicesBg,
        }}
      >
        {/* 아래 03 Compare의 eyebrow + 마스크 슬라이드업 제목과 완전히 같은 스타일·효과 —
            글자만 다르다. */}
        <p
          style={{
            margin: '0 0 18px',
            textAlign: 'center',
            fontSize: 13,
            fontWeight: 500,
            letterSpacing: '.22em',
            textTransform: 'uppercase',
            color: T.eyebrowInk,
          }}
        >
          three steps
        </p>
        <h2
          style={{
            margin: '0 auto clamp(32px, 5vh, 56px)',
            textAlign: 'center',
            fontSize: 'clamp(28px, 4vw, 52px)',
            fontWeight: 700,
            letterSpacing: '-.035em',
            lineHeight: 1.28,
            color: T.sectionHeadInk,
            maxWidth: '18em',
            wordBreak: 'keep-all',
            textWrap: 'pretty' as never,
          }}
        >
          {/* 초기 포즈는 인라인, 이후 스크롤 위치에 따라 매 프레임 직접 DOM 쓰기
              (updateWordsReveal) — 단어마다 순서대로 블러+상승에서 선명하게 나타난다. */}
          <span ref={stepsTitleRef}>
            {STEPS_TITLE_WORDS.map((w, i) => (
              <Fragment key={w}>
                <span
                  className="iv-word"
                  style={{ display: 'inline-block', opacity: 0, filter: 'blur(16px)', transform: 'translateY(24px)' }}
                >
                  {w}
                </span>
                {i < STEPS_TITLE_WORDS.length - 1 && ' '}
              </Fragment>
            ))}
          </span>
        </h2>
        <Reveal delay={0} duration={1}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'clamp(16px, 2vw, 28px)' }}>
            {STEPS.map((s) => (
              <TiltCard
                key={s.step}
                style={{
                  flex: '1 1 260px',
                  borderRadius: 20,
                  padding: 'clamp(24px, 2.6vw, 40px)',
                  textAlign: 'center',
                  // 라이트 모드 전용 화면이라 메인 화면 STEP 카드의 라이트 모드 배경값을
                  // 그대로 쓴다(page.tsx의 theme === "light" 분기와 동일).
                  background:
                    'radial-gradient(120% 120% at 50% 50%, rgba(196,222,248,0.85) 0%, rgba(196,222,248,0.4) 45%, #ffffff 100%)',
                }}
              >
                <span style={{ display: 'inline-block', fontSize: 12, fontWeight: 800, color: T.cardNumInk }}>
                  {s.step}
                </span>
                <h4 style={{ margin: '8px 0', fontSize: 16, fontWeight: 700, color: T.sectionHeadInk }}>{s.title}</h4>
                <p style={{ margin: 0, fontSize: 14, lineHeight: 1.6, color: T.eyebrowInk }}>
                  {s.desc.map((line, i) => (
                    <Fragment key={line}>
                      {i > 0 && <br />}
                      {line}
                    </Fragment>
                  ))}
                </p>
              </TiltCard>
            ))}
          </div>
        </Reveal>
      </section>

      {/* ══ 03 Compare ═══════════════════════════ */}
      <section
        id="compare"
        style={{
          position: 'relative',
          padding: 'clamp(80px, 12vh, 150px) clamp(24px, 5vw, 96px) clamp(90px, 14vh, 170px)',
          background: T.practicesBg,
        }}
      >
        <p
          style={{
            margin: '0 0 18px',
            textAlign: 'center',
            fontSize: 13,
            fontWeight: 500,
            letterSpacing: '.22em',
            textTransform: 'uppercase',
            color: T.eyebrowInk,
          }}
        >
          difference
        </p>
        <h2
          style={{
            margin: '0 auto clamp(32px, 5vh, 56px)',
            textAlign: 'center',
            fontSize: 'clamp(28px, 4vw, 52px)',
            fontWeight: 700,
            letterSpacing: '-.035em',
            lineHeight: 1.28,
            color: T.sectionHeadInk,
            maxWidth: '18em',
            wordBreak: 'keep-all',
            textWrap: 'pretty' as never,
          }}
        >
          {/* 화면에 들어오면(titleRevealed) 전체 단어가 한 번에 나타난다 — 단어마다
              WORD_STAGGER_S만큼 시작을 늦춰 왼쪽부터 순서대로 나타나 보이지만,
              스크롤을 더 내릴 필요 없이 짧은 스크롤 한 번으로 전체가 재생된다. */}
          <span ref={titleRef}>
            {COMPARE_TITLE_WORDS.map((w, i) => (
              <Fragment key={w}>
                <span
                  className="iv-word"
                  style={{
                    display: 'inline-block',
                    opacity: titleRevealed ? 1 : 0,
                    filter: titleRevealed ? 'blur(0px)' : 'blur(16px)',
                    transform: titleRevealed ? 'translateY(0px)' : 'translateY(24px)',
                    transition: `opacity .6s cubic-bezier(.2,.9,.24,1) ${(i * WORD_STAGGER_S).toFixed(2)}s, filter .6s cubic-bezier(.2,.9,.24,1) ${(i * WORD_STAGGER_S).toFixed(2)}s, transform .6s cubic-bezier(.2,.9,.24,1) ${(i * WORD_STAGGER_S).toFixed(2)}s`,
                  }}
                >
                  {w}
                </span>
                {i < COMPARE_TITLE_WORDS.length - 1 && ' '}
              </Fragment>
            ))}
          </span>
        </h2>

        {/* 제목을 보충하는 한 줄 설명 — 제목과는 marginTop으로 크게 띄웠고(요청에
            따라 한 번 더 넓혔다), 제목이 나타난 뒤 스크롤을 한 번 더 내리면
            제목과 똑같은 단어별 등장 효과(subRevealed)로 나타난다. */}
        <p
          style={{
            margin: 'clamp(80px, 13vh, 160px) auto 0',
            textAlign: 'center',
            fontSize: 'clamp(18px, 2.1vw, 24px)',
            fontWeight: 600,
            lineHeight: 1.6,
            color: T.cardBodyInk,
            maxWidth: '32em',
            wordBreak: 'keep-all',
            textWrap: 'pretty' as never,
          }}
        >
          <span ref={subtitleRef}>
            {COMPARE_SUBTITLE_WORDS.map((w, i) => (
              <Fragment key={`${w}-${i}`}>
                <span
                  className="iv-word"
                  style={{
                    display: 'inline-block',
                    opacity: subRevealed ? 1 : 0,
                    filter: subRevealed ? 'blur(0px)' : 'blur(16px)',
                    transform: subRevealed ? 'translateY(0px)' : 'translateY(24px)',
                    transition: `opacity .6s cubic-bezier(.2,.9,.24,1) ${(i * WORD_STAGGER_S).toFixed(2)}s, filter .6s cubic-bezier(.2,.9,.24,1) ${(i * WORD_STAGGER_S).toFixed(2)}s, transform .6s cubic-bezier(.2,.9,.24,1) ${(i * WORD_STAGGER_S).toFixed(2)}s`,
                  }}
                >
                  {w}
                </span>
                {i < COMPARE_SUBTITLE_WORDS.length - 1 && ' '}
              </Fragment>
            ))}
          </span>
        </p>

        {/* 제목과의 간격을 더 띄우고(marginTop), 카드를 가운데로 정렬했다
            (기존엔 justifyContent: 'flex-start'라 왼쪽에 붙어 있었다). */}
        <div style={{ display: 'flex', justifyContent: 'center', marginTop: 'clamp(64px, 11vh, 140px)' }}>
          <div
            ref={cardRef}
            style={{
              position: 'relative',
              opacity: 0,
              transform: 'translateY(56px)',
              width: '100%',
              maxWidth: 1180,
              padding: 'clamp(26px, 3vw, 40px)',
              borderRadius: 30,
              overflow: 'hidden',
              background: 'linear-gradient(150deg, #dceafa 0%, #eef4fc 42%, #e2ecf9 78%, #cfe0f4 100%)',
              boxShadow: '0 26px 60px rgba(15,56,104,0.16)',
              transformOrigin: '0% 0%',
              transition:
                'opacity .9s cubic-bezier(.2,.9,.24,1), transform 1s cubic-bezier(.2,.9,.24,1)',
            }}
          >
            <div
              style={{
                position: 'absolute',
                inset: 0,
                pointerEvents: 'none',
                background:
                  'radial-gradient(60% 50% at 18% 10%, rgba(255,255,255,.85), rgba(255,255,255,0) 70%)',
              }}
            />

            <div style={{ position: 'relative' }}>
              <div style={{ fontSize: 15, fontWeight: 500, color: '#1b6da3', letterSpacing: '-.01em' }}>
                {cmpOpen ? '분석 완료' : '차이점 분석 중…'}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 6 }}>
                <span
                  style={{
                    // 완료 문구가 "4가지가 다릅니다" 같은 짧은 헤드라인에서 문장형으로
                    // 바뀌어서, 헤드라인용 큰 글씨(clamp(28px,3.2vw,38px)/700)를 그대로
                    // 쓰면 문장이 여러 줄로 너무 크고 무겁게 나온다. 문단으로 읽히도록
                    // 크기·굵기·줄간격을 낮췄다(분석 중 숫자는 원래도 짧아 영향 없음).
                    fontSize: 'clamp(19px, 2.2vw, 25px)',
                    fontWeight: 600,
                    lineHeight: 1.45,
                    letterSpacing: '-.02em',
                    color: '#2b3440',
                    wordBreak: 'keep-all',
                    textWrap: 'pretty' as never,
                  }}
                >
                  {/* 분석 중엔 실제 비교 항목 수(CMP.length)만큼 1→2→3→4로 차분히
                      오르는 숫자(위 startCmp 참고). 완료 문구는 아래 4개 행이 말하는
                      내용을 한 문장으로 풀어 설명한다. */}
                  {cmpOpen
                    ? '거래를 연습하고, 금융 지식과 자신의 투자 성향까지 이해할 수 있도록 돕습니다.'
                    : `${Math.max(1, Math.round(cmp.val * CMP.length))}가지 확인 중…`}
                </span>
                <span
                  style={{
                    width: 30,
                    height: 30,
                    flex: '0 0 30px',
                    borderRadius: '50%',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    background: '#3d7bfa',
                    opacity: cmpOpen ? 1 : 0,
                    transform: `scale(${cmpOpen ? 1 : 0.4})`,
                    transition: 'opacity .5s ease, transform .6s cubic-bezier(.2,1.4,.35,1)',
                  }}
                >
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#ffffff" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M5 13l4.5 4.5L19 7" />
                  </svg>
                </span>
              </div>

              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'minmax(96px, 132px) 1fr 1fr',
                  gap: 14,
                  padding: '0 20px',
                  marginTop: 'clamp(22px, 3vh, 32px)',
                  opacity: cmpOpen ? 1 : 0,
                  transition: 'opacity .6s ease',
                }}
              >
                <span />
                <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: '-.01em', color: '#1b6da3' }}>
                  일반 증권사 앱
                </span>
                <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: '-.01em', color: '#1f3a68' }}>
                  모의주식 트레이딩
                </span>
              </div>

              {/* 4행 모두 높이가 고정이라(opacity+scaleX만 바뀜, width는 그대로) 이
                  컨테이너 자체의 높이는 바뀌지 않는다 — 그래서 height 트랜지션은
                  필요 없다(레이아웃이 움직이지 않게). */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 14 }}>
                {CMP.map((r, i) => {
                  // 펼칠 땐 위→아래, 접을 땐 아래→위 순서로 살짝씩 시차를 준다
                  // (요청 11번: 아주 짧은 stagger로 순서대로).
                  const delayMs = reducedMotion
                    ? 0
                    : (cmpOpen ? i : CMP.length - 1 - i) * CMP_ROW_STAGGER_MS;
                  const durationMs = reducedMotion ? 1 : CMP_ROW_DURATION_MS;
                  const transition = `opacity ${durationMs}ms ${CMP_ROW_EASE} ${delayMs}ms, transform ${durationMs}ms ${CMP_ROW_EASE} ${delayMs}ms`;
                  const cellStyle: React.CSSProperties = {
                    wordBreak: 'keep-all',
                  };
                  return (
                    <div
                      key={r.label}
                      style={{
                        display: 'grid',
                        gridTemplateColumns: 'minmax(96px, 132px) 1fr 1fr',
                        alignItems: 'center',
                        gap: 14,
                        minHeight: 62,
                        padding: '12px 20px',
                        borderRadius: 18,
                        background: '#ffffff',
                        // 왼쪽 끝을 축으로 오른쪽으로 펼쳐졌다가(scaleX 0→1) 다시
                        // 왼쪽으로 접혀 들어간다(scaleX 1→0) — width처럼 레이아웃에
                        // 영향을 주지 않고 transform(합성 레이어)만으로 처리돼 여전히
                        // 가볍다. opacity를 같은 곡선으로 같이 줘서 폭이 아주 좁을 때
                        // 텍스트가 눌려 보이는 구간을 자연스럽게 가려준다.
                        transformOrigin: '0% 50%',
                        opacity: cmpOpen ? 1 : 0,
                        transform: cmpOpen ? 'scaleX(1)' : 'scaleX(0)',
                        willChange: 'transform, opacity',
                        transition,
                      }}
                    >
                      <span style={{ ...cellStyle, fontSize: 15, fontWeight: 500, letterSpacing: '-.01em', color: '#23456f' }}>
                        {r.label}
                      </span>
                      <span style={{ ...cellStyle, fontSize: 16, letterSpacing: '-.01em', color: '#6b7787' }}>
                        {r.legacy}
                      </span>
                      <span style={{ ...cellStyle, fontSize: 16, fontWeight: 700, letterSpacing: '-.01em', color: '#1f3a68' }}>
                        {r.ours}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ══ 04 Zoom + CTA ════════════════════════ */}
      <section
        id="zoom"
        ref={zoomSecRef as React.RefObject<HTMLElement>}
        style={{ position: 'relative', height: '560vh', background: T.practicesBg }}
      >
        <div
          style={{
            position: 'sticky',
            top: 0,
            height: '100vh',
            overflow: 'hidden',
            background: T.practicesBg,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <div
            ref={lineRef}
            style={{
              position: 'absolute',
              left: '50%',
              top: '50%',
              transform: 'translate(-50%, -50%)',
              zIndex: 2,
              width: 'min(96vw, 1400px)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              whiteSpace: 'nowrap',
              fontSize: 'clamp(26px, 4.4vw, 62px)',
              fontWeight: 700,
              letterSpacing: '-.04em',
              color: T.deepInk,
              pointerEvents: 'none',
            }}
          >
            <span ref={wordARef} style={{ display: 'block', flex: '1 1 0', minWidth: 0, textAlign: 'right', opacity: 0 }}>
              첫 거래는 오늘,
            </span>
            <span ref={gapRef} style={{ display: 'block', width: 26, flex: '0 0 auto' }} />
            <span ref={wordBRef} style={{ display: 'block', flex: '1 1 0', minWidth: 0, textAlign: 'left', opacity: 0 }}>
              첫 손실은 0원
            </span>
          </div>

          {/* 세로 직선 → 정사각형으로 확대되며 화면을 채움 */}
          <div
            ref={zoomCardRef}
            style={{
              position: 'absolute',
              left: '50%',
              top: '50%',
              width: 0,
              height: 0,
              transform: 'translate(-50%, -50%)',
              zIndex: 3,
              background: T.deepInk,
              borderRadius: 999,
              overflow: 'hidden',
              opacity: 0,
            }}
          />

          <div
            ref={ctaRef}
            style={{
              position: 'absolute',
              inset: 0,
              zIndex: 5,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 'clamp(14px, 2vh, 22px)',
              padding: '0 clamp(24px, 6vw, 96px)',
              textAlign: 'center',
              opacity: 0,
              pointerEvents: 'none',
            }}
          >
            <p
              ref={ctaLineRef}
              style={{
                margin: 0,
                fontSize: 'clamp(28px, 4.2vw, 58px)',
                fontWeight: 700,
                letterSpacing: '-.03em',
                lineHeight: 1.32,
                color: '#ffffff',
                wordBreak: 'keep-all',
              }}
            >
              모의 투자금 5,000만원으로 지금 시작해보세요
            </p>
            <a
              ref={ctaBtnRef}
              className="iv-cta-btn"
              href={ctaHref}
              style={{
                marginTop: 'clamp(10px, 2vh, 20px)',
                display: 'inline-flex',
                alignItems: 'center',
                gap: 10,
                minHeight: 54,
                padding: '0 44px',
                borderRadius: 14,
                background: '#ffffff',
                color: T.deepInk,
                fontSize: 17,
                fontWeight: 700,
                letterSpacing: '-.01em',
                textDecoration: 'none',
                boxShadow: '0 18px 44px rgba(4,20,42,.34)',
              }}
            >
              시작하기
            </a>
          </div>
        </div>
      </section>
    </div>
  );
}
