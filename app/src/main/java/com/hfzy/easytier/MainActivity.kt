package com.hfzy.easytier

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.*
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

class MainActivity : ComponentActivity() {
    var notice by mutableStateOf("")
    var coreRevision by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
    var page by mutableIntStateOf(0)
    var coreUpdate by mutableStateOf(CoreUpdateState())
    var coreUpdateChannel by mutableStateOf(CoreChannel.STABLE)
        private set
    private var pendingProfile = ""
    private var exportText = ""
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) EasyTierVpnService.connect(this, pendingProfile)
        else notice = "VPN 授权已取消"
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val importConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val raw = contentResolver.openInputStream(uri)!!.use { String(it.readBytesLimited(1024 * 1024), Charsets.UTF_8) }
            val meta = NativeCore.request("inspect", raw)
            ProfileStore(this@MainActivity).save(meta.text("name").ifBlank { meta.text("network") }, raw)
            withContext(Dispatchers.Main) { page = 1; notice = "配置已导入" }
        }
    }
    private val exportConfig = registerForActivityResult(ActivityResultContracts.CreateDocument("application/toml")) { uri ->
        if (uri != null) work {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(exportText.toByteArray()) }
            withContext(Dispatchers.Main) { notice = "配置已导出" }
        }
    }
    private val importCore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val file = File.createTempFile("core-import-", ".zip", cacheDir)
            try {
                contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { it.write(input.readBytesLimited(120 * 1024 * 1024)) } }
                installCore(file)
            } finally { file.delete() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        coreUpdateChannel = CoreUpdates.channel(this)
        runCatching { ProfileStore(this).load() }.onFailure { notice = it.message ?: "加载失败" }
        intent.getStringExtra("expectedCore")?.let { expected ->
            val applied = NativeCore.loadedVersion == expected
            val message = if (applied) "已启用 ${CoreCatalog.displayVersion(expected)}" else "核心加载失败，已恢复 ${CoreCatalog.displayVersion(NativeCore.loadedVersion)}"
            coreUpdate = CoreUpdateState(phase = if (applied) UpdatePhase.CURRENT else UpdatePhase.ERROR, message = message)
            notice = message
        }
        setContent { EasyTierApp(this) }
        AppState.publish(this, AppState.vpn.value)
        if (savedInstanceState == null) handleConnectIntent(intent)
        if (System.currentTimeMillis() - CoreUpdates.lastChecked(this) >= 6 * 60 * 60 * 1000L) checkCoreUpdate()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConnectIntent(intent)
    }
    private fun handleConnectIntent(request: Intent) {
        if (request.action != EasyTierVpnService.CONNECT) return
        val profile = request.getStringExtra("profile") ?: AppState.selected.value
        setIntent(Intent(request).apply { action = null; removeExtra("profile") })
        connect(profile)
    }
    fun connect(id: String = AppState.selected.value) {
        if (id.isBlank()) { page = 1; notice = "请新建或导入网络配置"; return }
        pendingProfile = id
        val noTun = AppState.profiles.value.firstOrNull { it.id == id }?.meta?.optBoolean("noTun") == true
        val permission = if (noTun) null else VpnService.prepare(this)
        if (permission != null) vpnPermission.launch(permission) else EasyTierVpnService.connect(this, id)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun pickConfig() = importConfig.launch(arrayOf("*/*"))
    fun export(profile: Profile) {
        exportText = profile.raw
        exportConfig.launch(profile.title.replace(Regex("[^\\p{L}\\p{N}_-]"), "_") + ".toml")
    }
    fun pickCore() = importCore.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    fun downloadCore(url: String) = work {
        val file = CoreUpdates.download(this@MainActivity, url)
        try { installCore(file) } finally { file.delete() }
    }
    private suspend fun installCore(file: File) {
        val version = CoreUpdates.install(this, file)
        withContext(Dispatchers.Main) {
            coreRevision++
            coreUpdate = CoreUpdateState(phase = UpdatePhase.READY)
            notice = "${CoreCatalog.displayVersion(version)} 已校验，可重新启动并应用"
        }
    }
    fun checkCoreUpdate() {
        if (busy) return
        val channel = coreUpdateChannel
        coreUpdate = coreUpdate.copy(phase = UpdatePhase.CHECKING, message = "")
        work {
            try {
                val state = CoreCatalog.check(this@MainActivity, channel)
                withContext(Dispatchers.Main) { coreUpdate = state }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main) { coreUpdate = coreUpdate.copy(phase = UpdatePhase.ERROR, message = error.message ?: "检查更新失败") }
            }
        }
    }
    fun selectCoreChannel(channel: CoreChannel) {
        if (busy || channel == coreUpdateChannel) return
        CoreUpdates.setChannel(this, channel)
        coreUpdateChannel = channel
        coreUpdate = CoreUpdateState()
        checkCoreUpdate()
    }
    fun downloadCoreUpdate() {
        if (busy) return
        val release = coreUpdate.release ?: return
        coreUpdate = coreUpdate.copy(phase = UpdatePhase.DOWNLOADING, message = "", downloaded = 0, total = release.size)
        work {
            try {
                val file = CoreUpdates.download(this@MainActivity, release.url) { received, total ->
                    runOnUiThread { coreUpdate = coreUpdate.copy(downloaded = received, total = if (total > 0) total else release.size) }
                }
                try {
                    check(file.length() == release.size && CoreUpdates.fileDigest(file).equals(release.sha256, true)) { "下载包校验失败，请重新下载" }
                    CoreUpdates.install(this@MainActivity, file, release.version, release.channel)
                } finally { file.delete() }
                withContext(Dispatchers.Main) { coreRevision++; coreUpdate = coreUpdate.copy(phase = UpdatePhase.READY) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main) { coreUpdate = coreUpdate.copy(phase = UpdatePhase.ERROR, message = error.message ?: "核心更新失败") }
            }
        }
    }
    fun restartCore() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                if (AppState.vpn.value.active) {
                    EasyTierVpnService.disconnect(this@MainActivity)
                    withTimeout(10_000) { AppState.vpn.first { !it.active } }
                }
                startActivity(Intent(this@MainActivity, CoreRestartActivity::class.java)
                    .putExtra("oldPid", android.os.Process.myPid())
                    .putExtra("expectedCore", CoreUpdates.version(this@MainActivity))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            } catch (error: Exception) {
                busy = false
                notice = error.message ?: "重新启动失败"
            }
        }
    }
    fun work(block: suspend CoroutineScope.() -> Unit) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO, block) }
            catch (error: Exception) { if (error is CancellationException) throw error; notice = error.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, EasyTierTileService::class.java), "EasyTier", Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { result -> notice = if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) "快捷磁贴已添加" else "可在通知栏编辑磁贴，拖入 EasyTier" }
        else notice = "下拉通知栏，编辑快捷设置，将 EasyTier 拖入磁贴区域"
    }
    fun batterySettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
    fun vpnSettings() { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
}
