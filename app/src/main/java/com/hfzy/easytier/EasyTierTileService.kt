package com.hfzy.easytier

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class EasyTierTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        // Active tiles can stop listening while their service remains bound.
        // Keep observing until destruction so the cached tile also receives changes.
        stateJob = scope.launch {
            AppState.vpn.distinctUntilChangedBy { it.phase to it.ip }.collect { updateTile(it) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateTile(vpn: VpnState) {
        qsTile?.apply {
            if (Build.VERSION.SDK_INT >= 30) stateDescription = when (vpn.phase) {
                "connected" -> "已连接"
                "connecting" -> "连接中"
                "error" -> "连接失败"
                else -> "已断开"
            }
            state = if (vpn.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = when (vpn.phase) {
                "connected" -> vpn.ip.substringBefore('/')
                "connecting" -> "连接中…"
                "error" -> "连接失败"
                else -> "点击连接"
            }
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }
    private fun toggle() {
        if (AppState.vpn.value.active) {
            EasyTierVpnService.disconnect(this)
        } else {
            ProfileStore(this).load()
            if (VpnService.prepare(this) == null && AppState.selected.value.isNotBlank()) {
                EasyTierVpnService.connect(this)
            } else {
                val intent = Intent(this, MainActivity::class.java).setAction(EasyTierVpnService.CONNECT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            }
        }
    }
}
