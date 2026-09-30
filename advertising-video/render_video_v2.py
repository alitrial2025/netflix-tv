"""Second edit: source-matched TV screens and a more varied editorial treatment.

No Android build is used. The finished film is a staged interface demonstration.
The original export and generator remain available alongside this revision.
"""
from pathlib import Path
from functools import lru_cache
import argparse, bisect, ctypes, gc, hashlib, json, math, os, subprocess, time, wave
if os.name == 'nt':
    ctypes.windll.kernel32.SetPriorityClass(ctypes.c_void_p(-1), 0x40)
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import render_video as c
# Bound resized artwork independently of the original generator's larger cache.
c.cover = lru_cache(maxsize=24)(c.cover.__wrapped__)

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'revision-2'
W, H, FPS = 1920, 1080, 60
S = 1.5
FINAL = ROOT / 'NetflixPro-TV-3min-v2-1080p60.mp4'
SCENES = [
    (0, 6, 'intro', 'EVERY STORY. ONE REMOTE.', 'Meet Netflix Pro.'),
    (6, 19, 'profiles', 'MAKE IT PERSONAL', 'A space for everyone.'),
    (19, 37, 'home', 'FIND YOUR NEXT STORY', 'Featured stories. A world to explore.'),
    (37, 53, 'genres', 'FOLLOW YOUR MOOD', 'Eleven ways into your next watch.'),
    (53, 75, 'rows', 'KEEP EXPLORING', 'Across titles. Between rows. Always in focus.'),
    (75, 98, 'details', 'GO INSIDE THE STORY', 'Discover. Save. Make your choice.'),
    (98, 119, 'episodes', 'ONE MORE EPISODE', 'Your seasons. Your next chapter.'),
    (119, 136, 'search', 'HAVE SOMETHING IN MIND?', 'Find it with your remote.'),
    (136, 151, 'my', 'PICK UP WHERE YOU LEFT OFF', 'Continue Watching. My List. Your favourites.'),
    (151, 168, 'player', 'WATCH YOUR WAY', 'Seek, choose a language, settle in.'),
    (168, 177, 'edit', 'YOUR PROFILE. YOUR CHOICE.', 'Make the experience your own.'),
    (177, 180, 'outro', 'YOUR NEXT WATCH STARTS HERE', 'Netflix Pro for Android TV.'),
]
STARTS = [s[0] for s in SCENES]
px, clamp, ease = c.px, c.clamp, c.ease
text, rr, icon, logo, wrapped = c.text, c.rr, c.icon, c.logo, c.wrapped
WHITE, MUTED, RED = c.WHITE, c.MUTED, c.RED
CAT, FILMS, SERIES, BY_ID = c.CAT, c.FILMS, c.SERIES, c.BY_ID
NAV_H, HERO_H, CATEGORY_H, ROW_H, CARD_TOP = 52, 432, 96, 395, 48
FIRST_ROW = HERO_H + 10 + CATEGORY_H

def layer(w=1280, h=720, colour=(0, 0, 0, 0)):
    return Image.new('RGBA', (px(w), px(h)), colour)

def paste_alpha(target, source, xy=(0, 0), opacity=1):
    if opacity <= 0: return
    if source.mode == 'RGBA':
        if opacity < .999:
            source = source.copy()
            source.putalpha(source.getchannel('A').point(lambda a: round(a * opacity)))
        target.paste(source, (px(xy[0]), px(xy[1])), source)
    elif opacity < .999:
        target.paste(source, (px(xy[0]), px(xy[1])), Image.new('L', source.size, round(255 * opacity)))
    else: target.paste(source, (px(xy[0]), px(xy[1])))

@lru_cache(maxsize=20)
def serif_font(size):
    return ImageFont.truetype('C:/Windows/Fonts/georgiab.ttf', px(size))

def serif(im, x, y, value, size, colour=WHITE, anchor='lt'):
    ImageDraw.Draw(im).text((px(x), px(y)), value, font=serif_font(size), fill=colour, anchor=anchor)

def blend_colour(a, b, progress):
    return tuple(round(x + (y-x)*clamp(progress)) for x, y in zip(c.rgb(a), c.rgb(b)))

def spring_track(t, initial, events, stiffness=430):
    """Exact critically damped response; preserve position and velocity on repeats.

    Mirrors Spring.DampingRatioNoBouncy and TvMotion.stiffness(base)/1.3^2.
    Events are (time, requested focus/index). No easing reset between key presses.
    """
    omega = math.sqrt(stiffness / 1.3**2)
    x, velocity, target, previous = float(initial), 0., float(initial), 0.
    def advance(dt):
        nonlocal x, velocity
        delta = x - target
        coefficient = velocity + omega * delta
        decay = math.exp(-omega * dt)
        x = target + (delta + coefficient * dt) * decay
        velocity = (velocity - omega * coefficient * dt) * decay
    for at, value in events:
        if t < at: break
        advance(at-previous); target = float(value); previous = at
    advance(max(0, t-previous))
    if abs(x-target) < .0002 and abs(velocity) < .003: x = target
    return x, int(target)

def row_alpha(row_level, focus):
    # HomeScreen.kt computeRowAlpha: distance -1 is hidden; distance 0 is opaque.
    distance = row_level - focus
    if distance <= -1: return 0.
    if distance <= 0: return distance + 1
    if distance <= 1: return 1 - .15 * distance
    if distance <= 2: return .85 - .20 * (distance-1)
    if distance <= 3: return .65 - .20 * (distance-2)
    if distance <= 4: return .45 - .20 * (distance-3)
    return .25

def home_anchor(level):
    if level < 0: return 0.
    if level == 0: return -(HERO_H+10.)
    return -FIRST_ROW - ROW_H*(level-1)

def home_offset(focus):
    lower = math.floor(focus); fraction = focus-lower
    return home_anchor(lower) + (home_anchor(lower+1)-home_anchor(lower))*fraction

def billboard_alpha(focus): return clamp(-focus)
def categories_alpha(focus):
    return .65+.35*clamp(focus+1) if focus < 0 else clamp(1-focus)

@lru_cache(maxsize=40)
def movie_card(movie_id, expanded=False, rank=0, progress=False):
    it=BY_ID[movie_id]; width=437 if expanded else 190
    im=Image.new('RGB',(px(width),px(270)),'#111111')
    c.picture(im,it['backdrop_file'] if expanded else it['poster_file'],(0,0,width,270),8)
    if expanded:
        c.scrim(im,(0,0,width,270),'y',((0,0),(.30,0),(.70,90),(1,230)),8)
        rr(im,(width-63,10,width-10,29),4,'#252525')
        text(im,width-36.5,19.5,'FILM' if it['type']=='movie' else 'SERIES',9,WHITE,True,'mm')
        serif(im,12,211 if not progress else 203,it['title'],17)
        rr(im,(12,242 if not progress else 234,39,256 if not progress else 248),2,'#131313','#999999',.8)
        text(im,25.5,249 if not progress else 241,'16+',9,WHITE,True,'mm')
        text(im,49,249 if not progress else 241,str(it['year'])+'   HD',10,'#CCCCCC',False,'lm')
    if rank:
        # The native Top 10 style uses a 90sp dark number with a silver outline.
        ImageDraw.Draw(im).text((px(6),px(272)),str(rank),font=c.font(90,True),anchor='ls',fill='#141416',stroke_width=3,stroke_fill='#F5F5F5')
        rr(im,(8,8,43,26),3,RED);text(im,25.5,17,'TOP 10',8,WHITE,True,'mm')
    if progress:
        rr(im,(0,266,width,270),1,'#6D6D6D');rr(im,(0,266,width*.43,270),1,RED)
    return im

