import json
import functools
import http.server
import os
import shutil
import threading
from pathlib import Path
from playwright.sync_api import sync_playwright
out=Path(os.environ.get('PHONE_TOUR_REVIEW_DIR', '/tmp/netflixpro-phone-tour-review'))
out.mkdir(parents=True, exist_ok=True)
site=Path(__file__).resolve().parents[1]
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(http.server.SimpleHTTPRequestHandler,directory=str(site)))
threading.Thread(target=server.serve_forever,daemon=True).start()
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=shutil.which('chromium') or shutil.which('google-chrome'),headless=True,args=['--no-sandbox'])
 page=browser.new_page(viewport={'width':1440,'height':1000},device_scale_factor=1)
 errors=[]
 page.on('pageerror',lambda e:errors.append(str(e)))
 page.goto(f'http://127.0.0.1:{server.server_port}',wait_until='domcontentloaded')
 page.wait_for_selector('[data-release-card="mobile"] [data-release-download][data-unavailable="false"]')
 assert '/downloads/mobile/' in page.locator('[data-release-card="mobile"] [data-release-download]').get_attribute('href')
 page.locator('#phone-tour').scroll_into_view_if_needed()
 page.wait_for_selector('.phone-home')
 page.wait_for_timeout(500)
 checks=[]
 for screen in ['home','details','downloads','smart','search','profile']:
  page.locator(f'[data-phone-screen="{screen}"]').click()
  page.wait_for_timeout(260)
  assert page.locator(f'[data-phone-screen="{screen}"]').get_attribute('aria-pressed')=='true'
  assert page.locator('.phone-bottom').count()==(1 if screen in ['home','search'] else 0)
  if screen in ['home','search']:
   assert page.locator('.phone-bottom').bounding_box()['height'] <= 70
   assert page.locator('.phone-bottom .app-icon').evaluate_all('(icons)=>icons.every(icon=>icon.getBoundingClientRect().width<=24&&icon.getBoundingClientRect().height<=24)')
  assert page.locator('#phoneContent img').evaluate_all('(images)=>images.every(image=>image.complete&&image.naturalWidth>0)')
  page.locator('#phone-tour').screenshot(path=str(out/f'{screen}-desktop.png'))
  checks.append(screen)
 page.locator('[data-phone-screen="smart"]').click()
 toggle=page.get_by_role('switch',name='Downloads for You',exact=True)
 assert toggle.get_attribute('aria-checked')=='false'; toggle.click(); assert toggle.get_attribute('aria-checked')=='true'
 next_toggle=page.get_by_role('switch',name='Download Next Episode',exact=True)
 assert next_toggle.get_attribute('aria-checked')=='true'
 page.get_by_role('button',name='Increase storage for Alex',exact=True).click()
 assert page.locator('.phone-alloc b').first.inner_text().startswith('3.5')
 page.locator('[data-phone-screen="profile"]').click()
 page.locator('#phoneProfileName').fill('Jordan')
 page.get_by_role('button',name='Save',exact=True).click()
 page.locator('[data-phone-screen="home"]').click()
 assert 'Jordan' in page.locator('.phone-home').inner_text()
 page.locator('#phoneContent [data-phone-list]').click()
 assert page.locator('#phoneContent [data-phone-list]').get_attribute('aria-pressed')=='true'
 page.locator('#phoneContent [data-phone-open="downloads"]').click()
 page.locator('#phoneContent [data-phone-open="smart"]').first.click()
 assert page.get_by_role('switch',name='Downloads for You',exact=True).get_attribute('aria-checked')=='true'
 # Existing TV tour remains independently functional.
 page.get_by_role('button',name='Show Search',exact=True).click()
 assert page.locator('.shot--search').get_attribute('aria-hidden')=='false'
 page.get_by_role('button',name='Show Home',exact=True).click()
 assert page.locator('.shot--home').get_attribute('aria-hidden')=='false'
 for width in [390,768,1920]:
  page.set_viewport_size({'width':width,'height':1000})
  page.locator('[data-phone-screen="details"]').click()
  page.locator('#phone-tour').scroll_into_view_if_needed()
  assert page.evaluate('document.documentElement.scrollWidth<=window.innerWidth')
  page.locator('#phone-tour').screenshot(path=str(out/f'responsive-{width}.png'))
 page.emulate_media(reduced_motion='reduce')
 assert page.locator('.phone-tour__tabs button').first.evaluate('(button)=>getComputedStyle(button).transitionDuration')=='0s'
 assert not errors,errors
 browser.close()
 (out/'verification.json').write_text(json.dumps({'screens':checks,'responsiveWidths':[390,768,1920],'switchIndependence':True,'profileSave':True,'navigationCoverage':True,'tvTourPreserved':True,'reducedMotion':True,'pageErrors':errors},indent=2)+'\n')
 print('Six phone screens, independent toggles, storage allocation, profile preview, My List, navigation, existing TV tour, responsive layouts and reduced motion passed.')

server.shutdown()
server.server_close()
