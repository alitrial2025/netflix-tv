#!/usr/bin/env node
/**
 * Deep analysis of the saved 119KB series page
 * It has 187 poster IDs + OTT tabs — content might be tagged per-OTT
 */
import { readFile } from 'fs/promises';

const html = await readFile('tools/cdn-audit/pagemobile_series.html', 'utf8');
console.log('=== DEEP ANALYSIS OF SAVED SERIES PAGE (' + html.length + 'B) ===\n');

// 1. Find the poster card structure
// Look for common card patterns
const cardPatterns = [
  /class="[^"]*poster[^"]*"[\s\S]{0,500}?imgcdn[^"]*\/(\d{7,9})/g,
  /class="[^"]*card[^"]*"[\s\S]{0,500}?imgcdn[^"]*\/(\d{7,9})/g,
  /class="[^"]*item[^"]*"[\s\S]{0,500}?imgcdn[^"]*\/(\d{7,9})/g,
  /data-post="(\d+)"/g,
  /data-id="(\d+)"/g,
];

for (const p of cardPatterns) {
  const ids = new Set();
  let m;
  while ((m = p.exec(html)) !== null) ids.add(m[1]);
  if (ids.size > 0) console.log(p.source.substring(0,50) + ': ' + ids.size + ' matches');
}

// 2. Find all poster images with their context
console.log('\n--- Poster context analysis ---');
const posterContextRe = /([\s\S]{0,300})imgcdn[^"']*\/(\d{7,9})\.([\s\S]{0,300})/g;
let m;
const posterContexts = [];
while ((m = posterContextRe.exec(html)) !== null) {
  const before = m[1];
  const id = m[2];
  const after = m[3];
  
  // Look for OTT indicator near this poster
  const ottBefore = before.match(/data-ott="([^"]+)"/);
  const ottAfter = after.match(/data-ott="([^"]+)"/);
  
  // Look for data-post (show ID used in post.php)
  const postBefore = before.match(/data-post="([^"]+)"/);
  const postAfter = after.match(/data-post="([^"]+)"/);
  
  // Look for section/rail heading
  const sectionBefore = before.match(/<h[1-6][^>]*>([^<]+)/);
  
  posterContexts.push({
    id,
    ott: ottBefore ? ottBefore[1] : (ottAfter ? ottAfter[1] : null),
    post: postBefore ? postBefore[1] : (postAfter ? postAfter[1] : null),
    section: sectionBefore ? sectionBefore[1].trim() : null,
  });
}
console.log('Poster contexts:', posterContexts.length);

// 3. Check if posters have OTT tags
const withOtt = posterContexts.filter(p => p.ott);
const withPost = posterContexts.filter(p => p.post);
console.log('With OTT tag:', withOtt.length);
console.log('With data-post:', withPost.length);

if (withOtt.length > 0) {
  const byOtt = {};
  for (const p of withOtt) {
    if (!byOtt[p.ott]) byOtt[p.ott] = [];
    byOtt[p.ott].push(p.id);
  }
  console.log('\nOTT distribution:');
  for (const [ott, ids] of Object.entries(byOtt)) {
    console.log('  ' + ott + ': ' + ids.length + ' posters | sample: ' + ids.slice(0,5).join(', '));
  }
}

// 4. Look for section/rail structure  
console.log('\n--- Section/Rail structure ---');
const sectionRe = /<div[^>]*class="[^"]*(?:section|rail|row|genre-row|slider-row)[^"]*"[^>]*>([\s\S]*?)(?=<div[^>]*class="[^"]*(?:section|rail|row|genre-row|slider-row)|\z)/g;
const sections = [];
while ((m = sectionRe.exec(html)) !== null) {
  const content = m[0];
  const headingM = content.match(/<[^>]*class="[^"]*(?:genre-head|section-title|rail-title|row-title)[^"]*"[^>]*>([^<]+)/);
  const postersInSection = (content.match(/imgcdn/g) || []).length;
  if (headingM || postersInSection > 0) {
    sections.push({ heading: headingM ? headingM[1].trim() : '(no heading)', posters: postersInSection });
  }
}
console.log('Sections:', sections.length);
for (const s of sections) console.log('  "' + s.heading + '" | ' + s.posters + ' posters');

// 5. Find data-post IDs and what they correspond to
console.log('\n--- data-post analysis ---');
const dataPostRe = /data-post="([^"]+)"[\s\S]{0,200}?(?:class="[^"]*title[^"]*"[^>]*>([^<]+)|alt="([^"]+)"|title="([^"]+)")/g;
while ((m = dataPostRe.exec(html)) !== null) {
  const postId = m[1];
  const title = m[2] || m[3] || m[4] || '?';
  console.log('  data-post=' + postId + ' -> "' + title.substring(0,40) + '"');
}

// 6. Also check data-hash (might be the show hash/id)
const dataHashRe = /data-hash="([^"]+)"/g;
const hashes = [];
while ((m = dataHashRe.exec(html)) !== null) hashes.push(m[1]);
console.log('\ndata-hash values:', hashes.length);
if (hashes.length > 0) console.log('  Sample:', hashes.slice(0,3).join(' | '));

// 7. Check the modal/dialog structure for episodes
console.log('\n--- Modal/Dialog for episodes ---');
const modalRe = /id="exampleModalScrollable"[\s\S]{0,1000}/;
const modalM = html.match(modalRe);
if (modalM) {
  console.log('Modal found:');
  // Extract data attributes
  const dataAttrs = modalM[0].match(/data-[\w]+="[^"]*"/g) || [];
  for (const d of dataAttrs) console.log('  ' + d);
}

// 8. Look for the click handler that opens show details
console.log('\n--- Show click handler ---');
const clickRe = /\.(?:poster|card|item)[^{]*\{[\s\S]*?post\.php[\s\S]*?\}/;
const clickM = html.match(clickRe);
if (clickM) console.log('Click handler:', clickM[0].substring(0,300));

// Also from nf-custom.js
const js = await readFile('tools/cdn-audit/nf-custom.js', 'utf8');
// Find the post.php call
const postPhpRe = /post\.php[\s\S]{0,500}/g;
const postPhpCalls = [];
while ((m = postPhpRe.exec(js)) !== null) postPhpCalls.push(m[0]);
console.log('\npost.php calls in nf-custom.js:', postPhpCalls.length);
for (const c of postPhpCalls) console.log('  ' + c.substring(0,200).replace(/\n/g,' '));
