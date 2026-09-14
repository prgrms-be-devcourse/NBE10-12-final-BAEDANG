import { randomUUID, randomBytes } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import net from 'node:net';
import { clean } from './clean.mjs';
import { root, runtime, windows, output, run, launch, waitFor, stopCommands } from './process.mjs';

const id = `baedang-e2e-${randomUUID()}`;
const statePath = path.join(runtime, 'state.json');
await mkdir(runtime, { recursive: true });
try { await writeFile(statePath, JSON.stringify({ id }), { flag: 'wx' }); }
catch { throw new Error('E2E already owns resources; finish the run or use npm run clean'); }
const state = { id };
const save = () => writeFile(statePath, JSON.stringify(state));
let ending = false;
async function finish() { if (!ending) { ending = true; stopCommands(); await clean(); } }
process.on('SIGINT', () => finish().finally(() => process.exit(130)));
process.on('SIGTERM', () => finish().finally(() => process.exit(143)));
try {
  for (const port of [13000, 18088, 18089]) {
    await new Promise((resolve, reject) => {
      const probe = net.createServer(); probe.once('error', reject);
      probe.listen(port, '127.0.0.1', () => probe.close(resolve));
    });
  }
  const token = randomBytes(32).toString('hex');
  const password = randomBytes(24).toString('hex');
  output('docker', ['run', '-d', '--rm', '--name', id, '--label', `baedang.e2e=${id}`,
    '-p', '127.0.0.1::5432', '-e', 'POSTGRES_DB=baedang_e2e', '-e', 'POSTGRES_USER=baedang_e2e',
    '-e', `POSTGRES_PASSWORD=${password}`, 'timescale/timescaledb:latest-pg18']);
  const mapping = output('docker', ['port', id, '5432/tcp']);
  const dbPort = mapping.split(':').at(-1);
  const readyDeadline = Date.now() + 60_000;
  while (true) {
    try { output('docker', ['exec', id, 'pg_isready', '-U', 'baedang_e2e', '-d', 'baedang_e2e']); break; }
    catch { if (Date.now() >= readyDeadline) throw new Error('E2E database readiness timeout'); }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  const env = { ...process.env, E2E_RUN_ID: id, E2E_CONTROL_TOKEN: token, E2E_DB_PASSWORD: password,
    E2E_DB_URL: `jdbc:postgresql://127.0.0.1:${dbPort}/baedang_e2e`,
    NEXT_PUBLIC_API_BASE_URL: 'http://127.0.0.1:18088', NEXT_TELEMETRY_DISABLED: '1' };
  if (windows) await run('cmd.exe', ['/d', '/c', 'gradlew.bat e2eClasspath'], { cwd: path.join(root, 'back'), env });
  else await run('bash', ['gradlew', 'e2eClasspath'], { cwd: path.join(root, 'back'), env });
  const classpath = await readFile(path.join(root, 'back/build/e2e-classpath.txt'), 'utf8');
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', windows ? 'java.exe' : 'java') : 'java';
  const backend = await launch(java, [`-De2e.runId=${id}`, '-cp', classpath, 'com.baedang.e2e.E2eLauncher'], 'backend.log', env);
  state.backPid = backend.pid; await save();
  await waitFor('http://127.0.0.1:18089/health', { 'X-E2E-Key': token }, backend);
  const next = path.join(root, 'front/node_modules/next/dist/bin/next');
  await run(process.execPath, [next, 'build'], { cwd: path.join(root, 'front'), env });
  const frontend = await launch(process.execPath, [`--title=${id}`, next, 'start', path.join(root, 'front'), '--hostname', '127.0.0.1', '--port', '13000'], 'frontend.log', env);
  state.frontPid = frontend.pid; await save();
  await waitFor('http://127.0.0.1:13000', {}, frontend);
  await run(process.execPath, [path.join(root, 'e2e/node_modules/@playwright/test/cli.js'), 'test', ...process.argv.slice(2)],
    { cwd: path.join(root, 'e2e'), env });
} catch (error) {
  console.error(error.message); process.exitCode = 1;
} finally { await finish(); }
