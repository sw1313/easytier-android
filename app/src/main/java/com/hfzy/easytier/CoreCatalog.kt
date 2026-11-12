package com.hfzy.easytier

import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONObject

enum class UpdatePhase { IDLE, CHECKING, WAITING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }
enum class CoreChannel(val id: String, val label: String) {
    STABLE("stable", "稳定版"), NIGHTLY("nightly", "Nightly")
}
data class CoreRelease(
    val version: String,
    val coreVersion: String,
    val notes: String,
    val publishedAt: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val channel: CoreChannel = CoreChannel.STABLE,
    val upstreamCommit: String = "",
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
    private const val UPSTREAM = "https://api.github.com/repos/EasyTier/EasyTier/releases/"

    fun url(channel: CoreChannel): String = URL.replace("stable.json", "${channel.id}.json")
    fun channelOfVersion(version: String): CoreChannel =
        if (coreVersion(version).contains("-nightly.")) CoreChannel.NIGHTLY else CoreChannel.STABLE

    fun coreVersion(version: String): String = version.removePrefix("v").replace(Regex("-md\\d+$"), "")
    fun adapterBuild(version: String): String = version.substringAfter("-md", "").ifBlank { "内置" }
    fun displayVersion(version: String): String = "${coreVersion(version)}，Android 适配构建 ${adapterBuild(version)}"

    fun compareVersions(left: String, right: String): Int {
        fun parts(version: String): List<String> {
            val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$").matchEntire(coreVersion(version))
                ?: error("无法比较核心版本 $version")
            return (1..4).map { match.groupValues[it] }
        }
        val a = parts(left)
        val b = parts(right)
        for (index in 0..2) {
            val result = a[index].toInt().compareTo(b[index].toInt())
            if (result != 0) return result
        }
        if (a[3] != b[3]) {
            if (a[3].isEmpty()) return 1
            if (b[3].isEmpty()) return -1
            val aPreview = a[3].split('.')
            val bPreview = b[3].split('.')
            for ((first, second) in aPreview.zip(bPreview)) {
                val firstNumber = first.toLongOrNull()
                val secondNumber = second.toLongOrNull()
                val result = when {
                    firstNumber != null && secondNumber != null -> firstNumber.compareTo(secondNumber)
                    firstNumber != null -> -1
                    secondNumber != null -> 1
                    else -> first.compareTo(second)
                }
                if (result != 0) return result
            }
            val length = aPreview.size.compareTo(bPreview.size)
            if (length != 0) return length
        }
        fun adapter(version: String) = Regex("-md(\\d+)$").find(version)?.groupValues?.get(1)?.toInt() ?: 0
        return adapter(left).compareTo(adapter(right))
    }

    fun releases(context: Context, envelopeBytes: ByteArray, channel: CoreChannel): List<CoreRelease> {
        val envelope = JSONObject(String(envelopeBytes, Charsets.UTF_8))
        val bytes = Base64.decode(envelope.getString("payload"), Base64.DEFAULT)
        require(bytes.size <= 256 * 1024) { "更新目录过大" }
        CoreUpdates.verifySignature(context, bytes, Base64.decode(envelope.getString("signature"), Base64.DEFAULT))
        val catalog = JSONObject(String(bytes, Charsets.UTF_8))
        require(catalog.getInt("schema") == 1 && catalog.getString("channel") == channel.id) { "更新目录通道或格式不兼容" }
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
            require(channelOfVersion(version) == channel) { "核心版本与更新通道不一致" }
            val commit = item.optString("upstreamCommit")
            require(channel != CoreChannel.NIGHTLY || commit.matches(Regex("[0-9a-f]{40}"))) { "Nightly 缺少对应的官方构建提交" }
            val published = item.getString("publishedAt")
            java.time.Instant.parse(published)
            CoreRelease(version, item.getString("coreVersion"), item.getString("notes"), published, url, size, hash, channel, commit)
        }
    }

    private fun latest(releases: List<CoreRelease>, channel: CoreChannel): CoreRelease? =
        if (channel == CoreChannel.NIGHTLY) releases.maxByOrNull { java.time.Instant.parse(it.publishedAt) }
        else releases.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }

    fun parse(context: Context, envelopeBytes: ByteArray, channel: CoreChannel = CoreChannel.STABLE): CoreRelease =
        latest(releases(context, envelopeBytes, channel), channel)
            ?: error("更新源暂无适用于此应用与手机架构的核心")

    fun evaluate(context: Context, channel: CoreChannel, release: CoreRelease?, upstream: String, upstreamCommit: String = ""): CoreUpdateState {
        if (release == null) return CoreUpdateState(
            phase = UpdatePhase.WAITING, upstream = upstream, upstreamPending = upstream.isNotBlank(),
            message = "${channel.label} 暂无适用于当前应用架构的兼容核心包，准备完成后可在此更新。",
        )
        require(release.channel == channel) { "核心更新通道不一致" }
        val pending = if (channel == CoreChannel.NIGHTLY) {
            upstreamCommit.isNotBlank() && upstreamCommit != release.upstreamCommit
        } else {
            require(upstream.isBlank() || compareVersions(release.coreVersion, upstream) <= 0) { "更新包尚未对应官方稳定版" }
            upstream.isNotBlank() && compareVersions(upstream, release.coreVersion) > 0
        }
        val selected = CoreUpdates.version(context)
        val update = when {
            channelOfVersion(selected) != channel -> true
            channel == CoreChannel.NIGHTLY -> release.version != selected
            else -> compareVersions(release.version, selected) > 0
        }
        val phase = when {
            update -> UpdatePhase.AVAILABLE
            selected != NativeCore.loadedVersion -> UpdatePhase.READY
            else -> UpdatePhase.CURRENT
        }
        return CoreUpdateState(phase, release, upstream, pending)
    }

    fun check(context: Context, channel: CoreChannel = CoreUpdates.channel(context)): CoreUpdateState {
        val release = latest(releases(context, CoreUpdates.readRemote("${url(channel)}?check=${System.currentTimeMillis()}"), channel), channel)
        val upstream = runCatching {
            val suffix = if (channel == CoreChannel.STABLE) "latest" else "tags/nightly"
            val info = JSONObject(String(CoreUpdates.readRemote(UPSTREAM + suffix), Charsets.UTF_8))
            if (channel == CoreChannel.STABLE) info.getString("tag_name").removePrefix("v") to ""
            else {
                val commit = Regex("Commit:\\s*\\[?([0-9a-f]{40})").find(info.optString("body"))?.groupValues?.get(1)
                    ?: info.optString("target_commitish")
                require(commit.matches(Regex("[0-9a-f]{40}"))) { "无法识别官方 Nightly 构建" }
                "nightly (${commit.take(7)})" to commit
            }
        }.getOrDefault("" to "")
        val state = evaluate(context, channel, release, upstream.first, upstream.second)
        CoreUpdates.markChecked(context, channel)
        return state
    }
}
