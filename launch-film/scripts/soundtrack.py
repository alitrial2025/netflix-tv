"""Original 36-second stereo score and synchronized foley; no sampled music."""
from pathlib import Path
import wave
import numpy as np

SR=48000
DURATION=36
rng=np.random.default_rng(711)
t=np.arange(SR*DURATION)/SR
mix=np.zeros((len(t),2),dtype=np.float64)

def add(start, signal, gain=1, pan=0):
    at=int(start*SR)
    signal=signal[:max(0,len(t)-at)]
    mix[at:at+len(signal),0]+=signal*gain*np.sqrt((1-pan)/2)
    mix[at:at+len(signal),1]+=signal*gain*np.sqrt((1+pan)/2)

def note(freq,length,pluck=False):
    u=np.arange(int(length*SR))/SR
    env=(1-np.exp(-u*(70 if pluck else 2.0)))*np.exp(-u*(2.2 if pluck else .19))
    env*=np.minimum(1,(length-u)/.4)
    return env*(np.sin(2*np.pi*freq*u)+.24*np.sin(2*np.pi*freq*2.003*u)+.12*np.sin(2*np.pi*freq*3*u))

# C minor — A flat — E flat — B flat; voicings and melody composed here.
chords=[[130.813,155.563,195.998],[103.826,130.813,155.563],[155.563,195.998,233.082],[116.541,146.832,174.614]]
for block in range(6):
    start=block*6
    chord=chords[block%4]
    for i,freq in enumerate(chord):
        add(start,note(freq,6.8),.10,[-.6,0,.6][i])
        add(start,note(freq*1.001,6.8),.037,-[-.6,0,.6][i])
    add(start,note(chord[0]/2,6.3),.10)

beat=.625
for n in range(42):
    start=7+n*beat
    if start>33:break
    chord=chords[int(start//6)%4]
    add(start,note(chord[[0,2,1,2][n%4]]*2,1.9,True),.052,(-1 if n%2 else 1)*.4)
    u=np.arange(int(.26*SR))/SR
    kick=np.sin(2*np.pi*(49*u+26*(1-np.exp(-u*24))/24))*np.exp(-u*22)
    if n%2==0:add(start,kick,.105)
    if n%4==2:
        u=np.arange(int(.1*SR))/SR
        noise=rng.normal(size=len(u))
        add(start,(noise-np.roll(noise,1))*.15*np.exp(-u*48),.10,.2)

def whoosh(start,length,gain,pan=0):
    u=np.arange(int(length*SR))/SR
    raw=rng.normal(size=len(u))
    filt=np.convolve(raw,np.ones(15)/15,mode='same')
    env=np.sin(np.pi*u/length)**2
    add(start,filt*env,gain,pan)

# TV lands, relaxed breath, footstep exit, click, camera dive, phone catch.
add(2.72,note(67,.22,True),.30)
whoosh(3.42,.83,.045,-.1)
for stamp in [4.65,5.14,5.64,6.13]:add(stamp,note(92,.14,True),.095,.25)
add(9.58,note(1800,.04,True),.25)
whoosh(10.6,1.4,.19,.10)
whoosh(18.17,1.0,.19,-.35)
add(19.83,note(154,.10,True),.19)
whoosh(21.8,.8,.12,.3)
add(28.05,note(523.25,3.4,True),.11,-.3)
add(28.21,note(783.99,3.6,True),.10,.3)
add(28.39,note(1046.5,4.1,True),.08)
fade=np.minimum(1,t/1.2)*np.minimum(1,(DURATION-t)/1.5)
mix*=fade[:,None]
peak=np.max(np.abs(mix))
mix*=.86/max(peak,1e-9)
pcm=(np.clip(mix,-1,1)*32767).astype('<i2')
out=Path(__file__).resolve().parents[1]/'public'/'score.wav'
out.parent.mkdir(parents=True,exist_ok=True)
with wave.open(str(out),'wb') as handle:
    handle.setnchannels(2);handle.setsampwidth(2);handle.setframerate(SR);handle.writeframes(pcm.tobytes())
print(f'Original score: {out} | {DURATION}s | 48 kHz stereo | peak {np.max(np.abs(mix)):.3f}')