ROW_NAMES = ('Top 10 Movies & TV Shows in Your Country Today',
             'Award-Winning Cinema & Must-Watch Masterpieces',
             'Critically Acclaimed Peak TV Masterpieces',
             'Popular on Netflix Pro Worldwide')
ROW_ITEMS = (tuple(i['id'] for i in FILMS),
             tuple(i['id'] for i in FILMS[2:]+FILMS[:2]),
             tuple(i['id'] for i in SERIES),tuple(i['id'] for i in CAT))
MY_NAMES = ('Continue Watching for Alex','My List (Alex)','Top Picks Based on Your Activity')
MY_ITEMS = (('interstellar','stranger-things','the-dark-knight'),
            ('the-dark-knight','inception','interstellar'),tuple(i['id'] for i in CAT))

@lru_cache(maxsize=8)
def row_block(title, ids, index=0., focused=False, progress=False):
    im=Image.new('RGB',(W,px(ROW_H)),'#000000')
    x=16
    if focused:rr(im,(x,8,x+3.5,26),2,RED)
    x+=13.5
    top10='Top 10' in title
    if top10:
        rr(im,(x,6,x+62,28),4,RED);text(im,x+31,17,'TOP 10',11,WHITE,True,'mm');x+=72
    text(im,x,4,title,22,WHITE,True)
    if progress:
        bx=x+c.width(title,22,True)+10
        rr(im,(bx,6,bx+39,28),4,'#75202A','#B3363F',1);text(im,bx+19.5,17,'PRO',11,WHITE,True,'mm')
    if focused:
        rr(im,(1187,5,1259,28),10,'#181818','#3D3D3D',1)
        text(im,1223,16.5,f'{round(index)%len(ids)+1} / {len(ids)}',11,'#CCCCCC',True,'mm')
    floor=math.floor(index);fraction=index-floor
    for j in range(floor-1,floor+6):
        if j<floor: xx=16-206*(floor-j)-206*fraction
        elif j==floor: xx=16-206*fraction
        elif j==floor+1:xx=469+(16-469)*fraction
        else:xx=469+206*(j-floor-1)-206*fraction
        if xx>1280 or xx+190<0:continue
        if j==floor and fraction==0:continue
        bit=movie_card(ids[j%len(ids)],False,j%len(ids)+1 if top10 else 0,progress)
        paste_alpha(im,bit,(xx,CARD_TOP),.82 if focused else 1)
    hero=Image.new('RGBA',(px(437),px(270)))
    for j,alpha,dx in [(floor,1-fraction,-437*.35*fraction),
                       (floor+1,fraction,437*.35*(1-fraction))]:
        if alpha<=0:continue
        bit=movie_card(ids[j%len(ids)],True,j%len(ids)+1 if top10 else 0,progress).convert('RGBA')
        bit.putalpha(round(255*alpha))
        hero.alpha_composite(bit,dest=(px(dx),0))
    mask=Image.new('L',hero.size);ImageDraw.Draw(mask).rounded_rectangle((0,0,hero.width-1,hero.height-1),radius=px(8),fill=255)
    hero.putalpha(Image.fromarray(np.minimum(np.asarray(hero.getchannel('A')),np.asarray(mask))))
    paste_alpha(im,hero,(16,CARD_TOP),1 if focused else .85)
    if focused:
        it=BY_ID[ids[round(index)%len(ids)]]
        text(im,16,346,('Series' if it['type']=='tv' else 'Movie')+'   •   '+str(it['year'])+'   •   '+('2h 32m' if it['id']=='the-dark-knight' else 'Feature'),13,WHITE,True)
        rr(im,(238,345,268,362),3,RED);text(im,253,354,'16+',11,WHITE,True,'mm')
        wrapped(im,16,367,it['overview'],810,12,'#D9D9D9',15,2)
    return im

@lru_cache(maxsize=8)
def hero_block(title='blade-runner-2049',focus='Play'):
    old=c.billboard_ui(title,focus)
    return old.crop((0,px(50),W,px(472))).resize((W,px(HERO_H)),Image.Resampling.BICUBIC)

@lru_cache(maxsize=4)
def category_block(index,focused):
    im=Image.new('RGB',(W,px(CATEGORY_H)),'#10141B')
    c.genres(im,24,index,False)
    # Header and counter have the native 24dp separation from the pills.
    rr(im,(0,0,1280,19),0,'#10141B')
    text(im,16,1,'Explore categories',12,'#BEC2CA')
    text(im,1260,1,f'{round(index)%11+1} / 11' if focused else '11 genres',11,'#9096A1',False,'rt')
    return im

@lru_cache(maxsize=6)
def home_screen(focus=-1.,target=-1,index=0.,category_index=0.,hero='blade-runner-2049',action='Play',dropdown=False,tab='Home'):
    is_my=tab=='My Netflix'
    bg=blend_colour('#15212D','#000000',clamp(focus))
    im=c.base(bg)
    viewport=Image.new('RGB',(W,px(720-NAV_H)),bg)
    offset=home_offset(focus) if not is_my else -ROW_H*focus
    if not is_my:
        if billboard_alpha(focus)>.001:paste_alpha(viewport,hero_block(hero,action),(0,offset),billboard_alpha(focus))
        ca=categories_alpha(focus)
        if ca>.001:paste_alpha(viewport,category_block(round(category_index,5),target==0),(0,HERO_H+10+offset),ca)
    names,items=(MY_NAMES,MY_ITEMS) if is_my else (ROW_NAMES,ROW_ITEMS)
    first=0 if is_my else FIRST_ROW
    for r,(title,ids) in enumerate(zip(names,items)):
        level=r if is_my else r+1
        y=first+ROW_H*r+offset
        opacity=row_alpha(level,focus)
        if y+ROW_H<0 or y>720-NAV_H or opacity<=.001:continue
        active=level==target
        value=index if active else (3. if not is_my and r==0 and target>1 else 0.)
        block=row_block(title,ids,round(value,5),active,is_my and r==0)
        paste_alpha(viewport,block,(0,y),opacity)
    im.paste(viewport,(0,px(NAV_H)))
    # This frame is in the stationary viewport, outside all moving row layers.
    first_level=0 if is_my else 1
    if first_level<=target<first_level+len(names):
        rr(im,(16,NAV_H+CARD_TOP,453,NAV_H+CARD_TOP+270),8,None,WHITE,3.5)
    elif not is_my and target==0:
        rr(im,(16,NAV_H+24,228,NAV_H+88),12,None,WHITE,2.5)
    rr(im,(0,0,1280,42),0,bg);c.nav(im,tab)
    if dropdown:
        overlay=c.home_ui(dropdown=True).crop((px(16),px(8),px(276),px(274)))
        im.paste(overlay,(px(16),px(8)))
    return im

