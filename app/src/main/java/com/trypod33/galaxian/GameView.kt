package com.trypod33.galaxian

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    companion object {
        const val STATE_READY = 0
        const val STATE_PLAYING = 1
        const val STATE_DYING = 2
        const val STATE_OVER = 3
        // alien kinds
        const val FLAG = 0
        const val RED = 1
        const val PURPLE = 2
        const val BLUE = 3
        const val BOSS = 4
        // alien states
        const val FORM = 0
        const val DIVE = 1
        const val RETURN = 2
        const val CHAL = 3
        // diver phases
        const val PH_LOOP = 0
        const val PH_DIVE = 1
        const val PH_EXIT = 2
        const val PH_HOVER = 3
    }

    private val prefs = context.getSharedPreferences("galaxian", Context.MODE_PRIVATE)
    private var highScore = prefs.getInt("high", 0)
    private var mode2 = prefs.getBoolean("part2", false)

    @Volatile private var running = false
    private var thread: Thread? = null
    private var lastNs = 0L
    private var time = 0f

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        isFakeBoldText = true
    }
    private val pSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 30f
    }
    private val pFloat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 220, 80)
        textSize = 26f
        isFakeBoldText = true
    }
    private val pSprite = Paint().apply { isFilterBitmap = false }

    // ---- sprites ----
    private class Sprite(val bmp: Bitmap, glow: Int) {
        private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(glow, PorterDuff.Mode.SRC_IN)
            alpha = 70
        }
        fun draw(canvas: Canvas, cx: Float, cy: Float, w: Float, p: Paint, rot: Float = 0f, flipH: Boolean = false) {
            val h = w * bmp.height.toFloat() / bmp.width
            val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            val grect = RectF(cx - w * 0.64f, cy - h * 0.64f, cx + w * 0.64f, cy + h * 0.64f)
            if (flipH) {
                canvas.save()
                canvas.scale(-1f, 1f, cx, cy)
                canvas.drawBitmap(bmp, null, grect, pGlow)
                canvas.drawBitmap(bmp, null, rect, p)
                canvas.restore()
                return
            }
            if (rot != 0f) {
                canvas.save()
                canvas.rotate(rot, cx, cy)
                canvas.drawBitmap(bmp, null, grect, pGlow)
                canvas.drawBitmap(bmp, null, rect, p)
                canvas.restore()
            } else {
                canvas.drawBitmap(bmp, null, grect, pGlow)
                canvas.drawBitmap(bmp, null, rect, p)
            }
        }
    }
    private var sprShip: Sprite? = null
    private var sprFlag = arrayOfNulls<Sprite>(2)
    private var sprRed = arrayOfNulls<Sprite>(2)
    private var sprPurple = arrayOfNulls<Sprite>(2)
    private var sprBlue = arrayOfNulls<Sprite>(2)
    private var sprBoss = arrayOfNulls<Sprite>(2)
    private var sprBossH = arrayOfNulls<Sprite>(2)
    private var sprExpl = arrayOfNulls<Sprite>(3)

    private fun kindSprite(kind: Int, frame: Int, hurt: Boolean = false): Sprite? = when (kind) {
        FLAG -> sprFlag[frame]
        RED -> sprRed[frame]
        PURPLE -> sprPurple[frame]
        BOSS -> if (hurt) sprBossH[frame] else sprBoss[frame]
        else -> sprBlue[frame]
    }

    // ---- sound ----
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
    private fun play(name: String, vol: Float = 0.55f) {
        val id = sIds[name] ?: return
        snd.play(id, vol, vol, 1, 0, 1f)
    }

    // ---- field layout ----
    private val COLS = 10
    private var cell = 24f
    private var colW = 0f
    private var rowH = 0f
    private var formLeft = 0f
    private var formTop = 0f
    private var screenW = 0f
    private var screenH = 0f

    private var state = STATE_READY
    private var score = 0
    private var lives = 3
    private var wave = 1
    private var extraGiven = false
    private var carryFlags = 0
    private var melee = false
    private var swayT = 0f
    private var heartT = 0f

    // part 2 (Galaga-era additions)
    private var challenge = false
    private var chalFlight = -1
    private var chalFlightT = 0f
    private var chalKilled = 0
    private var chalMissed = 0

    // boss tractor beam / capture / dual fighter
    private var beamBoss: Alien? = null
    private var beamPlayed = false
    private var capturing = false
    private var captureT = 0f
    private var captureBoss: Alien? = null
    private var freedT = 0f
    private var freedFrom = floatArrayOf(0f, 0f)
    private var dual = false

    class Alien(var row: Int, var col: Int, var kind: Int) {
        var alive = true
        var state = FORM
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var phase = PH_LOOP
        var phaseT = 0f
        var side = 1f
        var seed = 0f
        var fireT = 0f
        var escortSlot = 0
        var leader: Alien? = null
        var hp = 1
        var bossMode = false
        var holdsShip = false
        var beamT = 0f
        var loopX = 0f
        var loopY = 0f
        // challenge-stage pathing
        var chalT = 0f
        var chalPath = 1
        var chalBaseY = 0f
        var chalOff = 0f
    }

    private val aliens = ArrayList<Alien>()
    private val groups = ArrayList<DiveGroup>()

    class DiveGroup(val leader: Alien) {
        val escorts = ArrayList<Alien>()
        val trail = ArrayList<FloatArray>()
        var escortsDead = 0
    }

    class Bullet(var x: Float, var y: Float, var vx: Float, var vy: Float)
    private val pBullets = ArrayList<Bullet>()
    private val eBullets = ArrayList<Bullet>()

    class Expl(var x: Float, var y: Float, var t: Float, var big: Boolean)
    private val expls = ArrayList<Expl>()

    private var diveTimer = 2.5f
    private var waveClearT = 0f
    private var fireCd = 0f
    private var wantFire = false

    private var px = 0f
    private var py = 0f
    private var tx = 0f
    private var touching = false
    private var invuln = 0f
    private var deathT = 0f
    private var shakeT = 0f
    private var attractT = 0f
    private val part2Rect = RectF()

    private val starCols = intArrayOf(
        Color.WHITE, Color.rgb(255, 90, 90), Color.rgb(90, 200, 255),
        Color.rgb(255, 230, 90), Color.rgb(140, 120, 255))
    private val stars = ArrayList<FloatArray>()

    private val parts = ArrayList<P>()
    private val floats = ArrayList<FT>()

    class P(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val col: Int)
    class FT(var x: Float, var y: Float, val text: String, var life: Float = 0.8f)

    init {
        holder.addCallback(this)
        fun spr(name: String, glow: Int): Sprite =
            Sprite(BitmapFactory.decodeResource(resources, resources.getIdentifier("spr_$name", "drawable", context.packageName)), glow)
        sprShip = spr("ship", Color.rgb(120, 200, 255))
        sprFlag[0] = spr("flag_0", Color.rgb(255, 230, 140)); sprFlag[1] = spr("flag_1", Color.rgb(255, 230, 140))
        sprRed[0] = spr("red_0", Color.rgb(255, 120, 110)); sprRed[1] = spr("red_1", Color.rgb(255, 120, 110))
        sprPurple[0] = spr("purple_0", Color.rgb(220, 140, 255)); sprPurple[1] = spr("purple_1", Color.rgb(220, 140, 255))
        sprBlue[0] = spr("blue_0", Color.rgb(120, 190, 255)); sprBlue[1] = spr("blue_1", Color.rgb(120, 190, 255))
        sprBoss[0] = spr("boss_0", Color.rgb(120, 255, 140)); sprBoss[1] = spr("boss_1", Color.rgb(120, 255, 140))
        sprBossH[0] = spr("bossh_0", Color.rgb(200, 130, 255)); sprBossH[1] = spr("bossh_1", Color.rgb(200, 130, 255))
        for (i in 0..2) sprExpl[i] = spr("expl_$i", Color.rgb(255, 200, 120))
        for (n in listOf("shoot", "hit", "die", "dive", "dive2", "flag", "wave", "extra", "beam", "rescue", "boom")) {
            sIds[n] = snd.load(context, resources.getIdentifier(n, "raw", context.packageName), 1)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        screenW = w.toFloat()
        screenH = h.toFloat()
        cell = min(screenW / 12f, screenH / 22f)
        colW = cell * 1.55f
        rowH = cell * 1.35f
        formLeft = (screenW - colW * (COLS - 1)) / 2f
        formTop = screenH * 0.16f
        stars.clear()
        // the arcade starfield is 252 stars from a 17-bit LFSR; 252 twinkling stars here
        repeat(252) {
            stars.add(floatArrayOf(
                Random.nextFloat() * screenW,
                Random.nextFloat() * screenH,
                Random.nextInt(starCols.size).toFloat(),
                Random.nextFloat()))
        }
        reset()
    }

    private fun isChallenge(w: Int) = mode2 && (w % 4 == 3)

    private fun reset() {
        score = 0
        lives = 3
        wave = 1
        extraGiven = false
        carryFlags = 0
        melee = false
        challenge = false
        dual = false
        aliens.clear()
        groups.clear()
        pBullets.clear()
        eBullets.clear()
        expls.clear()
        parts.clear()
        floats.clear()
        spawnFormation()
        px = screenW / 2f
        py = screenH - cell * 2.2f
        tx = px
        invuln = 0f
        diveTimer = 2.5f
        beamBoss = null
        capturing = false
        captureBoss = null
        freedT = 0f
    }

    private fun spawnFormation() {
        aliens.clear()
        melee = false
        challenge = isChallenge(wave)
        if (challenge) { startChallenge(); return }
        if (mode2) {
            // Galaga-era: 4 boss Galaga take the top row, 2 hits each
            for (c in 3..6) aliens.add(Alien(0, c, BOSS))
        } else {
            // arcade fleet: two flagships side by side, dead center like the real game
            aliens.add(Alien(0, 4, FLAG))
            aliens.add(Alien(0, 5, FLAG))
        }
        for (c in 2..7) aliens.add(Alien(1, c, RED))
        for (c in 1..8) aliens.add(Alien(2, c, PURPLE))
        // the arcade fleet is 36 aliens: two rows of ten drones, not three
        for (r in 3..4) for (c in 0..9) aliens.add(Alien(r, c, BLUE))
        for (a in aliens) {
            a.alive = true
            a.state = FORM
            a.hp = if (a.kind == BOSS) 2 else 1
            a.holdsShip = false
            a.x = slotX(a)
            a.y = slotY(a)
            a.seed = Random.nextFloat()
        }
        carryFlags = 0
        groups.clear()
    }

    private fun slotX(a: Alien) = formLeft + a.col * colW + swayX()
    private fun slotY(a: Alien) = formTop + a.row * rowH
    private fun swayX(): Float {
        val alive = aliens.count { it.alive && it.state == FORM }
        val speed = 1.1f + (1f - alive / 36f) * 2.2f + (wave - 1) * 0.08f
        return sin(swayT * speed) * colW * 0.8f
    }

    private fun burst(x: Float, y: Float, col: Int, n: Int, speed: Float = 4f) {
        repeat(n) {
            val ang = Random.nextFloat() * 6.283f
            val v = speed * cell * (0.4f + Random.nextFloat() * 0.8f)
            parts.add(P(x, y, kotlin.math.cos(ang) * v, kotlin.math.sin(ang) * v, 0.35f + Random.nextFloat() * 0.35f, col))
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (state == STATE_READY || state == STATE_OVER) {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN && part2Rect.contains(e.x, e.y)) {
                        mode2 = !mode2
                        prefs.edit().putBoolean("part2", mode2).apply()
                        play("hit", 0.5f)
                        return true
                    }
                    reset()
                    state = STATE_PLAYING
                    play("wave", 0.5f)
                }
                touching = true
                if (e.actionMasked == MotionEvent.ACTION_DOWN) wantFire = true
                tx = e.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        return true
    }

    fun pause() {
        snd.autoPause()
        running = false
        thread?.interrupt()
    }

    fun resume() {
        if (!running && holder.surface.isValid) {
            snd.autoResume()
            running = true
            thread = Thread(this).also { it.start() }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) { resume() }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) { pause() }

    override fun run() {
        while (running) {
            val canvas = try { holder.lockCanvas() } catch (t: Throwable) { null } ?: continue
            try {
                val now = System.nanoTime()
                if (lastNs == 0L) lastNs = now
                var dt = (now - lastNs) / 1e9f
                lastNs = now
                if (dt > 0.05f) dt = 0.05f
                try {
                    update(dt)
                    render(canvas)
                } catch (t: Throwable) {
                    canvas.drawColor(Color.BLACK)
                    pSmall.textSize = 22f
                    val lines = t.stackTraceToString().split("\n").take(12)
                    var y = 60f
                    for (ln in lines) { canvas.drawText(ln, 20f, y, pSmall); y += 30f }
                    pSmall.textSize = 30f
                }
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (t: Throwable) {}
            }
        }
    }

    private fun update(dt: Float) {
        time += dt
        attractT += dt
        if (shakeT > 0f) shakeT -= dt
        val pit = parts.iterator()
        while (pit.hasNext()) {
            val q = pit.next()
            q.x += q.vx * dt
            q.y += q.vy * dt
            q.vx *= (1f - 2.5f * dt)
            q.vy *= (1f - 2.5f * dt)
            q.life -= dt
            if (q.life <= 0f) pit.remove()
        }
        val fit = floats.iterator()
        while (fit.hasNext()) {
            val f = fit.next()
            f.y -= cell * 1.5f * dt
            f.life -= dt
            if (f.life <= 0f) fit.remove()
        }
        val eit = expls.iterator()
        while (eit.hasNext()) {
            val ex = eit.next()
            ex.t += dt
            if (ex.t > 0.4f) eit.remove()
        }
        if (freedT > 0f) {
            freedT -= dt
            if (freedT <= 0f) dual = true
        }

        if (state == STATE_READY || state == STATE_OVER) return
        if (invuln > 0f) invuln -= dt

        // classic dodge: the formation freezes while a shot passes through it
        val shotInForm = pBullets.any { it.y > formTop - cell * 1.5f && it.y < formTop + rowH * 4.6f }
        if (!shotInForm) swayT += dt
        for (a in aliens) if (a.alive && a.state == FORM) { a.x = slotX(a); a.y = slotY(a) }

        // player: horizontal only, classic
        if (!capturing) {
            val sp = 17f * cell * dt
            val dx = tx - px
            px += sign(dx) * min(sp, abs(dx))
            px = px.coerceIn(cell * 0.7f, screenW - cell * 0.7f)
        }

        if (state == STATE_DYING) {
            deathT -= dt
            if (deathT <= 0f) afterDeath()
            return
        }

        if (capturing) {
            captureT += dt
            val b = captureBoss
            val p = min(1f, captureT / 1.1f)
            if (b != null) {
                px += (b.x - px) * p * 0.12f
                py += ((b.y + cell * 1.4f) - py) * p * 0.12f
            }
            if (captureT > 1.4f) finishCapture()
            return
        }

        // fire: classic single shot on screen (dual fighter fires two)
        fireCd -= dt
        if ((touching || wantFire) && fireCd <= 0f) {
            if (dual && pBullets.size < 2) {
                fireCd = 0.06f
                wantFire = false
                pBullets.add(Bullet(px - cell * 0.72f, py - cell * 0.8f, 0f, -cell * 26f))
                pBullets.add(Bullet(px + cell * 0.72f, py - cell * 0.8f, 0f, -cell * 26f))
                play("shoot", 0.4f)
            } else if (!dual && pBullets.isEmpty()) {
                fireCd = 0.06f
                wantFire = false
                pBullets.add(Bullet(px, py - cell * 0.8f, 0f, -cell * 26f))
                play("shoot", 0.4f)
            }
        }

        updatePBullets(dt)
        if (challenge) {
            updateChallenge(dt)
        } else {
            heartT -= dt
            if (heartT <= 0f) { play("boom", 0.20f); heartT = 0.62f }
            updateDivers(dt)
            updateEBullets(dt)
            checkPlayerHit()
            // swarm: 3 or fewer in formation, or no blue/purple left, and they press the attack
            if (!melee) {
                val inForm = aliens.count { it.alive && it.state == FORM }
                val bp = aliens.count { it.alive && it.state == FORM && (it.kind == BLUE || it.kind == PURPLE) }
                if (inForm <= 3 || bp == 0) melee = true
            }
            if (aliens.none { it.alive }) {
                waveClearT += dt
                if (waveClearT > 1.2f) {
                    waveClearT = 0f
                    wave++
                    spawnFormation()
                    play("wave", 0.6f)
                    floats.add(FT(screenW / 2f, screenH * 0.45f, "WAVE $wave", 1.2f))
                }
            } else {
                waveClearT = 0f
            }
        }
    }

    private fun addScore(n: Int, x: Float, y: Float) {
        score += n
        floats.add(FT(x, y, "+$n"))
        if (!extraGiven && score >= 7000) {
            extraGiven = true
            lives++
            play("extra", 0.7f)
            floats.add(FT(px, py - cell * 1.5f, "EXTRA SHIP", 1.4f))
        }
    }

    private fun flagshipLadder(a: Alien): Int {
        val g = groups.firstOrNull { it.leader === a } ?: return 150
        if (g.escorts.isEmpty()) return 150  // solo flagship dive, no escorts ever assigned
        return when (g.escortsDead) {
            2 -> 800   // both escorts destroyed first, flagship last
            1 -> 200   // one escort down, flagship hit before the other
            else -> 300 // flagship hit first, both escorts still flying
        }
    }

    private fun updatePBullets(dt: Float) {
        val it = pBullets.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.y += b.vy * dt
            if (b.y < formTop - cell * 2f) { it.remove(); continue }
            var killed = false
            for (a in aliens) {
                if (!a.alive) continue
                val w = if (a.kind == FLAG || a.kind == BOSS) cell * 0.85f else cell * 0.6f
                val h = cell * 0.6f
                if (abs(b.x - a.x) < w && abs(b.y - a.y) < h) {
                    val diving = a.state != FORM && a.state != CHAL
                    // the captured fighter sits in the formation as an enemy; hitting it destroys it
                    if (a.holdsShip && a.kind != FLAG) {
                        // hit the ship itself: it is lost for good
                        a.holdsShip = false
                        addScore(1000, a.x, a.y)
                        killAlien(a, false)
                        play("hit", 0.6f)
                        it.remove()
                        killed = true
                        break
                    }
                    if (a.kind == BOSS && a.hp > 1) {
                        a.hp--
                        play("hit", 0.6f)
                        floats.add(FT(a.x, a.y, "+0", 0.3f))
                        floats.removeAt(floats.size - 1)
                        it.remove()
                        killed = true
                        break
                    }
                    val pts = when (a.kind) {
                        FLAG -> if (diving) flagshipLadder(a) else 60
                        BOSS -> if (diving) 400 else 150
                        RED -> if (diving) 100 else 50
                        PURPLE -> if (diving) 80 else 40
                        else -> if (a.state == CHAL) 100 else if (diving) 60 else 30
                    }
                    addScore(pts, a.x, a.y)
                    if (a.kind == BOSS && a.holdsShip) rescueShip(a)
                    killAlien(a, false)
                    play("hit", 0.6f)
                    it.remove()
                    killed = true
                    break
                }
            }
            if (killed) continue
        }
    }

    private fun rescueShip(a: Alien) {
        a.holdsShip = false
        freedFrom[0] = a.x
        freedFrom[1] = a.y
        freedT = 0.9f
        play("rescue", 0.8f)
        floats.add(FT(a.x, a.y, "SHIP RESCUED", 1.4f))
    }

    private fun killAlien(a: Alien, playerHit: Boolean) {
        a.alive = false
        if (a.state != FORM) {
            val g = groups.firstOrNull { it.leader === a }
            if (g != null) {
                groups.remove(g)
                for (e in g.escorts) {
                    if (!e.alive) continue
                    e.leader = null
                    if (e.state == DIVE) {
                        e.phase = PH_DIVE
                        e.leader = null
                    }
                }
            }
            val gg = groups.firstOrNull { it.escorts.contains(a) }
            if (gg != null) gg.escortsDead++
        }
        expls.add(Expl(a.x, a.y, 0f, a.kind == FLAG || a.kind == BOSS))
        burst(a.x, a.y, when (a.kind) {
            FLAG -> Color.rgb(255, 230, 120)
            BOSS -> Color.rgb(140, 255, 160)
            else -> Color.rgb(255, 150, 60)
        }, 8)
        if (playerHit) play("hit", 0.5f)
    }

    private fun updateDivers(dt: Float) {
        diveTimer -= dt
        if (diveTimer <= 0f) {
            val pool = aliens.filter { it.alive && it.state == FORM }
            if (pool.isNotEmpty() && groups.size < (if (melee) 3 else 2)) {
                if (melee) {
                    for (a in pool.shuffled().take(3)) {
                        startDive(a)
                        groups.add(DiveGroup(a))
                    }
                    play("dive", 0.5f)
                } else {
                    launchGroup(pool)
                }
                diveTimer = if (melee) 0.9f + Random.nextFloat() * 0.8f
                else (3.2f - (wave - 1) * 0.28f).coerceIn(0.9f, 3.2f) + Random.nextFloat() * 2f
            } else {
                diveTimer = 0.6f
            }
        }

        val diveSpeed = (cell * (7.5f + wave * 0.5f)).coerceAtMost(cell * 11f)

        for (g in groups.toList()) {
            val l = g.leader
            if (g.escorts.isNotEmpty()) {
                g.trail.add(floatArrayOf(l.x, l.y))
                if (g.trail.size > 240) g.trail.removeAt(0)
            }
            if (l.state == DIVE) {
                stepDiver(l, dt, diveSpeed)
                if (l.state != DIVE) {
                    for (e in g.escorts) if (e.alive && e.state == DIVE) { e.phase = PH_DIVE; e.leader = null }
                }
            } else if (l.state == RETURN) {
                stepReturner(l, dt)
                if (l.state == FORM) groups.remove(g)
            }
            for (e in g.escorts) {
                if (!e.alive) continue
                if (e.leader != null && e.state == DIVE) {
                    val idx = g.trail.size - 1 - (e.escortSlot + 1) * 12
                    if (idx >= 0) {
                        val p = g.trail[idx]
                        e.x = p[0]; e.y = p[1]
                        maybeFire(e, dt)
                        if (e.y > screenH + cell * 2f) {
                            if (melee) { e.y = -cell * 2f; e.phase = PH_DIVE; e.leader = null }
                            else { e.y = -cell * 2f; e.phase = PH_DIVE; e.leader = null; e.state = RETURN; e.x = slotX(e) }
                        }
                    } else {
                        e.phase = PH_DIVE
                        e.leader = null
                    }
                } else if (e.state == DIVE) {
                    stepDiver(e, dt, diveSpeed)
                } else if (e.state == RETURN) {
                    stepReturner(e, dt)
                }
            }
            if (!l.alive && g.escorts.none { it.alive && (it.leader != null || it.state == DIVE) }) groups.remove(g)
            if (l.state == FORM) groups.remove(g)
        }

        for (a in aliens) {
            if (!a.alive) continue
            if (a.state == DIVE && a.leader == null && groups.none { it.leader === a || it.escorts.contains(a) }) {
                stepDiver(a, dt, diveSpeed)
            } else if (a.state == RETURN && groups.none { it.leader === a || it.escorts.contains(a) }) {
                stepReturner(a, dt)
            } else if (a.state == FORM && a.holdsShip) {
                // the captured fighter rides in the formation and fires at you
                a.fireT -= dt
                if (a.fireT <= 0f && eBullets.size < 4 + wave) {
                    a.fireT = 1.6f + Random.nextFloat() * 2f
                    eBullets.add(Bullet(a.x, a.y + cell * 0.5f, 0f, cell * (8f + wave * 0.4f)))
                }
            }
        }
    }

    private fun launchGroup(pool: List<Alien>) {
        val boss = pool.firstOrNull { it.kind == BOSS }
        if (boss != null && groups.none { it.leader.kind == BOSS } && Random.nextFloat() < 0.5f) {
            startDive(boss, true)
            groups.add(DiveGroup(boss))
            play("dive2", 0.55f)
            return
        }
        val flag = pool.firstOrNull { it.kind == FLAG }
        val reds = pool.filter { it.kind == RED }
        if (flag != null && reds.isNotEmpty()) {
            val escorts = reds.sortedBy { abs(it.col - flag.col) }.take(if (reds.size >= 2) 2 else 1)
            startDive(flag)
            val g = DiveGroup(flag)
            for ((i, e) in escorts.withIndex()) {
                startDive(e)
                e.leader = flag
                e.escortSlot = i
                g.escorts.add(e)
            }
            groups.add(g)
            play("flag", 0.6f)
        } else {
            val pick = pool.filter { it.kind != FLAG }.ifEmpty { pool }.random()
            startDive(pick)
            groups.add(DiveGroup(pick))
            play("dive", 0.5f)
        }
    }

    private fun startDive(a: Alien, bossMode: Boolean = false) {
        a.state = DIVE
        a.phase = PH_LOOP
        a.phaseT = 0f
        a.side = if (a.x > screenW / 2f) 1f else -1f
        a.vx = 0f
        a.vy = 0f
        a.loopX = a.x
        a.loopY = a.y
        a.bossMode = bossMode
        a.beamT = 0f
        a.fireT = 0.3f + Random.nextFloat()
    }

    private fun stepDiver(a: Alien, dt: Float, diveSpeed: Float) {
        a.phaseT += dt
        when (a.phase) {
            PH_LOOP -> {
                // loop-the-loop entry, like the arcade peel-off
                val R = colW * 1.7f
                val cx0 = a.loopX
                val cyc = a.loopY + R
                val phi = -Math.PI.toFloat() / 2f + a.side * a.phaseT * 4.2f
                a.x = (cx0 + a.side * R * cos(phi) * 0.9f).coerceIn(cell * 0.7f, screenW - cell * 0.7f)
                a.y = cyc + R * sin(phi) * 0.85f
                if (a.phaseT > 1.1f) {
                    a.phase = if (a.bossMode) PH_HOVER else PH_DIVE
                    a.phaseT = 0f
                }
                a.fireT = 0.4f
                return
            }
            PH_HOVER -> {
                if (a.bossMode) {
                    // line up over the player, then drop the tractor beam
                    val dx = px - a.x
                    a.vx = sign(dx) * min(abs(dx) / dt.coerceAtLeast(0.0001f), cell * 6f)
                    a.vy = 0f
                    a.x = (a.x + a.vx * dt).coerceIn(cell * 0.7f, screenW - cell * 0.7f)
                    a.y += cell * 1.2f * dt
                    if (abs(a.x - px) < colW * 0.35f) {
                        a.beamT += dt
                        beamBoss = a
                        if (!beamPlayed) { play("beam", 0.6f); beamPlayed = true }
                        // tractor beam capture: only a single fighter can be taken
                        if (!dual && !capturing && abs(px - a.x) < cell * 1.05f && py > a.y) {
                            beamBoss = null
                            beamPlayed = false
                            startCapture(a)
                            return
                        }
                        if (a.beamT > 2.2f) {
                            beamBoss = null
                            beamPlayed = false
                            a.phase = PH_EXIT
                            a.vx = 0f
                            a.vy = -diveSpeed
                            a.bossMode = false
                        }
                    } else {
                        a.beamT = 0f
                    }
                    return
                }
                a.phase = PH_DIVE
            }
        }
        var txp: Float
        var typ: Float
        when (a.phase) {
            PH_DIVE -> {
                // weaving dive toward the player; purple Galaxians cut wider angles
                val amp = if (a.kind == PURPLE) colW * 3.2f else colW * 2.2f
                txp = px + sin(time * 2.6f + a.seed * 7f) * amp
                typ = a.y + cell * 5f
                if (a.y > py + cell * 3f) a.phase = PH_EXIT
            }
            else -> { // PH_EXIT: leave the screen; wrap at the bottom edge
                a.x += a.vx * dt
                a.y += a.vy * dt
                // keep divers inside the playfield instead of sliding out of reach
                a.x = a.x.coerceIn(cell * 0.7f, screenW - cell * 0.7f)
                maybeFire(a, dt)
                if (a.y > screenH + cell * 2f) {
                    if (melee && a.kind != BOSS) {
                        // melee: they no longer rejoin, they press the attack
                        a.y = -cell * 2f
                        a.x = slotX(a)
                        a.phase = PH_DIVE
                    } else {
                        a.y = -cell * 2f
                        a.x = slotX(a)
                        a.state = RETURN
                        a.vx = 0f; a.vy = cell * 2f
                    }
                }
                if (a.y < -cell * 3f) {
                    a.y = -cell * 3f
                    a.state = RETURN
                    a.vx = 0f; a.vy = cell * 2f
                }
                return
            }
        }
        val ddx = txp - a.x
        val ddy = typ - a.y
        val len = sqrt(ddx * ddx + ddy * ddy).coerceAtLeast(0.001f)
        val acc = diveSpeed * 2.2f
        a.vx += (ddx / len) * acc * dt
        a.vy += (ddy / len) * acc * dt
        val sp = sqrt(a.vx * a.vx + a.vy * a.vy)
        if (sp > diveSpeed) {
            a.vx = a.vx / sp * diveSpeed
            a.vy = a.vy / sp * diveSpeed
        }
        a.x += a.vx * dt
        a.y += a.vy * dt
        a.x = a.x.coerceIn(cell * 0.5f, screenW - cell * 0.5f)
        maybeFire(a, dt)
    }

    private fun stepReturner(a: Alien, dt: Float) {
        val txp = slotX(a)
        val typ = slotY(a)
        val ddx = txp - a.x
        val ddy = typ - a.y
        val len = sqrt(ddx * ddx + ddy * ddy).coerceAtLeast(0.001f)
        val spd = cell * 6f
        if (len < cell * 0.4f) {
            a.state = FORM
            a.vx = 0f; a.vy = 0f
            return
        }
        a.x += (ddx / len) * spd * dt
        a.y += (ddy / len) * spd * dt
    }

    private fun maybeFire(a: Alien, dt: Float) {
        if (!a.alive || a.state != DIVE || a.phase != PH_DIVE) return
        if (eBullets.size >= 4 + wave) return
        a.fireT -= dt
        if (a.fireT <= 0f) {
            a.fireT = 0.7f + Random.nextFloat() * 1.1f
            val aim = ((px - a.x) / (screenW * 0.9f)).coerceIn(-0.25f, 0.25f)
            eBullets.add(Bullet(a.x, a.y + cell * 0.5f, aim * cell * 9f, cell * (8.5f + wave * 0.4f)))
        }
    }

    private fun updateEBullets(dt: Float) {
        val it = eBullets.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.x += b.vx * dt
            b.y += b.vy * dt
            if (b.y > screenH + cell) { it.remove(); continue }
            if (b.x < -cell || b.x > screenW + cell) { it.remove(); continue }
        }
    }

    private fun checkPlayerHit() {
        if (invuln > 0f || capturing) return
        var hit = false
        val hx = if (dual) cell * 1.0f else cell * 0.5f
        for (b in eBullets) {
            if (abs(b.x - px) < hx && abs(b.y - py) < cell * 0.6f) { hit = true; break }
        }
        if (!hit) {
            for (a in aliens) {
                if (!a.alive || a.state == FORM || a.state == CHAL) continue
                if (abs(a.x - px) < hx + cell * 0.35f && abs(a.y - py) < cell * 0.55f) {
                    killAlien(a, true)
                    hit = true
                    break
                }
            }
        }
        if (hit) {
            if (dual) {
                // a hit dual fighter loses its second ship, not a life
                dual = false
                invuln = 2f
                burst(px + cell * 0.72f, py, Color.CYAN, 12, 5f)
                play("die", 0.5f)
                return
            }
            state = STATE_DYING
            deathT = 1.1f
            shakeT = 0.5f
            pBullets.clear()
            touching = false
            beamBoss = null
            beamPlayed = false
            burst(px, py, Color.CYAN, 22, 6f)
            burst(px, py, Color.WHITE, 10, 4f)
            play("die", 0.8f)
        }
    }

    private fun startCapture(b: Alien) {
        capturing = true
        captureT = 0f
        captureBoss = b
        b.phase = PH_EXIT
        b.vx = 0f
        b.vy = 0f
        pBullets.clear()
        play("die", 0.45f)
    }

    private fun finishCapture() {
        capturing = false
        captureT = 0f
        val b = captureBoss
        captureBoss = null
        lives--
        if (lives <= 0) {
            if (score > highScore) {
                highScore = score
                prefs.edit().putInt("high", highScore).apply()
            }
            state = STATE_OVER
            if (b != null && b.alive) b.holdsShip = false
            return
        }
        if (b != null && b.alive) {
            b.holdsShip = true
            b.state = RETURN
            b.phase = PH_DIVE
            b.leader = null
            b.bossMode = false
            b.fireT = 1.5f
        }
        px = screenW / 2f
        py = screenH - cell * 2.2f
        tx = px
        invuln = 2.5f
        dual = false
    }

    private fun afterDeath() {
        lives--
        if (lives <= 0) {
            if (score > highScore) {
                highScore = score
                prefs.edit().putInt("high", highScore).apply()
            }
            state = STATE_OVER
        } else {
            state = STATE_PLAYING
            px = screenW / 2f
            py = screenH - cell * 2.2f
            tx = px
            invuln = 2f
        }
    }

    // ---- Galaga-era challenging stage: 8 flights of 5, nobody fires, hit all 40 for the perfect bonus ----
    private fun startChallenge() {
        challenge = true
        aliens.clear()
        groups.clear()
        eBullets.clear()
        chalFlight = -1
        chalFlightT = 0.9f
        chalKilled = 0
        chalMissed = 0
    }

    private fun updateChallenge(dt: Float) {
        if (chalFlight < 7) {
            chalFlightT -= dt
            if (chalFlightT <= 0f && aliens.none { it.state == CHAL }) {
                chalFlight++
                val kind = when (chalFlight % 3) { 0 -> BLUE; 1 -> PURPLE; else -> RED }
                val side = if (chalFlight % 2 == 0) 1f else -1f
                val baseY = cell * (2.5f + (chalFlight % 3) * 1.6f)
                for (i in 0..4) {
                    val a = Alien(0, 0, kind)
                    a.state = CHAL
                    a.alive = true
                    a.chalPath = (if (side > 0f) 1 else -1)
                    a.chalBaseY = baseY
                    a.chalT = -(i * 0.35f)
                    a.chalOff = i * cell * 0.6f
                    a.x = -cell * 4f
                    a.y = baseY
                    aliens.add(a)
                }
                play("dive2", 0.4f)
                chalFlightT = 4.6f
            }
        } else if (aliens.none { it.alive }) {
            // stage over: perfect means all 40 shot down
            if (chalKilled == 40) {
                addScore(10000, screenW / 2f, screenH * 0.45f)
                floats.add(FT(screenW / 2f, screenH * 0.38f, "PERFECT!", 1.6f))
            } else {
                floats.add(FT(screenW / 2f, screenH * 0.42f, "HIT ${chalKilled} OF 40", 1.4f))
            }
            challenge = false
            wave++
            spawnFormation()
            play("wave", 0.6f)
            floats.add(FT(screenW / 2f, screenH * 0.45f, "WAVE $wave", 1.2f))
        }
        val cspd = cell * 5.5f
        val it = aliens.iterator()
        while (it.hasNext()) {
            val a = it.next()
            if (!a.alive) { it.remove(); continue }
            a.chalT += dt
            if (a.chalT < 0f) { a.x = -cell * 4f; a.y = a.chalBaseY; continue }
            val t = a.chalT / 5.2f
            if (t > 1f) { chalMissed++; a.alive = false; it.remove(); continue }
            val side = a.chalPath.toFloat()
            val cx = screenW * (0.5f - side * 0.18f)
            val cy = a.chalBaseY + cell * 2.5f
            val R = cell * 5f
            if (t < 0.55f) {
                val ang = (Math.PI.toFloat() / 2f) + side * (t / 0.55f) * 2f * Math.PI.toFloat()
                a.x = cx + R * cos(ang) + a.chalOff * side
                a.y = cy + R * sin(ang) * 0.8f
            } else {
                val q = (t - 0.55f) / 0.45f
                val xe = cx - side * R
                a.x = (xe + sin(q * Math.PI.toFloat() * 3f) * colW * 1.4f * -side)
                    .coerceIn(cell * 0.7f, screenW - cell * 0.7f)
                a.y = cy + q * q * (screenH + cell * 3f - cy)
            }
        }
    }

    private fun render(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        canvas.save()
        if (shakeT > 0f) {
            val k = shakeT * cell * 0.5f
            canvas.translate((Random.nextFloat() - 0.5f) * k, (Random.nextFloat() - 0.5f) * k)
        }

        // twinkling starfield, classic Galaxian colors
        for (s in stars) {
            s[1] += cell * 0.06f
            if (s[1] > screenH) s[1] -= screenH
            pFill.alpha = (40 + 165 * abs(sin(time * 2f + s[3] * 6.28f))).toInt()
            pFill.color = starCols[s[2].toInt()]
            canvas.drawCircle(s[0], s[1], 1.6f, pFill)
        }
        pFill.alpha = 255

        // enemy bullets: yellow zigzag flashes
        val zig = Path()
        for (b in eBullets) {
            zig.reset()
            zig.moveTo(b.x - cell * 0.12f, b.y - cell * 0.35f)
            zig.lineTo(b.x + cell * 0.12f, b.y - cell * 0.05f)
            zig.lineTo(b.x - cell * 0.12f, b.y + cell * 0.15f)
            zig.lineTo(b.x + cell * 0.12f, b.y + cell * 0.35f)
            pStroke.color = Color.rgb(255, 230, 80)
            pStroke.strokeWidth = 3.5f
            canvas.drawPath(zig, pStroke)
            pStroke.color = Color.WHITE
            pStroke.strokeWidth = 1.5f
            canvas.drawPath(zig, pStroke)
        }
        pStroke.strokeWidth = 2.5f

        // aliens: formation first, then divers on top
        val frame = ((time * 5f).toInt() % 2)
        for (pass in 0..1) {
            for (a in aliens) {
                if (!a.alive) continue
                val inForm = a.state == FORM
                if ((pass == 0) != inForm) continue
                val s = kindSprite(a.kind, frame, a.kind == BOSS && a.hp <= 1) ?: continue
                val w = when (a.kind) {
                    FLAG -> cell * 1.7f
                    BOSS -> cell * 1.9f
                    else -> cell * 1.35f
                }
                s.draw(canvas, a.x, a.y, w, pSprite, flipH = a.vx < -cell * 0.5f)
                if (inForm && a.holdsShip) {
                    // the captured fighter rides below its boss, tinted enemy-red
                    val pc = Paint().apply {
                        colorFilter = PorterDuffColorFilter(Color.rgb(255, 90, 90), PorterDuff.Mode.SRC_IN)
                    }
                    sprShip?.draw(canvas, a.x, a.y + cell * 1.35f, cell * 1.1f, pc)
                }
            }
        }

        // player bullets
        for (b in pBullets) {
            pFill.color = Color.rgb(120, 220, 255)
            pFill.alpha = 90
            canvas.drawCircle(b.x, b.y, 5f, pFill)
            pFill.alpha = 255
            pFill.color = Color.WHITE
            canvas.drawRect(b.x - 2f, b.y - cell * 0.35f, b.x + 2f, b.y + cell * 0.35f, pFill)
        }

        // explosions
        for (ex in expls) {
            val f = (ex.t / 0.4f * 3).toInt().coerceIn(0, 2)
            sprExpl[f]?.draw(canvas, ex.x, ex.y, if (ex.big) cell * 3f else cell * 2.2f, pSprite)
        }

        // particles
        for (q in parts) {
            pFill.alpha = (255 * (q.life / 0.7f).coerceIn(0f, 1f)).toInt()
            pFill.color = q.col
            canvas.drawCircle(q.x, q.y, cell * 0.09f, pFill)
        }
        pFill.alpha = 255

        // tractor beam: widening cone of pulsing arcs under the hovering boss
        val bb = beamBoss
        if (bb != null && bb.alive) {
            val pulse = (sin(time * 12f) + 1f) / 2f
            val bottom = screenH - cell * 1.2f
            for (i in 0..2) {
                pStroke.color = if (i == 1) Color.rgb(120, 220, 255) else Color.rgb(255, 230, 120)
                pStroke.alpha = (60 + 130 * pulse).toInt() - i * 20
                pStroke.strokeWidth = 6f - i * 1.5f
                val half = cell * (0.5f + i * 0.55f)
                canvas.drawLine(bb.x, bb.y + cell * 0.6f, bb.x - half, bottom, pStroke)
                canvas.drawLine(bb.x, bb.y + cell * 0.6f, bb.x + half, bottom, pStroke)
            }
            pStroke.strokeWidth = 2.5f
            pStroke.alpha = 255
        }

        // freed fighter flying home to dock
        if (freedT > 0f) {
            val p = 1f - freedT / 0.9f
            val fx = freedFrom[0] + (px - freedFrom[0]) * p
            val fy = freedFrom[1] + (py - freedFrom[1]) * p
            sprShip?.draw(canvas, fx, fy, cell * 1.3f, pSprite)
        }

        // player (dual fighter draws two linked ships)
        if (state == STATE_PLAYING || state == STATE_READY) {
            val blink = invuln > 0f && ((invuln * 8f).toInt() % 2 == 0)
            if (!blink) {
                if (dual) {
                    sprShip?.draw(canvas, px - cell * 0.72f, py, cell * 1.5f, pSprite)
                    sprShip?.draw(canvas, px + cell * 0.72f, py, cell * 1.5f, pSprite)
                } else {
                    sprShip?.draw(canvas, px, py, cell * 1.5f, pSprite)
                }
            }
        }

        // floating scores
        for (f in floats) {
            pFloat.alpha = (255 * (f.life / 0.8f).coerceIn(0f, 1f)).toInt()
            pFloat.textAlign = Paint.Align.CENTER
            canvas.drawText(f.text, f.x, f.y, pFloat)
        }
        pFloat.alpha = 255
        pFloat.textAlign = Paint.Align.LEFT

        canvas.restore()

        drawHud(canvas)
        drawOverlays(canvas)
    }

    private fun drawHud(canvas: Canvas) {
        pText.color = Color.rgb(255, 70, 70)
        canvas.drawText("1UP", 16f, 40f, pText)
        pText.color = Color.WHITE
        canvas.drawText("%05d".format(score), 16f, 78f, pText)
        pText.color = Color.rgb(255, 70, 70)
        pText.textAlign = Paint.Align.CENTER
        canvas.drawText("HIGH SCORE", screenW / 2f, 40f, pText)
        pText.color = Color.WHITE
        canvas.drawText("%05d".format(highScore), screenW / 2f, 78f, pText)
        pText.textAlign = Paint.Align.LEFT
        pSmall.color = Color.LTGRAY
        val wlabel = if (mode2) "PART 2  WAVE $wave" else "WAVE $wave"
        canvas.drawText(wlabel, screenW - 16f, 40f, pSmall)
        // round flags, bottom right, arcade style
        val flags = min(wave, 8)
        for (i in 0 until flags) {
            val fx = screenW - 20f - i * 26f
            val fy = screenH - 58f
            pStroke.color = Color.rgb(90, 220, 120)
            canvas.drawLine(fx, fy, fx, fy - 20f, pStroke)
            pFill.color = Color.rgb(90, 220, 120)
            val tri = Path()
            tri.moveTo(fx, fy - 20f)
            tri.lineTo(fx - 13f, fy - 14f)
            tri.lineTo(fx, fy - 8f)
            canvas.drawPath(tri, pFill)
        }
        for (i in 0 until min(lives, 6)) {
            sprShip?.draw(canvas, 30f + i * 40f, screenH - 26f, cell * 0.9f, pSprite)
        }
        pSmall.color = Color.LTGRAY
        pSmall.textAlign = Paint.Align.RIGHT
        canvas.drawText("CREDIT 01", screenW - 16f, screenH - 16f, pSmall)
        pSmall.textAlign = Paint.Align.LEFT
    }

    private fun drawOverlays(canvas: Canvas) {
        val cx = screenW / 2f
        val cy = screenH / 2f
        pText.textAlign = Paint.Align.CENTER
        when (state) {
            STATE_READY -> {
                val ph = ((attractT / 3.2f).toInt() % 3)
                if (ph == 0) {
                    pText.color = Color.rgb(120, 220, 255)
                    pText.textSize = 40f
                    canvas.drawText("WE ARE THE GALAXIANS", cx, cy - 130f, pText)
                    pText.color = Color.rgb(255, 230, 90)
                    pText.textSize = 34f
                    canvas.drawText("MISSION: DESTROY ALIENS", cx, cy - 78f, pText)
                    // a little patrol of aliens crossing under the mission text
                    val wx = ((time * cell * 2.2f) % (screenW + cell * 8f)) - cell * 4f
                    kindSprite(BLUE, frame = ((time * 5f).toInt() % 2))
                        ?.draw(canvas, wx, cy - 20f, cell * 1.3f, pSprite)
                    kindSprite(PURPLE, frame = ((time * 5f).toInt() % 2))
                        ?.draw(canvas, wx + cell * 2.2f, cy - 20f, cell * 1.3f, pSprite)
                    kindSprite(RED, frame = ((time * 5f).toInt() % 2))
                        ?.draw(canvas, wx + cell * 4.4f, cy - 20f, cell * 1.3f, pSprite)
                    kindSprite(FLAG, frame = ((time * 5f).toInt() % 2))
                        ?.draw(canvas, wx + cell * 6.6f, cy - 20f, cell * 1.6f, pSprite)
                } else if (ph == 1) {
                    pText.color = Color.WHITE
                    pText.textSize = 32f
                    canvas.drawText("SCORE ADVANCE TABLE", cx, cy - 150f, pText)
                    val rows = listOf(
                        Triple(BLUE, "30 / 60", 1.15f),
                        Triple(PURPLE, "40 / 80", 1.15f),
                        Triple(RED, "50 / 100", 1.15f),
                        Triple(FLAG, "60 / 150", 1.5f))
                    var ry = cy - 96f
                    for ((kind, label, w) in rows) {
                        kindSprite(kind, frame = ((time * 5f).toInt() % 2))
                            ?.draw(canvas, cx - 130f, ry, cell * w, pSprite)
                        pText.color = Color.rgb(120, 220, 255)
                        canvas.drawText(label, cx - 80f, ry + 12f, pText)
                        ry += cell * 2.4f
                    }
                    pText.color = Color.rgb(255, 230, 90)
                    pText.textSize = 26f
                    canvas.drawText("FLAGSHIP CONVOY: 150 200 300 800", cx, ry + 26f, pText)
                } else {
                    // rainbow GALAXIAN logo, letter by letter
                    val word = "GALAXIAN"
                    val cols = intArrayOf(
                        Color.rgb(255, 70, 70), Color.rgb(255, 160, 60), Color.rgb(255, 230, 90),
                        Color.rgb(110, 230, 110), Color.rgb(110, 200, 255), Color.rgb(150, 130, 255),
                        Color.rgb(255, 110, 200), Color.rgb(255, 70, 70))
                    pText.textSize = 62f
                    val lw = pText.measureText("W")
                    var lx = cx - word.length * lw / 2f + lw / 2f
                    for (i in word.indices) {
                        pText.color = cols[i % cols.size]
                        canvas.drawText(word[i].toString(), lx, cy - 90f, pText)
                        lx += lw
                    }
                    pText.color = Color.WHITE
                    pText.textSize = 40f
                    canvas.drawText("TAP TO START", cx, cy + 10f, pText)
                    pSmall.textAlign = Paint.Align.CENTER
                    canvas.drawText("drag to steer  |  hold to fire", cx, cy + 60f, pSmall)
                    // PART 2 chip: Galaga-era extras on/off
                    val chipW = cell * 7.5f
                    val chipH = cell * 1.9f
                    part2Rect.set(cx - chipW / 2f, cy + 96f, cx + chipW / 2f, cy + 96f + chipH)
                    pFill.color = if (mode2) Color.rgb(40, 90, 140) else Color.rgb(40, 40, 60)
                    canvas.drawRoundRect(part2Rect, 14f, 14f, pFill)
                    pStroke.color = Color.rgb(120, 220, 255)
                    canvas.drawRoundRect(part2Rect, 14f, 14f, pStroke)
                    pText.color = Color.WHITE
                    pText.textSize = 28f
                    canvas.drawText(if (mode2) "PART 2: ON" else "PART 2: OFF", cx, part2Rect.centerY() + 10f, pText)
                    pText.textSize = 40f
                    pSmall.textAlign = Paint.Align.LEFT
                }
            }
            STATE_OVER -> {
                pText.color = Color.rgb(255, 80, 80)
                pText.textSize = 58f
                canvas.drawText("GAME OVER", cx, cy - 60f, pText)
                pText.color = Color.WHITE
                pText.textSize = 42f
                canvas.drawText("SCORE %05d".format(score), cx, cy, pText)
                pText.color = if (score >= highScore && score > 0) Color.rgb(255, 220, 60) else Color.LTGRAY
                canvas.drawText("HI %05d".format(highScore), cx, cy + 48f, pText)
                pText.color = Color.WHITE
                canvas.drawText("TAP TO PLAY AGAIN", cx, cy + 120f, pText)
            }
        }
        pText.textSize = 40f
        pText.textAlign = Paint.Align.LEFT
        pSmall.textAlign = Paint.Align.LEFT
    }
}
