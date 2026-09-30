#!/usr/bin/env python3
"""Verify the encoded deliverable, not just the intended render settings."""
from pathlib import Path
from fractions import Fraction
import hashlib,json,re,subprocess,sys
import numpy as np
from PIL import Image,ImageDraw,ImageFont
import film

root=film.OUT/'final';master=root/'NetflixPro-Cinematic-TV-Mobile-4K.mp4'
info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_format','-show_streams','-show_chapters','-of','json',str(master)]))
v=next(s for s in info['streams'] if s['codec_type']=='video');a=next(s for s in info['streams'] if s['codec_type']=='audio')
assert (v['width'],v['height'])==(3840,2160)
assert Fraction(v['avg_frame_rate'])==30 and int(v['nb_frames'])==5400
assert abs(float(info['format']['duration'])-180)<.001
assert v['codec_name']=='h264' and v['pix_fmt']=='yuv420p'
assert a['codec_name']=='aac' and int(a['sample_rate'])==48000 and int(a['channels'])==2
assert len(info['chapters'])==14
for chapter,(start,end,_) in zip(info['chapters'],film.SCENES):
 assert abs(float(chapter['start_time'])-start)<.001 and abs(float(chapter['end_time'])-end)<.001

# Decode the entire master with errors made fatal. Read small analysis frames
# through a bounded pipe; this also checks frame count and intentional end fade.
cmd=['ffmpeg','-v','error','-xerror','-err_detect','explode','-threads','2','-i',str(master),'-an','-vf','scale=160:90','-pix_fmt','rgb24','-f','rawvideo','pipe:1']
means=[];differences=[];previous=None;size=160*90*3
proc=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
while True:
 data=proc.stdout.read(size)
 if not data:break
 assert len(data)==size,'Truncated decoded frame'
 im=np.frombuffer(data,np.uint8).reshape(90,160,3)
 means.append(float(im.mean()))
 if previous is not None:differences.append(float(np.abs(im.astype(np.int16)-previous).mean()))
 previous=im.astype(np.int16)
errors=proc.stderr.read().decode();assert proc.wait()==0,errors
assert len(means)==5400
assert means[-1]<.2,'The master must finish on black'
assert min(means[12*30:170*30])>.7,'Unexpected blank frame inside the app journey'

# Measure loudness and true peak from the actual AAC stream.
run=subprocess.run(['ffmpeg','-hide_banner','-threads','2','-i',str(master),'-vn','-af','loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json','-f','null','-'],capture_output=True,text=True,check=True)
match=re.findall(r'\{\s*"input_i".*?\}',run.stderr,re.S);assert match,'No loudness analysis'
loud=json.loads(match[-1]);assert abs(float(loud['input_i'])+16)<1.0
assert float(loud['input_tp'])<=-1.0,'Encoded audio true peak is too high'

# Decode representative frames from the finished film, including transitions,
# both UI platforms, native focus changes, original footage and final branding.
stills=root/'encoded-inspection';stills.mkdir(exist_ok=True)
times=[3,8.5,17,29,35,48,55,61,68,78,89,102,110,119,122,131.5,135,138.5,141.5,150,163,173,177.5,179.9666667]
thumbs=[]
for index,t in enumerate(times):
 path=stills/f'{index:02d}-{t:06.2f}.jpg'
 subprocess.run(['ffmpeg','-v','error','-y','-ss',str(t),'-threads','2','-i',str(master),'-frames:v','1','-vf','scale=1280:720','-q:v','2',str(path)],check=True)
 with Image.open(path) as image:
  thumb=image.convert('RGB').resize((480,270))
 canvas=Image.new('RGB',(480,310),(5,6,9));canvas.paste(thumb)
 ImageDraw.Draw(canvas).text((15,282),f'{int(t//60):02d}:{t%60:04.1f} — encoded master',font=ImageFont.truetype(str(film.FONTS/'OpenSans-Regular.ttf'),14),fill=(166,173,188));thumbs.append(canvas)
board=Image.new('RGB',(1920,1860),(2,3,5))
for i,im in enumerate(thumbs):board.paste(im,((i%4)*480,(i//4)*310))
board.save(root/'Encoded-Review.jpg',quality=93)
exports={group:{folder.name:len(list(folder.glob('*.png'))) for folder in (film.A/group).iterdir() if folder.is_dir()} for group in ['native-tv','native-mobile']}
for group,clips in exports.items():
 for name,count in clips.items():assert count>0,f'Missing native frames: {group}/{name}'
report={'master':master.name,'bytes':master.stat().st_size,'sha256':hashlib.sha256(master.read_bytes()).hexdigest(),'duration_seconds':float(info['format']['duration']),'width':v['width'],'height':v['height'],'fps':str(Fraction(v['avg_frame_rate'])),'frames':len(means),'video_codec':v['codec_name'],'pixel_format':v['pix_fmt'],'color_space':v.get('color_space'),'audio_codec':a['codec_name'],'audio_rate':a['sample_rate'],'audio_channels':a['channels'],'audio_loudness':loud,'chapters':info['chapters'],'full_decode_passed':True,'final_frame_mean_rgb':means[-1],'minimum_journey_frame_mean_rgb':min(means[360:5100]),'native_frame_exports':exports,'visual_inspection_samples':len(times),'scope':'Encoded-film checks; native Kotlin UI capture with staged fixture content. Not provider playback or device performance certification.'}
(root/'Verification.json').write_text(json.dumps(report,indent=2))
print(json.dumps({k:report[k] for k in ['duration_seconds','width','height','fps','frames','bytes','full_decode_passed','final_frame_mean_rgb']},indent=2))
print('Actual encoded audio:',loud)
