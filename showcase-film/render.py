"""Render a 180-second product tour from native Compose captures.

Reusable perspective/device/score primitives come from the existing film project.
Native app images stay intact; fixture data is explicitly documented.
"""
from pathlib import Path
from functools import lru_cache
import argparse,bisect,hashlib,json,math,os,subprocess,sys,time,zipfile
import numpy as np,cv2
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parent
WORK=Path(os.environ.get('NPRO_SHOWCASE_WORK','/workspace/artifacts/showcase-20261004'))
os.environ['NETFLIXPRO_FILM_WORK']=str(WORK)
os.environ['NETFLIXPRO_FILM_THREADS']='1'
sys.path.insert(0,str(HERE.parent/'advertising-video/cinematic'))
import film as fx
fx.W,fx.H,fx.SCALE=1920,1080,.5
import motion_design as md
cv2.setNumThreads(1)
W,H,FPS=1920,1080,30
SHOTS=json.loads((HERE/'shot-list.json').read_text())
ASSETS=WORK/'assets';OUT=WORK/'output';SEG=OUT/'segments'
for p in [SEG,OUT/'resolve',OUT/'review']:p.mkdir(parents=True,exist_ok=True)
STARTS=[s['start'] for s in SHOTS]
FONTS=Path('/usr/share/fonts/truetype/open-sans')
def ease(t): return fx.ease(t)
@lru_cache(maxsize=48)
def native_file(path):
 with Image.open(path) as im:return np.array(im.convert('RGBA'))
@lru_cache(maxsize=64)
def clip_paths(source):
 app,name=source.split(':');folder=ASSETS/('native-tv' if app=='tv' else 'native-mobile')
 return tuple(sorted((folder/name).glob('*.png'))),folder/(name+'.png')
def native(source,t=0):
 frames,still=clip_paths(source)
 path=frames[min(len(frames)-1,max(0,int(t*15)))] if frames else still
 return native_file(str(path))
def choose(s,t):
 items=s['sources'];portion=s['duration']/len(items);i=min(len(items)-1,int(t/portion))
 return items[i],t-i*portion,i

def screen_for(s,t):
 source,local,index=choose(s,t);current=native(source,local)
 if index==0 or local>=.28:return current
 previous=native(s['sources'][index-1],s['duration']/len(s['sources'])-.01)
 if previous.shape!=current.shape:return current
 return cv2.addWeighted(previous,1-ease(local/.28),current,ease(local/.28),0)
@lru_cache(maxsize=150)
def type_layer(text,size=48,color=(245,245,247),bold=False):
 f=ImageFont.truetype(str(FONTS/('OpenSans-Semibold.ttf' if bold else 'OpenSans-Regular.ttf')),size)
 width=max(4,int(f.getlength(text))+8)
 im=Image.new('RGBA',(width,size*2));ImageDraw.Draw(im).text((3,0),text,font=f,fill=(*color,255))
 return np.array(im)
def text(dst,value,x,y,size=48,color=(245,245,247),bold=False,alpha=1):
 bit=type_layer(value,size,color,bold)
 if dst.shape[2]==3:
  fx.over(dst,bit,x,y,alpha);return
 x,y=int(x),int(y);h,w=bit.shape[:2];x1,y1=max(x,0),max(y,0);x2,y2=min(x+w,dst.shape[1]),min(y+h,dst.shape[0])
 if x2<=x1 or y2<=y1:return
 b=bit[y1-y:y2-y,x1-x:x2-x].astype(np.float32);r=dst[y1:y2,x1:x2].astype(np.float32)
 a=b[:,:,3:4]/255*alpha;c=r[:,:,3:4]/255;out=a+c*(1-a)
 rgb=(b[:,:,:3]*a+r[:,:,:3]*c*(1-a))/np.maximum(out,.00001)
 dst[y1:y2,x1:x2]=np.uint8(np.concatenate([rgb,out*255],axis=2).clip(0,255))
