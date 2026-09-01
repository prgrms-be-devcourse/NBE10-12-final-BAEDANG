// 1x1 캔버스를 재사용해서 CSS 변수 값을 lightweight-charts가 이해하는 rgb()로 바꾼다.
let colorProbeContext: CanvasRenderingContext2D | null | undefined;

/**
 * CSS 변수 값을 `lightweight-charts`가 이해하는 구체적 `rgb()` 문자열로 바꾼다.
 *
 * <p>이 앱의 다크 모드 색은 `oklch(...)`로 선언돼 있는데, `getComputedStyle`로 바로
 * 읽으면(엘리먼트의 `color` 속성으로 우회해도 마찬가지) 브라우저가 `lab(...)`처럼
 * lightweight-charts의 색 파서가 모르는 표기 그대로 돌려준다 — "Failed to parse color:
 * lab(...)" 런타임 에러로 실제 확인됨(이슈 #76). Canvas 2D의 `fillStyle`은 oklch/lab을
 * 포함한 모든 CSS `<color>` 표기를 그대로 받아들이고, `getImageData`는 표기와 무관하게
 * 항상 구체적인 0~255 RGBA 픽셀값을 돌려준다 — 그래서 1x1 캔버스에 실제로 "그려서"
 * 되읽는 방식으로 정규화한다.
 *
 * <p>캔들차트(`CandlestickChart`)와 환율 추이 차트가 똑같이 필요로 해서 공용으로 뺐다.
 */
export function resolveCssColor(name: string, fallback: string): string {
  if (typeof document === "undefined") return fallback;
  if (colorProbeContext === undefined) {
    const canvas = document.createElement("canvas");
    canvas.width = 1;
    canvas.height = 1;
    colorProbeContext = canvas.getContext("2d", { willReadFrequently: true });
  }
  const ctx = colorProbeContext;
  if (!ctx) return fallback;

  const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  if (!raw) return fallback;

  try {
    ctx.clearRect(0, 0, 1, 1);
    ctx.fillStyle = raw;
    ctx.fillRect(0, 0, 1, 1);
    const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data;
    return a === 255 ? `rgb(${r}, ${g}, ${b})` : `rgba(${r}, ${g}, ${b}, ${(a / 255).toFixed(3)})`;
  } catch {
    return fallback;
  }
}
