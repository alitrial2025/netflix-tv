const links = [...document.querySelectorAll('.nav__link')];
const sections = links.map(link => document.querySelector(link.getAttribute('href'))).filter(Boolean);
function activate(id) {
  links.forEach(link => {
    if (link.getAttribute('href') === `#${id}`) link.setAttribute('aria-current', 'location');
    else link.removeAttribute('aria-current');
  });
}
links.forEach(link => link.addEventListener('click', () => activate(link.getAttribute('href').slice(1))));
if ('IntersectionObserver' in window) {
  const observer = new IntersectionObserver(entries => {
    const visible = entries.filter(entry => entry.isIntersecting).sort((a,b) => a.boundingClientRect.top - b.boundingClientRect.top);
    if (visible[0]) activate(visible[0].target.id);
  }, { rootMargin: '-88px 0px -55% 0px', threshold: 0 });
  sections.forEach(section => observer.observe(section));
}
activate(location.hash.slice(1) || 'top');
