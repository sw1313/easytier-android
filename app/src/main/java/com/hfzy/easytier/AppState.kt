package com.hfzy.easytier

import android.content.Context
import android.content.ComponentName
import android.service.quicksettings.TileService
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object NativeCore {
    private var loaded = false
    var loadedVersion = CoreUpdates.BUNDLED_VERSION
        private set
    var loadedLibrary: File? = null
        private set
    @Synchronized fun load(context: Context) {
        if (loaded) return
        var installed = CoreUpdates.activeLibrary(context)
        if (installed != null && CoreCatalog.channelOfVersion(CoreUpdates.version(context)) == CoreChannel.STABLE && runCatching {
            CoreCatalog.compareVersions(CoreUpdates.version(context), CoreUpdates.BUNDLED_VERSION) < 0
        }.getOrDefault(true)) {
            CoreUpdates.rollback(context)
            installed = null
        }
        repeat(2) {
            val candidate = installed ?: return@repeat
            try {
                System.load(candidate.absolutePath)
                loadedVersion = CoreUpdates.version(context)
                loadedLibrary = candidate
                loaded = true
                CoreUpdates.cleanup(context)
                return
            } catch (_: UnsatisfiedLinkError) {
                installed = CoreUpdates.rejectSelected(context)
            }
        }
        CoreUpdates.rollback(context)
        System.loadLibrary("easytier_mobile")
        loaded = true
        CoreUpdates.cleanup(context)
    }
    @JvmStatic external fun call(method: String, payload: String): String
    fun request(method: String, payload: String = ""): JSONObject {
        val response = JSONObject(call(method, payload))
        check(response.optBoolean("ok")) { response.optString("error", "内核操作失败") }
        return response.getJSONObject("data")
    }
}

data class Profile(val id: String, val title: String, val raw: String, val meta: JSONObject)
data class VpnState(
    val phase: String = "disconnected",
    val profileId: String = "",
    val ip: String = "",
    val error: String = "",
    val since: Long = 0,
    val snapshot: JSONObject = JSONObject(),
    val rxRate: Double = 0.0,
    val txRate: Double = 0.0,
    val history: List<Pair<Float, Float>> = emptyList(),
) {
    val active get() = phase == "connected" || phase == "connecting"
}

object AppState {
    val vpn = MutableStateFlow(VpnState())
    val profiles = MutableStateFlow<List<Profile>>(emptyList())
    val selected = MutableStateFlow("")
    val logs = MutableStateFlow<List<String>>(emptyList())
    val cloud = MutableStateFlow("未连接")
    fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        logs.value = (listOf("$time  $message") + logs.value).take(200)
    }
    fun publish(context: Context, state: VpnState) {
        vpn.value = state
        TileService.requestListeningState(context, ComponentName(context, EasyTierTileService::class.java))
    }
}

class ProfileStore(private val context: Context) {
    companion object { private val lock = Any() }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "profiles").apply { mkdirs() }
    fun load() = synchronized(lock) {
        NativeCore.load(context)
        AppState.profiles.value = directory.listFiles().orEmpty().filter { it.extension == "json" }
            .sortedBy { it.name }.mapNotNull { file ->
                runCatching {
                    val doc = JSONObject(file.readText())
                    val raw = doc.getString("toml")
                    Profile(doc.getString("id"), doc.getString("title"), raw, NativeCore.request("inspect", raw))
                }.getOrNull()
            }
        val saved = prefs.getString("selected", "").orEmpty()
        AppState.selected.value = AppState.profiles.value.firstOrNull { it.id == saved }?.id
            ?: AppState.profiles.value.firstOrNull()?.id.orEmpty()
    }
    fun save(title: String, raw: String, id: String = UUID.randomUUID().toString(), select: Boolean = true): Profile = synchronized(lock) {
        val meta = NativeCore.request("inspect", raw)
        val profile = Profile(id, title.ifBlank { meta.optString("network", "EasyTier") }, raw, meta)
        val atomic = AtomicFile(File(directory, "$id.json"))
        val output = atomic.startWrite()
        try {
            output.write(JSONObject().put("id", id).put("title", profile.title).put("toml", raw).toString().toByteArray())
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        AppState.profiles.value = (AppState.profiles.value.filter { it.id != id } + profile).sortedBy { it.title }
        if (select) select(id)
        profile
    }
    fun select(id: String) = synchronized(lock) {
        require(AppState.profiles.value.any { it.id == id })
        prefs.edit().putString("selected", id).apply()
        AppState.selected.value = id
    }
    fun delete(id: String) = synchronized(lock) {
        check(AppState.vpn.value.profileId != id || !AppState.vpn.value.active) { "请先断开此网络" }
        File(directory, "$id.json").delete()
        AppState.profiles.value = AppState.profiles.value.filter { it.id != id }
        val next = AppState.profiles.value.firstOrNull()?.id.orEmpty()
        prefs.edit().putString("selected", next).apply()
        AppState.selected.value = next
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

val defaultConfig = """
    instance_name = "手机网络"
    hostname = "android"
    dhcp = true
    listeners = ["udp://0.0.0.0:0", "tcp://0.0.0.0:0"]

    [network_identity]
    network_name = "easytier"
    network_secret = ""

    [flags]
    mtu = 1360
    latency_first = true
    default_protocol = "udp"
""".trimIndent()
