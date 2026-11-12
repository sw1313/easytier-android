@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.hfzy.easytier

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

private val Teal = Color(0xFF006A60)
private val Mint = Color(0xFFB8EBDD)
private val LightColors = lightColorScheme(
    primary = Teal, onPrimary = Color.White, primaryContainer = Color(0xFFC6F2E5),
    secondary = Color(0xFF4C635D), secondaryContainer = Color(0xFFD5E8DF), onSecondaryContainer = Color(0xFF143D32), surface = Color(0xFFF6F9F7),
    surfaceContainer = Color(0xFFEDF2EF), surfaceContainerLow = Color.White,
    background = Color(0xFFF6F9F7), onSurface = Color(0xFF17231F),
)

@Composable fun EasyTierApp(activity: MainActivity) {
    val prefs = remember { activity.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(prefs.getString("theme", "system")!!) }
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val colors = if (dark) darkColorScheme(primary = Mint, primaryContainer = Color(0xFF005047), secondary = Color(0xFFB0CCC1)) else LightColors
    MaterialTheme(colorScheme = colors) {
        val profiles by AppState.profiles.collectAsStateWithLifecycle()
        val selected by AppState.selected.collectAsStateWithLifecycle()
        val vpn by AppState.vpn.collectAsStateWithLifecycle()
        var editing by remember { mutableStateOf<Profile?>(null) }
        var editingSection by remember { mutableStateOf<String?>(null) }
        var exitProfile by remember { mutableStateOf<Profile?>(null) }
        var creating by remember { mutableStateOf(false) }
        var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
        var remote by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        if (exitProfile != null) ExitNodeDialog(activity, exitProfile!!, vpn) { exitProfile = null }
        LaunchedEffect(activity.notice) {
            if (activity.notice.isNotEmpty()) { snackbar.showSnackbar(activity.notice); activity.notice = "" }
        }
        BackHandler(editing != null || creating || detail != null) { editing = null; creating = false; detail = null }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (editing == null && !creating && detail == null && !remote) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("概览" to Icons.Outlined.Dashboard, "网络" to Icons.Outlined.Layers, "节点" to Icons.Outlined.Hub, "设置" to Icons.Outlined.Tune).forEachIndexed { index, pair ->
                        NavigationBarItem(selected = activity.page == index, onClick = { activity.page = index }, icon = { Icon(pair.second, null) }, label = { Text(pair.first) })
                    }
                }
            },
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                when {
                    remote -> RemoteScreen(activity) { remote = false }
                    editing != null || creating -> ConfigEditor(
                        editing, onClose = { editing = null; creating = false }, initialSection = editingSection,
                        onSave = { title, raw ->
                            runCatching { ProfileStore(activity).save(title, raw, editing?.id ?: java.util.UUID.randomUUID().toString()) }
                                .onSuccess { editing = null; creating = false; activity.notice = if (vpn.active) "配置已保存，重连后应用" else "配置已保存" }
                                .onFailure { activity.notice = it.message.orEmpty() }
                        },
                    )
                    detail != null -> DetailScreen(detail!!.first, detail!!.second) { detail = null }
                    else -> AnimatedContent(activity.page, label = "page") { page ->
                        when (page) {
                            0 -> HomeScreen(vpn, profiles.firstOrNull { it.id == vpn.profileId || (vpn.profileId.isEmpty() && it.id == selected) },
                                onConnect = { if (vpn.active) EasyTierVpnService.disconnect(activity) else activity.connect() },
                                onNetworks = { activity.page = 1 }, onDetails = { title, value -> detail = title to value },
                                onPortalSettings = { profile -> editingSection = "代理与服务"; editing = profile },
                                onExit = { exitProfile = it })
                            1 -> ProfilesScreen(profiles, selected, vpn,
                                onNew = { editingSection = null; creating = true }, onImport = activity::pickConfig,
                                onSelect = { ProfileStore(activity).select(it.id) }, onEdit = { editingSection = null; editing = it },
                                onConnect = { if (vpn.active && vpn.profileId == it.id) EasyTierVpnService.disconnect(activity) else activity.connect(it.id) },
                                onExport = activity::export,
                                onDuplicate = { profile ->
                                    val doc = NativeCore.request("decode", profile.raw)
                                    doc.remove("instance_id")
                                    ProfileStore(activity).save(profile.title + " 副本", NativeCore.request("encode", doc.toString()).getString("toml"))
                                },
                                onDelete = { runCatching { ProfileStore(activity).delete(it.id) }.onFailure { activity.notice = it.message.orEmpty() } },
                            )
                            2 -> NodesScreen(vpn) { title, text -> detail = title to text }
                            else -> SettingsScreen(activity, theme, onTheme = {
                                theme = it; prefs.edit().putString("theme", it).apply()
                            }, onDetails = { title, value -> detail = title to value }, onRemote = { remote = true })
                        }
                    }
                }
                if (activity.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

@Composable private fun PageHeading(title: String, subtitle: String = "", action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        }
        action()
    }
}

