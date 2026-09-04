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
const baseRef   = process.env.BASE_REF || process.env.GITHUB_BASE_REF || 'develop';
const repo      = process.env.REPO;
const ghToken   = process.env.GITHUB_TOKEN;

const TAG                           = '<!-- GEMINI_AI_REVIEW -->';
const NOTICE_QUOTA_APPEND           = `\n\n> ※ 안내: 최신 커밋에 대한 Gemini 코드 리뷰 갱신이 API 할당량(Quota) 초과로 건너뛰어졌습니다. 위 내용은 이전 커밋 기준 리뷰입니다.`;
const NOTICE_SERVER_OVERLOAD_APPEND = `\n\n> ※ 안내: Google AI 서버의 일시적인 과부하(503/Timeout)로 인해 최신 커밋 리뷰 갱신이 건너뛰어졌습니다. 위 내용은 이전 커밋 기준 리뷰입니다.`;
const NOTICE_EMPTY_APPEND          = `\n\n> ※ 안내: Gemini API로부터 유효한 응답을 받지 못하여 최신 커밋 리뷰 갱신이 건너뛰어졌습니다. 위 내용은 이전 커밋 기준 리뷰입니다.`;

const NOTICE_QUOTA_STANDALONE           = `현재 Google Gemini API의 요청 할당량(Quota/Rate Limit)이 초과되어 코드 리뷰 생성이 일시 지연되었습니다.`;
const NOTICE_SERVER_OVERLOAD_STANDALONE = `Google AI 서버의 일시적인 과부하 또는 응답 지연(Timeout)으로 인해 코드 리뷰 생성을 완료하지 못했습니다.`;
const NOTICE_EMPTY_STANDALONE          = `Gemini API로부터 유효한 코드 리뷰 응답을 수신하지 못했습니다.`;

const MAX_DIFF_LEN = 60_000;
const REQUEST_TIMEOUT_MS = 30_000;
const DIFF_PATHS = [
  '.',
  // Package lockfiles
  ':(exclude)**/package-lock.json',
  ':(exclude)**/yarn.lock',
  ':(exclude)**/pnpm-lock.yaml',
  ':(exclude)**/bun.lockb',
  // Build artifacts / cache / source maps
  ':(exclude)**/*.tsbuildinfo',
  ':(exclude)**/*.map',
  ':(exclude)**/*.min.*',
  ':(exclude)**/.next/**',
  ':(exclude)**/build/**',
  ':(exclude)**/.gradle/**',
  ':(exclude)**/*.log',
  // Documentation HTML files (large static prototypes)
  ':(exclude)docs/*.html',
  // Static assets / images / media / fonts / binaries
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
  ':(exclude)**/*.mp4',
  ':(exclude)**/*.pdf',
  ':(exclude)**/*.zip',
];

// ---------------------------------------------------------------------------
// Guard: API key must be set
// ---------------------------------------------------------------------------
if (!apiKey) {
  console.log('GEMINI_API_KEY is not configured in Repository Secrets. Skipping Gemini code review.');
  process.exit(0);
}

// ---------------------------------------------------------------------------
// Collect git diff (with multi-tier fallback: SHA -> origin/baseRef -> HEAD~1)
// ---------------------------------------------------------------------------
let diff = '';
try {
  diff = execFileSync('git', ['diff', `${baseSha}...${headSha}`, '--', ...DIFF_PATHS], {
    encoding: 'utf8',
  });
} catch (e) {
  console.warn(`[WARN] Failed to get git diff by SHA (${baseSha}...${headSha}):`, e.message);
  try {
    diff = execFileSync('git', ['diff', `origin/${baseRef}...HEAD`, '--', ...DIFF_PATHS], {
      encoding: 'utf8',
    });
  } catch (err) {
    console.error(`[ERROR] Failed to get git diff against origin/${baseRef}:`, err.message);
    diff = execFileSync('git', ['diff', 'HEAD~1', '--', ...DIFF_PATHS], { encoding: 'utf8' });
  }
}

if (!diff.trim()) {
  console.log('No diff found. Skipping review.');
  process.exit(0);
}

let truncated = false;
if (diff.length > MAX_DIFF_LEN) {
  const lastNewline = diff.lastIndexOf('\n', MAX_DIFF_LEN);
  diff = diff.substring(0, lastNewline > 0 ? lastNewline : MAX_DIFF_LEN);
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
    const raw = execFileSync(
      'gh',
      [
        'api',
        `repos/${repo}/issues/${prNumber}/comments`,
        '--paginate',
        '--jq',
        `[.[] | select(.body | contains("${TAG}"))] | first`,
      ],
      { env: { ...process.env, GITHUB_TOKEN: ghToken }, encoding: 'utf8' }
    ).trim();

    _cachedComment = raw && raw !== 'null' ? JSON.parse(raw) : null;
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
    execFileSync(
      'gh',
      ['api', '-X', 'PATCH', `repos/${repo}/issues/comments/${existing.id}`, '--input', '-'],
      { env: { ...process.env, GITHUB_TOKEN: ghToken }, input: payload, encoding: 'utf8' }
    );
    console.log(`Successfully updated existing Gemini code review comment (ID: ${existing.id}) on PR #${prNumber}`);
  } else {
    execFileSync(
      'gh',
      ['api', '-X', 'POST', `repos/${repo}/issues/${prNumber}/comments`, '--input', '-'],
      { env: { ...process.env, GITHUB_TOKEN: ghToken }, input: payload, encoding: 'utf8' }
    );
    console.log(`Successfully posted new Gemini code review comment on PR #${prNumber}`);
  }
}

