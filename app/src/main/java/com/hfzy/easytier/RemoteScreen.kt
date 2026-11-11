@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.hfzy.easytier

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable fun RemoteScreen(activity: MainActivity, onClose: () -> Unit) {
    val prefs = remember { activity.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var address by remember { mutableStateOf(prefs.getString("remote", "tcp://")!!) }
    var instances by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf("") }
    var stop by remember { mutableStateOf<JSONObject?>(null) }
    var chooseLocal by remember { mutableStateOf(false) }
    fun request(action: String, id: String = "", raw: String = ""): JSONObject = NativeCore.request("remote", JSONObject().put("url", address).put("action", action).put("id", id).put("toml", raw).toString())
    fun refresh() = activity.work {
        val result = request("list").array("instances").objects()
        withContext(Dispatchers.Main) { instances = result; prefs.edit().putString("remote", address).apply() }
    }
    BackHandler { if (editing != null || creating) { editing = null; creating = false } else onClose() }
    if (chooseLocal) AlertDialog(onDismissRequest = { chooseLocal = false }, title = { Text("从本机配置启动") }, text = {
        Column {
            AppState.profiles.value.forEach { profile ->
                TextButton(onClick = {
                    chooseLocal = false
                    activity.work {
                        request("run", raw = profile.raw)
                        val result = request("list").array("instances").objects()
                        withContext(Dispatchers.Main) { instances = result; activity.notice = "远端网络已启动" }
                    }
                }) { Text(profile.title) }
            }
        }
    }, confirmButton = { TextButton(onClick = { chooseLocal = false }) { Text("取消") } })
    if (editing != null || creating) {
        ConfigEditor(editing, { editing = null; creating = false }) { _, raw ->
            activity.work {
                request("run", editing?.id.orEmpty(), raw)
                val result = request("list").array("instances").objects()
                withContext(Dispatchers.Main) { instances = result; editing = null; creating = false; activity.notice = "远端配置已应用" }
            }
        }
        return
    }
    if (details.isNotBlank()) AlertDialog(onDismissRequest = { details = "" }, title = { Text("远端状态") }, text = {
        androidx.compose.foundation.text.selection.SelectionContainer { Text(details, style = MaterialTheme.typography.bodySmall) }
    }, confirmButton = { TextButton(onClick = { details = "" }) { Text("关闭") } })
    if (stop != null) AlertDialog(onDismissRequest = { stop = null }, title = { Text("停止远端网络？") }, text = { Text(stop!!.text("name")) }, confirmButton = {
        TextButton(onClick = {
            val id = stop!!.text("id"); stop = null
            activity.work {
                request("stop", id)
                val result = request("list").array("instances").objects()
                withContext(Dispatchers.Main) { instances = result; activity.notice = "远端网络已停止，本机保存的配置可用于恢复" }
            }
        }) { Text("停止") }
    }, dismissButton = { TextButton(onClick = { stop = null }) { Text("取消") } })
    Column {
        TopAppBar(title = { Text("远程管理") }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, "返回") } }, actions = { IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "在远端新建网络") } })
        LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                OutlinedTextField(address, { address = it }, label = { Text("EasyTier RPC 地址") }, placeholder = { Text("tcp://10.42.0.1:15888") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { refresh() }, enabled = !activity.busy) { Text("连接 / 刷新") }
                TextButton(onClick = { chooseLocal = true }, enabled = !activity.busy && AppState.profiles.value.isNotEmpty()) { Text("从本机配置启动") }
            }
            instances.forEach { instance -> item(key = instance.text("id")) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(instance.text("name").ifBlank { instance.text("network") }, style = MaterialTheme.typography.titleMedium)
                        Text(instance.text("id"), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = {
                                activity.work {
                                    val raw = request("get", instance.text("id")).getString("toml")
                                    val metadata = NativeCore.request("inspect", raw)
                                    withContext(Dispatchers.Main) { editing = Profile(instance.text("id"), instance.text("name"), raw, metadata) }
                                }
                            }) { Text("编辑") }
                            TextButton(onClick = {
                                activity.work {
                                    val raw = request("get", instance.text("id")).getString("toml")
                                    ProfileStore(activity).save(instance.text("name"), raw)
                                    withContext(Dispatchers.Main) { activity.notice = "配置已保存到本机网络列表" }
                                }
                            }) { Text("保存到本机") }
                            TextButton(onClick = {
                                activity.work {
                                    val data = request("info", instance.text("id")).toString(2)
                                    withContext(Dispatchers.Main) { details = data }
                                }
                            }) { Text("状态") }
                            TextButton(onClick = {
                                // Retain a local recoverable copy before stopping a remote instance.
                                activity.work {
                                    val raw = request("get", instance.text("id")).getString("toml")
                                    ProfileStore(activity).save("远端备份 · " + instance.text("name"), raw)
                                    withContext(Dispatchers.Main) { stop = instance }
                                }
                            }) { Text("停止") }
                        }
                    }
                }
            } }
        }
    }
}