@Composable internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun HomeScreen(vpn: VpnState, profile: Profile?, onConnect: () -> Unit, onNetworks: () -> Unit, onDetails: (String, String) -> Unit, onPortalSettings: (Profile) -> Unit, onExit: (Profile) -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(vpn.since) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    val nodes = vpn.snapshot.array("nodes").objects()
    val document = remember(profile?.raw) { profile?.let { runCatching { NativeCore.request("decode", it.raw) }.getOrNull() } }
    val exitIp = document?.array("exit_nodes")?.strings()?.firstOrNull()
    val portalConfig = vpn.snapshot.optJSONObject("details")?.optJSONObject("my_node_info")?.text("vpn_portal_cfg").orEmpty()
    val portalReady = usablePortalConfig(portalConfig)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("EasyTier", "你的设备，随时互联") { IconButton(onClick = onNetworks) { Icon(Icons.Outlined.SwapHoriz, "切换网络") } } }
        item {
            Surface(shape = RoundedCornerShape(30.dp), color = Color(0xFF073E37), contentColor = Color.White) {
                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF073E37), Color(0xFF146C5D)))).padding(26.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(9.dp).background(if (vpn.phase == "connected") Mint else Color(0xFF99B9AD), CircleShape))
                        Text(when (vpn.phase) { "connected" -> "已连接"; "connecting" -> "正在连接"; "error" -> "连接异常"; else -> "尚未连接" }, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Outlined.Shield, null, tint = Mint)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(profile?.title ?: "添加你的第一个网络", fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                        Text(vpn.ip.substringBefore('/').ifBlank { profile?.meta?.text("network") ?: "导入配置，连接家中设备" }, color = Color(0xFFD2EAE0), fontSize = 15.sp)
                    }
                    FilledTonalButton(onClick = onConnect, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = Mint, contentColor = Color(0xFF053C32))) {
                        Icon(if (vpn.active) Icons.Outlined.PowerSettingsNew else Icons.Outlined.Link, null)
                        Spacer(Modifier.width(10.dp))
                        Text(if (vpn.active) "断开连接" else "连接网络", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (vpn.active) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${nodes.size} 个在线节点", fontSize = 13.sp, color = Mint)
                        val elapsed = ((now - vpn.since).coerceAtLeast(0) / 1000)
                        Text("%02d:%02d:%02d".format(elapsed / 3600, elapsed / 60 % 60, elapsed % 60), fontSize = 13.sp, color = Mint)
                    }
                }
            }
        }
        if (vpn.error.isNotEmpty()) item { Text(vpn.error, color = MaterialTheme.colorScheme.error) }
        if (profile != null) item {
            Panel {
                SettingsAction(Icons.Outlined.Public, "出口节点", if (exitIp == null) "组网访问 · 点击选择互联网出口" else "互联网通过 ${nodes.firstOrNull { it.text("ip").substringBefore('/') == exitIp }?.text("name")?.ifBlank { exitIp } ?: exitIp}", { onExit(profile) })
            }
        }
        item {
            Panel {
                Text("实时流量", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth()) {
                    Metric("↓ 下载", bytes(vpn.rxRate) + "/s", Modifier.weight(1f))
                    Metric("↑ 上传", bytes(vpn.txRate) + "/s", Modifier.weight(1f))
                }
                TrafficChart(vpn.history)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("累计接收 ${bytes(vpn.snapshot.optDouble("rx", 0.0))}", style = MaterialTheme.typography.bodySmall)
                    Text("累计发送 ${bytes(vpn.snapshot.optDouble("tx", 0.0))}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Panel {
                Text("网络状态", style = MaterialTheme.typography.titleMedium)
                DataRow("虚拟地址", vpn.ip.ifBlank { "—" })
                DataRow("直连节点", nodes.count { it.optBoolean("direct") }.toString())
                DataRow("中转节点", nodes.count { !it.optBoolean("direct") }.toString())
                DataRow("子网路由", vpn.snapshot.array("routes").strings().distinct().joinToString("\n").ifBlank { "—" })
                TextButton(onClick = { onDetails("本机节点详情", vpn.snapshot.optJSONObject("details")?.optJSONObject("my_node_info")?.toString(2) ?: "连接后显示") }) { Text("查看本机详情"); Icon(Icons.Outlined.ChevronRight, null) }
                val portalClients = vpn.snapshot.array("portal_clients").objects().filter { usablePortalConfig(it.text("config")) }
                if (portalClients.isNotEmpty()) {
                    portalClients.forEach { client ->
                        TextButton(onClick = { onDetails("WireGuard · ${client.text("name")}", client.text("config")) }) { Text("门户客户端 ${client.text("name")}"); Icon(Icons.Outlined.ChevronRight, null) }
                    }
                    if (profile != null) TextButton(onClick = { onPortalSettings(profile) }) { Text("配置 WireGuard 门户"); Icon(Icons.Outlined.ChevronRight, null) }
                } else if (portalReady) {
                    TextButton(onClick = { onDetails("WireGuard 门户配置", portalConfig) }) { Text("查看 WireGuard 门户配置"); Icon(Icons.Outlined.ChevronRight, null) }
                } else {
                    Text(if (document?.has("vpn_portal_config") == true) "WireGuard 门户尚未启动，保存配置后请重连并检查监听端口。" else "WireGuard 门户未启用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (profile != null) TextButton(onClick = { onPortalSettings(profile) }) { Text("配置 WireGuard 门户"); Icon(Icons.Outlined.ChevronRight, null) }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun TrafficChart(history: List<Pair<Float, Float>>) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = Color(0xFF83B6EE)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        repeat(3) { line -> val y = size.height * (line + 1) / 4; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
        val maximum = history.maxOfOrNull { maxOf(it.first, it.second) }?.coerceAtLeast(1024f) ?: 1024f
        listOf(primary, secondary).forEachIndexed { stream, color ->
            val path = Path()
            val points = if (history.isEmpty()) List(2) { 0f to 0f } else history
            points.forEachIndexed { index, pair ->
                val x = size.width * index / (points.size - 1).coerceAtLeast(1)
                val y = size.height - 8f - (if (stream == 0) pair.first else pair.second) / maximum * (size.height - 16f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable private fun ProfilesScreen(profiles: List<Profile>, selected: String, vpn: VpnState,
    onNew: () -> Unit, onImport: () -> Unit, onSelect: (Profile) -> Unit, onEdit: (Profile) -> Unit,
    onConnect: (Profile) -> Unit, onExport: (Profile) -> Unit, onDuplicate: (Profile) -> Unit, onDelete: (Profile) -> Unit,
) {
    var delete by remember { mutableStateOf<Profile?>(null) }
    if (delete != null) AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除网络配置？") }, text = { Text(delete!!.title) },
        confirmButton = { TextButton(onClick = { onDelete(delete!!); delete = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } })
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("网络", "保存配置，一键切换") { IconButton(onClick = onImport) { Icon(Icons.Outlined.FileDownload, "导入 TOML") }; FilledIconButton(onClick = onNew) { Icon(Icons.Outlined.Add, "新建网络") } } }
        if (profiles.isEmpty()) item {
            Panel {
                Icon(Icons.Outlined.Lan, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text("连接你的 EasyTier 网络", style = MaterialTheme.typography.titleLarge)
                Text("可导入已有 TOML 配置，也可以填写网络名称、密钥和初始节点。")
                Button(onClick = onImport) { Text("导入配置") }
                TextButton(onClick = onNew) { Text("手动创建") }
            }
        }
        items(profiles, key = { it.id }) { profile ->
            var menu by remember { mutableStateOf(false) }
            val active = vpn.active && vpn.profileId == profile.id
            Surface(shape = RoundedCornerShape(24.dp), color = if (selected == profile.id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f) else MaterialTheme.colorScheme.surfaceContainerLow,
                border = if (selected == profile.id) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .35f)) else null,
                modifier = Modifier.fillMaxWidth().clickable { onSelect(profile) }) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lan, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(profile.meta.text("network"), style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "配置操作") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("编辑配置") }, onClick = { menu = false; onEdit(profile) })
                                DropdownMenuItem(text = { Text("导出 TOML（包含密钥）") }, onClick = { menu = false; onExport(profile) })
                                DropdownMenuItem(text = { Text("复制配置") }, onClick = { menu = false; onDuplicate(profile) })
                                DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; delete = profile })
                            }
                        }
                    }
                    DataRow("虚拟地址", profile.meta.text("ipv4").ifBlank { "自动分配" })
                    DataRow("初始节点", "${profile.meta.optInt("peers")} 个")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onConnect(profile) }, modifier = Modifier.weight(1f)) { Text(if (active) "断开" else "连接") }
                        OutlinedButton(onClick = { onEdit(profile) }) { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("编辑") }
                    }
                }
            }
        }
    }
}

