import { readFile, writeFile, stat, mkdir, copyFile, access, rename } from 'node:fs/promises';
import { constants } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { PACKAGES, MAX_APK_BYTES, secureUrl, parseBadging, parseSigners, readGateConfig, makeRelease } from './release-lib.mjs';

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const key = process.argv[i];
  if (['--dry-run', '--allow-legacy', '--replace-debug-baseline'].includes(key)) args[key] = true;
  else if (['--channel', '--apk', '--site', '--apk-url', '--notes-file', '--sdk', '--java'].includes(key)) {
    if (!process.argv[i + 1] || process.argv[i + 1].startsWith('--')) throw new Error(`Missing value for ${key}`);
    args[key] = process.argv[++i];
  } else throw new Error(`Unknown option ${key}`);
}

async function exists(path) { try { await access(path); return true; } catch { return false; } }
function run(binary, params, env = process.env) {
  const result = spawnSync(binary, params, { encoding: 'utf8', shell: false, windowsHide: true, timeout: 60000, maxBuffer: 2 * 1024 * 1024, env });
  if (result.error || result.status !== 0) throw new Error(`APK inspection failed: ${result.error?.message || result.stderr?.trim() || result.status}`);
  return result.stdout;
}

async function main() {
  const channel = args['--channel'];
  if (!PACKAGES[channel] || !args['--apk']) throw new Error('Usage: node publish-update.mjs --channel tv|mobile --apk "path/to/release.apk" [--apk-url https://…] [--notes-file notes.txt] [--dry-run]');
  const site = resolve(args['--site'] || join(dirname(fileURLToPath(import.meta.url)), '../../marketing-site'));
  const apkPath = resolve(args['--apk']);
  const info = await stat(apkPath);
  if (!info.isFile() || info.size < 1 || info.size > MAX_APK_BYTES) throw new Error('Invalid APK file or size.');
  const sdk = args['--sdk'] || process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME || join(process.env.LOCALAPPDATA || '', 'Android/Sdk');
  const { readdir } = await import('node:fs/promises');
  const versions = (await readdir(join(sdk, 'build-tools'))).filter(v => /^\d+\.\d+\.\d+$/.test(v))
    .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  if (!versions.length) throw new Error('Android SDK build-tools were not found. Pass --sdk.');
  const tools = join(sdk, 'build-tools', versions[0]);
  const metadata = parseBadging(run(join(tools, process.platform === 'win32' ? 'aapt2.exe' : 'aapt2'), ['dump', 'badging', apkPath]), channel);
  const configuredJava = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : null;
  const java = args['--java'] || (configuredJava && await exists(configuredJava) ? configuredJava : 'java');
  const signers = parseSigners(run(java, ['-jar', join(tools, 'lib/apksigner.jar'), 'verify', '--print-certs', apkPath]));
  const apk = await readFile(apkPath);
  const hash = createHash('sha256').update(apk).digest('hex');
  const config = readGateConfig(apk);
  const gateEnabled = typeof config?.siteUrl === 'string' && !!config.siteUrl.trim();
  if (gateEnabled) {
    const siteRoot = secureUrl(config.siteUrl);
    if (siteRoot.search || siteRoot.pathname !== '/') throw new Error('Update Gate config must contain the website root URL.');
  }
  if (!gateEnabled && !args['--allow-legacy']) throw new Error('This APK has no configured Update Gate. Configure the URL and build the updated sources first. Use --allow-legacy only to publish an older app explicitly.');
  const filename = `${metadata.versionCode}-${hash.slice(0, 16)}.apk`;
  const localUrl = `/downloads/${channel}/${filename}`;
  const apkUrl = args['--apk-url'] ? secureUrl(args['--apk-url']).href : localUrl;
  const manifestPath = join(site, 'updates', `${channel}.json`);
  const previous = await exists(manifestPath) ? JSON.parse(await readFile(manifestPath, 'utf8')) : null;
  const notes = args['--notes-file'] ? await readFile(resolve(args['--notes-file']), 'utf8') : '';
  const release = makeRelease({ channel, metadata, bytes: info.size, sha256: hash, signers, apkUrl, notes, previous, gateEnabled,
    replaceDebugBaseline: args['--replace-debug-baseline'] === true });
  if (release.requiresFreshInstallFromDebug) {
    process.stderr.write('Replacing the debug baseline with a production release. Debug installations need a fresh install; production signing-key checks remain enforced.\n');
  }
  if (!args['--apk-url'] && info.size > 10 * 1024 * 1024) {
    process.stderr.write('Netlify warns that files over 10 MB may fail deployment. You can use --apk-url with another HTTPS file host.\n');
  }
  if (!gateEnabled) process.stderr.write('Legacy APK: it does not contain Update Gate or the latest source changes.\n');
  if (args['--dry-run']) {
    process.stdout.write(JSON.stringify({ dryRun: true, site, configuredSite: config?.siteUrl || null, release }, null, 2) + '\n');
    return;
  }
  if (!args['--apk-url']) {
    const destination = join(site, 'downloads', channel, filename);
    await mkdir(dirname(destination), { recursive: true });
    if (await exists(destination)) {
      if (createHash('sha256').update(await readFile(destination)).digest('hex') !== hash) throw new Error('An immutable release filename already contains different bytes.');
    } else {
      await copyFile(apkPath, destination, constants.COPYFILE_EXCL);
    }
    if (createHash('sha256').update(await readFile(destination)).digest('hex') !== hash) throw new Error('Copied APK hash mismatch.');
  }
  await mkdir(dirname(manifestPath), { recursive: true });
  const temporary = `${manifestPath}.${process.pid}.tmp`;
  await writeFile(temporary, JSON.stringify(release, null, 2) + '\n', { flag: 'wx' });
  await rename(temporary, manifestPath);
  process.stdout.write(JSON.stringify({ publishedLocally: true, channel, version: metadata.versionName, versionCode: metadata.versionCode, manifest: manifestPath, apkUrl, configuredSite: config?.siteUrl || null }, null, 2) + '\n');
  process.stdout.write('Deploy the whole marketing-site folder after the APK is available. This command does not build, upload or deploy.\n');
}

main().catch(error => { process.stderr.write(error.message + '\n'); process.exitCode = 1; });
