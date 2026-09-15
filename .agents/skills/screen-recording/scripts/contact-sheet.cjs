#!/usr/bin/env node
// 녹화본(.webm)의 길이를 재고, 지정한 시점의 프레임을 한 장짜리 PNG로 모은다.
// ffmpeg 없이 Playwright의 Chromium으로 영상을 디코딩한다(Playwright는 e2e/ 패키지 것을 쓴다).
//
//   node contact-sheet.cjs <video.webm> <out.png> [--times 2,5.5,d-1] [--count 8] [--crop x,y,w,h] [--width 440]
//
//   --times  쉼표로 구분한 초. "d-1"처럼 쓰면 영상 길이 기준(끝에서 1초 전)이다.
//   --count  --times가 없을 때 처음부터 끝까지 고르게 뽑을 장수(기본 8).
//   --crop   원본 좌표 기준 잘라낼 영역(기본: 전체 화면). 숫자·문구를 읽으려면 좁게 자른다.
//   --width  한 칸의 가로 픽셀(기본 440). 2열 격자로 배치된다.
const fs = require('fs');
const path = require('path');

const repoRoot = path.resolve(__dirname, '../../../..');
const { chromium } = require(require.resolve('@playwright/test', { paths: [path.join(repoRoot, 'e2e'), process.cwd()] }));

function parseArgs(argv) {
  const [video, out, ...rest] = argv;
  if (!video || !out) {
    console.error('사용법: node contact-sheet.cjs <video.webm> <out.png> [--times 2,5,d-1] [--count 8] [--crop x,y,w,h] [--width 440]');
    process.exit(1);
  }
  const opts = { video: path.resolve(video), out: path.resolve(out), times: null, count: 8, crop: null, width: 440 };
  for (let i = 0; i < rest.length; i += 2) {
    const [key, value] = [rest[i], rest[i + 1]];
    if (key === '--times') opts.times = value.split(',').map((s) => s.trim());
    else if (key === '--count') opts.count = Number(value);
    else if (key === '--crop') opts.crop = value.split(',').map(Number);
    else if (key === '--width') opts.width = Number(value);
    else throw new Error(`알 수 없는 옵션: ${key}`);
  }
  return opts;
}

(async () => {
  const opts = parseArgs(process.argv.slice(2));
  const b64 = fs.readFileSync(opts.video).toString('base64');
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: opts.width * 2 + 20, height: 1000 } });
    await page.setContent(`<video id="v" muted src="data:video/webm;base64,${b64}"></video>
      <div id="out" style="display:grid;grid-template-columns:repeat(2,${opts.width}px);gap:4px;width:max-content"></div>`);
    const result = await page.evaluate(async ({ times, count, crop, width }) => {
      const v = document.getElementById('v');
      if (v.readyState < 1) await new Promise((r) => { v.onloadedmetadata = r; });
      // MediaRecorder로 만든 webm은 길이가 Infinity로 오기도 한다 — 끝으로 한 번 이동하면 확정된다.
      if (!isFinite(v.duration)) {
        v.currentTime = 1e9;
        await new Promise((r) => { v.ontimeupdate = r; });
      }
      const d = v.duration;
      const at = (spec) => {
        const m = /^d-(\d+(?:\.\d+)?)$/.exec(spec);
        return Math.min(d, Math.max(0, m ? d - Number(m[1]) : Number(spec)));
      };
      const picks = times
        ? times.map(at)
        : [...Array(count)].map((_, i) => (count === 1 ? d / 2 : 0.3 + (i * (d - 0.6)) / (count - 1)));
      const [sx, sy, w, h] = crop ?? [0, 0, v.videoWidth, v.videoHeight];
      for (const t of picks) {
        await new Promise((r) => { v.onseeked = r; v.currentTime = t; });
        const c = document.createElement('canvas');
        c.width = width;
        c.height = Math.round((h * width) / w);
        const x = c.getContext('2d');
        x.drawImage(v, sx, sy, w, h, 0, 0, c.width, c.height);
        x.fillStyle = 'red';
        x.font = 'bold 16px sans-serif';
        x.fillText(`${t.toFixed(1)}s`, 4, 16);
        document.getElementById('out').appendChild(c);
      }
      v.style.display = 'none';
      return { duration: d, picks: picks.map((t) => Number(t.toFixed(2))) };
    }, { times: opts.times, count: opts.count, crop: opts.crop, width: opts.width });
    await page.locator('#out').screenshot({ path: opts.out });
    console.log(`${path.basename(opts.video)} duration=${result.duration.toFixed(2)}s frames=${result.picks.join(',')} -> ${opts.out}`);
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