@lru_cache(maxsize=6)
def details_backdrop(movie_id):
    it=BY_ID[movie_id];im=c.base('#000000')
    c.picture(im,it['backdrop_file'],(0,0,1280,720))
    c.scrim(im,(0,0,1280,720),'x',((0,250),(.30,240),(.48,214),(.62,148),(.78,64),(1,0)))
    c.scrim(im,(0,0,1280,720),'y',((0,217),(.16,102),(.35,0),(.55,0),(.75,166),(1,250)))
    logo(im,1216,24,24,40)
    return im

@lru_cache(maxsize=16)
def details_layers(movie_id,saved=False,liked=False,focus='Play',resume=False):
    """Geometry from the active details/DetailsScreen.kt and DetailsInfoSection.kt."""
    it=BY_ID[movie_id];tv=it['type']=='tv';panels=[]
    # Row bottom: tabs end at 696; their 16dp padding and 34dp content take 50dp.
    # Left info has 12dp bottom padding, so its action circles end at y=634.
    title_y=403 if resume else 456
    title=layer();logo(title,40,title_y-25,9.5,17);text(title,54.5,title_y-16.5,'S E R I E S' if tv else 'F I L M',14,WHITE,True,'lm')
    serif(title,40,title_y,it['title'],32)
    panels.append(title)
    meta=layer();yy=title_y+47
    value=('Series' if tv else 'Film')+'  •  '+('TV Series • Series' if tv else 'Feature Film • Movie')+'  •  '+str(it['year'])+'  •  '+('4 Seasons' if tv else '2h 32m')+'  •'
    text(meta,40,yy,value,14,'#D9D9D9');xx=40+c.width(value,14)+8
    rr(meta,(xx,yy-1,xx+29,yy+17),3,'#EAB308');text(meta,xx+14.5,yy+8,'16',11,'#000000',True,'mm')
    xx+=40
    for val,w in [('AD)))',36),('CC',24)]:
        rr(meta,(xx,yy-1,xx+w,yy+17),2,None,'#777777',1);text(meta,xx+w/2,yy+8,val,10,'#CCCCCC',True,'mm');xx+=w+10
    panels.append(meta)
    desc=layer();wrapped(desc,40,title_y+77,it['overview'],500,14,'#F2F2F2',20,2)
    if resume:
        rr(desc,(40,530,360,573),6,'#171717');text(desc,50,536,'Resume Watch',14,WHITE,True);text(desc,350,536,'95m remaining',13,'#CCCCCC',False,'rt')
        rr(desc,(50,562,350,565),1,'#484848');rr(desc,(50,562,179,565),1,RED)
    panels.append(desc)
    actions=layer();action_y=586
    pw=110 if resume else 87;rr(actions,(40,action_y+7,40+pw,action_y+41),30,'#FFFFFF')
    icon(actions,'play',58,action_y+14,20,'#000000');text(actions,85,action_y+24,'Resume' if resume else 'Play',14,'#000000',True,'lm')
    if focus=='Play':rr(actions,(36,action_y+4,44+pw,action_y+44),30,None,WHITE,2.5)
    for name,xx,symbol,on in [('list',50+pw,'check' if saved else 'plus',saved),('like',108+pw,'thumb',liked)]:
        chosen=focus==name
        fill=RED if name=='list' and on else ('#FFFFFF' if chosen else ('#460309' if on else None))
        rr(actions,(xx,action_y,xx+48,action_y+48),24,fill,WHITE if chosen else None,2.5)
        icon(actions,symbol,xx+14,action_y+14,20,'#000000' if chosen and not (name=='list' and on) else WHITE)
    panels.append(actions)
    tabs=layer();xx=40
    for n,label in enumerate((['Episodes'] if tv else [])+['Details','More like this','Audio & Subtitles']):
        ww=c.width(label,14,True)+36+(21 if n==0 else 0);sel=focus==label
        if sel:rr(tabs,(xx,656,xx+ww,690),30,'#FFFFFF')
        tx=xx+18
        if n==0:icon(tabs,'down',tx,666,14,'#000000' if sel else WHITE);tx+=21
        text(tabs,tx,673,label,14,'#000000' if sel else '#E6E6E6',True,'lm');xx+=ww+12
    panels.append(tabs)
    cropped=[]
    for panel in panels:
        box=panel.getbbox()
        cropped.append((panel.crop(box),(box[0]/S,box[1]/S)))
    return tuple(cropped)

@lru_cache(maxsize=4)
def trivia_panel():
    im=layer();x=930
    rr(im,(x,448,1240,528),10,'#151B26','#454853',1)
    rr(im,(x+10,464,x+40,494),15,'#30333C');icon(im,'film',x+17,471,16,WHITE)
    wrapped(im,x+50,458,'Want to explore exclusive behind-the-scenes diaries and cast interviews?',248,14,WHITE,19,3,True)
    rr(im,(x,538,1240,626),10,'#151B26','#454853',1)
    rr(im,(x+10,548,x+40,578),15,'#30333C');text(im,x+25,568,'“',29,RED,True,'mm')
    wrapped(im,x+50,548,'A beautiful, gripping story that keeps you hooked from start to finish.',248,14,WHITE,19,3)
    text(im,x+50,608,'— Sample review',13,'#999999',True)
    return im

@lru_cache(maxsize=8)
def details_screen(movie_id='the-dark-knight',saved=False,liked=False,focus='Play',entrance=2.,resume=False):
    im=details_backdrop(movie_id).copy()
    p=ease(entrance/1.235)
    # Native stagger thresholds; title, metadata, synopsis, controls and tabs.
    thresholds=((0,.60),(.21,.36),(.36,.36),(.48,.36),(.57,.39))
    for i,((bit,origin),(start,duration)) in enumerate(zip(details_layers(movie_id,saved,liked,focus,resume),thresholds)):
        a=clamp((p-start)/duration)
        delta=(-40*(1-a),0) if i==4 else (0,16*(1-a))
        paste_alpha(im,bit,(origin[0]+delta[0],origin[1]+delta[1]),a)
    paste_alpha(im,trivia_panel(),opacity=p)
    return im

