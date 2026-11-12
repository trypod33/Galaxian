import math, random
from collections import namedtuple

# Python replica of the v0.2.0 GameView.kt core logic (classic + Part 2), cell=24 fixed
FLAG, RED, PURPLE, BLUE, BOSS = 0, 1, 2, 3, 4
FORM, DIVE, RETURN, CHAL = 0, 1, 2, 3
LOOP, DIVEP, EXIT, HOVER = 0, 1, 2, 3

class A:
    def __init__(s, row, col, kind):
        s.row, s.col, s.kind = row, col, kind
        s.alive=True; s.state=FORM; s.x=0; s.y=0; s.vx=0; s.vy=0
        s.phase=LOOP; s.phaseT=0; s.side=1; s.seed=0; s.fireT=0
        s.escortSlot=0; s.leader=None; s.hp=1; s.bossMode=False; s.holdsShip=False
        s.beamT=0; s.loopX=0; s.loopY=0
        s.chalT=0; s.chalPath=1; s.chalBaseY=0; s.chalOff=0

class G:
    def __init__(s, mode2=False, seed=1, W=432, H=768):
        s.rnd=random.Random(seed); s.mode2=mode2; s.W=W; s.H=H
        s.cell=24; s.colW=37.2; s.rowH=32.4
        s.formLeft=(W-37.2*9)/2; s.formTop=H*0.16
        s.score=0; s.lives=3; s.wave=1; s.extra=False; s.carry=0
        s.melee=False; s.swayT=0; s.time=0; s.challenge=False
        s.chalFlight=-1; s.chalFlightT=0; s.chalKilled=0; s.chalMissed=0
        s.groups=[]; s.aliens=[]; s.eb=[]; s.pb=[]; s.diveTimer=2.5; s.waveClearT=0
        s.capturing=False; s.captureT=0; s.captureBoss=None; s.dual=False; s.freedT=0
        s.px=W/2; s.py=H-24*2.2; s.tx=s.px
        s.floats=[]; s.log=[]
        s.spawn()
    def logf(s,*a): s.log.append(" ".join(str(x) for x in a))
    def sway(s):
        alive=sum(1 for a in s.aliens if a.alive and a.state==FORM)
        speed=1.1+(1-alive/36)*2.2+(s.wave-1)*0.08
        return math.sin(s.swayT*speed)*s.colW*0.8
    def slotX(s,a): return s.formLeft+a.col*s.colW+s.sway()
    def slotY(s,a): return s.formTop+a.row*s.rowH
    def spawn(s):
        s.aliens=[]; s.groups=[]; s.melee=False; s.challenge = s.mode2 and (s.wave%4==3)
        if s.challenge: s.start_chal(); return
        if s.mode2:
            for c in range(3,7): s.aliens.append(A(0,c,BOSS))
        else:
            s.aliens.append(A(0,4,FLAG)); s.aliens.append(A(0,5,FLAG))
        for c in range(2,8): s.aliens.append(A(1,c,RED))
        for c in range(1,9): s.aliens.append(A(2,c,PURPLE))
        for r in range(3,5):
            for c in range(10): s.aliens.append(A(r,c,BLUE))
        for a in s.aliens:
            a.hp = 2 if a.kind==BOSS else 1
            a.x=s.slotX(a); a.y=s.slotY(a)
        s.carry=0
    def start_chal(s):
        s.challenge=True; s.aliens=[]; s.groups=[]; s.eb=[]
        s.chalFlight=-1; s.chalFlightT=0.9; s.chalKilled=0; s.chalMissed=0
    def add(s,n,x,y):
        s.score+=n
        if not s.extra and s.score>=7000:
            s.extra=True; s.lives+=1; s.logf("EXTRA at",s.score)
    def step(s, dt, invincible=False, fire_fn=None):
        s.time+=dt
        if s.freedT>0:
            s.freedT-=dt
            if s.freedT<=0: s.dual=True
        shot_in = any(f[1] > s.formTop-36 and f[1] < s.formTop+s.rowH*4.6 for f in s.pb)
        if not shot_in: s.swayT+=dt
        for a in s.aliens:
            if a.alive and a.state==FORM: a.x=s.slotX(a); a.y=s.slotY(a)
        # player bullet
        for b in list(s.pb):
            b[1]+=b[3]*dt
            if b[1] < s.formTop-48: s.pb.remove(b); continue
            for a in s.aliens:
                if not a.alive: continue
                w = 20.4 if a.kind in (FLAG,BOSS) else 14.4
                if abs(b[0]-a.x)<w and abs(b[1]-a.y)<14.4:
                    s.pb.remove(b)
                    diving = a.state not in (FORM,CHAL)
                    if a.kind==BOSS and a.hp>1:
                        a.hp-=1; s.logf("boss hit ->",a.hp,"hp"); break
                    if a.kind==FLAG:
                        pts = s.ladder(a) if diving else 60
                    elif a.kind==BOSS: pts = 400 if diving else 150
                    elif a.kind==RED: pts = 100 if diving else 50
                    elif a.kind==PURPLE: pts = 80 if diving else 40
                    else: pts = 100 if a.state==CHAL else (60 if diving else 30)
                    s.logf("kill kind=%d state=%d +%d"%(a.kind,a.state,pts))
                    s.add(pts,a.x,a.y)
                    if a.kind==BOSS and a.holdsShip: s.logf("SHIP RESCUED")
                    s.kill(a)
                    break
        if s.capturing:
            s.captureT+=dt
            b=s.captureBoss
            if s.captureT>1.4: s.finish_capture(invincible)
            return
        if s.challenge: s.update_chal(dt); return
        s.diveTimer-=dt
        pool=[a for a in s.aliens if a.alive and a.state==FORM]
        if s.diveTimer<=0:
            cap = 3 if s.melee else 2
            if pool and len(s.groups)<cap:
                if s.melee:
                    picks=s.rnd.sample(pool,min(3,len(pool)))
                    for a in picks: s.start_dive(a); s.groups.append([a,[],[],0])
                    s.diveTimer=0.9+s.rnd.random()*0.8
                else: s.launch(pool); s.diveTimer=max(0.9,3.2-(s.wave-1)*0.28)+s.rnd.random()*2
            else: s.diveTimer=0.6
        if not s.melee:
            inf=sum(1 for a in s.aliens if a.alive and a.state==FORM)
            bp=sum(1 for a in s.aliens if a.alive and a.state==FORM and a.kind in (BLUE,PURPLE))
            if inf<=3 or bp==0: s.melee=True; s.logf("MELEE at wave",s.wave)
        ds = s.cell*(7.5+s.wave*0.5)
        for g in list(s.groups):
            l=g[0]
            if l.state==DIVE: s.step_diver(l,dt,ds)
            elif l.state==RETURN:
                s.step_ret(l,dt)
                if l.state==FORM and g in s.groups: s.groups.remove(g)
            for e in list(g[1]):
                if not e.alive: continue
                if e.leader is not None and e.state==DIVE:
                    idx=len(g[2])-1-(e.escortSlot+1)*12
                    if idx>=0:
                        p=g[2][idx]; e.x=p[0]; e.y=p[1]
                        if e.y>s.H+48:
                            e.y=-48; e.x=s.slotX(e); e.leader=None; e.phase=DIVEP; e.state=RETURN
                    else: e.phase=DIVEP; e.leader=None
                elif e.state==DIVE: s.step_diver(e,dt,ds)
                elif e.state==RETURN: s.step_ret(e,dt)
            if (not l.alive) and not any(e.alive and (e.leader is not None or e.state==DIVE) for e in g[1]) and g in s.groups: s.groups.remove(g)
            if l.state==FORM and g in s.groups: s.groups.remove(g)
        for a in s.aliens:
            if not a.alive: continue
            if a.state==DIVE and a.leader is None and not any(a is g[0] or a in g[1] for g in s.groups):
                s.step_diver(a,dt,ds)
            elif a.state==RETURN and not any(a is g[0] or a in g[1] for g in s.groups):
                s.step_ret(a,dt)
            elif a.state==FORM and a.holdsShip:
                a.fireT-=dt
                if a.fireT<=0:
                    a.fireT=1.6+s.rnd.random()*2
                    s.eb.append([a.x,a.y+12,0,s.cell*(8+s.wave*0.4)])
        for b in list(s.eb):
            b[0]+=b[2]*dt; b[1]+=b[3]*dt
            if b[1]>s.H+24 or b[0]<-24 or b[0]>s.W+24: s.eb.remove(b)
        # player hit check (bot dodges horizontally away from bullets)
        hit=False
        if not invincible:
          for b in s.eb:
            if abs(b[0]-s.px)<(24 if s.dual else 12) and abs(b[1]-s.py)<14: hit=True; break
          if not hit:
            for a in s.aliens:
                if a.alive and a.state not in (FORM,CHAL) and abs(a.x-s.px)<24 and abs(a.y-s.py)<13:
                    s.kill(a); hit=True; break
        if hit:
            if s.dual: s.dual=False; s.logf("dual lost -> single"); return
            s.lives-=1; s.logf("player death, lives",s.lives)
            if s.lives<=0: return "OVER"
            s.px=s.W/2; s.py=s.H-24*2.2
        if not any(a.alive for a in s.aliens):
            s.waveClearT+=dt
            if s.waveClearT>1.2:
                s.waveClearT=0
                s.carry=min(2,sum(1 for a in s.aliens if a.kind==FLAG and a.alive)) if not s.mode2 else 0
                s.wave+=1; s.spawn(); s.logf("WAVE",s.wave,"carry",s.carry)
        else: s.waveClearT=0
    def ladder(s,a):
        for g in s.groups:
            if g[0] is a:
                if not g[1]: return 150
                d=g[3]
                return {2:800,1:200}.get(d,300)
        return 150
    def kill(s,a):
        a.alive=False
        for g in s.groups:
            if g[0] is a:
                s.groups.remove(g)
                for e in g[1]:
                    if e.alive:
                        e.leader=None
                        if e.state==DIVE: e.phase=DIVEP
            elif a in g[1]: g[3]+=1
    def launch(s,pool):
        boss=[a for a in pool if a.kind==BOSS]
        if s.mode2 and boss and not any(g[0].kind==BOSS for g in s.groups) and s.rnd.random()<0.5:
            b=boss[0]; s.start_dive(b,True); s.groups.append([b,[],[],0]); s.logf("boss dive")
            return
        flag=[a for a in pool if a.kind==FLAG]
        reds=[a for a in pool if a.kind==RED]
        if flag and reds:
            f=flag[0]
            esc=sorted(reds,key=lambda e:abs(e.col-f.col))[:2 if len(reds)>=2 else 1]
            s.start_dive(f)
            g=[f,esc,[],0]; s.groups.append(g)
            s.logf("flagship convoy +%d escorts"%len(esc))
        else:
            p=s.rnd.choice([a for a in pool if a.kind!=FLAG] or pool)
            s.start_dive(p); s.groups.append([p,[],[],0])
    def start_dive(s,a,boss=False):
        a.state=DIVE; a.phase=LOOP; a.phaseT=0
        a.side=1 if a.x>s.W/2 else -1
        a.loopX=a.x; a.loopY=a.y; a.bossMode=boss; a.beamT=0
    def step_diver(s,a,dt,ds):
        a.phaseT+=dt
        if a.phase==LOOP:
            R=s.colW*1.7
            phi=-math.pi/2+a.side*a.phaseT*4.2
            a.x=min(max(a.loopX+a.side*R*math.cos(phi)*0.9,s.cell*0.7),s.W-s.cell*0.7)
            a.y=a.loopY+R+R*math.sin(phi)*0.85
            if a.phaseT>1.1:
                a.phase=HOVER if a.bossMode else DIVEP; a.phaseT=0
            return
        if a.phase==HOVER and a.bossMode:
            dx=s.px-a.x
            a.x+=max(-s.cell*6,min(s.cell*6,dx/dt)) * dt
            a.x=min(max(a.x,s.cell*0.7),s.W-s.cell*0.7)
            a.y+=s.cell*1.2*dt
            if abs(a.x-s.px)<s.colW*0.35:
                a.beamT+=dt
                s.logf("BEAM at y=%.0f"%(a.y)) if a.beamT<0.05 else None
                if not s.dual and abs(s.px-a.x)<s.cell*1.05 and s.py>a.y:
                    s.start_capture(a); return
                if a.beamT>2.2:
                    a.phase=EXIT; a.vy=-ds; a.bossMode=False
            return
        if a.phase==DIVEP:
            amp=s.colW*3.2 if a.kind==PURPLE else s.colW*2.2
            tx=s.px+math.sin(s.time*2.6+a.seed*7)*amp
            ty=a.y+s.cell*5
            if a.y>s.py+s.cell*3: a.phase=EXIT
        else: # EXIT
            a.x+=a.vx*dt; a.y+=a.vy*dt
            a.x=min(max(a.x,s.cell*0.7),s.W-s.cell*0.7)
            if a.y>s.H+48:
                if s.melee and a.kind!=BOSS:
                    a.y=-48; a.x=s.slotX(a); a.phase=DIVEP
                else:
                    a.y=-48; a.x=s.slotX(a); a.state=RETURN; a.vx=0; a.vy=48
            if a.y<-72:
                a.y=-72; a.state=RETURN; a.vx=0; a.vy=48
            return
        ddx=tx-a.x; ddy=ty-a.y
        L=max(0.001,math.hypot(ddx,ddy)); acc=ds*2.2
        a.vx+=ddx/L*acc*dt; a.vy+=ddy/L*acc*dt
        sp=math.hypot(a.vx,a.vy)
        if sp>ds: a.vx*=ds/sp; a.vy*=ds/sp
        a.x+=a.vx*dt; a.y+=a.vy*dt
        a.x=min(max(a.x,s.cell*0.5),s.W-s.cell*0.5)
    def step_ret(s,a,dt):
        tx=s.slotX(a); ty=s.slotY(a)
        d=math.hypot(tx-a.x,ty-a.y)
        if d<s.cell*0.4: a.state=FORM; a.vx=0; a.vy=0; return
        a.x+=(tx-a.x)/d*s.cell*6*dt; a.y+=(ty-a.y)/d*s.cell*6*dt
    def start_capture(s,b):
        s.capturing=True; s.captureT=0; s.captureBoss=b
        s.logf("CAPTURED")
    def finish_capture(s,invincible=False):
        s.capturing=False; b=s.captureBoss; s.captureBoss=None
        s.lives-=1
        if s.lives<=0:
            s.logf("game over by capture"); return
        if b is not None and b.alive:
            b.holdsShip=True; b.state=RETURN; b.phase=DIVEP; b.bossMode=False
            s.logf("boss carries ship into formation")
        s.px=s.W/2; s.py=s.H-24*2.2; s.dual=False
    def update_chal(s,dt):
        if s.chalFlight<7:
            s.chalFlightT-=dt
            if s.chalFlightT<=0 and not any(a.state==CHAL for a in s.aliens):
                s.chalFlight+=1
                kind=[BLUE,PURPLE,RED][s.chalFlight%3]
                side=1 if s.chalFlight%2==0 else -1
                baseY=s.cell*(2.5+(s.chalFlight%3)*1.6)
                for i in range(5):
                    a=A(0,0,kind); a.state=CHAL; a.chalPath=side
                    a.chalBaseY=baseY; a.chalT=-(i*0.35); a.chalOff=i*s.cell*0.6
                    a.x=-s.cell*4; a.y=baseY; s.aliens.append(a)
                s.logf("chal flight",s.chalFlight+1)
                s.chalFlightT=4.6
        elif not any(a.alive for a in s.aliens):
            if s.chalKilled==40:
                s.add(10000,s.W/2,s.H*0.45); s.logf("PERFECT +10000")
            else: s.logf("chal hit %d of 40"%s.chalKilled)
            s.challenge=False; s.wave+=1; s.spawn(); s.logf("WAVE",s.wave)
        for a in list(s.aliens):
            if not a.alive: s.aliens.remove(a); continue
            a.chalT+=dt
            if a.chalT<0: continue
            t=a.chalT/5.2
            if t>1: s.chalMissed+=1; a.alive=False; s.aliens.remove(a); continue
            side=a.chalPath; cx=s.W*(0.5-side*0.18); cy=a.chalBaseY+s.cell*2.5; R=s.cell*5
            if t<0.55:
                ang=math.pi/2+side*(t/0.55)*2*math.pi
                a.x=cx+R*math.cos(ang)+a.chalOff*side; a.y=cy+R*math.sin(ang)*0.8
            else:
                q=(t-0.55)/0.45; xe=cx-side*R
                a.x=xe+math.sin(q*math.pi*3)*s.colW*1.4*-side
                a.y=cy+q*q*(s.H+s.cell*3-cy)
