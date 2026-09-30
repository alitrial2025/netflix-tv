import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { secureUrl } from './release-lib.mjs';

const raw = process.argv[2];
if (!raw || process.argv.length > 3) throw new Error('Usage: node configure-update-gate.mjs https://your-site.netlify.app');
const url = secureUrl(raw);
if (url.search || (url.pathname !== '/' && url.pathname !== '')) throw new Error('Use the website root URL.');
const siteUrl = url.origin;
const projects = ['D:/Netflixtv', 'D:/Netflix2026/Netflixnewkotlin/Netflixpro-mobile'];
for (const project of projects) {
  // Check the expected project before writing; never infer a directory from user URL text.
  await readFile(resolve(project, 'app/src/main/AndroidManifest.xml'), 'utf8');
  const config = resolve(project, 'app/src/main/assets/update-gate.json');
  await mkdir(dirname(config), { recursive: true });
  await writeFile(config, JSON.stringify({ siteUrl }, null, 2) + '\n');
}
process.stdout.write(`Both apps now use ${siteUrl}. Build them yourself before publishing the release.\n`);
