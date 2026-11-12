package com.trypod33.galaxian

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

// Splash / game-select screen: pick the original Galaxian or Galaxian 3.
class SplashActivity : Activity() {

    private fun pick(n: Int) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("mode", n))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)
        root.gravity = Gravity.CENTER_HORIZONTAL

        val title = TextView(this)
        title.text = "GALAXIAN"
        title.setTextColor(Color.rgb(120, 230, 255))
        title.textSize = 40f
        title.typeface = Typeface.DEFAULT_BOLD
        title.letterSpacing = 0.15f
        val tp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        tp.topMargin = px(90)
        title.layoutParams = tp
        root.addView(title)

        val sub = TextView(this)
        sub.text = "ARCADE COLLECTION"
        sub.setTextColor(Color.rgb(150, 165, 200))
        sub.textSize = 13f
        sub.letterSpacing = 0.35f
        val sp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        sp.topMargin = px(6)
        sub.layoutParams = sp
        root.addView(sub)

        root.addView(Space(this), LinearLayout.LayoutParams(1, px(70)))

        fun gameButton(label: String, caption: String, mode: Int): View {
            val b = Button(this)
            b.isAllCaps = false
            b.textSize = 22f
            b.typeface = Typeface.DEFAULT_BOLD
            b.setTextColor(Color.WHITE)
            b.text = label
            b.setPadding(px(24), px(18), px(24), px(18))
            val bg = GradientDrawable()
            bg.cornerRadius = px(14).toFloat()
            bg.setColor(Color.rgb(16, 26, 52))
            bg.setStroke(px(2), Color.rgb(70, 150, 220))
            b.background = bg
            b.setOnClickListener { pick(mode) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = px(36); lp.rightMargin = px(36); lp.topMargin = px(6)
            b.layoutParams = lp
            val holder = LinearLayout(this)
            holder.orientation = LinearLayout.VERTICAL
            holder.addView(b)
            val cap = TextView(this)
            cap.text = caption
            cap.setTextColor(Color.rgb(140, 155, 190))
            cap.textSize = 12f
            cap.gravity = Gravity.CENTER
            val cp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            cp.topMargin = px(2)
            cap.layoutParams = cp
            holder.addView(cap)
            val hp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            hp.leftMargin = px(36); hp.rightMargin = px(36); hp.topMargin = px(22)
            holder.layoutParams = hp
            return holder
        }

        root.addView(gameButton("GALAXIAN", "The 1979 arcade classic", 0))
        root.addView(gameButton("GALAXIAN 3", "Project Dragoon, the 1990 rail shooter", 1))
        root.addView(gameButton("GALAGA", "The 1981 sequel: tractor beams, dual fighters", 2))

        val ver = TextView(this)
        ver.text = "v0.3.0"
        ver.setTextColor(Color.rgb(90, 100, 130))
        ver.textSize = 11f
        ver.gravity = Gravity.CENTER
        ver.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = px(60)
        }
        root.addView(ver)

        setContentView(root)
    }
}
