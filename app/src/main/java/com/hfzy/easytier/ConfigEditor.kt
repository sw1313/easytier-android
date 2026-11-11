@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.hfzy.easytier

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable fun ConfigEditor(profile: Profile?, onClose: () -> Unit, initialSection: String? = null, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(profile?.title ?: "新网络") }
    var raw by remember { mutableStateOf(profile?.raw ?: defaultConfig) }
    var doc by remember { mutableStateOf(NativeCore.request("decode", raw)) }
    var revision by remember { mutableIntStateOf(0) }
    var rawMode by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    fun serialize(): String = NativeCore.request("encode", doc.toString()).getString("toml")
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(title = { Text(if (profile == null) "新建网络" else "编辑网络") }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, "返回") } }, actions = {
            TextButton(onClick = {
                runCatching {
                    val config = if (rawMode) raw else serialize()
                    NativeCore.request("inspect", config)
                    onSave(title, config)
                }.onFailure { error = it.message.orEmpty() }
            }) { Text("保存") }
        })
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !rawMode, onClick = {
                if (rawMode) runCatching { NativeCore.request("decode", raw) }.onSuccess { doc = it; revision++; rawMode = false; error = "" }.onFailure { error = it.message.orEmpty() }
            }, label = { Text("可视化配置") }, leadingIcon = { Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)) })
            Spacer(Modifier.width(10.dp))
            FilterChip(selected = rawMode, onClick = {
                if (!rawMode) runCatching { serialize() }.onSuccess { raw = it; rawMode = true; error = "" }.onFailure { error = it.message.orEmpty() }
            }, label = { Text("TOML") }, leadingIcon = { Icon(Icons.Outlined.Code, null, Modifier.size(18.dp)) })
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
        if (rawMode) {
            OutlinedTextField(raw, { raw = it; error = "" }, Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        } else {
            val sectionIndex = configSections.indexOfFirst { it.title == initialSection }
            val scroll = rememberLazyListState(initialFirstVisibleItemIndex = if (sectionIndex >= 0) sectionIndex + 2 else 0)
            LazyColumn(state = scroll, contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { OutlinedTextField(title, { title = it }, label = { Text("显示名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(search, { search = it }, label = { Text("搜索配置项") }, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true) }
                configSections.forEachIndexed { index, section ->
                    item(key = section.title) {
                        val shown = if (search.isBlank()) section.fields else filterFields(section.fields, search)
                        if (shown.isNotEmpty()) {
                            var expanded by remember { mutableStateOf(index == 0 || section.title == initialSection) }
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                                    Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    Icon(if (expanded || search.isNotEmpty()) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                                }
                                if (expanded || search.isNotEmpty()) Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    @Suppress("UNUSED_VARIABLE") val observed = revision
                                    shown.forEach { spec -> FieldControl(doc, spec, section.title + "." + spec.key) { revision++; error = "" } }
                                }
                            }
                        }
                    }
                }
                item { Text("留空的可选项使用核心默认值。保存中的修改会在重新连接后应用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Button(onClick = { runCatching { serialize() }.onSuccess { error = "配置校验通过" }.onFailure { error = it.message.orEmpty() } }, modifier = Modifier.fillMaxWidth()) { Text("校验配置") } }
            }
        }
    }
}

private fun filterFields(fields: List<FieldSpec>, query: String): List<FieldSpec> = fields.mapNotNull { field ->
    if (field.label.contains(query, true) || field.key.contains(query, true)) field
    else filterFields(field.children, query).takeIf { it.isNotEmpty() }?.let { field.copy(children = it) }
}

@Composable private fun FieldControl(parent: JSONObject, spec: FieldSpec, path: String, onChange: () -> Unit) {
    when (spec.kind) {
        "object", "optional" -> {
            val existing = parent.optJSONObject(spec.key)
            var expanded by remember(path) { mutableStateOf(spec.kind == "object") }
            if (spec.kind == "optional") Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(existing != null, { enabled ->
                    if (enabled) {
                        val value = JSONObject()
                        if (spec.key == "vpn_portal_config") value.put("client_cidr", "10.20.0.0/24").put("wireguard_listen", "0.0.0.0:22022")
                        parent.put(spec.key, value)
                    } else parent.remove(spec.key)
                    expanded = enabled
                    onChange()
                })
            }
            if (spec.kind == "object" || (existing != null && expanded)) {
                spec.children.forEach { child ->
                    // Do not insert empty optional tables just by opening the editor.
                    val nested = existing ?: JSONObject()
                    FieldControl(nested, child, "$path.${child.key}") { parent.put(spec.key, nested); onChange() }
                }
            }
        }
        "objects" -> {
            val entries = parent.array(spec.key)
            Text(spec.label, style = MaterialTheme.typography.titleSmall)
            entries.objects().forEachIndexed { index, entry ->
                key(entry) {
                    var expanded by remember { mutableStateOf(false) }
                    OutlinedCard {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                                Text(entry.text("name").ifBlank { entry.text("uri") }.ifBlank { entry.text("cidr") }.ifBlank { "${spec.label} ${index + 1}" }, Modifier.weight(1f))
                                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                            }
                            IconButton(onClick = { entries.remove(index); parent.put(spec.key, entries); onChange() }) { Icon(Icons.Outlined.DeleteOutline, "移除") }
                        }
                        if (expanded) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            spec.children.forEach { FieldControl(entry, it, "$path.$index.${it.key}", onChange) }
                        }
                    }
                }
            }
            OutlinedButton(onClick = {
                val entry = JSONObject()
                when (spec.key) {
                    "peer" -> entry.put("uri", "udp://")
                    "port_forward" -> entry.put("proto", "tcp")
                    "chains" -> entry.put("name", "新规则链").put("enabled", true).put("chain_type", 1).put("default_action", 1).put("rules", JSONArray())
                    "rules" -> entry.put("name", "新规则").put("enabled", true).put("action", 1).put("protocol", 5).put("priority", 0).put("stateful", true)
                }
                entries.put(entry); parent.put(spec.key, entries); onChange()
            }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("添加${spec.label}") }
        }
        "bool" -> {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec.label, style = MaterialTheme.typography.bodyLarge)
                    Text(if (!parent.has(spec.key)) "默认${if (spec.hint.isBlank()) "" else " · ${spec.hint}"}" else spec.hint.ifBlank { spec.key }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (parent.has(spec.key)) IconButton(onClick = { parent.remove(spec.key); onChange() }, Modifier.size(32.dp)) { Icon(Icons.Outlined.RestartAlt, "恢复默认", Modifier.size(18.dp)) }
                Switch(parent.optBoolean(spec.key, defaultBoolean(spec.key)), { parent.put(spec.key, it); onChange() })
            }
        }
        else -> {
            val options = enumOptions(spec.key)
            if (options.isNotEmpty()) {
                var open by remember(path) { mutableStateOf(false) }
                val current = parent.text(spec.key)
                ExposedDropdownMenuBox(open, { open = it }) {
                    OutlinedTextField(options.firstOrNull { it.first == current }?.second ?: current.ifBlank { "使用默认值" }, {}, readOnly = true,
                        label = { Text(spec.label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
                    ExposedDropdownMenu(open, { open = false }) {
                        DropdownMenuItem(text = { Text("使用默认值") }, onClick = { parent.remove(spec.key); open = false; onChange() })
                        options.forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = {
                            parent.put(spec.key, if (spec.kind == "number") value.toInt() else value)
                            open = false; onChange()
                        }) }
                    }
                }
                return
            }
            var value by remember(parent, spec.key) { mutableStateOf(if (spec.kind == "list") parent.array(spec.key).strings().joinToString("\n") else parent.text(spec.key)) }
            var revealed by remember(path) { mutableStateOf(false) }
            OutlinedTextField(value, { text ->
                value = text
                when {
                    text.isBlank() -> parent.remove(spec.key)
                    spec.kind == "list" -> parent.put(spec.key, JSONArray(text.lines().map { it.trim() }.filter { it.isNotEmpty() }))
                    spec.kind == "number" -> text.toLongOrNull()?.let { parent.put(spec.key, it) }
                    else -> parent.put(spec.key, text)
                }
                onChange()
            }, modifier = Modifier.fillMaxWidth(), label = { Text(spec.label) },
                supportingText = if (spec.hint.isNotEmpty()) ({ Text(spec.hint) }) else null,
                singleLine = spec.kind != "list",
                minLines = if (spec.kind == "list") 2 else 1,
                isError = spec.kind == "number" && value.isNotEmpty() && value.toLongOrNull() == null,
                keyboardOptions = KeyboardOptions(keyboardType = if (spec.kind == "number") KeyboardType.Number else if (spec.kind == "secret") KeyboardType.Password else KeyboardType.Text),
                visualTransformation = if (spec.kind == "secret" && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon = if (spec.kind == "secret") ({ IconButton(onClick = { revealed = !revealed }) { Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "显示或隐藏密钥") } }) else null,
            )
        }
    }
}

private fun defaultBoolean(key: String) = key in setOf("dhcp", "enable_encryption", "enable_ipv6", "multi_thread", "bind_device")

private fun enumOptions(key: String): List<Pair<String, String>> = when (key) {
    "default_protocol" -> listOf("udp", "tcp", "quic", "wg", "ws", "wss").map { it to it.uppercase() }
    "proto" -> listOf("tcp" to "TCP", "udp" to "UDP")
    "protocol" -> listOf("0" to "未指定", "1" to "TCP", "2" to "UDP", "3" to "ICMP", "4" to "ICMPv6", "5" to "任意协议")
    "action", "default_action" -> listOf("0" to "继续匹配", "1" to "允许", "2" to "丢弃")
    "chain_type" -> listOf("1" to "入站", "2" to "出站", "3" to "转发")
    "data_compress_algo" -> listOf("0" to "关闭", "1" to "Zstd")
    "encryption_algorithm" -> listOf("aes-gcm", "aes-256-gcm", "chacha20", "xor").map { it to it }
    else -> emptyList()
}
