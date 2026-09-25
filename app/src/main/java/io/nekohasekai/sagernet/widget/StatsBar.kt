package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var ipGeoText: TextView
    private lateinit var behavior: YourBehavior

    var allowShow = true

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideDown(child)
        }
    }


    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        ipGeoText = findViewById(R.id.ip_geo)
        super.setOnClickListener(l)
    }

    // 测速进行中时，不把状态文字覆盖为"已连接"
    var suppressConnectedText = false

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    fun changeState(state: BaseService.State) {
        val activity = context as MainActivity
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
            postWhenStarted {
                if (allowShow) performShow()
                // 自动测速期间不显示"已连接"，直接显示"测速中…"
                if (!suppressConnectedText) setStatus(app.getText(R.string.vpn_connected))
            }
            // ZyBox: 连接成功后右下角显示出口 IP 与 geoip 国家
            fetchIpGeo()
        } else {
            postWhenStarted {
                performHide()
            }
            ipGeoText.text = ""
            updateSpeed(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    // ZyBox: 查询当前出口 IP 与 geoip 国家（走 VPN 隧道，显示在右下角）
    private fun fetchIpGeo() {
        val activity = context as? MainActivity ?: return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("https://api.ip.sb/geoip").openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("User-Agent", "ZyBox/2.0")
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                val ip = obj.optString("ip", "")
                val cc = obj.optString("country_code", "")
                val flag = if (cc.length == 2) {
                    cc.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
                } else ""
                val text = "$ip  $flag$cc"
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    ipGeoText.text = text
                }
            } catch (_: Exception) {
                // 查询失败保持空白，不打扰用户
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun testConnection() {
        val activity = context as MainActivity
        isEnabled = false
        suppressConnectedText = true
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                // ZyBox: 后台隧道测速（service.urlTest，走真实连接线路，结果更真实）。
                // 自动测速已在连接后延迟 2 秒等待隧道稳定，避免刚建立时超时
                val elapsed = activity.urlTest()
                // ZyBox: 同步测速结果到节点列表的延迟显示（当前连接节点）
                // 可在主页 ⋮ 菜单关闭"连接延迟同步节点"
                if (DataStore.syncPingOnTest) {
                    val proxyId = DataStore.selectedProxy
                    if (proxyId > 0) {
                        val profile = SagerDatabase.proxyDao.getById(proxyId)
                        if (profile != null) {
                            profile.ping = elapsed
                            profile.status = 1
                            ProfileManager.updateProfile(profile)
                        }
                    }
                }
                onMainDispatcher {
                    isEnabled = true
                    suppressConnectedText = false
                    setStatus(
                        app.getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    )
                }

            } catch (e: Exception) {
                // ZyBox: 同↑+红色保留——失败结果(超时/不可用/连接重置)也持久化到当前节点
                if (DataStore.syncPingFailed) {
                    val proxyId = DataStore.selectedProxy
                    if (proxyId > 0) {
                        val profile = SagerDatabase.proxyDao.getById(proxyId)
                        if (profile != null) {
                            profile.ping = 0
                            profile.status = 3
                            profile.error = e.readableMessage
                            ProfileManager.updateProfile(profile)
                        }
                    }
                }
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    suppressConnectedText = false
                    setStatus(app.getText(R.string.connection_test_testing))

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

}
