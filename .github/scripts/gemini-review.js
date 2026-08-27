'use strict';

const https = require('https');
const fs = require('fs');
const { execFileSync, execSync } = require('child_process');

// ---------------------------------------------------------------------------
// Environment variables (injected by GitHub Actions workflow)
// ---------------------------------------------------------------------------
const apiKey    = process.env.GEMINI_API_KEY;
const prNumber  = process.env.PR_NUMBER;
const prTitle   = process.env.PR_TITLE;
const baseSha   = process.env.BASE_SHA;
const headSha   = process.env.HEAD_SHA;
const repo      = process.env.REPO;
const ghToken   = process.env.GITHUB_TOKEN;

const TAG          = '<!-- GEMINI_AI_REVIEW -->';
const QUOTA_NOTICE = `

> ※ 안내: 최신 커밋에 대한 Gemini 코드 리뷰 갱신이 API 할당량(Quota) 초과로 건너뛰어졌습니다. 위 내용은 이전 커밋 기준 리뷰입니다.`;
const MAX_DIFF_LEN = 80_000;
const REQUEST_TIMEOUT_MS = 30_000;
const DIFF_PATHS = [
  '.',
  ':(exclude)**/package-lock.json',
  ':(exclude)**/yarn.lock',
  ':(exclude)**/pnpm-lock.yaml',
  ':(exclude)**/*.png',
  ':(exclude)**/*.jpg',
  ':(exclude)**/*.jpeg',
  ':(exclude)**/*.gif',
  ':(exclude)**/*.svg',
  ':(exclude)**/*.ico',
  ':(exclude)**/*.webp',
  ':(exclude)**/*.woff',
  ':(exclude)**/*.woff2',
  ':(exclude)**/*.ttf',
  ':(exclude)**/*.eot',
];

// ---------------------------------------------------------------------------
// Guard: API key must be set
// ---------------------------------------------------------------------------
if (!apiKey) {
  console.log('GEMINI_API_KEY is not configured in Repository Secrets. Skipping Gemini code review.');
  process.exit(0);
}

// ---------------------------------------------------------------------------
// Collect git diff
// ---------------------------------------------------------------------------
let diff = '';
try {
  diff = execFileSync('git', ['diff', `${baseSha}...${headSha}`, '--', ...DIFF_PATHS], {
    encoding: 'utf8',
  });
} catch (e) {
  console.error('Failed to get git diff:', e.message);
  diff = execFileSync('git', ['diff', 'HEAD~1', '--', ...DIFF_PATHS], { encoding: 'utf8' });
}

if (!diff.trim()) {
  console.log('No diff found. Skipping review.');
  process.exit(0);
}

let truncated = false;
if (diff.length > MAX_DIFF_LEN) {
  diff = diff.substring(0, MAX_DIFF_LEN);
  truncated = true;
}

// ---------------------------------------------------------------------------
// Load review guidelines
// ---------------------------------------------------------------------------
const rulesPath = '.github/gemini-rules.md';
const guidelines = fs.existsSync(rulesPath)
  ? fs.readFileSync(rulesPath, 'utf8')
  : '당신은 모의 주식 트레이딩 서비스의 시니어 코드 리뷰어 AI입니다. 코드 품질, 동시성, 금융 반올림 규칙, 예외 처리를 꼼꼼히 검토해 주세요. 이모지는 사용하지 마세요.';

// ---------------------------------------------------------------------------
// GitHub comment helpers (cached comment lookup)
// ---------------------------------------------------------------------------
/**
 * Fetches the single Gemini review comment on this PR.
 * Result is cached so the API is called at most once per run.
 */
let _cachedComment = undefined;

function getExistingComment() {
  if (_cachedComment !== undefined) return _cachedComment;
  try {
    const raw = execSync(
      `gh api repos/${repo}/issues/${prNumber}/comments --paginate`,
      { env: { ...process.env, GITHUB_TOKEN: ghToken } }
    ).toString();
    const comments = JSON.parse(raw);
    _cachedComment = comments.find(c => c.body && c.body.includes(TAG)) || null;
  } catch (err) {
    console.error('Failed to query existing PR comments:', err.message);
    _cachedComment = null;
  }
  return _cachedComment;
}

