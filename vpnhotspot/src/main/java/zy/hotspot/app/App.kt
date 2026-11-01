package zy.hotspot.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.res.Configuration
import android.location.LocationManager
import android.os.Build
import android.os.ServiceSpecificException
import android.os.StrictMode
import android.os.SystemProperties
import android.os.ext.SdkExtensions
import android.provider.Settings
import android.system.Os
import android.util.Log
import android.widget.Toast
import androidx.annotation.Size
import androidx.annotation.StringRes
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.getSystemService
import be.mygod.librootkotlinx.NoShellException
import zy.hotspot.app.room.AppDatabase
import zy.hotspot.app.root.RootManager
import zy.hotspot.app.util.DeviceStorageApp
import zy.hotspot.app.util.InPlaceExecutor
import zy.hotspot.app.util.Services
import zy.hotspot.app.util.UnblockCentral
import zy.hotspot.app.util.getRootCause
import zy.hotspot.app.widget.SmartSnackbar
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class App private constructor(private val context: Context) {
    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var app: App
        lateinit var deviceStorage: Application

        private val initialized = AtomicBoolean(false)

        /**
         * 极轻量初始化：只就绪 deviceStorage（attachBaseContext 阶段调用，所有进程）。
         * 不做数据库迁移/通知/StrictMode 等重活（那些在 [init] 主进程 onCreate 阶段完成）。
         * 注意：不能在这里取 context.applicationContext（attachBaseContext 阶段可能未就绪），
         * 直接用调用方传入的 Application 实例（原版即用 this）。
         */
        fun ensureInit(context: Context) {
            if (::deviceStorage.isInitialized) return
            app = App(context)
            deviceStorage = DeviceStorageApp(context as Application)
        }

        fun init(context: Context) {
            ensureInit(context)
            if (initialized.getAndSet(true)) return
            // overhead of debug mode is minimal: https://github.com/Kotlin/kotlinx.coroutines/blob/f528898/docs/debugging.md#debug-mode
            System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
            @Suppress("DEPRECATION")
            deviceStorage.moveSharedPreferencesFrom(context,
                android.preference.PreferenceManager.getDefaultSharedPreferencesName(context))
            deviceStorage.moveDatabaseFrom(context, AppDatabase.DB_NAME)
            // 触发 hidden API 全局豁免（原版在首次访问 UnblockCentral 时执行，集成后必须显式提前触发，
            // 否则 SoftApCallback 等 linking 会被系统拒绝导致闪退）
            UnblockCentral.WifiManager_mService
            Services.init { deviceStorage }

            Timber.plant(object : Timber.DebugTree() {
                @SuppressLint("LogNotTimber")
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (t == null) {
                        if (priority != Log.DEBUG || BuildConfig.DEBUG) Log.println(priority, tag, message)
                    } else {
                        if (priority >= Log.WARN || priority == Log.DEBUG) Log.println(priority, tag, message)
                    }
                }
            })
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().apply {
                if (BuildConfig.DEBUG) detectAll() else detectNetwork()
            }.penaltyListener(InPlaceExecutor) { Timber.w(it, "StrictMode thread policy violation") }.build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().apply {
                if (BuildConfig.DEBUG) detectAll() else detectFileUriExposure()
            }.penaltyListener(InPlaceExecutor) { Timber.w(it, "StrictMode VM policy violation") }.build())
            ServiceNotification.updateNotificationChannels()
        }
    }

    /**
     * This method is used to log "expected" and well-handled errors, i.e. we care less about logs, etc.
     */
    fun logEvent(@Size(min = 1L, max = 40L) event: String, block: () -> Unit = { }) {
        Timber.i(event)
    }

    /**
     * LOH also requires location to be turned on. So does p2p for some reason. Source:
     * https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/53e0284/service/java/com/android/server/wifi/WifiServiceImpl.java#1204
     * https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/53e0284/service/java/com/android/server/wifi/WifiSettingsStore.java#228
     */
    inline fun <reified T> startServiceWithLocation(context: Context) {
        if (Build.VERSION.SDK_INT < 33 && location?.isLocationEnabled != true) try {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            Toast.makeText(context, R.string.tethering_location_off, Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            app.logEvent("location_settings")
            SmartSnackbar.make(R.string.tethering_location_off).show()
        } else context.startForegroundService(Intent(context, T::class.java))
    }

    // ---- Context 委托 ----
    val noBackupFilesDir get() = context.noBackupFilesDir
    val classLoader get() = context.classLoader
    val packageName get() = context.packageName
    val contentResolver get() = context.contentResolver
    val resources get() = context.resources
    val packageManager get() = context.packageManager
    val theme get() = context.theme
    fun getString(resId: Int) = context.getString(resId)
    fun getString(resId: Int, vararg formatArgs: Any) = context.getString(resId, *formatArgs)
    fun getText(@StringRes resId: Int): CharSequence = context.getText(resId)
    fun <T> getSystemService(serviceClass: Class<T>): T = context.getSystemService(serviceClass)
    fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?) =
        context.registerReceiver(receiver, filter)
    fun unregisterReceiver(receiver: android.content.BroadcastReceiver) =
        context.unregisterReceiver(receiver)
    fun createConfigurationContext(overrideConfiguration: Configuration) =
        context.createConfigurationContext(overrideConfiguration)

    val english by lazy {
        createConfigurationContext(Configuration(resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
    }
    @Suppress("DEPRECATION")
    val pref by lazy { android.preference.PreferenceManager.getDefaultSharedPreferences(deviceStorage) }
    val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    val location by lazy { getSystemService(LocationManager::class.java) }

    val hasTouch by lazy { packageManager.hasSystemFeature("android.hardware.faketouch") }
    val customTabsIntent by lazy {
        CustomTabsIntent.Builder().apply {
            setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(resources.getColor(R.color.light_colorPrimary, theme))
            }.build())
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(resources.getColor(R.color.dark_colorPrimary, theme))
            }.build())
        }.build()
    }
}
