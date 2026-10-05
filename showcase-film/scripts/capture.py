"""Install/remove film-only capture harnesses without changing production app code."""
from pathlib import Path
import argparse,shutil
ROOT=Path(__file__).resolve().parents[2]
def main():
 p=argparse.ArgumentParser();p.add_argument('action',choices=['install','remove']);p.add_argument('--mobile-root',type=Path,default=ROOT.parent/'netflix-mobile');args=p.parse_args()
 for app,name in [(ROOT,'ShowcaseTvCaptureTest'),(args.mobile_root,'ShowcaseMobileCaptureTest')]:
  target=app/'app/src/test/java/com/example'/f'{name}.kt'
  if args.action=='install':
   target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/'showcase-film/native'/f'{name}.kt',target)
  else:target.unlink(missing_ok=True)
 print('Capture harnesses '+('installed into test source sets' if args.action=='install' else 'removed'))
if __name__=='__main__':main()
