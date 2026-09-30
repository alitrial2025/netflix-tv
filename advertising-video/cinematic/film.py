#!/usr/bin/env python3
"""NetflixPro: one cinematic journey. Native 4K composition, staged source UI.

Python controls the camera, perspective planes, particles, shot timing and score.
FFmpeg encodes separate resumable sequences and the final delivery. No paid assets,
provider tokens or passwords are included. UI playback is a staged demonstration.
"""
from pathlib import Path
from functools import lru_cache
import argparse, bisect, hashlib, json, math, os, shutil, subprocess, sys, time, wave
import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE=Path(__file__).resolve().parent
TV=HERE.parents[1]; MOBILE=TV.parent/'netflix-mobile'
WORK=Path(os.environ.get('NETFLIXPRO_FILM_WORK','/workspace/artifacts/netflixpro-cinematic'))
A=WORK/'assets'; OUT=WORK/'output'; FPS=30; DURATION=180
W,H=3840,2160; SCALE=1.0
FONTS=Path('/usr/share/fonts/truetype/open-sans')
cv2.setNumThreads(int(os.environ.get('NETFLIXPRO_FILM_THREADS','2')))
SCENES=[
 (0,12,'tv_reveal'),(12,25,'tv_screen_dive'),(25,43,'home_discovery'),
 (43,58,'content_tunnel'),(58,64,'movie_details'),(64,73,'player'),
 (73,84,'tv_pullout'),(84,98,'phone_reveal'),(98,116,'mobile_ui'),
 (116,130,'mobile_player'),(130,144,'features'),(144,159,'ecosystem'),
 (159,170,'hero_montage'),(170,180,'logo_finale')]
STARTS=[s[0] for s in SCENES]

def clamp(x,a=0.,b=1.): return max(a,min(b,float(x)))
def ease(x):
 x=clamp(x); return x*x*x*(10+x*(-15+6*x))
def mix(a,b,p): return a+(b-a)*p
def event(t,start,length=1.): return ease((t-start)/length)
def window(t,start,end,ramp=.7): return event(t,start,ramp)*(1-event(t,end-ramp,ramp))
def dims(w,h): return round(w*SCALE),round(h*SCALE)
def point(x,y): return round(x*SCALE),round(y*SCALE)
@lru_cache(maxsize=90)
def font(size,bold=False):
 return ImageFont.truetype(str(FONTS/('OpenSans-Semibold.ttf' if bold else 'OpenSans-Light.ttf')),max(1,round(size*SCALE)))

def read(path,alpha=False):
 with Image.open(path) as im: return np.array(im.convert('RGBA' if alpha else 'RGB'))
@lru_cache(maxsize=45)
def asset(group,name): return read(A/group/(name+'.png'),True)
@lru_cache(maxsize=30)
def poster(i):
 files=sorted((A/'posters').glob('*-poster.jpg')); return read(files[i%len(files)],True)
@lru_cache(maxsize=16)
def title_layer(value,size=86,tracking=0,bold=False,color=(242,243,247)):
 f=font(size,bold); width=round(sum(f.getlength(c) for c in value)+max(0,len(value)-1)*tracking*SCALE)
 im=Image.new('RGBA',(max(2,width+6),round(size*SCALE*1.7)))
 d=ImageDraw.Draw(im); x=3.
 for char in value:
  d.text((x,0),char,font=f,fill=(*color,255)); x+=f.getlength(char)+tracking*SCALE
 return np.array(im)

def over(dst,src,x,y,opacity=1):
 x,y=int(x),int(y); hh,ww=src.shape[:2]
 left,top=max(0,x),max(0,y); right,bottom=min(dst.shape[1],x+ww),min(dst.shape[0],y+hh)
 if right<=left or bottom<=top or opacity<=0: return
 bit=src[top-y:bottom-y,left-x:right-x]; roi=dst[top:bottom,left:right]
 if opacity>=.999 and (bit.shape[2]==3 or bit[:,:,3].min()==255): roi[:]=bit[:,:,:3]; return
 if bit.shape[2]==4 and opacity>=.999:
  mask=bit[:,:,3]
  if roi.strides[-2]==3:
   cv2.copyTo(np.ascontiguousarray(bit[:,:,:3]),np.uint8(mask==255),roi)
  else:
   opaque_roi=np.ascontiguousarray(roi);cv2.copyTo(np.ascontiguousarray(bit[:,:,:3]),np.uint8(mask==255),opaque_roi);roi[:]=opaque_roi
  partial=(mask>0)&(mask<255)
  if np.any(partial):
   alpha=mask[partial,None].astype(np.float32)/255
   roi[partial]=np.uint8(bit[:,:,:3][partial]*alpha+roi[partial]*(1-alpha))
  return
 alpha=(bit[:,:,3:4].astype(np.float32)*(opacity/255)) if bit.shape[2]==4 else float(opacity)
 roi[:]=np.clip(bit[:,:,:3]*alpha+roi*(1-alpha),0,255).astype(np.uint8)


def caption(dst,value,x=240,y=230,size=86,alpha=1,tracking=0,bold=False,center=False,color=(242,243,247)):
 bit=title_layer(value,size,tracking,bold,color); px,py=point(x,y)
 if center: px-=bit.shape[1]//2
 over(dst,bit,px,py,alpha)

def headline(dst,lines,at,start=0,end=7,x=240,y=310,size=90,support=None):
 opacity=window(at,start,end,.85)
 if opacity<.001: return
 shift=28*(1-event(at,start,.9))
 for i,line in enumerate(lines.split('\n')): caption(dst,line,x,y+shift+i*size*1.23,size,opacity,0)
 if support: caption(dst,support,x,y+shift+(i+1)*size*1.23+44,30,opacity,1.2,color=(170,175,188))

def quad(cx,cy,width,height,yaw=0,pitch=0,roll=0,z=0):
 """Camera-space rotation followed by a genuine perspective projection."""
 yaw,pitch,roll=np.radians([yaw,pitch,roll]); cyaw,sy=math.cos(yaw),math.sin(yaw); cp,sp=math.cos(pitch),math.sin(pitch); cr,sr=math.cos(roll),math.sin(roll)
 ry=np.array([[cyaw,0,sy],[0,1,0],[-sy,0,cyaw]])
 rx=np.array([[1,0,0],[0,cp,-sp],[0,sp,cp]])
 rz=np.array([[cr,-sr,0],[sr,cr,0],[0,0,1]])
 vertices=np.array([[-width/2,-height/2,0],[width/2,-height/2,0],[width/2,height/2,0],[-width/2,height/2,0]])@(rz@rx@ry).T
 denom=1+(vertices[:,2]+z)/(3840*1.5)
 q=vertices[:,:2]/denom[:,None]+[cx,cy]
 return np.asarray(q*SCALE,np.float32)