/** Posts or updates a PR comment, using stdin to avoid temp files. */
function upsertComment(body) {
  const payload = JSON.stringify({ body });
  const existing = getExistingComment();

  if (existing) {
    execSync(
      `gh api -X PATCH repos/${repo}/issues/comments/${existing.id} --input -`,
      { env: { ...process.env, GITHUB_TOKEN: ghToken }, input: payload }
    );
    console.log(`Successfully updated existing Gemini code review comment (ID: ${existing.id}) on PR #${prNumber}`);
  } else {
    execSync(
      `gh api -X POST repos/${repo}/issues/${prNumber}/comments --input -`,
      { env: { ...process.env, GITHUB_TOKEN: ghToken }, input: payload }
    );
    console.log(`Successfully posted new Gemini code review comment on PR #${prNumber}`);
  }
}

/** Appends a quota-exceeded notice to the existing review comment (if any). */
function handleQuotaExceededNotice(reason) {
  console.warn(`[WARN] ${reason}. Preserving existing review with quota notice.`);
  const existing = getExistingComment();
  if (existing && !existing.body.includes('할당량(Quota) 초과로 건너뛰어졌습니다')) {
    try {
      upsertComment(existing.body + QUOTA_NOTICE);
    } catch (e) {
      console.error('Failed to append quota notice:', e.message);
    }
  }
  process.exit(0);
}

// ---------------------------------------------------------------------------
// Build prompt and call Gemini API
// ---------------------------------------------------------------------------
const prompt = [
  guidelines,
  '',
  '[Pull Request 정보]',
  `- PR 번호: #${prNumber}`,
  `- PR 제목: ${prTitle}`,
  truncated ? '- (주의: diff가 너무 커서 앞부분 일부만 포함되었습니다.)' : '',
  '',
  '[코드 변경사항 (Diff)]',
  '```diff',
  diff,
  '```',
].join('\n');

const requestData = JSON.stringify({
  contents: [{ parts: [{ text: prompt }] }],
  generationConfig: { temperature: 0.2, maxOutputTokens: 4096 },
});

const GEMINI_URL = 'https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent';

const req = https.request(
  GEMINI_URL,
  {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(requestData),
      'x-goog-api-key': apiKey,   // API key via header — safer than URL query param
    },
  },
  (res) => {
    let body = '';
    res.on('data', (chunk) => { body += chunk; });
    res.on('end', () => {
      // 429 → quota exhausted: preserve existing review gracefully
      if (res.statusCode === 429) {
        handleQuotaExceededNotice(`Gemini API rate-limited (429)`);
        return;
      }

      // Other 4xx / 5xx → real error, fail the CI step so it is visible on the PR
      if (res.statusCode >= 400) {
        console.error(`[ERROR] Gemini API returned status ${res.statusCode}: ${body}`);
        process.exit(1);
      }

      try {
        const json = JSON.parse(body);
        const reviewText = json.candidates?.[0]?.content?.parts?.[0]?.text;
        if (!reviewText || !reviewText.trim()) {
          handleQuotaExceededNotice('Empty review content returned from Gemini');
          return;
        }

        const commentBody =
          `${TAG}\n### [Gemini AI 코드 리뷰 - PR #${prNumber}]\n\n${reviewText}\n\n---\n` +
          '*이 리뷰는 GitHub Actions와 Gemini 3.7 Flash에 의해 자동으로 생성·갱신되었습니다.*';

        upsertComment(commentBody);
      } catch (e) {
        console.error('Failed to parse Gemini response or post PR comment:', e.message);
        process.exit(1);
      }
    });
  }
);

req.on('error', (e) => {
  // Network-level errors (DNS failure, timeout, etc.) are not quota issues.
  // Fail the CI step explicitly so the problem is visible on the PR.
  console.error(`[ERROR] Gemini API network error: ${e.message}`);
  process.exit(1);
});

req.setTimeout(REQUEST_TIMEOUT_MS, () => {
  req.destroy(new Error(`Gemini API request timed out after ${REQUEST_TIMEOUT_MS}ms`));
});

req.write(requestData);
req.end();
