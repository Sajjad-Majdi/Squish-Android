package com.artin.pinch

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.artin.pinch.engine.Codecs
import com.artin.pinch.engine.Work
import com.artin.pinch.ui.Nav
import com.artin.pinch.ui.PinchRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fully transparent bars from the first frame; the theme picks icon colours later.
        val clear = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(clear, clear)
        window.isNavigationBarContrastEnforced = false
        if (savedInstanceState == null) handle(intent)
        setContent { PinchRoot() }
        // The codec list is slow to read the first time; do it before the dial needs it.
        Thread { Codecs.candidates(true); Codecs.candidates(false) }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    @Suppress("DEPRECATION")
    private fun handle(i: Intent?) {
        i ?: return
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                val u = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra(Intent.EXTRA_STREAM)
                u?.let { uris += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val l = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                l?.let { uris += it }
            }
            else -> return
        }
        if (uris.isEmpty()) i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { uris += it } }
        if (uris.isEmpty()) return
        Nav.route = null
        Nav.tab = 0
        Work.addUris(this, uris)
    }
}