def label(s,t,phone=False):
 im=np.zeros((H,W,4),np.uint8)
 entrance=ease(t/.7)
 if phone:
  lines=wrap(s['title'],25)
  for i,line in enumerate(lines):
   reveal=ease((t-.13*i)/.72)
   text(im,line,100,315+i*76+round(28*(1-reveal)),60,bold=True,alpha=reveal)
  support=ease((t-.35)/.7)
  for i,line in enumerate(wrap(s['subtitle'],36)):text(im,line,103,345+len(lines)*76+i*37+round(14*(1-support)),26,color=(170,176,187),alpha=support)
  text(im,'NETFLIXPRO  /  ANDROID PHONE',103,258,17,color=(255,80,93),bold=True,alpha=ease(t/.5))
 else:
  text(im,s['title'],95,45+round(22*(1-entrance)),43,bold=True,alpha=entrance)
  text(im,s['subtitle'],98,107+round(12*(1-entrance)),21,color=(175,181,192),alpha=ease((t-.18)/.7))
  text(im,'NETFLIXPRO  /  ANDROID TV',1530,60,16,color=(255,90,101),bold=True,alpha=entrance)
 return im
def wrap(value,limit):
 words=value.split();out=[];line=''
 for word in words:
  if line and len(line+' '+word)>limit:out.append(line);line=word
  else:line=(line+' '+word).strip()
 if line:out.append(line)
 return out
