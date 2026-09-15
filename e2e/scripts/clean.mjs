import { readFile, unlink } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { output, runtime, stopProcess, processExists } from './process.mjs';

export async function clean(expectedId, {
  statePath = path.join(runtime, 'state.json'), stop = stopProcess, exists = processExists, docker = output,
} = {}) {
  let state;
  try { state = JSON.parse(await readFile(statePath, 'utf8')); } catch (error) { if (error.code === 'ENOENT') return; throw error; }
  if (!/^baedang-e2e-[a-f0-9-]+$/.test(state.id)) throw new Error('Invalid E2E resource identifier');
  // 이전 실행의 finally가 새 실행의 자원을 정리하지 않도록 합니다.
  if (expectedId !== undefined && state.id !== expectedId) return;
  if (state.runnerPid && state.runnerPid !== process.pid && exists(state.runnerPid)) {
    throw new Error(`E2E runner ${state.runnerPid} is still active; stop its terminal with Ctrl+C before cleaning`);
  }
  await stop(state.frontPid, state.id);
  await stop(state.backPid, state.id);
  const containers = docker('docker', ['ps', '-a', '--format', '{{.Names}}']).split(/\r?\n/);
  if (containers.includes(state.id)) {
    const label = docker('docker', ['inspect', '-f', '{{index .Config.Labels "baedang.e2e"}}', state.id]);
    if (label !== state.id) throw new Error('E2E container ownership mismatch; state retained');
    docker('docker', ['rm', '-f', '-v', state.id]);
  }
  const current = JSON.parse(await readFile(statePath, 'utf8').catch(error => {
    if (error.code === 'ENOENT') return 'null';
    throw error;
  }));
  if (current?.id === state.id) await unlink(statePath);
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await clean();
