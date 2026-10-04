"""Create a Resolve-importable FCP7 XML and verify the delivered film."""
from pathlib import Path
import argparse,hashlib,json,subprocess,shutil,xml.etree.ElementTree as E,zipfile
HERE=Path(__file__).resolve().parents[1]
ROOT=Path('/workspace/artifacts/showcase-20261004')
OUT=ROOT/'output';SHOTS=json.loads((HERE/'shot-list.json').read_text())
RATE=30
def child(parent,key,value=None,**attrib):
 node=E.SubElement(parent,key,attrib)
 if value is not None:node.text=str(value)
 return node
def rate(parent):
 r=child(parent,'rate');child(r,'timebase',RATE);child(r,'ntsc','FALSE')
def file_node(parent,index,name,duration,path,audio=False):
 f=child(parent,'file',id=f'file-{index}');child(f,'name',name);child(f,'pathurl',path.as_uri());rate(f);child(f,'duration',duration)
 media=child(f,'media')
 if audio:
  a=child(media,'audio');sc=child(a,'samplecharacteristics');child(sc,'depth',24);child(sc,'samplerate',48000);child(a,'channelcount',2)
 else:
  video=child(media,'video');sc=child(video,'samplecharacteristics');rate(sc);child(sc,'width',1920);child(sc,'height',1080);child(sc,'anamorphic','FALSE');child(sc,'pixelaspectratio','square');child(sc,'fielddominance','none')
 return f
def timeline():
 package=OUT/'resolve/NetflixPro-Resolve-Project';(package/'media').mkdir(parents=True,exist_ok=True)
 root=E.Element('xmeml',version='4');sequence=child(root,'sequence',id='NetflixPro-Showcase');child(sequence,'name','NetflixPro — TV and Phone — 3 minutes');child(sequence,'duration',5400);rate(sequence)
 tc=child(sequence,'timecode');rate(tc);child(tc,'string','00:00:00:00');child(tc,'frame',0);child(tc,'displayformat','NDF')
 media=child(sequence,'media');video=child(media,'video');form=child(video,'format');sc=child(form,'samplecharacteristics');rate(sc)
 for k,v in [('width',1920),('height',1080),('anamorphic','FALSE'),('pixelaspectratio','square'),('fielddominance','none')]:child(sc,k,v)
 track=child(video,'track')
 for i,shot in enumerate(SHOTS):
  source=OUT/'segments'/f'{i+1:02d}-{shot["name"]}.mp4';target=package/'media'/source.name;shutil.copy2(source,target)
  frames=shot['duration']*RATE;clip=child(track,'clipitem',id=f'clip-{i+1}');child(clip,'name',shot['title']);child(clip,'duration',frames);rate(clip)
  for k,v in [('start',shot['start']*RATE),('end',shot['end']*RATE),('in',0),('out',frames),('enabled','TRUE')]:child(clip,k,v)
  file_node(clip,i+1,source.name,frames,target)
  st=child(clip,'sourcetrack');child(st,'mediatype','video');child(st,'trackindex',1)
 audio=child(media,'audio');form=child(audio,'format');sc=child(form,'samplecharacteristics');child(sc,'depth',16);child(sc,'samplerate',48000)
 source=ROOT/'assets/audio/master.wav';target=package/'media/NetflixPro-Original-Score-and-SFX.wav';shutil.copy2(source,target)
 for channel in [1,2]:
  at=child(audio,'track');clip=child(at,'clipitem',id=f'audio-{channel}');child(clip,'name','Original score + sound design');child(clip,'duration',5400);rate(clip)
  for k,v in [('start',0),('end',5400),('in',0),('out',5400)]:child(clip,k,v)
  if channel==1:file_node(clip,1000,target.name,5400,target,True)
  else:child(clip,'file',id='file-1000')
  st=child(clip,'sourcetrack');child(st,'mediatype','audio');child(st,'trackindex',channel)
 E.indent(root)
 xml=package/'NetflixPro-Showcase.xml';xml.write_text('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE xmeml>\n'+E.tostring(root,encoding='unicode'))
 # Archive retains all media and relative paths; Resolve's Media Search relinks after extraction.
 for p in [OUT/'NetflixPro-Captions.srt',HERE/'shot-list.json',OUT/'native-provenance.json']:shutil.copy2(p,package/p.name)
 (package/'README.txt').write_text('''The finished video is already edited and ready to watch. No editing is required.\n\nOptional DaVinci Resolve import:\n1. Extract this complete folder.\n2. In Resolve choose File > Import > Timeline and select NetflixPro-Showcase.xml.\n3. If prompted for media, select this folder's media directory.\n4. Timeline: 1920x1080, 30 fps, exactly 180 seconds.\n\nThe 29 main scenes are separately editable clips. Camera work, titles and transitions are baked into these clips; this is an XML interchange timeline, not a proprietary .drp file. The stereo score and sound design are on the audio tracks. Captions are included as SRT.\n\nNative Kotlin UI is shown using synthetic profiles/catalogue. Original procedural playback footage is illustrative. This film does not measure live stream speed or demonstrate a real payment.\n''')
 archive=OUT/'NetflixPro-Resolve-Project.zip'
 with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=2) as z:
  for p in package.rglob('*'):
   if p.is_file():z.write(p,p.relative_to(package.parent))
 print('Resolve interchange timeline and complete media archive ready',flush=True)
def verify():
 path=OUT/'NetflixPro-TV-and-Phone-3min.mp4'
 info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-show_chapters','-of','json',str(path)]))
 video=next(s for s in info['streams'] if s['codec_type']=='video');audio=next(s for s in info['streams'] if s['codec_type']=='audio')
 assert (video['width'],video['height'])==(1920,1080)
 assert video['r_frame_rate']=='30/1' and int(video['nb_frames'])==5400
 assert abs(float(info['format']['duration'])-180)<.05
 assert audio['channels']==2 and audio['sample_rate']=='48000'
 assert len(info['chapters'])==29
 subprocess.run(['ffmpeg','-v','error','-threads','1','-i',str(path),'-f','null','-'],check=True)
 xml=OUT/'resolve/NetflixPro-Resolve-Project/NetflixPro-Showcase.xml';r=E.parse(xml).getroot();sequence=r.find('sequence')
 assert sequence.findtext('duration')=='5400'
 clips=sequence.findall('media/video/track/clipitem');assert len(clips)==29
 assert [int(c.findtext('start')) for c in clips]==[s['start']*RATE for s in SHOTS]
 assert [int(c.findtext('end')) for c in clips]==[s['end']*RATE for s in SHOTS]
 report={'finishedVideo':path.name,'durationSeconds':180,'width':1920,'height':1080,'fps':30,'frames':5400,'chapters':29,'stereoAudio':True,'fullDecodePassed':True,'resolveXmlValidated':True,'resolveApplicationImportTested':False,'realPaymentMade':False,'fixtureData':True,'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
 (OUT/'Verification.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
def main():
 p=argparse.ArgumentParser();p.add_argument('action',choices=['timeline','verify']);args=p.parse_args()
 timeline() if args.action=='timeline' else verify()
if __name__=='__main__':main()