@lru_cache(maxsize=8)
def background(kind):return fx.stage('travel' if kind=='mobile' else 'red')
def base_compose(index,t):
 s=SHOTS[index];dur=s['duration'];p=ease(t/max(1,dur));im=background(s['kind']).copy()
 md.illuminate(im,t,index,s['kind'])
 if s['kind']=='both':
  if s['name']=='membership':
   source,u,_=choose(s,t)
   if source.startswith('tv:'):
    fx.device(im,native(source,u),2310,1110,2350,'tv',yaw=-3+5*p,reflect=False)
    text(im,'Membership.',105,325,57,bold=True);text(im,'One account.',108,408,40,bold=True)
    text(im,'Phone and Android TV.',109,469,25,color=(166,173,186))
   else:
    fx.device(im,native(source,u),2690,1100,790,'phone',yaw=-6+10*p,reflect=False)
    text(im,'Choose your plan.',104,352,56,bold=True)
    text(im,'Sign in. Explore the options.',107,434,25,color=(173,180,190))
   text(im,'Demo account · no payment submitted',104,940,17,color=(128,136,150))
  else:
   opening=s['name']=='opening';reveal=ease(t/1.3);arrival=ease(t/(3 if opening else 1.4))
   md.gallery(im,t,amount=.85 if opening else .6)
   md.shadow(im,825,970,1150);md.shadow(im,1530,996,440)
   q=fx.device(im,native('tv:home',t*.35),fx.mix(1750,1620,arrival),1230,fx.mix(1940,2310,arrival),'tv',yaw=fx.mix(68 if opening else -20,-3,arrival),pitch=fx.mix(4,.3,arrival),opacity=reveal,reflect=True)
   md.light_trace(im,t,q,duration=2)
   phoneArrival=ease((t-(.55 if opening else .05))/(2.3 if opening else 1.25))
   q=fx.device(im,native('mobile:home',t*.3),fx.mix(3760,3030,phoneArrival),fx.mix(-800 if opening else 1450,1150,phoneArrival),fx.mix(670,755,phoneArrival),'phone',yaw=fx.mix(91,-4,phoneArrival),roll=fx.mix(-22,-2,phoneArrival),opacity=reveal,reflect=True)
   md.light_trace(im,t-.65,q,color=(47,127,159),duration=2.2)
   if opening:
    bit=type_layer(s['title'],62,bold=True);md.title(im,bit,(W-bit.shape[1])//2,68,t,.18)
   else:
    mark=md.brand(460);md.title(im,mark,(W-mark.shape[1])//2,66,t,.15)
   bit=type_layer(s['subtitle'],25,color=(177,184,196));md.title(im,bit,(W-bit.shape[1])//2,169,t,.4)
   if not opening:
    bit=type_layer('npro-app.vercel.app',31,color=(252,77,88),bold=True);md.title(im,bit,(W-bit.shape[1])//2,919,t,.65)
    bit=type_layer('AVAILABLE NOW',15,color=(192,200,214),bold=True);md.title(im,bit,(W-bit.shape[1])//2,892,t,.5)
 elif s['kind']=='mobile':
  source,u,_=choose(s,t);frame=screen_for(s,t)
  if source=='mobile:clips':
   # Replace only the uniform native fallback gradient; keep native controls and copy.
   frame=frame.copy();h,w=frame.shape[:2];a,b=round(h*.09),round(h*.73)
   original=fx.footage(t);fh,fw=original.shape[:2];scale=max(w/fw,(b-a)/fh)
   fitted=cv2.resize(original,(math.ceil(fw*scale),math.ceil(fh*scale)))[:,:,:3]
   x=max(0,min(fitted.shape[1]-w,round(fitted.shape[1]*.62-w*.5)))
   y=(fitted.shape[0]-(b-a))//2;footage=fitted[y:y+b-a,x:x+w]
   region=frame[a:b,:,:3]
   fallback_color=np.median(region,axis=1)[:,None,:]
   distance=np.max(np.abs(region.astype(np.float32)-fallback_color),axis=2)
   alpha=np.clip(1-distance/12,0,1)[:,:,None]
   region[:]=np.uint8(region*(1-alpha)+footage*alpha)
  if s['name']=='connected':
   fx.device(im,native('tv:home',t*.3),1050,1660,1350,'tv',yaw=-8+7*p,opacity=ease(t/.8)*.85,reflect=False)
   md.connection(im,t)
  if s['name'] in ['mobile-intro','mobile-browse','mobile-details','mobile-clips','mobile-list']:
   md.gallery(im,t,mode='mobile',amount=.5)
  if len(s['sources'])>1:
   # Supporting screens remain dim and behind the current, readable hero.
   other=s['sources'][(choose(s,t)[2]+1)%len(s['sources'])]
   if other!=source:
    fx.device(im,native(other,0),3460+70*math.sin(t*.3),1180,455,'phone',yaw=-18+8*p,opacity=.33*ease(t/.8),reflect=False)
  arrival=ease(t/(2.1 if s['name']=='mobile-intro' else .9))
  heroY=fx.mix(-850,1080,arrival) if s['name']=='mobile-intro' else 1080+16*math.sin(t*.4)
  heroYaw=fx.mix(105,-7,arrival) if s['name']=='mobile-intro' else 11-18*p
  heroRoll=fx.mix(-24,-1,arrival) if s['name']=='mobile-intro' else -1+.8*math.sin(t*.4)
  md.shadow(im,1315,1010,510)
  q=fx.device(im,frame,2630+35*math.sin(t*.35),heroY,795+25*p,'phone',yaw=heroYaw,roll=heroRoll,pitch=1.5*math.sin(t*.3),reflect=s['name']=='mobile-intro')
  md.light_trace(im,t-.1,q,color=(48,124,173),duration=1.7)
  fx.over(im,label(s,t,True),0,0)
  md.accent(im,t,index,True)
  text(im,md.section(index),105,920,18,color=(137,147,163),alpha=ease((t-.4)/.8))
 elif s['kind']=='landscape':
  source,u,_=choose(s,t);screen=screen_for(s,t).copy()
  # Original synthetic eclipse footage is shown behind native playback controls.
  if source in ['mobile:player-controls','mobile:player']:
   base=cv2.resize(fx.footage(t),(screen.shape[1],screen.shape[0]))
   h=screen.shape[0];screen[int(.25*h):int(.67*h)]=base[int(.25*h):int(.67*h)]
  q=fx.quad(1920,1160,3390,3390*screen.shape[0]/screen.shape[1],yaw=-6+8*p,pitch=1-2*p)
  md.shadow(im,960,1020,1650);fx.project(im,screen,q);md.light_trace(im,t,q,color=(49,124,166))
  fx.over(im,label(s,t),0,0)
 else:
  source,u,_=choose(s,t);screen=screen_for(s,t).copy()
  if source=='tv:player':
   base=cv2.resize(fx.footage(t),(screen.shape[1],screen.shape[0]));hh=screen.shape[0]
   screen[int(.24*hh):int(.74*hh)]=base[int(.24*hh):int(.74*hh)]
  md.shadow(im,960,1050,1650)
  arrival=ease(t/1.3)
  yaw=fx.mix(-10 if s['name'] in ['tv-home','tv-details','tv-ambient'] else -4,2,arrival)+.8*math.sin(t*.27)
  q=fx.device(im,screen,1920+12*math.sin(t*.25),1210,fx.mix(3140,3320,p),'tv',yaw=yaw,pitch=.7*math.sin(t*.21),reflect=False)
  md.light_trace(im,t,q,duration=1.8)
  fx.over(im,label(s,t),0,0)
  md.accent(im,t,index)
 # Only the opening and end fade to black; internal edits use motivated transitions.
 fade=ease(t/.45) if index==0 else ease((dur-t)/.6) if index==len(SHOTS)-1 else 1
 if fade<.999:im=np.uint8(im*fade)
 return im
@lru_cache(maxsize=6)
def last_frame(index):return base_compose(index,SHOTS[index]['duration']-.01)
def scaled(frame,scale,dx=0,dy=0):
 m=np.float32([[scale,0,(1-scale)*W/2+dx],[0,scale,(1-scale)*H/2+dy]])
 return cv2.warpAffine(frame,m,(W,H),flags=cv2.INTER_LINEAR,borderMode=cv2.BORDER_REFLECT_101)
def compose(index,t):
 current=base_compose(index,t)
 if index==0 or t>=.6:return current
 p=ease(t/.6);previous=last_frame(index-1)
 # Choreograph edits around the actual story instead of rotating effects by index.
 modes={'tv-profiles':1,'tv-home':0,'tv-browsing':3,'tv-search':2,'tv-details':0,'tv-episodes':3,'tv-recommendations':1,'tv-info':2,'tv-player':0,'tv-audio':1,'tv-personalize':3,'tv-ambient':4,'mobile-intro':4,'mobile-browse':0,'mobile-search':2,'mobile-new':3,'mobile-games':1,'mobile-clips':4,'mobile-details':0,'mobile-player':0,'mobile-profiles':3,'mobile-downloads':2,'mobile-smart':1,'mobile-list':3,'connected':4,'preferences':1,'membership':2,'finale':4}
 mode=modes[SHOTS[index]['name']]
 if mode==0:
  # A camera push links one screen to the next rather than dropping to black.
  old=scaled(previous,1+.42*p);new=scaled(current,1.24-.24*p)
  return cv2.addWeighted(old,1-p,new,p,0)
 if mode==1:
  # Lens-like focus pull, kept short so UI stays readable once the scene settles.
  sigma=4*math.sin(math.pi*p)
  old=cv2.GaussianBlur(previous,(0,0),max(.1,sigma));new=cv2.GaussianBlur(current,(0,0),max(.1,sigma*.6))
  return cv2.addWeighted(old,1-p,new,p,0)
 if mode==2:
  # A narrow travelling light seam reveals the incoming screen.
  x=np.arange(W)[None,:,None];edge=p*(W+170)-85
  mask=np.clip((edge-x+60)/120,0,1).astype(np.float32)
  out=np.uint8(previous*(1-mask)+current*mask)
  seam=np.exp(-((x-edge)/17)**2).astype(np.float32)*math.sin(math.pi*p)
  return np.uint8(np.clip(out+seam*np.array([21,10,14],np.float32),0,255))
 if mode==3:
  old=scaled(previous,1,dx=-W*p*.2);new=scaled(current,1,dx=W*(1-p)*.2)
  return cv2.addWeighted(old,1-p,new,p,0)
 # Luminance-driven dissolve motivates a switch from dark TV to lit phone artwork.
 lum=cv2.cvtColor(current,cv2.COLOR_RGB2GRAY).astype(np.float32)/255
 mask=np.clip((p*1.8-1+lum)*5,0,1)[:,:,None]
 if p>.85:mask=np.maximum(mask,(p-.85)/.15)
 return np.uint8(previous*(1-mask)+current*mask)
def validate_sources():
 seen=sorted({src for s in SHOTS for src in s['sources']})
 records=[]
 for source in seen:
  frames,still=clip_paths(source);path=frames[0] if frames else still
  if not path.exists():raise FileNotFoundError(str(path))
  with Image.open(path) as im:size=im.size
  records.append({'source':source,'size':size,'motionFrames':len(frames),'captureFps':15,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
 (OUT/'native-provenance.json').write_text(json.dumps({'source':'Production Kotlin Compose, opt-in Robolectric native Skia captures','fixtureData':True,'customerAccountsUsed':False,'realPaymentMade':False,'captures':records},indent=2)+'\n')
 print('Validated',len(records),'native screen/component sources',flush=True)
def encode(index):
 s=SHOTS[index];output=SEG/f'{index+1:02d}-{s["name"]}.mp4';signature=hashlib.sha256(Path(__file__).read_bytes()+(HERE/'motion_design.py').read_bytes()+(HERE/'shot-list.json').read_bytes()+Path(fx.__file__).read_bytes()+(OUT/'native-provenance.json').read_bytes()).hexdigest()
 stamp=output.with_suffix('.json')
 if output.exists() and stamp.exists() and json.loads(stamp.read_text()).get('signature')==signature:return
 cmd=['ffmpeg','-hide_banner','-loglevel','error','-y','-f','rawvideo','-pix_fmt','rgb24','-s',f'{W}x{H}','-r',str(FPS),'-i','pipe:0','-an','-c:v','libx264','-preset','veryfast','-crf','18','-threads','1','-pix_fmt','yuv420p','-profile:v','high','-color_primaries','bt709','-color_trc','bt709','-colorspace','bt709','-movflags','+faststart',str(output)]
 start=time.monotonic();proc=subprocess.Popen(cmd,stdin=subprocess.PIPE)
 try:
  for i in range(s['duration']*FPS):proc.stdin.write(compose(index,i/FPS).tobytes())
  proc.stdin.close();code=proc.wait()
  if code:raise RuntimeError('Encoder failed')
 except BaseException:proc.kill();proc.wait();output.unlink(missing_ok=True);raise
 stamp.write_text(json.dumps({'signature':signature,'frames':s['duration']*FPS,'duration':s['duration']}))
 print(f'{index+1:02d} {s["name"]}: rendered in {time.monotonic()-start:.1f}s',flush=True)
def storyboard():
 cards=[]
 for i,s in enumerate(SHOTS):
  image=Image.fromarray(compose(i,min(s['duration']/2,2.5))).resize((480,270),Image.Resampling.LANCZOS)
  card=Image.new('RGB',(480,310),(7,9,14));card.paste(image)
  ImageDraw.Draw(card).text((12,281),f'{s["start"]:03d}s  {s["name"]}',font=ImageFont.truetype(str(FONTS/'OpenSans-Regular.ttf'),14),fill=(191,199,215));cards.append(card)
 board=Image.new('RGB',(1920,math.ceil(len(cards)/4)*310),(3,4,7))
 for i,card in enumerate(cards):board.paste(card,((i%4)*480,(i//4)*310))
 board.save(OUT/'review/Storyboard.jpg',quality=94)
 Image.fromarray(compose(0,3)).save(OUT/'review/Cover.jpg',quality=96)
def audio():
 fx.prepare();fx.build_audio()
 import wave
 path=ASSETS/'audio/mix.wav'
 with wave.open(str(path),'rb') as f:
  rate=f.getframerate();mix=np.frombuffer(f.readframes(f.getnframes()),dtype='<i2').astype(np.float32).reshape(-1,2)/32768
 rng=np.random.default_rng(20261004)
 for index,s in enumerate(SHOTS[1:],1):
  at=s['start']-.16;length=.7;x=np.arange(int(length*rate))/rate
  noise=rng.normal(0,1,len(x));noise=np.convolve(noise,np.ones(25)/25,mode='same')
  env=np.sin(np.pi*x/length)**2
  sweep=np.sin(2*np.pi*(850*x-520*x*x))*np.exp(-x*5)*.018
  whoosh=(noise*.2+sweep)*env
  pan=np.linspace(-.7,.7,len(x))*(-1 if index%2 else 1)
  effect=np.stack([whoosh*np.sqrt((1-pan)/2),whoosh*np.sqrt((1+pan)/2)],1)
  offset=int(max(0,at)*rate);n=min(len(effect),len(mix)-offset);mix[offset:offset+n]+=effect[:n]
  if index in [1,13,20,28]:
   x=np.arange(int(1.4*rate))/rate
   impact=np.sin(2*np.pi*(68*x-13*x*x))*np.exp(-x*5)*.085
   offset=int(s['start']*rate);n=min(len(impact),len(mix)-offset);mix[offset:offset+n]+=impact[:n,None]
 # Quiet interface ticks follow the individual screen changes within each chapter.
 for shot in SHOTS:
  for item in range(1,len(shot['sources'])):
   at=shot['start']+item*shot['duration']/len(shot['sources'])
   x=np.arange(int(.13*rate))/rate
   tone=(np.sin(2*np.pi*1040*x)+.3*np.sin(2*np.pi*1560*x))*np.exp(-x*34)*.023
   offset=int(at*rate);n=min(len(tone),len(mix)-offset)
   pan=.35 if shot['kind']=='mobile' else 0
   mix[offset:offset+n,0]+=tone[:n]*(1-pan)
   mix[offset:offset+n,1]+=tone[:n]*(1+pan)
 with wave.open(str(path),'wb') as f:
  f.setnchannels(2);f.setsampwidth(2);f.setframerate(rate);f.writeframes(np.int16(np.clip(mix,-.98,.98)*32767).tobytes())
 from audio_master import normalize
 normalize(path,ASSETS/'audio/master.wav')
def assemble():
 paths=[SEG/f'{i+1:02d}-{s["name"]}.mp4' for i,s in enumerate(SHOTS)]
 assert all(p.exists() for p in paths)
 listing=SEG/'concat.txt';listing.write_text(''.join("file '"+p.name+"'\n" for p in paths))
 chapters=OUT/'chapters.ffmetadata';chapters.write_text(';FFMETADATA1\ntitle=NetflixPro — TV and Phone\n'+''.join(f'[CHAPTER]\nTIMEBASE=1/1000\nSTART={s["start"]*1000}\nEND={s["end"]*1000}\ntitle={s["title"]}\n' for s in SHOTS))
 output=OUT/'NetflixPro-TV-and-Phone-3min.mp4'
 subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','concat','-safe','0','-i',str(listing),'-i',str(ASSETS/'audio/master.wav'),'-i',str(chapters),'-map','0:v','-map','1:a','-map_metadata','2','-map_chapters','2','-c:v','copy','-c:a','aac','-b:a','256k','-ar','48000','-t','180','-movflags','+faststart',str(output)],check=True)
 captions=[]
 def stamp(t):return f'{t//3600:02d}:{t//60%60:02d}:{t%60:02d},000'
 for i,s in enumerate(SHOTS):captions.append(f'{i+1}\n{stamp(s["start"])} --> {stamp(s["end"])}\n{s["title"]}\n{s["subtitle"]}\n')
 (OUT/'NetflixPro-Captions.srt').write_text('\n'.join(captions))
 print('Assembled',output,flush=True)
def main():
 p=argparse.ArgumentParser();p.add_argument('action',choices=['validate','storyboard','audio','render','scene','assemble']);p.add_argument('--index',type=int);args=p.parse_args()
 if args.action=='audio':audio();return
 validate_sources()
 if args.action=='validate':return
 if args.action=='storyboard':storyboard()
 elif args.action=='scene':encode(args.index)
 elif args.action=='assemble':assemble()
 else:
  storyboard()
  from concurrent.futures import ProcessPoolExecutor
  with ProcessPoolExecutor(max_workers=2) as pool:list(pool.map(encode,range(len(SHOTS))))
  assemble()
if __name__=='__main__':main()
