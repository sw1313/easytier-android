package com.hfzy.easytier

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray

@Composable fun ExitNodeDialog(activity: MainActivity, profile: Profile, vpn: VpnState, onClose: () -> Unit) {
    val doc = remember(profile.raw) { NativeCore.request("decode", profile.raw) }
    val prefs = remember { activity.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var selected by remember { mutableStateOf(doc.array("exit_nodes").strings().firstOrNull().orEmpty()) }
    var dns by remember { mutableStateOf(prefs.getString("exitDns:${profile.id}", "223.5.5.5")!!) }
    var manual by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val nodes = vpn.snapshot.array("nodes").objects().filter { it.text("ip").isNotBlank() }
    AlertDialog(onDismissRequest = onClose, title = { Text("出口节点") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("选择互联网流量的出口。对端需要启用“允许作为出口节点”。", style = MaterialTheme.typography.bodySmall)
            ExitChoice("组网访问", "使用手机当前网络访问互联网", selected.isBlank()) { selected = ""; manual = "" }
            nodes.forEach { node ->
                val ip = node.text("ip").substringBefore('/')
                ExitChoice(node.text("name").ifBlank { ip }, ip, selected == ip) { selected = ip; manual = "" }
            }
            if (selected.isNotBlank() && nodes.none { it.text("ip").substringBefore('/') == selected } && manual.isBlank()) {
                ExitChoice("当前配置的出口", selected, true) { }
            }
            OutlinedTextField(manual, { manual = it; selected = it.trim(); error = "" }, modifier = Modifier.fillMaxWidth(),
                label = { Text("自定义出口 IPv4（可选）") }, singleLine = true)
            if (selected.isNotBlank()) {
                OutlinedTextField(dns, { dns = it; error = "" }, label = { Text("出口模式 DNS") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("IPv4 流量和 DNS 查询通过所选出口，切换会重新建立 VPN。", style = MaterialTheme.typography.bodySmall)
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(onClick = {
            runCatching {
                if (selected.isNotBlank()) {
                    require(validIpv4(selected)) { "请填写有效的出口 IPv4 地址" }
                    require(validIpv4(dns.trim())) { "请填写有效的 DNS IPv4 地址" }
                    doc.put("exit_nodes", JSONArray().put(selected))
                } else doc.remove("exit_nodes")
                val raw = NativeCore.request("encode", doc.toString()).getString("toml")
                ProfileStore(activity).save(profile.title, raw, profile.id)
                prefs.edit().putString("exitDns:${profile.id}", dns.trim()).apply()
                if (vpn.active && vpn.profileId == profile.id) EasyTierVpnService.connect(activity, profile.id, restart = true)
                activity.notice = if (selected.isBlank()) "已切换为组网访问" else "出口节点已设为 $selected"
                onClose()
            }.onFailure { error = it.message ?: "保存失败" }
        }) { Text(if (vpn.active && vpn.profileId == profile.id) "保存并重连" else "保存") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

@Composable private fun ExitChoice(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun validIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && (part.toIntOrNull() ?: -1) in 0..255 }
}
