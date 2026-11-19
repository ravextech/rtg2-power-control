#!/usr/bin/env python3
"""Optional artwork regeneration. Needs Pillow and a TrueType font, not used by build.py."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--font',required=True);args=p.parse_args()
S=4
def symbol(draw,x,y,size):
    box=(int(x+size*.18),int(y+size*.24),int(x+size*.82),int(y+size*.88))
    draw.arc(box,-45,225,fill='#67E8C5',width=max(1,int(size*.085)))
    draw.line((int(x+size*.5),int(y+size*.13),int(x+size*.5),int(y+size*.51)),fill='#67E8C5',width=max(1,int(size*.085)))

icon=Image.new('RGBA',(512*S,512*S),'#101923');d=ImageDraw.Draw(icon)
d.rounded_rectangle((20*S,20*S,492*S,492*S),radius=100*S,fill='#182D3A')
symbol(d,0,0,512*S)
out=ROOT/'app/src/main/res/drawable-nodpi';out.mkdir(parents=True,exist_ok=True)
icon.resize((256,256),Image.Resampling.LANCZOS).save(out/'ic_launcher.png')
banner=Image.new('RGB',(320*S,180*S),'#101923');d=ImageDraw.Draw(banner)
d.rounded_rectangle((8*S,8*S,312*S,172*S),radius=20*S,fill='#182D3A')
symbol(d,8*S,31*S,112*S)
d.text((130*S,51*S),'RT-G2',font=ImageFont.truetype(args.font,37*S),fill='#EEF4FA')
d.text((132*S,103*S),'POWER CONTROL',font=ImageFont.truetype(args.font,13*S),fill='#A4B5C7')
banner.resize((320,180),Image.Resampling.LANCZOS).save(out/'tv_banner.png')
print('Generated original geometric icon and 320×180 TV banner')
