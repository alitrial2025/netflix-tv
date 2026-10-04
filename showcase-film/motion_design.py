"""Deterministic cinematography layers for the native-screen product film."""
from functools import lru_cache
from pathlib import Path
import io,math
import cv2,numpy as np
from PIL import Image,ImageDraw
import film as fx

HERE=Path(__file__).resolve().parent
W,H=1920,1080
ART=HERE.parent/'advertising-video/assets'

@lru_cache(maxsize=12)
def glow(width,height,color):
 y,x=np.mgrid[-1:1:complex(0,height),-1:1:complex(0,width)]
 alpha=np.uint8(np.exp(-(x*x+y*y)*4.5)*145)
 out=np.zeros((height,width,4),np.uint8);out[:,:,:3]=color;out[:,:,3]=alpha
 return out

@lru_cache(maxsize=16)
def poster(index):
 files=sorted(ART.glob('*-poster.jpg'));path=files[index%len(files)]
 with Image.open(path) as im:
  image=im.convert('RGBA');image.thumbnail((360,500),Image.Resampling.LANCZOS)
  card=Image.new('RGBA',(image.width+12,image.height+12),(15,18,26,255))
  card.alpha_composite(image,(6,6))
  ImageDraw.Draw(card).rounded_rectangle((0,0,card.width-1,card.height-1),8,outline=(81,90,106,255),width=2)
 return np.array(card)

@lru_cache(maxsize=4)
def brand(width=360):
 import cairosvg
 png=cairosvg.svg2png(url=str(HERE.parent/'assets/branding/netflixpro.svg'),output_width=width)
 with Image.open(io.BytesIO(png)) as im:return np.array(im.convert('RGBA'))

def illuminate(image,t,index,kind):
 """Moving key and rim lights, a perspective floor, and depth-dependent dust."""
 mobile=kind=='mobile';a=t+index*.83
 fx.over(image,glow(880,800,(49,14,34)),int(-380+80*math.sin(a*.21)),190,.28)
 fx.over(image,glow(900,850,(16,49,76)),int(1140+85*math.cos(a*.17)),-80,.37 if mobile else .23)
 # Low-contrast vanishing lines establish a shared stage beneath the devices.
 vanish=np.array([960+65*math.sin(a*.08),715.0])
 for x in range(-1000,3001,310):
  end=np.array([x,1090.0]);start=vanish*.71+end*.29
  cv2.line(image,tuple(start.astype(int)),tuple(end.astype(int)),(13,17,24),1,cv2.LINE_AA)
 for y in [790,845,915,1004,1075]:
  cv2.line(image,(0,y),(W,y),(12,16,22),1,cv2.LINE_AA)
 # Arc trails live behind the products, never across their screen contents.
 for j in range(3):
  x=np.linspace(-120,2050,88);y=800+j*27+100*np.sin(x/940+a*.13+j*.4)
  points=np.stack([x,y],1).astype(np.int32)
  cv2.polylines(image,[points],False,(22+j*5,18+j*3,31+j*4),1,cv2.LINE_AA)
 for j in range(44):
  depth=.2+(j*17%31)/40
  x=(j*197.23+math.sin(a*.21+j)*45+t*(4+depth*10))%W
  y=(j*89.7-t*(3+depth*7))%H
  opacity=.11+.08*math.sin(a*.37+j)
  fx.over(image,glow(14,14,(92,128,161)),int(x-7),int(y-7),opacity)

def gallery(image,t,mode='opening',amount=1):
 """An orbiting gallery of artwork with perspective, parallax and focus falloff."""
 travel=fx.ease(t/3)
 for j in range(8):
  phase=(j-3.5)*.45+t*.085
  depth=.35+(j%3)*.22
  cx=1920+math.sin(phase)*2350
  cy=1080+math.cos(phase*1.7+j*.2)*720
  width=260+depth*230
  if mode=='mobile':cx+=540
  texture=poster(j*3+1)
  yaw=-25*math.sin(phase);roll=7*math.cos(phase+j)
  q=fx.quad(cx,cy,width,width*texture.shape[0]/texture.shape[1],yaw=yaw,roll=roll,z=300*(1-depth))
  fx.project(image,texture,q,opacity=amount*(.10+.12*depth)*fx.ease(t/.7),blur=2.5*(1-depth))

def shadow(image,cx,cy,width):
 fx.over(image,glow(width,65,(0,0,0)),int(cx-width/2),int(cy-20),.65)

def connection(image,t):
 """A travelling light joins the phone and the TV in the connected chapter."""
 u=np.linspace(0,1,100)
 points=(1-u[:,None])**3*np.array([825,803])+3*(1-u[:,None])**2*u[:,None]*np.array([1040,870])+3*(1-u[:,None])*u[:,None]**2*np.array([950,440])+u[:,None]**3*np.array([1135,510])
 reveal=fx.ease((t-.4)/1.5);count=max(2,round(len(points)*reveal))
 cv2.polylines(image,[points[:count].astype(np.int32)],False,(24,61,79),2,cv2.LINE_AA)
 if reveal>.01:
  position=points[min(count-1,round((.5+.5*math.sin(t*1.8-1.2))*(count-1)))]
  fx.over(image,glow(75,75,(49,161,215)),int(position[0]-37),int(position[1]-37),.62*reveal)

def light_trace(image,t,q,color=(175,44,69),duration=1.6):
 """A short moving highlight follows the device's projected bezel."""
 if not 0<t<duration:return
 closed=np.concatenate([q,q[:1]],0);distance=t/duration*4
 segment=min(3,int(distance));fraction=distance-segment
 start=closed[segment];end=closed[segment+1];point=start+(end-start)*fraction
 cv2.line(image,tuple(start.astype(int)),tuple(point.astype(int)),color,1,cv2.LINE_AA)
 fx.over(image,glow(55,55,color),int(point[0]-27),int(point[1]-27),.37*math.sin(math.pi*t/duration))

def title(image,bit,x,y,t,delay=0):
 """Staggered rising typography revealed through a fixed, clean aperture."""
 p=fx.ease((t-delay)/.75)
 if p<=0:return
 rise=round(35*(1-p));layer=np.zeros_like(bit)
 if rise<bit.shape[0]:layer[rise:]=bit[:bit.shape[0]-rise]
 fx.over(image,layer,x,y,p)

def section(index):
 if index<13:return '01   THE BIG SCREEN'
 if index<25:return '02   IN YOUR HAND'
 return '03   ONE EXPERIENCE'

def accent(image,t,index,phone=False):
 p=fx.ease((t-.22)/1.05)
 x,y=(104,235) if phone else (98,30)
 cv2.line(image,(x,y),(x+round(56*p),y),(245,65,90),2,cv2.LINE_AA)
 # A small moving glint punctuates the reveal, then leaves the typography alone.
 if t<1.4:
  fx.over(image,glow(30,30,(207,70,113)),x+round(56*p)-15,y-15,.35*math.sin(math.pi*min(1,t/1.4)))
