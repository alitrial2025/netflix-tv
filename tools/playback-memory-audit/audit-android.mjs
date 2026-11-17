#!/usr/bin/env node
// Exercise the APK's real identity parser against Android's native ICU implementation.
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readdirSync, rmSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const options = Object.fromEntries(process.argv.slice(2).map(arg => {
  const [key, ...value] = arg.replace(/^--/, '').split('=');
  return [key, value.join('=')];
}));
if (!options.apk) throw new Error('Provide --apk=/absolute/path/to/app-debug.apk');
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
if (!sdk) throw new Error('Set ANDROID_HOME to the Android SDK directory');
const serial = options.serial || 'emulator-5554';
const className = options.class || 'com.example.data.PublicProviderIdentity';
const method = options.method || 'linkedHotstarIds';
if (!/^[\w.$]+$/.test(className) || !/^\w+$/.test(method)) throw new Error('Invalid class or method');
const budgetKiB = Number(options['max-native-growth-kib'] || 16384);
if (!Number.isFinite(budgetKiB) || budgetKiB <= 0) throw new Error('Invalid memory budget');
const root = dirname(fileURLToPath(import.meta.url));
const local = mkdtempSync(join(tmpdir(), 'tv-html-memory-'));
const remote = `/data/local/tmp/tv-html-memory-${process.pid}`;
const adb = (...args) => execFileSync(join(sdk, 'platform-tools/adb'), ['-s', serial, ...args],
  { encoding: 'utf8', timeout: 600_000, maxBuffer: 2 * 1024 * 1024 });
let remoteCreated = false;
try {
  const platforms = readdirSync(join(sdk, 'platforms')).filter(x => /^android-\d+$/.test(x))
    .sort((a, b) => Number(a.slice(8)) - Number(b.slice(8)));
  const builds = readdirSync(join(sdk, 'build-tools')).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
  const androidJar = join(sdk, 'platforms', platforms.at(-1), 'android.jar');
  const classes = join(local, 'classes');
  mkdirSync(classes);
  execFileSync('javac', ['--release', '11', '-cp', androidJar, '-d', classes, join(root, 'HtmlMemoryProbe.java')]);
  const probe = join(local, 'probe.jar');
  execFileSync(join(sdk, 'build-tools', builds.at(-1), 'd8'), ['--lib', androidJar,
    '--output', probe, join(classes, 'audit/HtmlMemoryProbe.class')]);
  adb('shell', 'mkdir', remote); remoteCreated = true;
  adb('push', resolve(options.apk), `${remote}/app.apk`);
  adb('push', probe, `${remote}/probe.jar`);
  const output = adb('shell', `CLASSPATH=${remote}/app.apk:${remote}/probe.jar app_process / audit.HtmlMemoryProbe ${className} ${method}`);
  const line = output.split('\n').find(x => x.startsWith('HTML_MEMORY_PROBE '));
  if (!line || !line.includes('results=[1234567890]')) throw new Error(`Parser audit failed: ${output}`);
  console.log(line);
  const growth = Number(line.match(/nativeGrowthKiB=(\d+)/)?.[1]);
  if (!Number.isFinite(growth) || growth > budgetKiB)
    throw new Error(`Native growth ${growth} KiB exceeded ${budgetKiB} KiB`);
  console.log(`PASS: Android parser native growth stayed within ${budgetKiB} KiB`);
} finally {
  if (remoteCreated) adb('shell', 'rm', '-r', remote);
  rmSync(local, { recursive: true, force: true });
}
