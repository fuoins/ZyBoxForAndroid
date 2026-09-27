package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.MenuItem
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.IdRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.SubscriptionEntity
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPlay
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.utils.Util

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        if (themeResId !in intArrayOf(
                R.style.Theme_SagerNet_Black
            )
        ) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        }
        onBackPressedDispatcher.addCallback {
            if (supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
            }
        }

        binding.fab.setOnClickListener {
            if (DataStore.serviceState.canStop) SagerNet.stopService() else connect.launch(
                null
            )
        }
        binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }

        setContentView(binding.root)
        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        refreshNavMenu(DataStore.enableClashAPI)

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                //动态申请
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

        if (isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // ZyBox: 恢复自动初始化完成状态（持久化），仅首次弹初始化向导
        isAutoInitDone = DataStore.autoInitDone
        if (!DataStore.firstLaunchInitDone) {
            binding.root.post {
                showFirstLaunchDialog()
            }
        }
    }

    // ===== ZyBox: 首次初始化（弹窗 + 菜单查询页共用）=====

    // ZyBox: VPN 权限请求
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            initDialog?.let { d -> refreshInitDialog(d) }
            refreshPermissionFragment()
        }

    // ZyBox: 自动初始化完成标记（供查询页显示）
    @Volatile
    var isAutoInitDone = false
        private set
    @Volatile
    var autoInitRunning = false
        private set

    private var initDialog: androidx.appcompat.app.AlertDialog? = null

    fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(POST_NOTIFICATIONS), 1001)
        } else {
            initDialog?.let { d -> refreshInitDialog(d) }
            refreshPermissionFragment()
        }
    }

    fun requestVpnPermission() {
        VpnService.prepare(this)?.let { vpnPermission.launch(it) }
    }

    // ZyBox: 应用列表权限（分应用代理）——QUERY_ALL_PACKAGES 为安装即授普通权限，按钮点击刷新状态
    fun requestAppsPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.QUERY_ALL_PACKAGES
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            snackbar(R.string.zybox_apps_permission_granted).show()
        } else {
            try {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null)
                    )
                )
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
        initDialog?.let { d -> refreshInitDialog(d) }
        refreshPermissionFragment()
    }

    fun isAppsPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.QUERY_ALL_PACKAGES
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ZyBox: Root 权限检测（异步，su 可用性）
    fun isRootGranted(onResult: (Boolean) -> Unit) {
        Thread {
            val hasRoot = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val ok = p.inputStream.bufferedReader().readText().contains("uid=0")
                p.destroy()
                ok
            } catch (_: Exception) {
                false
            }
            runOnUiThread { onResult(hasRoot) }
        }.start()
    }

    // ZyBox: 路由规则一键全开（数据层全开 → 跳路由页展示 → 回主页）
    fun enableAllRoutingRules(onDone: () -> Unit) {
        runOnDefaultDispatcher {
            val rules = SagerDatabase.rulesDao.allRules()
            if (rules.isNotEmpty()) {
                rules.forEach { it.enabled = true }
                SagerDatabase.rulesDao.updateRules(rules)
            }
            onMainDispatcher {
                snackbar(R.string.zybox_route_all_done).show()
                displayFragmentWithId(R.id.nav_route)
                binding.root.postDelayed(onDone, 1600)
            }
        }
    }

    // ZyBox: 分应用代理绕过配置一键导入（内置配置，跳过首行开关标记，包名换行写入 individual）
    fun importBuiltinBypassApps() {
        val packages = BUILTIN_BYPASS_PACKAGES
        DataStore.individual = packages
        snackbar(R.string.zybox_apps_import_done).show()
        initDialog?.let { d -> refreshInitDialog(d) }
    }

    companion object {
        // ZyBox: 内置分应用代理绕过配置（首行 true 为开关标记，已剔除；包名换行分隔）
        const val BUILTIN_BYPASS_PACKAGES = "com.mfcloudcalculate.networkdisk\n" +
                "cn.cj.pe\n" +
                "com.fiveplay\n" +
                "com.android.purebilibili\n" +
                "com.moonshot.kimichat\n" +
                "org.localsend.localsend_app\n" +
                "com.fongmi.android.tv\n" +
                "com.tencent.mobileqq\n" +
                "com.suda.yzune.wakeupschedule\n" +
                "com.android.bankabc\n" +
                "com.icbc\n" +
                "com.chinamworld.main\n" +
                "com.greenpoint.android.mc10086.activity\n" +
                "com.chinamobile.mcloud\n" +
                "com.chinamworld.bocmbci\n" +
                "com.heytap.themestore\n" +
                "com.unionpay\n" +
                "com.tmri.app.main\n" +
                "com.bankcomm.Bankcomm\n" +
                "com.jingdong.app.mall\n" +
                "com.jd.jrapp\n" +
                "com.tencent.wework\n" +
                "com.tencent.hunyuan.app.chat\n" +
                "com.jlmobile\n" +
                "com.cmcc.cmvideo\n" +
                "tv.danmaku.bili\n" +
                "com.maimemo.android.momo\n" +
                "com.ktls.fileinfo\n" +
                "com.realtech.xiaocan\n" +
                "com.pingan.lifecircle\n" +
                "com.pingan.carowner\n" +
                "com.pingan.lifeinsurance\n" +
                "com.nowcasting.activity\n" +
                "com.webank.wemoney\n" +
                "com.tencent.mm\n" +
                "com.smile.gifmaker\n" +
                "com.uu898.uuhavequality\n" +
                "com.shanbay.kaoyan\n" +
                "com.ss.android.ugc.aweme\n" +
                "com.ss.android.ugc.livelite\n" +
                "com.ss.android.yumme.video\n" +
                "cmb.pb\n" +
                "com.xunmeng.pinduoduo\n" +
                "com.sohu.inputmethod.sogou.xiaomi\n" +
                "com.eg.android.AlipayGphone\n" +
                "cn.gov.pbc.dcep\n" +
                "moc.nauxuoyoaixoaix.www\n" +
                "com.taobao.taobao\n" +
                "com.taobao.litetao\n" +
                "me.ele\n" +
                "com.sdu.didi.psnger\n" +
                "com.tencent.gamehelper.smoba\n" +
                "com.plan.kot32.tomatotime\n" +
                "com.dragon.read\n" +
                "com.baidu.netdisk\n" +
                "com.baidu.input_oppo\n" +
                "com.tencent.map\n" +
                "com.cainiao.wireless\n" +
                "com.larus.nova\n" +
                "com.douban.frodo\n" +
                "com.wandoujia.phoenix2\n" +
                "com.heytap.market\n" +
                "com.coolapk.market\n" +
                "com.xt.retouch\n" +
                "com.MobileTicket\n" +
                "com.taobao.idlefish\n" +
                "io.legado.app.release\n" +
                "com.jxedt"
    }

    fun runAutoInit() {
        if (autoInitRunning) return
        if (!isAutoInitDone) {
            autoInitRunning = true
            // ZyBox: 初始化期间禁用"进入"按钮，防止未跑完就退出
            initDialog?.let { d ->
                d.findViewById<android.view.View>(R.id.init_btn_enter)?.isEnabled = false
                d.findViewById<android.widget.TextView>(R.id.init_btn_enter)?.text =
                    getString(R.string.zybox_init_enter_doing)
            }
            isAutoInitDone = true
            DataStore.autoInitDone = true
            // 自动申请所需权限
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(POST_NOTIFICATIONS), 1001)
            }
            if (VpnService.prepare(this) != null) {
                VpnService.prepare(this)?.let { vpnPermission.launch(it) }
            }
            // 自动进入设置页完成速度显示初始化，随后返回主页
            displayFragmentWithId(R.id.nav_settings)
            binding.root.postDelayed({
                autoInitRunning = false
                // 初始化跑完，统一刷新（4 项全完成才真正解锁进入按钮）
                initDialog?.let { d -> refreshInitDialog(d) }
                if (!isFinishing) displayFragmentWithId(R.id.nav_configuration)
            }, 1200)
        }
        initDialog?.let { d -> refreshInitDialog(d) }
        refreshPermissionFragment()
    }

    // ZyBox: 一键必须 / 一键所有(含可选)——复用自动初始化链路（通知+VPN+设置页+应用列表），
    // 一键所有额外执行路由规则全开与分应用代理绕过导入
    fun runOneClick(includeOptional: Boolean) {
        if (autoInitRunning) return
        runAutoInit()
        if (includeOptional) {
            binding.root.postDelayed({
                importBuiltinBypassApps()
                enableAllRoutingRules { if (!isFinishing) displayFragmentWithId(R.id.nav_configuration) }
            }, 1600)
        }
    }

    private fun showFirstLaunchDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_zybox_init, null)
        val progress = view.findViewById<android.widget.TextView>(R.id.init_progress)
        val statusAuto = view.findViewById<android.widget.TextView>(R.id.init_status_auto)
        val statusNotif = view.findViewById<android.widget.TextView>(R.id.init_status_notif)
        val statusVpn = view.findViewById<android.widget.TextView>(R.id.init_status_vpn)
        val statusApps = view.findViewById<android.widget.TextView>(R.id.init_status_apps)

        view.findViewById<android.view.View>(R.id.init_btn_auto).setOnClickListener {
            runAutoInit()
        }
        // ZyBox: 一键必须（4 项）与一键所有（含可选）
        view.findViewById<android.view.View>(R.id.init_btn_all_required).setOnClickListener {
            runOneClick(false)
        }
        view.findViewById<android.view.View>(R.id.init_btn_all_optional).setOnClickListener {
            runOneClick(true)
        }
        view.findViewById<android.view.View>(R.id.init_btn_notif).setOnClickListener {
            requestNotifPermission()
        }
        view.findViewById<android.view.View>(R.id.init_btn_vpn).setOnClickListener {
            requestVpnPermission()
        }
        view.findViewById<android.view.View>(R.id.init_btn_apps).setOnClickListener {
            requestAppsPermission()
        }
        // ZyBox: 可选 1——路由规则一键全开（全开 → 路由页 → 回主页）
        view.findViewById<android.view.View>(R.id.init_btn_route).setOnClickListener {
            enableAllRoutingRules {
                if (!isFinishing) displayFragmentWithId(R.id.nav_configuration)
            }
        }
        // ZyBox: 可选 2——分应用代理绕过配置一键导入
        view.findViewById<android.view.View>(R.id.init_btn_apps_import).setOnClickListener {
            importBuiltinBypassApps()
        }
        view.findViewById<android.view.View>(R.id.init_btn_enter).setOnClickListener {
            val done = initDoneCount()
            if (done < 4) {
                snackbar(getString(R.string.zybox_init_incomplete)).show()
                return@setOnClickListener
            }
            initDialog?.dismiss()
            initDialog = null
            DataStore.firstLaunchInitDone = true
        }

        initDialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(false)
            .show()
        refreshInitDialog(initDialog!!)
    }

    private fun initDoneCount(): Int {
        return (if (isAutoInitDone) 1 else 0) +
                (if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                        this, POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33
                ) 1 else 0) +
                (if (VpnService.prepare(this) == null) 1 else 0) +
                (if (isAppsPermissionGranted()) 1 else 0)
    }

    private fun refreshInitDialog(d: androidx.appcompat.app.AlertDialog) {
        val view = d.findViewById<android.widget.TextView>(R.id.init_progress) ?: return
        val done = initDoneCount()
        view.text = "$done/4"
        d.findViewById<android.widget.TextView>(R.id.init_status_auto)?.text =
            if (isAutoInitDone) "✅" else "❌"
        d.findViewById<android.widget.TextView>(R.id.init_status_notif)?.text =
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    this, POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) "❌" else "✅"
        d.findViewById<android.widget.TextView>(R.id.init_status_vpn)?.text =
            if (VpnService.prepare(this) == null) "✅" else "❌"
        d.findViewById<android.widget.TextView>(R.id.init_status_apps)?.text =
            if (isAppsPermissionGranted()) "✅" else "❌"
        // ZyBox: Root 权限（异步检测）
        if (d.findViewById<android.widget.TextView>(R.id.init_status_root) != null) {
            isRootGranted { granted ->
                d.findViewById<android.widget.TextView>(R.id.init_status_root)?.text =
                    if (granted) "✅" else "❌"
            }
        }
        // ZyBox: 4 项必须全做完才可进入（按钮锁定）；自动初始化执行期间也保持锁定
        val enterBtn = d.findViewById<android.view.View>(R.id.init_btn_enter)
        if (enterBtn != null) {
            enterBtn.isEnabled = done >= 4 && !autoInitRunning
            (enterBtn as? android.widget.TextView)?.text = getString(
                if (done >= 4 && !autoInitRunning) R.string.zybox_init_enter
                else R.string.zybox_init_enter_locked
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 || requestCode == 0) {
            initDialog?.let { d -> refreshInitDialog(d) }
            refreshPermissionFragment()
        }
    }

    private fun refreshPermissionFragment() {
        val f = supportFragmentManager.findFragmentById(R.id.fragment_holder)
        if (f is ZyBoxPermissionFragment) f.refreshStatus()
    }

    fun refreshNavMenu(clashApi: Boolean) {
        if (::navigation.isInitialized) {
            navigation.menu.findItem(R.id.nav_traffic)?.isVisible = clashApi
            navigation.menu.findItem(R.id.nav_tuiguang)?.isVisible = !isPlay
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            // ZyBox: 从剪切板/链接导入订阅——新分组名固定为"宇神神了"
            group.name = "宇神神了"
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        // ZyBox: 已有"宇神神了"分组则并入（追加为额外订阅，避免出现两个同名分组），否则新建
        val existing = SagerDatabase.groupDao.allGroups()
            .find { it.name == "宇神神了" && !it.ungrouped }
        if (existing != null) {
            val entity = SubscriptionEntity(
                name = null,
                groupId = existing.id,
                bean = subscription.subscription ?: SubscriptionBean().applyDefaultValues(),
            )
            val created = GroupManager.createSubscription(entity)
            GroupUpdater.startUpdate(created, true)
        } else {
            GroupManager.createGroup(subscription)
            GroupUpdater.startUpdate(subscription, true)
        }
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://matsuridayo.github.io/nb4a-plugin/")
            }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) binding.drawerLayout.closeDrawers() else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }


    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        if (fragment is ConfigurationFragment) {
            binding.stats.allowShow = true
            binding.fab.show()
        } else if (!DataStore.showBottomBar) {
            binding.stats.allowShow = false
            binding.stats.performHide()
            binding.fab.hide()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_traffic -> displayFragment(WebviewFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_faq -> {
                launchCustomTab("https://matsuridayo.github.io/")
                return false
            }

            R.id.nav_about -> displayFragment(AboutFragment())
            R.id.nav_zybox_about -> displayFragment(ZyBoxAboutFragment())
            R.id.nav_zybox_permission -> displayFragment(ZyBoxPermissionFragment())
            R.id.nav_zybox_optimization -> displayFragment(ZyBoxOptimizationFragment())
            R.id.nav_vpn_hotspot -> {
                startActivity(Intent(this, zy.hotspot.app.MainActivity::class.java))
                return false
            }
            R.id.nav_tuiguang -> {
                launchCustomTab("https://neko-box.pages.dev/喵")
                return false
            }

            else -> return false
        }
        navigation.menu.findItem(id).isChecked = true
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        DataStore.serviceState = state

        binding.fab.changeState(state, DataStore.serviceState, animate)
        binding.stats.changeState(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.isShown) {
                anchorView = binding.fab
            }
            // TODO
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
        autoTestOnConnect(state)
    }

    // 连接成功后自动测速（等效点击"已连接，点击此处自动测速"）。
    // 只在状态刚进入 Connected 时触发一次；延迟 2 秒等 VPN 隧道稳定后再测，
    // 避免刚建立隧道未就绪导致后台隧道测速超时（后台测速走真实线路，结果更真实）。
    private var lastStateForAutoTest: BaseService.State? = null
    private fun autoTestOnConnect(state: BaseService.State) {
        val last = lastStateForAutoTest
        lastStateForAutoTest = state
        if (state != BaseService.State.Connected || last == BaseService.State.Connected) return
        // ZyBox: 可在主页 ⋮ 菜单关闭"连接自动测速"
        if (DataStore.autoTestOnConnect) {
            lifecycleScope.launch {
                // ZyBox: 等待时间可在 ⋮ → 连接自动测速 → 等待时间 中自定义（默认 1ms）
                delay(DataStore.autoTestDelay.toLong())
                // ZyBox: 首次失败重试（默认开）——首次超时再测一次，第二次还超时不再测；
                // 断开重连后仍按首次处理（不永久记住失败）
                // 首次失败不弹超时错误，提示"再次测速一次"；重试成功提示"二次测试成功"，重试失败才弹错误
                val firstOk = binding.stats.testConnectionOnce(if (DataStore.autoTestRetry) 1 else 0)
                if (!firstOk && DataStore.autoTestRetry) {
                    delay(500)
                    binding.stats.testConnectionOnce(2)
                }
            }
        }
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