@lru_cache(maxsize=16)
def episode_card(movie_id,number,expanded=False):
    width=290 if expanded else 230
    im=Image.new('RGB',(px(width),px(150)),'#222222')
    c.picture(im,BY_ID[movie_id]['backdrop_file'],(0,0,width,150),12)
    c.scrim(im,(0,0,width,150),'y',((0,0),(.22,0),(1,217 if expanded else 179)),12)
    rr(im,(12,10,54,27),4,'#191919')
    text(im,18,12,'EP '+str(number),11,WHITE,True)
    text(im,12,120,'Episode '+str(number),16 if expanded else 14,WHITE,True)
    return im

@lru_cache(maxsize=12)
def details_modal(movie_id='the-dark-knight',tab='Details',season=1,index=0.,credits=False):
    it=BY_ID[movie_id];tv=it['type']=='tv';im=c.base('#07070A')
    faint=c.cover(it['backdrop_file'],W,H);im=Image.blend(im,faint,.06)
    rr(im,(40,24,88,72),24,'#303033')
    c.line(im,[(58,51),(64,45),(70,51)],WHITE,2)
    xx=108
    for name in (['Episodes'] if tv else [])+['Details','More like this','Audio & Subtitles','Previews & Extras']:
        ww=c.width(name,16,name==tab)+40
        if name==tab:rr(im,(xx,26,xx+ww,68),20,'#FFFFFF')
        text(im,xx+20,47,name,16,'#000000' if name==tab else '#CCCCCC',name==tab,'lm');xx+=ww+20
    if tab=='Episodes':
        for s in range(1,5):
            xx=40+(s-1)*114;rr(im,(xx,100,xx+104,135),16,'#FFFFFF' if s==season else '#202024')
            text(im,xx+52,117.5,'Season '+str(s),14,'#000000' if s==season else WHITE,s==season,'mm')
        # Native episodes: 230dp posters, 290dp hero, 150dp high, 16dp gap.
        floor=math.floor(index);fraction=index-floor
        strip=Image.new('RGB',(px(1200),px(160)),'#09090C')
        for j in range(max(0,floor-1),floor+6):
            xx=-246*(floor-j)-246*fraction if j<=floor else 306+246*(j-floor-1)-246*fraction
            paste_alpha(strip,episode_card(movie_id,j+1),(xx,0))
        hero=layer(290,150)
        for j,alpha,dx in [(floor,1-fraction,-290*.35*fraction),(floor+1,fraction,290*.35*(1-fraction))]:
            if alpha<=0:continue
            bit=episode_card(movie_id,j+1,True).convert('RGBA');bit.putalpha(round(255*alpha))
            hero.alpha_composite(bit,dest=(px(dx),0))
        mask=Image.new('L',hero.size);ImageDraw.Draw(mask).rounded_rectangle((0,0,hero.width-1,hero.height-1),radius=px(12),fill=255)
        hero.putalpha(Image.fromarray(np.minimum(np.asarray(hero.getchannel('A')),np.asarray(mask))))
        paste_alpha(strip,hero)
        paste_alpha(im,strip,(40,149))
        rr(im,(40,149,330,299),12,None,WHITE,3.5)
    elif tab=='More like this':
        items=[BY_ID['stranger-things']]+[i for i in SERIES+FILMS if i['id']!='stranger-things']
        active=items[0]
        text(im,40,96,active['title'],22,WHITE,True)
        text(im,40,130,'Series • Feature Film • TV • '+str(active['year'])+' • 16+',14,'#B7B7B7',True)
        wrapped(im,40,158,active['overview'],1200,14,'#DDDDDD',20,3)
        text(im,40,589,'You might also like',16,WHITE,True)
        for j,it2 in enumerate(items[:8]):
            xx=40+j*146;c.picture(im,it2['backdrop_file'],(xx,616,xx+130,696),6)
            if j==0:rr(im,(xx-5.2,612.8,xx+135.2,699.2),6,None,WHITE,2)
    elif tab=='Details':
        text(im,40,96,it['title'].upper(),32,WHITE,True)
        xx=40
        for n,label in enumerate(['More Info','Cast & Credits']):
            selected=(n==int(credits));ww=c.width(label,14,selected)+32
            rr(im,(xx,150,xx+ww,183),16,'#464649' if selected else '#1B1B20')
            text(im,xx+16,166.5,label,14,WHITE,selected,'lm');xx+=ww+12
        if credits:
            for n,(label,value) in enumerate([('Cast','Lead Actor, Supporting Actor, Ensemble Cast'),('Director','Featured Director'),('Writers','Original Writers'),('Genres','Feature Film • Movie'),('Moods','Captivating, Entertaining, Polished')]):
                yy=201+n*28;text(im,40,yy,label+':',14,'#888888',True)
                text(im,40+c.width(label+': ',14,True),yy,value,14,'#E6E6E6')
        else:
            text(im,40,201,('Series' if tv else 'Movie')+' • Feature Film • '+str(it['year'])+' • '+('2h 32m' if not tv else 'Series')+' • AD))) CC',15,'#DDDDDD',True)
            wrapped(im,40,237,it['overview'],750,15,'#E6E6E6',22,4)
            quote='“A beautiful, gripping story that keeps you hooked from start to finish.” — Sample review'
            rr(im,(40,303,790,340),20,'#252529');text(im,54,314,quote,13,'#CCCCCC')
    return im

@lru_cache(maxsize=1)
def editorial_background():
    im=c.base('#050507')
    # A small cached gradient is inexpensive and keeps the edit within the app palette.
    strip=Image.new('RGB',(320,180));a=np.zeros((180,320,3),dtype=np.uint8)
    yy,xx=np.mgrid[0:180,0:320]
    glow=np.exp(-((xx-30)**2+(yy-155)**2)/11500.)
    a[:,:,0]=5+glow*32;a[:,:,1]=5+glow*4;a[:,:,2]=8+glow*5
    strip=Image.fromarray(a);im.paste(strip.resize((W,H),Image.Resampling.BILINEAR))
    return im

def fixed_fit(ui,box):
    return ui.resize((round(box[2]-box[0]),round(box[3]-box[1])),Image.Resampling.BICUBIC)

STAGE_CACHE=None
INSET_CACHE=None

def stage(ui,title,subtitle,at=2.,duration=3.):
    global STAGE_CACHE
    p=min(ease(clamp(at/.48)),ease(clamp((duration-at)/.48)))
    cache_key=(title,subtitle)
    if p==1 and STAGE_CACHE and STAGE_CACHE[0] is ui and STAGE_CACHE[1]==cache_key:return STAGE_CACHE[2]
    im=editorial_background().copy()
    logo(im,46,103,38,66);text(im,99,136,'NETFLIX PRO',15,'#AAAAB1',True,'lm')
    # Slide the two lines independently; this is editorial typography outside the app UI.
    for n,value in enumerate(title.split('\n')):
        bit=layer();text(bit,46,243+n*57,value,42,WHITE,True)
        reveal=ease(clamp((at-n*.07)/.45));paste_alpha(im,bit,(-25*(1-reveal),0),reveal)
    wrapped(im,47,243+len(title.split('\n'))*57+21,subtitle,273,17,'#B1B1BA',25,3)
    rr(im,(46,624,94,628),2,RED)
    box=tuple(a+(px(b)-a)*p for a,b in zip((0,0,W,H),(355,121,1255,627)))
    bit=fixed_fit(ui,box);im.paste(bit,(round(box[0]),round(box[1])))
    ImageDraw.Draw(im).rounded_rectangle(box,radius=10,outline='#35353E',width=2)
    if p==1:STAGE_CACHE=(ui,cache_key,im)
    return im

