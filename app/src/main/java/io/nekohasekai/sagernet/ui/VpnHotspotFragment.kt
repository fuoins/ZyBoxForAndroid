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
// - VPN 转发：对齐 routing.rs（20700 lookup 1000+ifindex / 20900 unreachable / ndc nat+ipfwd / MASQUERADE / FORWARD）
// - 管理系统共享：android.settings.TETHER_SETTINGS → Settings$TetherSettingsActivity（同 ManageBar）
class VpnHotspotFragment : ToolbarFragment(R.layout.layout_vpn_hotspot) {

    companion object {
        const val TUN_PATTERN = "tun[0-9]+|utun[0-9]+"
        const val IFACE_PATTERN = "ap0 softap0 wlan1 usb0 rndis0 bnep0 eth0 wlan0"
        const val TETHER_OFFLOAD = "tether_offload_disabled"

        fun rootAvailable(): Boolean {
            return try {
                val o = rootExec("id")
                o?.contains("uid=0") == true
            } catch (_: Exception) {
                false
            }
        }

        // 对齐 librootkotlinx RootProcessLauncher.startRootShell：裸 su（无 -c），命令写 stdin。
        // su -c 对含 ;/exec/重定向的长命令在 ColorOS 上不可靠（命令静默失效→输出全空，v16-v29 根因），
        // stdin 写入是 libsu/librootkotlinx 验证过的兼容方式。
        fun rootExec(cmd: String): String? {
            return try {
                val p = ProcessBuilder("/system/bin/su").start()
                try {
                    p.outputStream.write((cmd + "\nexit\n").toByteArray())
                    p.outputStream.flush()
                } catch (_: Exception) { }
                // stdout/stderr 并发读，避免管道缓冲填满死锁
                val err = StringBuilder()
                val t = Thread { err.append(p.errorStream.bufferedReader().readText()) }
                t.start()
                val out = p.inputStream.bufferedReader().readText()
                t.join(5000)
                p.waitFor()
                (out + err).trim()
            } catch (_: Exception) {
                null
            }
        }

        // VPN 转发脚本（参考 VPNHotspot routing 思路的可落地子集）：
        // 转发 + NAT + 双向 FORWARD（对齐 routing.rs desired_mutations）
        fun vpnHotspotScript(on: Boolean): String {
            val tunFind = "ip -o link show | grep -oE '" + TUN_PATTERN + "' | head -1"
            val ifaceFind = "for i in " + IFACE_PATTERN + "; do ip link show \$i >/dev/null 2>&1 && echo \$i && break; done"
            val rules = if (on) {
                // 对齐 routing.rs：upstream 规则 lookup netd 为 tun 建的接口表（1000+ifindex），
                // 优先级 20700（API>=12 系统 tethering 规则 20000/21000 之间）+ 20900 防泄漏 unreachable；
                // NAT 出口 = ndc nat enable（NetdNat）、ndc ipfwd enable（IpForward），不碰全局 sysctl
                "IFIDX=\$(cat /sys/class/net/\$TUN/ifindex 2>/dev/null); " +
                    "ip rule add iif \$IFACE priority 20700 lookup \$((1000+IFIDX)) 2>/dev/null; " +
                    "ip rule add iif \$IFACE priority 20900 unreachable 2>/dev/null; " +
                    "ndc nat enable \$IFACE \$TUN 0 2>/dev/null; " +
                    "ndc ipfwd enable \$IFACE 2>/dev/null; " +
                    "iptables -t nat -C POSTROUTING -o \$TUN -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -o \$TUN -j MASQUERADE; " +
                    "iptables -C FORWARD -i \$IFACE -o \$TUN -j ACCEPT 2>/dev/null || iptables -I FORWARD -i \$IFACE -o \$TUN -j ACCEPT; " +
                    "iptables -C FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || iptables -I FORWARD -i \$TUN -o \$IFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; " +
                    "echo RULE:; ip rule show | grep -E '20700|20900'; echo DONE"
            } else {
                // 全局清扫（等价 daemon CleanRouting）：不依赖接口/隧道是否还存在，遍历所有可能接口与优先级，见啥删啥。
                // 20701/表96/97 仅做兼容清理（旧版本曾添加），新版本不再创建
                "while ip rule del priority 20700 2>/dev/null; do :; done; " +
                    "while ip rule del priority 20701 2>/dev/null; do :; done; " +
                    "while ip rule del priority 20800 2>/dev/null; do :; done; " +
                    "while ip rule del priority 20900 2>/dev/null; do :; done; " +
                    "ip route flush table 96 2>/dev/null; " +
                    "ip route flush table 97 2>/dev/null; " +
                    "for I in " + IFACE_PATTERN + "; do " +
                    "ndc nat disable \$I 2>/dev/null; " +
                    "ndc ipfwd disable \$I 2>/dev/null; " +
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
    private var hotspotMaxClients = 0
    private var hotspotStateText = ""
    private var hotspotInfo: Any? = null
    private var cachedApMac: String? = null

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
            // 状态文本：过渡态用回调文本（正在开启/正在关闭），稳定态直接用实际开关状态
            val st = view?.findViewById<TextView>(R.id.hotspot_wifi_state)
            val transition = hotspotStateText == "正在开启…" || hotspotStateText == "正在关闭…" ||
                hotspotStateText == "开启失败"
            try {
                st?.text = when {
                    transition -> hotspotStateText
                    on -> "已开启"
                    else -> "已关闭"
                }
            } catch (_: Exception) { }
            ssidView.text = if (on) {
                if (ssid.isNotBlank()) "● 已开启 · $ssid" else "● 已开启"
            } else {
                "未开启"
            }
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
                // bssid 兜底走后台（rootExec 阻塞不能上主线程，v31 同步慢元凶）
                if (bssid.isBlank()) {
                    val cached = cachedApMac
                    if (cached != null) bssid = cached
                    else lifecycleScope.launch(Dispatchers.IO) {
                        val l = rootExec("ip link show ap0 2>/dev/null") ?: ""
                        Regex("link/ether ([0-9a-fA-F:]{17})").find(l)?.groupValues?.get(1)?.let {
                            cachedApMac = it
                            activity?.runOnUiThread { updateHotspotInfo() }
                        }
                    }
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
                det.append(if (hotspotClientCount >= 0) hotspotClientCount.toString() else "—")
                    .append("/").append(if (hotspotMaxClients > 0) hotspotMaxClients else 16)
                    .append(" ").append(getString(R.string.vpn_hotspot_status_clients)).append("已连接")
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
                        // 对齐 VPNHotspot WifiApManager：客户端数主源是隐藏回调 onNumClientsChanged(int)
                        "onNumClientsChanged" -> {
                            hotspotClientCount = args?.get(0) as? Int ?: 0
                        }
                        "onConnectedClientsChanged" -> {
                            val list = args?.get(0) as? List<*> ?: emptyList<Any>()
                            hotspotClientCount = list.size
                        }
                        "onInfoChanged" -> {
                            val info = args?.get(0) ?: return@newProxyInstance null
                            hotspotInfo = info
                        }
                        "onCapabilityChanged" -> {
                            val cap = args?.get(0) ?: return@newProxyInstance null
                            try { hotspotMaxClients = cap.javaClass.getMethod("getMaxSupportedClients").invoke(cap) as? Int ?: 0 } catch (_: Exception) { }
                        }
                        "onStateChanged" -> {
                            hotspotClientCount = 0
                            hotspotInfo = null
                            hotspotMaxClients = 0
                            var state = -1
                            try {
                                state = args?.get(0) as? Int ?: -1
                                hotspotStateText = when (state) {
                                    10 -> "正在关闭…"
                                    11 -> "已关闭"
                                    12 -> "正在开启…"
                                    13 -> "已开启"
                                    14 -> "开启失败"
                                    else -> "未知状态"
                                }
                            } catch (_: Exception) { }
                            // 系统热点状态变化 → 同步 wifiSwitch 按钮（系统手动开关热点时按钮跟随）
                            try { if (state == 13 || state == 11) refreshSystemStateSafe() } catch (_: Exception) { }
                        }
                    }
                    try {
                        val act = activity
                        if (act != null) act.runOnUiThread { updateHotspotInfo() } else updateHotspotInfo()
                    } catch (_: Exception) { }
                } catch (_: Exception) { }
                null
            }
            try {
                // 对齐 VPNHotspot WifiApManager：registerSoftApCallback 是 hidden 方法，用 getDeclaredMethod（getMethod 拿不到）
                val reg = if (Build.VERSION.SDK_INT >= 30) {
                    wm.javaClass.getDeclaredMethod("registerSoftApCallback", java.util.concurrent.Executor::class.java, cbCls)
                } else {
                    wm.javaClass.getDeclaredMethod("registerSoftApCallback", cbCls, android.os.Handler::class.java)
                }
                reg.isAccessible = true
                if (Build.VERSION.SDK_INT >= 30) {
                    reg.invoke(wm, java.util.concurrent.Executors.newSingleThreadExecutor(), cb)
                } else {
                    reg.invoke(wm, cb, android.os.Handler(android.os.Looper.getMainLooper()))
                }
            } catch (_: NoSuchMethodException) {
                // 兜底：公开方法
                try {
                    wm.javaClass.getMethod("registerSoftApCallback", java.util.concurrent.Executor::class.java, cbCls)
                        .invoke(wm, java.util.concurrent.Executors.newSingleThreadExecutor(), cb)
                } catch (_: Throwable) { }
            }
        } catch (_: Exception) { }
    }

    private fun registerTetheringMonitor() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val tm = requireContext().getSystemService("tethering")
            val cbCls = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            val cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, a ->
                try {
                    when (m.name) {
                        // 对齐 VPNHotspot TetheringManagerCompat LegacyCallback：客户端数走 TetheringEventCallback.onClientsChanged
                        "onClientsChanged" -> {
                            val clients = a?.get(0)
                            hotspotClientCount = try {
                                (clients as? Collection<*>)?.size ?: (clients as? List<*>)?.size ?: 0
                            } catch (_: Exception) { 0 }
                            updateHotspotInfo()
                        }
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
            // app_process 用绝对路径（对齐 librootkotlinx AppProcess.myExe=/proc/self/exe→app_process64）：
            // su 的 PATH 常不含 /system/bin，裸 app_process 会静默失败（v27 输出全空根因）；app_process64 优先，回退 app_process
            val ap64 = "/system/bin/app_process64"
            // 2 个变体即可（4 个变体每个都是数秒级启动失败，开关响应太慢）：
            // 主：app_process64；备：runcon（SELinux 域转换，防 killed）
            val cmds = listOf(
                "$base $ap64 -Xnoimage-dex2oat /system/bin --nice-name=zybox-helper io.nekohasekai.sagernet.RootHelper $action",
                "$base runcon u:r:su:s0 $ap64 -Xnoimage-dex2oat /system/bin --nice-name=zybox-helper io.nekohasekai.sagernet.RootHelper $action"
            )
            for (c in cmds) {
                // stdin 模式下 app_process stdout 随 root shell 会话可靠回传（对齐 librootkotlinx stdio attached）
                val o = rootExec(c)
                appendLog("root: $action\n${o ?: "(null)"}")
                if (o != null && o.contains("killed")) continue
                val code = Regex("RH_RESULT=(\\d)").find(o ?: "")?.groupValues?.get(1)?.toIntOrNull()
                if (code == 0) return o          // 系统回调确认成功
                // 输出传回失败/为空 → 查实际状态兜底：
                // 开启=热点已建；关闭=热点已关（真成功也判成功，不因输出丢失误杀）
                if (o == null || o.isBlank()) {
                    val stateOk = if (action.startsWith("tether wifi on")) {
                        hotspotEnabled() || shareIfaceExists()
                    } else {
                        !hotspotEnabled()
                    }
                    if (stateOk) return "STATE_OK $action"
                }
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
                    "onTetheringStopped" -> { started = true; appendLog("TetheringManager: 热点已关闭 (onTetheringStopped)") }
                    "onTetheringFailed" -> { failed = true; appendLog("TetheringManager: 热点启动失败 (onTetheringFailed)") }
                }
                try { latch.countDown() } catch (_: Throwable) { }
                null
            }
            if (on) {
                // 对齐源码 TetheringScreen toggle：startTethering 官方要求 WRITE_SETTINGS，
                // 先检查 Settings.System.canWrite，没有则跳转系统设置授权（同 VPNHotspot 行为）
                if (!android.provider.Settings.System.canWrite(requireContext())) {
                    appendLog("缺少 WRITE_SETTINGS 权限（TetheringManager.startTethering 必需）→ 跳转系统设置授权")
                    try {
                        requireContext().startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                android.net.Uri.parse("package:${requireContext().packageName}")
                            )
                        )
                    } catch (_: Exception) { }
                    return false to "需要 WRITE_SETTINGS 权限（已跳转系统设置，授权后重新打开）"
                }
                // 对齐源码 startTethering(type, showProvisioningUi)：
                // 1) 普通进程 exempt=true + showUi=true（VPNHotspot TetheringScreen 传 true）
                val (ok1, msg1) = tryStartTethering(true)
                if (ok1) return true to msg1
                // 对齐源码：仅 NO_CHANGE_TETHERING_PERMISSION 才继续降级，其他错误码直接失败
                val nc = Regex("code=(\\d+)").find(msg1)?.groupValues?.get(1)?.toIntOrNull()
                if (nc != null && nc != noChangeTetheringPermission()) {
                    appendLog("普通进程 startTethering 失败且错误码非 NO_CHANGE_TETHERING_PERMISSION（$msg1）→ 直接失败")
                    return false to msg1
                }
                appendLog("普通进程 startTethering(exempt=true) 失败（$msg1）→ 先试不豁免降级（响应快，多数 ColorOS 直接通过）")
                // 2) 不豁免降级：走正常 entitlement 流程（ColorOS 无运营商限制时直接通过；源码顺序 root→不豁免，
                //    这里提前不豁免仅为提速——root 兜底仍保留在最后，最终路径集合与源码一致）
                val (ok3, msg3) = tryStartTethering(false)
                if (ok3) return true to msg3
                appendLog("普通进程 startTethering(不豁免) 失败（$msg3）→ root 会话兜底")
                // 3) root 兜底（exempt=true，root 进程 uid=0 可绕过 entitlement，对齐 TetheringCommands.Start）
                val o = rootHelper("tether wifi on ${requireContext().packageName}")
                if (o != null && !cmdFailed(o)) return true to o
                return false to "全部开启路径失败（exempt=true→exempt=false→root）: $msg3"
            } else {
                // 对齐源码 stopTethering：ITetheringConnector.stopTethering(type, opPackageName, resultListener)
                // （不依赖 ColorOS 上不存在的 TetheringManager.stopTethering 签名）
                val (cok, cout) = stopViaConnector()
                if (cok) return true to cout
                appendLog("普通进程 connector 关闭未生效（$cout）→ root 会话兜底")
            }
        } catch (e: Exception) {
            appendLog("普通进程 startTethering 异常：${e.message}")
        }
        if (failed || !started) {
            appendLog("普通进程路径未成功（started=$started failed=$failed）→ root 会话兜底（entitlement 豁免需 TETHER_PRIVILEGED）")
            val o = rootHelper("tether wifi ${if (on) "on" else "off"} ${requireContext().packageName}")
            if (o != null && !cmdFailed(o)) return true to o
            // 最后降级：legacy ConnectivityManager.stopTethering(0)（对齐源码 stopTetheringLegacy）
            if (!on) {
                appendLog("root 兜底未生效 → legacy stopTethering 兜底")
                return try {
                    val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val m = ConnectivityManager::class.java.getDeclaredMethod("stopTethering", Int::class.java)
                    m.isAccessible = true
                    m.invoke(cm, 0)
                    Thread.sleep(1200)
                    if (!hotspotEnabled()) true to "legacy stopTethering 已生效" else false to "legacy 未生效"
                } catch (_: Exception) {
                    false to "legacy 调用失败"
                }
            }
            return false to (o ?: "roothelper 执行失败")
        }
        return false to "未收到系统回调"
    }

    // 权限状态刷新（附近的设备 + WRITE_SETTINGS——通知/VPN 权限在"权限与初始化"环节获取）
    private fun refreshPerms() {
        val view = view ?: return
        val nearby = if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(), "android.permission.NEARBY_WIFI_DEVICES"
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        view.findViewById<TextView>(R.id.hotspot_perm_nearby_status).text = if (nearby) "✅" else "❌"
        view.findViewById<TextView>(R.id.hotspot_perm_write_status).text =
            if (android.provider.Settings.System.canWrite(requireContext())) "✅" else "❌"
    }

    // TetheringManager.TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION（运行时反射，避免硬编码）
    private fun noChangeTetheringPermission(): Int = try {
        Class.forName("android.net.TetheringManager")
            .getField("TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION").getInt(null)
    } catch (_: Throwable) { -1 }

    // 单次尝试 startTethering（对齐源码 startTethering(type, exempt, showProvisioningUi=true)）
    private fun tryStartTethering(exempt: Boolean): Pair<Boolean, String> {
        var started = false
        var failed = false
        var failedCode = -1
        val latch = java.util.concurrent.CountDownLatch(1)
        return try {
            val tm = requireContext().getSystemService("tethering") ?: throw RuntimeException("no tether svc")
            val cbCls = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val exe = java.util.concurrent.Executors.newSingleThreadExecutor()
            val cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, a ->
                when (m.name) {
                    "onTetheringStarted" -> { started = true; appendLog("TetheringManager: 热点已启动 (onTetheringStarted)") }
                    "onTetheringFailed" -> {
                        failed = true
                        failedCode = a?.get(0) as? Int ?: -1
                        appendLog("TetheringManager: 热点启动失败 (onTetheringFailed code=$failedCode)")
                    }
                }
                try { latch.countDown() } catch (_: Throwable) { }
                null
            }
            val bCls = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = bCls.getConstructor(Int::class.java).newInstance(0) // TETHERING_WIFI
            try {
                bCls.getMethod("setExemptFromEntitlementCheck", Boolean::class.java).invoke(builder, exempt)
            } catch (_: Throwable) { }
            try {
                bCls.getMethod("setShouldShowEntitlementUi", Boolean::class.java).invoke(builder, true)
            } catch (_: Throwable) { }
            val req = bCls.getMethod("build").invoke(builder)
            tm.javaClass.getMethod("startTethering", req.javaClass, java.util.concurrent.Executor::class.java, cbCls)
                .invoke(tm, req, exe, cb)
            appendLog("TetheringManager.startTethering(wifi, exempt=$exempt, showUi=true) 已调用，等待系统回调 2s…")
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            if (started) true to "startTethering 成功（系统回调确认）"
            else false to (if (failed) "onTetheringFailed code=$failedCode" else "未收到系统回调")
        } catch (e: Exception) {
            appendLog("普通进程 startTethering 异常：${e.message}")
            false to "startTethering 异常 ${e.message}"
        }
    }

    // 普通进程关闭热点：getConnector → ITetheringConnector.stopTethering(type, opPackageName[, attributionTag], listener)
    // onResult(0)=成功（同 VPNHotspot 源码 stopTethering(type, context)）
    private fun stopViaConnector(): Pair<Boolean, String> {
        return try {
            val tm = requireContext().getSystemService("tethering") ?: throw RuntimeException("no tether svc")
            val itcCls = Class.forName("android.net.ITetheringConnector")
            var consumerCls: Class<*>? = null
            for (c in tm.javaClass.declaredClasses) {
                if (c.simpleName.contains("ConnectorConsumer")) { consumerCls = c; break }
            }
            consumerCls ?: throw RuntimeException("no IConnectorConsumer")
            val connectorRef = arrayOfNulls<Any>(1)
            val connLatch = java.util.concurrent.CountDownLatch(1)
            val consumer = java.lang.reflect.Proxy.newProxyInstance(
                consumerCls.classLoader, arrayOf(consumerCls)
            ) { _, m, a ->
                if (m.name == "onConnectorAvailable") { connectorRef[0] = a?.get(0); connLatch.countDown() }
                null
            }
            val getConn = tm.javaClass.declaredMethods.firstOrNull { it.name == "getConnector" }
                ?: throw RuntimeException("no getConnector")
            getConn.isAccessible = true
            getConn.invoke(tm, consumer)
            if (!connLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw RuntimeException("getConnector timeout")
            val connector = connectorRef[0] ?: throw RuntimeException("connector null")
            val irlCls = Class.forName("android.net.IIntResultListener")
            val res = intArrayOf(-1)
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                irlCls.classLoader, arrayOf(irlCls)
            ) { _, m, a ->
                if (m.name == "onResult") res[0] = a?.get(0) as? Int ?: -1
                null
            }
            val pkg = requireContext().packageName
            var stop: java.lang.reflect.Method? = null
            try {
                stop = itcCls.getMethod("stopTethering", Int::class.java, String::class.java, String::class.java, irlCls)
            } catch (_: NoSuchMethodException) { }
            if (stop == null) stop = itcCls.getMethod("stopTethering", Int::class.java, String::class.java, irlCls)
            if (stop.parameterCount == 4) {
                var attr: String? = null
                try { attr = requireContext().attributionTag } catch (_: Throwable) { }
                stop.invoke(connector, 0, pkg, attr, listener)
            } else {
                stop.invoke(connector, 0, pkg, listener)
            }
            Thread.sleep(1500)
            appendLog("connector.stopTethering → onResult=${res[0]}")
            if (res[0] == 0) true to "connector stopTethering 已生效" else false to "onResult=${res[0]}"
        } catch (e: Exception) {
            appendLog("普通进程 connector 关闭异常：${e.message}")
            false to "connector 异常 ${e.message}"
        }
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

    override fun onResume() {
        super.onResume()
        // 从系统授权页/权限弹窗返回后刷新
        refreshPerms()
        try {
            val icon = view?.findViewById<TextView>(R.id.hotspot_root_status)
            val hint = view?.findViewById<TextView>(R.id.hotspot_root_hint)
            if (icon != null && hint != null) {
                icon.text = "❌"
                hint.text = getString(R.string.vpn_hotspot_root_checking)
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = rootAvailable()
                    withContext(Dispatchers.Main) {
                        icon.text = if (ok) "✓" else "❌"
                        hint.text = if (ok) "Root" else "No Root"
                    }
                }
            }
        } catch (_: Exception) { }
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
        refreshPerms()
        refreshToggleState()
        refreshSystemState()
        registerSoftApMonitor()
        registerTetheringMonitor()
        updateHotspotInfo()

        view.findViewById<View>(R.id.hotspot_perm_nearby_btn).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    requireActivity().requestPermissions(
                        arrayOf("android.permission.NEARBY_WIFI_DEVICES"), 0x5A17
                    )
                } catch (_: Exception) { }
            }
        }
        view.findViewById<View>(R.id.hotspot_perm_write_btn).setOnClickListener {
            try {
                requireContext().startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    )
                )
            } catch (_: Exception) { }
        }

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
                        // 真关闭：只清转发；热点保持开着（关 VPN 热点后继续当普通热点用）
                        appendLog("VPN共享已关闭：转发已清理，热点保持开启")
                    }
                    withContext(Dispatchers.Main) {
                        when {
                            out == null -> {
                                // root 完全不可用
                                suppressToggle = true
                                toggleSwitch.isChecked = !checked
                                suppressToggle = false
                            }
                            out.contains("IFACE_MISS") -> {
                                // 转发接口未就绪：不误关开关（热点可能已开，等接口出现后重试转发）
                                appendLog("转发接口未就绪，等待接口出现后重试…")
                                if (checked) startVpnRetry() else stopVpnRetry()
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
                    // 结束后统一按实际热点状态刷新系统开关（防止"热点已开但按钮显示关"）
                    refreshSystemStateSafe()
                    activity?.runOnUiThread { updateHotspotInfo() }
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
