(function () {
  'use strict';

  const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const EASE = 'cubic-bezier(0.4, 0, 0.2, 1)';
  const T = { fast: 130, micro: 234, row: 260, feature: 286, toast: 390, intro: 520 };

  /* ─── Nav scroll state ─── */
  const nav = document.getElementById('nav');
  const onScroll = () => {
    if (window.scrollY > 50) nav.classList.add('scrolled');
    else nav.classList.remove('scrolled');
  };
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  /* ─── IntersectionObserver: feature cards stagger ─── */
  if ('IntersectionObserver' in window) {
    const io = new IntersectionObserver((entries) => {
      entries.forEach((entry, i) => {
        if (entry.isIntersecting) {
          const delay = i * 100;
          entry.target.style.transitionDelay = delay + 'ms';
          entry.target.classList.add('is-in');
          io.unobserve(entry.target);
        }
      });
    }, { threshold: 0.15 });
    document.querySelectorAll('.feature').forEach(el => io.observe(el));
  } else {
    document.querySelectorAll('.feature').forEach(el => el.classList.add('is-in'));
  }

  /* ─── IntersectionObserver: section titles scale-in on scroll ─── */
  if ('IntersectionObserver' in window) {
    const titleIO = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-in');
          titleIO.unobserve(entry.target);
        }
      });
    }, { threshold: 0.3 });
    document.querySelectorAll('.section__title').forEach(el => titleIO.observe(el));
  } else {
    document.querySelectorAll('.section__title').forEach(el => el.classList.add('is-in'));
  }

  /* ─── Stagger poster cards (50ms between each, 800ms duration) ─── */
  if (!prefersReduced) {
    document.querySelectorAll('.rail .poster').forEach((el, i) => {
      el.style.animationDelay = (i * 50) + 'ms';
    });
  }

  /* ─── Back to top button (IntersectionObserver sentinel) ─── */
  (function () {
    const sentinel = document.getElementById('scrollTopSentinel');
    const btn = document.getElementById('backToTop');
    if (!sentinel || !btn) return;
    btn.addEventListener('click', () => {
      window.scrollTo({ top: 0, behavior: prefersReduced ? 'auto' : 'smooth' });
    });
    if ('IntersectionObserver' in window) {
      const obs = new IntersectionObserver((entries) => {
        entries.forEach(e => {
          if (e.isIntersecting) btn.classList.remove('is-visible');
          else btn.classList.add('is-visible');
        });
      }, { threshold: 0 });
      obs.observe(sentinel);
    } else {
      // Fallback: scroll listener
      const onScrollBTT = () => {
        if (window.scrollY > 600) btn.classList.add('is-visible');
        else btn.classList.remove('is-visible');
      };
      window.addEventListener('scroll', onScrollBTT, { passive: true });
      onScrollBTT();
    }
  })();

  /* ─── Page loader: hide once interactive, only show if >300ms ─── */
  (function () {
    const loader = document.getElementById('pageLoader');
    if (!loader) return;
    let shown = false;
    const showTimer = setTimeout(() => {
      shown = true;
      loader.classList.add('is-active');
    }, 300);
    const hide = () => {
      clearTimeout(showTimer);
      // Wait one frame for layout, then fade out
      requestAnimationFrame(() => {
        if (shown) {
          loader.classList.add('is-hidden');
        } else {
          loader.style.display = 'none';
        }
      });
    };
    if (document.readyState === 'complete' || document.readyState === 'interactive') {
      // DOM ready; just hide
      setTimeout(hide, 0);
    } else {
      document.addEventListener('DOMContentLoaded', () => setTimeout(hide, 50));
    }
    // Safety: hide after 2.5s no matter what
    setTimeout(hide, 2500);
  })();

  /* ─── FAQ accordion ─── */
  const faqData = [
    ['Is this the official Netflix app?', 'No, NetflixPro TV is a third-party client that streams from public sources. It is not affiliated with, endorsed by, or sponsored by Netflix, Inc.'],
    ['Is it free?', 'The APK is free to download. Guests can browse and watch trailers. Full playback requires an active plan; choose and pay for your plan on the phone.'],
    ['What devices are supported?', 'Use the TV APK on Android TV or Google TV, and the phone APK on Android phones. Both require Android 7.0 or newer. Playback capabilities depend on your device and the available stream.'],
    ['Does it support 4K?', 'Playback quality depends on the available stream, your plan, connection and device. A 4K-capable display does not guarantee that every title has a 4K source.'],
    ['Can I use a VPN?', 'Yes, the app works with any VPN that tunnels your Android TV’s traffic. No special configuration needed.'],
    ['Is my data safe?', 'Your watch history and My List are stored locally on your TV and synced to your private account. We do not sell data to third parties.'],
    ['How do I update?', 'Install a release with Update Gate once. On later launches, the app checks for a newer release, downloads it and verifies it before installation. Android may ask you to allow this app to install updates or confirm the install. It may reopen automatically; otherwise open it from your launcher. You can also download the APK here.'],
    ['How does my phone connect to TV?', 'Use the TV pairing option on your phone to sign in on the TV. Your account profiles, My List and watch history are shared between the apps. Available profiles and playback access depend on your plan.'],
    ['Does it support subtitles?', 'Choose from the subtitle tracks provided by the available stream. Subtitle languages and availability vary by title.'],
    ['How do I report a bug?', 'Include your app version, device model and the steps that caused the problem when contacting support. Avoid sending your password, PIN or payment credentials.']
  ];
  const faqList = document.getElementById('faqList');
  const chevronSVG = '<svg class="faq__chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 6 15 12 9 18"/></svg>';
  faqData.forEach(([q, a]) => {
    const item = document.createElement('div');
    item.className = 'faq__item';
    item.innerHTML = `
      <button class="faq__q" type="button" aria-expanded="false">
        <span>${q}</span>
        ${chevronSVG}
      </button>
      <div class="faq__a"><div class="faq__a-inner">${a}</div></div>
    `;
    faqList.appendChild(item);
  });
  faqList.addEventListener('click', (e) => {
    const q = e.target.closest('.faq__q');
    if (!q) return;
    const item = q.parentElement;
    const open = item.classList.toggle('is-open');
    q.setAttribute('aria-expanded', open ? 'true' : 'false');
  });

  /* App tour: explicit screen selection, swipe navigation, and a keyboard viewer. */
  const track = document.getElementById('track');
  const shots = Array.from(track.querySelectorAll('.shot'));
  const dots = document.getElementById('dots');
  const tourCaption = document.getElementById('tourCaption');
  const lightbox = document.getElementById('lightbox');
  const lbContent = document.getElementById('lbContent');
  const lbCaption = document.getElementById('lbCaption');
  const lbClose = document.getElementById('lbClose');
  const lbPrev = document.getElementById('lbPrev');
  const lbNext = document.getElementById('lbNext');
  let active = 0;
  let viewerOpen = false;
  let viewerTrigger = null;
  let touchStart = null;
  let lastSwipeAt = 0;
  const inertBeforeViewer = new Map();

  shots.forEach((shot, index) => {
    const button = document.createElement('button');
    button.className = 'shot__dot';
    button.type = 'button';
    button.textContent = shot.dataset.screen;
    button.setAttribute('aria-label', 'Show ' + shot.dataset.screen);
    button.setAttribute('aria-controls', 'track');
    button.addEventListener('click', () => goTo(index));
    dots.appendChild(button);
    shot.addEventListener('click', () => {
      if (Date.now() - lastSwipeAt > 600) openLightbox(index, shot);
    });
    shot.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        openLightbox(index, shot);
      }
    });
  });

  function warmPreview(index) {
    const preview = shots[(index + shots.length) % shots.length];
    preview.querySelectorAll('img').forEach(image => { image.loading = 'eager'; });
  }

  function updateTour() {
    shots.forEach((shot, index) => {
      const selected = index === active;
      shot.classList.toggle('is-active', selected);
      shot.inert = !selected;
      shot.setAttribute('aria-hidden', String(!selected));
      shot.tabIndex = selected ? 0 : -1;
      const button = dots.children[index];
      button.classList.toggle('is-active', selected);
      button.setAttribute('aria-pressed', String(selected));
    });
    tourCaption.textContent = shots[active].dataset.label;
    warmPreview(active);
    warmPreview(active + 1);
  }

  function goTo(index) {
    active = (index + shots.length) % shots.length;
    updateTour();
  }

  document.getElementById('tourPrev').addEventListener('click', () => goTo(active - 1));
  document.getElementById('tourNext').addEventListener('click', () => goTo(active + 1));
  document.getElementById('tourExpand').addEventListener('click', event => openLightbox(active, event.currentTarget));
  track.addEventListener('pointerdown', event => {
    if (event.pointerType === 'touch') touchStart = { x: event.clientX, y: event.clientY };
  }, { passive: true });
  track.addEventListener('pointerup', event => {
    if (!touchStart) return;
    const dx = event.clientX - touchStart.x;
    const dy = event.clientY - touchStart.y;
    touchStart = null;
    if (Math.abs(dx) > 45 && Math.abs(dx) > Math.abs(dy) * 1.25) {
      lastSwipeAt = Date.now();
      goTo(active + (dx < 0 ? 1 : -1));
    }
  }, { passive: true });
  track.addEventListener('pointercancel', () => { touchStart = null; }, { passive: true });
  updateTour();

  function renderViewer() {
    const source = shots[active];
    const clone = source.cloneNode(true);
    clone.classList.add('lightbox__shot', 'is-active');
    clone.inert = false;
    clone.removeAttribute('tabindex');
    clone.removeAttribute('aria-hidden');
    clone.setAttribute('role', 'img');
    clone.setAttribute('aria-label', source.dataset.label);
    clone.querySelectorAll('img').forEach(image => { image.loading = 'eager'; });
    lbContent.replaceChildren(clone);
    lbCaption.textContent = source.dataset.label;
    warmPreview(active - 1);
    warmPreview(active + 1);
  }

  function openLightbox(index, trigger) {
    active = index;
    viewerTrigger = trigger;
    renderViewer();
    viewerOpen = true;
    lightbox.classList.add('is-open');
    lightbox.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';
    Array.from(document.body.children).forEach(element => {
      if (element !== lightbox && element.tagName !== 'SCRIPT') {
        inertBeforeViewer.set(element, element.inert);
        element.inert = true;
      }
    });
    lbClose.focus({ preventScroll: true });
  }

  function closeLightbox() {
    viewerOpen = false;
    lightbox.classList.remove('is-open');
    lightbox.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
    inertBeforeViewer.forEach((wasInert, element) => { element.inert = wasInert; });
    inertBeforeViewer.clear();
    goTo(active);
    const trigger = viewerTrigger && !viewerTrigger.inert ? viewerTrigger : shots[active];
    trigger.focus({ preventScroll: true });
  }

  function navLightbox(delta) {
    active = (active + delta + shots.length) % shots.length;
    renderViewer();
  }
  lbClose.addEventListener('click', closeLightbox);
  lbPrev.addEventListener('click', () => navLightbox(-1));
  lbNext.addEventListener('click', () => navLightbox(1));
  lightbox.addEventListener('click', event => { if (event.target === lightbox) closeLightbox(); });
  document.addEventListener('keydown', event => {
    if (!viewerOpen) {
      if (event.target.closest('#carousel') && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) {
        event.preventDefault();
        goTo(active + (event.key === 'ArrowRight' ? 1 : -1));
      }
      return;
    }
    if (event.key === 'Escape') { event.preventDefault(); closeLightbox(); }
    if (event.key === 'ArrowLeft') { event.preventDefault(); navLightbox(-1); }
    if (event.key === 'ArrowRight') { event.preventDefault(); navLightbox(1); }
    if (event.key === 'Tab') {
      const buttons = [lbClose, lbPrev, lbNext];
      const index = buttons.indexOf(document.activeElement);
      if ((event.shiftKey && index <= 0) || (!event.shiftKey && index === buttons.length - 1)) {
        event.preventDefault();
        buttons[event.shiftKey ? buttons.length - 1 : 0].focus();
      }
    }
  });


  /* ─── Smooth-scroll for in-page anchors (offset for sticky nav) ─── */
  document.querySelectorAll('a[href^="#"]').forEach(a => {
    a.addEventListener('click', (e) => {
      const id = a.getAttribute('href');
      // Release loading can turn this anchor into a real HTTPS APK link.
      if (!id || !id.startsWith('#') || id.length < 2) return;
      const target = document.getElementById(id.slice(1));
      if (!target) return;
      e.preventDefault();
      const navH = nav.getBoundingClientRect().height;
      const top = target.getBoundingClientRect().top + window.pageYOffset - navH - 12;
      window.scrollTo({ top, behavior: prefersReduced ? 'auto' : 'smooth' });
    });
  });

  /* ─── TMDB image wiring ───
     Image CDN is allowed (host policy blocks scripts/fetches, not <img>).
     Curated hardcoded dataset; no API calls.
  */
  const TMDB_IMG = 'https://image.tmdb.org/t/p';

  // 24 titles split across 3 rails
  const TITLES_TRENDING = [
    { title: 'Fight Club',          type: 'movie', year: 1999, rating: 8.4, poster: '/1g0dhYtq4irTY1GPXvft6k4YLjm.jpg', backdrop: '/52AfXWuXCHn3UjD17rBruA9f5qb.jpg', overview: 'An insomniac office worker forms an underground fight club with a soap maker.' },
    { title: 'Pulp Fiction',        type: 'movie', year: 1994, rating: 8.5, poster: '/d5iIlFn5s0ImszYzBPb8JPIfbXD.jpg', backdrop: '/suaEOtk1N1sgg2MTM7oZd2cfVp3.jpg', overview: 'The lives of two mob hitmen, a boxer, a gangster and his wife intertwine in four tales of violence and redemption.' },
    { title: 'The Dark Knight',     type: 'movie', year: 2008, rating: 8.5, poster: '/qJ2tW6WMUDux911r6m7haRef0WH.jpg', backdrop: '/nMKdUUepR0i5zn0y1T4CsSB5chy.jpg', overview: 'Batman raises the stakes in his war on crime with the help of Lt. Jim Gordon and DA Harvey Dent.' },
    { title: 'Inception',           type: 'movie', year: 2010, rating: 8.4, poster: '/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg', backdrop: '/s3TBrRGB1iav7gFOCNx3H31MoES.jpg', overview: 'A thief who steals corporate secrets through dream-sharing technology is given a final job.' },
    { title: 'Breaking Bad',        type: 'tv',    year: 2008, rating: 8.9, poster: '/ggFHVNu6YYI5L9pCfOacjizRGt.jpg', backdrop: '/tsRy63Mu5cu022Q9DZ54ubdkdAW.jpg', overview: 'A chemistry teacher diagnosed with cancer teams with a former student to cook crystal meth.' },
    { title: 'Stranger Things',     type: 'tv',    year: 2016, rating: 8.6, poster: '/49WJfeN0moxb9IPfGn8AIqMGskD.jpg', backdrop: '/56v2KjBlU4XaOv9rVYEQypROD7P.jpg', overview: 'A group of kids in a small town uncover a government conspiracy and a portal to a strange dimension.' },
    { title: 'The Matrix',          type: 'movie', year: 1999, rating: 8.2, poster: '/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg', backdrop: '/fNG7i7RqMEr3qhY9dBEAnnfoXBI.jpg', overview: 'A hacker discovers reality is a simulation and joins a rebellion against its machines.' },
    { title: 'Interstellar',        type: 'movie', year: 2014, rating: 8.4, poster: '/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg', backdrop: '/rAiYTfKGqDCRIIqo664sY9XZIvQ.jpg', overview: 'A team of explorers travels through a wormhole in space to ensure humanity’s survival.' }
  ];

  const TITLES_CONTINUE = [
    { title: 'Game of Thrones',     type: 'tv',    year: 2011, rating: 8.4, poster: '/u3bZgnGQ9T01sWNhyveQzIzW4PR.jpg', backdrop: '/2OMB0ynKlyIenMJWI2Dy9IWT4c.jpg', overview: 'Nine noble families wage war for control of the Iron Throne while an ancient enemy returns.' },
    { title: 'The Crown',           type: 'tv',    year: 2016, rating: 8.6, poster: '/1M876KPjulVwppEpldhdc8V4o68.jpg', backdrop: '/7k7oKVjvLNXdf75CezvcXKynZ7P.jpg', overview: 'The political rivalries and romance of Queen Elizabeth II’s reign from the 1940s onward.' },
    { title: 'Severance',           type: 'tv',    year: 2022, rating: 8.7, poster: '/lXglP6jRdklunwMqOiZ4v0aMTFz.jpg', backdrop: '/lXglP6jRdklunwMqOiZ4v0aMTFz.jpg', overview: 'Employees of Lumon Industries undergo a procedure separating their work and personal memories.' },
    { title: 'The Boys',            type: 'tv',    year: 2019, rating: 8.7, poster: '/stTEycfG9928NWGEcPyiK1DO4Fd.jpg', backdrop: '/mGVrYUgi2eToJHP0d8qxpYfqA6M.jpg', overview: 'A group of vigilantes set out to take down corrupt superheroes who abuse their powers.' },
    { title: 'Better Call Saul',    type: 'tv',    year: 2015, rating: 8.9, poster: '/fC2HDm5t0lHl1m00qecM9pSe34E.jpg', backdrop: '/1yeVJox3rjo2jBKrrihIMj7uoS9.jpg', overview: 'The trials and tribulations of attorney Jimmy McGill in the years before he becomes Saul Goodman.' },
    { title: 'Dark',                type: 'tv',    year: 2017, rating: 8.8, poster: '/apbrbWs8d9c1OK9xvKBx4mTJ4ty.jpg', backdrop: '/3lBDg3i6nn5R2NKFCJ6oKyUo2N5.jpg', overview: 'A German town’s children vanish, exposing time-travel conspiracies spanning four families.' },
    { title: 'The Wire',            type: 'tv',    year: 2002, rating: 8.7, poster: '/zN80RkEsVuQ1J4e9X4nWp0SwF3t.jpg', backdrop: '/7pyh4D9Ut89htZQBZgNx2K8uVR8.jpg', overview: 'The Baltimore drug scene seen through the eyes of dealers, law enforcement, and schools.' },
    { title: 'House of the Dragon', type: 'tv',    year: 2022, rating: 8.4, poster: '/7QMsOTMUswlwxJP0rTTZfmz2tX2.jpg', backdrop: '/etj8E2o0Bud0HkONVQPjyCkIvpv.jpg', overview: 'A Targaryen civil war erupts two centuries before the events of Game of Thrones.' }
  ];

  const TITLES_ACCLAIMED = [
    { title: 'The Shawshank Redemption', type: 'movie', year: 1994, rating: 8.7, poster: '/q6y0Go1tsGEsmtFryDOJo3dEmqu.jpg', backdrop: '/kXfqcdQKsToO0OUXHcrrNCHDBzO.jpg', overview: 'Two imprisoned men bond over a number of years, finding solace and eventual redemption.' },
    { title: 'The Godfather',           type: 'movie', year: 1972, rating: 8.7, poster: '/3bhkrj58Vtu7enYsRolD1fZdja1.jpg', backdrop: '/tmU7GeKVybMWFButWEGl2M4GeiP.jpg', overview: 'The aging patriarch of an organized crime dynasty transfers control to his reluctant son.' },
    { title: '12 Angry Men',            type: 'movie', year: 1957, rating: 8.5, poster: '/ow3wq89wM8qd5X7hWKxiRfsFf9C.jpg', backdrop: '/qqHQsStV6exghCM7zbObuYBiYxw.jpg', overview: 'A jury holdout attempts to prevent a miscarriage of justice by forcing his colleagues to reconsider.' },
    { title: 'Schindler’s List',  type: 'movie', year: 1993, rating: 8.6, poster: '/sF1U4EUQS8YHUYjNl3pMGNIQyr0.jpg', backdrop: '/loRmRzQXZeqG78TqZuyvSlEQfZb.jpg', overview: 'In German-occupied Poland, Oskar Schindler saves the lives of more than a thousand Jewish refugees.' },
    { title: 'Parasite',                type: 'movie', year: 2019, rating: 8.5, poster: '/7IiTTgloJzvGI1TAYymCfbfl3vT.jpg', backdrop: '/TU9NIjwzjoKPwQHoHshkFcQUCG.jpg', overview: 'A poor family schemes to become employed by a wealthy family by infiltrating their household.' },
    { title: 'The Lord of the Rings: The Return of the King', type: 'movie', year: 2003, rating: 8.5, poster: '/rCzpDGLbOoPwLjy3OAm5NUPOTrC.jpg', backdrop: '/8BPZO0Bf8TeAy8znF43z8soK3ys.jpg', overview: 'Gandalf and Aragorn lead the World of Men against Sauron’s army to save Middle-earth.' },
    { title: 'Forrest Gump',            type: 'movie', year: 1994, rating: 8.5, poster: '/arw2vcBveWOVZr6pxd9XTd1TdQa.jpg', backdrop: '/yE5d3BUhE8hCnkMUJOo1QDoOGNz.jpg', overview: 'The presidencies of Kennedy and Johnson, Vietnam, and more—all from the eyes of an Alabama man.' },
    { title: 'Spirited Away',           type: 'movie', year: 2001, rating: 8.5, poster: '/39wmItIWsg5sZMyRUHLkWBcuVCM.jpg', backdrop: '/Ab8mkHmkYADjU7wQiOkia9BzGvS.jpg', overview: 'During her family’s move to the suburbs, a sullen 10-year-old discovers a world of gods and spirits.' }
  ];

  // Build a poster card
  function posterCard(t) {
    return `
      <a class="poster g1" href="#${t.type}-${encodeURIComponent(t.title)}" aria-label="Watch ${t.title} (${t.year})">
        <img class="tmdb" src="${TMDB_IMG}/w780${t.backdrop}" alt="${t.title} artwork" loading="lazy" decoding="async" referrerpolicy="no-referrer" />
        <div class="poster__shade"></div>
        <img class="poster__n" src="assets/project-mark.svg" alt="" />
        <span class="poster__rating-badge">★ ${t.rating.toFixed(1)}</span>
        <p class="poster__overview">${t.overview}</p>
        <div class="poster__title">${t.title}</div>
      </a>
    `;
  }

  function renderRail(name, list) {
    const rail = document.querySelector('.rail[data-rail="' + name + '"] .rail__scroller');
    if (!rail) return;
    rail.innerHTML = list.map(posterCard).join('');
    if (name === 'continue') rail.querySelectorAll('.poster').forEach(card => card.insertAdjacentHTML('beforeend', '<span class="poster__continue" aria-hidden="true"></span>'));
  }

  renderRail('trending', TITLES_TRENDING);
  renderRail('continue', TITLES_CONTINUE);
  renderRail('acclaimed', TITLES_ACCLAIMED);

  // Wire hero backdrop (Blade Runner 2049 — TMDB id 335983)
  (function () {
    var heroEl = document.getElementById('heroBackdrop');
    if (!heroEl) return;
    // Original quality is large; use w1280 for size/quality balance
    heroEl.style.backgroundImage = 'url(' + TMDB_IMG + '/w1280/ilRyazdMJwN05exqhwK4tMKBYZs.jpg)';
  })();

  // Share the existing sample catalogue with the TV layouts; no extra API requests.
  const appIcon = name => '<svg class="app-icon" aria-hidden="true"><use href="#app-icon-' + name + '"></use></svg>';
  const appGenres = [
    ['Dramas', 'Compelling stories', 'film', '#CC8CAA', '#29222a'],
    ['Comedies', 'Feel-good favourites', 'comedy', '#E1B576', '#29251f'],
    ['Action', 'High-stakes stories', 'bolt', '#E78C77', '#2c2220'],
    ['Romance', 'Love & connection', 'heart', '#E89BB8', '#2b232b'],
    ['Anime', 'Bold new worlds', 'star', '#ADA5EA', '#252431'],
    ['Kids & Family', 'Watch together', 'family', '#88C9C2', '#1e2a2b'],
    ['Sci-Fi', 'Beyond our world', 'rocket', '#8CC6E5', '#202832'],
    ['Thrillers', 'On the edge', 'eye', '#B1C59D', '#242922'],
    ['Documentaries', 'Real stories', 'globe', '#D2BD94', '#28271f'],
    ['Horror', 'After dark', 'moon', '#C493AC', '#29232b'],
    ['Fantasy', 'A little wonder', 'magic', '#C1A6DF', '#272330']
  ];
  const escapeMarkup = value => value.replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]));
  document.querySelectorAll('[data-app-categories]').forEach(rail => {
    rail.innerHTML = appGenres.map(([title, caption, icon, accent, surface]) =>
      '<div class="app-category"><span class="app-category__name">' + escapeMarkup(title) + '</span></div>'
    ).join('');
  });
  document.querySelectorAll('[data-app-keyboard]').forEach(grid => {
    grid.innerHTML = Array.from('abcdefghijklmnopqrstuvwxyz1234567890').map(key => '<span class="app-key">' + key + '</span>').join('');
  });
  document.querySelectorAll('[data-app-landscape]').forEach(rail => {
    rail.innerHTML = TITLES_TRENDING.slice(0, 4).map(title =>
      '<div class="app-landscape"><img src="' + TMDB_IMG + '/w780' + title.backdrop + '" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" /><img class="app-n app-landscape__n" src="assets/project-mark.svg" alt="" /><span class="app-landscape__title">' + escapeMarkup(title.title) + '</span></div>'
    ).join('');
  });
  document.querySelectorAll('[data-app-search-grid]').forEach(grid => {
    grid.innerHTML = TITLES_TRENDING.slice(0, 8).map(title =>
      '<div class="app-search-card"><img src="' + TMDB_IMG + '/w342' + title.poster + '" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" /></div>'
    ).join('');
  });
  // The installation illustration shows the same Home layout as the tour.
  const installScreen = document.querySelector('.tv__screen');
  if (installScreen) installScreen.appendChild(shots[0].querySelector('.app-ui').cloneNode(true));
  updateTour();


})();
