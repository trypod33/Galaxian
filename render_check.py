from PIL import Image, ImageDraw
import math, random
RES="app/src/main/res/drawable-nodpi/"
SP={n:Image.open(RES+n+".png").convert("RGBA") for n in
    ["spr_ship","spr_flag_0","spr_red_0","spr_purple_0","spr_blue_0","spr_boss_0","spr_bossh_0"]}
cell=24; W=432; H=768; K=2  # 2x scale for readability
colW=1.55*cell; rowH=1.35*cell
formLeft=(W-colW*9)/2; formTop=H*0.16

def put(canvas,name,x,y,w,flip=False):
    img=SP[name]
    h=img.height/img.width*w
    im=img.resize((int(w*K),int(h*K)),Image.NEAREST)
    if flip: im=im.transpose(Image.FLIP_LEFT_RIGHT)
    canvas.paste(im,(int((x-w/2)*K),int((y-h/2)*K)),im)

def starfield(d,seed=3):
    r=random.Random(seed)
    cols=[(255,60,60),(90,160,255),(255,255,255),(120,220,120),(255,220,90)]
    for _ in range(252):
        x,y=r.random()*W,r.random()*H
        d.ellipse([x*K-2,y*K-2,x*K+2,y*K+2],fill=cols[r.randrange(5)])

def formation(boss=False):
    im=Image.new("RGB",(W*K,H*K),(0,0,0)); d=ImageDraw.Draw(im)
    starfield(d)
    d.text((16,20),"1UP",fill=(255,70,70)); d.text((16,52),"04150",fill="white")
    d.text((W/2-60,20),"HIGH SCORE",fill=(255,70,70)); d.text((W/2-24,52),"09000",fill="white")
    d.text((W-110,20),"WAVE 1",fill=(160,160,160))
    sway=math.sin(2.5)*colW*0.8
    def sx(c,r): return formLeft+(c+(0.5 if r==0 else 0))*colW+sway
    def sy(r): return formTop+r*rowH
    if not boss:
        put(im,"spr_flag_0",sx(4,0),sy(0),cell*1.7)
        for c in range(2,8): put(im,"spr_red_0",sx(c,1),sy(1),cell*1.35)
        for c in range(1,9): put(im,"spr_purple_0",sx(c,2),sy(2),cell*1.35)
        for r in range(3,6):
            for c in range(10): put(im,"spr_blue_0",sx(c,r),sy(r),cell*1.35)
    else:
        for c in range(3,7): put(im,"spr_boss_0",sx(c,0),sy(0),cell*1.9)
        for c in range(2,8): put(im,"spr_red_0",sx(c,1),sy(1),cell*1.35)
        for c in range(1,9): put(im,"spr_purple_0",sx(c,2),sy(2),cell*1.35)
        for r in range(3,6):
            for c in range(10): put(im,"spr_blue_0",sx(c,r),sy(r),cell*1.35)
    # player + life count + flags
    put(im,"spr_ship",W/2,H-24*2.2,cell*1.5)
    for i in range(3): put(im,"spr_ship",30+i*40,H-26,cell*0.9)
    for i in range(1):
        fx=W-20-i*26; fy=H-58
        d.line([fx,fy,fx,fy-20],fill=(90,220,120),width=2)
        d.polygon([(fx,fy-20),(fx-13,fy-14),(fx,fy-8)],fill=(90,220,120))
    return im

formation().resize((W,H)).save("preview_classic.png")
formation(boss=True).resize((W,H)).save("preview_part2.png")

# beam frame: boss hovering above a solo player, cone of pulsing lines
im=Image.new("RGB",(W*K,H*K),(0,0,0)); d=ImageDraw.Draw(im); starfield(d)
for r in range(1,6):
    for c in range(10):
        if r==4 and c in (2,7): continue
        put(im,"spr_blue_0",formLeft+c*colW+30,formTop+r*rowH,cell*1.35)
bx=W/2; by=H*0.62
put(im,"spr_boss_0",bx,by,cell*1.9)
for i in range(3):
    half=cell*(0.5+i*0.55)
    col=[(255,230,120),(120,220,255),(255,230,120)][i]
    w=int((6-i*1.5)*K/2)
    d.line([bx*K,(by+cell*0.6)*K,(bx-half)*K,(H-cell*1.2)*K],fill=col,width=w)
    d.line([bx*K,(by+cell*0.6)*K,(bx+half)*K,(H-cell*1.2)*K],fill=col,width=w)
put(im,"spr_ship",W/2,H-24*2.2,cell*1.5)
im.resize((W,H)).save("preview_beam.png")

# challenge stage: a flight of 5 blues looping across, HUD says WAVE 3
im=Image.new("RGB",(W*K,H*K),(0,0,0)); d=ImageDraw.Draw(im); starfield(d)
d.text((W/2-40,20),"WAVE 3",fill=(160,160,160))
baseY=cell*2.5; cx=W*0.32; cy=baseY+cell*2.5; R=cell*5
for i in range(5):
    t=(i*0.12)
    ang=math.pi/2+1*(t/0.55)*2*math.pi if t<0.55 else 0
    if t<0.55: x,y=cx+R*math.cos(ang),cy+R*math.sin(ang)*0.8
    else: x,y=cx-R,W*0.6
    put(im,"spr_blue_0",x+ i*cell*0.6,y,cell*1.35)
put(im,"spr_ship",W/2,H-24*2.2,cell*1.5)
im.resize((W,H)).save("preview_chal.png")
print("previews done")