def project(dst,texture,q,opacity=1,blur=0):
 """Bound perspective rasterization to the visible ROI rather than a 4K layer."""
 x0=max(0,int(np.min(q[:,0]))-3); y0=max(0,int(np.min(q[:,1]))-3)
 x1=min(dst.shape[1],int(np.max(q[:,0]))+4); y1=min(dst.shape[0],int(np.max(q[:,1]))+4)
 if x1<=x0 or y1<=y0: return
 hh,ww=texture.shape[:2]
 src=np.float32([[0,0],[ww-1,0],[ww-1,hh-1],[0,hh-1]])
 mat=cv2.getPerspectiveTransform(src,np.float32(q-[x0,y0]))
 bit=cv2.warpPerspective(texture,mat,(x1-x0,y1-y0),flags=cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
 if blur: bit=cv2.GaussianBlur(bit,(0,0),max(.1,blur*SCALE))
 over(dst,bit,x0,y0,opacity)

def poly(dst,pts,color,width=1):
 pts=np.asarray(pts,np.int32)
 cv2.polylines(dst,[pts],False,color,max(1,round(width*SCALE)),cv2.LINE_AA)

@lru_cache(maxsize=8)
def stage(theme='red'):
 ww,hh=dims(960,540); y,x=np.mgrid[0:hh,0:ww]; xx=x/max(1,ww); yy=y/max(1,hh)
 red=np.exp(-((xx-.22)**2/.12+(yy-.78)**2/.32))
 blue=np.exp(-((xx-.87)**2/.17+(yy-.28)**2/.4))
 arr=np.zeros((hh,ww,3),np.float32)+[3,4,7]
 arr+=red[:,:,None]*np.array([26,2,4])+blue[:,:,None]*np.array([1,7,15])
 if theme=='city':
  rng=np.random.default_rng(2026)
  for _ in range(75):
   bx=int(rng.uniform(.03,.98)*ww); by=int(rng.uniform(.15,.7)*hh); r=int(rng.uniform(2,11)*SCALE)
   cv2.circle(arr,(bx,by),max(1,r),(25,18,37),-1,cv2.LINE_AA)
  arr=cv2.GaussianBlur(arr,(0,0),4)
 if theme=='travel': arr+=np.exp(-((xx-.68)**2/.11+(yy-.7)**2/.32))[:,:,None]*np.array([4,13,15])
 # Restrained floor illumination, giving the objects a shared spatial anchor.
 horizon=.85; floor=np.maximum(0,(yy-horizon)/(1-horizon))
 arr+=floor[:,:,None]*np.array([8,7,8])
 vignette=np.clip(1-.48*((xx-.5)**2+(yy-.5)**2),.65,1)
 arr*=vignette[:,:,None]
 return cv2.resize(np.uint8(np.clip(arr,0,255)),(W,H),interpolation=cv2.INTER_LINEAR)

@lru_cache(maxsize=6)
def shell(kind,back=False):
 ww,hh=(1520,880) if kind=='tv' else (780,1650)
 arr=np.zeros((hh,ww,4),np.uint8)
 y,x=np.mgrid[0:hh,0:ww]
 edge=np.maximum(np.exp(-x/10)+np.exp(-(ww-1-x)/10),np.exp(-y/12)+np.exp(-(hh-1-y)/12))
 gloss=5+edge*50+np.exp(-((x-ww*.77)/(ww*.22))**2)*5
 arr[:,:,:3]=np.stack([gloss*.85,gloss*.9,gloss],-1).astype(np.uint8)
 im=Image.fromarray(arr); d=ImageDraw.Draw(im)
 # Bevel bands, a red key light and a cold rim; these are behind the OLED plane.
 mask=Image.new('L',(ww,hh)); ImageDraw.Draw(mask).rounded_rectangle((0,0,ww-1,hh-1),radius=16 if kind=='tv' else 95,fill=255)
 im.putalpha(mask)
 d=ImageDraw.Draw(im)
 d.rounded_rectangle((2,2,ww-3,hh-3),radius=14 if kind=='tv' else 94,outline=(94,96,105,255),width=2)
 d.line([(8,hh*.3),(8,hh*.78)],fill=(117,17,25,255),width=3)
 d.line([(ww-9,hh*.12),(ww-9,hh*.54)],fill=(43,72,87,255),width=3)
 if back:
  d.rounded_rectangle((18,18,ww-18,hh-18),radius=16 if kind=='tv' else 87,fill=(9,10,14,255),outline=(49,53,59,255),width=2)
 return np.array(im)

@lru_cache(maxsize=4)
def mask_texture(kind):
 ww,hh=(3840,2160) if kind=='tv' else (824,1790)
 im=Image.new('L',(ww,hh)); ImageDraw.Draw(im).rounded_rectangle((0,0,ww-1,hh-1),radius=14 if kind=='tv' else 65,fill=255)
 return np.array(im)

@lru_cache(maxsize=6)
def chassis_surface(kind):
 ww,hh=(1970,1129) if kind=='tv' else (876,1849)
 return cv2.resize(shell(kind),(ww,hh),interpolation=cv2.INTER_CUBIC)

def device(dst,screen,cx,cy,width,kind='tv',yaw=0,pitch=0,roll=0,opacity=1,reflect=True):
 ratio=16/9 if kind=='tv' else 824/1790
 height=width/ratio; bw=width*1.026 if kind=='tv' else width*1.063; bh=height*1.045 if kind=='tv' else height*1.033
 q=quad(cx,cy,bw,bh,yaw,pitch,roll)
 thickness=(8 if kind=='tv' else 14)*math.sin(math.radians(yaw))
 if abs(yaw)>10:
  project(dst,shell(kind,True),quad(cx+thickness,cy+4,bw,bh,yaw,pitch,roll),opacity)
 body=chassis_surface(kind).copy(); th,tw=body.shape[:2]
 if screen is not None and math.cos(math.radians(yaw))>0:
  sw,sh=round(tw*width/bw),round(th*height/bh); ix,iy=(tw-sw)//2,(th-sh)//2
  bit=cv2.resize(screen,(sw,sh),interpolation=cv2.INTER_LINEAR)
  if kind=='phone':
   bit=bit.copy();bit[:,:,3]=np.minimum(bit[:,:,3],cv2.resize(mask_texture('phone'),(sw,sh)))
  body_rgb=np.ascontiguousarray(body[:,:,:3]);over(body_rgb,bit,ix,iy);body[:,:,:3]=body_rgb
  if kind=='phone':
   cv2.rectangle(body,(tw//2-round(sw*.07),iy+round(sh*.017)),(tw//2+round(sw*.07),iy+round(sh*.022)),(5,6,8,255),-1)
 project(dst,body,q,opacity)
 if kind=='tv':
  for side in [-1,1]:
   foot=np.array([[side*width*.32,-height*.03],[side*width*.35,height*.035],[side*width*.43,height*.035]],np.float32)
   foot[:,0]+=cx;foot[:,1]+=cy+bh*.5;poly(dst,foot*SCALE,(48,49,54),8)
 if screen is not None and reflect and height<1900 and 400<width<3600:
  ref=cv2.resize(screen,(max(1,screen.shape[1]//6),max(1,screen.shape[0]//6)),interpolation=cv2.INTER_AREA)[::-1].copy()
  ref[:,:,3]=np.uint8(ref[:,:,3]*np.linspace(.065,0,len(ref))[:,None])
  qr=quad(cx,cy+height*.86+60,width,height*.62,-yaw,-pitch,-roll);project(dst,ref,qr,opacity)
 return q

@lru_cache(maxsize=32)
def black_screen(kind='tv'):
 ww,hh=(1920,1080) if kind=='tv' else (824,1790)
 return np.full((hh,ww,4),(1,2,4,255),np.uint8)

@lru_cache(maxsize=32)
def logo_screen(kind='tv',width=.42):
 im=Image.fromarray(black_screen(kind).copy()); logo=Image.open(A/'branding/wordmark.png').convert('RGBA')
 ww=round(im.width*width); hh=round(ww/logo.width*logo.height)
 logo=logo.resize((ww,hh),Image.Resampling.LANCZOS); im.alpha_composite(logo,((im.width-ww)//2,(im.height-hh)//2))
 return np.array(im)

RNG=np.random.default_rng(91026)
PARTICLES=RNG.random((110,5))
def particles(dst,t,amount=1):
 if amount<=0:return
 for x,y,z,s,phase in PARTICLES:
  px=(x*W+math.sin(t*.2+phase*12)*35*SCALE)%W; py=(y*H-t*(8+z*15)*SCALE)%H
  glow=(.2+.8*(.5+.5*math.sin(t*.8+phase*30)))*amount
  cv2.circle(dst,(round(px),round(py)),max(1,round((.5+s)*SCALE)),(round(80*glow),round(8*glow),round(14*glow)),-1,cv2.LINE_AA)

def light_path(dst,t,start=0,end=1,offset=0):
 points=[]
 for u in np.linspace(start,end,140):
  x=300+3240*u; y=1160+250*math.sin(u*math.pi*2+t*.35+offset)
  points.append(point(x,y))
 poly(dst,points,(90,7,22),3); poly(dst,points,(243,71,84),1)

def ui_plane(dst,screen,p=0,cx=1920,cy=1080,width=3900,yaw=0,pitch=0,roll=0,opacity=1):
 project(dst,screen,quad(cx,cy,width,width*screen.shape[0]/screen.shape[1],yaw,pitch,roll),opacity)

def native_mobile(name): return native_clip('native-mobile',name,0)
def tvscreen(name): return native_clip('native-tv',name,0)

def zoom_screen(screen,cx,cy,zoom):
 ww,hh=screen.shape[1],screen.shape[0]; bw,bh=ww/zoom,hh/zoom
 x=clamp(cx*ww-bw/2,0,ww-bw); y=clamp(cy*hh-bh/2,0,hh-bh)
 mat=np.float32([[zoom,0,-x*zoom],[0,zoom,-y*zoom]])
 return cv2.warpAffine(screen,mat,(ww,hh),flags=cv2.INTER_LINEAR)

@lru_cache(maxsize=30)
def clip_files(group,name):
 return tuple(sorted((A/group/name).glob('*.png')))

@lru_cache(maxsize=12)
def native_frame(path):return read(path,True)
def native_clip(group,name,t=0):
 files=clip_files(group,name)
 if files:
  index=min(len(files)-1,max(0,round(t*30)))
  return native_frame(str(files[index]))
 path=A/group/(name+'.png')
 if not path.exists():raise FileNotFoundError(f'Missing native Kotlin export: {path}')
 return native_frame(str(path))

def moving_tv(local,mode='rows'):
 name={'my':'my-list'}.get(mode,mode)
 return native_clip('native-tv',name,local)

@lru_cache(maxsize=1)
def film_still():
 """Original procedural cinematography, not licensed movie footage."""
 ww,hh=1280,720; y,x=np.mgrid[0:hh,0:ww]; xx=x/ww; yy=y/hh
 halo=np.exp(-(((xx-.62)**2+(yy-.46)**2)**.5-.21)**2/.0003)
 haze=np.exp(-((xx-.7)**2/.13+(yy-.45)**2/.26))
 arr=np.zeros((hh,ww,4),np.uint8); arr[:,:,:3]=np.uint8(np.clip(np.array([2,5,11])+haze[:,:,None]*[7,27,45]+halo[:,:,None]*[160,37,31],0,255)); arr[:,:,3]=255
 cv2.circle(arr,(round(.62*ww),round(.46*hh)),round(.198*ww),(1,2,5,255),-1,cv2.LINE_AA)
 stars=np.random.default_rng(221).random((260,3))
 for sx,sy,s in stars: cv2.circle(arr,(int(sx*ww),int(sy*hh)),1,(int(75+s*110),int(90+s*120),int(110+s*130),255),-1)
 return arr

def footage(t,controls=False,phone=False):
 base=zoom_screen(film_still(),.5+.018*math.sin(t*.22),.5,1.06+.025*math.sin(t*.13))
 # Orbital foreground lines and drifting particles create actual motion.
 for j in range(7):
  yy=570+j*22+math.sin(t*.24+j*.5)*24
  pts=[(int(x),int(yy-60*math.sin(x/280+t*.17+j*.4))) for x in np.linspace(-10,1290,80)]
  cv2.polylines(base,[np.int32(pts)],False,(14+j*2,40+j*2,59+j*3,255),1,cv2.LINE_AA)
 if controls:
  # Overlay exported production Kotlin controls; no hand-drawn player widgets.
  layer=native_clip('native-tv','player',t%4).copy()
  large=cv2.resize(base,(layer.shape[1],layer.shape[0]),interpolation=cv2.INTER_CUBIC)
  hh=layer.shape[0]; layer[round(hh*.24):round(hh*.78)]=large[round(hh*.24):round(hh*.78)]
  return layer
 return base


def prepare():
 for group in ['tv','mobile','branding','posters','audio']: (A/group).mkdir(parents=True,exist_ok=True)
 for group in ['scenes','previews','final']: (OUT/group).mkdir(parents=True,exist_ok=True)
 for p in (HERE.parent/'assets').glob('*-poster.jpg'): shutil.copy2(p,A/'posters'/p.name)
 import cairosvg
 for n,out in [('npro','symbol'),('netflixpro','wordmark')]:
  cairosvg.svg2png(url=str(TV/'assets/branding'/f'{n}.svg'),write_to=str(A/'branding'/f'{out}.png'),output_width=2200 if out=='wordmark' else 1000)
 (WORK/'asset-provenance.json').write_text(json.dumps({
 'ui':'All app screens and interaction frames exported by native Robolectric/Compose from production Kotlin code. Fixture data is injected through test harnesses; no Python screen reconstruction.',
 'tv':'NativeTvFilmCaptureTest.kt; SDK34, 1280×720 dp, hdpi: 1920×1080. D-pad keys dispatch to the actual Activity and current app springs.',
 'mobile':'NativeMobileFilmMotionTest.kt; SDK34, 412×895 dp, xhdpi: 824×1790. Actual native touch gestures, profile editing and Details transitions.',
 'master':'3840×2160, 30fps, 180 seconds; composition is native 4K, source UI textures retain their capture resolution.',
 'playback':'Original procedural eclipse animation with actual Kotlin player controls; no third-party movie footage or live provider playback claim.',
 'music':'Original synthesized score and spatial sound design.',
 'ecosystem':'Artistic representation of a common visual identity, not validation of automatic device handoff.',
 'artwork':'Existing TMDB review fixtures; title availability is illustrative.'},indent=2))


def setup_runtime():pass


def shot(index,t):
 start,end,name=SCENES[index]; d=end-start; p=ease(t/d)
 out=stage('city' if index==9 and 4<t<9 else 'travel' if index==9 and t>=9 else 'red').copy()
 global_t=start+t
 if index==0:
  particles(out,t,.45*event(t,1,3))
  width=mix(2750,3100,p); yaw=mix(76,-3,event(t,0,9))
  screen=black_screen().copy()
  if t>=6.8:
   final=logo_screen(); reveal=event(t,6.8,1.8)
   edge=int(final.shape[1]*reveal)
   screen[:,:edge]=final[:,:edge]
   # Streaks converge through the logo's horizontal baseline; no plain fade.
   for k in range(10):
    xx=int(mix(final.shape[1]+k*150,final.shape[1]*.43, event(t,6.8+k*.045,1.2)))
    yy=int(final.shape[0]*.5+math.sin(k*1.9)*(1-reveal)*180)
    cv2.line(screen,(xx-140,yy),(xx,yy),(max(0,int(170*(1-reveal))),4,17,255),2,cv2.LINE_AA)
  device(out,screen,1920,1050,width,yaw=yaw,pitch=mix(4,0,p),reflect=True)
  if 1<t<7:
   x=mix(-600,4400,event(t,1,5)); poly(out,[point(x,100),point(x+180,1900)],(63,5,14),2)
 elif index==1:
  ui=native_clip('native-tv','home',max(0,t-2)); move=event(t,0,10)
  width=mix(3100,4020,move); yaw=mix(-3,0,move)
  if t<2:
   device(out,logo_screen(width=mix(.42,.08,event(t,0,1.6))),1920,1050,width,yaw=yaw,reflect=False)
   # Application emerges behind the contracting logo through an edge wipe.
   part=ui.copy(); part[:,:,3]=np.uint8(part[:,:,3]*event(t,.6,1.5))
   device(out,part,1920,1050,width,yaw=yaw,reflect=False)
  else: device(out,ui,1920,mix(1050,1080,move),width,yaw=yaw,reflect=False)
  # Screen-space copy, quiet enough to leave the hero artwork visible.
  headline(out,'YOUR WORLD\nOF ENTERTAINMENT',t,5,12,x=1850,y=1100,size=88,support='Movies. Series. Stories. One experience.')
 elif index==2:
  # Long, calm 430/1.6² springs, rather than speeding up the actual remote UI.
  if t<5:
   ui=native_clip('native-tv','home',t); ui_plane(out,ui,width=4000,yaw=math.sin(t*.25)*1.5,pitch=.7)
  elif t<10:
   ui=moving_tv(t-5,'categories'); ui_plane(out,ui,width=3900,yaw=-1.5)
  elif t<14:
   ui=moving_tv(t-10,'rows'); ui_plane(out,ui,width=3900,yaw=1.5)
  else:
   ui=moving_tv(t-14,'vertical'); ui_plane(out,ui,width=3900,yaw=-1)
  if t<5:headline(out,'FIND SOMETHING\nINCREDIBLE.',t,.7,4.8,x=1930,y=1200,size=85)
  # Brief, understated discovery beat labels outside the application plane.
  label='HOME' if t<5 else 'CATEGORIES' if t<10 else 'TRENDING / POPULAR / SERIES' if t<14 else 'CONTINUE WATCHING / MY LIST'
  caption(out,label,240,2010,25,window(t,.3,d-.3,.5),4,color=(184,187,195))
 elif index==3:
  particles(out,global_t,.25)
  if t<4:
   # Motivated poster portals, connected by the same horizontal movement.
   j=int(t/.8); u=(t/.8)%1; art=poster(j+3)
   q=quad(1920+mix(160,-160,ease(u)),1080,mix(1540,5900,event(u,.45,.5)),mix(2310,8850,event(u,.45,.5)),yaw=mix(-14,4,ease(u)),roll=-3)
   project(out,art,q)
  else:
   u=(t-4)/11; travel=mix(0,16,u*u)
   planes=[]
   for j in range(30):
    z=(j*.7-travel)%18+.9; side=-1 if j%2==0 else 1
    scale=1/(.35+z*.23); cx=1920+side*1380*scale; cy=1080+math.sin(j*2.2)*250*scale
    planes.append((z,j,cx,cy,620*scale,930*scale))
   for z,j,cx,cy,ww,hh in sorted(planes,reverse=True):
    q=quad(cx,cy,ww,hh,yaw=(-1 if j%2==0 else 1)*12+math.sin(global_t*.1+j)*5,roll=math.sin(j)*5)
    project(out,poster(j),q,opacity=min(1,1.4-z*.05))
   if t>13.6:
    art=poster(8); q=quad(1920,1080,mix(600,6300,event(t,13.6,1.4)),mix(900,9450,event(t,13.6,1.4)),yaw=mix(-12,0,event(t,13.6,1.4)))
    project(out,art,q)
 elif index==4:
  ui=native_clip('native-tv','details',min(t,1.35)); zz=mix(1.0,1.19,p)
  ui_plane(out,zoom_screen(ui,mix(.5,.43,p),mix(.5,.55,p),zz),width=3840)
  if t>3:
   # Play pulse is a selected-control animation, never a cursor.
   r=14+event(t,3.2,1.2)*110; strength=1-event(t,3.2,1.2)
   cv2.circle(out,point(320,1852),max(1,round(r*SCALE)),(int(210*strength),int(210*strength),int(215*strength)),max(1,round(2*SCALE)),cv2.LINE_AA)
  if t>5.3:
   # Pass through the control plane into the playback portal.
   ui_plane(out,footage(global_t),cx=1920,cy=1080,width=mix(200,4050,event(t,5.3,.7)),opacity=event(t,5.3,.3))
 elif index==5:
  controls=t<4.1 or t>7
  ui=footage(global_t,controls); ui_plane(out,ui,width=3930,pitch=.4*math.sin(t*.2))
  headline(out,'YOUR SCREEN.\nYOUR CONTROL.',t,3.8,8.8,x=240,y=400,size=86)
  if t<4.1:
   caption(out,'PLAY / PAUSE     TIMELINE     SUBTITLES',240,245,26,window(t,.6,4,.4),3)
   caption(out,'EPISODES     QUALITY     VOLUME',240,312,26,window(t,.9,4,.4),3)
 elif index==6:
  ui=footage(global_t,t<1.8); width=mix(4060,2450,event(t,0,7.4)); cx=mix(1920,1400,event(t,3.5,5.5)); yaw=mix(0,-16,p)
  device(out,ui,cx,1050,width,yaw=yaw,pitch=-1,reflect=True)
  if t>5: light_path(out,global_t,0,event(t,5,5))
  if t>9:
   # The connected red line is the direction cue for the next device reveal.
   caption(out,'THE STORY CONTINUES.',2440,940,45,event(t,9,1),1)
 elif index==7:
  particles(out,global_t,.25); light_path(out,global_t,0,1)
  width=mix(720,1100,event(t,4,9)); yaw=mix(175,-7,event(t,0,10)); roll=mix(8,-2,p)
  screen=black_screen('phone') if t<2 else logo_screen('phone',.73) if t<7.4 else native_mobile('home')
  device(out,screen,2580,1050,width,'phone',yaw=yaw,pitch=-3,roll=roll,reflect=True)
  headline(out,'ENTERTAINMENT\nTHAT MOVES\nWITH YOU.',t,4,13.4,x=260,y=730,size=91)
  if t>12.2:
   # Glass interface moves out of the physical screen in the same direction.
   shift=event(t,12.2,1.8)
   ui_plane(out,native_mobile('home'),cx=mix(2580,2420,shift),cy=1050,width=mix(1100,1150,shift),yaw=-7,pitch=-3,roll=-2)
 elif index==8:
  # UI planes expand from the phone, then collapse back into it.
  spread=event(t,1.2,4)*(1-event(t,14,4)); center=2420-500*spread
  screens=['home','details','my-list']; widths=[850,960,820]
  for j in [0,2,1]:
   cx=center+(j-1)*1050*spread; cy=1080+abs(j-1)*70*spread
   yaw=(1-j)*mix(-7,13,spread); opacity=1 if j==1 else spread
   name=screens[j]
   # A gentle vertical gesture transports the native UI as a single glass plane.
   dy=-40*math.sin(t*.18+j)*spread
   ui_plane(out,native_clip('native-mobile',name,max(0,t-1.2)),cx=cx,cy=cy+dy,width=widths[j],yaw=yaw,roll=(j-1)*3*spread,opacity=opacity)
   if spread>.5: caption(out,['HOME','DETAILS','MY LIST'][j],cx,2050,24,spread,4,center=True,color=(164,171,185))
  if t<5: headline(out,'A WORLD\nIN YOUR HAND.',t,.4,4.8,x=180,y=320,size=70)
  if t>14:
   device(out,native_mobile('home'),2420,1050,960,'phone',yaw=-7,roll=-2,opacity=event(t,14,2),reflect=False)
  if 6<t<11:
   # Add-to-list confirmation is synchronized with the existing source control.
   caption(out,'DISCOVER. SAVE. CONTINUE.',1920,110,29,window(t,6,11,.6),3,center=True)
 elif index==9:
  u=event(t,0,3); width=mix(960,1180,u); roll=mix(-2,-90,u)
  # Use a portrait texture with sideways footage; after the device rotates the
  # content is upright in its native landscape playback orientation.
  landscape=footage(global_t,False)
  canvas=np.full((824,1790,4),(0,0,0,255),np.uint8)
  fitted=cv2.resize(landscape,(1465,824),interpolation=cv2.INTER_CUBIC); canvas[:,162:162+1465]=fitted
  if 1.8<t<4.8 or 10.5<t<12.8:
   controls=native_clip('native-mobile','player',t%4).copy()
   controls[:,:,3]=np.uint8(np.max(controls[:,:,:3],axis=2)>8)*255
   over(canvas[:,:,:3],controls,0,0)
  portrait=np.rot90(canvas,k=-1).copy()
  device(out,portrait,2350,1050,width,'phone',yaw=mix(-7,12,p),pitch=3,roll=roll,reflect=False)
  # The physical phone's portrait width is the landscape height. Keep device
  # within the frame and use the surrounding world to communicate travel.
  headline(out,'YOUR STORIES\nGO WHERE YOU GO.',t,4,13.6,x=210,y=380,size=80)
  if t<4:caption(out,'AT HOME',260,1930,26,window(t,.5,4,.5),5)
  elif t<9:caption(out,'AFTER DARK',260,1930,26,window(t,4,9,.5),5)
  else:caption(out,'ON THE MOVE',260,1930,26,window(t,9,14,.5),5)
 elif index==10:
  section=min(3,int(t/3.5)); u=t-section*3.5
  names=['profile-picker','clips','downloads','my-list']; copy=['YOUR PROFILE.','YOUR STORIES.','YOUR ENTERTAINMENT.','YOUR LIST.']
  ui=native_clip('native-mobile',names[section],u); width=mix(900,950,ease(u/3.5)); yaw=mix(9,-5,ease(u/3.5))
  ui_plane(out,ui,cx=2580,cy=1080,width=width,yaw=yaw,pitch=-2,roll=-2)
  headline(out,copy[section],u,.1,3.45,x=230,y=880,size=87)
  if section==0:
   # Avatars are lifted from the actual profile grid, retaining its artwork.
   for j,x in enumerate([105,324,562]):
    tex=ui[1240:1415,x:x+175].copy() if len(ui)>1500 else ui
    q=quad(620+j*270,1320+math.sin(global_t+j)*16,190,190,yaw=mix(-12,7,p),roll=(j-1)*3)
    project(out,tex,q,window(u,.8,3.4,.5))
  if section==2:
   # Progress circle -> completed offline badge: a deliberate shape match.
   center=point(710,1320); radius=round(72*SCALE)
   cv2.ellipse(out,center,(radius,radius),0,-90,-90+360*event(u,.5,1.7),(220,14,34),max(1,round(4*SCALE)),cv2.LINE_AA)
   if u>2.2:
    poly(out,[point(670,1320),point(700,1350),point(755,1285)],(241,243,247),5)
   caption(out,'DOWNLOAD. TAKE IT WITH YOU.',230,1590,25,window(u,.5,3.5,.5),2)
 elif index==11:
  p=event(t,0,5); particles(out,global_t,.6); light_path(out,global_t,.22,.86)
  device(out,tvscreen('home'),mix(1100,1290,p),1080,mix(1850,2220,p),'tv',yaw=-9,pitch=1,reflect=True)
  device(out,native_mobile('home'),mix(2760,3030,p),mix(1060,1160,p),mix(890,620,p),'phone',yaw=9,pitch=-2,roll=-3,reflect=True)
  for j in range(3):
   u=((t*.10+j/3)%1); ww=160*(1+.2*math.sin(u*math.pi))
   project(out,poster(j+2),quad(1400+1550*u,1500+160*math.sin(u*math.pi*2),ww,ww*1.5,yaw=-14+28*u),.7*window(t,3,14,.6))
  caption(out,'ONE EXPERIENCE.',1920,245,94,window(t,3,8.5,.7),0,center=True)
  # Center these distinct beats without introducing additional explanatory copy.
  if 8<t<14.5: caption(out,'EVERY SCREEN.',1920,245,94,window(t,8,14.5,.7),0,center=True)
 elif index==12:
  # Beat-aligned object passes and match zooms, no screenshot crossfades.
  cuts=[0,.75,1.5,2.75,3.5,4.75,5.5,6.75,7.5,8.75,9.5,10.25,11]
  j=max(0,bisect.bisect_right(cuts,t)-1); local=t-cuts[j]; span=cuts[j+1]-cuts[j]; u=ease(local/span)
  if j%5==0: device(out,tvscreen('rows'),1920,1080,mix(3000,3300,u),'tv',yaw=mix(15,-7,u),pitch=-2,roll=1,reflect=False)
  elif j%5==1:device(out,native_mobile('home'),1920,1080,mix(900,1040,u),'phone',yaw=mix(-16,5,u),roll=-3,reflect=False)
  elif j%5==2: project(out,poster(j),quad(1920,1080,mix(1400,2200,u),mix(2100,3300,u),yaw=mix(-15,0,u),roll=-4))
  elif j%5==3:ui_plane(out,footage(global_t,j<5),width=mix(3900,4300,u),roll=1)
  else:
   names=['clips','downloads','profile-picker']; ui_plane(out,native_mobile(names[(j//5)%3]),cx=1920,cy=1080,width=mix(880,1020,u),yaw=mix(11,-6,u))
  # Consistent right-to-left obstruction conceals the fast cut at shot edges.
  if local>span-.15:
   sweep=event(local,span-.15,.15); q=quad(mix(4600,1920,sweep),1080,1400,3300,yaw=-18,roll=-8)
   project(out,poster(j+1),q)
 elif index==13:
  if t<5.6:
   travel=event(t,0,4.8)
   for j in range(12):
    theta=j/12*math.tau+travel*5; radius=mix(1570,4,travel); size=mix(400,1,travel)
    tex=native_mobile(['home','details','downloads','profile-picker'][j%4]) if j%2 else native_clip('native-tv',['home','rows','episodes'][j%3],t)
    ww=size; hh=ww*tex.shape[0]/tex.shape[1]
    project(out,tex,quad(1920+math.cos(theta)*radius,1080+math.sin(theta)*radius*.48,ww,hh,yaw=math.sin(theta)*20,roll=math.degrees(theta)%360),1-travel*.7)
   if t>3.7:
    yy=1080; length=mix(3500,0,event(t,3.7,1.7)); poly(out,[point(1920-length/2,yy),point(1920+length/2,yy)],(190,12,35),3)
  else:
   out=np.zeros((H,W,3),np.uint8)
   # Negative space and a restrained glow let the outlined identity resolve.
   small=cv2.resize(stage(),(W,H)); out[:]=np.uint8(small*.35)
   particles(out,global_t,.35)
   mark=asset('branding','wordmark'); width=mix(1690,1740,event(t,5.6,4.4))
   ui_plane(out,mark,cx=1920,cy=820,width=width,opacity=event(t,5.6,.55))
   caption(out,'ENTERTAINMENT. EVERYWHERE.',1920,1190,47,event(t,6.2,.8),5,center=True)
   caption(out,'WATCH NOW',1920,1510,28,event(t,6.8,.8),6,center=True,color=(192,196,207))
   # Discreet physical silhouettes behind the lockup; no secondary logo text.
   if t<7:
    device(out,None,710,1110,620,'tv',yaw=-16,opacity=.12,reflect=False)
    device(out,None,3060,1190,160,'phone',yaw=12,opacity=.12,reflect=False)
  if t>9.5: out=np.uint8(out*(1-event(t,9.5,.4666667)))
 if index==3 and t<4 and (t/.8)%1>.81:
  out=cv2.blur(out,(max(1,round(23*SCALE)),1))
 if index==12 and local>span-.10:
  out=cv2.blur(out,(max(1,round(29*SCALE)),1))
 return out


def frame(global_t):
 index=max(0,min(len(SCENES)-1,bisect.bisect_right(STARTS,global_t)-1))
 return shot(index,global_t-SCENES[index][0])


def build_audio():
 """Original score: analog pads, a restrained pulse, percussion and spatial FX."""
 rate=48000; length=rate*180; music=np.zeros((length,2),np.float32); effects=np.zeros_like(music)
 rng=np.random.default_rng(4343)
 def add(dst,at,data,amp=1,pan=0):
  i=round(at*rate); n=min(len(data),length-i)
  if i<0 or n<=0:return
  if data.ndim==1:
   dst[i:i+n,0]+=data[:n]*amp*math.sqrt((1-pan)/2)
   dst[i:i+n,1]+=data[:n]*amp*math.sqrt((1+pan)/2)
  else: dst[i:i+n]+=data[:n]*amp
 def tone(note,dur,amp=.1,kind='pad',at=0,pan=0,dst=None):
  f=440*2**((note-69)/12); x=np.arange(round(dur*rate))/rate
  if kind=='pad':
   signal=sum(np.sin(math.tau*f*k*x+.2*k)/(k*k) for k in range(1,6)); env=np.minimum(1,x/1.8)*np.minimum(1,(dur-x)/2.2); env*=.87+.13*np.sin(math.tau*.11*x)
   data=np.stack([signal*env,np.sin(math.tau*f*1.0012*x)*env],1)
  elif kind=='piano':
   signal=sum(np.sin(math.tau*f*(k+(.001*k*k if k>1 else 0))*x)/(k**1.8) for k in range(1,7)); env=(1-np.exp(-x*250))*np.exp(-x*1.25); data=signal*env
  else:
   signal=np.sin(math.tau*f*x)+.22*np.sin(math.tau*f*2*x); env=(1-np.exp(-x*110))*np.exp(-x*3.8); data=signal*env
  add(music if dst is None else dst,at,data,amp,pan)
 chords=[[45,52,57,60,64],[41,48,53,57,60],[48,55,60,64,67],[43,50,55,59,62]]
 for start in np.arange(0,170,10):
  chord=chords[int(start/10)%4]; intensity=.32 if start<25 else .5 if start<58 else .7 if start<84 else .64 if start<130 else .55 if start<144 else 1
  for note in chord[1:]: tone(note,13,.033*intensity,'pad',start)
  tone(chord[0]-12,11,.06*intensity,'pad',start)
 # 96 bpm, quiet opening, then a pulse that gathers weight without arcade beeps.
 beat=.625
 for k,at in enumerate(np.arange(25,169.8,beat)):
  phase=.3 if at<58 else .52 if at<84 else .64 if at<116 else .48 if at<144 else .9
  chord=chords[int(at/10)%4]
  if k%2==0:
   x=np.arange(int(.45*rate))/rate; freq=45+90*np.exp(-x*35); sig=np.sin(math.tau*np.cumsum(freq)/rate)*np.exp(-x*12)
   sig+=rng.normal(0,.13,len(x))*np.exp(-x*100)
   add(music,at,sig,.26*phase)
   tone(chord[0]-12,.55,.1*phase,'bass',at)
  if k%4==2:
   x=np.arange(int(.22*rate))/rate; noise=rng.normal(0,1,len(x)).astype(np.float32)
   noise=noise-np.convolve(noise,np.ones(7)/7,'same'); sig=noise*np.exp(-x*22)+np.sin(math.tau*175*x)*np.exp(-x*30)*.35
   add(music,at,sig,.09*phase)
  if at>84:
   x=np.arange(int(.095*rate))/rate; noise=rng.normal(0,1,len(x)); hp=noise-np.convolve(noise,np.ones(9)/9,'same')
   add(music,at,hp*np.exp(-x*65),.025*phase,(-.32 if k%2 else .32))
  if k%2==1:
   note=chord[2+(k%3)]+12; tone(note,1.7,.075*phase,'piano',at,(-.28 if k%4==1 else .28))
 for at in np.arange(116,169,2.5):
  note=[69,72,76,74,72,67,69,64][int((at-116)/2.5)%8]; tone(note,3.5,.07 if at<144 else .11,'piano',at,.05)
 # Movement FX: air, low-frequency pressure and a purposeful stereo trajectory.
 impacts=[6.8,12,25,43,58,64,73,84,98,116,130,133.5,137,140.5,144,152,159,170,175.6]
 for at in impacts:
  x=np.arange(int(1.9*rate))/rate; sig=np.sin(math.tau*(39*x+5*(1-np.exp(-x*12))))*np.exp(-x*3)
  sig+=rng.normal(0,.06,len(x))*np.exp(-x*18)
  add(effects,at,sig,.38 if at in [58,84,159,175.6] else .18)
 for j,at in enumerate([11.3,24.2,42.4,46.2,50.0,57.2,63.5,72.6,82.5,96.8,114.8,128.8,143.1,158.4,159.5,160.2,161.5,162.3,163.8,165,166.4,168,170.2,174]):
  dur=1.3 if at<159 else .55; x=np.arange(int(dur*rate))/rate; noise=rng.normal(0,1,len(x)); smooth=np.convolve(noise,np.ones(24)/24,'same')
  env=np.sin(np.pi*x/dur)**3; data=(smooth+.05*np.sin(math.tau*130*x))*env
  pan=np.linspace(-.8,.8,len(x))*(-1 if j%2 else 1)
  stereo=np.stack([data*np.sqrt((1-pan)/2),data*np.sqrt((1+pan)/2)],1)
  add(effects,at,stereo,.21 if at<159 else .15)
 for at in [27,28.6,32,33.6,36,38,61.5,66,101,104.5,108,137.5]:
  x=np.arange(int(.13*rate))/rate; sig=(rng.normal(0,.35,len(x))+np.sin(math.tau*420*x)*.1)*np.exp(-x*60)
  add(effects,at,sig,.07)
 for at,n in [(176,45),(176.24,52),(176.52,57)]:tone(n,2.6,.16,'piano',at,dst=effects)
 # Early atmosphere and finale strip-down; a short stereo room decay binds FX.
 x=np.arange(length)/rate
 fade=np.minimum(1,x/4)*np.minimum(1,np.maximum(0,(170-x)/2))
 music*=fade[:,None]
 for delay,amount in [(.18,.18),(.36,.12),(.71,.07)]:
  n=int(delay*rate); effects[n:]+=effects[:-n,::-1]*amount
 final=music+effects
 final*=np.minimum(1,np.maximum(0,(180-x)/.4))[:,None]
 peak=np.max(np.abs(final)); gain=.88/max(1,peak); final*=gain
 for name,data in [('music',music*gain),('sound-design',effects*gain),('mix',final)]:
  with wave.open(str(A/'audio'/f'{name}.wav'),'wb') as stream:
   stream.setnchannels(2);stream.setsampwidth(2);stream.setframerate(rate);stream.writeframes(np.int16(np.clip(data,-1,1)*32767).tobytes())
 print('Original 180-second score and stereo effects written.',flush=True)


def encode_scene(index,preview=False):
 start,end,name=SCENES[index]; duration=end-start; count=round(duration*FPS)
 out=OUT/'scenes'/f'scene_{index+1:02d}_{name}.mp4'
 signature=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()+f'/{W}/{H}/{FPS}'
 stamp=out.with_suffix('.json')
 if out.exists() and stamp.exists() and json.loads(stamp.read_text()).get('signature')==signature:
  info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-of','json',str(out)]))['streams'][0]
  if int(info.get('nb_frames',0))==count and info['width']==W and info['height']==H:
   print('Reuse verified scene',out.name,flush=True);return out
 cmd=['ffmpeg','-hide_banner','-loglevel','error','-y','-f','rawvideo','-pixel_format','rgb24','-video_size',f'{W}x{H}','-framerate',str(FPS),'-i','pipe:0','-an','-c:v','libx264','-preset','veryfast','-crf','19','-threads',os.environ.get('NETFLIXPRO_FILM_THREADS','2'),'-pix_fmt','yuv420p','-profile:v','high','-color_primaries','bt709','-color_trc','bt709','-colorspace','bt709','-movflags','+faststart',str(out)]
 begin=time.monotonic()
 with (OUT/'scenes'/f'{name}.log').open('w') as log:
  proc=subprocess.Popen(cmd,stdin=subprocess.PIPE,stderr=log)
  try:
   for k in range(count):
    im=shot(index,k/FPS); proc.stdin.write(im.tobytes())
    if k%max(1,FPS*2)==0: print(f'{index+1:02d} {name}: {k}/{count} frames, {time.monotonic()-begin:.1f}s',flush=True)
   proc.stdin.close(); code=proc.wait()
  except BaseException:
   proc.kill();proc.wait();out.unlink(missing_ok=True);raise
 if code: out.unlink(missing_ok=True);raise RuntimeError(f'Encoder failed; inspect {name}.log')
 stamp.write_text(json.dumps({'signature':signature,'sequence':index+1,'frames':count,'width':W,'height':H,'fps':FPS,'seconds':duration},indent=2))
 print('Completed',out.name,f'{time.monotonic()-begin:.1f}s',flush=True)
 return out


def assemble():
 scenes=[OUT/'scenes'/f'scene_{i+1:02d}_{n}.mp4' for i,(_,_,n) in enumerate(SCENES)]
 if not all(p.exists() for p in scenes):raise RuntimeError('Finish all sequences before assembling.')
 listing=OUT/'scenes/concat.txt'; listing.write_text(''.join("file '"+p.name+"'\n" for p in scenes))
 metadata=OUT/'scenes/chapters.ffmeta';metadata.write_text(';FFMETADATA1\ntitle=NetflixPro — Entertainment. Everywhere.\ncomment=Cinematic product visualization; native and source-staged UI.\n'+''.join(f'[CHAPTER]\nTIMEBASE=1/1000\nSTART={s*1000}\nEND={e*1000}\ntitle={n.replace("_"," ").title()}\n' for s,e,n in SCENES))
 from audio_master import normalize
 normalized=A/'audio/master.wav'; normalize(A/'audio/mix.wav',normalized)
 final=OUT/'final/NetflixPro-Cinematic-TV-Mobile-4K.mp4'
 cmd=['ffmpeg','-hide_banner','-y','-f','concat','-safe','0','-i',str(listing),'-i',str(normalized),'-i',str(metadata),'-map','0:v','-map','1:a','-map_metadata','2','-map_chapters','2','-c:v','copy','-c:a','aac','-b:a','320k','-ar','48000','-t','180','-movflags','+faststart',str(final)]
 with (OUT/'final/mux.log').open('w') as log: subprocess.run(cmd,stdout=log,stderr=log,check=True)
 # A smaller review file is useful on slower connections; master stays 4K.
 review=OUT/'final/NetflixPro-Cinematic-Review-1080p.mp4'
 subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(final),'-vf','scale=1920:1080:flags=lanczos','-c:v','libx264','-preset','veryfast','-crf','22','-threads',os.environ.get('NETFLIXPRO_FILM_THREADS','2'),'-c:a','copy','-movflags','+faststart',str(review)],check=True)
 print('Delivered master and review:',final,review,flush=True)


def storyboard():
 shots=[3,8.5,17,29,35,48,55,61,68,78,89,102,110,122,131.5,138.5,150,163,173,177.5]
 thumbs=[]
 for t in shots:
  im=Image.fromarray(frame(t)); path=OUT/'previews'/f'shot-{t:06.2f}.jpg'; im.save(path,quality=94)
  thumb=im.resize((640,360),Image.Resampling.LANCZOS); canvas=Image.new('RGB',(640,410),(5,6,9));canvas.paste(thumb)
  ImageDraw.Draw(canvas).text((18,372),f'{int(t//60):02d}:{t%60:04.1f}  /  {SCENES[bisect.bisect_right(STARTS,t)-1][2].replace("_"," ")}',font=ImageFont.truetype(str(FONTS/'OpenSans-Regular.ttf'),17),fill=(172,176,188))
  thumbs.append(canvas)
 board=Image.new('RGB',(2560,2050),(2,3,5))
 for i,im in enumerate(thumbs):board.paste(im,((i%4)*640,(i//4)*410))
 board.save(OUT/'previews/Cinematic-Storyboard.jpg',quality=95)
 Image.fromarray(frame(150)).save(OUT/'previews/Film-Cover.jpg',quality=95)
 (HERE/'shot-list.json').write_text(json.dumps([{'sequence':i+1,'start':s,'end':e,'seconds':e-s,'name':n,'camera':'Perspective camera, eased dolly/orbit/truck; screen-space UI controlled independently.','motivation':{'tv_reveal':'Red edge light reveals TV; streaks construct current wordmark.','tv_screen_dive':'Continuous push carries bezel outside frame into Home.','home_discovery':'Current slow critically damped browsing moves categories and rows.','content_tunnel':'Selected poster expands into depth corridor, leading to Details.','movie_details':'Selected Play pulse motivates screen portal.','player':'Original eclipse footage inside current TV player UI.','tv_pullout':'Pull back to physical screen; luminous path leads to phone.','phone_reveal':'Phone orbit, rim-light reveal, corresponding screen push.','mobile_ui':'Native Home, Details and My List fan out on depth planes.','mobile_player':'Phone rotates into landscape; environments shift with light.','features':'Profiles, Clips, Downloads and My List retain native UI.','ecosystem':'Shared stage and moving artwork suggest a unified design ecosystem.','hero_montage':'Beat-synced directional object passes and matched scale.','logo_finale':'Planes spiral inward; a light beam resolves into the end lockup.'}[n]} for i,(s,e,n) in enumerate(SCENES)],indent=2))


def preview():
 segments=[(0,6,3),(3,4,2.5),(7,4.5,3),(8,4,3),(11,5,3),(13,6,3)]
 out=OUT/'previews/Cinematic-Motion-Sample.mp4'
 cmd=['ffmpeg','-hide_banner','-loglevel','error','-y','-f','rawvideo','-pix_fmt','rgb24','-s',f'{W}x{H}','-r',str(FPS),'-i','pipe:0','-c:v','libx264','-preset','veryfast','-crf','20','-threads',os.environ.get('NETFLIXPRO_FILM_THREADS','2'),'-pix_fmt','yuv420p','-movflags','+faststart',str(out)]
 proc=subprocess.Popen(cmd,stdin=subprocess.PIPE)
 for i,at,span in segments:
  for k in range(round(span*FPS)):proc.stdin.write(shot(i,at+k/FPS).tobytes())
 proc.stdin.close()
 if proc.wait():raise RuntimeError('Preview encode failed')
 print('Motion review:',out,flush=True)


def main():
 global W,H,SCALE
 p=argparse.ArgumentParser();p.add_argument('action',choices=['prepare','storyboard','preview','audio','scene','render','assemble']);p.add_argument('--scene',type=int);p.add_argument('--size',type=int,default=3840)
 args=p.parse_args(); W=args.size;H=round(W*9/16);SCALE=W/3840
 if args.action=='prepare':prepare();return
 setup_runtime()
 if args.action=='audio':build_audio()
 elif args.action=='storyboard':storyboard()
 elif args.action=='preview':preview()
 elif args.action=='scene':encode_scene(args.scene-1)
 elif args.action=='assemble':assemble()
 else:
  for i in range(len(SCENES)):encode_scene(i)
  assemble()
if __name__=='__main__':main()
