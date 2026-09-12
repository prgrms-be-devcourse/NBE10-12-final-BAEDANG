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
 * 04 Zoom      "첫 투자는 오늘, 첫 실수는 0원" → 세로 직선 → 정사각형 확대 → CTA
 */

import { Fragment, useCallback, useEffect, useRef, useState } from 'react';
import {
  T,
  TIMING,
  STEPS,
  STEPS_TITLE_WORDS,
  COMPARE_TITLE_WORDS,
  COMPARE_SUBTITLE_WORDS,
  CTA_LINE_WORDS,
  CMP,
  GLOBE_DOTS,
  PINS,
  DEG,
  clamp01,
} from '@/lib/investup-intro-data';
import { SwapText } from './SwapText';
import { TiltCard } from './TiltCard';
import './investup-intro.css';

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

// 04 Zoom의 남색 패널 — 스크롤 위치를 그대로 쓰지 않고 이 비율만큼씩만 목표
// 크기를 따라가며 입력을 다듬는다(0~1, 1이면 스무딩 없이 스크롤과 완전히
// 1:1). 값을 올리면 스크롤에 더 빠르게/딱딱하게 반응하고, 낮추면 더 부드럽고
// 느긋하게 따라온다 — 속도감을 조절하고 싶으면 이 값 하나만 바꾸면 된다.
const ZOOM_SCROLL_SMOOTHING = 0.35;

