import test from 'node:test';
import assert from 'node:assert/strict';
import {NETFLIX_SITEMAP,parseNetflixSitemap,netflixMovieMetadata,uniqueTmdbMovie,enrichNetflixCatalog,initialNetflixState} from './netflix-catalog.mjs';
const sitemap = ids => `<?xml version="1.0" encoding="UTF-8"?><sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${ids.map(id => `<sitemap><loc>https://www.netflix.com/sitemap/title/${id}</loc></sitemap>`).join('')}</sitemapindex>`;
const page = (id,title='A New Release',year='2026',extras={}) => `<script type="application/ld+json">${JSON.stringify({'@type':'Movie',url:`https://www.netflix.com/title/${id}`,name:title,dateCreated:`${year}-8-27`,...extras})}</script>`;
const native = {schemaVersion:1,generatedAt:'2026-10-03T00:00:00.000Z',license:'CC0',rows:[['movie','1','nf','80000001','Q1']]};
const options = {apiKey:'fixture-key',minimumSitemapEntries:1,now:100000,concurrency:1};

test('sitemap must be complete, correctly namespaced and contain only published native ID links',() => {
  assert.deepEqual(parseNetflixSitemap(sitemap(['80000001','80000002']),{minimum:1}),['80000001','80000002']);
  for (const xml of [sitemap(['80000001']).replace('</sitemapindex>',''),
    sitemap(['80000001']).replace('</sitemap>',''),sitemap(['80000001']).replace('www.netflix.com','evil.example'),
    sitemap(['80000001']).replace('</sitemapindex>','<garbage/></sitemapindex>'),
    sitemap(['80000001']).replace('<sitemapindex xmlns','<sitemapindex broken xmlns'),
    sitemap(['80000001']).replace('<sitemapindex xmlns','<sitemapindex xmlns="duplicate" xmlns'),
    sitemap(['80000001']).replace('version="1.0"','broken'),
    sitemap(['80000001']).replace('sitemaps.org','evil.example'),
    '<!DOCTYPE sitemapindex [<!ENTITY x SYSTEM "file:///private">]>'+sitemap(['80000001'])]) {
    assert.throws(() => parseNetflixSitemap(xml,{minimum:1}));
  }
});

test('Netflix Movie year comes from its own valid release metadata, never nested trailer dates or TV guesses',() => {
  assert.deepEqual(netflixMovieMetadata(page('80000002'),'80000002'),{title:'A New Release',year:'2026',netflixId:'80000002'});
  assert.equal(netflixMovieMetadata(page('80000002','Film','2026',{dateCreated:undefined,trailer:{uploadDate:'2026-08-27'}}),'80000002'),null);
  assert.equal(netflixMovieMetadata(page('80000002','Film','2026',{'@type':'TVSeries'}),'80000002'),null);
  assert.equal(netflixMovieMetadata(page('80000002','Film','2026',{datePublished:'2025-01-01'}),'80000002'),null);
  assert.equal(netflixMovieMetadata(page('80000002','Film','2026',{dateCreated:'2026-02-30'}),'80000002'),null);
  assert.equal(netflixMovieMetadata(page('80000002'),'80000003'),null);
  assert.equal(netflixMovieMetadata(page('80000002')+page('80000002','Another Film'),'80000002'),null);
  assert.equal(netflixMovieMetadata(page('80000002','Film','2026',{dateCreated:undefined,datePublished:'2026-08-27T00:00:00Z'}),'80000002').year,'2026');
});

test('TMDB joins require a unique typed release-year identity and authoritative matching title',async () => {
  const movie = {title:'Official Regional Title',year:'2026',netflixId:'80000002'};
  const search = {total_pages:1,results:[{id:22,title:'Canonical Title',release_date:'2026-08-27'}]};
  const detail = {id:22,title:'Canonical Title',original_title:'Original Title',release_date:'2026-08-27',alternative_titles:{titles:[{title:'Official Regional Title'}]}};
  assert.deepEqual(await uniqueTmdbMovie(movie,search,async () => detail),['movie','22','nf','80000002','']);
  for (const value of [{...detail,release_date:'1977-06-16'},{...detail,id:23},{...detail,media_type:'tv'},
    {...detail,title:undefined,name:'Official Regional Title'},{...detail,alternative_titles:{titles:[]}}]) {
    assert.equal(await uniqueTmdbMovie(movie,search,async () => value),null);
  }
  const duplicate = {...search,results:[...search.results,{id:23,title:'Canonical Title',release_date:'2026-09-01'}]};
  assert.equal(await uniqueTmdbMovie(movie,duplicate,async id => ({...detail,id})),null);
  assert.equal(await uniqueTmdbMovie(movie,{...search,total_pages:2},async () => detail),null);
  assert.equal(await uniqueTmdbMovie(movie,{...search,results:[{...search.results[0],media_type:'tv'}]},async () => detail),null);
  const unicode = {title:'电影',year:'2026',netflixId:'80000002'};
  assert.equal(await uniqueTmdbMovie(unicode,search,async () => ({...detail,title:'电视剧',original_title:'电视剧',alternative_titles:{titles:[]}})),null);
});

