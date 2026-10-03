"""Guard against blank Three.js device screens in representative render frames."""
from pathlib import Path
import sys
import numpy as np
from PIL import Image

root=Path(sys.argv[1])
checks=[(500,(.12,.12,.88,.82)),(735,(.29,.25,.40,.65)),(1010,(.23,.38,.40,.57))]
for frame,box in checks:
    image=Image.open(root/f'element-{frame:04d}.png').convert('RGB')
    w,h=image.size
    pixels=np.asarray(image.crop(tuple(int(v*(w if i%2==0 else h)) for i,v in enumerate(box))))
    white=float(np.mean(np.min(pixels,axis=2)>245))
    variation=float(np.std(pixels))
    assert white<.70 and variation>15, f'Blank native UI at frame {frame}: white={white:.3f}, variation={variation:.3f}'
    print(f'Native texture visible: frame={frame}, white={white:.3f}, variation={variation:.3f}')