def closeup(ui,source_box,label,at=2.,dest=(831,93,1229,332)):
    """A magnified inset keeps the real full-screen interface in view."""
    global INSET_CACHE
    p=ease(min(1,at/.4));cache_key=(source_box,label,dest)
    if p==1 and INSET_CACHE and INSET_CACHE[0] is ui and INSET_CACHE[1]==cache_key:return INSET_CACHE[2]
    im=ui.copy()
    source=tuple(px(v) for v in source_box)
    box=tuple(px(v) for v in dest)
    bw,bh=box[2]-box[0],box[3]-box[1]
    crop=ui.crop(source).resize((bw,bh),Image.Resampling.BICUBIC)
    mask=Image.new('L',(bw,bh));ImageDraw.Draw(mask).rounded_rectangle((0,0,bw-1,bh-1),radius=px(12),fill=round(255*p))
    xx=box[0]+px(25*(1-p));yy=box[1]
    # Dark backing separates the inset without changing anything inside the UI.
    back=layer();rr(back,(dest[0]-10,dest[1]-10,dest[2]+10,dest[3]+44),16,'#0B0B10','#686872',1)
    paste_alpha(im,back,(25*(1-p),0),p)
    im.paste(crop,(xx,yy),mask)
    if p>.98:
        ImageDraw.Draw(im).rounded_rectangle((xx,yy,xx+bw,yy+bh),radius=px(12),outline=WHITE,width=2)
        text(im,dest[0]+8,dest[3]+24,label,15,WHITE,True,'lm')
        c.line(im,[(source_box[2],(source_box[1]+source_box[3])/2),(dest[0]-10,dest[1]+30)],'#B8B8C1',.8)
    if p==1:INSET_CACHE=(ui,cache_key,im)
    return im

def camera_pan(ui,t,zoom=1.10,cx1=830,cx2=390,cy=360):
    p=ease(clamp(t));cx=cx1+(cx2-cx1)*p
    ww=W/zoom;hh=H/zoom;x=clamp(px(cx)-ww/2,0,W-ww);y=clamp(px(cy)-hh/2,0,H-hh)
    return ui.resize((W,H),Image.Resampling.BICUBIC,box=(x,y,x+ww,y+hh))

def rail_insert(ui,title,at=2.):
    im=editorial_background().copy();p=ease(min(1,at/.5))
    logo(im,48,46,22,39);text(im,88,66,'NETFLIX PRO',12,'#A4A4AF',True,'lm')
    text(im,50,173,title,41,WHITE,True)
    text(im,52,239,'CHOOSE A SEASON  /  FIND YOUR EPISODE',13,'#B9B9C4',True)
    # The true episode strip is 290×150 / 230×150; keep that shape when enlarged.
    strip=ui.crop((px(32),px(91),px(1248),px(311)))
    size=(px(1180),px(214));strip=strip.resize(size,Image.Resampling.BICUBIC)
    paste_alpha(im,strip,(50+60*(1-p),315),p)
    rr(im,(50,583,114,587),2,RED);text(im,50,619,'Your next chapter is one click away.',22,WHITE)
    return im

def modal_reveal(background,modal,p,opening=True):
    progress=ease(clamp(p));progress=progress if opening else 1-progress
    im=background.copy();paste_alpha(im,modal,(0,44*(1-progress)),progress)
    return im

@lru_cache(maxsize=2)
def intro(t):
    # A fast visual hook first, then a short brand statement.
    if t<2.25:
        shot=int(t/.45)
        if shot==0:im=home_screen(-1,-1)
        elif shot==1:im=home_screen(1,1,3.)
        elif shot==2:im=details_screen()
        elif shot==3:im=c.player_ui(.43,'English',False,True)
        else:im=c.profile_ui(0)
        im=im.copy();c.scrim(im,(0,0,1280,720),'y',((0,60),(.4,0),(1,95)))
        text(im,50,647,['DISCOVER','EXPLORE','SAVE','WATCH','MAKE IT YOURS'][min(4,shot)],40,WHITE,True)
        rr(im,(50,621,1230,625),2,'#45454A');rr(im,(50,621,50+1180*(t/2.25),625),2,RED)
        return im
    im=editorial_background().copy();u=t-2.25
    for i,it in enumerate([BY_ID['blade-runner-2049'],BY_ID['interstellar'],BY_ID['stranger-things']]):
        xx=798+i*157+45*(1-ease(clamp(u/.7)))
        c.picture(im,it['poster_file'],(xx,123+i*39,xx+190,593+i*20),10)
    c.scrim(im,(670,0,1280,720),'x',((0,255),(.22,70),(1,30)))
    logo(im,70,97,46,82);text(im,139,139,'NETFLIX PRO',21,WHITE,True,'lm')
    bit=layer();text(bit,67,280,'EVERY STORY.',56,WHITE,True);text(bit,67,345,'ONE REMOTE.',56,WHITE,True)
    paste_alpha(im,bit,(-25*(1-ease(clamp(u/.5))),0),ease(clamp(u/.5)))
    text(im,72,463,'Your world of entertainment. On your TV.',20,'#BCBCC7')
    text(im,73,655,'Code-rendered interface tour · Illustrative titles',10,'#797982')
    return im

def outro(t):
    im=editorial_background().copy();p=ease(clamp(t/.45))
    # Closing typography stays large and resolves quickly.
    logo(im,609,111+24*(1-p),62,112)
    text(im,640,286,'NETFLIX PRO',47,WHITE,True,'mt')
    text(im,640,381,'Your next watch starts here.',34,WHITE,False,'mt')
    rr(im,(559,474,721,514),20,RED);text(im,640,494,'ANDROID TV',13,WHITE,True,'mm')
    text(im,640,654,'Independent app · Interface demonstration',10,'#797982',False,'mt')
    if t>2.6:im=Image.blend(im,c.base('#000000'),ease((t-2.6)/.4))
    return im

