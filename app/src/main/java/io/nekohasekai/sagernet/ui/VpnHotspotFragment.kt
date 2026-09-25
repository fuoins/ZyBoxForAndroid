package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// ZyBox: VPN 热点页 v6（参考 Mygod/VPNHotspot 实现思路）
// - 开关一律走 root shell 命令（cmd wifi / cmd tethering / svc），普通进程不触碰 TetheringManager，避免权限异常与闪退
// - 热点多级尝试：cmd wifi start-softap → cmd tethering start wifi → 反射 startTetheredHotspot → setWifiApEnabled
// - VPN 转发：MASQUERADE + FORWARD + DNS 重定向（53→8.8.8.8 使客户端 DNS 走隧道）
// - 管理系统共享：android.settings.TETHER_SETTINGS → Settings$TetherSettingsActivity（同 ManageBar）
class VpnHotspotFragment : ToolbarFragment(R.layout.layout_vpn_hotspot) {

    companion object {
        const val TUN_PATTERN = "tun[0-9]+|utun[0-9]+"
        const val IFACE_PATTERN = "ap0 softap0 wlan1 usb0 rndis0 bnep0 eth0 wlan0"
        const val TETHER_OFFLOAD = "tether_offload_disabled"

        fun rootAvailable(): Boolean {
            return try {
                val p = ProcessBuilder("/system/bin/su", "-c", "id").start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                out.contains("uid=0")
            } catch (_: Exception) {
                false
            }
        }

        fun rootExec(cmd: String): String? {
            return try {
                val p = ProcessBuilder("/system/bin/su", "-c", cmd).start()
                val out = p.inputStream.bufferedReader().readText() +
                    p.errorStream.bufferedReader().readText()
                p.waitFor()
                out.trim()
            } catch (_: Exception) {
                null
            }
        }

        // VPN 转发脚本（参考 VPNHotspot routing 思路的可落地子集）：
        // 转发 + NAT + 双向 FORWARD + DNS 重定向到 8.8.8.8（客户端 DNS 查询走隧道，避免被墙 DNS 污染）
        fun vpnHotspotScript(on: Boolean): String {
            val tunFind = "ip -o link show | grep -oE '" + TUN_PATTERN + "' | head -1"
            val ifaceFind = "for i in " + IFACE_PATTERN + "; do ip link show \$i >/dev/null 2>&1 && echo \$i && break; done"
            val rules = if (on) {
                // ① 对齐 VPNHotspot：downstream 流量 lookup netd 的 tun 接口表（1000+ifindex），优先级插系统 tethering 规则间
                "IFIDX=\$(cat /sys/class/net/\$TUN/ifindex 2>/dev/null); " +
                    "ip rule add iif \$IFACE priority 20700 lookup \$((1000+IFIDX)) 2>/dev/null; " +
                    // ② 兜底：自定义表 97 默认走 tun（netd 未建接口表时）
                    "ip route replace 0.0.0.0/0 dev \$TUN table 96 2>/dev/null; " +
                    "ip rule add iif \$IFACE priority 20701 lookup 96 2>/dev/null; " +
                    // ③ 防泄漏：VPN 路由缺失时 unreachable（绝不走蜂窝直连）
                    "ip rule add iif \$IFACE priority 20900 unreachable 2>/dev/null; " +
                    // ④ NAT 出口指向 VPN
                    "ndc nat enable \$IFACE \$TUN 0 2>/dev/null; " +
                    "ndc ipfwd enable \$IFACE 2>/dev/null; " +
                    "iptables -t nat -C POSTROUTING -o \$TUN -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -o \$TUN -j MASQUERADE; " +
                    // ⑤ 转发允许
                    "iptables -C FORWARD -i \$IFACE -o \$TUN -j ACCEPT 2>/dev/null || iptables -I FORWARD -i \$IFACE -o \$TUN -j ACCEPT; " +
                    "iptables -C FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || iptables -I FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; " +
                    // ⑥ DNS 走隧道
                    "iptables -t nat -C PREROUTING -i \$IFACE -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null || iptables -t nat -I PREROUTING -i \$IFACE -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53; " +
                    "iptables -t nat -C PREROUTING -i \$IFACE -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null || iptables -t nat -I PREROUTING -i \$IFACE -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53; " +
                    "echo RULE:; ip rule show | grep -E '20700|20701|20900'; echo ROUTE96:; ip route show table 96; " +
                    "ip route del 0.0.0.0/0 dev \$TUN table 97 2>/dev/null; echo CLEAN97_OK"
            } else {
                // 全局清扫（等价 daemon CleanRouting）：不依赖接口/隧道是否还存在，遍历所有可能接口与优先级，见啥删啥
                "while ip rule del priority 20700 2>/dev/null; do :; done; " +
                    "while ip rule del priority 20701 2>/dev/null; do :; done; " +
                    "while ip rule del priority 20800 2>/dev/null; do :; done; " +
                    "while ip rule del priority 20900 2>/dev/null; do :; done; " +
                    "ip route flush table 96 2>/dev/null; " +
                    "ip route flush table 97 2>/dev/null; " +
                    "for I in " + IFACE_PATTERN + "; do " +
                    "ndc nat disable \$I 2>/dev/null; " +
                    "ndc ipfwd disable \$I 2>/dev/null; " +
                    "iptables -t nat -D PREROUTING -i \$I -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null; " +
                    "iptables -t nat -D PREROUTING -i \$I -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null; " +
                    "iptables -D FORWARD -i \$I -o tun0 -j ACCEPT 2>/dev/null; " +
                    "iptables -D FORWARD -i tun0 -o \$I -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null; " +
                    "iptables -D FORWARD -i \$I -o tun1 -j ACCEPT 2>/dev/null; " +
                    "iptables -D FORWARD -i tun1 -o \$I -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null; " +
                    "done; " +
                    "for T in tun0 tun1 utun0 utun1; do " +
                    "iptables -t nat -D POSTROUTING -o \$T -j MASQUERADE 2>/dev/null; " +
                    "done; " +
                    "echo CLEAN_ALL_DONE"
            }
            return if (on) {
                "TUN=\$($tunFind); IFACE=\$($ifaceFind); " +
                    "[ -z \"\$TUN\" ] && echo TUN_MISS && exit 3; [ -z \"\$IFACE\" ] && echo IFACE_MISS && exit 4; " +
                    rules + "; echo IFACE=\$IFACE; echo DONE"
            } else {
                // 关闭：接口可能已消失，不清检查，逐条清理防残留
                "TUN=\$($tunFind); IFACE=\$($ifaceFind); " + rules + "; echo IFACE=\$IFACE; echo DONE"
            }
        }

        fun cleanupAtStartup() {
            rootExec(vpnHotspotScript(false))
        }
    }

