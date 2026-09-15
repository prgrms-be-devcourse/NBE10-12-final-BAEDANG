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
export async function stopCommands() {
  for (const child of activeCommands) {
    if (!child.pid || child.exitCode !== null) continue;
    if (windows) {
      const result = spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, encoding: 'utf8' });
      if ((result.error || result.status !== 0) && processExists(child.pid)) {
        throw new Error(`Command termination failed for ${child.pid}: ${result.stderr || result.error}`);
      }
    } else {
      try { process.kill(-child.pid, 'SIGTERM'); }
      catch (error) { if (error.code !== 'ESRCH') throw error; }
    }
    for (let i = 0; i < 30 && child.exitCode === null && child.signalCode === null; i++) {
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    if (child.exitCode === null && child.signalCode === null && processExists(child.pid)) {
      throw new Error(`Command ${child.pid} is still alive; state retained`);
    }
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
export function processExists(pid, kill = process.kill) {
  if (!Number.isSafeInteger(pid) || pid <= 0) throw new Error('Invalid process identifier');
  try { kill(pid, 0); return true; }
  catch (error) { if (error.code === 'ESRCH') return false; throw error; }
}

export async function stopProcess(pid, marker, {
  isWindows = windows, exec = spawnSync, read = readFile, kill = process.kill,
  sleep = ms => new Promise(resolve => setTimeout(resolve, ms)),
} = {}) {
  if (!pid) return;
  if (!processExists(pid, kill)) return;
  let commandLine = '';
  if (isWindows) {
    const found = exec('powershell.exe', ['-NoProfile', '-Command', '$ErrorActionPreference = "Stop"; $target = Get-CimInstance Win32_Process -Filter "ProcessId = $env:E2E_PID"; if ($null -eq $target) { "null" } else { @{ commandLine = $target.CommandLine } | ConvertTo-Json -Compress }'],
      { encoding: 'utf8', windowsHide: true, env: { ...process.env, E2E_PID: String(pid) } });
    if (found.error || found.status !== 0) throw new Error(`Process lookup failed for ${pid}: ${found.stderr || found.error}`);
    const target = JSON.parse(found.stdout);
    if (target === null) return;
    if (!target.commandLine) throw new Error(`Cannot establish process ownership for ${pid}`);
    commandLine = target.commandLine;
  } else {
    try {
      const environment = await read(`/proc/${pid}/environ`, 'utf8');
      if (environment.split('\0').includes(`E2E_RUN_ID=${marker}`)) commandLine = marker;
    } catch (error) { if (error.code === 'ENOENT' && !processExists(pid, kill)) return; throw error; }
  }
  if (!commandLine.includes(marker)) return;
  const waitForExit = async () => {
    for (let i = 0; i < 30; i++) {
      if (!processExists(pid, kill)) return true;
      await sleep(100);
    }
    return !processExists(pid, kill);
  };
  if (isWindows) {
    const result = exec('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true, encoding: 'utf8' });
    if ((result.error || result.status !== 0) && processExists(pid, kill)) {
      throw new Error(`Process termination failed for ${pid}: ${result.stderr || result.error}`);
    }
  } else {
    try { kill(pid, 'SIGTERM'); } catch (error) { if (error.code === 'ESRCH') return; throw error; }
    if (await waitForExit()) return;
    // PID가 재사용되었는지 소유권을 다시 확인한 뒤 강제 종료합니다.
    const environment = await read(`/proc/${pid}/environ`, 'utf8').catch(error => {
      if (error.code === 'ENOENT' && !processExists(pid, kill)) return '';
      throw error;
    });
    if (!environment.split('\0').includes(`E2E_RUN_ID=${marker}`)) return;
    try { kill(pid, 'SIGKILL'); } catch (error) { if (error.code === 'ESRCH') return; throw error; }
  }
  if (!await waitForExit()) throw new Error(`Process ${pid} is still alive; state retained`);
}
