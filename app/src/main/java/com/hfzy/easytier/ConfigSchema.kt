package com.hfzy.easytier

data class FieldSpec(
    val key: String,
    val label: String,
    val kind: String = "text",
    val hint: String = "",
    val children: List<FieldSpec> = emptyList(),
)
data class ConfigSection(val title: String, val fields: List<FieldSpec>)

private fun flag(key: String, label: String, hint: String = "") = FieldSpec(key, label, "bool", hint)
private fun list(key: String, label: String, hint: String = "") = FieldSpec(key, label, "list", hint)
private fun number(key: String, label: String, hint: String = "") = FieldSpec(key, label, "number", hint)

val aclRuleFields = listOf(
    FieldSpec("name", "规则名称"), FieldSpec("description", "说明"),
    number("priority", "优先级"), flag("enabled", "启用"),
    number("protocol", "协议", "0 未指定 · 1 TCP · 2 UDP · 3 ICMP · 4 ICMPv6 · 5 任意"),
    list("ports", "目标端口", "每行一个端口或范围"), list("source_ports", "来源端口"),
    list("source_ips", "来源地址 / CIDR"), list("destination_ips", "目标地址 / CIDR"),
    list("source_groups", "来源组"), list("destination_groups", "目标组"),
    number("action", "动作", "0 继续 · 1 允许 · 2 丢弃"),
    number("rate_limit", "速率限制"), number("burst_limit", "突发限制"), flag("stateful", "有状态跟踪"),
)

