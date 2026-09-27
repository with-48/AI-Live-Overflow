package com.yuchen.deskpet

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 128, 64, 64)
        }
        root.addView(TextView(this).apply {
            text = "小云朵\n\n按顺序来：\n1 悬浮窗权限\n2 通知权限\n3 使用情况访问（让它知道你在用什么App）\n4 启动它"
            textSize = 16f
        })
        root.addView(Button(this).apply {
            text = "1 悬浮窗权限"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            }
        })
        root.addView(Button(this).apply {
            text = "2 通知权限"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
            }
        })
        root.addView(Button(this).apply {
            text = "3 使用情况访问"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        })
        root.addView(Button(this).apply {
            text = "4 启动 / 唤醒"
            setOnClickListener { startForegroundServiceCompat() }
        })
        root.addView(Button(this).apply {
            text = "5 收起"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })
        setContentView(root)
    }

    private fun startForegroundServiceCompat() {
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }
}
