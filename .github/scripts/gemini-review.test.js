'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');

const source = readFileSync(join(__dirname, 'gemini-review.js'), 'utf8');

// GitHub/Gemini/파일 쓰기를 모두 대체하며 실제 외부 API는 호출하지 않습니다.
async function scenario(options = {}) {
  let requests = 0;
  const comments = [];
  const logs = [];
  const summaries = [];
  const commandTimeouts = [];
  const processStub = {
    env: { GEMINI_API_KEY: 'test', GITHUB_TOKEN: 'test', REPO: 'owner/repo', PR_NUMBER: '1',
      BASE_SHA: 'base', HEAD_SHA: 'head', GITHUB_STEP_SUMMARY: 'summary', ...options.env },
    exitCode: 0,
    exit(code) { this.exitCode = code; throw new Error('TEST_EXIT'); },
  };
  const modules = {
    fs: { existsSync: () => false, appendFileSync: (_, text) => summaries.push(text) },
    child_process: {
      execFileSync(command, args, settings) {
        commandTimeouts.push(settings.timeout);
        if (command === 'git') return 'diff --git a/test b/test';
        const posting = args.includes('-X');
        if (options.ghError && (options.ghErrorStage !== 'post' || posting)) {
          const error = new Error('mock GitHub failure');
          Object.assign(error, options.ghError);
          throw error;
        }
        if (!posting) return options.existing ? JSON.stringify(options.existing) : 'null';
        comments.push(JSON.parse(settings.input).body);
        return '{}';
      },
    },
    https: {
      request(_url, _settings, callback) {
        const index = requests++;
        const req = new EventEmitter();
        req.write = () => {};
        req.destroy = error => req.emit('error', error);
        req.end = () => queueMicrotask(() => {
          if (options.noResponse) return;
          if (options.networkError) return req.emit('error', new Error('ECONNRESET'));
          const res = new EventEmitter();
          res.setEncoding = () => {};
          res.statusCode = options.statuses?.[index] ?? options.status ?? 200;
          callback(res);
          res.emit('data', options.body ?? JSON.stringify({ candidates: [{ content: { parts: [{ text: 'review' }] } }] }));
          if (options.aborted) res.emit('aborted');
          else res.emit('end');
          res.emit('close');
        });
        return req;
      },
    },
  };
  try {
    await vm.runInNewContext(source, {
      require: name => { assert.ok(modules[name], name); return modules[name]; },
      process: processStub, Buffer,
      console: { log: text => logs.push(text), warn: text => logs.push(text), error: text => logs.push(text) },
      setTimeout: (fn, ms) => { assert.equal(ms, 60_000); return setTimeout(fn, 1); },
      clearTimeout,
    });
  } catch (error) {
    if (error.message !== 'TEST_EXIT') throw error;
  }
  return { code: processStub.exitCode, requests, comments, logs, summaries, commandTimeouts };
}

test('successful review is posted once with bounded CLI calls', async () => {
  const result = await scenario();
  assert.equal(result.code, 0);
  assert.equal(result.requests, 1);
  assert.equal(result.comments.length, 1);
  assert.ok(result.commandTimeouts.every(value => value === 10_000));
});

for (const options of [{ noResponse: true }, { networkError: true }, { aborted: true },
  { status: 408 }, { status: 429 }, { status: 503 }, { body: 'invalid JSON' }, { body: '{}' }]) {
  test(`upstream unavailable is a reported skip: ${JSON.stringify(options)}`, async () => {
    const result = await scenario(options);
    assert.equal(result.code, 0);
    assert.equal(result.requests, 3);
    assert.equal(result.comments.length, 1);
    assert.ok(result.summaries.some(text => text.includes('리뷰 생략')));
  });
}

for (const status of [400, 401, 403]) {
  test(`Gemini configuration/auth error ${status} fails without fallback`, async () => {
    const result = await scenario({ status });
    assert.equal(result.code, 1);
    assert.equal(result.requests, 1);
  });
}

test('missing secrets fail before requests', async () => {
  const result = await scenario({ env: { GEMINI_API_KEY: '' } });
  assert.equal(result.code, 1);
  assert.equal(result.requests, 0);
});

test('404 falls back, but all missing models are a configuration error', async () => {
  assert.equal((await scenario({ statuses: [404, 200] })).code, 0);
  assert.equal((await scenario({ status: 404 })).code, 1);
});

test('GitHub timeout skips publishing without regenerating review', async () => {
  const result = await scenario({ ghErrorStage: 'post', ghError: { code: 'ETIMEDOUT' } });
  assert.equal(result.code, 0);
  assert.equal(result.requests, 1);
  assert.ok(result.summaries.some(text => text.includes('게시 생략')));
});

test('GitHub auth failure propagates even when posting a skip notice', async () => {
  for (const status of [200, 503]) {
    const result = await scenario({ status, ghError: { stderr: 'HTTP 403: Resource not accessible' } });
    assert.equal(result.code, 1);
  }
});

test('unavailable GitHub during upstream outage still ends normally', async () => {
  const result = await scenario({ status: 503, ghError: { stderr: 'HTTP 502: Bad Gateway' } });
  assert.equal(result.code, 0);
  assert.ok(result.summaries.some(text => text.includes('리뷰 생략')));
});

test('existing review text survives a skipped update', async () => {
  const result = await scenario({ status: 503, existing: { id: 7, body: 'previous review' } });
  assert.equal(result.code, 0);
  assert.ok(result.comments[0].startsWith('previous review'));
  assert.ok(result.comments[0].includes('이전 커밋 기준'));
});
