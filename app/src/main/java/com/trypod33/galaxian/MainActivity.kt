package com.trypod33.galaxian

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val mode = intent.getIntExtra("mode", 0)
        try {
            setContentView(if (mode == 1) Galaxian3View(this) else GameView(this))
        } catch (t: Throwable) {
            val tv = TextView(this)
            tv.setTextColor(Color.WHITE)
            tv.textSize = 14f
            tv.gravity = Gravity.START
            tv.setPadding(24, 48, 24, 24)
            tv.text = "Galaxian failed to start\n" + t.stackTraceToString()
            setContentView(tv)
        }
    }
}
