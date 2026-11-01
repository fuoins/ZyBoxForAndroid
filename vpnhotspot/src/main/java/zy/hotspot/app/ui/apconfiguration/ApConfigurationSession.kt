package zy.hotspot.app.ui.apconfiguration

import android.net.wifi.SoftApConfiguration
import android.net.wifi.p2p.WifiP2pGroup
import android.os.Build
import android.os.Parcelable
import androidx.compose.material3.SnackbarHostState
import zy.hotspot.app.App.Companion.app
import zy.hotspot.app.R
import zy.hotspot.app.net.monitor.TetherTimeoutMonitor
import zy.hotspot.app.net.wifi.SoftApConfigurationCompat
import zy.hotspot.app.net.wifi.SoftApConfigurationCompat.Companion.toCompat
import zy.hotspot.app.net.wifi.WifiApManager
import zy.hotspot.app.net.wifi.WifiSsidCompat
import zy.hotspot.app.root.RootManager
import zy.hotspot.app.root.WifiApCommands
import zy.hotspot.app.ui.showLongSnackbar
import zy.hotspot.app.util.getRootCause
import zy.hotspot.app.util.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import java.lang.reflect.InvocationTargetException

@Parcelize
data class ApConfigurationSession(
    val initial: SoftApConfigurationCompat,
    val target: ApConfigurationTarget,
    val readOnly: Boolean = false,
) : Parcelable

enum class ApConfigurationTarget {
    System,
    Temporary,
}

suspend fun loadSystemApConfiguration(snackbarHostState: SnackbarHostState): ApConfigurationSession? {
    return try {
        val config = if (Build.VERSION.SDK_INT < 30) @Suppress("DEPRECATION") {
            WifiApManager.configurationLegacy?.toCompat() ?: SoftApConfigurationCompat()
        } else WifiApManager.configuration.toCompat()
        ApConfigurationSession(config, ApConfigurationTarget.System)
    } catch (e: InvocationTargetException) {
        if (e.targetException !is SecurityException) Timber.w(e)
        try {
            val config = if (Build.VERSION.SDK_INT < 30) @Suppress("DEPRECATION") {
                RootManager.use { it.execute(WifiApCommands.GetConfigurationLegacy()) }?.toCompat()
                    ?: SoftApConfigurationCompat()
            } else RootManager.use { it.execute(WifiApCommands.GetConfiguration()) }.toCompat()
            ApConfigurationSession(config, ApConfigurationTarget.System)
        } catch (e: CancellationException) {
            throw e
        } catch (eRoot: Exception) {
            eRoot.addSuppressed(e)
            if (Build.VERSION.SDK_INT >= 30 || eRoot.getRootCause() !is SecurityException) Timber.w(eRoot)
            snackbarHostState.showLongSnackbar(eRoot.readableMessage)
            null
        }
    } catch (e: IllegalArgumentException) {
        Timber.w(e)
        snackbarHostState.showLongSnackbar(e.readableMessage)
        null
    }
}

suspend fun applySystemApConfiguration(
    configuration: SoftApConfigurationCompat,
    snackbarHostState: SnackbarHostState,
): Boolean {
    if (Build.VERSION.SDK_INT < 30) @Suppress("DEPRECATION") {
        if (configuration.isAutoShutdownEnabled != TetherTimeoutMonitor.enabled) try {
            TetherTimeoutMonitor.setEnabled(configuration.isAutoShutdownEnabled)
        } catch (e: Exception) {
            Timber.w(e)
            snackbarHostState.showLongSnackbar(e.readableMessage)
        }
        val wc = configuration.toWifiConfiguration()
        try {
            if (WifiApManager.setConfiguration(wc)) return true
        } catch (e: InvocationTargetException) {
            try {
                if (RootManager.use { it.execute(WifiApCommands.SetConfigurationLegacy(wc)) }.value) return true
            } catch (eCancel: CancellationException) {
                throw eCancel
            } catch (eRoot: Exception) {
                eRoot.addSuppressed(e)
                Timber.w(eRoot)
                snackbarHostState.showLongSnackbar(eRoot.readableMessage)
                return false
            }
        }
    } else {
        val platform = try {
            configuration.toPlatform()
        } catch (e: InvocationTargetException) {
            Timber.w(e)
            snackbarHostState.showLongSnackbar(e.readableMessage)
            return false
        }
        try {
            if (WifiApManager.setConfiguration(platform)) return true
        } catch (e: InvocationTargetException) {
            try {
                if (RootManager.use { it.execute(WifiApCommands.SetConfiguration(platform)) }.value) return true
            } catch (eCancel: CancellationException) {
                throw eCancel
            } catch (eRoot: Exception) {
                eRoot.addSuppressed(e)
                Timber.w(eRoot)
                snackbarHostState.showLongSnackbar(eRoot.readableMessage)
                return false
            }
        }
    }
    snackbarHostState.showLongSnackbar(app.getString(R.string.configuration_rejected))
    return false
}
