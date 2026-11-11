package com.hfzy.easytier

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class EasyTierTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val state = AppState.vpn.value
        qsTile?.apply {
            if (Build.VERSION.SDK_INT >= 30) stateDescription = when (state.phase) {
                "connected" -> "已连接"
                "connecting" -> "连接中"
                else -> "已断开"
            }
            this.state = if (AppState.vpn.value.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = when (AppState.vpn.value.phase) {
                "connected" -> AppState.vpn.value.ip.substringBefore('/')
                "connecting" -> "连接中…"
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
