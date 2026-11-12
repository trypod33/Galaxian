package com.trypod33.galaxian

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GalagaView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    companion object {
        const val ST_READY = 0
        const val ST_ENTR = 1
        const val ST_PLAY = 2
        const val ST_CLEAR = 3
        const val ST_OVER = 4
        const val ST_RESULTS = 5
        // enemy kinds
        const val K_BOSS = 0
        const val K_RED = 1
        const val K_BEE = 2
        // enemy states
        const val ST_FORM = 0
        const val ST_ENTER = 1
        const val ST_DIVE = 2
        const val ST_RETURN = 3
        const val ST_HOVER = 4
        // seg kinds: 0 line, 1 circle, 2 hover, 3 sine-line
        const val SG_LINE = 0
        const val SG_CIRC = 1
        const val SG_HOV = 2
        const val SG_SINE = 3
    }

    private val prefs = context.getSharedPreferences("galaga", Context.MODE_PRIVATE)
    private var highScore = prefs.getInt("high", 0)

    @Volatile private var running = false
    private var thread: Thread? = null
    private var lastNs = 0L
    private var time = 0f
    private val rng = Random(1981)

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 40f; isFakeBoldText = true
    }
    private val pSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 70, 70); textSize = 34f; isFakeBoldText = true
    }
    private val pTiny = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 200, 255); textSize = 26f
    }
    private val pSprite = Paint()

    private class Sprite(val bmp: Bitmap, glow: Int) {
        private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(glow, PorterDuff.Mode.SRC_IN)
            alpha = 60
        }
        fun draw(canvas: Canvas, cx: Float, cy: Float, w: Float, p: Paint, flipH: Boolean = false) {
            val h = w * bmp.height.toFloat() / bmp.width
            val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            val grect = RectF(cx - w * 0.62f, cy - h * 0.62f, cx + w * 0.62f, cy + h * 0.62f)
            if (flipH) {
                canvas.save(); canvas.scale(-1f, 1f, cx, cy)
                canvas.drawBitmap(bmp, null, grect, pGlow)
                canvas.drawBitmap(bmp, null, rect, p)
                canvas.restore()
            } else {
                canvas.drawBitmap(bmp, null, grect, pGlow)
                canvas.drawBitmap(bmp, null, rect, p)
            }
        }
    }

    private fun spr(name: String, glow: Int): Sprite {
        val id = resources.getIdentifier(name, "drawable", context.packageName)
        return Sprite(BitmapFactory.decodeResource(resources, id), glow)
    }
    private lateinit var sprBee: Array<Sprite?>
    private lateinit var sprRed: Array<Sprite?>
    private lateinit var sprBoss: Array<Sprite?>
    private lateinit var sprBossH: Array<Sprite?>
    private lateinit var sprShip: Array<Sprite?>
    private lateinit var sprShipR: Array<Sprite?>
    private lateinit var sprExpl: Array<Sprite?>
    private fun loadSprites() {
        sprBee = arrayOf(spr("gal_bee0", Color.rgb(80, 140, 255)), spr("gal_bee1", Color.rgb(80, 140, 255)))
        sprRed = arrayOf(spr("gal_red0", Color.rgb(255, 90, 90)), spr("gal_red1", Color.rgb(255, 90, 90)))
        sprBoss = arrayOf(spr("gal_boss0", Color.rgb(60, 230, 130)), spr("gal_boss1", Color.rgb(60, 230, 130)))
        sprBossH = arrayOf(spr("gal_bossh0", Color.rgb(200, 110, 255)), spr("gal_bossh1", Color.rgb(200, 110, 255)))
        sprShip = arrayOf(spr("gal_ship0", Color.WHITE), spr("gal_ship1", Color.WHITE))
        sprShipR = arrayOf(spr("gal_shipred0", Color.rgb(255, 110, 110)), spr("gal_shipred1", Color.rgb(255, 110, 110)))
        sprExpl = arrayOf(spr("gal_exp0", Color.WHITE), spr("gal_exp1", Color.WHITE), spr("gal_exp2", Color.WHITE))
    }

    private val snd: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val sIds = HashMap<String, Int>()
    private fun loadSounds() {
        for (n in listOf("gal_start", "gal_dive", "gal_shot", "gal_hit", "gal_pdie",
                         "gal_beam", "gal_capture", "gal_rescue", "gal_chal")) {
            val id = resources.getIdentifier(n, "raw", context.packageName)
            if (id != 0) sIds[n] = snd.load(context, id, 1)
        }
    }
    private fun play(name: String, vol: Float = 0.55f) {
        val id = sIds[name] ?: return
        snd.play(id, vol, vol, 1, 0, 1f)
    }

    // ---- layout (arcade 224x288) ----
    private var cell = 24f
    private var colW = 0f
    private var rowH = 0f
    private var formTop = 0f
    private var screenW = 0f
    private var screenH = 0f
    private var shipW = 0f
    private var enemyW = 0f

    // ---- game state ----
    private var state = ST_READY
    private var stateT = 0f
    private var score = 0
    private var lives = 3
    private var stage = 1
    private var nextExtra = 20000
    private var dual = false
    private var pendingDual = false
    private var challenge = false
    private var challengeGroup = 0
    private var challengeHits = 0

    private var formCx = 0f
    private var sway = 0f
    private var breath = 1f
    private var attackT = 1f
    private var fireT = 0f
    private var entSpawnIdx = 0
    private var entSpawnT = 0f
    private var entGroup = 0
    private var entSideIsLeft = true

    private class Bullet(var x: Float, var y: Float, var vx: Float, var vy: Float, var mine: Boolean, var dead: Boolean = false)
    private val bullets = ArrayList<Bullet>()
    private class Expl(var x: Float, var y: Float, var t: Float, var big: Boolean, var txt: String? = null)
    private val effects = ArrayList<Expl>()
    private class FloatTxt(var x: Float, var y: Float, var t: Float, val s: String)
    private val floaties = ArrayList<FloatTxt>()

    private var playerX = 0f
    private var playerY = 0f
    private var playerAlive = false
    private var respawnT = 0f
    private var invulnT = 0f
    private var pressing = false
    private var tapQueued = false
    private var fireCool = 0f

    // capture machinery
    private var capturedBoss: Enemy? = null
    private var capturePhase = 0
    private var captureT = 0f
    private var wasDualAtCapture = false
    private var gameOverPending = false

    private val stars = ArrayList<FloatArray>()
    private var starT = 0f

    private class Enemy(
        val kind: Int, var row: Int, var col: Int,
        var x: Float, var y: Float, var state: Int,
        var hp: Int, var hurt: Boolean,
        var dead: Boolean = false, var exited: Boolean = false,
        var chalGroup: Int = -1, var beamT: Float = 0f, var beamOn: Boolean = false,
        var hasCaptured: Boolean = false, var fired: Float = 0f,
        var leader: Enemy? = null, var escDx: Float = 0f, var escDy: Float = 0f,
        var isCapturedShip: Boolean = false
    ) {
        val segs = ArrayList<FloatArray>()
        var segIdx = 0
        var segT = 0f
        var sx = 0f; var sy = 0f // seg start anchor
        var a0 = 0f
    }

    private val enemies = ArrayList<Enemy>()
    private val entranceQueue = ArrayList<IntArray>() // kind, row, col
    private var liveBoss: Enemy? = null

    private fun slotX(col: Int): Float = formCx + (col - 4.5f) * colW * breath
    private fun slotY(row: Int): Float = formTop + row * rowH

    private fun segLine(tx: Float, ty: Float, dur: Float) = floatArrayOf(SG_LINE.toFloat(), tx, ty, dur)
    private fun segCirc(cx: Float, cy: Float, r: Float, sweep: Float, dur: Float) =
        floatArrayOf(SG_CIRC.toFloat(), cx, cy, r, sweep, dur)
    private fun segHov(dur: Float) = floatArrayOf(SG_HOV.toFloat(), dur)
    private fun segSine(tx: Float, ty: Float, dur: Float, amp: Float, cycles: Float) =
        floatArrayOf(SG_SINE.toFloat(), tx, ty, dur, amp, cycles)

    private fun stepSegs(e: Enemy, dt: Float) {
        if (e.segIdx >= e.segs.size) { pathEnd(e); return }
        val s = e.segs[e.segIdx]
        val kind = s[0].toInt()
        if (e.segT == 0f) {
            e.sx = e.x; e.sy = e.y
            if (kind == SG_CIRC) e.a0 = atan2(e.y - s[2], e.x - s[1])
        }
        val dur = when (kind) {
            SG_LINE -> s[3]; SG_CIRC -> s[5]; SG_HOV -> s[1]; else -> s[3]
        }
        e.segT += dt
        var t = e.segT / max(dur, 0.0001f)
        val done = t >= 1f
        if (done) t = 1f
        when (kind) {
            SG_LINE -> { e.x = e.sx + (s[1] - e.sx) * t; e.y = e.sy + (s[2] - e.sy) * t }
            SG_CIRC -> {
                val ang = e.a0 + s[4] * t
                e.x = s[1] + s[3] * cos(ang)
                e.y = s[2] + s[3] * sin(ang)
            }
            SG_HOV -> { }
            SG_SINE -> {
                e.x = e.sx + (s[1] - e.sx) * t + s[4] * sin(t * 2f * PI.toFloat() * s[5]) * cos(t * PI.toFloat() / 2f)
                e.y = e.sy + (s[2] - e.sy) * t
            }
        }
        if (done) {
            e.segIdx++; e.segT = 0f
            if (e.segIdx >= e.segs.size) pathEnd(e)
        }
    }

    private fun pathEnd(e: Enemy) {
        if (e.chalGroup >= 0) { e.exited = true; e.dead = true; return }
        when (e.state) {
            ST_DIVE -> {
                e.y = -cell * 1.6f
                e.state = ST_RETURN
                e.leader = null
                e.segs.clear()
                e.segs.add(segLine(slotX(e.col), slotY(e.row), 1.1f))
                e.segIdx = 0; e.segT = 0f
                orphanEscorts(e)
            }
            ST_HOVER -> {
                e.beamOn = false
                e.state = ST_RETURN
                e.leader = null
                e.segs.clear()
                e.segs.add(segLine(slotX(e.col), slotY(e.row), 1.3f))
                e.segIdx = 0; e.segT = 0f
            }
            ST_RETURN, ST_ENTER -> {
                e.state = ST_FORM
                e.leader = null
            }
        }
    }

    private fun orphanEscorts(e: Enemy) {
        for (o in enemies) {
            if (o.leader === e && !o.dead && !o.isCapturedShip) {
                o.leader = null
                o.state = ST_RETURN
                o.segs.clear(); o.segIdx = 0; o.segT = 0f
                o.segs.add(segLine(slotX(o.col), slotY(o.row), 1.0f))
            }
        }
    }

    // ---- stage setup ----
    private fun buildStage() {
        enemies.clear(); bullets.clear(); effects.clear(); floaties.clear()
        capturedBoss = null; liveBoss = null
        challenge = (stage % 4 == 3)
        challengeGroup = 0; challengeHits = 0
        attackT = 2.2f
        entSpawnIdx = 0; entSpawnT = 0f; entGroup = 0
        entranceQueue.clear()
        if (challenge) {
            // 40 flyers, 5 groups of 8
            for (g in 0 until 5) for (i in 0 until 8) {
                val k = when ((g * 8 + i) % 4) { 1 -> K_RED; 3 -> K_BOSS; else -> K_BEE }
                entranceQueue.add(intArrayOf(k, -1, -1, g))
            }
            play("gal_chal")
            state = ST_ENTR
        } else {
            val list = ArrayList<IntArray>()
            for (i in 0 until 4) list.add(intArrayOf(K_BOSS, 0, 3 + i, -1))
            if (stage == 1) {
                for (i in 0 until 8) list.add(intArrayOf(K_RED, 1, 1 + i, -1))
                for (i in 0 until 8) list.add(intArrayOf(K_BEE, 3, 1 + i, -1))
            } else {
                for (r in 1..2) for (i in 0 until 8) list.add(intArrayOf(K_RED, r, 1 + i, -1))
                for (r in 3..4) for (i in 0 until 10) list.add(intArrayOf(K_BEE, r, i, -1))
            }
            entranceQueue.addAll(list)
            state = ST_ENTR
            play("gal_start")
        }
        entSideIsLeft = true
    }

    private fun spawnEntrant(kind: Int, row: Int, col: Int, group: Int) {
        val fromLeft = entSideIsLeft
        val e = Enemy(kind, row, col, if (fromLeft) -cell * 2f else screenW + cell * 2f,
            formTop + rowH * 2.2f, ST_ENTER, if (kind == K_BOSS) 2 else 1, false,
            chalGroup = group)
        e.segs.add(segLine(if (fromLeft) cell * 2.5f else screenW - cell * 2.5f,
            formTop + rowH * 2.4f, 0.9f))
        val loopCx = formCx + (if (fromLeft) -colW * 1.2f else colW * 1.2f)
        val loopCy = formTop + rowH * 2.6f
        val dir = if (fromLeft) 1f else -1f
        e.segs.add(segCirc(loopCx, loopCy, cell * 2.3f, dir * 2f * PI.toFloat() * 1.5f, 1.5f))
        if (!challenge) {
            e.segs.add(segLine(slotX(col), slotY(row), 1.0f))
        } else {
            // challenge: fly through, loop again, exit opposite side
            val ex = if (fromLeft) screenW + cell * 2f else -cell * 2f
            e.segs.add(segSine(formCx + (if (fromLeft) colW * 2.5f else -colW * 2.5f), formTop + rowH * (1.5f + (group % 3)), 1.1f, cell * 1.6f, 2f))
            e.segs.add(segCirc(formCx + (if (fromLeft) colW * 2.2f else -colW * 2.2f), formTop + rowH * 2.8f, cell * 2.0f, -dir * 2f * PI.toFloat(), 1.2f))
            e.segs.add(segLine(ex, formTop + rowH * 2.8f, 1.2f))
        }
        enemies.add(e)
        if (kind == K_BOSS) liveBoss = e
    }

    private fun updateEntrance(dt: Float) {
        entSpawnT -= dt
        val perGroup = 8
        val groups = if (challenge) 5 else (entranceQueue.size + perGroup - 1) / perGroup
        if (entGroup < groups && entSpawnT <= 0f) {
            // launch one enemy every 0.30s within a group, gap 3.0s between groups
            if (entSpawnIdx % perGroup == 0) entSideIsLeft = (entGroup % 2 == 0)
            val idx = entGroup * perGroup + (entSpawnIdx % perGroup)
            if (idx < entranceQueue.size) {
                val q = entranceQueue[idx]
                spawnEntrant(q[0], q[1], q[2], if (challenge) q[3] else -1)
                entSpawnT = if (entSpawnIdx % perGroup == perGroup - 1) 3.0f else 0.30f
                entSpawnIdx++
                if (entSpawnIdx % perGroup == 0) entGroup++
            } else {
                entGroup = groups
            }
        }
    }

    private fun allEntrantsDone(): Boolean {
        return entGroup >= (if (challenge) 5 else (entranceQueue.size + 7) / 8) &&
            enemies.none { it.state == ST_ENTER }
    }

    // ---- attack scheduler ----
    private fun scheduleAttack() {
        val fliers = enemies.filter { it.state == ST_FORM && !it.isCapturedShip }
        if (fliers.isEmpty()) return
        val bossList = fliers.filter { it.kind == K_BOSS }
        val beamChance = if (bossList.any { it.hasCaptured }) 0.10f else 0.35f
        if (bossList.isNotEmpty() && rng.nextFloat() < beamChance) {
            val boss = bossList[rng.nextInt(bossList.size)]
            // capture beam dive, bring up to 2 red escorts
            val escorts = pickEscorts(fliers, 2)
            boss.leader = null
            boss.state = ST_DIVE
            boss.segs.clear(); boss.segIdx = 0; boss.segT = 0f
            boss.segs.add(segCirc(boss.x, boss.y - cell * 2.2f, cell * 2.0f, 2f * PI.toFloat(), 1.0f))
            boss.segs.add(segLine(boss.x, playerY - cell * 2.4f, 1.3f))
            boss.segs.add(segHov(2.6f))
            for (esc in escorts) attachEscort(esc, boss, 2)
            play("gal_dive")
        } else {
            val solo = rng.nextFloat() < 0.55f || bossList.isEmpty()
            if (solo) {
                val bees = fliers.filter { it.kind == K_BEE }
                val reds = fliers.filter { it.kind == K_RED }
                val pool = if (bees.isNotEmpty() && (reds.isEmpty() || rng.nextBoolean())) bees else reds
                if (pool.isEmpty()) return
                val lead = pool[rng.nextInt(pool.size)]
                lead.leader = null
                lead.state = ST_DIVE
                lead.segs.clear(); lead.segIdx = 0; lead.segT = 0f
                lead.segs.add(segCirc(lead.x, lead.y - cell * 2.0f, cell * 1.9f, if (rng.nextBoolean()) 2f * PI.toFloat() else -2f * PI.toFloat(), 1.0f))
                lead.segs.add(segSine(lead.x + (rng.nextFloat() - 0.5f) * colW * 6f, screenH + cell * 2f, 2.0f, colW * 1.4f, 2f))
                // sometimes a buddy joins
                if (pool.size > 1 && rng.nextFloat() < 0.4f) {
                    val buddy = pool.firstOrNull { it !== lead && it.kind == lead.kind } ?: return
                    buddy.leader = lead; buddy.escDx = colW * 0.9f; buddy.escDy = cell * 1.2f
                    buddy.state = ST_DIVE
                    buddy.segs.clear(); buddy.segIdx = 0; buddy.segT = 0f
                }
                play("gal_dive")
            } else {
                val boss = bossList[rng.nextInt(bossList.size)]
                val escorts = pickEscorts(fliers, 2)
                boss.leader = null
                boss.state = ST_DIVE
                boss.segs.clear(); boss.segIdx = 0; boss.segT = 0f
                boss.segs.add(segCirc(boss.x, boss.y - cell * 2.2f, cell * 2.0f, 2f * PI.toFloat(), 1.0f))
                boss.segs.add(segSine(boss.x + (rng.nextFloat() - 0.5f) * colW * 6f, screenH + cell * 2f, 2.0f, colW * 1.2f, 2f))
                for (esc in escorts) attachEscort(esc, boss, 2)
                play("gal_dive")
            }
        }
    }

    private fun pickEscorts(fliers: List<Enemy>, want: Int): List<Enemy> {
        val reds = fliers.filter { it.kind == K_RED && it.state == ST_FORM && it.leader == null }
        val out = ArrayList<Enemy>()
        var i = 0
        while (out.size < want && i < reds.size) { out.add(reds[i]); i++ }
        return out
    }

    private fun attachEscort(esc: Enemy, boss: Enemy, maxN: Int) {
        esc.leader = boss
        esc.escDx = if (esc.row % 2 == 0) -colW * 0.9f else colW * 0.9f
        esc.escDy = cell * 1.15f
        esc.state = ST_DIVE
        esc.segs.clear(); esc.segIdx = 0; esc.segT = 0f
    }

    private fun updateFormation(dt: Float) {
        sway = sin(time * 0.55f) * colW * 0.35f
        breath = 1f + 0.10f * sin(time * 0.9f)
        formCx = screenW / 2f + sway
    }

    private fun updateEnemies(dt: Float) {
        for (e in enemies) {
            if (e.dead) continue
            if (e.isCapturedShip) { updateCapturedShip(e, dt); continue }
            when (e.state) {
                ST_FORM -> {
                    e.x = slotX(e.col); e.y = slotY(e.row)
                    if (e.leader != null && e.leader!!.state == ST_FORM) orphanEscorts(e)
                }
                ST_DIVE -> {
                    val L = e.leader
                    if (L != null && !L.dead && (L.state == ST_DIVE || L.state == ST_HOVER)) {
                        // ride the leader's dive path with an escort offset
                        e.x = L.x + e.escDx
                        e.y = L.y + e.escDy
                    } else {
                        e.leader = null
                        stepSegs(e, dt)
                    }
                }
                ST_ENTER, ST_RETURN, ST_HOVER -> stepSegs(e, dt)
            }
            if (e.state == ST_HOVER && !e.beamOn) {
                e.beamOn = true
                e.beamT = 2.6f
                play("gal_beam")
            }
            if (e.beamOn) {
                e.beamT -= dt
                if (e.beamT <= 0f) {
                    e.beamOn = false
                    // retract: return to formation
                    e.state = ST_RETURN
                    e.segs.clear(); e.segIdx = 0; e.segT = 0f
                    e.segs.add(segLine(slotX(e.col), slotY(e.row), 1.3f))
                }
            }
            // divers fire
            if (!challenge && (e.state == ST_DIVE) && e.y > screenH * 0.3f && e.y < playerY - cell) {
                e.fired -= dt
                val cap = 5 + min(6, stage)
                if (e.fired <= 0f && bullets.count { !it.mine && !it.dead } < cap) {
                    e.fired = 0.55f + rng.nextFloat() * 0.6f
                    if (rng.nextFloat() < 0.75f) fireEnemyShot(e.x, e.y)
                }
            }
        }
    }

    private fun updateCapturedShip(e: Enemy, dt: Float) {
        val b = e.leader
        if (b == null || b.dead) {
            // boss gone: freed or destroyed elsewhere; handled by kill logic
            e.dead = true
            return
        }
        e.x = b.x + e.escDx
        e.y = b.y + e.escDy
    }

    private fun fireEnemyShot(x: Float, y: Float) {
        if (!playerAlive) return
        val dx = (playerX - x) + (rng.nextFloat() - 0.5f) * cell * 4f
        val dy = (playerY - y).coerceAtLeast(cell * 3f)
        val len = sqrt(dx * dx + dy * dy)
        val sp = cell * (7f + min(4f, stage * 0.25f))
        bullets.add(Bullet(x, y + cell * 0.4f, dx / len * sp, dy / len * sp, false))
        play("gal_shot", 0.35f)
    }

    // ---- player & capture ----
    private fun updatePlayer(dt: Float) {
        if (gameOverPending && !playerAlive && capturePhase == 0 && respawnT <= 0f && state != ST_OVER) {
            state = ST_OVER
            stateT = 0f
        }
        if (capturePhase > 0) {
            val b = capturedBoss
            captureT += dt
            if (capturePhase == 1 && b != null) {
                // slide under the beam center, then rise with the boss
                val k = min(1f, captureT / 0.7f)
                playerX += (b.x - playerX) * k * 0.35f
                if (captureT > 0.7f) {
                    capturePhase = 2
                    captureT = 0f
                    play("gal_capture")
                }
            } else if (capturePhase == 2 && b != null) {
                playerX = b.x
                playerY = b.y + cell * 1.15f
                if (b.state == ST_RETURN || b.state == ST_FORM) {
                    // boss is heading home with its prize
                    if (b.state == ST_FORM) {
                        finishCapture(b)
                    }
                }
            }
            return
        }
        if (!playerAlive) {
            respawnT -= dt
            if (respawnT <= 0f && !gameOverPending && state != ST_OVER) {
                playerAlive = true
                playerX = screenW / 2f
                invulnT = 2f
                if (pendingDual) { dual = true; pendingDual = false }
            }
            return
        }
        invulnT -= dt
        // drag steering
        if (targetX >= 0f) playerX += (targetX - playerX) * min(1f, dt * 14f)
        playerX = playerX.coerceIn(shipW * 0.6f, screenW - shipW * 0.6f)
        // firing
        fireCool -= dt
        val cap = if (dual) 2 else 1
        if ((pressing || tapQueued) && fireCool <= 0f && bullets.count { it.mine && !it.dead } < cap) {
            fireCool = 0.12f
            tapQueued = false
            if (dual) {
                bullets.add(Bullet(playerX - shipW * 0.45f, playerY - cell * 0.9f, 0f, -cell * 26f, true))
                bullets.add(Bullet(playerX + shipW * 0.45f, playerY - cell * 0.9f, 0f, -cell * 26f, true))
            } else {
                bullets.add(Bullet(playerX, playerY - cell * 0.9f, 0f, -cell * 26f, true))
            }
        }
    }

    private var targetX = -1f

    private fun finishCapture(b: Enemy) {
        b.hasCaptured = true
        b.beamOn = false
        capturePhase = 0
        capturedBoss = null
        // the captured fighter now rides beside the boss in the formation
        val cap = Enemy(K_BEE, b.row, b.col, b.x + colW * 0.62f, b.y, ST_FORM, 1, false, isCapturedShip = true)
        cap.leader = b
        cap.escDx = colW * 0.62f; cap.escDy = 0f
        enemies.add(cap)
        if (wasDualAtCapture) {
            dual = false
        } else {
            lives--
            if (lives <= 0) { gameOverPending = true }
            playerAlive = false
            respawnT = 1.2f
        }
    }

    private fun updateBeamCapture(dt: Float) {
        if (capturePhase > 0 || !playerAlive || dual) return
        for (e in enemies) {
            if (e.kind != K_BOSS || e.dead || !e.beamOn) continue
            if (abs(playerX - e.x) < cell * 1.5f && playerY > e.y) {
                capturedBoss = e
                wasDualAtCapture = false
                capturePhase = 1
                captureT = 0f
                return
            }
        }
    }

    private fun freeCapturedShip(b: Enemy) {
        // boss holding our fighter went down: dock it beside us as a dual fighter
        val cs = enemies.firstOrNull { it.isCapturedShip && !it.dead && it.leader === b }
        cs?.dead = true
        b.hasCaptured = false
        if (playerAlive || respawnT > 0f || capturePhase > 0) {
            if (capturePhase > 0) { capturePhase = 0; capturedBoss = null; playerAlive = true; playerY = screenH - cell * 2.2f }
            if (dual) { /* already dual */ } else { dual = true }
            pendingDual = false
            play("gal_rescue")
        } else {
            pendingDual = true
        }
    }

    // ---- bullets & collisions ----
    private fun updateBullets(dt: Float) {
        val it2 = bullets.iterator()
        while (it2.hasNext()) {
            val b = it2.next()
            b.x += b.vx * dt
            b.y += b.vy * dt
            if (b.y < -cell || b.y > screenH + cell || b.x < -cell || b.x > screenW + cell) b.dead = true
            if (b.dead) { it2.remove(); continue }
            if (b.mine) {
                for (e in enemies) {
                    if (e.dead || e.isCapturedShip) {
                        if (e.isCapturedShip && !e.dead) {
                            // shooting your own captured fighter destroys it
                            if (abs(b.x - e.x) < enemyW * 0.5f && abs(b.y - e.y) < enemyW * 0.5f) {
                                e.dead = true; b.dead = true
                                effects.add(Expl(e.x, e.y, 0f, false))
                                play("gal_hit")
                                break
                            }
                        }
                        continue
                    }
                    if (abs(b.x - e.x) < enemyW * 0.55f && abs(b.y - e.y) < enemyW * 0.55f) {
                        b.dead = true
                        hitEnemy(e, b.x, b.y)
                        break
                    }
                }
            } else if (playerAlive && capturePhase == 0 && invulnT <= 0f) {
                if (abs(b.x - playerX) < shipW * 0.55f && abs(b.y - playerY) < shipW * 0.7f) {
                    b.dead = true
                    killPlayer()
                }
            }
        }
    }

    private fun hitEnemy(e: Enemy, bx: Float, by: Float) {
        e.hp--
        if (e.hp > 0) { e.hurt = true; play("gal_hit", 0.5f); return }
        killEnemy(e, bx, by)
    }

    private fun killEnemy(e: Enemy, bx: Float, by: Float) {
        e.dead = true
        effects.add(Expl(e.x, e.y, 0f, false))
        play("gal_hit")
        if (challenge) {
            val pts = 100 * (e.chalGroup + 1)
            challengeHits++
            addScore(pts)
            floaties.add(FloatTxt(e.x, e.y, 0.8f, "$pts"))
            return
        }
        if (capturedBoss === e) {
            // the boss went down mid-capture: our fighter escapes, no life spent
            capturePhase = 0
            capturedBoss = null
            playerAlive = true
            play("gal_rescue")
        }
        var pts = when (e.kind) {
            K_BOSS -> 150
            K_RED -> if (e.state == ST_FORM || e.state == ST_ENTER) 80 else 160
            else -> if (e.state == ST_FORM || e.state == ST_ENTER) 50 else 100
        }
        if (e.kind == K_BOSS && e.state == ST_DIVE) {
            val n = enemies.count { !it.dead && !it.isCapturedShip && it.leader === e && (it.state == ST_DIVE || it.state == ST_ENTER) }
            pts = when (n) {
                1 -> 800; 2 -> 1600; else -> 400
            }
        }
        addScore(pts)
        if (e.state != ST_FORM) floaties.add(FloatTxt(e.x, e.y, 0.8f, "$pts"))
        // escorts and captured ship orphaned
        if (e.hasCaptured) freeCapturedShip(e)
        for (o in enemies) {
            if (o.leader === e && !o.dead) {
                o.leader = null
                if (!o.isCapturedShip && o.state == ST_DIVE) {
                    o.state = ST_RETURN
                    o.segs.clear(); o.segIdx = 0; o.segT = 0f
                    o.segs.add(segLine(slotX(o.col), slotY(o.row), 1.0f))
                }
            }
        }
    }

    private fun killPlayer() {
        if (dual) {
            // a hit on the dual fighter only takes one of the pair
            dual = false
            invulnT = 2f
            effects.add(Expl(playerX, playerY, 0f, true))
            play("gal_pdie")
            return
        }
        playerAlive = false
        lives--
        effects.add(Expl(playerX, playerY, 0f, true))
        play("gal_pdie")
        if (lives <= 0) gameOverPending = true else respawnT = 1.6f
    }

    private fun playerVsEnemies() {
        if (!playerAlive || capturePhase != 0 || invulnT > 0f || challenge) return
        for (e in enemies) {
            if (e.dead) continue
            if (e.state == ST_FORM) continue
            if (abs(e.x - playerX) < (shipW + enemyW) * 0.5f && abs(e.y - playerY) < (shipW + enemyW) * 0.5f) {
                if (!e.isCapturedShip) {
                    killEnemy(e, e.x, e.y)
                    killPlayer()
                    return
                }
            }
        }
    }

    private fun addScore(p: Int) {
        score += p
        if (score >= nextExtra) {
            lives++
            nextExtra += 70000
            play("gal_rescue", 0.5f)
        }
        if (score > highScore) { highScore = score; prefs.edit().putInt("high", highScore).apply() }
    }

    // ---- main update ----
    private fun update(dt: Float) {
        starT += dt
        updateFormation(dt)
        when (state) {
            ST_READY -> {
                stateT += dt
                if (stateT > 2.2f) { stateT = 0f; buildStage() }
            }
            ST_ENTR -> {
                updateEntrance(dt)
                updateEnemies(dt)
                updateBullets(dt)
                updatePlayer(dt)
                if (allEntrantsDone()) {
                    state = if (challenge) ST_PLAY else ST_PLAY
                    stateT = 0f
                    attackT = 1.2f
                }
            }
            ST_PLAY -> {
                updateEnemies(dt)
                updateBullets(dt)
                updatePlayer(dt)
                updateBeamCapture(dt)
                playerVsEnemies()
                if (!challenge) {
                    attackT -= dt
                    val maxGroups = min(3, 1 + stage / 4)
                    if (attackT <= 0f) {
                        attackT = max(0.9f, 2.4f - stage * 0.10f) + rng.nextFloat() * 0.8f
                        val diving = enemies.count { it.state == ST_DIVE || it.state == ST_HOVER }
                        if (diving < maxGroups * 2) scheduleAttack()
                    }
                    if (enemies.none { !it.dead && !it.isCapturedShip }) {
                        state = ST_CLEAR; stateT = 0f
                    }
                } else {
                    if (enemies.all { it.dead || it.exited }) {
                        state = ST_RESULTS; stateT = 0f
                    }
                }
            }
            ST_CLEAR -> {
                stateT += dt
                if (stateT > 1.8f) { stage++; stateT = 0f; buildStage() }
            }
            ST_RESULTS -> {
                stateT += dt
                if (stateT > 2.8f) {
                    if (challengeHits == 40) addScore(10000)
                    stage++; stateT = 0f; buildStage()
                }
            }
            ST_OVER -> {
                stateT += dt
            }
        }
        for (ex in effects) ex.t += dt
        effects.removeAll { it.t > (if (it.big) 0.7f else 0.45f) }
        for (f in floaties) { f.t -= dt; f.y -= dt * cell * 1.5f }
        floaties.removeAll { it.t <= 0f }
    }

    // ---- render ----
    private fun render(c: Canvas) {
        // starfield: twinkling white/red/blue
        if (stars.isEmpty()) {
            for (i in 0 until 90) {
                stars.add(floatArrayOf(rng.nextFloat() * screenW, rng.nextFloat() * screenH,
                    0.8f + rng.nextFloat() * 1.8f, rng.nextInt(3).toFloat()))
            }
        }
        for (s in stars) {
            var tw = 0.5f + 0.5f * sin(starT * s[2] * 2f)
            if (s[3] == 0f) tw *= 0.9f
            val col = when (s[3].toInt()) {
                0 -> Color.rgb((200 * tw + 55).toInt(), 60, 60)
                1 -> Color.rgb(60, 60, (200 * tw + 55).toInt())
                else -> Color.rgb((220 * tw + 30).toInt(), (220 * tw + 30).toInt(), (220 * tw + 30).toInt())
            }
            pFill.color = col
            c.drawRect(s[0], s[1], s[0] + 2.5f, s[1] + 2.5f, pFill)
        }
        // enemies
        for (e in enemies) {
            if (e.dead) continue
            val frame = if ((time * 8f).toInt() % 2 == 0) 0 else 1
            val s: Sprite? = when {
                e.isCapturedShip -> sprShipR[frame]
                e.kind == K_BOSS -> if (e.hurt) sprBossH[frame] else sprBoss[frame]
                e.kind == K_RED -> sprRed[frame]
                else -> sprBee[frame]
            }
            val flipH = e.x < formCx && (e.state == ST_DIVE || e.state == ST_ENTER)
            s?.draw(c, e.x, e.y, if (e.isCapturedShip) shipW else enemyW, pSprite, flipH)
            // tractor beam
            if (e.beamOn) {
                val w1 = cell * 0.9f
                val w2 = cell * 3.4f
                val len = screenH - e.y
                pFill.color = Color.argb(90, 60, 230, 255)
                val path = android.graphics.Path()
                path.moveTo(e.x - w1, e.y + cell * 0.6f)
                path.lineTo(e.x + w1, e.y + cell * 0.6f)
                path.lineTo(e.x + w2, e.y + len)
                path.lineTo(e.x - w2, e.y + len)
                path.close()
                c.drawPath(path, pFill)
                val phase = (time * 10f).toInt() % 2 == 0
                pFill.color = Color.argb(if (phase) 70 else 110, 180, 250, 255)
                val path2 = android.graphics.Path()
                path2.moveTo(e.x - w1 * 0.55f, e.y + cell * 0.6f)
                path2.lineTo(e.x + w1 * 0.55f, e.y + cell * 0.6f)
                path2.lineTo(e.x + w2 * 0.55f, e.y + len)
                path2.lineTo(e.x - w2 * 0.55f, e.y + len)
                path2.close()
                c.drawPath(path2, pFill)
            }
        }
        // bullets
        for (b in bullets) {
            if (b.dead) continue
            if (b.mine) {
                pFill.color = Color.WHITE
                c.drawRect(b.x - 2f, b.y - cell * 0.6f, b.x + 2f, b.y + cell * 0.6f, pFill)
                pFill.color = Color.rgb(120, 200, 255)
                c.drawRect(b.x - 1f, b.y - cell * 0.9f, b.x + 1f, b.y + cell * 0.6f, pFill)
            } else {
                pFill.color = Color.WHITE
                val path = android.graphics.Path()
                path.moveTo(b.x, b.y - cell * 0.45f)
                path.lineTo(b.x + cell * 0.18f, b.y + cell * 0.2f)
                path.lineTo(b.x, b.y + cell * 0.45f)
                path.lineTo(b.x - cell * 0.18f, b.y + cell * 0.2f)
                path.close()
                c.drawPath(path, pFill)
            }
        }
        // player
        if (playerAlive && capturePhase == 0) {
            val frame = if ((time * 10f).toInt() % 2 == 0) 0 else 1
            if (invulnT > 0f && (time * 12f).toInt() % 2 == 0) {
                // blink while invulnerable
            } else {
                val drawW = if (dual) shipW else shipW
                if (dual) {
                    sprShip[frame]!!.draw(c, playerX - shipW * 0.62f, playerY, shipW, pSprite)
                    sprShip[frame]!!.draw(c, playerX + shipW * 0.62f, playerY, shipW, pSprite)
                } else {
                    sprShip[frame]!!.draw(c, playerX, playerY, drawW, pSprite)
                }
            }
        }
        // captured player sliding up with the boss
        if (capturePhase >= 2) {
            capturedBoss?.let { b ->
                sprShipR[0]!!.draw(c, playerX, playerY, shipW, pSprite)
            }
        }
        // explosions
        for (ex in effects) {
            val idx = when {
                ex.t < 0.15f -> 0
                ex.t < 0.3f -> 1
                else -> 2
            }
            val w = if (ex.big) cell * 2.6f else cell * 1.7f
            sprExpl[idx]!!.draw(c, ex.x, ex.y, w, pSprite)
        }
        // floating score text
        for (f in floaties) {
            pTiny.color = Color.rgb(255, 220, 80)
            pTiny.textSize = cell * 0.9f
            c.drawText(f.s, f.x - pTiny.measureText(f.s) / 2f, f.y, pTiny)
        }
        // HUD
        pText.color = Color.rgb(255, 70, 70)
        if ((time * 2f).toInt() % 2 == 0) c.drawText("1UP", cell * 1.2f, cell * 1.5f, pText)
        pText.color = Color.rgb(120, 200, 255)
        c.drawText("HIGH SCORE", screenW / 2f - pText.measureText("HIGH SCORE") / 2f, cell * 1.5f, pText)
        pText.color = Color.WHITE
        val sc = score.toString()
        c.drawText(sc, cell * 1.2f, cell * 2.6f, pText)
        val hs = highScore.toString()
        c.drawText(hs, screenW / 2f - pText.measureText(hs) / 2f, cell * 2.6f, pText)
        // lives
        for (i in 0 until lives - 1) {
            sprShip[0]!!.draw(c, cell * (1.4f + i * 1.2f), screenH - cell * 0.9f, shipW * 0.62f, pSprite)
        }
        // stage flags
        var flags = stage
        var fx = screenW - cell * 1.0f
        pFill.color = Color.rgb(255, 220, 60)
        while (flags > 0 && fx > screenW * 0.6f) {
            val n = min(10, flags)
            val fw = cell * (0.35f * n + 0.3f)
            c.drawRect(fx - fw, screenH - cell * 1.4f, fx, screenH - cell * 0.5f, pFill)
            flags -= n
            fx -= fw + cell * 0.35f
        }
        // center texts
        pText.color = Color.rgb(120, 200, 255)
        when (state) {
            ST_READY -> {
                pText.color = Color.rgb(255, 70, 70)
                c.drawText("PLAYER ONE", center(pText, "PLAYER ONE"), screenH * 0.42f, pText)
                pText.color = Color.rgb(120, 200, 255)
                c.drawText("READY", center(pText, "READY"), screenH * 0.52f, pText)
            }
            ST_ENTR -> {
                val label = if (challenge) "CHALLENGING STAGE" else "STAGE $stage"
                if ((time * 3f).toInt() % 2 == 0) c.drawText(label, center(pText, label), screenH * 0.52f, pText)
            }
            ST_CLEAR -> {
                val label = "STAGE $stage CLEAR"
                c.drawText(label, center(pText, label), screenH * 0.52f, pText)
            }
            ST_RESULTS -> {
                pText.color = Color.WHITE
                val l1 = "NUMBER OF HITS $challengeHits"
                c.drawText(l1, center(pText, l1), screenH * 0.44f, pText)
                if (challengeHits == 40) {
                    pText.color = Color.rgb(255, 220, 60)
                    val l2 = "PERFECT !"
                    val l3 = "SPECIAL BONUS 10000 PTS"
                    c.drawText(l2, center(pText, l2), screenH * 0.54f, pText)
                    c.drawText(l3, center(pText, l3), screenH * 0.62f, pText)
                }
            }
            ST_OVER -> {
                pText.color = Color.rgb(255, 70, 70)
                c.drawText("GAME OVER", center(pText, "GAME OVER"), screenH * 0.46f, pText)
                if (stateT > 2.5f) {
                    pText.color = Color.rgb(120, 200, 255)
                    val l = "TOUCH TO PLAY AGAIN"
                    c.drawText(l, center(pText, l), screenH * 0.56f, pText)
                }
            }
        }
        pTiny.color = Color.rgb(140, 140, 160)
        c.drawText("GALAGA", cell * 1.2f, screenH - cell * 0.4f, pTiny)
    }

    private fun center(p: Paint, s: String): Float = screenW / 2f - p.measureText(s) / 2f

    // ---- touch ----
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                pressing = true
                targetX = ev.x
                if (state == ST_OVER && stateT > 2.5f) resetGame()
            }
            MotionEvent.ACTION_MOVE -> { if (pressing) targetX = ev.x }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pressing = false
                tapQueued = true
            }
        }
        return true
    }

    private fun resetGame() {
        score = 0; lives = 3; stage = 1; nextExtra = 20000
        dual = false; pendingDual = false; gameOverPending = false
        playerAlive = false; respawnT = 0.4f
        capturePhase = 0; capturedBoss = null
        enemies.clear(); bullets.clear(); effects.clear(); floaties.clear()
        state = ST_READY; stateT = 0f
    }

    // ---- lifecycle ----
    override fun surfaceCreated(holder: SurfaceHolder) {
        cell = min(screenW / 14f, screenH / 24f).takeIf { screenW > 0 } ?: cell
        if (screenW > 0) {
            colW = cell * 1.18f; rowH = cell * 1.05f
            formTop = screenH * 0.21f
            shipW = cell * 1.05f; enemyW = cell * 1.05f
            playerY = screenH - cell * 2.2f
            formCx = screenW / 2f
        }
        if (!running) {
            running = true
            thread = Thread(this); thread?.start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenW = width.toFloat(); screenH = height.toFloat()
        cell = min(width / 14f, height / 24f)
        colW = cell * 1.18f; rowH = cell * 1.05f
        formTop = screenH * 0.21f
        shipW = cell * 1.05f; enemyW = cell * 1.05f
        playerY = screenH - cell * 2.2f
        formCx = screenW / 2f
        stars.clear()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        try { thread?.join() } catch (_: InterruptedException) {}
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadSprites()
        loadSounds()
        holder.addCallback(this)
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
        surfaceChanged(holder, 0, width, height)
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun run() {
        while (running) {
            if (!holder.surface.isValid) continue
            val now = System.nanoTime()
            var dt = (now - lastNs) / 1e9f
            lastNs = now
            if (dt <= 0f || dt > 0.05f) dt = 0.05f
            var c: Canvas? = null
            try {
                time += dt
                update(dt)
                c = holder.lockCanvas()
                if (c != null) {
                    c.drawColor(Color.BLACK)
                    render(c)
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                try {
                    if (c == null) c = holder.lockCanvas()
                    if (c != null) {
                        c.drawColor(Color.BLACK)
                        pText.color = Color.RED
                        c.drawText("GALAGA ERROR", 40f, screenH / 2f, pText)
                        pTiny.color = Color.LTGRAY
                        c.drawText(ex.toString(), 40f, screenH / 2f + 60f, pTiny)
                    }
                } catch (_: Exception) {}
            } finally {
                try { if (c != null) holder.unlockCanvasAndPost(c) } catch (_: Exception) {}
            }
        }
    }
}
