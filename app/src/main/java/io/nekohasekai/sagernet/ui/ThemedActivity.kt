package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.GlassUi
import io.nekohasekai.sagernet.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        Theme.applyNightTheme()

        super.onCreate(savedInstanceState)

        // Liquid Glass：透明系统栏 + 自适应图标明暗，让玻璃背景一直延伸到屏幕边缘
        GlassUi.applySystemBars(this)

        uiMode = resources.configuration.uiMode

        if (Build.VERSION.SDK_INT >= 35) {
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val top = bars.top
                findViewById<AppBarLayout>(R.id.appbar)?.apply {
                    updatePadding(top = top)
//                Logs.w("appbar $top")
                }
                // Liquid Glass：底部状态坞要浮在手势条之上，不能被系统导航区压住
                findViewById<android.view.View>(R.id.stats)?.let { stats ->
                    val lp = stats.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    if (lp != null) {
                        val margin = bars.bottom + (10 * resources.displayMetrics.density).toInt()
                        if (lp.bottomMargin != margin) {
                            lp.bottomMargin = margin
                            stats.layoutParams = lp
                        }
                    }
                }
//            findViewById<NavigationView>(R.id.nav_view)?.apply {
//                updatePadding(top = top)
//            }
                insets
            }
        }
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

}