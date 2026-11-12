# Headless replay of GalagaView.kt logic to find the stage-2 stall.
import math, random
PI = math.pi

class Enemy:
    def __init__(s, kind, row, col, x, y, state, hp, chal=-1):
        s.kind, s.row, s.col = kind, row, col
        s.x, s.y, s.state, s.hp, s.hurt = x, y, state, hp, False
        s.dead = False; s.exited = False
        s.chalGroup = chal; s.beamT = 0.0; s.beamOn = False
        s.hasCaptured = False; s.fired = 0.0
        s.leader = None; s.escDx = 0.0; s.escDy = 0.0
        s.isCapturedShip = False
        s.segs = []; s.segIdx = 0; s.segT = 0.0
        s.sx = 0.0; s.sy = 0.0; s.a0 = 0.0

SG_LINE, SG_CIRC, SG_HOV, SG_SINE = 0, 1, 2, 3
ST_READY, ST_ENTR, ST_PLAY, ST_CLEAR, ST_OVER, ST_RESULTS = 0,1,2,3,4,5
K_BOSS, K_RED, K_BEE = 0,1,2
ST_FORM, ST_ENTER, ST_DIVE, ST_RETURN, ST_HOVER = 0,1,2,3,4

class Game:
    def __init__(s, W=1080.0, H=2400.0):
        s.W, s.H = W, H
        s.cell = min(W/14.0, H/24.0)
        s.colW = s.cell*1.18; s.rowH = s.cell*1.05
        s.formTop = H*0.21
        s.shipW = s.enemyW = s.cell*1.05
        s.playerY = H - s.cell*2.2
        s.rng = random.Random(1981)
        s.reset()
    def reset(s):
        s.state = ST_READY; s.stateT = 0.0; s.time = 0.0
        s.score=0; s.lives=3; s.stage=1; s.nextExtra=20000
        s.dual=False; s.pendingDual=False; s.gameOverPending=False
        s.challenge=False; s.challengeGroup=0; s.challengeHits=0
        s.attackT=1.0; s.entSpawnIdx=0; s.entSpawnT=0.0; s.entGroup=0
        s.entSideIsLeft=True
        s.enemies=[]; s.entranceQueue=[]
        s.bullets=[]; s.effects=[]; s.floaties=[]
        s.playerX=s.W/2; s.playerAlive=True; s.respawnT=0.0; s.invulnT=0.0
        s.fireCool=0.0; s.press=True
        s.capturedBoss=None; s.capturePhase=0; s.captureT=0.0
        s.wasDualAtCapture=False
        s.sway=0.0; s.breath=1.0; s.formCx=s.W/2
        s.botAim = None
        s.transitions=[]
    def slotX(s,c): return s.formCx + (c-4.5)*s.colW*s.breath
    def slotY(s,r): return s.formTop + r*s.rowH
    def segL(s,tx,ty,d): return [SG_LINE,tx,ty,d]
    def segC(s,cx,cy,r,sw,d): return [SG_CIRC,cx,cy,r,sw,d]
    def segH(s,d): return [SG_HOV,d]
    def segS(s,tx,ty,d,a,c): return [SG_SINE,tx,ty,d,a,c]
    def stepSegs(s,e,dt):
        if e.segIdx >= len(e.segs): s.pathEnd(e); return
        seg = e.segs[e.segIdx]; kind = int(seg[0])
        if e.segT == 0.0:
            e.sx, e.sy = e.x, e.y
            if kind == SG_CIRC: e.a0 = math.atan2(e.y-seg[2], e.x-seg[1])
            if kind == SG_HOV and e.state == ST_DIVE: e.state = ST_HOVER
        if kind==SG_LINE: dur=seg[3]
        elif kind==SG_CIRC: dur=seg[5]
        elif kind==SG_HOV: dur=seg[1]
        else: dur=seg[3]
        e.segT += dt
        t = e.segT/max(dur,1e-4); done = t>=1.0
        if done: t=1.0
        if kind==SG_LINE:
            e.x = e.sx+(seg[1]-e.sx)*t; e.y = e.sy+(seg[2]-e.sy)*t
        elif kind==SG_CIRC:
            a = e.a0+seg[4]*t
            e.x = seg[1]+seg[3]*math.cos(a); e.y = seg[2]+seg[3]*math.sin(a)
        elif kind==SG_HOV:
            pass
        else:
            e.x = e.sx+(seg[1]-e.sx)*t + seg[4]*math.sin(t*2*PI*seg[5])*math.cos(t*PI/2)
            e.y = e.sy+(seg[2]-e.sy)*t
        if done:
            e.segIdx+=1; e.segT=0.0
            if e.segIdx >= len(e.segs): s.pathEnd(e)
    def pathEnd(s,e):
        if e.chalGroup >= 0: e.exited=True; e.dead=True; return
        if e.state in (ST_DIVE,):
            e.y = -s.cell*1.6; e.state=ST_RETURN; e.leader=None
            e.segs=[s.segL(s.slotX(e.col), s.slotY(e.row),1.1)]; e.segIdx=0; e.segT=0
            s.orphan(e)
        elif e.state == ST_HOVER:
            e.beamOn=False; e.state=ST_RETURN; e.leader=None
            e.segs=[s.segL(s.slotX(e.col), s.slotY(e.row),1.3)]; e.segIdx=0; e.segT=0
        else:
            e.state=ST_FORM; e.leader=None
    def orphan(s,e):
        for o in s.enemies:
            if o.leader is e and not o.dead and not o.isCapturedShip:
                o.leader=None; o.state=ST_RETURN
                o.segs=[s.segL(s.slotX(o.col), s.slotY(o.row),1.0)]; o.segIdx=0; o.segT=0
    def buildStage(s):
        s.enemies=[]; s.bullets=[]; s.effects=[]; s.floaties=[]
        s.capturedBoss=None
        s.challenge = (s.stage%4==3)
        s.challengeHits=0; s.attackT=2.2
        s.entSpawnIdx=0; s.entSpawnT=0.0; s.entGroup=0
        s.entranceQueue=[]
        if s.challenge:
            for g in range(5):
                for i in range(8): s.entranceQueue.append((K_BEE,-1,-1,g))
            s.state=ST_ENTR
        else:
            L=[]
            for i in range(4): L.append((K_BOSS,0,3+i,-1))
            for r in (1,2):
                for i in range(8): L.append((K_RED,r,1+i,-1))
            for r in (3,4):
                for i in range(10): L.append((K_BEE,r,i,-1))
            s.entranceQueue=L; s.state=ST_ENTR
        s.entSideIsLeft=True
        s.transitions.append(("buildStage", s.stage, s.state, len(s.entranceQueue)))
    def spawnEntrant(s,kind,row,col,group):
        fromL = s.entSideIsLeft
        e = Enemy(kind,row,col, (-s.cell*2) if fromL else (s.W+s.cell*2), s.formTop+s.rowH*2.2, ST_ENTER, 2 if kind==K_BOSS else 1, chal=group)
        e.segs.append(s.segL(s.cell*2.5 if fromL else s.W-s.cell*2.5, s.formTop+s.rowH*2.4, 0.9))
        dirn = 1.0 if fromL else -1.0
        e.segs.append(s.segC(s.formCx+(-s.colW*1.2 if fromL else s.colW*1.2), s.formTop+s.rowH*2.6, s.cell*2.3, dirn*2*PI*1.5, 1.5))
        if not s.challenge:
            e.segs.append(s.segL(s.slotX(col), s.slotY(row), 1.0))
        else:
            ex = s.W+s.cell*2 if fromL else -s.cell*2
            e.segs.append(s.segS(s.formCx+(s.colW*2.5 if fromL else -s.colW*2.5), s.formTop+s.rowH*(1.5+(group%3)), 1.1, s.cell*1.6, 2))
            e.segs.append(s.segC(s.formCx+(s.colW*2.2 if fromL else -s.colW*2.2), s.formTop+s.rowH*2.8, s.cell*2.0, -dirn*2*PI, 1.2))
            e.segs.append(s.segL(ex, s.formTop+s.rowH*2.8, 1.2))
        s.enemies.append(e)
    def updateEntrance(s,dt):
        s.entSpawnT -= dt
        per=8
        groups = 5 if s.challenge else (len(s.entranceQueue)+per-1)//per
        if s.entGroup<groups and s.entSpawnT<=0:
            if s.entSpawnIdx%per==0: s.entSideIsLeft = (s.entGroup%2==0)
            idx = s.entGroup*per + (s.entSpawnIdx%per)
            if idx < len(s.entranceQueue):
                k,r,c,g = s.entranceQueue[idx]
                s.spawnEntrant(k,r,c, g if s.challenge else -1)
                s.entSpawnT = 3.0 if s.entSpawnIdx%per==per-1 else 0.30
                s.entSpawnIdx+=1
                if s.entSpawnIdx%per==0: s.entGroup+=1
            else:
                s.entGroup=groups
    def allEntrantsDone(s):
        groups = 5 if s.challenge else (len(s.entranceQueue)+7)//8
        return s.entGroup>=groups and not any((not e.dead) and e.state==ST_ENTER for e in s.enemies)
    def scheduleAttack(s):
        fliers=[e for e in s.enemies if (not e.dead) and e.state==ST_FORM and not e.isCapturedShip]
        if not fliers: return
        bossList=[e for e in fliers if e.kind==K_BOSS]
        beamChance = 0.10 if any(e.hasCaptured for e in bossList) else 0.35
        if bossList and s.rng.random()<beamChance:
            boss = s.rng.choice(bossList)
            esc = s.pickEscorts(fliers,2)
            boss.leader=None; boss.state=ST_DIVE
            boss.segs=[]; boss.segIdx=0; boss.segT=0
            boss.segs.append(s.segC(boss.x, boss.y-s.cell*2.2, s.cell*2.0, 2*PI, 1.0))
            boss.segs.append(s.segL(boss.x, s.playerY-s.cell*2.4, 1.3))
            boss.segs.append(s.segH(2.6))
            for x in esc: s.attach(x,boss)
        else:
            if s.rng.random()<0.55 or not bossList:
                bees=[e for e in fliers if e.kind==K_BEE]; reds=[e for e in fliers if e.kind==K_RED]
                pool = bees if (bees and (not reds or s.rng.random()<0.5)) else reds
                if not pool: return
                lead = s.rng.choice(pool)
                lead.leader=None; lead.state=ST_DIVE
                lead.segs=[]; lead.segIdx=0; lead.segT=0
                lead.segs.append(s.segC(lead.x, lead.y-s.cell*2.0, s.cell*1.9, (2*PI) if s.rng.random()<0.5 else (-2*PI), 1.0))
                lead.segs.append(s.segS(lead.x+(s.rng.random()-0.5)*s.colW*6, s.H+s.cell*2, 2.0, s.colW*1.4, 2))
                if len(pool)>1 and s.rng.random()<0.4:
                    buddy = next((b for b in pool if b is not lead and b.kind==lead.kind), None)
                    if buddy:
                        buddy.leader=lead; buddy.escDx=s.colW*0.9; buddy.escDy=s.cell*1.2
                        buddy.state=ST_DIVE; buddy.segs=[]; buddy.segIdx=0; buddy.segT=0
            else:
                boss = s.rng.choice(bossList)
                esc = s.pickEscorts(fliers,2)
                boss.leader=None; boss.state=ST_DIVE
                boss.segs=[]; boss.segIdx=0; boss.segT=0
                boss.segs.append(s.segC(boss.x, boss.y-s.cell*2.2, s.cell*2.0, 2*PI, 1.0))
                boss.segs.append(s.segS(boss.x+(s.rng.random()-0.5)*s.colW*6, s.H+s.cell*2, 2.0, s.colW*1.2, 2))
                for x in esc: s.attach(x,boss)
    def pickEscorts(s,fliers,want):
        reds=[e for e in fliers if e.kind==K_RED and e.state==ST_FORM and e.leader is None]
        return reds[:want]
    def attach(s,esc,boss):
        esc.leader=boss
        esc.escDx = -s.colW*0.9 if esc.row%2==0 else s.colW*0.9
        esc.escDy = s.cell*1.15
        esc.state=ST_DIVE; esc.segs=[]; esc.segIdx=0; esc.segT=0
    def updateFormation(s,dt):
        s.sway = math.sin(s.time*0.55)*s.colW*0.35
        s.breath = 1+0.10*math.sin(s.time*0.9)
        s.formCx = s.W/2+s.sway
    def updateEnemies(s,dt):
        for e in s.enemies:
            if e.dead: continue
            if e.isCapturedShip:
                b=e.leader
                if b is None or b.dead: e.dead=True
                else: e.x=b.x+e.escDx; e.y=b.y+e.escDy
                continue
            if e.state==ST_FORM:
                e.x=s.slotX(e.col); e.y=s.slotY(e.row)
                if e.leader is not None and e.leader.state==ST_FORM: s.orphan(e)
            elif e.state==ST_DIVE:
                L=e.leader
                if L is not None and not L.dead and L.state in (ST_DIVE,ST_HOVER):
                    e.x=L.x+e.escDx; e.y=L.y+e.escDy
                else:
                    e.leader=None
                    if not e.segs:
                        e.state=ST_RETURN
                        e.segs=[s.segL(s.slotX(e.col), s.slotY(e.row),1.0)]; e.segIdx=0; e.segT=0
                    else: s.stepSegs(e,dt)
            else:
                s.stepSegs(e,dt)
            if e.state==ST_HOVER and not e.beamOn:
                e.beamOn=True; e.beamT=2.6
            if e.beamOn:
                e.beamT-=dt
                if e.beamT<=0:
                    e.beamOn=False; e.state=ST_RETURN
                    e.segs=[s.segL(s.slotX(e.col), s.slotY(e.row),1.3)]; e.segIdx=0; e.segT=0
            if not s.challenge and e.state==ST_DIVE and e.y>s.H*0.3 and e.y<s.playerY-s.cell:
                e.fired-=dt
                if e.fired<=0 and len([b for b in s.bullets if not b[4]])<5+min(6,s.stage):
                    e.fired=0.55+s.rng.random()*0.6
                    if s.rng.random()<0.75: pass  # bot invincible: skip enemy bullets
    def updateBullets(s,dt):
        for b in s.bullets[:]:
            b[0]+=b[2]*dt; b[1]+=b[3]*dt
            if b[1]<-s.cell or b[1]>s.H+s.cell: b[4]=True
            if b[4]:
                s.bullets.remove(b); continue
            if b[5]:  # mine
                for e in s.enemies:
                    if e.dead: continue
                    if e.isCapturedShip:
                        if abs(b[0]-e.x)<s.enemyW*0.5 and abs(b[1]-e.y)<s.enemyW*0.5:
                            e.dead=True; b[4]=True; break
                        continue
                    if abs(b[0]-e.x)<s.enemyW*0.55 and abs(b[1]-e.y)<s.enemyW*0.55:
                        b[4]=True; s.hitEnemy(e); break
        s.bullets=[b for b in s.bullets if not b[4]]
    def hitEnemy(s,e):
        e.hp-=1
        if e.hp>0: e.hurt=True; return
        s.killEnemy(e)
    def killEnemy(s,e):
        e.dead=True
        if s.challenge:
            s.challengeHits+=1; s.score += 100*(e.chalGroup+1); return
        s.score += {K_BOSS:150, K_RED:(80 if e.state in (ST_FORM,ST_ENTER) else 160), K_BEE:(50 if e.state in (ST_FORM,ST_ENTER) else 100)}[e.kind]
        if s.capturedBoss is e:
            s.capturePhase=0; s.capturedBoss=None; s.playerAlive=True
        if e.hasCaptured: s.freeCaptured(e)
        for o in s.enemies:
            if o.leader is e and not o.dead:
                o.leader=None
                if not o.isCapturedShip and o.state==ST_DIVE:
                    o.state=ST_RETURN
                    o.segs=[s.segL(s.slotX(o.col), s.slotY(o.row),1.0)]; o.segIdx=0; o.segT=0
    def freeCaptured(s,b):
        cs = next((c for c in s.enemies if c.isCapturedShip and not c.dead and c.leader is b), None)
        if cs: cs.dead=True
        b.hasCaptured=False
        if s.playerAlive or s.respawnT>0 or s.capturePhase>0:
            if s.capturePhase>0:
                s.capturePhase=0; s.capturedBoss=None; s.playerAlive=True; s.playerY=s.H-s.cell*2.2
            s.dual=True if not s.dual else s.dual
            s.pendingDual=False
        else:
            s.pendingDual=True
    def updatePlayer(s,dt):
        if s.gameOverPending and not s.playerAlive and s.capturePhase==0 and s.respawnT<=0 and s.state!=ST_OVER:
            s.state=ST_OVER; s.stateT=0
            s.transitions.append(("OVER",))
            return
        if s.capturePhase>0:
            s.captureT+=dt
            b=s.capturedBoss
            if s.capturePhase==1 and b is not None:
                if s.captureT>0.7: s.capturePhase=2; s.captureT=0
            elif s.capturePhase==2 and b is not None:
                s.playerX=b.x; s.playerY=b.y+s.cell*1.15
                if b.state==ST_FORM: s.finishCapture(b)
            return
        if not s.playerAlive:
            s.respawnT-=dt
            if s.respawnT<=0 and not s.gameOverPending and s.state!=ST_OVER:
                s.playerAlive=True; s.playerX=s.W/2; s.invulnT=2
                if s.pendingDual: s.dual=True; s.pendingDual=False
            return
        s.invulnT-=dt
        # bot: aim at lowest alive enemy, fire every 0.15s
        s.fireCool-=dt
        alive=[e for e in s.enemies if not e.dead and not e.isCapturedShip]
        if s.fireCool<=0 and alive and len([b for b in s.bullets if b[5]])<(2 if s.dual else 1):
            s.fireCool=0.15
            tgt = max(alive, key=lambda e:e.y)
            s.playerX = tgt.x  # perfect aim
            bs = -s.cell*26
            if s.dual:
                s.bullets.append([s.playerX-s.shipW*0.45, s.playerY-s.cell*0.9, 0, bs, False, True])
                s.bullets.append([s.playerX+s.shipW*0.45, s.playerY-s.cell*0.9, 0, bs, False, True])
            else:
                s.bullets.append([s.playerX, s.playerY-s.cell*0.9, 0, bs, False, True])
    def finishCapture(s,b):
        b.hasCaptured=True; b.beamOn=False
        s.capturePhase=0; s.capturedBoss=None
        cap = Enemy(K_BEE,b.row,b.col,b.x+s.colW*0.62,b.y,ST_FORM,1)
        cap.isCapturedShip=True; cap.leader=b; cap.escDx=s.colW*0.62; cap.escDy=0
        s.enemies.append(cap)
        if s.wasDualAtCapture: s.dual=False
        else:
            s.lives-=1
            if s.lives<=0: s.gameOverPending=True
            s.playerAlive=False; s.respawnT=1.2
    def updateBeamCapture(s,dt):
        if s.capturePhase>0 or not s.playerAlive or s.dual: return
        for e in s.enemies:
            if e.kind!=K_BOSS or e.dead or not e.beamOn: continue
            if abs(s.playerX-e.x)<s.cell*1.5 and s.playerY>e.y:
                s.capturedBoss=e; s.wasDualAtCapture=False
                s.capturePhase=1; s.captureT=0
                s.transitions.append(("captured",))
                return
    def playerVsEnemies(s): pass  # bot invincible
    def update(s,dt):
        s.time+=dt
        s.updateFormation(dt)
        st=s.state
        if st==ST_READY:
            s.stateT+=dt
            if s.stateT>2.2: s.stateT=0; s.buildStage()
        elif st==ST_ENTR:
            s.updateEntrance(dt); s.updateEnemies(dt); s.updateBullets(dt); s.updatePlayer(dt)
            if s.allEntrantsDone():
                s.state=ST_PLAY; s.stateT=0; s.attackT=1.2
                s.transitions.append(("PLAY",s.stage))
        elif st==ST_PLAY:
            s.updateEnemies(dt); s.updateBullets(dt); s.updatePlayer(dt); s.updateBeamCapture(dt)
            if not s.challenge:
                s.attackT-=dt
                maxG=min(3,1+s.stage//4)
                if s.attackT<=0:
                    s.attackT=max(0.9,2.4-s.stage*0.10)+s.rng.random()*0.8
                    diving=len([e for e in s.enemies if (not e.dead) and e.state in (ST_DIVE,ST_HOVER)])
                    if diving<maxG*2: s.scheduleAttack()
                if not any((not e.dead and not e.isCapturedShip) for e in s.enemies):
                    s.state=ST_CLEAR; s.stateT=0
                    s.transitions.append(("CLEAR",s.stage))
            else:
                if all(e.dead or e.exited for e in s.enemies):
                    s.state=ST_RESULTS; s.stateT=0
        elif st==ST_CLEAR:
            s.stateT+=dt
            if s.stateT>1.8: s.stage+=1; s.stateT=0; s.buildStage()
        elif st==ST_RESULTS:
            s.stateT+=dt
            if s.stateT>2.8:
                if s.challengeHits==40: pass
                s.stage+=1; s.stateT=0; s.buildStage()
        elif st==ST_OVER:
            s.stateT+=dt
    def run(s,limit=240.0,debug=False):
        dt=1/60.0
        n=int(limit/dt)
        for i in range(n):
            s.update(dt)
            if debug and i%120==0:
                alive=[e for e in s.enemies if not e.dead]
                from collections import Counter
                print(f"t={s.time:5.1f} state={s.state} entG={s.entGroup} idx={s.entSpawnIdx} q={len(s.entranceQueue)} enemies={len(s.enemies)} alive={len(alive)} states={Counter(e.state for e in alive)} score={s.score}")
            if s.state==ST_OVER: return "OVER", s.transitions
        return "TIMEOUT", s.transitions

results=[]
for g in range(5):
    game=Game(); game.reset()
    # skip READY delay
    game.stateT=2.3
    outcome,tr = game.run()
    print(f"game {g}: {outcome} stage={game.stage} score={game.score} lives={game.lives}")
    for t in tr[-8:]: print("   ", t)
    results.append((outcome,game.stage))
print("SUMMARY", results)
