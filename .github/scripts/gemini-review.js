'use strict';

const https = require('https');
const fs = require('fs');
const { execFileSync } = require('child_process');

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

const NOTICE_QUOTA_STANDALONE           = `현재 Google Gemini API의 요청 할당량(Quota/Rate Limit)이 초과되어 코드 리뷰 생성이 일시 지연되었습니다.`;
const NOTICE_SERVER_OVERLOAD_STANDALONE = `Google AI 서버의 일시적인 과부하 또는 응답 지연(Timeout)으로 인해 코드 리뷰 생성을 완료하지 못했습니다.`;

const MAX_DIFF_LEN = 60_000;
const REQUEST_TIMEOUT_MS = 60_000;
const COMMAND_TIMEOUT_MS = 10_000;

class ConfigurationError extends Error { }
class ServiceUnavailableError extends Error { }

function report(message, warning = false) {
  console.log(`${warning ? '::warning::' : ''}${message}`);
  if (process.env.GITHUB_STEP_SUMMARY) {
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${message}\n`);
  }
}
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
// Guard: required authentication / repository configuration
// ---------------------------------------------------------------------------
if (![apiKey, ghToken, repo, prNumber].every(value => typeof value === 'string' && value.trim())) {
  report('Gemini 리뷰 설정 오류: GEMINI_API_KEY, GITHUB_TOKEN, REPO, PR_NUMBER를 확인하세요.', true);
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Collect git diff (with multi-tier fallback: SHA -> origin/baseRef -> HEAD~1)
// ---------------------------------------------------------------------------
let diff = '';
try {
  diff = execFileSync('git', ['diff', `${baseSha}...${headSha}`, '--', ...DIFF_PATHS], {
    encoding: 'utf8', timeout: COMMAND_TIMEOUT_MS,
  });
} catch (e) {
  console.warn(`[WARN] Failed to get git diff by SHA (${baseSha}...${headSha}):`, e.message);
  try {
    diff = execFileSync('git', ['diff', `origin/${baseRef}...HEAD`, '--', ...DIFF_PATHS], {
      encoding: 'utf8', timeout: COMMAND_TIMEOUT_MS,
    });
  } catch (err) {
    console.error(`[ERROR] Failed to get git diff against origin/${baseRef}:`, err.message);
    diff = execFileSync('git', ['diff', 'HEAD~1', '--', ...DIFF_PATHS], { encoding: 'utf8', timeout: COMMAND_TIMEOUT_MS });
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

function githubApi(args, input) {
  try {
    return execFileSync('gh', ['api', ...args], {
      env: { ...process.env, GITHUB_TOKEN: ghToken }, input,
      encoding: 'utf8', timeout: COMMAND_TIMEOUT_MS,
    });
  } catch (error) {
    const details = String(error.stderr || '');
    if (error.code === 'ENOENT' || /HTTP (400|401|403|404|422)\b/.test(details)) {
      throw new ConfigurationError('GitHub 댓글 API의 인증·권한·저장소 설정을 확인하세요.');
    }
    if (error.code === 'ETIMEDOUT' || error.signal
        || /HTTP (408|429|5\d\d)\b|timeout|timed out|connection|network|EOF|no such host|TLS|dial tcp/i.test(details)) {
      throw new ServiceUnavailableError('GitHub 댓글 API 연결 실패 또는 응답 시간 초과');
    }
    throw new ConfigurationError('GitHub CLI 실행 실패: 토큰·CLI·요청 설정을 확인하세요.');
  }
}

function getExistingComment() {
  if (_cachedComment !== undefined) return _cachedComment;
  const raw = githubApi([
    `repos/${repo}/issues/${prNumber}/comments`, '--paginate', '--jq',
    `[.[] | select(.body | contains("${TAG}"))] | first`,
  ]).trim();
  try {
    _cachedComment = raw && raw !== 'null' ? JSON.parse(raw) : null;
  } catch {
    throw new ServiceUnavailableError('GitHub 댓글 API가 유효하지 않은 응답을 반환했습니다.');
  }
  return _cachedComment;
}

function upsertComment(body) {
  const existing = getExistingComment();
  const endpoint = existing
    ? `repos/${repo}/issues/comments/${existing.id}`
    : `repos/${repo}/issues/${prNumber}/comments`;
  githubApi(['-X', existing ? 'PATCH' : 'POST', endpoint, '--input', '-'], JSON.stringify({ body }));
}

function publishComment(body) {
  try {
    upsertComment(body);
    report('Gemini 코드 리뷰를 PR에 게시했습니다.');
  } catch (error) {
    if (!(error instanceof ServiceUnavailableError)) throw error;
    report(`리뷰 게시 생략: ${error.message}. 리뷰 게시 성공을 의미하지 않습니다.`, true);
  }
}

/** 외부 장애는 경고와 실행 요약을 남기고 정상 종료하되 인증·설정 오류는 전파합니다. */
function handleGracefulNotice(reason, appendText, standaloneText) {
  report(`Gemini 리뷰 생략: ${reason}. 리뷰 통과를 의미하지 않습니다.`, true);
  try {
    const existing = getExistingComment();
    if (existing) {
      if (!existing.body.includes('건너뛰어졌습니다') && !existing.body.includes('일시 지연')) {
        upsertComment(existing.body + appendText);
      }
    } else {
      upsertComment(`${TAG}\n### [Gemini AI 코드 리뷰 - PR #${prNumber}]\n\n> ${standaloneText}\n\n워크플로우를 재실행하면 리뷰를 다시 시도합니다.`);
    }
  } catch (error) {
    if (!(error instanceof ServiceUnavailableError)) throw error;
    report(`생략 안내 댓글도 게시하지 못했습니다: ${error.message}`, true);
  }
}

