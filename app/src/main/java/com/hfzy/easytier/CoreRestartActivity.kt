package com.hfzy.easytier

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.widget.TextView

/** A separate process keeps a foreground activity alive while replacing the JNI process. */
class CoreRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "正在切换核心…"
            textSize = 18f
            gravity = Gravity.CENTER
        })
        val oldPid = intent.getIntExtra("oldPid", -1)
        val resume = intent.getStringExtra("profile").orEmpty()
        val expected = intent.getStringExtra("expectedCore")
        if (oldPid > 0 && oldPid != Process.myPid()) Process.killProcess(oldPid)
        Handler(Looper.getMainLooper()).postDelayed({
            val launch = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("expectedCore", expected)
            if (resume.isNotBlank()) launch.setAction(EasyTierVpnService.CONNECT).putExtra("profile", resume)
            startActivity(launch)
            finish()
        }, 400)
    }
}
