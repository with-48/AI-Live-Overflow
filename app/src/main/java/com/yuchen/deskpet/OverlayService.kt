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

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("我在。"))
        mainHandler.post { setupOverlay() }
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
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}