// ---------------------------------------------------------------------------
// Build prompt and call Gemini API with bounded model fallback
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
    let timer;
    let settled = false;
    const finish = (error, result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) reject(error);
      else resolve(result);
    };
    const req = https.request(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(requestData),
        'x-goog-api-key': apiKey,
      },
    }, res => {
      res.setEncoding('utf8');
      let body = '';
      res.on('data', chunk => { body += chunk; });
      res.on('end', () => finish(null, { statusCode: res.statusCode, body }));
      res.on('error', error => finish(error));
      res.on('aborted', () => finish(new ServiceUnavailableError('Gemini 응답 연결 중단')));
      res.on('close', () => {
        if (!settled) finish(new ServiceUnavailableError('Gemini 응답이 완료되기 전에 종료됨'));
      });
    });
    req.on('error', error => finish(error));
    // 소켓 유휴 시간이 아닌 DNS·연결·응답 본문 수신을 포함한 전체 요청 제한입니다.
    timer = setTimeout(() => {
      const error = new ServiceUnavailableError('Gemini 전체 요청 시간 초과');
      finish(error);
      req.destroy(error);
    }, REQUEST_TIMEOUT_MS);
    req.write(requestData);
    req.end();
  });
}

async function run() {
  let allQuotaExceeded = true;
  let allModelsMissing = true;

  for (const model of MODELS) {
    console.log(`[INFO] Attempting code review with model: ${model} (1 attempt)`);
    let response;
    try {
      response = await sendGeminiRequest(model);
    } catch (error) {
      allQuotaExceeded = false;
      allModelsMissing = false;
      console.warn(`[WARN] ${model}: network/timeout failure; trying next model.`);
      continue;
    }
    const { statusCode, body } = response;
    if (statusCode !== 404) allModelsMissing = false;
    if (statusCode !== 429) allQuotaExceeded = false;

    if (statusCode >= 200 && statusCode < 300) {
      let reviewText;
      try {
        reviewText = JSON.parse(body).candidates?.[0]?.content?.parts?.[0]?.text;
      } catch {
        console.warn('[WARN] Invalid upstream JSON; trying next model.');
        continue;
      }
      if (typeof reviewText !== 'string' || !reviewText.trim()) continue;
      const commentBody = `${TAG}\n### [Gemini AI 코드 리뷰 - PR #${prNumber}]\n\n${reviewText}\n\n---\n`
        + `*이 리뷰는 GitHub Actions와 ${getModelDisplayName(model)}에 의해 자동으로 생성·갱신되었습니다.*`;
      // 댓글 게시 실패를 Gemini 호출 실패로 취급하여 다시 생성하지 않습니다.
      publishComment(commentBody);
      return;
    }
    if ([408, 429, 404].includes(statusCode) || statusCode >= 500) continue;
    throw new ConfigurationError(`Gemini API 설정 오류 (HTTP ${statusCode}): API 키·권한·요청 설정을 확인하세요.`);
  }

  if (allModelsMissing) throw new ConfigurationError('설정된 Gemini 모델이 모두 존재하지 않습니다 (404).');
  handleGracefulNotice(
    allQuotaExceeded ? '요청 할당량 초과' : '외부 서버 무응답·장애 또는 유효하지 않은 응답',
    allQuotaExceeded ? NOTICE_QUOTA_APPEND : NOTICE_SERVER_OVERLOAD_APPEND,
    allQuotaExceeded ? NOTICE_QUOTA_STANDALONE : NOTICE_SERVER_OVERLOAD_STANDALONE,
  );
}

run().catch(error => {
  report(`Gemini 리뷰 자동화 오류: ${error.message}`, true);
  process.exitCode = 1;
});
