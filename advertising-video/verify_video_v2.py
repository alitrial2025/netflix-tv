"""Verify the delivered MP4 and inspect a small set of encoded frames gently."""
from pathlib import Path
import datetime, json, os, re, subprocess, time
import render_video as core
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parent
OUT=ROOT/'revision-2'
VIDEO=ROOT/'NetflixPro-TV-3min-v2-1080p60.mp4'
FFMPEG=core.FFMPEG

def main():
    core.set_idle_priority()
    info=core.mp4_video_info(VIDEO)
    assert info and info['frames']==10800 and abs(info['duration']-180)<.01, info
    metadata=OUT/'Encoded-Chapters.ffmetadata'
    result=subprocess.run([str(FFMPEG),'-y','-hide_banner','-threads','1','-i',str(VIDEO),
                           '-f','ffmetadata',str(metadata)],capture_output=True,text=True,
                          encoding='utf-8',errors='replace',check=True,creationflags=core.LOW_LOAD_FLAGS)
    report=result.stderr
    assert re.search(r'Video: h264.*1920x1080.*60 fps',report), report
    assert re.search(r'Audio: aac.*48000 Hz, stereo',report), report
    assert 'Duration: 00:03:00.00' in report, report
    assert metadata.read_text(encoding='utf-8').count('[CHAPTER]')==12
    inspection=OUT/'encoded-inspection';inspection.mkdir(exist_ok=True)
    moments=[('profile-motion',10.7),('home-stage',23.1),('categories-fade',37.9),
             ('rows-before',60.1),('rows-moving',60.35),('rows-after',61.1),
             ('details-transition',75.3),('details',76.8),('details-more-info',90.7),
             ('recommendations',95.2),('episodes',109.0),('player-language',157.7)]
    sheet=Image.new('RGB',(1920,1080),'#050507')
    font=ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf',16)
    decoded=[]
    for n,(label,at) in enumerate(moments):
        target=inspection/f'{n:02d}-{label}.jpg'
        subprocess.run([str(FFMPEG),'-y','-hide_banner','-loglevel','error',
                        '-threads','1','-filter_threads','1','-filter_complex_threads','1',
                        '-ss',str(at),'-i',str(VIDEO),'-an','-frames:v','1',
                        '-vf','scale=960:540','-threads','1',str(target)],
                       check=True,creationflags=core.LOW_LOAD_FLAGS)
        with Image.open(target) as im:
            assert im.size==(960,540)
            bit=im.resize((624,351),Image.Resampling.LANCZOS)
        xx=16+(n%3)*640;yy=12+(n//3)*270
        # A 16:9 tile uses 256px height; labels stay readable on the contact sheet.
        bit=bit.resize((456,256),Image.Resampling.LANCZOS)
        xx=16+(n%4)*480;yy=12+(n//4)*360
        sheet.paste(bit,(xx,yy))
        ImageDraw.Draw(sheet).text((xx+8,yy+270),f'{at:.2f}s  {label}',font=font,fill='white')
        decoded.append({'time':at,'label':label,'path':str(target)})
        print('Decoded '+label,flush=True)
        time.sleep(2)
    sheet.save(OUT/'Encoded-Video-Check-v2.jpg',quality=94)
    verification={'verified_at':datetime.datetime.now().astimezone().isoformat(),
                  'path':str(VIDEO),'bytes':VIDEO.stat().st_size,
                  'duration_seconds':info['duration'],'frames':info['frames'],
                  'resolution':[1920,1080],'fps':60,'video_codec':'H.264',
                  'audio_codec':'AAC','audio_rate_hz':48000,'audio_channels':2,
                  'chapters':12,'decoded_samples':decoded,
                  'verification':'Container metadata, exact frame count and sampled frame decoding. Code-rendered interface demonstration.'}
    (OUT/'Video-Verification.json').write_text(json.dumps(verification,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in verification.items() if k!='decoded_samples'},indent=2),flush=True)

if __name__=='__main__':main()
