import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, unlink, rmdir } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { clean } from './clean.mjs';
import { processExists, stopProcess } from './process.mjs';

const oldId = 'baedang-e2e-aaaa';
const newId = 'baedang-e2e-bbbb';
const denied = () => Object.assign(new Error('access denied'), { code: 'EPERM' });
const gone = () => Object.assign(new Error('not found'), { code: 'ESRCH' });

async function fixture(t, state) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'baedang-cleanup-test-'));
  const statePath = path.join(directory, 'state.json');
  await writeFile(statePath, JSON.stringify(state));
  t.after(async () => {
    await unlink(statePath).catch(error => { if (error.code !== 'ENOENT') throw error; });
    await rmdir(directory);
  });
  const effects = [];
  return { statePath, effects, options: {
    statePath, exists: () => false,
    stop: async pid => { effects.push(['stop', pid]); },
    docker: (_, args) => {
      effects.push(args);
      if (args[0] === 'ps') return state.id;
      if (args[0] === 'inspect') return state.id;
      return '';
    },
  } };
}

test('이전 runner의 종료는 새 실행에 아무 작업도 하지 않는다', async t => {
  const f = await fixture(t, { id: newId, runnerPid: 123, frontPid: 124 });
  await clean(oldId, f.options);
  assert.deepEqual(f.effects, []);
  assert.equal(JSON.parse(await readFile(f.statePath)).id, newId);
});

test('수동 clean은 살아 있는 runner를 정리하지 않는다', async t => {
  const f = await fixture(t, { id: oldId, runnerPid: 123, frontPid: 124 });
  await assert.rejects(clean(undefined, { ...f.options, exists: () => true }), /still active/);
  assert.deepEqual(f.effects, []);
  assert.equal(JSON.parse(await readFile(f.statePath)).id, oldId);
});

test('프로세스 조회 실패 시 상태와 컨테이너를 유지한다', async t => {
  const f = await fixture(t, { id: oldId, runnerPid: 123 });
  await assert.rejects(clean(undefined, { ...f.options, exists: () => { throw denied(); } }), /access denied/);
  assert.deepEqual(f.effects, []);
  assert.equal(JSON.parse(await readFile(f.statePath)).id, oldId);
});

test('프로세스 종료 실패 시 상태를 유지하고 재시도할 수 있다', async t => {
  const f = await fixture(t, { id: oldId, frontPid: 124, backPid: 125 });
  await assert.rejects(clean(oldId, { ...f.options, stop: async () => { throw denied(); } }), /access denied/);
  assert.equal(JSON.parse(await readFile(f.statePath)).id, oldId);
  assert.deepEqual(f.effects, []);
  await clean(oldId, f.options);
  assert.ok(f.effects.some(args => args[0] === 'rm'));
  await assert.rejects(readFile(f.statePath), { code: 'ENOENT' });
});

test('정리 중 교체된 상태 파일을 삭제하지 않는다', async t => {
  const f = await fixture(t, { id: oldId, frontPid: 124 });
  await clean(oldId, { ...f.options, stop: async () => {
    await writeFile(f.statePath, JSON.stringify({ id: newId }));
  } });
  assert.equal(JSON.parse(await readFile(f.statePath)).id, newId);
});

test('컨테이너 소유권 불일치 시 상태를 유지한다', async t => {
  const f = await fixture(t, { id: oldId });
  await assert.rejects(clean(oldId, { ...f.options, docker: (_, args) => args[0] === 'ps' ? oldId : newId }), /ownership mismatch/);
  assert.equal(JSON.parse(await readFile(f.statePath)).id, oldId);
});

test('이미 종료된 PID와 조회 권한 실패를 구분한다', () => {
  assert.equal(processExists(123, () => { throw gone(); }), false);
  assert.throws(() => processExists(123, () => { throw denied(); }), /access denied/);
});

function windowsOptions(exec, kill = () => {}) {
  return { isWindows: true, exec, kill, sleep: async () => {} };
}
const owned = { status: 0, stdout: JSON.stringify({ commandLine: `java -De2e.runId=${oldId}` }) };

test('Windows 조회 오류를 정상 종료로 간주하지 않는다', async () => {
  await assert.rejects(stopProcess(123, oldId, windowsOptions(() => ({ status: 1, stderr: 'CIM failed' }))), /Process lookup failed/);
});

test('Windows taskkill 실패를 호출자에게 전달한다', async () => {
  await assert.rejects(stopProcess(123, oldId, windowsOptions(command => command === 'powershell.exe' ? owned : { status: 1, stderr: 'Access denied' })), /Process termination failed/);
});

test('Windows 종료 명령 성공 후에도 살아 있으면 실패한다', async () => {
  await assert.rejects(stopProcess(123, oldId, windowsOptions(command => command === 'powershell.exe' ? owned : { status: 0 })), /still alive/);
});

test('Windows 종료 도중 이미 종료된 프로세스는 성공 처리한다', async () => {
  let alive = true;
  await stopProcess(123, oldId, windowsOptions(command => {
    if (command === 'powershell.exe') return owned;
    alive = false;
    return { status: 1 };
  }, () => { if (!alive) throw gone(); }));
});

test('소유권이 다른 PID를 종료하지 않는다', async () => {
  const commands = [];
  await stopProcess(123, oldId, windowsOptions(command => {
    commands.push(command);
    return { status: 0, stdout: JSON.stringify({ commandLine: `java -De2e.runId=${newId}` }) };
  }));
  assert.deepEqual(commands, ['powershell.exe']);
});

test('Linux 환경 조회 권한 실패를 호출자에게 전달한다', async () => {
  await assert.rejects(stopProcess(123, oldId, { isWindows: false, kill: () => {}, read: async () => { throw denied(); } }), /access denied/);
});

test('Linux SIGTERM 권한 실패를 호출자에게 전달한다', async () => {
  await assert.rejects(stopProcess(123, oldId, { isWindows: false,
    read: async () => `E2E_RUN_ID=${oldId}\0`,
    kill: (_, signal) => { if (signal !== 0) throw denied(); },
  }), /access denied/);
});

test('Linux SIGKILL 이후에도 종료되지 않으면 실패한다', async () => {
  const signals = [];
  await assert.rejects(stopProcess(123, oldId, { isWindows: false,
    read: async () => `E2E_RUN_ID=${oldId}\0`, kill: (_, signal) => signals.push(signal), sleep: async () => {},
  }), /still alive/);
  assert.ok(signals.includes('SIGTERM'));
  assert.ok(signals.includes('SIGKILL'));
});
