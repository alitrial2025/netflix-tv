import {netflixMovieMetadata} from './netflix-catalog.mjs';
import {netflixSeasons} from './public-catalog-flow.mjs';
import test from 'node:test';import assert from 'node:assert/strict';
import {netflixPublicMetadata} from './netflix-public-metadata.mjs';
function page({id='82723106',title='Demon Slayer: Kimetsu no Yaiba Infinity Castle I',kind='Movie',year=2025,seasons=[],recommendations=[],queryId=id}={}) {
 const video={videoId:Number(id),__typename:kind,title,latestYear:year,similarVideos:recommendations};
 if(kind==='Show') video.seasons={edges:seasons.map(([number,sid,label])=>({node:{__typename:'Season',showMemberType:'SEASON',numberLabelV2:`Season ${number}`,videoId:Number(sid),title:label}}))};
 const query={query:'clcsHookV2',variables:{parameters:[{name:'titleId',value:{stringValue:queryId}}]}};
 const metadata={videoId:Number(id),topLevelVideoId:Number(id),type:kind,nextPage:`https://www.netflix.com/title/${id}`};
 const batch={clcsHookV2:{result:{screen:{componentTree:{nodes:[{data:{videos:[video]}}]}}}}};
 const root={models:{nmTitleGQL:{data:{metaData:metadata}},graphqlClcs:{data:{[JSON.stringify(query)]:batch}}}};
 return `<script>window.netflix = window.netflix || {};\nnetflix.reactContext = ${JSON.stringify(root)};</script>`;
}
test('current Netflix movie model verifies native identity, exact title/type and movie year',()=>{
 assert.deepEqual(netflixPublicMetadata(page(),'82723106'),{id:'82723106',title:'Demon Slayer: Kimetsu no Yaiba Infinity Castle I',type:'movie',year:'2025',seasons:{}});
 assert.equal(netflixPublicMetadata(page(),'81091393'),null);
 assert.equal(netflixPublicMetadata(page().replace('https://www.netflix.com/title/82723106','https://evil.example/title/82723106')),null);
 assert.equal(netflixPublicMetadata(page({year:null})),null);
 assert.equal(netflixPublicMetadata(page().replace('"topLevelVideoId":82723106','"topLevelVideoId":81091393')),null);
});
test('named anime seasons come from explicit numbers and remain independent of array order',()=>{
 const html=page({id:'81091393',kind:'Show',title:'Demon Slayer: Kimetsu no Yaiba',year:2024,seasons:[[5,'81743731','Hashira Training Arc'],[1,'81091394','Unwavering Resolve Arc'],[2,'81513781','Mugen Train Arc']]});
 assert.deepEqual(netflixPublicMetadata(html,'81091393').seasons,{1:'81091394',2:'81513781',5:'81743731'});
 assert.equal(netflixPublicMetadata(html).year,null);
 assert.equal(netflixPublicMetadata(html.replace('Season 5','Arc five')),null);
 assert.equal(netflixPublicMetadata(html.replace('Season 5','Season 1')),null);
 assert.equal(netflixPublicMetadata(html.replace('81743731','81091394')),null);
});
test('recommendations and duplicate or contradictory page models cannot supply requested metadata',()=>{
 const recommendation={videoId:81091393,__typename:'Show',title:'Demon Slayer: Kimetsu no Yaiba'};
 assert.equal(netflixPublicMetadata(page({recommendations:[recommendation]}),'81091393'),null);
 assert.equal(netflixPublicMetadata(page()+page()),null);
 assert.equal(netflixPublicMetadata(page({queryId:'81091393'})),null);
 assert.equal(netflixPublicMetadata(page().replace('"__typename":"Movie"','"__typename":"Show"')),null);
});
test('published hex-escaped text is decoded as JSON without executing JavaScript',()=>{
 const html=page().replaceAll('Demon Slayer','Demon\\x20Slayer');
 assert.equal(netflixPublicMetadata(html).title,'Demon Slayer: Kimetsu no Yaiba Infinity Castle I');
 assert.equal(netflixPublicMetadata(html.replace(';</script>',';globalThis.sideEffect=true;</script>')),null);
 assert.equal(globalThis.sideEffect,undefined);
});

test('the scheduled catalog importer and playback audit use the current page contract',()=>{
 assert.deepEqual(netflixMovieMetadata(page(),'82723106'),{title:'Demon Slayer: Kimetsu no Yaiba Infinity Castle I',year:'2025',netflixId:'82723106'});
 const html=page({id:'81091393',kind:'Show',title:'Demon Slayer: Kimetsu no Yaiba',seasons:[[5,'81743731','Hashira Training Arc'],[1,'81091394','Unwavering Resolve Arc']]});
 assert.deepEqual(netflixSeasons(html,'Demon Slayer: Kimetsu no Yaiba'),new Map([[1,'81091394'],[5,'81743731']]));
 assert.equal(netflixMovieMetadata(html,'81091393'),null);
});
