package zy.hotspot.app.root

import android.content.Context
import android.os.RemoteException
import android.provider.Settings
import be.mygod.librootkotlinx.RootCommandNoResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import zy.hotspot.app.util.Services
import kotlinx.parcelize.Parcelize

@Parcelize
data class Dump(val path: String) : RootCommandNoResult {
    companion object {
        private const val DUMPSYS = "/system/bin/dumpsys"
        private const val IP = "/system/bin/ip"
        private const val IPTABLES = "/system/bin/iptables"
        const val LOGCAT = "/system/bin/logcat"
    }

    override suspend fun execute() = withContext(Dispatchers.IO) {
        val output = File(path)
        val process = ProcessBuilder("/system/bin/sh").apply {
            redirectErrorStream(true)
            redirectOutput(ProcessBuilder.Redirect.appendTo(output))
        }.start()
        val script = """
                    |echo
                    |echo dumpsys ${Context.WIFI_P2P_SERVICE}
                    |$DUMPSYS ${Context.WIFI_P2P_SERVICE}
                    |echo
                    |echo dumpsys ${Context.CONNECTIVITY_SERVICE} tethering
                    |$DUMPSYS ${Context.CONNECTIVITY_SERVICE} tethering
                    |echo
                    |echo iptables-save
                    |/system/bin/iptables-save
                    |echo
                    |echo ip6tables-save
                    |/system/bin/ip6tables-save
                    |echo
                    |echo ip rule
                    |$IP rule
                    |echo
                    |echo ip route show table all
                    |$IP route show table all
                    |echo
                    |echo ip neigh
                    |$IP neigh
                    |echo
                    |echo ip -s link
                    |$IP -s link
                    |echo
                    |echo iptables -t nat -nvx -L POSTROUTING
                    |$IPTABLES -w -t nat -nvx -L POSTROUTING
                    |echo
                    |echo iptables -t nat -nvx -L vpnhotspot_masquerade
                    |$IPTABLES -w -t nat -nvx -L vpnhotspot_masquerade
                    |echo
                    |echo iptables -nvx -L vpnhotspot_acl
                    |$IPTABLES -w -nvx -L vpnhotspot_acl
                    |echo
                    |echo iptables -nvx -L vpnhotspot_stats
                    |$IPTABLES -w -nvx -L vpnhotspot_stats
                    |echo
                    |echo logcat-su
                    |$LOGCAT -d
                """.trimMargin()
        try {
            process.outputStream.use {
                it.write(script.encodeToByteArray())
                it.flush()
            }
            val exit = process.waitFor()
            if (exit != 0) output.appendText("Process exited with $exit")
        } finally {
            process.destroy()
        }
        null
    }
}

@Parcelize
data class SettingsGlobalPut(val name: String, val value: String) : RootCommandNoResult {
    companion object {
        suspend fun int(name: String, value: Int) {
            try {
                check(Settings.Global.putInt(Services.context.contentResolver, name, value))
            } catch (e: SecurityException) {
                try {
                    RootManager.use { it.execute(SettingsGlobalPut(name, value.toString())) }
                } catch (eRoot: Exception) {
                    eRoot.addSuppressed(e)
                    throw eRoot
                }
            }
        }
    }

    override suspend fun execute() = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("/system/bin/settings", "put", "global", name, value).apply {
            redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        }.start()
        val output = process.inputStream.readBytes().decodeToString() + process.errorStream.readBytes().decodeToString()
        val exit = process.waitFor()
        if (exit != 0 || output.isNotEmpty()) throw RemoteException("Process exited with $exit: $output")
        null
    }
}