type PinState = { on: boolean; i: number; x: number; y: number; boxW: number; boxH: number };
type CmpState = { phase: 0 | 1 | 2; open: boolean };

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
  const [cmp, setCmp] = useState<CmpState>({ phase: 0, open: true });

  const lifted = step >= 4;
  const charted = step >= 5;

  const globeRef = useRef<HTMLCanvasElement | null>(null);
  const stepsTitleRef = useRef<HTMLSpanElement | null>(null);
  const stepsCardsRef = useRef<HTMLDivElement | null>(null);
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
    stepsShown: false,
    titleShown: false,
    subShown: false,
    zoomWordsShown: false,
    ctaBtnShown: false,
    ctaLineShown: false,
    lastY: null as number | null,
  });

  // 비교 섹션 제목/부제 — 화면에 들어오면 한 번에 전체가 나타나는 방식(요청:
  // "스크롤을 한 번만 내려도 문구 속 내용이 다 등장"). 스크롤 위치의 연속
  // 함수로 매 프레임 다시 그리는 대신, 한 번 트리거되면 끝나는 React 상태로
  // 관리한다 — 이 파일의 다른 "1회성 전환"들(cmp, step, heroIn 등)과 같은
  // 패턴이다(계속 값이 바뀌는 연속 스크롤 값만 직접 DOM 쓰기로 처리한다).
  const [titleRevealed, setTitleRevealed] = useState(false);
  const [subRevealed, setSubRevealed] = useState(false);
  // "GET STARTED"/"투자의 첫걸음, 이렇게 시작해요"에도 같은 방식을 적용해달라는
  // 요청 — titleRevealed와 완전히 같은 패턴이다.
  const [stepsRevealed, setStepsRevealed] = useState(false);
  // "첫 투자는 오늘, 첫 실수는 0원"에도 같은 방식을 적용해달라는 요청 — 역시
  // titleRevealed와 완전히 같은 패턴이다.
  const [zoomWordsRevealed, setZoomWordsRevealed] = useState(false);
  // "시작하기" 버튼에도 같은 방식을 적용해달라는 요청 — 역시 titleRevealed와
  // 완전히 같은 패턴이다.
  const [ctaBtnRevealed, setCtaBtnRevealed] = useState(false);
  // "모의 투자금 5,000만원으로 나만의 투자 연습을 시작해보세요."에도 같은
  // 방식을 적용해달라는 요청 — 역시 titleRevealed와 완전히 같은 패턴이다.
  const [ctaLineRevealed, setCtaLineRevealed] = useState(false);

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
  // paintZoom()도 rAF 루프 안에서 매 프레임 도는 클로저라 같은 이유로 ref가
  // 필요하다 — reducedMotion이면 04 Zoom의 스크롤 입력 스무딩(lerp)을 꺼서
  // 지연 없이 스크롤 위치를 그대로 따라가게 한다.
  const reducedMotionRef = useRef(reducedMotion);
  useEffect(() => {
    reducedMotionRef.current = reducedMotion;
  }, [reducedMotion]);

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
  const cmpTimers = useRef<{ done?: number }>({});

  useEffect(() => {
    const timers = cmpTimers.current;
    return () => {
      window.clearTimeout(timers.done);
    };
  }, []);

  // useCallback으로 감싸 참조가 안정적으로 유지되게 했다 — 프레임 루프
  // effect(아래, deps: [])가 이 함수들을 호출하므로 exhaustive-deps 규칙을
  // 만족시키려면 안정적인 참조가 필요하다.
  //
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
    window.clearTimeout(timers.done);
    const initial: CmpState = { phase: 1, open: true };
    cmpRef.current = initial;
    setCmp(initial);
    timers.done = window.setTimeout(() => {
      cmpRef.current = { ...cmpRef.current, phase: 2 };
      setCmp((s) => ({ ...s, phase: 2 }));
    }, 2400);
  }, []);

  const collapseCmp = useCallback((collapse: boolean) => {
    cmpRef.current = { ...cmpRef.current, open: !collapse };
    setCmp((s) => ({ ...s, open: !collapse }));
  }, []);

  /* ── 프레임 루프: 지구본 페인트 + 스크롤 구동 ── */
  useEffect(() => {
    let raf = 0;
    let mounted = true;
    let stepsCardsTimer = 0;

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

      // "기존 증권사 서비스와 무엇이 다른가요?"와 똑같은 1회성 등장 방식으로
      // 바꿨다 — 예전엔 p(스크롤 진행률)에 따라 매 프레임 opacity/translateY를
      // 계산하는 연속 스크럽이었는데, 이제는 이 섹션에 들어오면(p가 조금이라도
      // 움직이면) 한 번만 트리거해서 zoomWordsRevealed를 켜고, 나머지는 JSX의
      // CSS transition이 재생한다(아래 참고).
      const a = A.current;
      if (!a.zoomWordsShown && p > 0.02) {
        a.zoomWordsShown = true;
        setZoomWordsRevealed(true);
      }

      // 선이 더 빠르게 등장하도록 시작 지점을 앞당기고(0.2→0.1) 구간을
      // 좁혔다(0.26→0.16) — 스크롤을 조금만 내려도 선이 금방 다 자란다.
      const bar = clamp01((p - 0.1) / 0.16); // 세로 직선이 길어지는 구간
      const eb = 1 - Math.pow(1 - bar, 4);
      const grow = clamp01((p - 0.5) / 0.38); // 정사각형으로 확대되는 구간
      const eg = grow < 0.5 ? 2.6 * grow * grow * grow : 1 - Math.pow(1 - grow, 2.1);
      const q = clamp01((p - 0.72) / 0.2); // CTA 등장

      // 세로 막대 굵기 — 가느다란 얇은 선으로 보이도록 줄였다(기존 9~16px대 → 3~4px대).
      const barW = Math.max(3, vw * 0.0022);
      const tw = barW + eg * (vw * 2.05 - barW);
      const th = eb * vh * 0.42 + eg * (vh * 2.4 - vh * 0.42);

      if (a.cw == null || a.ch == null) {
        a.cw = tw;
        a.ch = th;
      }
      // prefers-reduced-motion이면 스무딩 없이 스크롤 위치를 그대로 1:1로
      // 따라간다(추가로 "코스팅"되는 움직임을 없앤다). 그 외엔
      // ZOOM_SCROLL_SMOOTHING만큼씩 목표값을 따라가며 입력을 부드럽게 다듬는다
      // — 값이 클수록(1에 가까울수록) 스크롤에 더 빠르게 반응한다. 나중에 속도감을
      // 조절하려면 이 상수 하나만 바꾸면 된다.
      const lerp = reducedMotionRef.current ? 1 : ZOOM_SCROLL_SMOOTHING;
      a.cw += (tw - a.cw) * lerp;
      a.ch += (th - a.ch) * lerp;
      const w = Math.abs(tw - a.cw) < 0.4 ? tw : a.cw;
      const h = Math.abs(th - a.ch) < 0.4 ? th : a.ch;

      // bar > 0이 되는 순간 opacity를 0→1로 그대로 튀게 하던 것 대신, 막대가
      // 자라는 것과 같은 곡선(eb)으로 함께 서서히 밝아지게 해서 "부드럽게
      // 등장"하도록 했다.
      card.style.opacity = eb.toFixed(3);
      // width/height를 매 프레임 바꾸면 리플로우가 생긴다 — 대신 기준 크기를
      // JSX에서 100vw×100vh로 고정해두고(CSS 뷰포트 단위라 리사이즈에도 JS
      // 없이 저절로 맞춰진다), transform: scale()만 매 프레임 써서 확대·축소를
      // 표현한다. scale은 합성 레이어에서만 처리돼 리플로우가 없다.
      card.style.transform = `translate(-50%, -50%) scale(${(w / vw).toFixed(4)}, ${(h / vh).toFixed(4)})`;

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
        // CTA 문구/버튼 둘 다 03 Compare 제목과 같은 1회성 등장 방식으로
        // 바꿨다 — 예전엔 CTA 진행률(q)에 따라 매 프레임 rise()로 계산하는
        // 연속 스크럽이었는데, 이제는 각자 예전 rise의 시작 지점(from)과
        // 같은 지점에서 한 번만 켜고 나머지는 JSX의 CSS transition이 재생한다.
        if (!a.ctaLineShown && q > 0.08) {
          a.ctaLineShown = true;
          setCtaLineRevealed(true);
        }
        if (!a.ctaBtnShown && q > 0.3) {
          a.ctaBtnShown = true;
          setCtaBtnRevealed(true);
        }
      }
    };

    const tick = () => {
      const a = A.current;

      // "GET STARTED"/"투자의 첫걸음, 이렇게 시작해요"도 03 Compare 제목/부제와
      // 완전히 같은 방식(화면에 들어오면 한 번만 트리거되는 전체 등장)으로
      // 바꿨다 — 예전엔 연속 스크럽(updateWordsReveal)을 썼는데, 이제 STEPS쪽도
      // 쓰지 않으므로 그 함수 자체를 지웠다.
      const stepsTitleEl = stepsTitleRef.current;
      if (stepsTitleEl && !a.stepsShown && stepsTitleEl.getBoundingClientRect().top < window.innerHeight * 0.92) {
        a.stepsShown = true;
        setStepsRevealed(true);
        // STEP 1~3 카드는 제목이 다 나타난 다음에 등장해야 한다(요청) — 제목
        // 단어 트랜지션(.6s) + 최대 시차(3 × WORD_STAGGER_S ≈ .21s)보다 넉넉하게
        // 900ms 뒤에 카드를 연다.
        stepsCardsTimer = window.setTimeout(() => {
          const stepsCardsEl = stepsCardsRef.current;
          if (!stepsCardsEl) return;
          stepsCardsEl.style.opacity = '1';
          stepsCardsEl.style.transform = 'translateY(0px)';
        }, 900);
      }

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
      window.clearTimeout(stepsCardsTimer);
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

  // 01 Hero의 좌·우 도트 패턴 + 발광 하이라이트 — 04 Zoom의 CTA 화면 하단에도
  // "동일한 디자인 및 효과"로 넣어달라는 요청으로, 어느 모서리(edge)에 붙일지와
  // 보임/애니메이션 시작 여부(visible/animate)를 인자로 받도록 일반화했다.
  // 원래는 컴포넌트 스코프의 lifted/charted를 직접 읽었는데, 이제 호출하는 쪽이
  // 각자의 트리거(히어로는 lifted/charted, CTA는 ctaLineRevealed/ctaBtnRevealed)를
  // 넘겨준다 — 스타일 값 자체는 완전히 그대로다.
  const dotLayer = (
    side: 'left' | 'right',
    visible: boolean,
    animate: boolean,
    edge: 'top' | 'bottom' = 'top',
  ): React.CSSProperties => {
    const x = side === 'left' ? '0%' : '100%';
    const y = edge === 'top' ? '0%' : '100%';
    return {
      position: 'absolute',
      [side]: 0,
      [edge]: 0,
      width: 'min(26vw, 360px)',
      height: 'min(26vw, 360px)',
      zIndex: 2,
      pointerEvents: 'none',
      backgroundImage: `radial-gradient(${T.dotInk} 32%, rgba(0,0,0,0) 33%)`,
      backgroundSize: '13px 13px',
      backgroundPosition: `${x} ${y}`,
      opacity: visible ? T.dotMax : 0,
      transition: 'opacity .8s ease',
      animation: animate ? 'iv-dot-breathe 6.5s ease-in-out 1s infinite' : 'none',
      maskImage: `radial-gradient(115% 115% at ${x} ${y}, #000 0%, rgba(0,0,0,.6) 46%, rgba(0,0,0,0) 90%)`,
      WebkitMaskImage: `radial-gradient(115% 115% at ${x} ${y}, #000 0%, rgba(0,0,0,.6) 46%, rgba(0,0,0,0) 90%)`,
    };
  };

  const shimmer = (side: 'left' | 'right', animate: boolean): React.CSSProperties => ({
    position: 'absolute',
    inset: 0,
    // 도트 자체 색(T.dotInk)은 그대로 두고, 훑고 지나가는 하이라이트만 살짝
    // 더 옅게(T.dotShimmerInk) 해달라는 요청을 반영했다.
    background: `radial-gradient(48% 48% at 50% 50%, ${T.dotShimmerInk} 0%, rgba(0,0,0,0) 78%)`,
    mixBlendMode: 'screen',
    opacity: 0.5,
    backgroundRepeat: 'no-repeat',
    backgroundSize: '190% 190%',
    backgroundPosition: side === 'left' ? '0% 0%' : '100% 0%',
    animation: animate
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
        <div style={dotLayer('left', lifted, charted)}>
          <div style={shimmer('left', charted)} />
        </div>
        <div style={dotLayer('right', lifted, charted)}>
          <div style={shimmer('right', charted)} />
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
            실수는 가볍게, 투자 감각은 제대로
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
          // 위쪽 여백만 늘렸다 — 지구본(01 Hero)과 "GET STARTED" 사이 간격을
          // 많이 띄워달라는 요청.
          padding: 'clamp(160px, 22vh, 280px) clamp(24px, 5vw, 96px) clamp(96px, 14vh, 180px)',
          background: T.practicesBg,
        }}
      >
        {/* 03 Compare의 eyebrow + 제목과 완전히 같은 스타일·효과(stepsRevealed가
            titleRevealed와 같은 역할) — 화면에 들어오면 한 번에 전체가 나타난다. */}
        <p
          style={{
            margin: '0 0 18px',
            textAlign: 'center',
            fontSize: 13,
            fontWeight: 500,
            letterSpacing: '.22em',
            textTransform: 'uppercase',
            color: T.eyebrowInk,
            opacity: stepsRevealed ? 1 : 0,
            filter: stepsRevealed ? 'blur(0px)' : 'blur(8px)',
            transform: stepsRevealed ? 'translateY(0px)' : 'translateY(12px)',
            transition: 'opacity .6s cubic-bezier(.2,.9,.24,1), filter .6s cubic-bezier(.2,.9,.24,1), transform .6s cubic-bezier(.2,.9,.24,1)',
          }}
        >
          get started
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
          {/* 화면에 들어오면(stepsRevealed) 전체 단어가 한 번에 나타난다 — 단어마다
              WORD_STAGGER_S만큼 시작을 늦춰 왼쪽부터 순서대로 나타나 보이지만,
              스크롤을 더 내릴 필요 없이 짧은 스크롤 한 번으로 전체가 재생된다. */}
          <span ref={stepsTitleRef}>
            {STEPS_TITLE_WORDS.map((w, i) => (
              <Fragment key={w}>
                <span
                  className="iv-word"
                  style={{
                    display: 'inline-block',
                    opacity: stepsRevealed ? 1 : 0,
                    filter: stepsRevealed ? 'blur(0px)' : 'blur(16px)',
                    transform: stepsRevealed ? 'translateY(0px)' : 'translateY(24px)',
                    transition: `opacity .6s cubic-bezier(.2,.9,.24,1) ${(i * WORD_STAGGER_S).toFixed(2)}s, filter .6s cubic-bezier(.2,.9,.24,1) ${(i * WORD_STAGGER_S).toFixed(2)}s, transform .6s cubic-bezier(.2,.9,.24,1) ${(i * WORD_STAGGER_S).toFixed(2)}s`,
                  }}
                >
                  {w}
                </span>
                {i < STEPS_TITLE_WORDS.length - 1 && ' '}
              </Fragment>
            ))}
          </span>
        </h2>
        {/* 제목과의 간격을 많이 띄워달라는 요청 — 처음엔 비교 섹션 카드와 같은
            값(clamp(64px, 11vh, 140px))을 썼는데, 조금 더 띄워달라는 후속
            요청으로 다시 키웠다.
            예전엔 <Reveal>(자체 IntersectionObserver로 뷰포트 진입 시 독립적으로
            등장)로 감쌌는데, "제목이 다 나타난 다음에 카드가 등장"해야 해서
            Reveal 대신 직접 ref에 스타일을 쓴다 — 제목이 트리거되는 순간(tick()의
            stepsShown 분기) 900ms 뒤로 예약된 타이머가 이 스타일을 바꾼다.
            처음엔 Reveal의 riseIn과 같은 translateY(18px)를 썼는데, "등장하는
            티가 안 난다"는 피드백으로 이동 거리를 64px로 크게 늘렸다(지속시간
            1.3s는 그대로 — 더 먼 거리를 같은 시간에 움직이니 체감 속도는
            자연히 조금 더 빨라진다). */}
          <div
            ref={stepsCardsRef}
            style={{
              display: 'flex',
              flexWrap: 'wrap',
              gap: 'clamp(16px, 2vw, 28px)',
              marginTop: 'clamp(90px, 14vh, 170px)',
              opacity: 0,
              transform: 'translateY(64px)',
              transition: 'opacity 1.3s cubic-bezier(.22,1,.36,1), transform 1.3s cubic-bezier(.22,1,.36,1)',
            }}
          >
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
                <span style={{ display: 'inline-block', fontSize: 13, fontWeight: 800, color: T.cardNumInk }}>
                  {s.step}
                </span>
                <h4 style={{ margin: '8px 0', fontSize: 18, fontWeight: 700, color: T.sectionHeadInk }}>{s.title}</h4>
                <p style={{ margin: 0, fontSize: 16, lineHeight: 1.6, color: T.eyebrowInk }}>
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
        {/* 바로 아래 제목과 같은 titleRevealed 플래그로 나타난다 — 지연 없이
            바로 시작해서(제목 단어들은 WORD_STAGGER_S만큼씩 늦게 시작) eyebrow가
            먼저 살짝 떠오르고 그다음 제목이 순서대로 이어지는 느낌을 만든다.
            제목보다 작은 요소라 이동 거리(12px)·블러(8px)도 절반 정도로 옅게 줬다. */}
        <p
          style={{
            margin: '0 0 18px',
            textAlign: 'center',
            fontSize: 13,
            fontWeight: 500,
            letterSpacing: '.22em',
            textTransform: 'uppercase',
            color: T.eyebrowInk,
            opacity: titleRevealed ? 1 : 0,
            filter: titleRevealed ? 'blur(0px)' : 'blur(8px)',
            transform: titleRevealed ? 'translateY(0px)' : 'translateY(12px)',
            transition: 'opacity .6s cubic-bezier(.2,.9,.24,1), filter .6s cubic-bezier(.2,.9,.24,1), transform .6s cubic-bezier(.2,.9,.24,1)',
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
            fontSize: 'clamp(20px, 2.3vw, 27px)',
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
                {/* "도구고," 다음에서 줄바꿈 — 나머지 단어 사이는 그대로 띄어쓰기. */}
                {i < COMPARE_SUBTITLE_WORDS.length - 1 && (w === '도구고,' ? <br /> : ' ')}
              </Fragment>
            ))}
          </span>
        </p>

        {/* 제목과의 간격을 더 띄우고(marginTop), 카드를 가운데로 정렬했다
            (기존엔 justifyContent: 'flex-start'라 왼쪽에 붙어 있었다). */}
        <div style={{ display: 'flex', justifyContent: 'center', marginTop: 'clamp(64px, 11vh, 140px)' }}>
          <div
            ref={cardRef}
            // 분석이 끝난 뒤엔(phase 2) 마우스를 올리면 4행이 오른쪽으로 펼쳐지고,
            // 커서를 떼면(mouseleave) 다시 왼쪽으로 접힌다 — 스크롤 방향으로 여닫는
            // 것과 똑같은 collapseCmp를 그대로 재사용해서 애니메이션(속도·이징·
            // scaleX 방향)이 완전히 동일하다. 분석 중(phase 0/1)엔 아직 펼칠 4행이
            // 없으니 무시한다.
            onMouseEnter={() => {
              if (cmpRef.current.phase === 2) collapseCmp(false);
            }}
            onMouseLeave={() => {
              if (cmpRef.current.phase === 2) collapseCmp(true);
            }}
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
                  {/* 완료 문구는 아래 4개 행이 말하는 내용을 한 문장으로 풀어 설명한다. */}
                  {cmpOpen ? (
                    '거래를 연습하고, 금융 지식과 자신의 투자 성향까지 이해할 수 있도록 돕습니다.'
                  ) : (
                    <Fragment>
                      비교 분석 중
                      {/* "…" 한 글자 대신 점 3개를 각각 다른 span으로 나눠서
                          animation-delay를 다르게 줬다 — 순서대로 살짝 떠올랐다
                          가라앉는 "넘실거리는" 움직임(iv-dot-wave, css 참고). */}
                      <span aria-hidden style={{ display: 'inline-flex' }}>
                        <span className="iv-dot" style={{ animationDelay: '0s' }}>.</span>
                        <span className="iv-dot" style={{ animationDelay: '.15s' }}>.</span>
                        <span className="iv-dot" style={{ animationDelay: '.3s' }}>.</span>
                      </span>
                    </Fragment>
                  )}
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
                  기존 증권사 서비스
                </span>
                <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: '-.01em', color: '#1f3a68' }}>
                  Investup
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
                        // 완전 불투명한 흰색 대신 반투명하게 — 뒤 카드의 그라데이션이
                        // 비쳐 보인다(0.72는 차이가 잘 안 느껴진다는 피드백으로 낮췄다).
                        background: 'rgba(255, 255, 255, 0.45)',
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
            {/* "기존 증권사 서비스와 무엇이 다른가요?" 제목과 완전히 같은
                등장 효과(opacity/blur/translateY/transition 값이 동일) —
                zoomWordsRevealed가 titleRevealed와 같은 역할을 한다. 두
                문구를 COMPARE_TITLE_WORDS의 단어들처럼 취급해 같은
                WORD_STAGGER_S만큼 시차를 준다. */}
            <span
              ref={wordARef}
              style={{
                display: 'block',
                flex: '1 1 0',
                minWidth: 0,
                textAlign: 'right',
                opacity: zoomWordsRevealed ? 1 : 0,
                filter: zoomWordsRevealed ? 'blur(0px)' : 'blur(16px)',
                transform: zoomWordsRevealed ? 'translateY(0px)' : 'translateY(24px)',
                transition: 'opacity .6s cubic-bezier(.2,.9,.24,1), filter .6s cubic-bezier(.2,.9,.24,1), transform .6s cubic-bezier(.2,.9,.24,1)',
              }}
            >
              첫 투자는 오늘,
            </span>
            <span ref={gapRef} style={{ display: 'block', width: 26, flex: '0 0 auto' }} />
            <span
              ref={wordBRef}
              style={{
                display: 'block',
                flex: '1 1 0',
                minWidth: 0,
                textAlign: 'left',
                opacity: zoomWordsRevealed ? 1 : 0,
                filter: zoomWordsRevealed ? 'blur(0px)' : 'blur(16px)',
                transform: zoomWordsRevealed ? 'translateY(0px)' : 'translateY(24px)',
                transition: `opacity .6s cubic-bezier(.2,.9,.24,1) ${WORD_STAGGER_S}s, filter .6s cubic-bezier(.2,.9,.24,1) ${WORD_STAGGER_S}s, transform .6s cubic-bezier(.2,.9,.24,1) ${WORD_STAGGER_S}s`,
              }}
            >
              첫 실수는 0원
            </span>
          </div>

          {/* 세로 얇은 막대 → 화면을 채우는 사각형으로 확대. 기준 크기를
              100vw×100vh로 고정해두고(리사이즈에도 CSS 뷰포트 단위가 알아서
              맞춘다), paintZoom()이 매 프레임 transform: scale()만 써서
              키운다 — width/height를 매 프레임 바꾸면 생기는 리플로우 없이
              합성 레이어에서만 처리된다. border-radius도 고정값(스케일이
              커지는 마지막 구간 기준으로 자연스러운 굵기)이라 별도 계산이
              필요 없다. */}
          <div
            ref={zoomCardRef}
            style={{
              position: 'absolute',
              left: '50%',
              top: '50%',
              width: '100vw',
              height: '100vh',
              transform: 'translate(-50%, -50%) scale(0, 0)',
              zIndex: 3,
              background: T.deepInk,
              borderRadius: 16,
              overflow: 'hidden',
              opacity: 0,
            }}
          />

          {/* 01 Hero의 좌·우 도트 패턴 + 발광 하이라이트와 동일한 디자인·효과를
              이 화면 하단에도 넣어달라는 요청 — 위 dotLayer/shimmer 함수를
              그대로 재사용하되 edge='bottom'으로 아래쪽 모서리에 붙이고,
              히어로의 lifted/charted 대신 이 화면 자체의 등장 신호
              (ctaLineRevealed로 먼저 보이고, ctaBtnRevealed에서 애니메이션
              시작 — 히어로의 "먼저 나타나고 뒤이어 움직이기 시작" 순서와
              같다)를 트리거로 쓴다. 남색 패널(zIndex 3)이 다 채워진 뒤에도
              보여야 하므로 zIndex만 그 위로 올렸다. */}
          <div style={{ ...dotLayer('left', ctaLineRevealed, ctaBtnRevealed, 'bottom'), zIndex: 4 }}>
            <div style={shimmer('left', ctaBtnRevealed)} />
          </div>
          <div style={{ ...dotLayer('right', ctaLineRevealed, ctaBtnRevealed, 'bottom'), zIndex: 4 }}>
            <div style={shimmer('right', ctaBtnRevealed)} />
          </div>

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
              // 문구 뒤에 어두운 남색 그라데이션 — 위쪽은 진하고 아래로 갈수록
              // 옅어져 투명해진다(문구가 있는 위쪽의 대비를 높여준다). 이 div
              // 자체의 opacity(스크롤에 따라 0→q)를 그대로 같이 타므로 등장할
              // 때 그라데이션도 함께 페이드인된다.
              background: 'linear-gradient(180deg, rgba(4,14,28,.62) 0%, rgba(4,14,28,0) 65%)',
              opacity: 0,
              pointerEvents: 'none',
            }}
          >
            {/* "기존 증권사 서비스와 무엇이 다른가요?" 제목과 완전히 같은 등장
                효과(opacity/blur/translateY/transition 값이 동일) —
                ctaLineRevealed가 titleRevealed와 같은 역할을 한다.
                "5,000만원으로" 다음에서 줄바꿈된다. */}
            <p
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
              {CTA_LINE_WORDS.map((w, i) => (
                <Fragment key={`${w}-${i}`}>
                  <span
                    className="iv-word"
                    style={{
                      display: 'inline-block',
                      opacity: ctaLineRevealed ? 1 : 0,
                      filter: ctaLineRevealed ? 'blur(0px)' : 'blur(16px)',
                      transform: ctaLineRevealed ? 'translateY(0px)' : 'translateY(24px)',
                      transition: `opacity .6s cubic-bezier(.2,.9,.24,1) ${(i * WORD_STAGGER_S).toFixed(2)}s, filter .6s cubic-bezier(.2,.9,.24,1) ${(i * WORD_STAGGER_S).toFixed(2)}s, transform .6s cubic-bezier(.2,.9,.24,1) ${(i * WORD_STAGGER_S).toFixed(2)}s`,
                    }}
                  >
                    {w}
                  </span>
                  {i < CTA_LINE_WORDS.length - 1 && (w === '5,000만원으로' ? <br /> : ' ')}
                </Fragment>
              ))}
            </p>
            {/* "기존 증권사 서비스와 무엇이 다른가요?" 제목과 완전히 같은 등장
                효과(opacity/blur/translateY/transition 값이 동일) —
                ctaBtnRevealed가 titleRevealed와 같은 역할을 한다. 예전엔
                CTA 진행률(q)에 따라 매 프레임 rise()로 계산하는 연속
                스크럽이었다. */}
            <a
              ref={ctaBtnRef}
              // iv-hover-swap: 호버/포커스 시 안의 SwapText가 반응하게 하는
              // 트리거 클래스(investup-intro.css 참고) — iv-cta-btn과는
              // 별개라 다른 버튼에도 그대로 재사용할 수 있다.
              className="iv-cta-btn iv-hover-swap"
              href={ctaHref}
              style={{
                // 문구와의 간격을 많이 띄워달라는 요청 — 처음엔
                // clamp(48px, 8vh, 96px)이었는데, 더 띄워달라는 후속 요청으로
                // 다시 키웠다(부모의 flex gap 최대 22px에 더해진다).
                marginTop: 'clamp(80px, 13vh, 160px)',
                display: 'inline-flex',
                alignItems: 'center',
                gap: 10,
                minHeight: 54,
                padding: '0 44px',
                // 오른쪽으로 갈수록 옅어지는 그라데이션은 막상 적용해보니
                // 별로라는 피드백으로 뺐다. 새로 첨부한 사진(어두운 배경 위
                // 반투명한 흰색 알약형 버튼, "자세히 보기") 참고 — 흰색의
                // 불투명도만 낮춘 단색 반투명 배경, 흰 글자로 바꿨다. 무거운
                // 그림자 대신 아주 옅은 그림자는 그대로 뒀다.
                borderRadius: 999,
                background: 'rgba(255,255,255,.16)',
                color: '#ffffff',
                // "시작하기" 글자를 더 키워달라는 요청 — 17 → 19.
                fontSize: 19,
                fontWeight: 700,
                letterSpacing: '-.01em',
                textDecoration: 'none',
                boxShadow: '0 2px 8px rgba(15,23,32,.08)',
                opacity: ctaBtnRevealed ? 1 : 0,
                filter: ctaBtnRevealed ? 'blur(0px)' : 'blur(16px)',
                transform: ctaBtnRevealed ? 'translateY(0px)' : 'translateY(24px)',
                // .iv-cta-btn 클래스의 hover 트랜지션(background-color .28s ease,
                // investup-intro.css 참고)이 인라인 transition에 덮이지 않도록
                // 여기서도 같이 나열했다.
                transition:
                  'opacity .6s cubic-bezier(.2,.9,.24,1), filter .6s cubic-bezier(.2,.9,.24,1), transform .6s cubic-bezier(.2,.9,.24,1), background-color .28s ease',
              }}
            >
              <SwapText>시작하기</SwapText>
            </a>
          </div>
        </div>
      </section>
    </div>
  );
}
