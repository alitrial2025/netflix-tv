#!/usr/bin/env node
import { readFile } from 'fs/promises';

console.log('=== OTT CONTENT MAPPING FROM CATALOG PAGES ===\n');

for (const file of ['tools/cdn-audit/pagemobile_series.html', 'tools/cdn-audit/pagemobile_movies.html']) {
  const html = await readFile(file, 'utf8');
  const label = file.includes('series') ? 'SERIES' : 'MOVIES';
  console.log('--- ' + label + ' (' + html.length + 'B) ---');
  
  // Find elements with data-ott
  const ottPattern = /data-ott="([^"]+)"/g;
  const ottValues = new Map();
  let m;
  while ((m = ottPattern.exec(html)) !== null) {
    const ott = m[1];
    if (!ottValues.has(ott)) ottValues.set(ott, 0);
    ottValues.set(ott, ottValues.get(ott) + 1);
  }
  console.log('OTT values:', ottValues.size);
  for (const [ott, count] of [...ottValues.entries()].sort((a,b) => b[1]-a[1])) {
    console.log('  ' + ott + ': ' + count + ' items');
  }
  
  // Find content blocks with poster + ott
  // Look for patterns like: <div ... data-ott="xxx" ... > ... imgcdn.../{id}. ...
  // Or: poster/{size}/{id}.jpg near data-ott
  const blockPattern = /data-ott="([^"]*)"[\s\S]{0,500}?imgcdn[^"']*\/(\d{7,9})\./g;
  const ottContent = new Map();
  while ((m = blockPattern.exec(html)) !== null) {
    const ott = m[1];
    const id = m[2];
    if (!ottContent.has(ott)) ottContent.set(ott, []);
    ottContent.get(ott).push(id);
  }
  
  // Also try reverse: poster first, then ott
  const blockPattern2 = /imgcdn[^"']*\/(\d{7,9})\.[\s\S]{0,500}?data-ott="([^"]*)"/g;
  while ((m = blockPattern2.exec(html)) !== null) {
    const id = m[1];
    const ott = m[2];
    if (!ottContent.has(ott)) ottContent.set(ott, []);
    if (!ottContent.get(ott).includes(id)) ottContent.get(ott).push(id);
  }
  
  console.log('\nOTT -> Content IDs:');
  for (const [ott, ids] of [...ottContent.entries()].sort((a,b) => b[1].length - a[1].length)) {
    const unique = [...new Set(ids)];
    console.log('  [' + ott + ']: ' + unique.length + ' items | ' + unique.slice(0,5).join(', '));
  }
  console.log('');
}

// Now specifically look for non-Netflix show IDs that we can test
console.log('--- Finding non-Netflix items to test ---');
const seriesHtml = await readFile('tools/cdn-audit/pagemobile_series.html', 'utf8');

// Find all poster cards/items — they're likely in a consistent structure
// Look for any pattern that has an ID near a title and OTT tag
const itemPattern = /data-ott="([^"]*)"[\s\S]{0,300}?(?:title|alt)="([^"]*)"[\s\S]{0,200}?imgcdn[^"']*\/(\d{7,9})\./g;
let m2;
const items = [];
while ((m2 = itemPattern.exec(seriesHtml)) !== null) {
  items.push({ott: m2[1], title: m2[2], id: m2[3]});
}

const itemPattern2 = /imgcdn[^"']*\/(\d{7,9})\.[\s\S]{0,200}?(?:title|alt)="([^"]*)"[\s\S]{0,300}?data-ott="([^"]*)"/g;
while ((m2 = itemPattern2.exec(seriesHtml)) !== null) {
  items.push({ott: m2[3], title: m2[2], id: m2[1]});
}

console.log('Items with title+ott+id:', items.length);
for (const item of items.slice(0,20)) {
  console.log('  [' + item.ott + '] id=' + item.id + ' "' + item.title.substring(0,40) + '"');
}
