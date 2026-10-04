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
  for i,line in enumerate(lines):text(im,line,100,315+i*76,60,bold=True,alpha=entrance)
  for i,line in enumerate(wrap(s['subtitle'],36)):text(im,line,103,345+len(lines)*76+i*37,26,color=(170,176,187),alpha=entrance)
  text(im,'NETFLIXPRO  /  ANDROID PHONE',103,258,17,color=(255,80,93),bold=True,alpha=entrance)
 else:
  text(im,s['title'],95,45,43,bold=True,alpha=entrance)
  text(im,s['subtitle'],98,107,21,color=(175,181,192),alpha=entrance)
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
   opening=s['name']=='opening';reveal=ease(t/1.3);arrival=ease(t/3.0)
   fx.device(im,native('tv:home',0),fx.mix(1750,1620,arrival),1230,fx.mix(1940,2310,arrival),'tv',yaw=fx.mix(-18,2,arrival),opacity=reveal,reflect=False)
   fx.device(im,native('mobile:home',0),fx.mix(3760,3030,arrival),1150,fx.mix(670,755,arrival),'phone',yaw=fx.mix(21,4,arrival),roll=-2,opacity=reveal,reflect=False)
   title=s['title'];bit=type_layer(title,62 if opening else 72,bold=True)
   fx.over(im,bit,(W-bit.shape[1])//2,68+int(15*(1-reveal)),reveal)
   sub=s['subtitle'];bit=type_layer(sub,25,color=(177,184,196));fx.over(im,bit,(W-bit.shape[1])//2,169,reveal)
   if not opening:
    bit=type_layer('npro-app.vercel.app',31,color=(252,77,88),bold=True);fx.over(im,bit,(W-bit.shape[1])//2,919,ease(t/.7))
 elif s['kind']=='mobile':
  source,u,_=choose(s,t);frame=native(source,u)
  fx.device(im,frame,2630+35*math.sin(t*.35),1080+16*math.sin(t*.4),795+25*p,'phone',yaw=9-16*p,roll=-1+.8*math.sin(t*.4),reflect=False)
  if len(s['sources'])>1:
   # Supporting screens remain dim and behind the current, readable hero.
   other=s['sources'][(choose(s,t)[2]+1)%len(s['sources'])]
   if other!=source:
    fx.device(im,native(other,0),3460,1180,455,'phone',yaw=-13,opacity=.35,reflect=False)
  fx.over(im,label(s,t,True),0,0)
  text(im,'ANDROID PHONE',105,920,18,color=(137,147,163))
 elif s['kind']=='landscape':
  source,u,_=choose(s,t);screen=native(source,u).copy()
  # Original synthetic eclipse footage is shown behind native playback controls.
  if source in ['mobile:player-controls','mobile:player']:
   base=cv2.resize(fx.footage(t),(screen.shape[1],screen.shape[0]))
   h=screen.shape[0];screen[int(.25*h):int(.67*h)]=base[int(.25*h):int(.67*h)]
  fx.project(im,screen,fx.quad(1920,1160,3390,3390*screen.shape[0]/screen.shape[1],yaw=-2+4*p))
  fx.over(im,label(s,t),0,0)
 else:
  source,u,_=choose(s,t);screen=native(source,u).copy()
  if source=='tv:player':
   base=cv2.resize(fx.footage(t),(screen.shape[1],screen.shape[0]));hh=screen.shape[0]
   screen[int(.24*hh):int(.74*hh)]=base[int(.24*hh):int(.74*hh)]
  fx.device(im,screen,1920,1210,3230+80*p,'tv',yaw=-2.5+4*p,pitch=.3,reflect=False)
  fx.over(im,label(s,t),0,0)
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
 p=ease(t/.6);previous=last_frame(index-1);mode=index%5
 if mode==0:
  # A camera push links one screen to the next rather than dropping to black.
  old=scaled(previous,1+.16*p);new=scaled(current,1.16-.16*p)
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
 s=SHOTS[index];output=SEG/f'{index+1:02d}-{s["name"]}.mp4';signature=hashlib.sha256(Path(__file__).read_bytes()+(HERE/'shot-list.json').read_bytes()+Path(fx.__file__).read_bytes()+(OUT/'native-provenance.json').read_bytes()).hexdigest()
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