def state_frame(kind,t):
    """Faster actions with deliberate reading beats, rather than long zoom holds."""
    if kind=='intro':return intro(min(t,2.95))
    if kind=='outro':return outro(t)
    if kind=='profiles':
        pos,_=spring_track(t,0,[(1.6,1),(3.0,2),(4.4,1),(5.1,0)],550)
        ui=c.profile_motion_ui(round(pos,5))
        if 5.8<=t<8.0:return closeup(ui,(82,126,333,377),'A space for everyone.',t-5.8,dest=(823,131,1193,501))
        if 8<=t<11:return stage(ui,'MAKE IT\nPERSONAL.','Choose a profile and step into your own space.',t-8)
        return ui
    if kind=='home':
        hf,ht=spring_track(t,-2,[(.5,-1)])
        hero='interstellar' if 6.2<=t<10.1 else 'blade-runner-2049'
        action='More Info' if 10.1<t<13.0 else 'Play'
        ui=home_screen(round(hf,5),ht,hero=hero,action=action,dropdown=15.0<=t<17.0)
        if 3<=t<6.0:return stage(ui,'FIND YOUR\nNEXT STORY.','Featured stories. Clear choices. Ready for your remote.',t-3)
        if 6.2<=t<9.5:return camera_pan(ui,(t-6.2)/2.4,1.10,900,385,350)
        if 12<=t<14.6:return closeup(ui,(36,398,286,457),'Play or discover more.',t-12,dest=(833,100,1223,192))
        return ui
    if kind=='genres':
        f,target=spring_track(t,-1,[(.55,0)])
        idx,_=spring_track(t,0,[(2.6,1),(3.7,2),(4.8,3),(6,4),(7.4,6),(9,8),(10.2,6)],550)
        ui=home_screen(round(f,5),target,category_index=round(idx,5))
        if 6<=t<8.8:return stage(ui,'FOLLOW\nYOUR MOOD.','Eleven genres. A different world with every move.',t-6,2.8)
        if 10.8<=t<13.2:return closeup(ui,(10,54,248,144),'Find a genre that fits.',t-10.8,dest=(826,454,1216,602))
        return ui
    if kind=='rows':
        events=[(.55,1),(7.1,2),(8.4,3),(9.8,2),(11.2,1),(16.2,2),(17.4,1)]
        f,target=spring_track(t,0,events)
        ix,_=spring_track(t,0,[(2.1,1),(3.4,2),(4.8,3),(13.2,2),(14.5,3)],550)
        if target!=1:ix=0.
        ui=home_screen(round(f,5),target,round(ix,5),6.)
        if 5.7<=t<7.1:return closeup(ui,(10,93,459,376),'The story stays in focus.',t-5.7,dest=(779,399,1238,688))
        if 12<=t<15.6:return stage(ui,'ALWAYS\nIN FOCUS.','Glide between titles and rows, with one steady frame.',t-12,3.6)
        return ui
    if kind=='details':
        focus='Play' if t<7 else 'list' if t<10.5 else 'like' if t<13.8 else 'Details'
        ui=details_screen(saved=t>=7.8,liked=t>=11.1,focus=focus,entrance=round(min(t,1.235),5))
        if 3<=t<5.5:return stage(ui,'GO INSIDE\nTHE STORY.','The details you need before you press play.',t-3,2.5)
        if 7.8<=t<10.4:return closeup(ui,(127,576,254,643),'Saved to My List.',t-7.8,dest=(825,112,1184,301))
        if 11.4<=t<13.8:return closeup(ui,(921,441,1248,633),'More context. More to discover.',t-11.4,dest=(66,98,571,395))
        if t>=14.4:
            modal=details_modal(tab='Details' if t<19 else 'More like this',credits=17<=t<19)
            return modal_reveal(ui,modal,(t-14.4)/.48) if t<14.88 else modal
        return ui
    if kind=='episodes':
        ui=details_screen('stranger-things',focus='Play' if t<3 else 'Episodes',entrance=round(min(t,1.235),5))
        if t<4:return ui
        ix,_=spring_track(t,0,[(9.6,1),(10.8,2),(12,3),(16.8,2)],1000)
        modal=details_modal('stranger-things','Episodes',1 if t<6.2 else 2,round(ix,5))
        if t<4.48:return modal_reveal(ui,modal,(t-4)/.48)
        if 13<=t<16.4:return rail_insert(modal,'ONE MORE EPISODE.',t-13)
        if t>=18.6:return modal_reveal(ui,modal,(t-18.6)/.48,False)
        return modal
    if kind=='search':
        n=0 if t<1.4 else min(4,int((t-1.4)/.52)+1)
        query='dark'[:n];key='dark'[max(0,n-1)] if 1.4<t<4.0 else ''
        ui=c.search_ui(query,key,t>=6.8)
        if 2.5<=t<5.3:return closeup(ui,(34,61,277,379),'A few letters. Your next watch.',t-2.5,dest=(856,135,1201,586))
        if 9<=t<12.2:return stage(ui,'FIND YOUR\nNEXT OBSESSION.','Search the catalogue from the comfort of your sofa.',t-9,3.2)
        if 13<=t<15:return closeup(ui,(295,103,526,442),'Straight to the story.',t-13,dest=(855,131,1190,623))
        return ui
    if kind=='my':
        f,target=spring_track(t,0,[(5,1),(7.4,2),(9.2,1),(12,0)])
        ix,_=spring_track(t,0,[(5.7,1),(6.5,0)],550)
        ui=home_screen(round(f,5),target,round(ix,5) if target==1 else 0.,tab='My Netflix')
        if t<2.4:return stage(ui,'RIGHT\nWHERE YOU\nLEFT OFF.','Continue Watching and My List, together in My Netflix.',t,2.4)
        if 2.8<=t<4.8:return closeup(ui,(10,335,460,375),'Ready to resume.',t-2.8,dest=(786,426,1234,466))
        if 9.8<=t<11.8:return closeup(ui,(10,91,459,376),'Your saved favourites.',t-9.8,dest=(816,364,1241,634))
        return ui
    if kind=='player':
        progress=c.tween_keys(t,[(0,.38),(2,.38),(2.5,.395),(3.1,.415),(4.1,.43),(17,.431)])
        ui=c.player_ui(round(progress,4),'English' if t>=6 else 'Off',2<t<4.6,9<=t<12.2,t<14)
        if 6.1<=t<8.7:return closeup(ui,(59,670,763,712),'English selected. Just English.',t-6.1,dest=(565,103,1235,143))
        if 12.5<=t<14:return stage(ui,'YOUR LANGUAGE.\nYOUR MOMENT.','Choose subtitles and audio, then settle into the story.',t-12.5,1.5)
        return ui
    if kind=='edit':
        ui=c.edit_ui(0 if t<1.1 else 4,False) if t<3 else c.edit_ui(7,True) if t<7.5 else c.edit_ui(6,False)
        if 1.4<=t<3:return stage(ui,'MAKE IT\nYOUR OWN.','Name, icon, language and profile settings.',t-1.4,1.6)
        return ui
    raise ValueError(kind)

@lru_cache(maxsize=2)
def scene_tail(index):
    return state_frame(SCENES[index][2],SCENES[index][1]-SCENES[index][0]-1/FPS)