@Composable private fun NodesScreen(vpn: VpnState, onDetails: (String, String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    val nodes = vpn.snapshot.array("nodes").objects().filter { it.text("name").contains(filter, true) || it.text("ip").contains(filter) }
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("节点", "${nodes.size} 个可达节点") }
        item { OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索名称或地址") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(20.dp)) }
        if (nodes.isEmpty()) item { Panel { Icon(Icons.Outlined.Hub, null, Modifier.size(36.dp)); Text(if (vpn.active) "正在发现网络中的节点…" else "连接网络后显示节点") } }
        items(nodes, key = { it.optLong("id") }) { node ->
            Surface(onClick = { onDetails(node.text("name").ifBlank { "节点详情" }, node.toString(2)) }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (node.optBoolean("direct")) Icons.Outlined.Link else Icons.Outlined.AltRoute, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(node.text("name").ifBlank { "未命名节点" }, fontWeight = FontWeight.SemiBold); Text(node.text("ip"), style = MaterialTheme.typography.bodySmall) }
                        Text("%.1f ms".format(Locale.ROOT, node.optDouble("latency")), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuggestionChip(onClick = {}, label = { Text(if (node.optBoolean("direct")) "P2P 直连" else "中转 · ${node.optInt("cost")} 跳") })
                        if (!node.isNull("loss")) SuggestionChip(onClick = {}, label = { Text("丢包 %.1f%%".format(node.optDouble("loss"))) })
                    }
                    val networks = node.array("networks").strings()
                    if (networks.isNotEmpty()) Text(networks.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Text("EasyTier ${node.text("version")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(activity: MainActivity, theme: String, onTheme: (String) -> Unit, onDetails: (String, String) -> Unit, onRemote: () -> Unit) {
    val logs by AppState.logs.collectAsStateWithLifecycle()
    val settings = remember { activity.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var cloudUrl by rememberSaveable { mutableStateOf(settings.getString("cloudUrl", "")!!) }
    var cloudEnabled by remember { mutableStateOf(settings.getBoolean("cloudEnabled", false)) }
    var cloudSecure by remember { mutableStateOf(settings.getBoolean("cloudSecure", true)) }
    val cloudStatus by AppState.cloud.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("设置") }
        item {
            Panel {
                Text("连接与快捷操作", style = MaterialTheme.typography.titleMedium)
                SettingsAction(Icons.Outlined.Widgets, "添加快捷磁贴", "将 EasyTier 添加到通知栏快捷设置", activity::addTile)
                SettingsAction(Icons.Outlined.BatteryChargingFull, "后台运行设置", "小米手机请允许自启动与后台运行", activity::batterySettings)
                SettingsAction(Icons.Outlined.VpnKey, "系统 VPN 设置", "可启用始终开启的 VPN", activity::vpnSettings)
            }
        }
        item { CoreUpdateCard(activity) }
        item { Panel { SettingsAction(Icons.Outlined.Dns, "远程管理", "连接核心 RPC，管理远端网络与配置", onRemote) } }
        item {
            Panel {
                Text("外观", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEachIndexed { index, pair ->
                        SegmentedButton(selected = theme == pair.first, onClick = { onTheme(pair.first) }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(pair.second) }
                    }
                }
            }
        }
        item {
            Panel {
                Text("配置服务器", style = MaterialTheme.typography.titleMedium)
                DataRow("连接状态", cloudStatus)
                OutlinedTextField(cloudUrl, { cloudUrl = it }, label = { Text("配置服务器 URL / Token") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                Row(verticalAlignment = Alignment.CenterVertically) { Text("连接网络时自动接入", Modifier.weight(1f)); Switch(cloudEnabled, { cloudEnabled = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("安全握手", Modifier.weight(1f)); Switch(cloudSecure, { cloudSecure = it }) }
                Text("服务器下发的配置会出现在网络列表中，首次接入自动使用第一个网络。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    settings.edit().putString("cloudUrl", cloudUrl).putBoolean("cloudEnabled", cloudEnabled).putBoolean("cloudSecure", cloudSecure).apply()
                    if (AppState.vpn.value.active) activity.notice = "已保存，重连后应用配置服务器设置" else activity.connect("cloud")
                }, enabled = cloudUrl.isNotBlank()) { Text("保存并连接") }
            }
        }
        item {
            Panel {
                SettingsAction(Icons.Outlined.ReceiptLong, "事件日志", "${logs.size} 条记录", { onDetails("事件日志", logs.joinToString("\n\n").ifBlank { "暂无事件" }) })
                SettingsAction(Icons.Outlined.DataObject, "连接与路由详情", "当前内核运行状态", { onDetails("内核详情", AppState.vpn.value.snapshot.optJSONObject("details")?.toString(2) ?: "连接后显示") })
            }
        }
        item { Text("EasyTier MD ${BuildConfig.VERSION_NAME} · LGPL-3.0\n原生 Android VPN · Jetpack Compose Material 3", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
    }
}

@Composable private fun SettingsAction(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Outlined.ChevronRight, null)
    }
}

@Composable fun DataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(.8f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun DetailScreen(title: String, text: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, "返回") } }, actions = {
            IconButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, text)) }) { Icon(Icons.Outlined.ContentCopy, "复制") }
        })
        SelectionContainerCompat(text)
    }
}

@Composable private fun SelectionContainerCompat(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

fun bytes(value: Double): String {
    val safe = if (value.isFinite()) value.coerceAtLeast(0.0) else 0.0
    return when {
        safe >= 1024 * 1024 * 1024 -> "%.1f GB".format(safe / 1024 / 1024 / 1024)
        safe >= 1024 * 1024 -> "%.1f MB".format(safe / 1024 / 1024)
        safe >= 1024 -> "%.1f KB".format(safe / 1024)
        else -> "%.0f B".format(safe)
    }
}

fun usablePortalConfig(value: String): Boolean = value.isNotBlank() && !value.trimStart().startsWith("ERROR", ignoreCase = true)
