/**
 * Investup 인트로(홈페이지 첫 방문 시 소개 화면) — 정적 데이터 & 디자인 토큰.
 * 팀원이 전달한 디자인 패키지("Investup 인트로 화면 디자인.zip")의
 * `lib/investup-intro.data.ts`를 그대로 옮긴 파일이다 — 값을 임의로 바꾸지 않는다.
 */

export const T = {
  pageBg: '#eff6fc',
  heroBase: '#eff6fc',
  meshImage:
    'radial-gradient(closest-side at 22% 26%, #dceefa, rgba(220,238,250,0) 72%), radial-gradient(closest-side at 74% 20%, #e4eff8, rgba(228,239,248,0) 72%), radial-gradient(closest-side at 62% 74%, #dceefa, rgba(220,238,250,0) 70%), radial-gradient(closest-side at 14% 78%, #e9f2f9, rgba(233,242,249,0) 72%)',
  veilBg: '#eff6fc',
  flareCore:
    'radial-gradient(circle at 50% 50%, #ffffff 0%, rgba(255,255,255,.8) 4%, rgba(220,238,250,.4) 12%, rgba(220,238,250,0) 34%)',
  flareStreak:
    'linear-gradient(90deg, rgba(255,255,255,0) 0%, rgba(255,255,255,.6) 44%, #ffffff 50%, rgba(255,255,255,.6) 56%, rgba(255,255,255,0) 100%)',
  logoInk: '#0f3868',
  taglineInk: '#1b6da3',
  chevronInk: '#0f3868',
  practicesBg: '#eff6fc',
  eyebrowInk: '#1b6da3',
  sectionHeadInk: '#071829',
  cardBg: '#ffffff',
  cardLine: '#d3e4f1',
  cardNumInk: '#0f3868',
  cardTitleInk: '#071829',
  cardBodyInk: '#1e3a52',
  dotInk: '#7fb6e3',
  dotMax: 0.85,
  /** 확대되는 사각형 / CTA 배경 */
  deepInk: '#0f3868',
  ctaHover: '#90c3ec',
} as const;

/** 인트로 타임라인 (ms) */
export const TIMING = {
  /** 로고 + 태그라인이 화면 중앙에 머무는 시간 */
  holdBeforeLift: 1400,
  /** 위로 올라간 뒤 지구본이 등장하기까지 */
  chartDelay: 650,
} as const;

/* ── 3가지 실천 카드 ───────────────────────────── */

export const PRACTICES = [
  {
    n: '01',
    title: '실시간 시세로 연습',
    body: '실제 시장 데이터를 그대로 쓰는 모의 계좌로, 잃을 것 없이 매매 습관을 만듭니다.',
  },
  {
    n: '02',
    title: '매매 리포트로 복기',
    body: '모든 주문의 근거와 결과를 기록하고, 승률·손익비를 자동으로 정리해 드립니다.',
  },
  {
    n: '03',
    title: '리그로 함께 성장',
    body: '시즌 랭킹과 전략 공유로, 혼자보다 빠르게 나만의 원칙을 다듬습니다.',
  },
] as const;

/* ── 비교 표 ──────────────────────────────────── */

export const CMP = [
  { label: '목적', legacy: '거래 체결', ours: '학습과 훈련' },
  { label: '실수했을 때', legacy: '실제 손실, 되돌릴 수 없음', ours: '손실 없음, 초기화하고 다시' },
  { label: '수수료·세금', legacy: '거래 후 결과에만 반영', ours: '주문 전 미리 보여줌' },
  { label: '사용법 안내', legacy: '없음', ours: '이용가이드 · 용어 위키 제공' },
] as const;

/** 접힌(스켈레톤) 상태의 행 너비 */
export const CMP_SKELETON_W = ['78%', '68%', '58%', '48%'] as const;

/* ── 지구본: 저해상도 세계지도 비트맵 (64×32) ──── */

type Run = [number, ...Array<[number, number]>];

