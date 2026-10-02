import { openAsBlob } from 'node:fs';
import { basename } from 'node:path';

export class ProviderError extends Error {
  constructor(code) {
    super(code === 12 ? 'NitroFlare requires a user CAPTCHA challenge; no automatic retry.' : `NitroFlare rejected the request (code ${Number.isInteger(code) ? code : 'unknown'}).`);
    this.code = code;
  }
}
export function providerURL(value) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password || url.port ||
      !(url.hostname === 'nitroflare.com' || url.hostname.endsWith('.nitroflare.com'))) {
    throw new Error('Expected an HTTPS NitroFlare URL; HTTP endpoints are not upgraded implicitly.');
  }
  return url;
}

export class NitroFlare {
  constructor({ fetchImpl = fetch, now = Date.now, timeoutMs = 30_000, env = process.env } = {}) {
    this.fetch = fetchImpl; this.now = now; this.timeoutMs = timeoutMs; this.env = env;
  }
  async request(url, options = {}, timeout = this.timeoutMs) {
    let response;
    try {
      response = await this.fetch(providerURL(url), { ...options, redirect: 'error', signal: AbortSignal.timeout(timeout) });
    } catch { throw new Error('NitroFlare network request failed or timed out. Credentials and URLs were omitted.'); }
    if (!response.ok) throw new Error(`NitroFlare HTTP ${response.status}; request was not retried.`);
    return response;
  }
  async api(method, params) {
    const url = new URL(`https://nitroflare.com/api/v2/${method}`);
    for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value);
    let data;
    try { data = await (await this.request(url)).json(); }
    catch (error) { if (error instanceof SyntaxError) throw new Error('Invalid provider JSON response.'); throw error; }
    if (data?.type !== 'success') throw new ProviderError(data?.code);
    if (!data.result || typeof data.result !== 'object') throw new Error('Missing provider result.');
    return data.result;
  }
  async upload(path) {
    if (!this.env.NITROFLARE_USER_HASH) throw new Error('Set NITROFLARE_USER_HASH securely before uploading.');
    // Opening the blob fails before any network call when the local file is missing.
    const blob = await openAsBlob(path);
    let server = (await (await this.request('https://nitroflare.com/plugins/fileupload/getServer')).text()).trim();
    if (server.startsWith('"')) {
      try { server = JSON.parse(server); } catch { throw new Error('Invalid upload-server response.'); }
    }
    const url = providerURL(server);
    const form = new FormData();
    form.set('user', this.env.NITROFLARE_USER_HASH);
    form.set('files', blob, basename(path));
    // Uploads are deliberately not retried: a failed response may follow a successful upload.
    let data;
    try { data = await (await this.request(url, { method: 'POST', body: form }, 3_600_000)).json(); }
    catch (error) { if (error instanceof SyntaxError) throw new Error('Invalid upload response; inspect provider account before retrying.'); throw error; }
    return data;
  }
  async info(ids) {
    const files = {};
    for (let i = 0; i < ids.length; i += 100) {
      const result = await this.api('getFileInfo', { files: ids.slice(i, i + 100).join(',') });
      if (!result.files || typeof result.files !== 'object') throw new Error('Missing file-info map.');
      Object.assign(files, result.files);
    }
    return files;
  }
  async begin(id) {
    const result = await this.api('getDownloadLink', { file: id });
    if (result.linkType !== 'free' || !Number.isFinite(result.delay) || result.delay < 0 || typeof result.accessLink !== 'string') {
      throw new Error('Unexpected free-download handshake; provider response needs review.');
    }
    const url = this.access(result.accessLink);
    if (url.searchParams.get('file') !== id) throw new Error('Download handshake returned a different file.');
    return { accessLink: url.href, readyAt: this.now() + result.delay * 1000, recaptchaPublic: result.recaptchaPublic };
  }
  access(value) {
    const url = providerURL(new URL(value, 'https://nitroflare.com/api/v2/'));
    if (url.origin !== 'https://nitroflare.com' || url.pathname !== '/api/v2/getDownloadLink') throw new Error('Unexpected download handshake endpoint.');
    return url;
  }
  async complete(pending, captcha) {
    if (!Number.isFinite(pending.readyAt) || this.now() < pending.readyAt) throw new Error('Provider waiting period has not elapsed.');
    if (!captcha) throw new Error('Set NITROFLARE_CAPTCHA to the user-completed challenge response.');
    const url = this.access(pending.accessLink);
    url.searchParams.set('captcha', captcha);
    const result = await this.api('getDownloadLink', Object.fromEntries(url.searchParams));
    providerURL(result.url);
    return result;
  }
}

export function uploadedIDs(data) {
  // Provider upload response shape is not specified in the supplied docs.
  // Only accept recognizable NitroFlare /view/ URLs or explicit file-ID fields.
  const ids = new Set();
  function walk(value, key = '') {
    if (Array.isArray(value)) return value.forEach(item => walk(item));
    if (value && typeof value === 'object') return Object.entries(value).forEach(([k,v]) => walk(v,k));
    if (typeof value !== 'string') return;
    if (/^(fileId|file_id|id)$/i.test(key) && /^[A-Fa-f0-9]{15,32}$/.test(value)) ids.add(value.toUpperCase());
    if (/^https?:\/\//.test(value)) {
      try {
        const url = new URL(value);
        const match = url.pathname.match(/^\/view\/([A-Fa-f0-9]{15,32})(?:\/|$)/);
        if (match && (url.hostname === 'nitroflare.com' || url.hostname.endsWith('.nitroflare.com'))) ids.add(match[1].toUpperCase());
      } catch { /* Ignore unrelated response strings. */ }
    }
  }
  walk(data);
  return [...ids];
}