/** Gracefully handles non-fatal API issues (quota, temporary overload) by preserving existing review or posting a notice. */
function handleGracefulNotice(reason, appendText, standaloneText) {
  console.warn(`[WARN] ${reason}.`);
  const existing = getExistingComment();

  if (existing) {
    // 1. 기존 리뷰가 있는 경우: 기존 리뷰 본문은 100% 보존하고 맨 아래에 안내 문구만 추가
    if (!existing.body.includes('건너뛰어졌습니다') && !existing.body.includes('일시 지연')) {
      try {
        upsertComment(existing.body + appendText);
      } catch (e) {
        console.error('Failed to append notice to comment:', e.message);
      }
    }
  } else {
    // 2. 최초 리뷰인데 실패한 경우: 원인을 명시하는 단독 안내 카드 댓글 등록
    try {
      const commentBody =
        `${TAG}\n### [Gemini AI 코드 리뷰 - PR #${prNumber}]\n\n` +
        `> ⚠️ **Gemini 코드 리뷰 일시 지연 안내**\n>\n` +
        `> ${standaloneText}\n\n` +
        `---\n` +
        `*새로운 커밋을 푸시하거나 워크플로우를 재실행하면 코드 리뷰가 다시 시도됩니다.*`;
      upsertComment(commentBody);
    } catch (e) {
      console.error('Failed to post initial notice comment:', e.message);
    }
  }

  process.exit(0);
}

// ---------------------------------------------------------------------------
// Build prompt and call Gemini API with retry
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

const MODELS = ['gemini-3.7-flash', 'gemini-3.6-flash', 'gemini-3.5-flash'];

function getModelDisplayName(model) {
  switch (model) {
    case 'gemini-3.7-flash': return 'Gemini 3.7 Flash';
    case 'gemini-3.6-flash': return 'Gemini 3.6 Flash';
    case 'gemini-3.5-flash': return 'Gemini 3.5 Flash';
    default: return model;
  }
}

function sendGeminiRequest(model) {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`;
  return new Promise((resolve, reject) => {
    const req = https.request(
      url,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(requestData),
          'x-goog-api-key': apiKey,
        },
      },
      (res) => {
        res.setEncoding('utf8');
        let body = '';
        res.on('data', (chunk) => { body += chunk; });
        res.on('end', () => {
          resolve({ statusCode: res.statusCode, body });
        });
      }
    );

    req.on('error', (e) => {
      reject(e);
    });

    req.setTimeout(REQUEST_TIMEOUT_MS, () => {
      req.destroy(new Error(`Gemini API request timed out after ${REQUEST_TIMEOUT_MS}ms`));
    });

    req.write(requestData);
    req.end();
  });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function run() {
  let lastError = null;
  let allQuotaExceeded = true;

  for (const model of MODELS) {
    console.log(`\n[INFO] Attempting code review with model: ${model} (1 attempt)`);

    try {
      const { statusCode, body } = await sendGeminiRequest(model);

      // 1. Success (2xx)
      if (statusCode >= 200 && statusCode < 300) {
        const json = JSON.parse(body);
        const reviewText = json.candidates?.[0]?.content?.parts?.[0]?.text;
        if (!reviewText || !reviewText.trim()) {
          console.warn(`[WARN] ${model} returned empty review text. Falling back to next model.`);
          allQuotaExceeded = false;
          continue;
        }

        const modelDisplayName = getModelDisplayName(model);
        const commentBody =
          `${TAG}\n### [Gemini AI 코드 리뷰 - PR #${prNumber}]\n\n${reviewText}\n\n---\n` +
          `*이 리뷰는 GitHub Actions와 ${modelDisplayName}에 의해 자동으로 생성·갱신되었습니다.*`;

        upsertComment(commentBody);
        return;
      }

      // 2. Quota exceeded (429) -> Fallback to next model (different models have separate quotas)
      if (statusCode === 429) {
        console.warn(`[WARN] ${model} quota exceeded (429). Falling back to next model.`);
        continue;
      }

      allQuotaExceeded = false;

      // 3. Transient server error (5xx) or model not found (404) -> Fallback to next model
      if (statusCode >= 500 || statusCode === 404) {
        console.warn(`[WARN] ${model} returned status ${statusCode}: ${body}. Falling back to next model.`);
        continue;
      }

      // 4. Other client errors (400, 401, 403) -> Fail CI to surface misconfiguration
      console.error(`[ERROR] Gemini API returned fatal status ${statusCode}: ${body}`);
      process.exit(1);

    } catch (err) {
      allQuotaExceeded = false;
      console.warn(`[WARN] Network/timeout error on ${model}: ${err.message}. Falling back to next model.`);
      lastError = err;
    }
  }

  // If all models exhausted:
  if (allQuotaExceeded) {
    handleGracefulNotice('All Gemini models quota exceeded (429)', NOTICE_QUOTA_APPEND, NOTICE_QUOTA_STANDALONE);
  } else {
    handleGracefulNotice(
      `All models exhausted (${lastError?.message || 'Server Overload'})`,
      NOTICE_SERVER_OVERLOAD_APPEND,
      NOTICE_SERVER_OVERLOAD_STANDALONE
    );
  }
}

run();
