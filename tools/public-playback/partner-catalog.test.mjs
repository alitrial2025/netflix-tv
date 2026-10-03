import test from 'node:test';
import assert from 'node:assert/strict';
import {partnerRow,publishedLinks,browsePath,matchesPartnerPage} from './partner-catalog.mjs';
test('published browse pages supply migrated identities missing from sitemaps',()=>{
 const path='/tv-shows/house-of-the-dragon/HOTSTAR_DTH_TVSHOW_1971002877';
 const html=`<a href="${path}">House of the Dragon</a><script>{"link":"https:\\/\\/www.airtelxstream.in${path.replaceAll('/','\\/')}"}</script>`;
 assert.ok(publishedLinks(html).map(partnerRow).some(r=>r?.[2]==='1971002877'));
 assert.deepEqual(partnerRow(path),['tv','houseofthedragon','1971002877',path]);
 assert.equal(partnerRow('https://evil.example'+path),null);
 assert.equal(partnerRow(path.replace('TVSHOW','MOVIE')),null);
 assert.equal(browsePath('/tv-shows/english-tv-shows'),'/tv-shows/english-tv-shows');
 assert.equal(browsePath('https://evil.example/tv-shows'),null);
 assert.equal(browsePath('/tv-shows?next=https://evil.example'),null);
});
test('series latest-season years do not reject the matching show; movie remakes still require year',()=>{
 const page='<p id="banner-content-release-year">2026</p><script>{"@type":"VideoObject","name":"House Of The Dragon"}</script>';
 assert.equal(matchesPartnerPage(page,'House of the Dragon',2022,'tv'),true);
 assert.equal(matchesPartnerPage(page,'Other show',2022,'tv'),false);
 assert.equal(matchesPartnerPage(page,'House of the Dragon',2022,'movie'),false);
});
test('accented published paths retain their opaque id and canonical URL encoding',()=>{
 const row=partnerRow('/movies/tár/HOTSTAR_DTH_MOVIE_1971309279');
 assert.deepEqual(row,['movie','tar','1971309279','/movies/t%C3%A1r/HOTSTAR_DTH_MOVIE_1971309279']);
 assert.deepEqual(partnerRow(row[3]),row);
 assert.equal(partnerRow('/movies/unsafe%2Fpath/HOTSTAR_DTH_MOVIE_1971309279'),null);
});
