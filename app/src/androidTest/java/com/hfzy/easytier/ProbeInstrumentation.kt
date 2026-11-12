package com.hfzy.easytier

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject

/** Runs on an isolated emulator with a loopback-only reference peer. */
class ProbeInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle()
        start()
    }
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    override fun onStart() {
        val results = Bundle()
        try {
            val context = targetContext
            NativeCore.load(context)
            if (arguments.getString("stageCore") != null) {
                val version = CoreUpdates.install(context, java.io.File(arguments.getString("stageCore")!!))
                check(CoreUpdates.activeLibrary(context) != null)
                results.putString("result", "PASS staged signed core $version")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (arguments.getString("customLoaded") == "true") {
                check(CoreCatalog.channelOfVersion(NativeCore.loadedVersion) == CoreChannel.CUSTOM)
                val version = NativeCore.request("version")
                check(version.optBoolean("custom") && version.getInt("adapter_build") == 5)
                check(version.getString("upstream_commit") == "7a4b2b105b3da373ce24ebb629c04be767fcab7b")
                val document = NativeCore.request("decode", defaultConfig)
                document.put("parallel_connections", 2).put("traffic_disguise", "video").put("disable_parallel_connections", false)
                document.getJSONObject("flags").put("p2p_disguise_mode", "off").put("close_redundant_conns_when_disguised", false)
                val raw = NativeCore.request("encode", document.toString()).getString("toml")
                val roundtrip = NativeCore.request("decode", raw)
                check(roundtrip.getInt("parallel_connections") == 2)
                check(roundtrip.getString("traffic_disguise") == "video")
                check(roundtrip.getJSONObject("flags").getString("p2p_disguise_mode") == "off")
                check(!roundtrip.getJSONObject("flags").getBoolean("close_redundant_conns_when_disguised"))
                document.put("parallel_connections", 17)
                check(runCatching { NativeCore.request("encode", document.toString()) }.isFailure)
                CoreUpdates.setChannel(context, CoreChannel.CUSTOM)
                check(CoreUpdates.channel(context) == CoreChannel.CUSTOM)
                val before = CoreUpdates.lastChecked(context, CoreChannel.NIGHTLY)
                CoreUpdates.markChecked(context, CoreChannel.CUSTOM)
                check(CoreUpdates.lastChecked(context, CoreChannel.CUSTOM) > 0)
                check(CoreUpdates.lastChecked(context, CoreChannel.NIGHTLY) == before)
                arguments.getString("customCatalog")?.let { filename ->
                    val bytes = java.io.File(filename).readBytes()
                    val release = CoreCatalog.parse(context, bytes, CoreChannel.CUSTOM)
                    check(release.version == NativeCore.loadedVersion)
                    check(runCatching { CoreCatalog.parse(context, bytes, CoreChannel.STABLE) }.isFailure)
                    check(runCatching { CoreCatalog.parse(context, bytes, CoreChannel.NIGHTLY) }.isFailure)
                    check(CoreCatalog.evaluate(context, CoreChannel.CUSTOM, release, "Custom", "a".repeat(40)).phase == UpdatePhase.CURRENT)
                }
                results.putString("result", "PASS real Custom JNI / pinned source / Custom TOML roundtrip / bounds / channel persistence / scoped checks / signed catalog isolation")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (arguments.getString("nightlyLoaded") == "true") {
                check(CoreCatalog.channelOfVersion(NativeCore.loadedVersion) == CoreChannel.NIGHTLY)
                val version = NativeCore.request("version")
                check(version.getInt("adapter_build") == 4)
                check(version.getString("upstream_commit") == "2abb1676b475947e46c527b57acc5f296cb48482")
                check(version.optBoolean("portal_clients"))
                val raw = defaultConfig + "\n[vpn_portal_config]\nclient_cidr = \"10.20.0.0/24\"\nwireguard_listen = \"127.0.0.1:22022\""
                val stored = ProfileStore(context).save("旧门户兼容验证", raw, "legacy-portal-test")
                val portal = NativeCore.request("decode", stored.raw).getJSONObject("vpn_portal_config")
                check(portal.getString("client_cidr") == "10.20.0.0/24")
                val key = portal.getString("wireguard_private_key")
                check(android.util.Base64.decode(key, android.util.Base64.DEFAULT).size == 32)
                ProfileStore(context).load()
                val reloaded = AppState.profiles.value.first { it.id == stored.id }
                check(NativeCore.request("decode", reloaded.raw).getJSONObject("vpn_portal_config").getString("wireguard_private_key") == key)
                NativeCore.request("inspect", reloaded.raw)
                results.putString("result", "PASS real Nightly JNI loaded / pinned upstream commit / legacy portal migration / API 1")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (arguments.getString("vpnTraffic") == "true") {
                check(android.net.VpnService.prepare(context) == null)
                val customTransport = arguments.getString("customTransport") == "true"
                val raw = """
                    instance_name = "Nightly emulator traffic"
                    hostname = "android-traffic-test"
                    ipv4 = "10.211.50.2/24"
                    dhcp = false
                    listeners = []
                    ${if (customTransport) "parallel_connections = 2" else ""}
                    [network_identity]
                    network_name = "${if (customTransport) "custom-local-test" else "material-local-test"}"
                    network_secret = "${if (customTransport) "local-custom-test-only" else "local-emulator-test-only"}"
                    [[peer]]
                    uri = "${if (customTransport) "http3://10.0.2.2:21140" else "tcp://10.0.2.2:21110"}"
                    [flags]
                    enable_ipv6 = false
                    disable_upnp = true
                    ${if (customTransport) "p2p_disguise_mode = \"off\"\nclose_redundant_conns_when_disguised = false" else ""}
                    [vpn_portal_config]
                    wireguard_listen = "127.0.0.1:22022"
                    [[vpn_portal_config.clients]]
                    name = "test-client"
                    virtual_ip = "10.20.0.1/24"
                """.trimIndent()
                val profile = ProfileStore(context).save("Nightly 流量验证", raw, "nightly-traffic")
                val savedKey = NativeCore.request("decode", profile.raw).getJSONObject("vpn_portal_config").getString("wireguard_private_key")
                ProfileStore(context).load()
                check(NativeCore.request("decode", AppState.profiles.value.first { it.id == profile.id }.raw).getJSONObject("vpn_portal_config").getString("wireguard_private_key") == savedKey)
                try {
                    runOnMainSync { EasyTierVpnService.connect(context, profile.id) }
                    val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
                    while (AppState.vpn.value.phase != "connected" && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
                    check(AppState.vpn.value.phase == "connected") { "VPN failed: ${AppState.vpn.value.error}" }
                    Thread.sleep(1500)
                    val response = shell("echo nightly-vpn-test | toybox nc -w 5 198.18.0.1 21130")
                    check(response.contains("nightly-vpn-test")) { "VPN TCP payload did not return" }
                    val snapshot = NativeCore.request("snapshot")
                    check(snapshot.optLong("rx") > 0 && snapshot.optLong("tx") > 0)
                    check(snapshot.array("portal_clients").objects().any { usablePortalConfig(it.text("config")) }) { "Portal did not generate a named client config" }
                    if (customTransport) {
                        check(snapshot.array("nodes").objects().any { it.optJSONObject("connections")?.optJSONObject("tunnel")?.text("tunnel_type") == "http3" }) { "Custom HTTP3 transport was not selected" }
                        var parallel = false
                        repeat(30) {
                            if (NativeCore.request("snapshot").getJSONObject("details").array("peers").objects()
                                    .any { it.array("conns").objects().count { conn -> !conn.optBoolean("is_closed") } >= 2 }) parallel = true
                            if (!parallel) Thread.sleep(200)
                        }
                        check(parallel) { "Custom parallel connections were not established" }
                    }
                    results.putString("result", "PASS Android TUN / peer connection / subnet proxy TCP payload / counters / named WireGuard portal" + if (customTransport) " / HTTP3 / parallel connections" else "")
                } finally {
                    runOnMainSync { EasyTierVpnService.disconnect(context) }
                    repeat(50) { if (AppState.vpn.value.active) Thread.sleep(100) }
                }
                finish(Activity.RESULT_OK, results)
                return
            }
            if (arguments.getString("channels") == "true") {
                val prefs = context.getSharedPreferences("core", android.content.Context.MODE_PRIVATE)
                val keys = listOf("updateChannel", "version", "lastChecked", "lastChecked:nightly")
                val backup = keys.associateWith { prefs.all[it] }
                try {
                    check(CoreCatalog.compareVersions("2.7.0-nightly.20261111.aaaaaaa-md3", "2.6.4-md3") > 0)
                    check(CoreCatalog.compareVersions("2.7.0", "2.7.0-nightly.20261111.aaaaaaa-md3") > 0)
                    check(CoreCatalog.compareVersions("2.7.0-nightly.10.aaaaaaa-md3", "2.7.0-nightly.9.aaaaaaa-md3") > 0)
                    check(CoreCatalog.compareVersions("2.6.4-md3", "2.6.4-md2") > 0)
                    check(CoreCatalog.channelOfVersion("2.6.4-md3") == CoreChannel.STABLE)
                    val nightlyVersion = "2.7.0-nightly.20261111.aaaaaaa-md3"
                    check(CoreCatalog.channelOfVersion(nightlyVersion) == CoreChannel.NIGHTLY)
                    val stableBytes = CoreUpdates.readRemote(CoreCatalog.url(CoreChannel.STABLE) + "?test=" + System.currentTimeMillis())
                    val nightlyBytes = CoreUpdates.readRemote(CoreCatalog.url(CoreChannel.NIGHTLY) + "?test=" + System.currentTimeMillis())
                    val stable = CoreCatalog.parse(context, stableBytes)
                    check(CoreCatalog.releases(context, nightlyBytes, CoreChannel.NIGHTLY).all { it.channel == CoreChannel.NIGHTLY })
                    check(runCatching { CoreCatalog.releases(context, stableBytes, CoreChannel.NIGHTLY) }.isFailure)
                    check(runCatching { CoreCatalog.releases(context, nightlyBytes, CoreChannel.STABLE) }.isFailure)
                    val tampered = JSONObject(String(nightlyBytes, Charsets.UTF_8))
                    val payload = android.util.Base64.decode(tampered.getString("payload"), android.util.Base64.DEFAULT)
                    payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
                    tampered.put("payload", android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP))
                    check(runCatching { CoreCatalog.releases(context, tampered.toString().toByteArray(), CoreChannel.NIGHTLY) }.isFailure)
                    val stableChecked = CoreUpdates.lastChecked(context, CoreChannel.STABLE)
                    CoreUpdates.setChannel(context, CoreChannel.NIGHTLY)
                    check(CoreUpdates.channel(context) == CoreChannel.NIGHTLY)
                    CoreUpdates.markChecked(context, CoreChannel.NIGHTLY)
                    check(CoreUpdates.lastChecked(context, CoreChannel.NIGHTLY) > 0)
                    check(CoreUpdates.lastChecked(context, CoreChannel.STABLE) == stableChecked)
                    val nightly = stable.copy(version = nightlyVersion, coreVersion = "2.7.0-20261111", channel = CoreChannel.NIGHTLY, upstreamCommit = "a".repeat(40))
                    prefs.edit().putString("version", CoreUpdates.BUNDLED_VERSION).commit()
                    check(CoreCatalog.evaluate(context, CoreChannel.NIGHTLY, nightly, "nightly", "a".repeat(40)).phase == UpdatePhase.AVAILABLE)
                    prefs.edit().putString("version", nightlyVersion).commit()
                    check(CoreCatalog.evaluate(context, CoreChannel.STABLE, stable, stable.coreVersion).phase == UpdatePhase.AVAILABLE)
                    check(!CoreCatalog.evaluate(context, CoreChannel.NIGHTLY, nightly, "nightly", "a".repeat(40)).upstreamPending)
                    check(CoreCatalog.evaluate(context, CoreChannel.NIGHTLY, nightly, "nightly", "b".repeat(40)).upstreamPending)
                    check(CoreCatalog.evaluate(context, CoreChannel.NIGHTLY, nightly, "nightly", "b".repeat(40)).phase == UpdatePhase.READY)
                    val older = nightly.copy(version = "2.7.0-nightly.20261110.bbbbbbb-md4", publishedAt = "2026-11-10T00:00:00Z")
                    val newer = nightly.copy(publishedAt = "2026-11-11T00:00:00Z")
                    check(CoreCatalog.newestAvailable(listOf(older, newer), CoreChannel.NIGHTLY) { true } == newer)
                    check(CoreCatalog.newestAvailable(listOf(older, newer), CoreChannel.NIGHTLY) { it != newer } == older)
                    check(CoreCatalog.newestAvailable(listOf(older, newer), CoreChannel.NIGHTLY) { false } == null)
                    prefs.edit().putString("version", CoreUpdates.BUNDLED_VERSION).commit()
                    check(CoreCatalog.evaluate(context, CoreChannel.NIGHTLY, older, "nightly", "c".repeat(40)).phase == UpdatePhase.AVAILABLE)
                    prefs.edit().putString("version", nightlyVersion).commit()
                    check(CoreCatalog.evaluate(context, CoreChannel.NIGHTLY, null, "nightly").phase == UpdatePhase.WAITING)
                    val archive = CoreUpdates.download(context, stable.url)
                    try {
                        check(runCatching { CoreUpdates.install(context, archive, stable.version, CoreChannel.NIGHTLY) }.isFailure)
                        check(CoreUpdates.version(context) == nightlyVersion)
                    } finally { archive.delete() }
                } finally {
                    val edit = prefs.edit()
                    backup.forEach { (key, value) ->
                        when (value) {
                            is String -> edit.putString(key, value)
                            is Long -> edit.putLong(key, value)
                            else -> edit.remove(key)
                        }
                    }
                    check(edit.commit())
                }
                results.putString("result", "PASS channel persistence / scoped timestamps / signed catalogs / cross-channel rejection / Nightly version ordering / stable downgrade / waiting state")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (arguments.getString("vpnLifecycle") == "true") {
                check(android.net.VpnService.prepare(context) == null) { "Grant VPN permission before this test" }
                val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
                fun hasVpn(): Boolean = connectivity.allNetworks.any { network ->
                    connectivity.getNetworkCapabilities(network)
                        ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                }
                fun awaitCondition(message: String, predicate: () -> Boolean) {
                    repeat(80) {
                        if (predicate()) return
                        Thread.sleep(100)
                    }
                    error(message)
                }
                val profile = ProfileStore(context).save("VPN 生命周期验证", """
                    ipv4 = "10.42.0.2/24"
                    dhcp = false
                    listeners = []
                    [flags]
                    enable_ipv6 = false
                """.trimIndent(), "vpn-lifecycle-regression")
                try {
                    repeat(3) {
                        runOnMainSync { EasyTierVpnService.connect(context, profile.id) }
                        awaitCondition("Android VPN network was not established") { hasVpn() }
                        check(AppState.vpn.value.active)
                        runOnMainSync { EasyTierVpnService.disconnect(context) }
                        awaitCondition("App did not report disconnection") { !AppState.vpn.value.active }
                        awaitCondition("Android VPN network remains after disconnect") { !hasVpn() }
                        check(!NativeCore.request("snapshot").optBoolean("running"))
                    }
                    results.putString("result", "PASS 3 real TUN connect/disconnect cycles / system VPN removed / core stopped / adapter ${NativeCore.request("version").getInt("adapter_build")}")
                } finally {
                    runOnMainSync { EasyTierVpnService.disconnect(context) }
                }
                finish(Activity.RESULT_OK, results)
                return
            }
            if (arguments.getString("tile") == "true") {
                val component = "com.hfzy.easytier/.EasyTierTileService"
                fun awaitTile(state: Int, subtitle: String) {
                    repeat(30) {
                        val line = shell("dumpsys activity service com.android.systemui/.SystemUIService")
                            .lineSequence().firstOrNull { it.contains("spec=custom(") && it.contains("EasyTierTileService") }
                        if (line != null && Regex(",state=$state(?:,|\\])").containsMatchIn(line) && line.contains(subtitle)) return
                        Thread.sleep(200)
                    }
                    error("Quick Settings tile did not reach state $state / $subtitle")
                }
                fun publish(state: VpnState) = runOnMainSync { AppState.publish(context, state) }
                try {
                    shell("cmd statusbar add-tile $component")
                    shell("cmd statusbar set-tiles custom($component)")
                    shell("cmd statusbar expand-settings")
                    publish(VpnState())
                    awaitTile(1, "点击连接")
                    android.service.quicksettings.TileService.requestListeningState(context,
                        android.content.ComponentName(context, EasyTierTileService::class.java))
                    val listeningDeadline = android.os.SystemClock.elapsedRealtime() + 5000
                    while (AppState.vpn.subscriptionCount.value == 0 && android.os.SystemClock.elapsedRealtime() < listeningDeadline) {
                        Thread.sleep(50)
                    }
                    check(AppState.vpn.subscriptionCount.value > 0) { "Tile service did not begin observing VPN state" }
                    // Keep the existing binding while changing the flow, as with a cached QS panel.
                    runOnMainSync { AppState.vpn.value = VpnState(phase = "connecting") }
                    awaitTile(2, "连接中")
                    runOnMainSync { AppState.vpn.value = VpnState() }
                    awaitTile(1, "点击连接")
                    repeat(3) {
                        publish(VpnState(phase = "connecting"))
                        awaitTile(2, "连接中")
                        publish(VpnState(phase = "connected", ip = "10.42.0.2/24"))
                        awaitTile(2, "10.42.0.2")
                        publish(VpnState())
                        awaitTile(1, "点击连接")
                    }
                    publish(VpnState(phase = "error", error = "test-only"))
                    awaitTile(1, "连接失败")
                    shell("cmd statusbar collapse")
                    publish(VpnState(phase = "connected", ip = "10.42.0.3/24"))
                    shell("cmd statusbar expand-settings")
                    awaitTile(2, "10.42.0.3")
                    publish(VpnState())
                    awaitTile(1, "点击连接")
                    val profile = ProfileStore(context).save("磁贴验证", """
                        ipv4 = "10.42.0.2/24"
                        dhcp = false
                        listeners = []
                        [flags]
                        no_tun = true
                    """.trimIndent(), "tile-regression")
                    runOnMainSync { EasyTierVpnService.connect(context, profile.id) }
                    awaitTile(2, "连接中")
                    shell("cmd statusbar click-tile $component")
                    awaitTile(1, "点击连接")
                    check(!AppState.vpn.value.active)
                    check(CoreUpdates.abi in android.os.Build.SUPPORTED_ABIS)
                    results.putString("result", "PASS live tile highlight / disconnect / error / reopen / actual service stop / process ABI")
                } finally {
                    runOnMainSync { EasyTierVpnService.disconnect(context) }
                    publish(VpnState())
                    shell("cmd statusbar collapse")
                }
                finish(Activity.RESULT_OK, results)
                return
            }
            if (arguments.getString("updater") == "true") {
                check(CoreCatalog.compareVersions("2.10.0", "2.9.9") > 0)
                check(CoreCatalog.compareVersions("2.6.4-md2", "2.6.4-md1") > 0)
                check(CoreCatalog.compareVersions("v2.6.4", "2.6.4") == 0)
                check(CoreCatalog.coreVersion("2.6.4-md2") == "2.6.4")
                check(CoreCatalog.adapterBuild("2.6.4-md2") == "2")
                val bytes = CoreUpdates.readRemote(CoreCatalog.URL + "?test=" + System.currentTimeMillis())
                val release = CoreCatalog.parse(context, bytes)
                val tampered = JSONObject(String(bytes, Charsets.UTF_8))
                val payload = android.util.Base64.decode(tampered.getString("payload"), android.util.Base64.DEFAULT)
                payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
                tampered.put("payload", android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP))
                check(runCatching { CoreCatalog.parse(context, tampered.toString().toByteArray()) }.isFailure)
                val before = CoreUpdates.version(context)
                val archive = CoreUpdates.download(context, release.url)
                try {
                    check(archive.length() == release.size)
                    check(CoreUpdates.fileDigest(archive).equals(release.sha256, true))
                    check(runCatching { CoreUpdates.install(context, archive, "0.0.0") }.isFailure)
                    check(CoreUpdates.version(context) == before)
                    check(CoreUpdates.install(context, archive, release.version) == release.version)
                    check(NativeCore.loadedVersion == before)
                    NativeCore.request("inspect", defaultConfig)
                } finally { archive.delete() }
                CoreUpdates.rollback(context)
                CoreUpdates.cleanup(context)
                ProfileStore(context).save("更新验证", defaultConfig, "update-ui-test")
                results.putString("result", "PASS signed live catalog / version ordering / tamper rejection / download checksum / staged install / current core preserved")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (arguments.getString("updated") == "true") {
                check(NativeCore.loadedVersion == "2.6.4-md3")
                check(NativeCore.request("version").getInt("adapter_build") == 3)
                check(CoreCatalog.check(context, CoreChannel.STABLE).phase == UpdatePhase.CURRENT)
                if (arguments.getString("legacyPortal") == "true") {
                    ProfileStore(context).load()
                    val portal = AppState.profiles.value.first { it.id == "legacy-portal-test" }
                    NativeCore.request("inspect", portal.raw)
                    check(NativeCore.request("decode", portal.raw).getJSONObject("vpn_portal_config").getString("client_cidr") == "10.20.0.0/24")
                }
                results.putString("result", "PASS updated JNI loaded / current-version check")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (arguments.getString("smoke") == "true") {
                check(!usablePortalConfig("ERROR: Wireguard VPN Portal Not Started"))
                check(!usablePortalConfig(""))
                check(usablePortalConfig("[Interface]\nAddress = 10.20.0.2/24"))
                check(validIpv4("10.42.0.2"))
                check(!validIpv4("10.42.0.999"))
                val doc = NativeCore.request("decode", defaultConfig)
                doc.put("exit_nodes", org.json.JSONArray().put("10.42.0.2"))
                val raw = NativeCore.request("encode", doc.toString()).getString("toml")
                check(NativeCore.request("inspect", raw).optInt("exitNodes") == 1)
                doc.remove("exit_nodes")
                doc.put("vpn_portal_config", JSONObject().put("client_cidr", "10.20.0.0/24").put("wireguard_listen", "0.0.0.0:22022"))
                NativeCore.request("encode", doc.toString())
                ProfileStore(context).save("界面验证", defaultConfig, "ui-smoke")
                results.putString("result", "PASS portal status / portal defaults / exit configuration / IPv4 validation")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (arguments.getBoolean("cloudTest", false) || arguments.getString("cloudTest") == "true") {
                NativeCore.request("cloudStart", JSONObject().put("url", "tcp://10.0.2.2:21120/material-test")
                    .put("machineId", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee").put("secure", true).toString())
                var received = false
                repeat(45) {
                    Thread.sleep(1000)
                    val state = NativeCore.request("cloud")
                    if (state.array("profiles").length() > 0) received = true
                }
                check(received) { "Cloud configuration was not received" }
                NativeCore.request("stop")
                results.putString("result", "PASS configuration server secure handshake / pushed configuration")
                finish(Activity.RESULT_OK, results)
                return
            }
            val raw = """
                instance_name = "开发验证"
                hostname = "android-test"
                ipv4 = "10.211.50.2/24"
                dhcp = false
                listeners = []
                custom_future_option = "preserved"
                [network_identity]
                network_name = "material-local-test"
                network_secret = "local-emulator-test-only"
                [[peer]]
                uri = "tcp://10.0.2.2:21110"
                [flags]
                mtu = 1360
                enable_ipv6 = false
            """.trimIndent()
            val meta = NativeCore.request("inspect", raw)
            check(meta.text("ipv4") == "10.211.50.2/24")
            check(!meta.toString().contains("local-emulator-test-only"))
            val doc = NativeCore.request("decode", raw)
            doc.put("hostname", "android-material")
            val roundtrip = NativeCore.request("encode", doc.toString()).getString("toml")
            check(roundtrip.contains("custom_future_option"))
            val invalid = JSONObject(NativeCore.call("inspect", "network_secret = \"PRIVATE_TEST\"\n[[broken"))
            check(!invalid.getBoolean("ok") && !invalid.toString().contains("PRIVATE_TEST"))
            val store = ProfileStore(context)
            store.load()
            store.save("本地验证网络", roundtrip, "emulator-test")
            store.load()
            check(AppState.profiles.value.any { it.id == "emulator-test" })
            NativeCore.request("start", roundtrip)
            var snapshot = JSONObject()
            repeat(20) {
                Thread.sleep(500)
                snapshot = NativeCore.request("snapshot")
                if (snapshot.array("nodes").length() > 0) return@repeat
            }
            check(snapshot.optBoolean("running")) { "Native instance did not start" }
            check(snapshot.array("nodes").length() > 0) { "Reference peer was not reached" }
            NativeCore.request("stop")
            check(!NativeCore.request("cloud").optBoolean("connected"))
            val remoteArgs = JSONObject().put("url", "tcp://10.0.2.2:21112").put("action", "list")
            val remote = NativeCore.request("remote", remoteArgs.toString()).array("instances").objects()
            check(remote.isNotEmpty()) { "Remote RPC did not list reference instance" }
            remoteArgs.put("action", "get").put("id", remote.first().text("id"))
            check(NativeCore.request("remote", remoteArgs.toString()).getString("toml").contains("material-local-test"))
            val remoteRaw = """
                ipv4 = "10.211.51.1/24"
                hostname = "remote-probe"
                listeners = []
                [network_identity]
                network_name = "material-rpc-test"
                [flags]
                no_tun = true
            """.trimIndent()
            remoteArgs.put("action", "run").put("id", "").put("toml", remoteRaw)
            val newRemoteId = NativeCore.request("remote", remoteArgs.toString()).getString("id")
            remoteArgs.put("action", "stop").put("id", newRemoteId)
            NativeCore.request("remote", remoteArgs.toString())
            check(ipv4Network("198.51.100.210/24") == "198.51.100.0/24")
            check(ipv4Network("10.42.0.4/0") == "0.0.0.0/0")
            check(ipv4Network("10.42.0.4/32") == "10.42.0.4/32")
            val coreFile = arguments.getString("coreFile")
            if (coreFile != null) {
                val archive = java.io.File(coreFile)
                check(CoreUpdates.install(context, archive) == "2.6.4-md1")
                check(CoreUpdates.activeLibrary(context) != null)
                val invalidArchive = java.io.File(context.cacheDir, "invalid-core.zip")
                java.util.zip.ZipFile(archive).use { input ->
                    java.util.zip.ZipOutputStream(invalidArchive.outputStream()).use { output ->
                        input.entries().asSequence().forEach { entry ->
                            output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            val bytes = input.getInputStream(entry).use { it.readBytes() }
                            if (entry.name == "manifest.sig") bytes[0] = (bytes[0].toInt() xor 1).toByte()
                            output.write(bytes)
                            output.closeEntry()
                        }
                    }
                }
                check(runCatching { CoreUpdates.install(context, invalidArchive) }.isFailure)
                check(CoreUpdates.version(context) == "2.6.4-md1")
                invalidArchive.delete()
            }
            results.putString("result", "PASS native / config roundtrip / private metadata / persistence / peer / remote RPC / CIDR / signed core")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            runCatching { NativeCore.request("stop") }
            results.putString("result", "FAIL ${error.javaClass.simpleName}: ${error.message}")
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
