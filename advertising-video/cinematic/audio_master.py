"""Measure integrated loudness, apply fixed gain and a transparent peak limiter.

Fixed gain preserves the brief's quiet opening and musical build. The limiter
only catches peaks; it does not ride the volume of the entire film dynamically.
"""
from pathlib import Path
import json,math,re,subprocess

def measure(path):
 r=subprocess.run(['ffmpeg','-hide_banner','-i',str(path),'-vn','-af','loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json','-f','null','-'],capture_output=True,text=True,check=True)
 return json.loads(re.findall(r'\{\s*"input_i".*?\}',r.stderr,re.S)[-1])

def normalize(source,target):
 original=measure(source);gain=-16-float(original['input_i'])
 # Leave headroom for AAC inter-sample peaks. Compensate lookahead latency so
 # sound-design hits remain on their original camera/edit timestamps.
 for attempt in range(2):
  af=f'volume={gain:.6f}dB,alimiter=limit=0.75:attack=5:release=80:level=false:latency=true'
  subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(source),'-af',af,'-ar','48000','-c:a','pcm_s24le',str(target)],check=True)
  result=measure(target);error=-16-float(result['input_i'])
  if abs(error)<.08:break
  gain+=error
 report={'source':original,'normalized_pcm':result,'gain_db':gain,'sample_peak_limit':.75,'limiter_lookahead_ms':5,'release_ms':80,'latency_compensated':True}
 Path(target).with_suffix('.json').write_text(json.dumps(report,indent=2))
 return report
