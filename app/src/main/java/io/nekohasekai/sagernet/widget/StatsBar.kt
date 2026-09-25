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
import kotlinx.coroutines.withContext

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var ipText: TextView
    private lateinit var geoText: TextView
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
        ipText = findViewById(R.id.ip_text)
        geoText = findViewById(R.id.geo_text)
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
            ipText.text = ""
            geoText.text = ""
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

    // ZyBox: 查询当前出口 IP 与 geoip 国家（走 VPN 隧道，IP 与上传速度同高、国家中文名与延迟同高）
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
                val cc = obj.optString("country_code", "").uppercase()
                val flag = if (cc.length == 2) {
                    cc.map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
                } else ""
                val countryCn = countryNameCn(cc)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    ipText.text = ip
                    geoText.text = "$flag $countryCn"
                }
            } catch (_: Exception) {
                // 查询失败保持空白，不打扰用户
            }
        }
    }

    // ZyBox: 国家代码 → 中文名（常用国家）
    private fun countryNameCn(cc: String): String = when (cc) {
        "US" -> "美国"; "GB" -> "英国"; "JP" -> "日本"; "DE" -> "德国"; "FR" -> "法国"
        "SG" -> "新加坡"; "HK" -> "中国香港"; "TW" -> "中国台湾"; "KR" -> "韩国"; "CA" -> "加拿大"
        "AU" -> "澳大利亚"; "NL" -> "荷兰"; "RU" -> "俄罗斯"; "IN" -> "印度"; "BR" -> "巴西"
        "IT" -> "意大利"; "ES" -> "西班牙"; "TH" -> "泰国"; "VN" -> "越南"; "MY" -> "马来西亚"
        "ID" -> "印度尼西亚"; "PH" -> "菲律宾"; "TR" -> "土耳其"; "AE" -> "阿联酋"; "SA" -> "沙特阿拉伯"
        "IL" -> "以色列"; "PL" -> "波兰"; "SE" -> "瑞典"; "CH" -> "瑞士"; "BE" -> "比利时"
        "AT" -> "奥地利"; "IE" -> "爱尔兰"; "PT" -> "葡萄牙"; "DK" -> "丹麦"; "NO" -> "挪威"
        "FI" -> "芬兰"; "GR" -> "希腊"; "MX" -> "墨西哥"; "AR" -> "阿根廷"; "CL" -> "智利"
        "CO" -> "哥伦比亚"; "ZA" -> "南非"; "EG" -> "埃及"; "UA" -> "乌克兰"; "KZ" -> "哈萨克斯坦"
        "NZ" -> "新西兰"; "MO" -> "中国澳门"; "CN" -> "中国"; "CZ" -> "捷克"; "HU" -> "匈牙利"
        "RO" -> "罗马尼亚"; "BG" -> "保加利亚"; "HR" -> "克罗地亚"; "RS" -> "塞尔维亚"; "SI" -> "斯洛文尼亚"
        "SK" -> "斯洛伐克"; "EE" -> "爱沙尼亚"; "LT" -> "立陶宛"; "LV" -> "拉脱维亚"; "LU" -> "卢森堡"
        "IS" -> "冰岛"; "MT" -> "马耳他"; "CY" -> "塞浦路斯"; "QA" -> "卡塔尔"; "KW" -> "科威特"
        "OM" -> "阿曼"; "BH" -> "巴林"; "JO" -> "约旦"; "LB" -> "黎巴嫩"; "IR" -> "伊朗"
        "IQ" -> "伊拉克"; "PK" -> "巴基斯坦"; "BD" -> "孟加拉国"; "LK" -> "斯里兰卡"; "NP" -> "尼泊尔"
        "MM" -> "缅甸"; "KH" -> "柬埔寨"; "LA" -> "老挝"; "MN" -> "蒙古"; "UZ" -> "乌兹别克斯坦"
        "GE" -> "格鲁吉亚"; "AM" -> "亚美尼亚"; "AZ" -> "阿塞拜疆"; "BY" -> "白俄罗斯"; "MD" -> "摩尔多瓦"
        "BA" -> "波黑"; "MK" -> "北马其顿"; "AL" -> "阿尔巴尼亚"; "ME" -> "黑山"; "PE" -> "秘鲁"
        "VE" -> "委内瑞拉"; "EC" -> "厄瓜多尔"; "BO" -> "玻利维亚"; "PY" -> "巴拉圭"; "UY" -> "乌拉圭"
        "PA" -> "巴拿马"; "CR" -> "哥斯达黎加"; "GT" -> "危地马拉"; "HN" -> "洪都拉斯"; "NI" -> "尼加拉瓜"
        "SV" -> "萨尔瓦多"; "DO" -> "多米尼加"; "CU" -> "古巴"; "JM" -> "牙买加"; "PR" -> "波多黎各"
        "NG" -> "尼日利亚"; "KE" -> "肯尼亚"; "MA" -> "摩洛哥"; "DZ" -> "阿尔及利亚"; "TN" -> "突尼斯"
        "ET" -> "埃塞俄比亚"; "GH" -> "加纳"; "TZ" -> "坦桑尼亚"; "UG" -> "乌干达"; "ZW" -> "津巴布韦"
        "MZ" -> "莫桑比克"; "AO" -> "安哥拉"; "GE" -> "格鲁吉亚"
        else -> cc
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
        activity.lifecycleScope.launch {
            testConnectionOnce()
        }
    }

    // ZyBox: 单次测速（挂起，返回是否成功）。手动点击/连接自动测速/首次失败重试都走这里。
    // 后台隧道测速（service.urlTest，走真实连接线路，结果更真实）
    suspend fun testConnectionOnce(): Boolean {
        val activity = context as MainActivity
        isEnabled = false
        suppressConnectedText = true
        setStatus(app.getText(R.string.connection_test_testing))
        return withContext(Dispatchers.IO) {
            try {
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
                true
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
                false
            }
        }
    }

}
