package com.yuchen.deskpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSig = ""

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false

    companion object {
        private const val CHANNEL_ID = "deskpet_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val PET_W_DP = 180
        private const val PET_H_DP = 220
        private const val POLL_MS = 5000L
        const val SUPA_URL = "https://dovlvwfmmqdjfampfmjv.supabase.co"
        const val SUPA_ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRvdmx2d2ZtbXFkamZhbXBmbWp2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTA0OTQzNDQsImV4cCI6MjEwNjA3MDM0NH0.cFW0IGNqSpedR5PbQyobgEiJOKVny5lTOZENDLLDfOI"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("我在。"))
        mainHandler.post { setupOverlay() }
        mainHandler.postDelayed(pollRunnable, 1500)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            Thread { fetchState() }.start()
            mainHandler.postDelayed(this, POLL_MS)
        }
    }

    private fun fetchState() {
        try {
            val conn = URL(SUPA_URL + "/rest/v1/pet_state?select=*&id=eq.1").openConnection() as HttpURLConnection
            conn.setRequestProperty("apikey", SUPA_ANON)
            conn.setRequestProperty("Authorization", "Bearer " + SUPA_ANON)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val arr = JSONArray(body)
            if (arr.length() > 0) {
                val o = arr.getJSONObject(0)
                val mood = o.optString("mood", "normal")
                val bubble = o.optString("bubble", "")
                val style = o.optString("bubble_style", "normal")
                val sig = mood + "|" + bubble + "|" + style
                if (sig != lastSig) {
                    lastSig = sig
                    mainHandler.post {
                        eval("window.petEngine && window.petEngine.setMood && window.petEngine.setMood(" + JSONObject.quote(mood) + ")")
                        eval("window.petEngine && window.petEngine.setBubble && window.petEngine.setBubble(" + JSONObject.quote(bubble) + ", " + JSONObject.quote(style) + ")")
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun postEvent(kind: String, payload: String) {
        try {
            val conn = URL(SUPA_URL + "/rest/v1/pet_events").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("apikey", SUPA_ANON)
            conn.setRequestProperty("Authorization", "Bearer " + SUPA_ANON)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val json = JSONObject()
            json.put("kind", kind)
            json.put("payload", JSONObject().put("v", payload))
            conn.outputStream.use { it.write(json.toString().toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
        }
    }

    private fun setupOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val p = WindowManager.LayoutParams(
            dp(PET_W_DP),
            dp(PET_H_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 400
        }
        params = p
        val web = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = WebViewClient()
            addJavascriptInterface(PetBridge(), "petHost")
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(touchListener)
        }
        overlayView = web
        windowManager?.addView(web, p)
    }

    inner class PetBridge {
        @JavascriptInterface
        fun log(msg: String) {
            println("[pet] " + msg)
        }

        @JavascriptInterface
        fun report(kind: String, payload: String) {
            Thread { postEvent(kind, payload) }.start()
        }

        @JavascriptInterface
        fun resize(w: Int, h: Int) {
            mainHandler.post {
                val lp = params ?: return@post
                lp.width = dp(w)
                lp.height = dp(h)
                overlayView?.let { v -> windowManager?.updateViewLayout(v, lp) }
            }
        }
    }

    private val touchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params?.x ?: 0
                initialY = params?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                touchStartTime = System.currentTimeMillis()
                hasMoved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                    hasMoved = true
                    val lp = params
                    if (lp != null) {
                        lp.x = initialX + dx
                        lp.y = initialY + dy
                        overlayView?.let { v -> windowManager?.updateViewLayout(v, lp) }
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = System.currentTimeMillis() - touchStartTime
                if (!hasMoved) {
                    val now = System.currentTimeMillis()
                    when {
                        elapsed > 600L -> js("onLongPress")
                        now - lastTapTime < 320L -> js("onDoubleTap")
                        else -> js("onTap")
                    }
                    lastTapTime = now
                }
                true
            }
            else -> false
        }
    }

    private fun js(fn: String) {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine." + fn + " && window.petEngine." + fn + "();",
            null
        )
    }

    private fun eval(code: String) {
        overlayView?.evaluateJavascript(code, null)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("小云朵")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "小云朵", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        mainHandler.removeCallbacks(pollRunnable)
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}
