"""Fail on missing frames, wrong dimensions/audio, corruption or timeline drift."""
import hashlib,json,subprocess,sys
from pathlib import Path

folder=Path(sys.argv[1])
report=[]
for name,size in [('NetflixPro-Launch-4K.mp4',(3840,2160)),('NetflixPro-Launch-1080p.mp4',(1920,1080))]:
    file=folder/name
    probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(file)]))
    video=next(s for s in probe['streams'] if s['codec_type']=='video')
    audio=next(s for s in probe['streams'] if s['codec_type']=='audio')
    assert (video['width'],video['height'])==size,video
    assert video['avg_frame_rate']=='30/1',video
    assert int(video['nb_frames'])==1080,video
    assert abs(float(probe['format']['duration'])-36)<.08,probe['format']
    assert audio['channels']==2 and int(audio['sample_rate'])==48000,audio
    subprocess.run(['ffmpeg','-v','error','-i',str(file),'-f','null','-'],check=True)
    report.append({'file':name,'width':size[0],'height':size[1],'fps':30,'frames':1080,'duration':36,'audio':'48kHz stereo','bytes':file.stat().st_size,'sha256':hashlib.sha256(file.read_bytes()).hexdigest()})
(folder/'verification.json').write_text(json.dumps({'verified':True,'videos':report},indent=2))
print(json.dumps(report,indent=2))
