/* A self-contained browser illustration of the Kotlin mobile screens; no account or API requests. */
const root = document.querySelector('#phoneContent');
const preview = document.querySelector('#phonePreview');
const buttons = [...document.querySelectorAll('[data-phone-screen]')];
const caption = document.querySelector('#phoneCaption');
const icon = name => `<svg class="app-icon" aria-hidden="true"><use href="#app-icon-${name}"></use></svg>`;
const art = name => `assets/mobile/${name}.webp`;
const avatar = '<span class="phone-avatar" aria-hidden="true"></span>';
const jump = (screen, label, content) => `<button type="button" data-phone-open="${screen}" aria-label="${label}">${content}</button>`;
const descriptions = {
  home: 'Home — a cinematic hero, familiar categories and your next watch within reach.',
  details: 'Details — title artwork, a poster-coloured gradient, resume progress and episodes together.',
  downloads: 'Downloads — your saved episodes together, with Smart Downloads just one tap away.',
  smart: 'Smart Downloads — independent controls for the next episode and Downloads for You, with storage per profile.',
  search: 'Search — a clear search field, title filters and an easy route to your next story.',
  profile: 'Edit profile — your name, viewing preferences and autoplay controls in one place.'
};
const labels = {home:'Home',details:'Details',downloads:'Downloads',smart:'Smart Downloads',search:'Search',profile:'Edit profile'};
let current = 'home';
const state = {next:true,forYou:false,autoplay:true,previews:true,alex:3,kids:1,name:'Alex',listed:false};
function header(title, back = 'home') {
  return `<div class="phone-header">${jump(back,'Back to '+labels[back],icon('back'))}<h3>${title}</h3>${jump('search','Open Search',icon('search'))}${jump('profile','Open Edit profile',avatar)}</div>`;
}
function homeIcon() {
  return '<svg class="app-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="m3 10 9-7 9 7v10H3V10Z"/></svg>';
}
function bottom() {
  return `<div class="phone-bottom">${jump('home','Go to Home',homeIcon()+'Home')}${jump('search','Open Search',icon('search')+'Search')}${jump('profile','Open My NetflixPro',avatar+'My NetflixPro')}</div>`;
}
function toggle(key,label) {
  return `<button type="button" class="phone-toggle" role="switch" aria-checked="${state[key]}" aria-label="${label}" data-phone-toggle="${key}"></button>`;
}
function home() {
  return `<div class="phone-view phone-home"><div class="phone-header"><img class="phone-mark" src="assets/netflix-n.svg" alt="NetflixPro"/><h3>Home</h3>${jump('downloads','Open Downloads',downloadIcon())}${jump('profile','Open Edit profile',avatar)}</div>
    <div class="phone-categories">${['Series','Films','Games','New & Hot','Categories'].map(label=>jump('search','Browse '+label,label)).join('')}</div>
    <div class="phone-hero"><img class="phone-hero__poster" src="${art('squid-game')}" alt="Squid Game artwork"/><div class="phone-hero__copy"><img class="phone-title-logo" src="${art('squid-game-logo')}" alt="Squid Game"/><p class="phone-hero__tags">Suspenseful · Thriller · Game of Death · Korean</p><div class="phone-actions">${jump('details','Explore Squid Game',icon('play')+'Play')}<button type="button" data-phone-list aria-pressed="${state.listed}">${icon(state.listed?'check':'plus')}My List</button></div></div></div>
    <h4>Continue watching for ${escapeText(state.name)}</h4><div class="phone-poster-row">${['squid-game','umthetho','squid-game'].map((name,index)=>jump('details','Explore '+(name==='umthetho'?'Umthetho':'Squid Game'),`<img src="${art(name)}" alt="" loading="lazy"/><span></span>`)).join('')}</div><h4>Only on NetflixPro</h4><div class="phone-poster-row">${['umthetho','squid-game'].map(name=>jump('details','Explore this title',`<img src="${art(name)}" alt="" loading="lazy"/>`)).join('')}</div></div>${bottom()}`;
}
function downloadIcon() {
  return '<svg class="app-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M12 3v13m-5-5 5 5 5-5M4 20h16"/></svg>';
}
function details() {
  return `<div class="phone-view phone-details"><div class="phone-details__art"><img src="${art('squid-game')}" alt="Squid Game artwork"/>${jump('home','Close Details','×').replace('<button ','<button class="phone-close" ')}</div><div class="phone-details__body"><img class="phone-wordmark" src="assets/netflix-logo.svg" alt="NetflixPro"/><img class="phone-title-logo" src="${art('squid-game-logo')}" alt="Squid Game"/><div class="phone-meta">2021 <span>16+</span> 3 Seasons</div><button class="phone-primary" type="button" data-phone-feedback="Playback is available in the Android app.">${icon('play')}Resume</button>${jump('downloads','Open Downloads',downloadIcon()+'Download').replace('<button ','<button class="phone-secondary" ')}<h4>The Invitation</h4><div class="phone-progress"><span></span>31m remaining</div><p class="phone-summary">Hundreds of cash-strapped players accept a strange invitation to compete in children's games. Inside, a tempting prize awaits — with deadly high stakes.</p><p class="phone-muted">Cast: Lee Jung-jae, Park Hae-soo, Wi Ha-jun<br/>Creator: Hwang Dong-hyuk</p><div class="phone-detail-actions"><button type="button" data-phone-list aria-pressed="${state.listed}">${icon(state.listed?'check':'plus')}My List</button><button type="button" data-phone-feedback="Ratings are saved to your profile in the Android app.">${icon('thumb')}Rate</button><button type="button" data-phone-feedback="Share Squid Game from the Android app.">${shareIcon()}Share</button></div><div class="phone-detail-tabs"><span>Episodes</span><span>More Like This</span><span>Trailers & More</span></div><div class="phone-season">Season 1 ${icon('down')}</div><div class="phone-episode"><img src="${art('squid-game')}" alt=""/><p>1. The Invitation<small>45m</small></p>${jump('downloads','Open episode Downloads',downloadIcon())}</div><p class="phone-muted">A mysterious invitation brings the players together.</p></div></div>`;
}
function shareIcon() {
  return '<svg class="app-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="m21 3-7 19-4-9-9-4 20-6ZM10 13l11-10"/></svg>';
}
function downloads() {
  return `<div class="phone-view">${header('Downloads')}<button type="button" class="phone-smart-link" data-phone-open="smart">${icon('gear')}Smart Downloads ${icon('right')}</button><div class="phone-profile-label">${avatar}${escapeText(state.name)}</div><button type="button" class="phone-download" data-phone-open="details"><img src="${art('squid-game')}" alt="Squid Game"/><p>Squid Game<small>16+ · 1 Episode · 282 MB</small></p>${icon('right')}</button><h4>Introducing Downloads for You</h4><p class="phone-muted">Download a selection of movies and shows so you always have something to watch on your phone.</p><div class="phone-poster-fan"><img src="${art('umthetho')}" alt="Umthetho"/><img src="${art('squid-game')}" alt="Squid Game"/><img src="${art('umthetho')}" alt="Umthetho"/></div><button class="phone-blue" type="button" data-phone-open="smart">SET UP</button></div>`;
}
function allocation(name,key,color) {
  return `<div class="phone-alloc"><span class="phone-avatar" aria-hidden="true" style="background:${color}"></span><span>${name}</span><button type="button" data-phone-allocation="${key}" data-step="-0.5" aria-label="Decrease storage for ${name}">−</button><b>${state[key].toFixed(1)}<small>GB</small></b><button type="button" data-phone-allocation="${key}" data-step="0.5" aria-label="Increase storage for ${name}">+</button></div>`;
}
function smart() {
  return `<div class="phone-view">${header('Smart Downloads','downloads')}<h4>Download Next Episode</h4><div class="phone-toggle-row">${icon('play')}<p>When you finish a downloaded episode, get the next one and remove the episode you've watched. Downloads use Wi-Fi.</p>${toggle('next','Download Next Episode')}</div><h4>Downloads for You</h4><div class="phone-toggle-row">${downloadIcon()}<p>Download a selection of movies and shows so you always have something to watch. Downloads use Wi-Fi.</p>${toggle('forYou','Downloads for You')}</div><h4>Allocate storage</h4><p class="phone-muted">Choose how much space each profile can use. Your manual downloads are separate.</p>${allocation(escapeText(state.name),'alex','#df0010')}${allocation('Kids','kids','#299274')}<div class="phone-storage"></div><p class="phone-muted" style="margin-top:12px">0.0 GB · Downloads for You</p></div>`;
}
function search() {
  return `<div class="phone-view">${header('Search')}<div class="phone-search-input">${icon('search')}Search movies, shows and genres</div><div class="phone-search-filter"><span>All</span><span>Series</span><span>Films</span></div><h4>Top Searches</h4><div class="phone-search-list">${[['squid-game','Squid Game'],['umthetho','Umthetho'],['squid-game','Squid Game']].map(([name,title])=>jump('details','Explore '+title,`<img src="${art(name)}" alt=""/><b>${title}</b>${icon('play')}`)).join('')}</div></div>${bottom()}`;
}
function profile() {
  return `<div class="phone-view">${header('Edit profile')}<span class="phone-avatar phone-edit-avatar" aria-hidden="true"></span><label class="phone-muted" for="phoneProfileName">Name</label><input class="phone-name-field" id="phoneProfileName" value="${escapeText(state.name)}" maxlength="30"/><div class="phone-setting"><p>Display language<small>English</small></p>${icon('right')}</div><div class="phone-setting"><p>Audio & subtitles<small>Original · Subtitles off</small></p>${icon('right')}</div><div class="phone-setting"><p>Game handle<small>Choose your game handle</small></p>${icon('right')}</div><div class="phone-setting"><p>Maturity rating<small>All maturity ratings</small></p>${icon('right')}</div><div class="phone-setting"><p>Profile lock<small>No PIN set</small></p>${icon('lock')}</div><div class="phone-setting"><p>Autoplay next episode<small>Play the next episode automatically.</small></p>${toggle('autoplay','Autoplay next episode')}</div><div class="phone-setting"><p>Autoplay previews<small>Play previews while browsing.</small></p>${toggle('previews','Autoplay previews')}</div><button type="button" class="phone-primary" data-phone-save style="margin-top:22px">Save</button></div>`;
}
const renderers = {home,details,downloads,smart,search,profile};
function escapeText(value) {
  return value.replace(/[&<>"']/g,character=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
}
function render(screen) {
  if (!renderers[screen]) return;
  current = screen;
  root.innerHTML = renderers[screen]();
  root.scrollTop = 0;
  buttons.forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.phoneScreen===screen)));
  caption.textContent = descriptions[screen];
  preview.setAttribute('aria-label',`NetflixPro phone ${labels[screen]} preview`);
}
buttons.forEach(button=>button.addEventListener('click',()=>render(button.dataset.phoneScreen)));
root.addEventListener('click',event=>{
  const open = event.target.closest('[data-phone-open]');
  if (open) { render(open.dataset.phoneOpen); return; }
  const toggleButton = event.target.closest('[data-phone-toggle]');
  if (toggleButton) {
    const key = toggleButton.dataset.phoneToggle;
    state[key] = !state[key];
    toggleButton.setAttribute('aria-checked',String(state[key]));
    return;
  }
  const allocationButton = event.target.closest('[data-phone-allocation]');
  if (allocationButton) {
    const key = allocationButton.dataset.phoneAllocation;
    state[key] = Math.max(0,Math.min(10,state[key]+Number(allocationButton.dataset.step)));
    allocationButton.parentElement.querySelector('b').innerHTML = `${state[key].toFixed(1)}<small>GB</small>`;
    return;
  }
  const listButton = event.target.closest('[data-phone-list]');
  if (listButton) {
    state.listed = !state.listed;
    listButton.setAttribute('aria-pressed',String(state.listed));
    listButton.innerHTML = icon(state.listed?'check':'plus')+'My List';
    return;
  }
  const saveButton = event.target.closest('[data-phone-save]');
  if (saveButton) {
    const name = root.querySelector('#phoneProfileName').value.trim();
    if (name) state.name = name.slice(0,30);
    feedback(name?'Profile updated in this preview.':'Enter a profile name.');
    return;
  }
  const feedbackButton = event.target.closest('[data-phone-feedback]');
  if (feedbackButton) feedback(feedbackButton.dataset.phoneFeedback);
});
function feedback(message) {
  let node = root.querySelector('.phone-preview-feedback');
  if (!node) {
    node = document.createElement('p'); node.className='phone-preview-feedback'; node.setAttribute('role','status');
    (root.querySelector('.phone-details__body')||root.querySelector('.phone-view')).appendChild(node);
  }
  node.textContent=message;
  node.scrollIntoView({block:'nearest',behavior:'auto'});
}
render('home');