    // ===== VPN 隧道等待轮询（没开VPN也能开VPN共享，隧道出现后自动补转发）=====
    private var vpnRetryJob: Job? = null

    private fun startVpnRetry() {
        stopVpnRetry()
        vpnRetryJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val tun = rootExec("ip -o link show | grep -oE '$TUN_PATTERN' | head -1") ?: ""
                    if (tun.isNotBlank()) {
                        val out = rootExec(vpnHotspotScript(true))
                        appendLog("VPN隧道 $tun 已出现，补配转发:\n$out")
                        break
                    }
                    delay(3000)
                } catch (_: Exception) {
                    delay(3000)
                }
            }
        }
    }

    private fun stopVpnRetry() {
        vpnRetryJob?.cancel()
        vpnRetryJob = null
    }

    // ===== 热点实时状态（SoftApCallback + TetheringEventCallback，学习 VPNHotspot 的状态识别）=====
    private var hotspotClientCount = 0
    private var hotspotInfo: Any? = null

    // 对齐 VPNHotspot softApInfoSummary：SoftApInfo(bssid/apInstanceIdentifier/frequency/bandwidth/wifiStandard/autoShutdownTimeoutMillis)
    // SoftApInfo.getBandwidth() 是 CHANNEL_WIDTH_ 常量（SoftApText.channelBandwidthLabel）：
    // 0=auto -1=invalid 1=20(no HT) 2=20 3=40 4=80 5=160(80+80) 6=160 7=2160 8=4320 9=6480 10=8640 11=320
    private fun channelWidthLabel(bw: Int): String = when (bw) {
        1 -> "20 MHz (no HT)"
        2 -> "20 MHz"
        3 -> "40 MHz"
        4 -> "80 MHz"
        5 -> "160 MHz (80+80)"
        6 -> "160 MHz"
        7 -> "2160 MHz"
        8 -> "4320 MHz"
        9 -> "6480 MHz"
        10 -> "8640 MHz"
        11 -> "320 MHz"
        else -> ""
    }

    // 对齐 VPNHotspot SoftApConfigurationCompat.frequencyToChannel
    private fun frequencyToChannel(freq: Int): Int = when {
        freq == 2484 -> 14
        freq < 2484 -> (freq - 2407) / 5
        freq in 4910..4980 -> (freq - 4000) / 5
        freq < 5925 -> (freq - 5000) / 5
        freq == 5935 -> 2
        freq <= 45000 -> (freq - 5950) / 5
        freq in 58320..70200 -> (freq - 56160) / 2160
        else -> 0
    }

    private fun formatInt(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n.toLong())

    private fun updateHotspotInfo() {
        try {
            val ssidView = view?.findViewById<TextView>(R.id.hotspot_status_ssid) ?: return
            val detailView = view?.findViewById<TextView>(R.id.hotspot_status_detail) ?: return
            var ssid = ""
            if (Build.VERSION.SDK_INT >= 30) try {
                val wm = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
                val cfg = WifiManager::class.java.getMethod("getSoftApConfiguration").invoke(wm)
                cfg?.let { ssid = it.javaClass.getMethod("getSsid").invoke(it) as? String ?: "" }
            } catch (_: Exception) { }
            val on = hotspotEnabled()
            val sb = StringBuilder()
            sb.append(if (on) "● 已开启" else "○ 已关闭")
            if (ssid.isNotBlank()) sb.append(" · ").append(ssid)
            ssidView.text = if (on) sb.toString() else "○ 未开启 · " + ssid.ifBlank { "—" }
            val det = StringBuilder()
            if (on) {
                // SoftApInfo 各字段（API 31+；getBssid 返回 MacAddress，需 toString）
                var bssid = ""
                var apId = ""
                var freq = 0
                var bw = 0
                var std = 0
                var idleMs = 0L
                hotspotInfo?.let { info ->
                    val cls = info.javaClass
                    try {
                        val mac = cls.getMethod("getBssid").invoke(info)
                        bssid = mac?.toString() ?: ""
                    } catch (_: Exception) { }
                    try { apId = cls.getMethod("getApInstanceIdentifier").invoke(info) as? String ?: "" } catch (_: Exception) { }
                    try { freq = cls.getMethod("getFrequency").invoke(info) as? Int ?: 0 } catch (_: Exception) { }
                    try { bw = cls.getMethod("getBandwidth").invoke(info) as? Int ?: 0 } catch (_: Exception) { }
                    try { std = cls.getMethod("getWifiStandard").invoke(info) as? Int ?: 0 } catch (_: Exception) { }
                    try { idleMs = (cls.getMethod("getAutoShutdownTimeoutMillis").invoke(info) as? Long) ?: 0L } catch (_: Exception) { }
                }
                // 回调缺失时 MAC 兜底（ip link）
                if (bssid.isBlank()) {
                    try {
                        val l = rootExec("ip link show ap0 2>/dev/null") ?: ""
                        Regex("link/ether ([0-9a-fA-F:]{17})").find(l)?.groupValues?.get(1)?.let { bssid = it }
                    } catch (_: Exception) { }
                }
                if (apId.isBlank()) apId = "ap0"
                val head = buildString {
                    if (bssid.isNotBlank()) append(bssid).append("%").append(apId).append(": ")
                    append(
                        when (std) {
                            4 -> "Wi-Fi 4"
                            5 -> "Wi-Fi 5"
                            6 -> "Wi-Fi 6"
                            7 -> "Wi-Fi 7"
                            else -> ""
                        }
                    )
                }
                det.append(head)
                if (freq > 0) {
                    if (det.isNotEmpty() && !det.toString().endsWith(": ")) det.append(", ")
                    det.append(formatInt(freq)).append(" MHz")
                    val ch = frequencyToChannel(freq)
                    if (ch > 0) det.append(", 频道 ").append(formatInt(ch))
                }
                val bwLabel = channelWidthLabel(bw)
                if (bwLabel.isNotBlank()) det.append(", 频宽 ").append(bwLabel)
                if (idleMs > 0) det.append(", 关闭延迟 ").append(idleMs / 60000).append("分钟")
                if (det.isNotEmpty()) det.append("\n")
                det.append(hotspotClientCount).append("/16 ").append(getString(R.string.vpn_hotspot_status_clients)).append("已连接")
            } else {
                det.append("—")
            }
            detailView.text = det.toString()
        } catch (_: Exception) { }
    }

    // VPN 共享：显示共享接口（ap0 等）的 IP 地址（IPv4 + IPv6）
    private fun refreshIpInfo() {
        try {
            val v = view?.findViewById<TextView>(R.id.vpn_ip_text) ?: return
            val iface = rootExec("for i in " + IFACE_PATTERN.split(" ").joinToString(" ") { "$it" } + "; do ip link show \$i >/dev/null 2>&1 && echo \$i && break; done") ?: ""
            val out = rootExec("ip -o addr show ${iface.trim()} 2>/dev/null") ?: ""
            val sb = StringBuilder()
            val v4 = Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+/\\d+)").find(out)?.groupValues?.get(1)
            val v6 = Regex("inet6 ([0-9a-fA-F:]+/\\d+)").findAll(out).map { it.groupValues.get(1) }
                .filter { !it.startsWith("fe80") && it != "::1/128" }.toList()
            if (v4 != null) sb.append("IPv4 ").append(v4)
            if (v6.isNotEmpty()) sb.append(if (sb.isNotEmpty()) "\n" else "").append("IPv6 ").append(v6.first())
            activity?.runOnUiThread { v.text = if (sb.isNotEmpty()) sb.toString() else "接口 ${iface.trim().ifBlank { "未识别" }} 无地址" }
        } catch (_: Exception) { }
    }

    private fun registerSoftApMonitor() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val wm = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
            val cbCls = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            val cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, args ->
                try {
                    when (m.name) {
                        "onConnectedClientsChanged" -> {
                            val list = args?.get(0) as? List<*> ?: emptyList<Any>()
                            hotspotClientCount = list.size
                        }
                        "onInfoChanged" -> {
                            val info = args?.get(0) ?: return@newProxyInstance null
                            hotspotInfo = info
                        }
                        "onStateChanged" -> {
                            hotspotClientCount = 0
                            hotspotInfo = null
                        }
                    }
                    updateHotspotInfo()
                } catch (_: Exception) { }
                null
            }
            wm.javaClass.getMethod("registerSoftApCallback", cbCls, android.os.Handler::class.java)
                .invoke(wm, cb, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (_: Exception) { }
    }

    private fun registerTetheringMonitor() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val tm = requireContext().getSystemService("tethering")
            val cbCls = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            val cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, _ ->
                try {
                    when (m.name) {
                        "onTetheringStarted", "onTetheringStopped", "onError", "onUpstreamChanged", "onInterfaceStateChanged" -> {
                            refreshSystemStateSafe()
                            updateHotspotInfo()
                        }
                    }
                } catch (_: Exception) { }
                null
            }
            tm.javaClass.getMethod("registerTetheringEventCallback", java.util.concurrent.Executor::class.java, cbCls)
                .invoke(tm, java.util.concurrent.Executors.newSingleThreadExecutor(), cb)
        } catch (_: Exception) { }
    }

    override fun onDestroyView() {
        stopVpnRetry()
        super.onDestroyView()
    }

    private fun refreshSystemStateSafe() {
        try {
            lifecycleScope.launch(Dispatchers.IO) {
                val h = hotspotEnabled()
                withContext(Dispatchers.Main) {
                    view?.findViewById<Switch>(R.id.hotspot_wifi_switch)?.isChecked = h
                }
            }
        } catch (_: Exception) { }
    }

    // ===== 页面日志（完整输出，防 snack 截断）=====
    private val logBuf = StringBuilder()
    private var logView: TextView? = null
    private fun appendLog(msg: String) {
        try {
            if (logBuf.length > 20000) logBuf.setLength(0)
            logBuf.append(msg).append("\n")
            val v = logView ?: return
            activity?.runOnUiThread {
                v.text = logBuf.toString()
                v.post { v.scrollTo(0, v.layout?.height ?: 0) }
            }
        } catch (_: Exception) { }
    }

    // ===== 状态读取（多源兜底，全部 try-catch）=====
    private fun hotspotEnabled(): Boolean {
        // 1) isTetheredHotspotEnabled (API 33+)
        try {
            val wm = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
            val m = WifiManager::class.java.getMethod("isTetheredHotspotEnabled")
            if (m.invoke(wm) as? Boolean == true) return true
        } catch (_: Exception) {
        }
        // 2) isWifiApEnabled（旧接口，Oplus 常见）
        try {
            val wm = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
            val m = WifiManager::class.java.getMethod("isWifiApEnabled")
            if (m.invoke(wm) as? Boolean == true) return true
        } catch (_: Exception) {
        }
        // 3) tethered ifaces 含 wlan0/ap0
        if (tetheredIfaces().any { it.contains("wlan") || it == "ap0" }) return true
        // 4) cmd wifi status 文本
        return try {
            val out = rootExec("cmd wifi status") ?: return false
            out.contains("SoftApState: STATE_ENABLED") ||
                out.contains("Wi-Fi AP state: enabled") ||
                out.contains("AP state: enabled") ||
                out.contains("ENABLED")
        } catch (_: Exception) {
            false
        }
    }

    private fun tetheredIfaces(): List<String> {
        return try {
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val m = ConnectivityManager::class.java.getMethod("getTetheredIfaces")
            (m.invoke(cm) as Array<*>).map { it.toString() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ===== 开关（一律 root shell 命令，多级尝试）=====
    // RootHelper（root 进程反射系统服务；启动方式对齐 librootkotlinx：
    // CLASSPATH=APK exec app_process -Xnoimage-dex2oat /system/bin --nice-name；runcon 处理 SELinux 域防 killed）
    private fun rootHelper(action: String): String? {
        return try {
            val apk = requireContext().applicationInfo.sourceDir
            val logf = "/data/local/tmp/zybox_rh.log"
            val base = "setenforce 0; CLASSPATH=$apk exec "
            val cmds = listOf(
                "$base app_process -Xnoimage-dex2oat /system/bin --nice-name=zybox-helper io.nekohasekai.sagernet.RootHelper $action",
                "$base runcon u:r:su:s0 app_process -Xnoimage-dex2oat /system/bin --nice-name=zybox-helper io.nekohasekai.sagernet.RootHelper $action",
                "$base app_process /system/bin io.nekohasekai.sagernet.RootHelper $action"
            )
            for (c in cmds) {
                // 输出重定向到文件再读（app_process stdout 经 su 传递不可靠，见 v16 空输出问题）
                val o = rootExec("$c > $logf 2>&1; cat $logf; rm -f $logf")
                appendLog("root: $action\n${o ?: "(null)"}")
                if (o != null && !o.contains("killed") && !cmdFailed(o)) return o
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // 命令输出是否失败（ColorOS/Android15 报错文本不能当作成功）
    private fun cmdFailed(out: String): Boolean {
        val s = out.lowercase()
        return s.contains("no shell command") || s.contains("invalid args") ||
            s.contains("unknown command") || s.contains("not found") ||
            s.contains("permission denied") || s.contains("exception") ||
            s.contains("error") || s.contains("usage:") || s.contains("fail")
    }

    // 热点：先普通进程 startTethering 并等待系统回调；
    // entitlement 豁免需 TETHER_PRIVILEGED（普通进程拿不到），onTetheringFailed/异常 → 自动 root 会话兜底（同 VPNHotspot）
    private fun setHotspot(on: Boolean): Pair<Boolean, String> {
        var started = false
        var failed = false
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            val tm = requireContext().getSystemService("tethering") ?: throw RuntimeException("no tether svc")
            val cbCls = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val exe = java.util.concurrent.Executors.newSingleThreadExecutor()
            val cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, _ ->
                when (m.name) {
                    "onTetheringStarted" -> { started = true; appendLog("TetheringManager: 热点已启动 (onTetheringStarted)") }
                    "onTetheringFailed" -> { failed = true; appendLog("TetheringManager: 热点启动失败 (onTetheringFailed)") }
                }
                try { latch.countDown() } catch (_: Throwable) { }
                null
            }
            if (on) {
                val bCls = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
                val builder = bCls.getConstructor(Int::class.java).newInstance(0) // TETHERING_WIFI
                try {
                    bCls.getMethod("setExemptFromEntitlementCheck", Boolean::class.java).invoke(builder, true)
                } catch (_: Throwable) { }
                try {
                    bCls.getMethod("setShouldShowEntitlementUi", Boolean::class.java).invoke(builder, false)
                } catch (_: Throwable) { }
                val req = bCls.getMethod("build").invoke(builder)
                tm.javaClass.getMethod("startTethering", req.javaClass, java.util.concurrent.Executor::class.java, cbCls)
                    .invoke(tm, req, exe, cb)
                appendLog("TetheringManager.startTethering(wifi) 已调用，等待系统回调 2s…")
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                if (started) return true to "startTethering 成功（系统回调确认）"
            } else {
                try {
                    tm.javaClass.getMethod("stopTethering", Int::class.java, java.util.concurrent.Executor::class.java, cbCls)
                        .invoke(tm, 0, exe, cb)
                } catch (e: NoSuchMethodException) {
                    tm.javaClass.getMethod("stopTethering", Int::class.java, String::class.java, java.util.concurrent.Executor::class.java, cbCls)
                        .invoke(tm, 0, requireContext().packageName, exe, cb)
                }
                appendLog("TetheringManager.stopTethering(wifi) 已调用，等待系统处理…")
                Thread.sleep(1200)
                if (!hotspotEnabled()) return true to "stopTethering 已生效"
                appendLog("普通进程关闭未生效 → root 会话兜底")
            }
        } catch (e: Exception) {
            appendLog("普通进程 startTethering 异常：${e.message}")
        }
        if (failed || !started) {
            appendLog("普通进程路径未成功（started=$started failed=$failed）→ root 会话兜底（entitlement 豁免需 TETHER_PRIVILEGED）")
            val o = rootHelper("tether wifi ${if (on) "on" else "off"}")
            if (o != null && !cmdFailed(o)) return true to o
            return false to (o ?: "roothelper 执行失败")
        }
        return false to "未收到系统回调"
    }

    // 是否有共享接口（热点/usb/蓝牙/以太网任一已建立）
    private fun shareIfaceExists(): Boolean {
        val out = rootExec("ip -o link show") ?: return false
        return IFACE_PATTERN.split(" ").any { out.contains(": $it:") }
    }

    // 管理系统共享：android.settings.TETHER_SETTINGS → Settings$TetherSettingsActivity（同 VPNHotspot ManageBar）
    private fun startTetherSettings() {
        var eSuppressed: RuntimeException? = null
        if (Build.VERSION.SDK_INT >= 30) try {
            startActivity(Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: RuntimeException) {
            eSuppressed = e
        }
        try {
            startActivity(Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"))
            return
        } catch (e: RuntimeException) {
            eSuppressed?.let { e.addSuppressed(it) }
        }
        try {
            startActivity(Intent("android.settings.SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val statusIcon = view.findViewById<TextView>(R.id.hotspot_root_status)
        val rootHint = view.findViewById<TextView>(R.id.hotspot_root_hint)
        val toggleState = view.findViewById<TextView>(R.id.hotspot_toggle_state)
        val toggleSwitch = view.findViewById<Switch>(R.id.hotspot_toggle_switch)
        val wifiSwitch = view.findViewById<Switch>(R.id.hotspot_wifi_switch)
        logView = view.findViewById<TextView>(R.id.hotspot_log)

        // 附近设备权限（Android 13+，VPNHotspot 开热点前也请求）
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requireActivity().requestPermissions(
                    arrayOf("android.permission.NEARBY_WIFI_DEVICES"), 0x5A17
                )
            } catch (_: Exception) { }
        }

        view.findViewById<View>(R.id.hotspot_log_clear)?.setOnClickListener {
            logBuf.setLength(0)
            logView?.text = ""
        }

        fun snack(msg: String) {
            try {
                (activity as? MainActivity)?.snackbar(msg)?.show()
            } catch (_: Exception) {
            }
        }

        fun refreshRoot() {
            statusIcon.text = "❌"
            rootHint.text = getString(R.string.vpn_hotspot_root_checking)
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = rootAvailable()
                withContext(Dispatchers.Main) {
                    statusIcon.text = if (ok) "✓" else "❌"
                    rootHint.text = if (ok) "Root" else "No Root"
                }
            }
        }

        // 防死循环：程序性设置 isChecked 时暂停 listener
        var suppressToggle = false
        fun refreshToggleState() {
            val on = DataStore.vpnHotspotEnabled
            suppressToggle = true
            toggleSwitch.isChecked = on
            toggleState.text = getString(if (on) R.string.vpn_hotspot_on else R.string.vpn_hotspot_off)
            suppressToggle = false
        }

        fun refreshSystemState() {
            lifecycleScope.launch(Dispatchers.IO) {
                val h = hotspotEnabled()
                withContext(Dispatchers.Main) {
                    wifiSwitch.isChecked = h
                }
            }
        }

        refreshRoot()
        refreshToggleState()
        refreshSystemState()
        registerSoftApMonitor()
        registerTetheringMonitor()
        updateHotspotInfo()

        view.findViewById<View>(R.id.hotspot_root_refresh).setOnClickListener {
            refreshRoot()
            refreshSystemState()
        }

        // VPN 热点开关（开启时自动禁用硬件加速；关闭时清转发并一并关热点）
        toggleSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // ZyBox: 开启 VPN 热点时若系统热点未开，自动开热点并等待共享接口出现
                    if (checked && !shareIfaceExists()) {
                        val (hok, hout) = setHotspot(true)
                        var waited = 0
                        while (!shareIfaceExists() && waited < 20) {
                            delay(500)
                            waited++
                        }
                        if (!shareIfaceExists() && !hok) {
                            appendLog("自动开启热点未成功: ${hout.take(200)}")
                        }
                    }
                    val out = rootExec(vpnHotspotScript(checked))
                    appendLog("转发脚本输出:\n$out")
                    if (!checked) {
                        // 真关闭：转发已清理，热点一并关闭
                        val (hok2, hout2) = setHotspot(false)
                        appendLog("VPN热点关闭时热点关闭: ok=$hok2 ${hout2.take(200)}")
                    }
                    withContext(Dispatchers.Main) {
                        when {
                            out == null -> {
                                suppressToggle = true
                                toggleSwitch.isChecked = !checked
                                suppressToggle = false
                            }
                            out.contains("IFACE_MISS") -> {
                                suppressToggle = true
                                toggleSwitch.isChecked = false
                                suppressToggle = false
                            }
                            else -> {
                                DataStore.vpnHotspotEnabled = checked
                                suppressToggle = true
                                toggleSwitch.isChecked = checked
                                suppressToggle = false
                                toggleState.text = getString(
                                    if (checked) R.string.vpn_hotspot_on else R.string.vpn_hotspot_off
                                )
                            }
                        }
                    }
                    if (checked && out != null && out.contains("TUN_MISS")) {
                        appendLog("VPN共享已开启，等待VPN隧道出现后自动补配转发…")
                        startVpnRetry()
                    } else {
                        stopVpnRetry()
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 热点独立开关：只开/关系统热点（转发由 VPN 热点 toggle 负责）
        wifiSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val (ok, out) = setHotspot(checked)
                    appendLog("热点开关: ok=$ok ${out.take(200)}")
                    delay(1500)
                    val now = hotspotEnabled()
                    withContext(Dispatchers.Main) {
                        if (now != checked) {
                            suppressToggle = true
                            wifiSwitch.isChecked = checked
                            suppressToggle = false
                        }
                    }
                    refreshIpInfo()
                    updateHotspotInfo()
                } catch (_: Exception) {
                }
            }
        }

        // 管理系统共享
        view.findViewById<View>(R.id.hotspot_manage).setOnClickListener {
            startTetherSettings()
        }
    }
}
