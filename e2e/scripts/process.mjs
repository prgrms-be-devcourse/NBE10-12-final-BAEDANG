import { spawn, spawnSync } from 'node:child_process';
import { readFile, mkdir } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export const runtime = path.join(root, 'e2e/.runtime');
export const windows = process.platform === 'win32';
const activeCommands = new Set();
export function output(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', windowsHide: true });
  if (result.error || result.status !== 0) throw new Error(`${command} failed: ${result.stderr ?? result.error}`);
  return result.stdout.trim();
}
export async function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'inherit', windowsHide: true, detached: !windows, ...options });
    activeCommands.add(child);
    child.on('error', error => { activeCommands.delete(child); reject(error); });
    child.on('exit', code => {
      activeCommands.delete(child);
      code === 0 ? resolve() : reject(new Error(`${command} exited ${code}`));
    });
  });
}
export function stopCommands() {
  for (const child of activeCommands) {
    if (!child.pid || child.exitCode !== null) continue;
    if (windows) spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
    else { try { process.kill(-child.pid, 'SIGTERM'); } catch {} }
  }
}
export async function launch(command, args, logName, env) {
  await mkdir(runtime, { recursive: true });
  const log = createWriteStream(path.join(runtime, logName));
  const child = spawn(command, args, { cwd: root, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.pipe(log); child.stderr.pipe(log);
  child.on('error', error => log.write(String(error)));
  return child;
}
export async function waitFor(url, headers = {}, child) {
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    if (child?.exitCode != null) throw new Error(`Server exited: ${url}`);
    try { const response = await fetch(url, { headers, signal: AbortSignal.timeout(1000) }); if (response.ok) return; } catch {}
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw new Error(`Server readiness timeout: ${url}; inspect e2e/.runtime/*.log`);
}
export async function stopProcess(pid, marker) {
  if (!pid) return;
  let commandLine = '';
  if (windows) {
    const found = spawnSync('powershell.exe', ['-NoProfile', '-Command', '(Get-CimInstance Win32_Process -Filter "ProcessId = $env:E2E_PID").CommandLine'],
      { encoding: 'utf8', windowsHide: true, env: { ...process.env, E2E_PID: String(pid) } });
    commandLine = found.stdout ?? '';
  } else {
    try {
      const environment = await readFile(`/proc/${pid}/environ`, 'utf8');
      if (environment.split('\0').includes(`E2E_RUN_ID=${marker}`)) commandLine = marker;
    } catch { return; }
  }
  if (!commandLine.includes(marker)) return;
  if (windows) spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
  else {
    try { process.kill(pid, 'SIGTERM'); } catch { return; }
    for (let i = 0; i < 30; i++) {
      try { process.kill(pid, 0); } catch { return; }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    try { process.kill(pid, 'SIGKILL'); } catch {}
  }
}
