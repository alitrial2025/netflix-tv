#!/usr/bin/env node
import { mkdir, readFile, writeFile, rename, open, unlink } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { NitroFlare, uploadedIDs } from './client.mjs';

const [command, argument] = process.argv.slice(2);
const dir = resolve(process.env.NITROFLARE_STATE_DIR || '/workspace/cloud-setup/nitroflare-state');
const idPattern = /^[A-Fa-f0-9]{15,32}$/;
async function read(name, fallback) {
  try { return JSON.parse(await readFile(join(dir, name), 'utf8')); }
  catch (e) { if (e.code === 'ENOENT') return fallback; throw new Error('State file is unreadable; it has not been overwritten.'); }
}
async function save(name, value) {
  const tmp = join(dir, `${name}.${process.pid}.tmp`);
  await writeFile(tmp, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
  await rename(tmp, join(dir, name));
}
function id() {
  if (!argument || !idPattern.test(argument)) throw new Error('Provide a valid NitroFlare hexadecimal file ID.');
  return argument.toUpperCase();
}
async function main() {
  if (!['upload','register','status','download-begin','download-complete'].includes(command)) {
    throw new Error('Usage: node cli.mjs upload <local-file> | register <file-id> | status | download-begin <file-id> | download-complete <file-id>');
  }
  await mkdir(dir, { recursive: true, mode: 0o700 });
  let lock;
  try { lock = await open(join(dir, '.lock'), 'wx', 0o600); }
  catch (e) { if (e.code === 'EEXIST') throw new Error('Another operation holds the state lock. If interrupted, verify no process is running before removing .lock.'); throw e; }
  const started = Date.now();
  try {
    const client = new NitroFlare();
    const registry = await read('registry.json', { files: {} });
    let result;
    if (command === 'upload') {
      if (!argument) throw new Error('Provide a local file path.');
      const response = await client.upload(resolve(argument));
      if (response?.type === 'error' || response?.status === 'error') throw new Error('Provider rejected upload; check account before retrying.');
      // Preserve the potentially sensitive response before interpretation to recover IDs safely.
      await save('last-upload-response.json', response);
      const ids = uploadedIDs(response);
      if (!ids.length) throw new Error('Upload response saved privately, but no file ID recognized. Inspect it and use register; do not blindly re-upload.');
      for (const fileId of ids) registry.files[fileId] = { ...registry.files[fileId], registeredAt: new Date().toISOString() };
      await save('registry.json', registry);
      result = { uploadedIDs: ids };
    } else if (command === 'register') {
      const fileId = id();
      registry.files[fileId] ||= { registeredAt: new Date().toISOString() };
      await save('registry.json', registry);
      result = { registered: fileId };
    } else if (command === 'status') {
      const ids = Object.keys(registry.files);
      const files = await client.info(ids);
      const statuses = ids.map(fileId => ({ id: fileId, status: ['online','offline'].includes(files[fileId]?.status) ? files[fileId].status : 'unknown', size: files[fileId]?.size, uploadDate: files[fileId]?.uploadDate }));
      result = { checkedAt: new Date().toISOString(), files: statuses, offline: statuses.filter(f => f.status === 'offline').length, unknown: statuses.filter(f => f.status === 'unknown').length };
      await save('last-status.json', result);
    } else if (command === 'download-begin') {
      const fileId = id();
      const pending = await read('pending.json', {});
      if (pending[fileId]) throw new Error('A handshake already exists for this file; complete it or deliberately remove the expired entry from private pending.json.');
      pending[fileId] = await client.begin(fileId);
      await save('pending.json', pending);
      result = { id: fileId, readyAt: new Date(pending[fileId].readyAt).toISOString(), challengeSiteKey: pending[fileId].recaptchaPublic, note: 'Complete the provider CAPTCHA legitimately; set NITROFLARE_CAPTCHA before download-complete.' };
    } else {
      const fileId = id();
      const pending = await read('pending.json', {});
      if (!pending[fileId]) throw new Error('Begin a download handshake first.');
      const response = await client.complete(pending[fileId], process.env.NITROFLARE_CAPTCHA);
      await save(`download-${fileId}.json`, response);
      delete pending[fileId];
      await save('pending.json', pending);
      result = { id: fileId, linkSavedPrivately: true, note: 'Playback URL was not printed or added to the registry.' };
    }
    const output = { command, durationMs: Date.now() - started, ...result };
    console.log(JSON.stringify(output, null, 2));
    if (command === 'status' && (result.offline || result.unknown)) process.exitCode = 2;
  } finally { await lock.close(); await unlink(join(dir, '.lock')); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
