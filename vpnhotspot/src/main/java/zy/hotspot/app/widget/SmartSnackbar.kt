package zy.hotspot.app.widget

import android.annotation.SuppressLint
import android.os.Looper
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import zy.hotspot.app.App
import zy.hotspot.app.App.Companion.app
import zy.hotspot.app.util.Services
import zy.hotspot.app.util.readableMessage
import zy.hotspot.app.util.withRootHint

class SmartSnackbar private constructor(
    private val text: CharSequence,
) {
    internal class Request(
        val text: CharSequence,
        val actionText: CharSequence?,
        val action: (() -> Unit)?,
    )

    companion object {
        private var composeHandler: ((Request) -> Boolean)? = null

        fun make(@StringRes text: Int): SmartSnackbar = make(app.getText(text))
        fun make(text: CharSequence = "") = SmartSnackbar(text)
        // ZyBox: root shell 不可用时追加"没有root权限 请勿点击任何此页面功能"
        fun make(e: Throwable) = make(e.withRootHint(App.deviceStorage))

        @MainThread
        internal fun registerComposeHandler(
            handler: (Request) -> Boolean,
        ): AutoCloseable {
            composeHandler = handler
            return AutoCloseable { if (composeHandler === handler) composeHandler = null }
        }
    }

    private var actionText: CharSequence? = null
    private var action: (() -> Unit)? = null
    private var toastDuration = Toast.LENGTH_LONG

    fun show() {
        if (Looper.myLooper() == Looper.getMainLooper()) showOnMain() else Services.mainHandler.post(::showOnMain)
    }

    @MainThread
    private fun showOnMain() {
        if (composeHandler?.invoke(Request(text, actionText, action)) != true) {
            @SuppressLint("ShowToast")
            Toast.makeText(App.deviceStorage, text, toastDuration).show()
        }
    }

    fun action(@StringRes id: Int, listener: () -> Unit) {
        actionText = app.getText(id)
        action = listener
    }

    fun shortToast() = apply {
        toastDuration = Toast.LENGTH_SHORT
    }
}
