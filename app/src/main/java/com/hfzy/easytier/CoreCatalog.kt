package com.hfzy.easytier

import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONObject

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }
data class CoreRelease(
    val version: String,
    val coreVersion: String,
    val notes: String,
    val publishedAt: String,
    val url: String,
    val size: Long,
    val sha256: String,
)
data class CoreUpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: CoreRelease? = null,
    val upstream: String = "",
    val upstreamPending: Boolean = false,
    val message: String = "",
    val downloaded: Long = 0,
    val total: Long = 0,
)

object CoreCatalog {
    const val URL = "https://raw.githubusercontent.com/sw1313/easytier-android/main/updates/stable.json"
    private const val UPSTREAM = "https://api.github.com/repos/EasyTier/EasyTier/releases/latest"

    fun coreVersion(version: String): String = version.removePrefix("v").substringBefore("-md")
    fun adapterBuild(version: String): String = version.substringAfter("-md", "").ifBlank { "内置" }
    fun displayVersion(version: String): String = "${coreVersion(version)}，Android 适配构建 ${adapterBuild(version)}"

    fun compareVersions(left: String, right: String): Int {
        fun parts(version: String): List<Int> {
            val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-md(\\d+))?$").matchEntire(version)
                ?: error("无法比较核心版本 $version")
            return (1..4).map { match.groupValues[it].ifEmpty { "0" }.toInt() }
        }
        return parts(left).zip(parts(right)).firstOrNull { it.first != it.second }
            ?.let { it.first.compareTo(it.second) } ?: 0
    }

    fun parse(context: Context, envelopeBytes: ByteArray): CoreRelease {
        val envelope = JSONObject(String(envelopeBytes, Charsets.UTF_8))
        val bytes = Base64.decode(envelope.getString("payload"), Base64.DEFAULT)
        require(bytes.size <= 256 * 1024) { "更新目录过大" }
        CoreUpdates.verifySignature(context, bytes, Base64.decode(envelope.getString("signature"), Base64.DEFAULT))
        val catalog = JSONObject(String(bytes, Charsets.UTF_8))
        require(catalog.getInt("schema") == 1 && catalog.getString("channel") == "stable") { "更新目录格式不兼容" }
        val abi = CoreUpdates.abi
        return catalog.array("releases").objects().filter {
            it.optInt("api") == 1 && it.optInt("minAppVersionCode", 1) <= BuildConfig.VERSION_CODE &&
                it.optInt("minSdk", 26) <= Build.VERSION.SDK_INT && it.getJSONObject("assets").has(abi)
        }.map { item ->
            val asset = item.getJSONObject("assets").getJSONObject(abi)
            val url = asset.getString("url")
            val size = asset.getLong("size")
            val hash = asset.getString("sha256")
            require(java.net.URL(url).protocol == "https" && size in 1..120L * 1024 * 1024 && hash.matches(Regex("[0-9a-fA-F]{64}"))) { "核心下载信息无效" }
            val version = item.getString("version")
            compareVersions(version, version)
            CoreRelease(version, item.getString("coreVersion"), item.getString("notes"), item.getString("publishedAt"), url, size, hash)
        }.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
            ?: error("更新源暂无适用于此应用与手机架构的核心")
    }

    fun check(context: Context): CoreUpdateState {
        val release = parse(context, CoreUpdates.readRemote("$URL?check=${System.currentTimeMillis()}"))
        val upstream = runCatching {
            JSONObject(String(CoreUpdates.readRemote(UPSTREAM), Charsets.UTF_8)).getString("tag_name").removePrefix("v")
        }.getOrDefault("")
        val pending = upstream.isNotBlank() && compareVersions(upstream, release.coreVersion) > 0
        require(upstream.isBlank() || compareVersions(release.coreVersion, upstream) <= 0) { "更新包尚未对应官方稳定版" }
        val selected = CoreUpdates.version(context)
        val phase = when {
            compareVersions(release.version, selected) > 0 -> UpdatePhase.AVAILABLE
            selected != NativeCore.loadedVersion -> UpdatePhase.READY
            else -> UpdatePhase.CURRENT
        }
        CoreUpdates.markChecked(context)
        return CoreUpdateState(phase, release, upstream, pending)
    }
}