val configSections = listOf(
    ConfigSection("基本设置", listOf(
        FieldSpec("instance_name", "配置名称"), FieldSpec("hostname", "主机名"),
        flag("dhcp", "自动分配 IPv4"), FieldSpec("ipv4", "虚拟 IPv4", hint = "例如 10.42.0.4/24"),
        FieldSpec("ipv6", "虚拟 IPv6"),
        FieldSpec("network_identity", "网络身份", "object", children = listOf(
            FieldSpec("network_name", "网络名称"), FieldSpec("network_secret", "网络密钥", "secret"),
        )),
        FieldSpec("peer", "初始节点", "objects", children = listOf(
            FieldSpec("uri", "节点地址", hint = "udp://服务器:11010"), FieldSpec("peer_public_key", "节点公钥（可选）"),
        )),
    )),
    ConfigSection("网络与路由", listOf(
        list("listeners", "监听地址", "每行一个，支持 tcp / udp / wg / ws / wss / quic"),
        list("mapped_listeners", "公网映射监听地址"),
        FieldSpec("proxy_network", "子网代理", "objects", children = listOf(
            FieldSpec("cidr", "子网 CIDR"), FieldSpec("mapped_cidr", "映射 CIDR（可选）"), list("allow", "允许的网络"),
        )),
        list("routes", "手动路由", "留空时学习对端发布的路由"), list("exit_nodes", "出口节点 IPv4"),
        flag("ipv6_public_addr_auto", "自动配置公网 IPv6"), flag("ipv6_public_addr_provider", "提供公网 IPv6 前缀"),
        FieldSpec("ipv6_public_addr_prefix", "公网 IPv6 前缀"),
        list("stun_servers", "IPv4 STUN 服务器"), list("stun_servers_v6", "IPv6 STUN 服务器"),
        list("tcp_whitelist", "TCP 白名单"), list("udp_whitelist", "UDP 白名单"),
    )),
    ConfigSection("连接与性能", listOf(FieldSpec("flags", "连接参数", "object", children = listOf(
        FieldSpec("default_protocol", "默认传输协议", hint = "tcp / udp / quic / wg / ws / wss"),
        number("mtu", "MTU", "手机推荐 1360"), flag("latency_first", "优先低延迟路径"),
        flag("enable_ipv6", "启用 IPv6 传输"), flag("disable_p2p", "禁用 P2P"),
        flag("p2p_only", "仅使用 P2P"), flag("lazy_p2p", "按需建立 P2P"), flag("need_p2p", "强制尝试 P2P"),
        flag("disable_tcp_hole_punching", "禁用 TCP 打洞"), flag("disable_udp_hole_punching", "禁用 UDP 打洞"),
        flag("disable_sym_hole_punching", "禁用对称 NAT 打洞"), flag("disable_upnp", "禁用 UPnP"),
        flag("enable_kcp_proxy", "启用 KCP 代理"), flag("disable_kcp_input", "拒绝 KCP 输入"),
        flag("enable_quic_proxy", "启用 QUIC 代理"), flag("disable_quic_input", "拒绝 QUIC 输入"),
        number("quic_listen_port", "QUIC 监听端口"), flag("multi_thread", "多线程运行"),
        number("multi_thread_count", "工作线程数"), number("instance_recv_bps_limit", "接收限速（字节/秒）"),
        number("data_compress_algo", "数据压缩", "0 关闭 · 1 Zstd"),
    )))),
    ConfigSection("安全与中转", listOf(
        FieldSpec("flags", "安全参数", "object", children = listOf(
            flag("enable_encryption", "启用加密"), FieldSpec("encryption_algorithm", "加密算法", hint = "aes-gcm / aes-256-gcm / chacha20 / xor"),
            flag("private_mode", "私有网络模式"), FieldSpec("relay_network_whitelist", "允许中转的网络", hint = "* 或逗号分隔网络名"),
            flag("relay_all_peer_rpc", "中转全部节点 RPC"), flag("disable_relay_data", "禁止转发数据"),
            flag("disable_relay_kcp", "禁止 KCP 中转"), flag("disable_relay_quic", "禁止 QUIC 中转"),
            flag("enable_relay_foreign_network_kcp", "允许其他网络 KCP 中转"),
            flag("enable_relay_foreign_network_quic", "允许其他网络 QUIC 中转"),
            number("foreign_relay_bps_limit", "跨网络中转限速（字节/秒）"),
        )),
        FieldSpec("secure_mode", "安全模式", "object", children = listOf(
            flag("enabled", "启用安全模式"), FieldSpec("local_private_key", "本机私钥", "secret"), FieldSpec("local_public_key", "本机公钥"),
        )), FieldSpec("credential_file", "凭据文件路径"),
    )),
    ConfigSection("代理与服务", listOf(
        FieldSpec("socks5_proxy", "SOCKS5 监听地址", hint = "socks5://0.0.0.0:1080"),
        FieldSpec("vpn_portal_config", "WireGuard VPN 门户", "optional", children = listOf(
            FieldSpec("client_cidr", "客户端网段", hint = "10.20.0.0/24"),
            FieldSpec("wireguard_listen", "监听地址", hint = "0.0.0.0:22022"),
        )),
        FieldSpec("port_forward", "端口转发", "objects", children = listOf(
            FieldSpec("proto", "协议", hint = "tcp / udp"), FieldSpec("bind_addr", "本机监听", hint = "0.0.0.0:8080"),
            FieldSpec("dst_addr", "目标地址", hint = "192.168.1.10:3389"),
        )),
        FieldSpec("flags", "系统集成", "object", children = listOf(
            flag("accept_dns", "使用 Magic DNS"), FieldSpec("tld_dns_zone", "Magic DNS 域名后缀"),
            flag("enable_exit_node", "允许作为出口节点"), flag("use_smoltcp", "使用用户态网络栈"),
            flag("enable_udp_broadcast_relay", "中转 UDP 广播"), flag("no_tun", "无 TUN 模式"),
            flag("bind_device", "绑定传输网卡", "Android 传输由 VpnService 排除自身流量"),
            flag("proxy_forward_by_system", "使用系统转发", "需要设备具备系统转发权限"), FieldSpec("dev_name", "虚拟网卡名称"),
        )),
    )),
    ConfigSection("访问控制 ACL", listOf(
        FieldSpec("acl", "访问控制", "object", children = listOf(
            FieldSpec("acl_v1", "ACL v1", "object", children = listOf(
                FieldSpec("chains", "规则链", "objects", children = listOf(
                    FieldSpec("name", "规则链名称"), FieldSpec("description", "说明"), flag("enabled", "启用"),
                    number("chain_type", "方向", "1 入站 · 2 出站 · 3 转发"),
                    number("default_action", "默认动作", "0 继续 · 1 允许 · 2 丢弃"),
                    FieldSpec("rules", "规则", "objects", children = aclRuleFields),
                )),
                FieldSpec("group", "访问组", "object", children = listOf(
                    list("members", "加入的组"), FieldSpec("declares", "声明的组", "objects", children = listOf(
                        FieldSpec("group_name", "组名"), FieldSpec("group_secret", "组密钥", "secret"),
                    )),
                )),
            )),
        )),
    )),
)
