package io.nekohasekai.sagernet

import android.annotation.SuppressLint
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import android.os.StrictMode
import android.os.UserManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import go.Seq
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isOss
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.*
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import libcore.Libcore
import moe.matsuri.nb4a.NativeInterface
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.cleanWebview
import java.io.File
import androidx.work.Configuration as WorkConfiguration

class SagerNet : Application(),
    WorkConfiguration.Provider {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        application = this

        // ZyBox: VPNHotspot 环境初始化提前到 attachBaseContext（所有进程无条件执行），
        // 确保 root/daemon 等早期代码能访问 deviceStorage（原版在 Application.attachBaseContext 初始化）
        zy.hotspot.app.App.ensureInit(this)
    }

    private val nativeInterface = NativeInterface()

    val externalAssets: File by lazy { getExternalFilesDir(null) ?: filesDir }
    val process: String = JavaUtil.getProcessName()
    private val isMainProcess = process == BuildConfig.APPLICATION_ID
    val isBgProcess = process.endsWith(":bg")

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler)

        if (isMainProcess || isBgProcess) {
            externalAssets.mkdirs()
            // ZyBox: 必须同步、且必须在 Libcore.initCore 之前完成。
            // sing-box 启动路由时会立刻打开 geosite.db，若此处仍在后台写文件，
            // 它会读到被截断的库并报 "parse rule-set: Type: EOF"，导致无法连接。
            copyGeoAssetsIfNeeded()
            // ZyBox: 集成 VPNHotspot（Tethering 页原样搬入），初始化其环境
            zy.hotspot.app.App.init(this)
            Seq.setContext(this)
            Libcore.initCore(
                process,
                cacheDir.absolutePath + "/",
                filesDir.absolutePath + "/",
                externalAssets.absolutePath + "/",
                DataStore.logBufSize,
                DataStore.logLevel > 0,
                nativeInterface, nativeInterface, LocalResolverImpl
            )

            // fix multi process issue in Android 9+
            JavaUtil.handleWebviewDir(this)

            runOnDefaultDispatcher {
                PackageCache.register()
                cleanWebview()
            }
        }

        if (isMainProcess) {
            // ZyBox: 「测速延迟持久化同步」改为默认关闭。
            // 老用户库里已经存了 true，仅改默认值对他们无效，这里做一次性迁移。
            if (DataStore.configurationStore.getBoolean(MIGRATION_SYNC_PING) != true) {
                DataStore.syncPingOnTest = false
                DataStore.syncPingFailed = false
                DataStore.configurationStore.putBoolean(MIGRATION_SYNC_PING, true)
            }

            Theme.apply(this)
            Theme.applyNightTheme()
            runOnDefaultDispatcher {
                DefaultNetworkListener.start(this) {
                    underlyingNetwork = it
                }

                updateNotificationChannels()
            }
        }

        if (BuildConfig.DEBUG) {
            System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotificationChannels()
    }

    override fun getWorkManagerConfiguration(): WorkConfiguration {
        return WorkConfiguration.Builder()
            .setDefaultProcessName("${BuildConfig.APPLICATION_ID}:bg")
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        Libcore.forceGc()
    }

    @SuppressLint("InlinedApi")
    companion object {

        // 完整 geo 库都是 3MB 级别，小于 1KB 只可能是上次写坏的空壳
        private const val MIN_GEO_SIZE = 1024L

        // 「测速延迟持久化同步」默认关闭的一次性迁移标记
        private const val MIGRATION_SYNC_PING = "zybox_sync_ping_default_off_v1"

        lateinit var application: SagerNet

        val isTv by lazy {
            uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }

        val configureIntent: (Context) -> PendingIntent by lazy {
            {
                PendingIntent.getActivity(
                    it,
                    0,
                    Intent(
                        application, MainActivity::class.java
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                )
            }
        }
        val activity by lazy { application.getSystemService<ActivityManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val user by lazy { application.getSystemService<UserManager>()!! }
        val uiMode by lazy { application.getSystemService<UiModeManager>()!! }
        val power by lazy { application.getSystemService<PowerManager>()!! }

        fun getClipboardText(): String {
            return clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString() ?: ""
        }

        fun trySetPrimaryClip(clip: String) = try {
            clipboard.setPrimaryClip(ClipData.newPlainText(null, clip))
            true
        } catch (e: RuntimeException) {
            Logs.w(e)
            false
        }

        fun updateNotificationChannels() {
            if (Build.VERSION.SDK_INT >= 26) @RequiresApi(26) {
                notification.createNotificationChannels(
                    listOf(
                        NotificationChannel(
                            "service-vpn",
                            application.getText(R.string.service_vpn),
                            if (Build.VERSION.SDK_INT >= 28) NotificationManager.IMPORTANCE_MIN
                            else NotificationManager.IMPORTANCE_LOW
                        ),   // #1355
                        NotificationChannel(
                            "service-proxy",
                            application.getText(R.string.service_proxy),
                            NotificationManager.IMPORTANCE_LOW
                        ), NotificationChannel(
                            "service-subscription",
                            application.getText(R.string.service_subscription),
                            NotificationManager.IMPORTANCE_DEFAULT
                        ), NotificationChannel(
                            "connection-test",
                            application.getText(R.string.connection_test),
                            NotificationManager.IMPORTANCE_DEFAULT
                        )
                    )
                )
            }
        }

        fun startService() = ContextCompat.startForegroundService(
            application, Intent(application, SagerConnection.serviceClass)
        )

        fun reloadService() =
            application.sendBroadcast(Intent(Action.RELOAD).setPackage(application.packageName))

        fun stopService() =
            application.sendBroadcast(Intent(Action.CLOSE).setPackage(application.packageName))

        var underlyingNetwork: Network? = null

        var appVersionNameForDisplay = {
            var n = BuildConfig.VERSION_NAME
            if (isPreview) {
                n += " " + BuildConfig.PRE_VERSION_NAME
            } else if (!isOss) {
                n += " ${BuildConfig.FLAVOR}"
            }
            if (BuildConfig.DEBUG) {
                n += " DEBUG"
            }
            n
        }()
    }

    // ZyBox: 首次启动把内置 geoip/geosite 数据库铺到外部 files 目录（sing-box 规则集的读取路径）。
    // 关键在于「原子」：先写 .tmp 再 rename，其它进程永远看不到写了一半的库；
    // 并用文件锁串行化，避免主进程与 :bg 进程同时截断同一个文件。
    private fun copyGeoAssetsIfNeeded() {
        val names = arrayOf("geoip.db", "geosite.db")
        val lockFile = File(externalAssets, ".geo.lock")
        try {
            java.io.RandomAccessFile(lockFile, "rw").use { raf ->
                val lock = raf.channel.lock()
                try {
                    for (name in names) {
                        val target = File(externalAssets, name)
                        // available() 对压缩 asset 返回解压后的总长度，
                        // 用它和磁盘文件对比，能识别「写到一半」的残库，而不只是空文件
                        val expected = try {
                            assets.open(name).use { it.available().toLong() }
                        } catch (e: Exception) {
                            -1L
                        }
                        if (expected > MIN_GEO_SIZE && target.length() == expected) continue
                        if (expected <= MIN_GEO_SIZE && target.length() > MIN_GEO_SIZE) continue

                        val tmp = File(externalAssets, "$name.tmp")
                        if (tmp.exists()) tmp.delete()
                        assets.open(name).use { input ->
                            java.io.FileOutputStream(tmp).use { output ->
                                input.copyTo(output)
                                output.fd.sync()
                            }
                        }
                        if (tmp.length() <= MIN_GEO_SIZE) {
                            tmp.delete()
                            continue
                        }
                        if (!tmp.renameTo(target)) {
                            target.delete()
                            tmp.renameTo(target)
                        }
                    }
                } finally {
                    lock.release()
                }
            }
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

}
