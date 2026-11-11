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
    override fun onStart() {
        val results = Bundle()
        try {
            val context = targetContext
            NativeCore.load(context)
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
                check(NativeCore.loadedVersion == "2.6.4-md2")
                check(NativeCore.request("version").getInt("adapter_build") == 2)
                check(CoreCatalog.check(context).phase == UpdatePhase.CURRENT)
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
