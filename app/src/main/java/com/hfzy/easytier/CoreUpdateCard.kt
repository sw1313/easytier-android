package com.hfzy.easytier

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun CoreUpdateCard(activity: MainActivity) {
    val state = activity.coreUpdate
    val selected = remember(activity.coreRevision, state.phase) { CoreUpdates.version(activity) }
    val currentCore = CoreCatalog.coreVersion(NativeCore.loadedVersion)
    val targetCore = if (state.phase == UpdatePhase.READY) CoreCatalog.coreVersion(selected) else state.release?.coreVersion
    val adapterOnly = targetCore == currentCore
    var menu by remember { mutableStateOf(false) }
    var customUrl by remember { mutableStateOf<String?>(null) }
    var restart by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(false) }
    if (restart) AlertDialog(onDismissRequest = { restart = false }, title = { Text("应用核心更新") }, text = {
        Text("应用将重新启动，加载 ${CoreCatalog.displayVersion(selected)}。" + if (AppState.vpn.value.active) "随后恢复当前网络连接和出口节点设置。" else "")
    }, confirmButton = { TextButton(onClick = { restart = false; activity.restartCore() }) { Text("立即应用") } },
        dismissButton = { TextButton(onClick = { restart = false }) { Text("稍后") } })
    if (customUrl != null) AlertDialog(onDismissRequest = { customUrl = null }, title = { Text("从 URL 导入") }, text = {
        OutlinedTextField(customUrl!!, { customUrl = it }, label = { Text("HTTPS 核心包地址") }, singleLine = true)
    }, confirmButton = { TextButton(enabled = customUrl!!.startsWith("https://"), onClick = { activity.downloadCore(customUrl!!); customUrl = null }) { Text("下载") } },
        dismissButton = { TextButton(onClick = { customUrl = null }) { Text("取消") } })
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Memory, null, tint = MaterialTheme.colorScheme.primary)
            Text("核心更新", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
            Box {
                IconButton(onClick = { menu = true }, enabled = !activity.busy) { Icon(Icons.Outlined.MoreVert, "更多更新操作") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("本地导入") }, onClick = { menu = false; activity.pickCore() })
                    DropdownMenuItem(text = { Text("从 URL 导入") }, onClick = { menu = false; customUrl = "" })
                    DropdownMenuItem(text = { Text("恢复内置核心") }, onClick = {
                        menu = false
                        CoreUpdates.rollback(activity)
                        activity.coreRevision++
                        activity.coreUpdate = CoreUpdateState(phase = if (NativeCore.loadedVersion == CoreUpdates.BUNDLED_VERSION) UpdatePhase.CURRENT else UpdatePhase.READY)
                        activity.notice = "已选择内置 ${CoreCatalog.displayVersion(CoreUpdates.BUNDLED_VERSION)}"
                    })
                }
            }
        }
        DataRow("当前核心", currentCore)
        if (state.upstream.isNotBlank()) DataRow("官方稳定版", state.upstream)
        DataRow("Android 适配构建", CoreCatalog.adapterBuild(NativeCore.loadedVersion))
        if (selected != NativeCore.loadedVersion) DataRow("等待应用", CoreCatalog.displayVersion(selected))
        if (state.release != null && !adapterOnly) DataRow("可用核心", state.release.coreVersion)
        val stableStatus = when {
            state.upstreamPending -> "官方 ${state.upstream} 已发布，Android 兼容包准备中。"
            state.upstream == currentCore -> "已是最新官方稳定版"
            state.release == null && state.upstream.isBlank() -> "点击检查官方稳定版更新"
            state.upstream.isBlank() -> "已检查兼容更新源，官方版本查询暂不可用。"
            else -> "官方稳定版为 ${state.upstream}"
        }
        val message = when (state.phase) {
            UpdatePhase.IDLE -> "启动时自动检查，也可手动检查稳定版更新。"
            UpdatePhase.CHECKING -> "正在检查稳定版和兼容更新源…"
            UpdatePhase.CURRENT -> state.message.ifBlank { stableStatus }
            UpdatePhase.AVAILABLE -> if (adapterOnly) stableStatus else "发现核心更新 ${state.release?.coreVersion}"
            UpdatePhase.DOWNLOADING -> if (adapterOnly) "正在下载并校验适配组件…" else "正在下载并校验核心…"
            UpdatePhase.READY -> if (adapterOnly) "Android 适配组件已就绪，重新启动后生效" else "核心更新已就绪，重新启动后生效"
            UpdatePhase.ERROR -> state.message
        }
        Text(message, color = if (state.phase == UpdatePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (adapterOnly && state.phase == UpdatePhase.AVAILABLE) Text("Android 适配组件可更新至构建 ${CoreCatalog.adapterBuild(state.release!!.version)}", style = MaterialTheme.typography.bodySmall)
        if (state.phase == UpdatePhase.DOWNLOADING) {
            LinearProgressIndicator(progress = { if (state.total > 0) (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
            Text("${bytes(state.downloaded.toDouble())} / ${bytes(state.total.toDouble())}", style = MaterialTheme.typography.bodySmall)
        }
        state.release?.let { release ->
            TextButton(onClick = { notes = !notes }) { Text(if (notes) "收起更新说明" else if (adapterOnly) "适配组件更新说明" else "核心更新说明") }
            if (notes) Text(release.notes.take(6000), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.phase == UpdatePhase.AVAILABLE) Button(onClick = activity::downloadCoreUpdate, enabled = !activity.busy) { Text(if (adapterOnly) "更新适配组件" else "下载核心更新") }
            if (state.phase == UpdatePhase.READY) Button(onClick = { restart = true }, enabled = !activity.busy) { Text(if (AppState.vpn.value.active) "应用并重连" else "应用并重启") }
            OutlinedButton(onClick = activity::checkCoreUpdate, enabled = !activity.busy) { Text(if (state.phase == UpdatePhase.CHECKING) "正在检查" else "检查更新") }
        }
        val checked = CoreUpdates.lastChecked(activity)
        if (checked > 0) Text("上次检查 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(checked))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