def transition(previous,current,progress,kind):
    p=ease(clamp(progress));im=c.base('#000000')
    if kind=='push':
        im.paste(previous,(-round(W*p),0));im.paste(current,(round(W*(1-p)),0))
    elif kind=='stripes':
        im=previous.copy()
        for n in range(3):
            q=ease(clamp(progress*1.35-n*.16));x=round(n*W/3);end=round((n+1)*W/3);hh=round(H*q)
            if hh:im.paste(current.crop((x,0,end,hh)),(x,0))
            if 0<q<1:ImageDraw.Draw(im).rectangle((x,max(0,hh-7),end,hh),fill=RED)
    elif kind=='aperture':
        im=previous.copy();x=px(96)*(1-p);y=px(148)*(1-p)
        x2=px(172)+(W-px(172))*p;y2=px(224)+(H-px(224))*p
        mask=Image.new('L',(W,H));ImageDraw.Draw(mask).rounded_rectangle((x,y,x2,y2),radius=px(8)*(1-p),fill=255)
        im.paste(current,(0,0),mask)
    elif kind in ('card','poster','play'):
        original=(16,NAV_H+CARD_TOP,453,NAV_H+CARD_TOP+270) if kind!='poster' else (40,616,170,696)
        source='the-dark-knight' if kind=='card' else 'stranger-things' if kind=='poster' else 'interstellar'
        box=tuple(px(v)*(1-p)+full*p for v,full in zip(original,(0,0,W,H)))
        im=Image.blend(previous,c.base('#000000'),p*.9)
        bit=c.cover(BY_ID[source]['backdrop_file'],round(box[2]-box[0]),round(box[3]-box[1]))
        im.paste(bit,(round(box[0]),round(box[1])))
        if p>.58:im=Image.blend(im,current,ease((p-.58)/.42))
    else:im=Image.blend(previous,current,p)
    return im

TRANSITIONS={1:('stripes',.48),2:('aperture',.48),5:('card',.60),6:('poster',.55),
             7:('push',.42),8:('push',.42),9:('play',.55),10:('stripes',.46),11:('stripes',.42)}

def frame(t):
    index=min(len(SCENES)-1,max(0,bisect.bisect_right(STARTS,t)-1));local=t-SCENES[index][0]
    im=state_frame(SCENES[index][2],local)
    effect=TRANSITIONS.get(index)
    if effect and local<effect[1]:im=transition(scene_tail(index-1),im,local/effect[1],effect[0])
    return im

def clear_memory():
    global STAGE_CACHE,INSET_CACHE
    STAGE_CACHE=INSET_CACHE=None
    c.release_scene_memory()
    for value in list(globals().values()):
        clear=getattr(value,'cache_clear',None)
        if callable(clear):clear()
    gc.collect()

def paced_pause(cpu_begin,wall_begin):
    spent=time.process_time()-cpu_begin
    time.sleep(max(.025,spent/.35-(time.monotonic()-wall_begin)))

