import { readFile, unlink } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { output, runtime, stopProcess } from './process.mjs';

export async function clean() {
  const statePath = path.join(runtime, 'state.json');
  let state;
  try { state = JSON.parse(await readFile(statePath, 'utf8')); } catch (error) { if (error.code === 'ENOENT') return; throw error; }
  if (!/^baedang-e2e-[a-f0-9-]+$/.test(state.id)) throw new Error('Invalid E2E resource identifier');
  await stopProcess(state.frontPid, state.id);
  await stopProcess(state.backPid, state.id);
  const containers = output('docker', ['ps', '-a', '--format', '{{.Names}}']).split(/\r?\n/);
  if (containers.includes(state.id)) {
    const label = output('docker', ['inspect', '-f', '{{index .Config.Labels "baedang.e2e"}}', state.id]);
    if (label !== state.id) throw new Error('E2E container ownership mismatch; state retained');
    output('docker', ['rm', '-f', '-v', state.id]);
  }
  await unlink(statePath);
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await clean();
