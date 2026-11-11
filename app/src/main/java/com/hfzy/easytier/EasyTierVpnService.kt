package com.hfzy.easytier

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.net.InetAddress

class EasyTierVpnService : VpnService() {
    companion object {
        const val CONNECT = "com.hfzy.easytier.CONNECT"
        const val DISCONNECT = "com.hfzy.easytier.DISCONNECT"
        fun connect(context: Context, id: String = AppState.selected.value, restart: Boolean = false) {
            context.startForegroundService(Intent(context, EasyTierVpnService::class.java).setAction(CONNECT).putExtra("profile", id).putExtra("restart", restart))
        }
        fun disconnect(context: Context) {
            context.startService(Intent(context, EasyTierVpnService::class.java).setAction(DISCONNECT))
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<String>(Channel.UNLIMITED)
    private var tun: ParcelFileDescriptor? = null
    private var routeKey = ""
    private var current: Profile? = null
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastPoll = 0L
    private var startedAt = 0L
    private var lastCoreEvents = emptySet<String>()
    private var cloudOn = false
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("vpn", "VPN 连接", NotificationManager.IMPORTANCE_LOW))
        NativeCore.load(this)
        scope.launch {
            while (isActive) {
                val command = withTimeoutOrNull(1000) { commands.receive() }
                try {
                    if (command != null) {
                        stopCore()
                        if (command.isEmpty()) {
                            prefs.edit().putBoolean("wanted", false).apply()
                            AppState.publish(this@EasyTierVpnService, VpnState())
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            continue
                        }
                        startCore(command)
                    }
                    if (cloudOn) syncCloud()
                    if (current != null) poll()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    runCatching { stopCore() }
                    val message = error.message ?: "VPN 启动失败"
                    AppState.log(message)
                    AppState.publish(this@EasyTierVpnService, VpnState(phase = "error", error = message))
                    prefs.edit().putBoolean("wanted", false).apply()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    continue
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISCONNECT) {
            commands.trySend("")
            return START_NOT_STICKY
        }
        showNotification("正在连接…", initial = true)
        if (intent == null && !prefs.getBoolean("wanted", false)) {
            commands.trySend("")
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("profile") ?: prefs.getString("active", "").orEmpty()
        if (current?.id != id || intent?.getBooleanExtra("restart", false) == true) commands.trySend(id)
        return START_STICKY
    }

    private fun startCore(id: String) {
        ProfileStore(this).load()
        if (id == "cloud") {
            startedAt = SystemClock.elapsedRealtime()
            prefs.edit().putBoolean("wanted", true).putString("active", id).apply()
            AppState.publish(this, VpnState(phase = "connecting", profileId = "cloud", since = startedAt))
            startCloud()
            return
        }
        val profile = AppState.profiles.value.firstOrNull { it.id == id } ?: error("请选择或导入网络配置")
        check(profile.meta.optBoolean("noTun") || prepare(this) == null) { "请先在应用中允许 VPN 连接" }
        current = profile
        startedAt = SystemClock.elapsedRealtime()
        lastPoll = 0
        lastRx = 0
        lastTx = 0
        lastCoreEvents = emptySet()
        prefs.edit().putBoolean("wanted", true).putString("active", id).apply()
        AppState.publish(this, VpnState(phase = "connecting", profileId = id, since = startedAt))
        AppState.log("正在连接 ${profile.title}")
        NativeCore.request(if (id.startsWith("cloud-")) "startCloudProfile" else "start", profile.raw)
        if (prefs.getBoolean("cloudEnabled", false)) startCloud()
    }

    private fun startCloud() {
        val url = prefs.getString("cloudUrl", "").orEmpty()
        check(url.isNotBlank()) { "请填写配置服务器地址" }
        var machine = prefs.getString("machineId", null)
        if (machine == null) {
            machine = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("machineId", machine).apply()
        }
        NativeCore.request("cloudStart", JSONObject().put("url", url).put("machineId", machine).put("secure", prefs.getBoolean("cloudSecure", true)).toString())
        cloudOn = true
        AppState.cloud.value = "正在连接"
    }

    private fun syncCloud() {
        val data = NativeCore.request("cloud")
        AppState.cloud.value = if (data.optBoolean("connected")) "已连接" else "正在重连"
        val received = data.array("profiles").objects()
        received.forEach { item ->
            val id = "cloud-" + item.getString("id")
            val raw = item.getString("toml")
            val old = AppState.profiles.value.firstOrNull { it.id == id }
            val profile = if (old?.raw == raw) old else ProfileStore(this).save("云端 · " + item.text("name"), raw, id, select = false)
            if (current?.id == id) current = profile
        }
        if (current == null && received.isNotEmpty()) {
            val id = "cloud-" + received.first().getString("id")
            val profile = AppState.profiles.value.first { it.id == id }
            current = profile
            NativeCore.request("startCloudProfile", profile.raw)
            ProfileStore(this).select(id)
            AppState.publish(this, AppState.vpn.value.copy(profileId = id))
        }
    }

    private fun poll() {
        val profile = current ?: return
        val data = NativeCore.request("snapshot")
        check(data.text("error").isEmpty()) { data.text("error") }
        val ip = data.text("ipv4").ifBlank { profile.meta.text("ipv4") }
        val noTun = profile.meta.optBoolean("noTun")
        if (ip.isBlank() && !noTun) {
            check(SystemClock.elapsedRealtime() - startedAt < 90_000) { "等待虚拟地址超时，请检查初始节点和网络密钥" }
            return
        }
        val overlay = if (ip.isBlank()) "" else ipv4Network(ip)
        val ipv6 = profile.meta.text("ipv6")
        val manual = profile.meta.array("routes").strings()
        val exitMode = profile.meta.optInt("exitNodes") > 0
        val defaultRoutes = if (exitMode) listOf("0.0.0.0/0", "::/0") else emptyList()
        val routes = (listOf(overlay, ipv6).filter { it.isNotBlank() } + manual + defaultRoutes + data.array("routes").strings()).distinct().sorted()
        val key = ip + ipv6 + routes.joinToString() + profile.meta.optInt("mtu") + data.optLong("generation")
        if (!noTun && key != routeKey) {
            val parts = ip.split('/')
            val builder = Builder().setSession(profile.title).setMtu(profile.meta.optInt("mtu", 1360))
                .addAddress(parts[0], parts.getOrNull(1)?.toInt() ?: 24)
                .addDisallowedApplication(packageName).setBlocking(false)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            if (!exitMode) builder.allowFamily(android.system.OsConstants.AF_INET6)
            routes.forEach { route ->
                val normalized = ipNetwork(route).split('/')
                builder.addRoute(normalized[0], normalized[1].toInt())
            }
            if (ipv6.isNotBlank()) {
                val v6 = ipv6.split('/')
                builder.addAddress(v6[0], v6.getOrNull(1)?.toInt() ?: 128)
            }
            val document = NativeCore.request("decode", profile.raw)
            val flags = document.optJSONObject("flags")
            if (exitMode) {
                val dns = prefs.getString("exitDns:${profile.id}", "223.5.5.5").orEmpty()
                check(validIpv4(dns)) { "出口 DNS 地址无效" }
                builder.addDnsServer(dns)
            } else if (flags?.optBoolean("accept_dns") == true) {
                // Same Magic DNS address used by the official Android client.
                builder.addDnsServer("100.100.100.101")
                builder.addRoute("100.100.100.101", 32)
            }
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
            val replacement = builder.establish() ?: error("无法创建 Android VPN")
            try {
                NativeCore.request("tun", replacement.fd.toString())
            } catch (error: Exception) {
                replacement.close()
                throw error
            }
            tun?.close()
            tun = replacement
            routeKey = key
            AppState.log("VPN 路由已更新，共 ${routes.size} 条")
        }
        val now = SystemClock.elapsedRealtime()
        val rx = data.optLong("rx")
        val tx = data.optLong("tx")
        val seconds = ((now - lastPoll) / 1000.0).coerceAtLeast(0.001)
        val rxRate = if (lastPoll == 0L) 0.0 else (rx - lastRx).coerceAtLeast(0) / seconds
        val txRate = if (lastPoll == 0L) 0.0 else (tx - lastTx).coerceAtLeast(0) / seconds
        lastPoll = now
        lastRx = rx
        lastTx = tx
        val online = data.optBoolean("running") && data.array("nodes").length() > 0
        val phase = if (online) "connected" else "connecting"
        val previous = AppState.vpn.value
        if (previous.phase != phase && online) AppState.log("已接入网络")
        val events = data.optJSONObject("details")?.array("events")?.strings().orEmpty()
        events.reversed().filter { it !in lastCoreEvents }.forEach { AppState.log(it) }
        lastCoreEvents = events.toSet()
        AppState.publish(this, VpnState(
            phase, profile.id, ip, since = startedAt, snapshot = data,
            rxRate = rxRate, txRate = txRate,
            history = (previous.history + (rxRate.toFloat() to txRate.toFloat())).takeLast(60),
        ))
        showNotification(if (online) "${ip.substringBefore('/')} · ${data.array("nodes").length()} 个节点" else "等待对端连接…")
    }

    private fun stopCore() {
        try {
            NativeCore.request("stop")
        } finally {
            val descriptor = tun
            tun = null
            routeKey = ""
            current = null
            cloudOn = false
            AppState.cloud.value = "未连接"
            descriptor?.close()
        }
    }

    private fun showNotification(message: String, initial: Boolean = false) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "vpn").setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("EasyTier · ${current?.title ?: "VPN"}").setContentText(message)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "断开", stop).build()).build()
        if (initial) {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notification)
        } else getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    override fun onRevoke() {
        prefs.edit().putBoolean("wanted", false).apply()
        AppState.log("系统已撤销 VPN 连接")
        commands.trySend("")
        super.onRevoke()
    }

    override fun onDestroy() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        runCatching { stopCore() }
        if (AppState.vpn.value.phase != "error") AppState.publish(this, VpnState())
        super.onDestroy()
    }
}

fun ipv4Network(cidr: String): String {
    require(!cidr.contains(':'))
    return ipNetwork(cidr)
}

fun ipNetwork(cidr: String): String {
    val parts = cidr.split('/')
    require(parts[0].matches(Regex("[0-9a-fA-F:.]+"))) { "无效的 IP 路由 $cidr" }
    val bytes = InetAddress.getByName(parts[0]).address
    val prefix = parts.getOrNull(1)?.toInt() ?: (bytes.size * 8)
    require(prefix in 0..bytes.size * 8)
    bytes.indices.forEach { index ->
        val bits = (prefix - index * 8).coerceIn(0, 8)
        bytes[index] = (bytes[index].toInt() and (255 shl (8 - bits))).toByte()
    }
    return "${InetAddress.getByAddress(bytes).hostAddress}/$prefix"
}
