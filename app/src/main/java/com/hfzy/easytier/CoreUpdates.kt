package com.hfzy.easytier

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.zip.ZipFile

object CoreUpdates {
    const val BUNDLED_VERSION = "2.6.4-md2"
    val abi: String
        get() = (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS)
            .firstOrNull() ?: error("无法识别当前应用的核心架构")
    private const val LIMIT = 120 * 1024 * 1024
    private fun prefs(context: Context) = context.getSharedPreferences("core", Context.MODE_PRIVATE)
    fun version(context: Context): String = prefs(context).getString("version", BUNDLED_VERSION).orEmpty()
    fun lastChecked(context: Context): Long = prefs(context).getLong("lastChecked", 0)
    fun markChecked(context: Context) { prefs(context).edit().putLong("lastChecked", System.currentTimeMillis()).apply() }
    fun activeLibrary(context: Context): File? {
        val name = prefs(context).getString("library", null) ?: return null
        val library = File(File(context.filesDir, "cores"), name)
        return library.takeIf { it.isFile && !it.canWrite() }
    }
    fun rollback(context: Context) {
        prefs(context).edit().remove("library").remove("version").remove("previousLibrary").remove("previousVersion").commit()
    }
    @Synchronized fun install(context: Context, archive: File, expectedVersion: String? = null): String {
        val version: String
        ZipFile(archive).use { zip ->
            fun read(name: String, limit: Int): ByteArray {
                val entry = zip.getEntry(name) ?: error("更新包缺少 $name")
                require(entry.size in 1..limit.toLong()) { "更新包文件大小无效" }
                return zip.getInputStream(entry).use { it.readBytesLimited(limit) }
            }
            val manifestBytes = read("manifest.json", 16 * 1024)
            val signatureBytes = read("manifest.sig", 2048)
            verifySignature(context, manifestBytes, signatureBytes)
            val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
            require(manifest.getInt("api") == 1) { "核心接口版本不兼容" }
            require(manifest.getString("abi") == abi) { "该核心不适用于当前应用架构" }
            version = manifest.getString("version")
            require(version.isNotBlank() && version.length <= 80)
            require(expectedVersion == null || expectedVersion == version) { "更新包版本与更新目录不一致" }
            val bytes = read("libeasytier_mobile.so", LIMIT)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(digest.equals(manifest.getString("sha256"), true)) { "核心文件校验失败" }
            require(bytes.size > 20 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte()) { "核心文件格式无效" }
            val directory = File(context.filesDir, "cores").apply { mkdirs() }
            val library = File(directory, "core-${UUID.randomUUID()}.so")
            try {
                library.outputStream().use { output ->
                    // Android 14 requires dynamically loaded code to be read-only.
                    check(library.setReadOnly())
                    output.write(bytes)
                    output.fd.sync()
                }
                val edit = prefs(context).edit().putString("library", library.name).putString("version", version)
                val previous = NativeCore.loadedLibrary
                if (previous != null) edit.putString("previousLibrary", previous.name).putString("previousVersion", NativeCore.loadedVersion)
                else edit.remove("previousLibrary").remove("previousVersion")
                check(edit.commit()) { "无法保存核心更新状态" }
                cleanup(context)
            } catch (error: Exception) {
                library.delete()
                throw error
            }
        }
        return version
    }
    fun verifySignature(context: Context, bytes: ByteArray, signature: ByteArray) {
        val key = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.decode(context.getString(R.string.core_signing_key), Base64.DEFAULT))
        )
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update(bytes)
        require(verifier.verify(signature)) { "更新签名校验失败" }
    }
    @Synchronized fun rejectSelected(context: Context): File? {
        val preferences = prefs(context)
        val previous = preferences.getString("previousLibrary", null)
        val version = preferences.getString("previousVersion", BUNDLED_VERSION)
        val file = previous?.let { File(File(context.filesDir, "cores"), it) }
        if (file?.isFile == true && !file.canWrite()) {
            preferences.edit().putString("library", previous).putString("version", version)
                .remove("previousLibrary").remove("previousVersion").commit()
            return file
        }
        rollback(context)
        return null
    }
    @Synchronized fun cleanup(context: Context) {
        val preferences = prefs(context)
        val retained = setOf(preferences.getString("library", null), preferences.getString("previousLibrary", null), NativeCore.loadedLibrary?.name)
        File(context.filesDir, "cores").listFiles().orEmpty().filter {
            it.name.matches(Regex("core-[0-9a-f-]{36}\\.so")) && it.name !in retained
        }.forEach { it.delete() }
    }
    private fun openHttps(address: String): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https") { "请使用 HTTPS 更新地址" }
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "EasyTier-MD/${BuildConfig.VERSION_NAME}")
            try {
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, connection.getHeaderField("Location") ?: error("更新地址跳转失败"))
                    connection.disconnect()
                } else {
                    check(code == 200) { "更新服务器返回 HTTP $code" }
                    return connection
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        error("更新地址跳转次数过多")
    }
    fun readRemote(address: String, limit: Int = 512 * 1024): ByteArray {
        val connection = openHttps(address)
        try { return connection.inputStream.use { it.readBytesLimited(limit) } }
        finally { connection.disconnect() }
    }
    fun fileDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun download(context: Context, address: String, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val file = File.createTempFile("core-update-", ".zip", context.cacheDir)
        var connection: HttpURLConnection? = null
        try {
            val response = openHttps(address)
            connection = response
            val expected = response.contentLengthLong
            require(expected <= LIMIT) { "更新包过大" }
            response.inputStream.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var notifiedAt = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= LIMIT) { "更新包过大" }
                    output.write(buffer, 0, count)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - notifiedAt >= 100) {
                        progress(total, expected)
                        notifiedAt = now
                    }
                }
                check(expected < 0 || total == expected) { "核心更新包下载不完整" }
                progress(total, expected)
            } }
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally { connection?.disconnect() }
    }
}

fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "文件过大" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
