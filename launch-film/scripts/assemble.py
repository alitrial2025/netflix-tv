"""Join eight silent, closed-GOP frame ranges and mux one continuous score."""
import subprocess,sys
from pathlib import Path
out=Path(sys.argv[1]).resolve();out.mkdir(parents=True,exist_ok=True)
chunks=sorted(Path(sys.argv[2]).resolve().rglob('chunk-*.mp4'))
assert len(chunks)==8,chunks
listing=out/'chunks.txt'
listing.write_text(''.join("file '"+str(f).replace("'","'\\''")+"'\n" for f in chunks))
score=Path(__file__).resolve().parents[1]/'public'/'score.wav'
master=out/'NetflixPro-Launch-4K.mp4'
subprocess.run(['ffmpeg','-y','-v','warning','-f','concat','-safe','0','-i',str(listing),'-i',str(score),'-map','0:v:0','-map','1:a:0','-c:v','copy','-c:a','aac','-b:a','320k','-ar','48000','-movflags','+faststart','-t','36',str(master)],check=True)
subprocess.run(['ffmpeg','-y','-v','warning','-i',str(master),'-vf','scale=1920:1080:flags=lanczos','-c:v','libx264','-crf','18','-preset','slow','-pix_fmt','yuv420p','-c:a','copy','-movflags','+faststart',str(out/'NetflixPro-Launch-1080p.mp4')],check=True)
subprocess.run(['ffmpeg','-y','-v','warning','-i',str(master),'-vf','fps=1/3,scale=640:360,tile=4x3','-frames:v','1',str(out/'contact-sheet.jpg')],check=True)
subprocess.run([sys.executable,str(Path(__file__).with_name('verify-film.py')),str(out)],check=True)