const G_RUNS: Run[] = [
  [1, [10, 19], [21, 28], [34, 34], [42, 50]],
  [2, [9, 19], [22, 28], [34, 37], [40, 50], [52, 58]],
  [3, [2, 7], [8, 20], [23, 28], [33, 37], [38, 58], [60, 63]],
  [4, [2, 7], [7, 21], [28, 29], [33, 37], [37, 60]],
  [5, [3, 7], [8, 22], [30, 32], [33, 37], [37, 61]],
  [6, [8, 22], [30, 32], [32, 37], [37, 60]],
  [7, [9, 20], [31, 37], [37, 58]],
  [8, [10, 19], [30, 32], [33, 40], [40, 57]],
  [9, [10, 18], [30, 39], [39, 57]],
  [10, [11, 17], [30, 38], [38, 42], [42, 54]],
  [11, [12, 17], [29, 38], [38, 42], [44, 48], [49, 54]],
  [12, [13, 18], [29, 38], [39, 41], [44, 48], [49, 51]],
  [13, [15, 17], [29, 40], [45, 47], [49, 51], [53, 54]],
  [14, [17, 19], [29, 40], [47, 47], [49, 51], [53, 54]],
  [15, [18, 23], [33, 40], [49, 53]],
  [16, [18, 26], [33, 39], [49, 56]],
  [17, [18, 26], [34, 39], [49, 57]],
  [18, [19, 25], [34, 39], [40, 41], [54, 58]],
  [19, [19, 25], [34, 38], [40, 41], [52, 59]],
  [20, [20, 23], [35, 38], [52, 59]],
  [21, [19, 22], [35, 37], [52, 59]],
  [22, [19, 22], [53, 58], [62, 63]],
  [23, [19, 21], [57, 58], [62, 63]],
  [24, [19, 20], [61, 62]],
  [25, [19, 20]],
];

const G_LAND = (() => {
  const s = new Set<number>();
  G_RUNS.forEach((row) => {
    const rowIdx = row[0] as number;
    for (let i = 1; i < row.length; i++) {
      const [a, b] = row[i] as [number, number];
      for (let c = a; c <= b; c++) s.add(rowIdx * 64 + c);
    }
  });
  return s;
})();

const isLand = (lat: number, lon: number) => {
  let l = lon;
  while (l < -180) l += 360;
  while (l > 180) l -= 360;
  const col = Math.min(63, Math.floor(((l + 180) / 360) * 64));
  const row = Math.min(31, Math.max(0, Math.floor(((90 - lat) / 180) * 32)));
  return G_LAND.has(row * 64 + col);
};

/** [lat, lon, isEdge] — 지구본 위 도트 */
export const GLOBE_DOTS: Array<[number, number, 0 | 1]> = (() => {
  const out: Array<[number, number, 0 | 1]> = [];
  const stepLat = 2.4;
  for (let lat = -84; lat <= 84; lat += stepLat) {
    const circ = Math.cos((lat * Math.PI) / 180);
    const n = Math.max(10, Math.round(160 * circ));
    for (let i = 0; i < n; i++) {
      const lon = -180 + (360 * i) / n;
      if (!isLand(lat, lon)) continue;
      let edge: 0 | 1 = 0;
      if (!isLand(lat + stepLat, lon) || !isLand(lat - stepLat, lon)) edge = 1;
      if (!isLand(lat, lon + 360 / n) || !isLand(lat, lon - 360 / n)) edge = 1;
      const jx = (Math.sin(lat * 12.9898 + lon * 78.233) * 43758.5453) % 1;
      const jy = (Math.sin(lat * 39.3468 + lon * 11.135) * 24634.6345) % 1;
      out.push([lat + jy * stepLat * 0.16, lon + jx * (360 / n) * 0.16, edge]);
    }
  }
  return out;
})();

export type Pin = {
  lat: number;
  lon: number;
  title: string;
  price: string;
  delta: string;
  sub: string;
  up: boolean;
};

/** 지구본 위 거래 핀 (up = 상승/빨강, 하락/파랑) */
export const PINS: Pin[] = [
  { lat: 45.5, lon: 127.0, title: '54주 판매', price: '$2,019.21', delta: '+3.6%', sub: '415,000원', up: true },
  { lat: 48.7, lon: -74.0, title: '30주 구매', price: '$1,205.40', delta: '-1.8%', sub: '320,000원', up: false },
  { lat: 56.5, lon: -0.12, title: '18주 판매', price: '$864.70', delta: '+2.1%', sub: '128,000원', up: true },
  { lat: 43.6, lon: 139.7, title: '42주 구매', price: '$1,540.05', delta: '-0.9%', sub: '246,000원', up: false },
  { lat: 41.9, lon: 103.8, title: '25주 판매', price: '$977.60', delta: '+4.4%', sub: '182,000원', up: true },
];

/* ── 이징 헬퍼 ────────────────────────────────── */

export const clamp01 = (v: number) => Math.max(0, Math.min(1, v));
export const eOut = (t: number) => 1 - Math.pow(1 - t, 3);
export const DEG = Math.PI / 180;