test('a new release absent from native mappings is discovered once, with incremental title requests afterward',async () => {
  let ids = ['80000001','80000002']; const requested = [];
  const transport = async url => {
    requested.push(url); const parsed = new URL(url);
    if (url === NETFLIX_SITEMAP) return sitemap(ids);
    if (parsed.hostname === 'www.netflix.com') return page(parsed.pathname.split('/').at(-1));
    if (parsed.pathname === '/3/search/movie') return JSON.stringify({total_pages:1,results:[{id:22,title:'A New Release',release_date:'2026-08-27'}]});
    return JSON.stringify({id:22,title:'A New Release',release_date:'2026-08-27',alternative_titles:{titles:[]}});
  };
  const first = await enrichNetflixCatalog(native,initialNetflixState(),{...options,transport});
  assert.equal(first.stats.added,1);
  assert.deepEqual(first.native.rows.at(-1),['movie','22','nf','80000002','']);
  assert.equal(requested.some(url => url.includes('/title/80000001')),false);
  requested.length = 0;
  const second = await enrichNetflixCatalog(native,first.state,{...options,transport,now:200000});
  assert.deepEqual(requested,[NETFLIX_SITEMAP]);
  assert.deepEqual(second.native.rows,first.native.rows);
  ids = [...ids,'80000003']; requested.length = 0;
  const third = await enrichNetflixCatalog(native,second.state,{...options,transport,now:300000});
  assert.equal(third.stats.attempted,1);
  assert.equal(requested.some(url => url.includes('/title/80000002')),false);
  assert.equal(requested.some(url => url.includes('/title/80000003')),true);
});

test('partial, malformed or severely shrinking sitemap retains the last complete state and accepted rows',async () => {
  const previous = {...initialNetflixState(),seenIds:['80000001','80000002'],rows:[['movie','22','nf','80000002','']],lastCompleteIndexAt:'2026-10-03T00:00:00.000Z'};
  for (const body of [sitemap(['80000001']).replace('</sitemapindex>',''),sitemap(['80000001'])]) {
    const result = await enrichNetflixCatalog(native,previous,{...options,transport:async () => body});
    assert.equal(result.stats.status,'sitemap_retained');
    assert.deepEqual(result.state,previous);
    assert.deepEqual(result.native.rows.at(-1),previous.rows[0]);
  }
});

test('new title lookups and retries are bounded, persisted and delayed rather than rescanning the whole catalog',async () => {
  const ids = ['80000001','80000002','80000003','80000004','80000005']; let calls = 0;
  const transport = async url => { calls++; if (url === NETFLIX_SITEMAP) return sitemap(ids); throw Error('Temporary network failure'); };
  const first = await enrichNetflixCatalog(native,null,{...options,transport,maximumTitles:2,maximumRequests:3});
  assert.equal(first.stats.attempted,2); assert.equal(calls,3);
  assert.equal(Object.keys(first.state.pending).length,4);
  assert.equal(first.state.pending['80000005'].attempts,1);
  assert.equal(first.state.pending['80000005'].retryAt,options.now+86400000);
  calls = 0;
  const second = await enrichNetflixCatalog(native,first.state,{...options,transport,maximumTitles:4,now:options.now+1});
  assert.equal(second.stats.attempted,2); assert.equal(calls,3);
  assert.equal(second.state.pending['80000005'].attempts,1);
  calls = 0;
  const third = await enrichNetflixCatalog(native,second.state,{...options,transport,maximumTitles:4,now:options.now+2});
  assert.equal(third.stats.attempted,0); assert.equal(calls,1);
});

test('shared request budget also bounds concurrent workers and keeps previously accepted joins on failures',async () => {
  const previous = {...initialNetflixState(),rows:[['movie','22','nf','80000002','']]};
  let calls = 0;
  const transport = async url => {
    calls++;
    if (url === NETFLIX_SITEMAP) return sitemap(['80000001','80000002','80000003','80000004','80000005']);
    return page(new URL(url).pathname.split('/').at(-1));
  };
  const result = await enrichNetflixCatalog(native,previous,{...options,transport,concurrency:3,maximumTitles:3,maximumRequests:3});
  assert.equal(calls,3); assert.equal(result.stats.requests,3);
  assert.deepEqual(result.state.rows,previous.rows);
});