def build_soundtrack():
    dest=OUT/'original-soundtrack.wav'
    if dest.exists():return
    print('Preparing a more rhythmic original soundtrack at low CPU load.',flush=True)
    sr=48000;beat=60/112;chords=[(45,[57,60,64,67]),(41,[53,57,60,64]),(48,[55,60,64,67]),(43,[55,59,62,69])]
    cue_times=[s[0] for s in SCENES[1:]]+[7.6,9,10.4,11.1,39.6,40.7,41.8,44.4,55.1,56.4,57.8,60.1,61.4,62.8,64.2,82.8,86.1,89.4,102,104.2,107.6,108.8,110,120.4,120.92,121.44,121.96,141,143.4,148,153,154.1,157,160,171]
    raw=OUT/'soundtrack.unscaled.wav';peak=0.;cool_at=time.monotonic()
    rng=np.random.default_rng(8122)
    def hz(note):return 440*2**((note-69)/12)
    with wave.open(str(raw),'wb') as audio:
        audio.setnchannels(2);audio.setsampwidth(2);audio.setframerate(sr)
        for second in range(180):
            cb=time.process_time();wb=time.monotonic();tt=second+np.arange(sr)/sr
            sig=np.zeros((sr,2),np.float32)
            for chord_no,(bass,notes) in enumerate(chords):
                bar_index=np.floor(tt/(8*beat)).astype(int)
                active=(bar_index%4)==chord_no;phase=tt%(8*beat)
                env=np.minimum(1,phase/.14)*np.minimum(1,(8*beat-phase)/.28)*active
                for n,note in enumerate(notes):
                    pad=(np.sin(2*np.pi*hz(note)*tt)+.2*np.sin(2*np.pi*hz(note)*1.003*tt))*env*.026
                    sig[:,0]+=pad*(.65 if n%2 else .9);sig[:,1]+=pad*(.9 if n%2 else .65)
                sig[:,0]+=np.sin(2*np.pi*hz(bass)*tt)*env*.031
                sig[:,1]+=np.sin(2*np.pi*hz(bass)*tt)*env*.031
            for b in range(max(0,int(second/beat)-3),int((second+1)/beat)+1):
                age=tt-b*beat;gate=(age>=0)&(age<1.5);a=np.maximum(0,age)
                chord=chords[(b//8)%4];note=chord[1][b%4]+12
                pulse=np.sin(2*np.pi*(43*a+4*(1-np.exp(-a*30))))*np.exp(-a*16)*gate*.09
                pluck=np.sin(2*np.pi*hz(note)*a)*np.exp(-a*5.8)*np.minimum(1,a/.009)*gate*.042
                sig[:,0]+=pulse+pluck*(.7 if b%2 else 1)
                sig[:,1]+=pulse+pluck*(1 if b%2 else .7)
                if b%2:
                    noise=rng.normal(0,1,sr)*np.exp(-a*85)*gate*.007
                    sig[:,0]+=noise;sig[:,1]+=noise
            for at in cue_times:
                if at<second-.20 or at>second+1:continue
                a=np.maximum(0,tt-at);g=(tt>=at)&(a<.16)
                tick=(np.sin(2*np.pi*980*a)+.22*np.sin(2*np.pi*1470*a))*np.exp(-a*54)*np.minimum(1,a/.005)*g*.055
                sig+=tick[:,None]
            sig*=np.minimum(1,tt/.45)[:,None]*np.minimum(1,(180-tt)/1.5)[:,None]
            peak=max(peak,float(np.max(np.abs(sig))))
            audio.writeframesraw((np.clip(sig,-1,1)*32767).astype('<i2').tobytes())
            paced_pause(cb,wb)
            if time.monotonic()-cool_at>25:time.sleep(6);cool_at=time.monotonic()
    gain=min(3.,.62/max(.001,peak))
    temp=dest.with_suffix('.rendering.wav')
    with wave.open(str(raw),'rb') as r,wave.open(str(temp),'wb') as w:
        w.setparams(r.getparams())
        while True:
            cb=time.process_time();wb=time.monotonic();data=r.readframes(sr)
            if not data:break
            samples=np.frombuffer(data,dtype='<i2').astype(np.float32)*gain
            w.writeframesraw(np.clip(samples,-32768,32767).astype('<i2').tobytes());paced_pause(cb,wb)
    temp.replace(dest)
    print(f'Soundtrack ready: 180s stereo, peak {20*math.log10(peak*gain):.1f} dBFS.',flush=True)

def write_notes():
    notes={
        'active_details_route':'MainActivity.kt:42 imports com.example.ui.screens.details.DetailsScreen',
        'sources':['HomeScreen.kt:1204 computeRowAlpha','HomeMotion.kt','components/NetflixMovieRow.kt','details/DetailsScreen.kt','details/DetailsInfoSection.kt','details/DetailsModalOverlay.kt','details/DetailsEpisodesSection.kt','util/TvMotion.kt'],
        'native_geometry_dp':{'viewport_top':NAV_H,'billboard':HERO_H,'categories':CATEGORY_H,'row':ROW_H,'portrait':[190,270],'expanded':[437,270],'row_ring':[16,CARD_TOP,437,270],'episode_base':[230,150],'episode_expanded':[290,150]},
        'row_alpha_knots':{'-1':0,'0':1,'1':.85,'2':.65,'3':.45,'4':.25},
        'motion':{'damping':1,'duration_scale':1.3,'vertical_base_stiffness':430,'horizontal_base_stiffness':550,'hero_slide_fraction':.35},
        'edit_changes':['Short visual hook','Faster remote interactions','Full-screen native browsing','Editorial split compositions','Magnified insets that preserve the full UI','Lateral camera moves','Selected-card transitions into details/playback','Directional page transitions','Short branded strip reveals'],
        'limitations':'Code-rendered UI demonstration with illustrative catalogue data; not an APK recording. Georgia provides the generic serif fallback on Windows. Review copy is labelled as sample text.'
    }
    (OUT/'Source-Fidelity.json').write_text(json.dumps(notes,indent=2),encoding='utf-8')
    (OUT/'README.md').write_text('''# Netflix Pro — revision 2

The revised 180-second film uses faster actions, full-screen app views, split compositions, detail insets, pans and transitions motivated by the selected card. The original film is preserved.

The Home reconstruction follows the shared animated focus position in HomeMotion.kt, computeRowAlpha in HomeScreen.kt, the fixed viewport ring, the 395dp row interval and the 35% horizontal hero slide. The invented title area above browsing rows has been removed.

Details follows the implementation imported by MainActivity: bottom-aligned content, native control sizes, yellow rating badge, generic serif title fallback, transparent bottom tabs and the two right-hand information cards. Episodes uses the real full-screen modal and its 230/290 × 150dp cards.

Source-Fidelity.json records the source mappings and geometry. Catalogue data is illustrative. This is a code-rendered demonstration, with Windows Georgia standing in for Android's generic serif fallback. Review copy is labelled as sample text. No Android build is performed.

Rendering remains sequential, with one encoder thread, Windows idle priority, CPU pacing and cooling pauses. Revision caches are keyed to both renderer files, so stale chapters cannot enter a changed edit.

Run `python ../render_video_v2.py --storyboard-only` for still checks, `--preview` for the short motion review, or omit arguments for the full film.
''',encoding='utf-8')

def fidelity_checks():
    assert all(abs(row_alpha(1,1-d)-a)<1e-8 for d,a in [(-1,0),(0,1),(1,.85),(2,.65),(3,.45),(4,.25)])
    assert (billboard_alpha(-1),billboard_alpha(0),categories_alpha(0),categories_alpha(1))==(1,0,1,0)
    assert home_offset(-.5)==-221 and home_offset(.5)==-490 and home_offset(1.5)==-735.5
    x0,_=spring_track(1-1e-6,0,[(0,1),(1,2)])
    x1,_=spring_track(1+1e-6,0,[(0,1),(1,2)])
    assert abs(x0-x1)<.0001
    assert all(SCENES[i][1]==SCENES[i+1][0] for i in range(len(SCENES)-1))
    assert SCENES[-1][1]==180
    print('Source geometry, opacity anchors, continuous motion and timeline checked.',flush=True)

def storyboard():
    times=[3.9,9,22.5,40,61.7,76.5,83.5,105,122,141.7,158,172]
    sheet=Image.new('RGB',(1920,1440),'#000000')
    for n,t in enumerate(times):
        cb=time.process_time();wb=time.monotonic()
        im=frame(t);bit=im.resize((624,351),Image.Resampling.LANCZOS)
        sheet.paste(bit,(16+(n%3)*640,12+(n//3)*360))
        paced_pause(cb,wb)
    sheet.save(OUT/'Storyboard-v2.jpg',quality=94)
    for name,t in [('Rows-v2',65),('Details-v2',77),('Episodes-v2',105),('Inset-v2',83.5),('Cover-v2',3.9)]:
        cb=time.process_time();wb=time.monotonic();frame(t).save(OUT/(name+'.jpg'),quality=95);paced_pause(cb,wb)
    clear_memory()
    print('Saved revision stills.',flush=True)

def setup():
    OUT.mkdir(exist_ok=True);(OUT/'.render-cache').mkdir(exist_ok=True)
    c.ROOT=OUT;c.SCENES=SCENES;c.STARTS=STARTS;c.frame=frame;c.FPS=FPS
    c.set_idle_priority();c.metadata();write_notes();fidelity_checks()

def render_preview():
    build_soundtrack();c.FPS=24
    spans=[(37,39),(59,63),(75,79),(89,92),(97.7,99.7),(107.3,110.3)]
    times=[a+k/24 for a,b in spans for k in range(round((b-a)*24))]
    picture=OUT/'.render-cache/review-picture.mp4'
    c.encode_frames(picture,times,OUT/'.render-cache/review.log')
    c.mux_video([picture],OUT/'Review-18s-v2.mp4',18,False)
    c.FPS=FPS;clear_memory()

def render_full():
    build_soundtrack()
    digest=hashlib.sha256(Path(__file__).read_bytes()+(ROOT/'render_video.py').read_bytes()).hexdigest()[:12]
    cache=OUT/'.render-cache'/digest;cache.mkdir(exist_ok=True)
    print('Revision 2: sequential, single thread, idle priority, paced CPU and cooling breaks.',flush=True)
    files=[]
    for i,(start,end,kind,*_) in enumerate(SCENES):
        target=cache/f'{i:02d}-{kind}.mp4';total=(end-start)*FPS
        info=c.mp4_video_info(target) if target.exists() else None
        if info and info['frames']==total and abs(info['duration']-(end-start))<.01:
            print('Reusing verified '+kind,flush=True)
        else:
            c.encode_frames(target,[start+k/FPS for k in range(total)],cache/f'{i:02d}.log')
            clear_memory()
        files.append(target)
        print(f'Completed {len(files)}/12 revision chapters.',flush=True)
        if i<11:time.sleep(10)
    c.mux_video(files,FINAL,180)
    print('Revision complete: '+str(FINAL),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--storyboard-only',action='store_true')
    parser.add_argument('--preview',action='store_true')
    args=parser.parse_args();setup()
    if args.storyboard_only:storyboard()
    elif args.preview:render_preview()
    else:render_full()
