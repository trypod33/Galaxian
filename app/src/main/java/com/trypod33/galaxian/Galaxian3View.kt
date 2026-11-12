package com.trypod33.galaxian

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// Galaxian 3: Project Dragoon (1990/1992, Namco). Rail shooter: the Dragoon flies a
// preset course, you aim a gun-pod cursor and shoot UIMS ships down before they reach
// the ship. Shared ENERGY meter drains on every hit that gets through; the mission
// ends at the Cannon Seed, which must be destroyed before the timer runs out.
class Galaxian3View(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    companion object {
        const val READY = 0
        const val PLAYING = 1
        const val OVER = 2
        const val WIN = 3
        const val INTRO = 4
        // foe types
        const val FIGHTER = 0
        const val COMMANDER = 1
        const val GUNNER = 2
        const val FAR = 3600f
    }

    private val prefs = context.getSharedPreferences("galaxian", Context.MODE_PRIVATE)
    private var high = prefs.getInt("high3", 0)

    @Volatile private var running = false
    private var thread: Thread? = null
    private var lastNs = 0L

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG)

    private var screenW = 0f
    private var screenH = 0f
    private var camX = 0f     // projection centre on screen
    private var camY = 0f
    private var focal = 1000f
    private var bank = 0f     // world-space camera offset (the Dragoon banking)
    private var bankY = 0f

    private var state = READY
    private var score = 0
    private var energy = 100f
    private var sector = 1
    private var wave = 0
    private var phaseT = 0f
    private var beltT = 0f
    private var inBelt = false
    private var flash = 0f
    private var shake = 0f
    private var time = 0f

    // boss (Cannon Seed)
    private var bossOn = false
    private var bossX = 0f
    private var bossY = 0f
    private var bossZ = FAR
    private var bossHp = 0
    private var bossMoveT = 0f
    private var bossFireCd = 0f
    private var bossCharge = -1f   // -1 idle, else charge countdown
    private var beamT = 0f         // >0 beam active
    private var beamX = 0f
    private var bossTimer = 0f     // mission clock
    private var bossFlash = 0f

    private val cursor = FloatArray(2)   // gun-pod crosshair, screen space
    private var firing = false
    private var fireCd = 0f

    private class Star(var x: Float, var y: Float, var z: Float, var tw: Float)
    private class Foe(
        var x: Float, var y: Float, var z: Float, var type: Int,
        var hp: Int, var phase: Float, var amp: Float, var freq: Float,
        var baseX: Float, var baseY: Float, var dive: Boolean, var dvx: Float, var dvy: Float,
        var fireCd: Float, var flash: Float
    ) { var dead = false }
    private class Bolt(var x: Float, var y: Float, var z: Float, var tx: Float, var ty: Float, var zt: Float, var side: Int) { var done = false }
    private class EShot(var x: Float, var y: Float, var z: Float, var tx: Float, var ty: Float, var z0: Float, var x0: Float, var y0: Float)
    private class Rock(var x: Float, var y: Float, var z: Float, var rot: Float, var spin: Float, var hp: Int, var shape: FloatArray, var vy: Float)
    private class Boom(var x: Float, var y: Float, var z: Float, var t: Float, var big: Boolean)

    private val stars = ArrayList<Star>()
    private val foes = ArrayList<Foe>()
    private val bolts = ArrayList<Bolt>()
    private val eshots = ArrayList<EShot>()
    private val rocks = ArrayList<Rock>()
    private val booms = ArrayList<Boom>()

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

    init {
        holder.addCallback(this)
        for (n in listOf("g3laser", "g3boom", "g3hit", "g3boss", "g3warn")) {
            sIds[n] = snd.load(context, resources.getIdentifier(n, "raw", context.packageName), 1)
        }
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        screenW = w.toFloat()
        screenH = h.toFloat()
        camX = screenW / 2f
        camY = screenH * 0.42f
        focal = screenH * 1.15f
        cursor[0] = screenW / 2f
        cursor[1] = screenH * 0.45f
        stars.clear()
        repeat(170) {
            stars.add(Star(
                (Random.nextFloat() * 2f - 1f) * screenW * 1.4f,
                (Random.nextFloat() * 2f - 1f) * screenH * 1.2f,
                60f + Random.nextFloat() * (FAR - 60f),
                Random.nextFloat()))
        }
    }

    // ---- projection ----
    private fun sc(z: Float): Float = focal / (focal + z)
    private fun sx(x: Float, z: Float): Float = camX + x * sc(z)
    private fun sy(y: Float, z: Float): Float = camY + y * sc(z)
    // screen point -> world point at depth zd (accounts for the camera bank)
    private fun wx(screenX: Float, zd: Float): Float {
        val s = focal / (focal + zd)
        return (screenX - camX) / s - bank
    }
    private fun wy(screenY: Float, zd: Float): Float {
        val s = focal / (focal + zd)
        return (screenY - camY) / s - bankY
    }

    // ---- sound / lifecycle ----
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!running) {
            running = true
            thread = Thread(this).apply { start() }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        snd.autoPause()
        running = false
        thread?.interrupt()
    }

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
                    pTxt.textSize = 22f
                    pTxt.color = Color.WHITE
                    val lines = t.stackTraceToString().split("\n").take(12)
                    var y = 60f
                    for (ln in lines) { canvas.drawText(ln, 20f, y, pTxt); y += 30f }
                    pTxt.textSize = 30f
                }
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (t: Throwable) {}
            }
        }
    }

    fun pause() {
        snd.autoPause()
        running = false
        thread?.interrupt()
    }

    fun resume() {
        if (!running && holder.surface.isValid) {
            running = true
            thread = Thread(this).apply { start() }
        }
    }

    // ---- game flow ----
    private fun startGame() {
        score = 0
        energy = 100f
        sector = 1
        wave = 0
        bossOn = false
        bossHp = 0
        bossZ = FAR
        bossCharge = -1f
        beamT = 0f
        inBelt = false
        foes.clear(); bolts.clear(); eshots.clear(); rocks.clear(); booms.clear()
        state = INTRO
        phaseT = 2.2f
        play("g3warn", 0.5f)
    }

    private fun sectorSpeed() = 1f + 0.16f * (sector - 1)

    // spawn one scripted wave; returns foes it added
    private fun spawnWave(n: Int) {
        val speed = sectorSpeed()
        val kind = when {
            n % 3 == 0 -> 2   // swarm wave, staggered dive-in
            n % 3 == 1 -> 0   // classic grid
            else -> 1         // V with commanders
        }
        when (kind) {
            0 -> {
                val rows = 2 + min(2, sector / 2)
                for (r in 0 until rows) for (c in 0 until 5) {
                    val type = if (r == 0 && c == 2) COMMANDER else FIGHTER
                    addFoe(type, (c - 2) * screenW * 0.34f, -screenH * 0.28f + r * screenH * 0.17f,
                        FAR + r * 260f + c * 90f, speed)
                }
            }
            1 -> {
                val cmds = if (sector >= 3) 2 else 1
                for (i in 0 until 7) {
                    val type = if (i == 3) COMMANDER else GUNNER
                    val off = abs(i - 3) * screenW * 0.30f
                    addFoe(type, if (i <= 3) -off else off, -screenH * 0.30f - abs(i - 3) * screenH * 0.16f,
                        FAR + i * 70f, speed)
                }
                for (k in 0 until cmds) {
                    addFoe(COMMANDER, (k - (cmds - 1) / 2f) * screenW * 0.5f, screenH * 0.1f,
                        FAR + 420f + k * 120f, speed)
                }
            }
            else -> {
                for (i in 0 until 6 + sector) {
                    val edge = if (i % 2 == 0) -1 else 1
                    addFoe(if (i % 4 == 3) GUNNER else FIGHTER,
                        edge * screenW * (0.5f + Random.nextFloat() * 0.4f),
                        (Random.nextFloat() * 2f - 1f) * screenH * 0.5f,
                        FAR + i * 150f, speed * 1.1f)
                }
            }
        }
        wave++
    }

    private fun addFoe(type: Int, wx0: Float, wy0: Float, z: Float, speed: Float) {
        val hp = if (type == COMMANDER) 2 else 1
        foes.add(Foe(
            wx0, wy0, z, type, hp,
            phase = Random.nextFloat() * 6.28f,
            amp = screenW * (0.05f + Random.nextFloat() * 0.10f),
            freq = 0.9f + Random.nextFloat() * 0.8f,
            baseX = wx0, baseY = wy0,
            dive = false, dvx = 0f, dvy = 0f,
            fireCd = if (Random.nextFloat() < 0.45f) 1.0f + Random.nextFloat() * 2.2f else 999f,
            flash = 0f
        ))
    }

    private fun spawnBelt() {
        for (i in 0 until 9 + sector) {
            val shape = FloatArray(14)
            for (k in 0 until 7) shape[k * 2] = 0.55f + Random.nextFloat() * 0.45f
            rocks.add(Rock(
                (Random.nextFloat() * 2f - 1f) * screenW * 1.1f,
                (Random.nextFloat() * 2f - 1f) * screenH * 0.9f,
                FAR + i * 420f, Random.nextFloat() * 6.28f,
                (Random.nextFloat() - 0.5f) * 2.4f, 2, shape,
                (Random.nextFloat() - 0.5f) * screenH * 0.12f))
        }
    }

    private fun startBoss() {
        bossOn = true
        bossHp = 40
        bossZ = FAR
        bossX = 0f
        bossY = -screenH * 0.05f
        bossMoveT = 0f
        bossFireCd = 2.0f
        bossCharge = -1f
        beamT = 0f
        bossTimer = 75f
        play("g3boss", 0.7f)
    }

    // ---- update ----
    private fun update(dt: Float) {
        time += dt
        flash = maxOf(0f, flash - dt * 2.5f)
        shake = maxOf(0f, shake - dt * 22f)
        val tBankY = -(cursor[1] - screenH * 0.45f) * 0.08f
        bankY += (tBankY - bankY) * min(1f, dt * 4f)
        val tBankX = -(cursor[0] - screenW / 2f) * 0.11f
        bank += (tBankX - bank) * min(1f, dt * 4f)
        camX = screenW / 2f + shake * sin(time * 47f)
        camY = screenH * 0.42f + shake * cos(time * 39f)

        // starfield always runs
        val starSpd = if (state == PLAYING || state == INTRO) 1500f * sectorSpeed() else 700f
        for (s in stars) {
            s.z -= starSpd * dt
            if (s.z < 40f) {
                s.z += FAR
                s.x = (Random.nextFloat() * 2f - 1f) * screenW * 1.4f
                s.y = (Random.nextFloat() * 2f - 1f) * screenH * 1.2f
            }
        }
        booms.forEach { it.t += dt }
        booms.removeAll { it.t > 0.55f }

        when (state) {
            READY, OVER, WIN -> {
                bossCharge = -1f; beamT = 0f
            }
            INTRO -> {
                phaseT -= dt
                if (phaseT <= 0f) state = PLAYING
            }
            PLAYING -> updatePlay(dt)
        }
    }

    private fun updatePlay(dt: Float) {
        fireCd -= dt
        if (firing && fireCd <= 0f && state == PLAYING) { fire(); fireCd = 0.14f }

        // wave / belt / boss sequencing
        if (!bossOn) {
            if (inBelt) {
                beltT -= dt
                if (rocks.isEmpty() && beltT < 6f) {
                    inBelt = false
                    if (wave >= 3) {
                        if (sector >= 5) { startBoss(); } else { sector++; wave = 0; state = INTRO; phaseT = 2.2f; play("g3warn", 0.5f) }
                    } else {
                        state = INTRO; phaseT = 1.0f
                    }
                }
            } else if (foes.isEmpty() && rocks.isEmpty()) {
                if (wave >= 3) {
                    inBelt = true
                    beltT = 8f
                    spawnBelt()
                } else {
                    spawnWave(wave + 1)
                }
            }
        } else {
            updateBoss(dt)
        }

        updateFoes(dt)
        updateBolts(dt)
        updateShots(dt)
        updateRocks(dt)
    }

    private fun updateFoes(dt: Float) {
        val spd = 620f * sectorSpeed()
        for (f in foes) {
            if (f.flash > 0f) f.flash -= dt
            f.z -= spd * dt
            f.phase += dt * f.freq
            if (!f.dive) {
                f.x = f.baseX + sin(f.phase) * f.amp
                f.y = f.baseY + (FAR - f.z) * 0.015f * screenH * 0.001f
            } else {
                // swoop toward the gun position, then past
                val k = min(1f, dt * 1.6f)
                f.dvx += (-(bank) - f.x) * 0.4f * k
                f.x += f.dvx * dt
                f.y += f.dvy * dt
            }
            // peel off into a dive
            if (!f.dive && f.z < 2400f && Random.nextFloat() < dt * 0.35f) {
                f.dive = true
                val tx = wx(cursor[0], 260f)
                val ty = wy(cursor[1], 260f)
                f.dvx = (tx - f.x) * 0.9f
                f.dvy = (ty - f.y) * 0.9f
            }
            // fire
            f.fireCd -= dt
            if (f.fireCd <= 0f && f.z > 500f && f.z < 2900f && state == PLAYING) {
                eshots.add(EShot(f.x, f.y, f.z, -bank + (Random.nextFloat() * 2f - 1f) * 130f,
                    -bankY + (Random.nextFloat() * 2f - 1f) * 130f, f.z, f.x, f.y))
                f.fireCd = (1.7f + Random.nextFloat() * 1.6f) / sectorSpeed()
                if (f.type == GUNNER) f.fireCd *= 0.55f
            }
            // reached the ship
            if (f.z < 120f) {
                f.dead = true
                if (hypot(f.x + bank, f.y + bankY) < 190f) {
                    hitEnergy(if (f.type == COMMANDER) 14f else 10f)
                }
                booms.add(Boom(f.x, f.y, 60f, 0f, false))
            }
        }
        foes.removeAll { it.dead }
        if (energy <= 0f && state == PLAYING) gameOver()
    }

    private fun updateBolts(dt: Float) {
        val spd = 5200f
        for (b in bolts) {
            b.z += spd * dt
            val k = (b.z / b.zt).coerceIn(0f, 1f)
            b.x = lerpF(if (b.side == 0) -screenW * 0.34f else screenW * 0.34f, b.tx, k)
            b.y = lerpF(screenH * 0.535f, b.ty, k)
            // hits
            if (bossOn && abs(b.z - bossZ) < 320f && hypot(b.x - bossX, b.y - bossY) < 190f) {
                b.done = true
                bossHp--
                bossFlash = 0.1f
                booms.add(Boom(b.x, b.y, bossZ, 0f, false))
                play("g3boom", 0.4f)
                if (bossHp <= 0) winGame()
                continue
            }
            for (f in foes) {
                if (f.dead) continue
                if (abs(b.z - f.z) < 300f && hypot(b.x - f.x, b.y - f.y) < foeRadius(f.type) * 1.5f) {
                    b.done = true
                    f.hp--
                    f.flash = 0.12f
                    if (f.hp <= 0) killFoe(f)
                    break
                }
            }
            // can also shoot down incoming fire
            for (s in eshots) {
                if (abs(b.z - s.z) < 260f && hypot(b.x - s.x, b.y - s.y) < 120f) {
                    b.done = true
                    s.z = -10f
                    booms.add(Boom(s.x, s.y, maxOf(60f, s.z), 0f, false))
                    addScore(20)
                    break
                }
            }
            for (r in rocks) {
                if (abs(b.z - r.z) < 300f && hypot(b.x - r.x, b.y - r.y) < 150f) {
                    b.done = true
                    r.hp--
                    if (r.hp <= 0) {
                        r.z = -10f
                        booms.add(Boom(r.x, r.y, 200f, 0f, true))
                        addScore(50)
                        play("g3boom", 0.6f)
                    }
                    break
                }
            }
        }
        bolts.removeAll { it.done || it.z > FAR * 0.8f }
        eshots.removeAll { it.z < -1f }
        rocks.removeAll { it.z < -1f }
    }

    private fun updateShots(dt: Float) {
        val spd = 1150f * sectorSpeed()
        for (s in eshots) {
            if (s.z < 0f) continue
            s.z -= spd * dt
            val k = (1f - maxOf(0f, s.z) / s.z0).coerceIn(0f, 1f)
            s.x = lerpF(s.x0, s.tx, k)
            s.y = lerpF(s.y0, s.ty, k)
            if (s.z < 150f) {
                s.z = -2f
                if (hypot(s.x + bank, s.y + bankY) < 150f) hitEnergy(8f)
                else booms.add(Boom(s.x, s.y, 60f, 0f, false))
            }
        }
        eshots.removeAll { it.z < 0f }
    }

    private fun updateRocks(dt: Float) {
        val spd = 460f * sectorSpeed()
        for (r in rocks) {
            if (r.z < 0f) continue
            r.z -= spd * dt
            r.rot += r.spin * dt
            r.y += r.vy * dt
            if (r.z < 140f) {
                r.z = -10f
                if (hypot(r.x + bank, r.y + bankY) < 180f) hitEnergy(12f)
                else booms.add(Boom(r.x, r.y, 80f, 0f, false))
            }
        }
        rocks.removeAll { it.z < 0f }
    }

    private fun updateBoss(dt: Float) {
        bossFlash = maxOf(0f, bossFlash - dt)
        bossTimer -= dt
        if (bossTimer <= 0f) { gameOver(); return }
        bossMoveT += dt
        if (bossZ > 1700f) bossZ -= 420f * dt else bossZ = 1700f
        bossX = sin(bossMoveT * 0.5f) * screenW * 0.42f
        bossY = -screenH * 0.05f + cos(bossMoveT * 0.33f) * screenH * 0.06f
        bossFireCd -= dt
        if (bossFireCd <= 0f && beamT <= 0f && bossCharge < 0f) {
            for (i in -1..1) {
                eshots.add(EShot(bossX, bossY, bossZ,
                    -bank + i * 70f, -bankY + (Random.nextFloat() * 2f - 1f) * 90f, bossZ, bossX, bossY))
            }
            bossFireCd = 1.15f
            if (Random.nextFloat() < 0.32f) { bossCharge = 1.6f; play("g3warn", 0.6f) }
        }
        if (bossCharge > 0f) {
            bossCharge -= dt
            if (bossCharge <= 0f) { beamT = 1.1f; beamX = bossX; play("g3boss", 0.8f) }
        } else if (beamT > 0f) {
            beamT -= dt
            val cxw = wx(cursor[0], 900f)
            if (abs(cxw - beamX) < 150f) {
                energy -= 24f * dt
                flash = maxOf(flash, 0.3f)
                if (energy <= 0f) gameOver()
            }
        }
    }

    private fun foeRadius(type: Int) = when (type) {
        COMMANDER -> 80f
        GUNNER -> 56f
        else -> 48f
    }

    private fun killFoe(f: Foe) {
        f.dead = true
        addScore(when (f.type) {
            COMMANDER -> 300
            GUNNER -> 150
            else -> 100
        })
        booms.add(Boom(f.x, f.y, maxOf(60f, f.z), 0f, f.type == COMMANDER))
        play("g3boom", 0.55f)
    }

    private fun addScore(n: Int) {
        score += n
    }

    private fun hitEnergy(n: Float) {
        energy -= n
        flash = 1f
        shake = 14f
        play("g3hit", 0.7f)
        if (energy <= 0f) gameOver()
    }

    private fun fire() {
        val zt = 320f
        val tx = wx(cursor[0], zt)
        val ty = wy(cursor[1], zt)
        bolts.add(Bolt(0f, 0f, 0f, tx, ty, zt, 0))
        bolts.add(Bolt(0f, 0f, 0f, tx, ty, zt, 1))
        play("g3laser", 0.4f)
    }

    private fun saveHigh() {
        if (score > high) {
            high = score
            prefs.edit().putInt("high3", high).apply()
        }
    }

    private fun gameOver() {
        if (state != PLAYING) return
        saveHigh()
        state = OVER
        play("g3boom", 0.8f)
    }

    private fun winGame() {
        addScore(10000)
        saveHigh()
        state = WIN
        play("g3boom", 0.9f)
        booms.add(Boom(bossX, bossY, bossZ, 0f, true))
    }

    private fun lerpF(a: Float, b: Float, k: Float) = a + (b - a) * k

    // ---- render ----
    private fun render(canvas: Canvas) {
        if (screenW <= 0f || screenH <= 0f) { canvas.drawColor(Color.BLACK); return }
        canvas.drawColor(Color.rgb(3, 5, 14))

        // starfield with motion streaks
        for (s in stars) {
            val s1 = sc(s.z)
            val x = sx(s.x, s.z)
            val y = sy(s.y, s.z)
            if (x < -4f || x > screenW + 4f || y < -4f || y > screenH + 4f) continue
            val z2 = s.z + 150f
            val x2 = sx(s.x, z2)
            val y2 = sy(s.y, z2)
            val tw = 0.55f + 0.45f * sin(time * 4f + s.tw * 6.28f)
            pLine.strokeWidth = min(3.5f, 1.2f + 2.2f * s1)
            pLine.color = Color.argb((150 + 90 * tw).toInt(), 150 + (40 * tw).toInt(), 170, 255)
            canvas.drawLine(x2, y2, x, y, pLine)
        }

        // explosions
        for (b in booms) {
            val s = sc(b.z)
            val r = (30f + b.t * (if (b.big) 420f else 200f)) * s
            val a = (1f - b.t / 0.55f)
            pFill.style = Paint.Style.STROKE
            pFill.strokeWidth = 5f
            pFill.color = Color.argb((200 * a).toInt(), 255, if (b.big) 230 else 190, 110)
            canvas.drawCircle(sx(b.x, b.z), sy(b.y, b.z), r, pFill)
            pFill.color = Color.argb((120 * a).toInt(), 255, 140, 60)
            canvas.drawCircle(sx(b.x, b.z), sy(b.y, b.z), r * 0.55f, pFill)
        }
        pFill.style = Paint.Style.FILL

        for (r in rocks) if (r.z > 0f) drawRock(canvas, r)
        for (f in foes) if (!f.dead) drawFoe(canvas, f)
        if (bossOn) drawBoss(canvas)

        // enemy shots
        for (s in eshots) {
            if (s.z < 0f) continue
            val sc1 = sc(s.z)
            val x = sx(s.x, s.z)
            val y = sy(s.y, s.z)
            val rad = 14f * sc1 + 3f
            pFill.color = Color.argb(70, 255, 90, 40)
            canvas.drawCircle(x, y, rad * 2.4f, pFill)
            pFill.color = Color.rgb(255, 120, 50)
            canvas.drawCircle(x, y, rad, pFill)
            pFill.color = Color.rgb(255, 230, 180)
            canvas.drawCircle(x, y, rad * 0.45f, pFill)
        }

        // player tracer bolts
        pLine.strokeWidth = 4f
        pLine.color = Color.rgb(120, 255, 240)
        for (b in bolts) {
            val zp = maxOf(0f, b.z - 340f)
            canvas.drawLine(sx(b.x, zp), sy(b.y, zp), sx(b.x, b.z), sy(b.y, b.z), pLine)
        }
        pLine.strokeWidth = 0f

        drawHull(canvas)
        drawCursor(canvas)
        drawHud(canvas)

        if (flash > 0f) {
            pFill.color = Color.argb((90 * flash).toInt(), 255, 40, 30)
            canvas.drawColor(pFill.color)
        }

        when (state) {
            INTRO -> drawIntro(canvas)
            READY -> drawReady(canvas)
            OVER -> drawEnd(canvas, false)
            WIN -> drawEnd(canvas, true)
            else -> {}
        }
    }

    private fun drawFoe(canvas: Canvas, f: Foe) {
        val s = sc(f.z)
        val x = sx(f.x, f.z)
        val y = sy(f.y, f.z)
        val size = foeRadius(f.type) * s
        if (x < -size * 2 || x > screenW + size * 2 || y < -size * 2 || y > screenH + size * 2) return
        val body = when (f.type) {
            COMMANDER -> if (f.flash > 0f) Color.WHITE else Color.rgb(255, 70, 60)
            GUNNER -> if (f.flash > 0f) Color.WHITE else Color.rgb(230, 60, 110)
            else -> if (f.flash > 0f) Color.WHITE else Color.rgb(255, 150, 40)
        }
        val dark = when (f.type) {
            COMMANDER -> Color.rgb(150, 30, 30)
            GUNNER -> Color.rgb(130, 25, 65)
            else -> Color.rgb(160, 80, 10)
        }
        val path = Path()
        val pts: FloatArray = when (f.type) {
            COMMANDER -> floatArrayOf(0f, -1.15f, 0.55f, -0.2f, 1.0f, 0.5f, 0.35f, 0.35f, 0f, 0.8f, -0.35f, 0.35f, -1.0f, 0.5f, -0.55f, -0.2f)
            GUNNER -> floatArrayOf(0f, -1.0f, 0.8f, 0f, 0f, 1.0f, -0.8f, 0f)
            else -> floatArrayOf(0f, -1.1f, 0.9f, 0.7f, 0.3f, 0.5f, 0f, 0.85f, -0.3f, 0.5f, -0.9f, 0.7f)
        }
        var first = true
        var i = 0
        while (i < pts.size) {
            val px = x + pts[i] * size
            val py = y + pts[i + 1] * size
            if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
            i += 2
        }
        path.close()
        pFill.style = Paint.Style.FILL
        pFill.color = body
        canvas.drawPath(path, pFill)
        // flat-shading pass: darker underside
        canvas.save()
        canvas.clipPath(path)
        pFill.color = dark
        canvas.drawRect(x - size * 1.2f, y + size * 0.12f, x + size * 1.2f, y + size * 1.4f, pFill)
        canvas.restore()
        pLine.style = Paint.Style.STROKE
        pLine.strokeWidth = maxOf(1.5f, 3f * s)
        pLine.color = dark
        canvas.drawPath(path, pLine)
        pLine.strokeWidth = 0f
        pLine.style = Paint.Style.FILL
    }

    private fun drawRock(canvas: Canvas, r: Rock) {
        val s = sc(r.z)
        val x = sx(r.x, r.z)
        val y = sy(r.y, r.z)
        val size = 120f * s
        if (x < -size * 2 || x > screenW + size * 2 || y < -size * 2 || y > screenH + size * 2) return
        val path = Path()
        var first = true
        for (k in 0 until 7) {
            val ang = r.rot + k * 6.2832f / 7f
            val rad = r.shape[k] * size
            val px = x + cos(ang) * rad
            val py = y + sin(ang) * rad
            if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
        }
        path.close()
        pFill.style = Paint.Style.FILL
        pFill.color = Color.rgb(96, 92, 88)
        canvas.drawPath(path, pFill)
        canvas.save()
        canvas.clipPath(path)
        pFill.color = Color.rgb(60, 58, 56)
        canvas.drawRect(x - size, y + size * 0.15f, x + size, y + size, pFill)
        canvas.restore()
        pLine.style = Paint.Style.STROKE
        pLine.strokeWidth = maxOf(1.5f, 3f * s)
        pLine.color = Color.rgb(130, 126, 120)
        canvas.drawPath(path, pLine)
        pLine.strokeWidth = 0f
        pLine.style = Paint.Style.FILL
    }

    private fun drawBoss(canvas: Canvas) {
        val s = sc(bossZ)
        val x = sx(bossX, bossZ)
        val y = sy(bossY, bossZ)
        val size = 210f * s

        // beam telegraph / active beam
        if (bossCharge > 0f) {
            pLine.strokeWidth = 5f
            pLine.color = Color.argb(180, 255, 60, 40)
            canvas.drawLine(x, y, x, screenH.toFloat(), pLine)
            pLine.strokeWidth = 0f
        } else if (beamT > 0f) {
            val bx = sx(beamX, 900f)
            val bw = 150f * sc(900f)
            pFill.color = Color.argb(150, 255, 80, 40)
            canvas.drawRect(bx - bw, 0f, bx + bw, screenH.toFloat(), pFill)
            pFill.color = Color.argb(230, 255, 240, 200)
            canvas.drawRect(bx - bw * 0.25f, 0f, bx + bw * 0.25f, screenH.toFloat(), pFill)
        }

        // hull: stacked flat-shaded plates
        val body = if (bossFlash > 0f) Color.WHITE else Color.rgb(46, 110, 70)
        val dark = Color.rgb(24, 62, 40)
        val path = Path()
        path.moveTo(x - size * 1.15f, y - size * 0.25f)
        path.lineTo(x + size * 1.15f, y - size * 0.25f)
        path.lineTo(x + size * 0.55f, y + size * 0.55f)
        path.lineTo(x - size * 0.55f, y + size * 0.55f)
        path.close()
        pFill.style = Paint.Style.FILL
        pFill.color = body
        canvas.drawPath(path, pFill)
        canvas.save()
        canvas.clipPath(path)
        pFill.color = dark
        canvas.drawRect(x - size * 1.2f, y + size * 0.15f, x + size * 1.2f, y + size * 0.7f, pFill)
        canvas.restore()
        // side pods
        pFill.color = if (bossFlash > 0f) Color.WHITE else Color.rgb(70, 60, 110)
        canvas.drawRect(x - size * 1.55f, y - size * 0.05f, x - size * 1.05f, y + size * 0.45f, pFill)
        canvas.drawRect(x + size * 1.05f, y - size * 0.05f, x + size * 1.55f, y + size * 0.45f, pFill)
        // cannon core
        val pulse = 0.8f + 0.2f * sin(time * 9f)
        pFill.color = Color.rgb(255, 90, 50)
        canvas.drawCircle(x, y + size * 0.05f, size * 0.22f * pulse, pFill)
        pFill.color = Color.rgb(255, 220, 160)
        canvas.drawCircle(x, y + size * 0.05f, size * 0.11f * pulse, pFill)
        // barrel pointing down the tunnel
        pFill.color = if (bossFlash > 0f) Color.WHITE else dark
        canvas.drawRect(x - size * 0.12f, y + size * 0.5f, x + size * 0.12f, y + size * 1.0f, pFill)
        pLine.style = Paint.Style.STROKE
        pLine.strokeWidth = maxOf(1.5f, 3f * s)
        pLine.color = Color.rgb(120, 200, 140)
        canvas.drawPath(path, pLine)
        pLine.strokeWidth = 0f
        pLine.style = Paint.Style.FILL
    }

    private fun drawHull(canvas: Canvas) {
        val y0 = screenH * 0.94f
        val path = Path()
        path.moveTo(-screenW * 0.1f, screenH + 2f)
        path.lineTo(-screenW * 0.1f, y0 + screenH * 0.02f)
        path.lineTo(screenW * 0.18f, y0)
        path.lineTo(screenW * 0.82f, y0)
        path.lineTo(screenW * 1.1f, y0 + screenH * 0.02f)
        path.lineTo(screenW * 1.1f, screenH + 2f)
        path.close()
        pFill.color = Color.rgb(14, 20, 36)
        canvas.drawPath(path, pFill)
        pLine.strokeWidth = 4f
        pLine.color = Color.rgb(60, 140, 170)
        canvas.drawLine(screenW * 0.18f, y0, screenW * 0.82f, y0, pLine)
        pLine.strokeWidth = 0f
        // cockpit glow
        pFill.color = Color.argb(200, 90, 220, 255)
        canvas.drawCircle(screenW * 0.5f, y0 + screenH * 0.012f, screenW * 0.035f, pFill)
        // twin cannons at the wing edges, where the bolts come from
        pFill.color = Color.rgb(40, 52, 78)
        canvas.drawCircle(screenW * 0.16f, y0 + screenH * 0.014f, screenW * 0.022f, pFill)
        canvas.drawCircle(screenW * 0.84f, y0 + screenH * 0.014f, screenW * 0.022f, pFill)
        // engine flicker
        val fl = 0.7f + 0.3f * sin(time * 26f)
        pFill.color = Color.argb((160 * fl).toInt(), 255, 150, 60)
        canvas.drawCircle(screenW * 0.5f, screenH * 1.005f, screenW * 0.09f * fl, pFill)
    }

    private fun drawCursor(canvas: Canvas) {
        val x = cursor[0]
        val y = cursor[1]
        val r = 34f * (if (firing) 0.9f else 1f)
        pLine.style = Paint.Style.STROKE
        pLine.strokeWidth = 3.5f
        pLine.color = Color.rgb(255, 230, 90)
        canvas.drawCircle(x, y, r, pLine)
        canvas.drawLine(x - r * 1.5f, y, x - r * 0.6f, y, pLine)
        canvas.drawLine(x + r * 0.6f, y, x + r * 1.5f, y, pLine)
        canvas.drawLine(x, y - r * 1.5f, x, y - r * 0.6f, pLine)
        canvas.drawLine(x, y + r * 0.6f, x, y + r * 1.5f, pLine)
        pFill.color = Color.rgb(255, 70, 50)
        canvas.drawCircle(x, y, 5f, pFill)
        pLine.strokeWidth = 0f
        pLine.style = Paint.Style.FILL
    }

    private fun drawHud(canvas: Canvas) {
        pTxt.color = Color.WHITE
        pTxt.textSize = screenH * 0.026f
        canvas.drawText("SCORE $score", screenW * 0.04f, screenH * 0.045f, pTxt)
        canvas.drawText("HI $high", screenW * 0.04f, screenH * 0.08f, pTxt)
        if (!bossOn) canvas.drawText("SECTOR $sector", screenW * 0.96f, screenH * 0.045f, pTxt)

        if (bossOn) {
            pTxt.color = Color.rgb(255, 120, 60)
            pTxt.textAlign = Paint.Align.CENTER
            pTxt.textSize = screenH * 0.032f
            canvas.drawText("CANNON SEED  ${"%.1f".format(bossTimer)}", screenW / 2f, screenH * 0.05f, pTxt)
            pTxt.textAlign = Paint.Align.LEFT
        }

        // energy bar
        val bw = screenW * 0.6f
        val bx = (screenW - bw) / 2f
        val by = screenH * 0.895f
        pFill.color = Color.argb(120, 0, 0, 0)
        canvas.drawRect(bx, by, bx + bw, by + screenH * 0.022f, pFill)
        val frac = (energy / 100f).coerceIn(0f, 1f)
        pFill.color = when {
            frac > 0.5f -> Color.rgb(90, 230, 110)
            frac > 0.25f -> Color.rgb(255, 200, 60)
            else -> Color.rgb(255, 70, 50)
        }
        canvas.drawRect(bx, by, bx + bw * frac, by + screenH * 0.022f, pFill)
        pLine.style = Paint.Style.STROKE
        pLine.strokeWidth = 2f
        pLine.color = Color.rgb(160, 180, 220)
        canvas.drawRect(bx, by, bx + bw, by + screenH * 0.022f, pLine)
        pLine.strokeWidth = 0f
        pLine.style = Paint.Style.FILL
        pTxt.textSize = screenH * 0.022f
        pTxt.color = Color.rgb(160, 180, 220)
        canvas.drawText("ENERGY", bx, by - screenH * 0.008f, pTxt)
    }

    private fun drawIntro(canvas: Canvas) {
        pTxt.textAlign = Paint.Align.CENTER
        pTxt.color = Color.rgb(120, 230, 255)
        pTxt.textSize = screenH * 0.06f
        canvas.drawText("SECTOR $sector", screenW / 2f, screenH * 0.4f, pTxt)
        pTxt.color = Color.rgb(255, 120, 90)
        pTxt.textSize = screenH * 0.03f
        canvas.drawText("UIMS DETECTED", screenW / 2f, screenH * 0.46f, pTxt)
        pTxt.textAlign = Paint.Align.LEFT
    }

    private fun drawReady(canvas: Canvas) {
        pTxt.textAlign = Paint.Align.CENTER
        // title with a metallic fade, Galaxian-blue
        pTxt.textSize = screenH * 0.085f
        val lg = LinearGradient(0f, screenH * 0.26f, 0f, screenH * 0.36f,
            Color.rgb(120, 230, 255), Color.rgb(40, 90, 200), Shader.TileMode.CLAMP)
        pTxt.shader = lg
        canvas.drawText("GALAXIAN 3", screenW / 2f, screenH * 0.34f, pTxt)
        pTxt.shader = null
        pTxt.color = Color.rgb(255, 140, 80)
        pTxt.textSize = screenH * 0.036f
        canvas.drawText("PROJECT DRAGOON", screenW / 2f, screenH * 0.40f, pTxt)
        pTxt.color = Color.rgb(170, 190, 220)
        pTxt.textSize = screenH * 0.024f
        canvas.drawText("MAN THE GUNS OF THE STARSHIP DRAGOON", screenW / 2f, screenH * 0.47f, pTxt)
        canvas.drawText("DESTROY THE CANNON SEED BEFORE THE TIMER RUNS OUT", screenW / 2f, screenH * 0.51f, pTxt)
        pTxt.color = Color.rgb(255, 230, 90)
        pTxt.textSize = screenH * 0.032f
        canvas.drawText("TAP TO START", screenW / 2f, screenH * 0.60f, pTxt)
        pTxt.color = Color.WHITE
        pTxt.textSize = screenH * 0.026f
        canvas.drawText("HI $high", screenW / 2f, screenH * 0.66f, pTxt)
        pTxt.textAlign = Paint.Align.LEFT
    }

    private fun drawEnd(canvas: Canvas, won: Boolean) {
        pTxt.textAlign = Paint.Align.CENTER
        pTxt.color = if (won) Color.rgb(120, 255, 140) else Color.rgb(255, 80, 60)
        pTxt.textSize = screenH * 0.055f
        canvas.drawText(if (won) "MISSION COMPLETE" else "MISSION FAILED", screenW / 2f, screenH * 0.38f, pTxt)
        pTxt.color = Color.WHITE
        pTxt.textSize = screenH * 0.036f
        canvas.drawText("SCORE $score", screenW / 2f, screenH * 0.46f, pTxt)
        if (score >= high && score > 0) {
            pTxt.color = Color.rgb(255, 230, 90)
            canvas.drawText("NEW HIGH SCORE", screenW / 2f, screenH * 0.52f, pTxt)
        }
        pTxt.color = Color.rgb(255, 230, 90)
        pTxt.textSize = screenH * 0.03f
        canvas.drawText("TAP TO FLY AGAIN", screenW / 2f, screenH * 0.62f, pTxt)
        pTxt.textAlign = Paint.Align.LEFT
    }

    // ---- input ----
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                cursor[0] = e.x.coerceIn(0f, screenW)
                cursor[1] = e.y.coerceIn(0f, screenH)
                if (state == READY || state == OVER || state == WIN) {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        startGame()
                        return true
                    }
                }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) firing = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> firing = false
        }
        return true
    }
}
