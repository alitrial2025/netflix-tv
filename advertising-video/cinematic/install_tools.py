"""Fetch exact wheels through official PyPI JSON; preserve TLS and SHA-256."""
from pathlib import Path
import argparse, hashlib, json, subprocess, sys, urllib.request
from packaging.tags import sys_tags
from packaging.utils import parse_wheel_filename

p=argparse.ArgumentParser();p.add_argument('--target',default='/workspace/cloud-setup/film-python');args=p.parse_args()
root=Path(args.target);downloads=root.parent/'film-wheels';downloads.mkdir(parents=True,exist_ok=True)
tags=set(sys_tags());wheels=[]
for requirement in Path(__file__).with_name('requirements.txt').read_text().splitlines():
 name,version=requirement.split('==')
 with urllib.request.urlopen(f'https://pypi.org/pypi/{name}/{version}/json',timeout=30) as r:metadata=json.load(r)
 matches=[f for f in metadata['urls'] if f['filename'].endswith('.whl') and parse_wheel_filename(f['filename'])[3]&tags]
 if not matches:raise RuntimeError(f'No platform-compatible wheel for {requirement}')
 f=matches[0];path=downloads/f['filename']
 if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=f['digests']['sha256']:
  urllib.request.urlretrieve(f['url'],path)
 if hashlib.sha256(path.read_bytes()).hexdigest()!=f['digests']['sha256']:raise RuntimeError(f'Checksum mismatch: {path.name}')
 print(f'{requirement}: official SHA-256 verified',flush=True);wheels.append(str(path))
subprocess.run([sys.executable,'-m','pip','install','--target',str(root),'--no-index','--no-deps',*wheels],check=True)
